import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Provider
@Priority(Priorities.USER - 100)
public class ImmutableLogFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @ConfigProperty(name = "immutablelog.api-key")
    String apiKey;

    @ConfigProperty(name = "immutablelog.service-name", defaultValue = "my-service")
    String serviceName;

    @ConfigProperty(name = "immutablelog.env", defaultValue = "production")
    String env;

    @ConfigProperty(name = "immutablelog.url", defaultValue = "https://api.immutablelog.com")
    String apiUrl;

    // RESTEasy Reactive runs on Vert.x (there is no servlet API): the connection
    // address comes from the Vert.x RoutingContext of the current request.
    @Inject
    RoutingContext routingContext;

    private static final String START_KEY = "imtbl.startedAt";
    private static final String REQ_ID_KEY = "imtbl.requestId";
    private static final Set<String> SKIP_PATHS = Set.of("/health", "/q/health", "/ping");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public void filter(ContainerRequestContext req) {
        String path = req.getUriInfo().getPath();
        // getUriInfo().getPath() comes back without a leading slash (e.g. "q/health"),
        // but SKIP_PATHS entries have one — normalize before matching, or health checks
        // never match and end up audited/billed like any other request.
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (SKIP_PATHS.stream().anyMatch(normalizedPath::startsWith)) return;

        req.setProperty(START_KEY, System.currentTimeMillis());
        req.setProperty(REQ_ID_KEY, UUID.randomUUID().toString());
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        Long startedAt = (Long) req.getProperty(START_KEY);
        if (startedAt == null) return; // skipped

        String requestId = (String) req.getProperty(REQ_ID_KEY);
        long latencyMs = System.currentTimeMillis() - startedAt;
        int status = res.getStatus();
        String method = req.getMethod();
        String path = req.getUriInfo().getPath();

        String customEvent = (String) req.getProperty("imtbl.eventName");
        String eventName = customEvent != null
            ? customEvent
            : method.toLowerCase() + "." + path.replace("/", ".").replaceAll("^\\.+", "");

        String type = status >= 500 ? "error"
            : status >= 400 ? "error"
            : status >= 300 ? "info"
            : "success";

        emit(requestId, eventName, type, method, path, status, latencyMs, req, getThrowable(req));
    }

    private void emit(String requestId, String eventName, String type,
                      String method, String path, int status,
                      long latencyMs, ContainerRequestContext req, Throwable ex) {
        try {
            // Bare IP (no port) or null; when null, client_ip is left out of payload and meta.
            String clientIp = getClientIp(req);

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("method", method);
            payloadMap.put("path", path);
            payloadMap.put("status", status);
            payloadMap.put("latency_ms", latencyMs);
            if (clientIp != null) payloadMap.put("client_ip", clientIp);
            payloadMap.put("user_agent", req.getHeaderString("User-Agent"));

            if (ex != null) {
                Map<String, Object> errMap = new LinkedHashMap<>();
                errMap.put("type", ex.getClass().getName());
                errMap.put("message", ex.getMessage());
                errMap.put("retryable", !(ex instanceof IllegalArgumentException));
                payloadMap.put("error", errMap);
            }

            // Besides the middleware's own keys, this carries the reserved observability keys
            // of the contract (service, environment, client_ip, http_method, http_status,
            // http_route, log_level, error_type, error_message). All of them are strings:
            // the core only accepts meta as a string -> string map.
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", type);
            meta.put("event_name", eventName);
            meta.put("service", serviceName);
            meta.put("request_id", requestId);
            // "env" is the legacy name, kept because there may be clients in production
            // reading it; "environment" is the canonical name of the contract. Same value.
            meta.put("env", env);
            meta.put("environment", env);
            meta.put("http_method", method);
            meta.put("http_status", String.valueOf(status));
            meta.put("http_route", getHttpRoute(path));
            meta.put("log_level", logLevelFrom(type));

            // client_ip has to travel in meta (not only in the payload) so the platform can
            // promote it to source.ip. Only a bare IP is promoted, so nothing else is sent.
            if (clientIp != null) meta.put("client_ip", clientIp);

            if (ex != null) {
                meta.put("error_type", ex.getClass().getName());
                String errMessage = ex.getMessage();
                if (errMessage != null && !errMessage.isBlank()) {
                    meta.put("error_message", truncate(errMessage, 500));
                }
            }

            Map<String, Object> body = Map.of(
                "payload", mapper.writeValueAsString(payloadMap),
                "meta", meta
            );

            String json = mapper.writeValueAsString(body);

            var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/v1/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            http.sendAsync(httpReq, HttpResponse.BodyHandlers.discarding());

        } catch (Exception ignored) {}
    }

    /**
     * Route for meta.http_route. Known limitation: this is the CONCRETE path
     * (e.g. "/orders/42"), not the template ("/orders/{id}") — a JAX-RS
     * ContainerRequestContext/UriInfo does not expose the matched template here
     * (getMatchedURIs() also returns concrete URIs). Inject @Context ResourceInfo and
     * read the @Path annotations of class + method if the template is needed; be aware
     * that the concrete path raises the cardinality of the field on the platform side.
     */
    private String getHttpRoute(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Maps the event type to meta.log_level (ECS log.level). */
    private String logLevelFrom(String kind) {
        return "error".equals(kind) ? "error"
            : "warning".equals(kind) ? "warn"
            : "debug".equals(kind) ? "debug"
            : "info"; // info, success, audit
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * Exception thrown by the resource and already turned into a response (WebApplicationException
     * or an ExceptionMapper of the app), or null. A ContainerResponseFilter does not receive it, so
     * it is read from the RESTEasy Reactive request context, which keeps it after mapping. Read
     * only: the response, its status and the app's mappers are untouched. An exception no mapper
     * handles never reaches this filter (Quarkus answers 500 outside JAX-RS), so it has no event.
     */
    private Throwable getThrowable(ContainerRequestContext req) {
        if (!(req instanceof ResteasyReactiveContainerRequestContext)) return null;
        Object ctx = ((ResteasyReactiveContainerRequestContext) req).getServerRequestContext();
        return ctx instanceof AbstractResteasyReactiveContext
            ? ((AbstractResteasyReactiveContext<?, ?>) ctx).getThrowable()
            : null;
    }

    /**
     * Client IP for client_ip: the first X-Forwarded-For entry when it is a valid IP, otherwise
     * the IP of the connection (Vert.x remoteAddress().host()). Returns null when neither is a
     * valid IP. X-Forwarded-For is only trustworthy behind a proxy/ALB that overwrites it:
     * without one, any caller can forge it.
     */
    private String getClientIp(ContainerRequestContext req) {
        String xff = req.getHeaderString("X-Forwarded-For");
        if (xff != null) {
            String ip = bareIp(xff.split(",")[0]);
            if (ip != null) return ip;
        }
        try {
            var remote = routingContext.request().remoteAddress();
            return remote == null ? null : bareIp(remote.host());
        } catch (RuntimeException e) {
            return null; // no active Vert.x request (e.g. outside the HTTP layer)
        }
    }

    private static final Pattern IPV4 = Pattern.compile(
        "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    /**
     * Trims v, strips a port ("10.0.0.1:8080", "[2001:db8::1]:8080") or brackets, and returns
     * the bare IP, or null if it is not one. Never resolves a host name: IPv4 is matched by
     * pattern, and InetAddress only sees strings with ':' made of hex digits, which it parses
     * as an IPv6 literal (or rejects) without DNS.
     */
    static String bareIp(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            String rest = end < 0 ? null : v.substring(end + 1);
            if (rest == null || !(rest.isEmpty() || rest.matches(":\\d+"))) return null;
            v = v.substring(1, end);
        } else if (v.indexOf(':') > 0 && v.indexOf(':') == v.lastIndexOf(':')) {
            v = v.substring(0, v.indexOf(':'));
        }
        if (IPV4.matcher(v).matches()) return v;
        if (v.indexOf(':') < 0 || !IPV6_CHARS.matcher(v).matches()) return null;
        try {
            InetAddress.getByName(v);
            return v;
        } catch (Exception e) {
            return null;
        }
    }
}
