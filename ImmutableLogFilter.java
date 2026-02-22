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
        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) return;

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

            Map<String, Object> meta = Map.of(
                "type", type,
                "event_name", eventName,
                "service", serviceName,
                "request_id", requestId,
                "env", env
            );

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

    private String getClientIp(ContainerRequestContext req) {
        String xff = req.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        // UriInfo doesn't expose remote addr in JAX-RS; inject @Context HttpServletRequest if needed
        return "unknown";
    }
}
