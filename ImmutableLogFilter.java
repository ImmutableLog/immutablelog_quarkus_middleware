import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

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

        emit(requestId, eventName, type, method, path, status, latencyMs, req, null);
    }

    private void emit(String requestId, String eventName, String type,
                      String method, String path, int status,
                      long latencyMs, ContainerRequestContext req, Throwable ex) {
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("method", method);
            payloadMap.put("path", path);
            payloadMap.put("status", status);
            payloadMap.put("latency_ms", latencyMs);
            payloadMap.put("client_ip", getClientIp(req));
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
            // promote it to source.ip; "unknown" is not an IP, so it is not worth sending.
            String clientIp = getClientIp(req);
            if (clientIp != null && !clientIp.isBlank() && !clientIp.equals("unknown")) {
                meta.put("client_ip", clientIp);
            }

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

    private String getClientIp(ContainerRequestContext req) {
        String xff = req.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        // UriInfo doesn't expose remote addr in JAX-RS; inject @Context HttpServletRequest if needed
        return "unknown";
    }
}
