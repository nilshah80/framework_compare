package benchmark.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Provider
@Priority(500)
public class LoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );
    private static final String START_TIME_PROP = "startTime";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestContext.setProperty(START_TIME_PROP, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        long startTime = (long) requestContext.getProperty(START_TIME_PROP);
        long elapsed = System.nanoTime() - startTime;
        double latencyMs = elapsed / 1_000_000.0;

        Object requestId = requestContext.getProperty(RequestIdFilter.REQUEST_ID_PROP);
        String reqId = requestId != null ? requestId.toString() : "";

        Map<String, Object> reqHeaders = redactHeaders(requestContext.getHeaders());

        Map<String, String> queryParams = new HashMap<>();
        requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                queryParams.put(k, v.getFirst());
            }
        });

        Map<String, Object> respHeaders = redactHeaders(responseContext.getHeaders());

        String responseBody = "";
        int bytesOut = 0;
        if (responseContext.getEntity() != null) {
            try {
                String body = MAPPER.writeValueAsString(responseContext.getEntity());
                responseBody = body;
                bytesOut = body.getBytes().length;
            } catch (Exception ignored) {}
        }

        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("level", "INFO");
        logEntry.put("message", "http_dump");
        logEntry.put("request_id", reqId);
        logEntry.put("method", requestContext.getMethod());
        logEntry.put("path", requestContext.getUriInfo().getPath());
        logEntry.put("query", queryParams);
        // Client IP from X-Forwarded-For
        String clientIp = requestContext.getHeaderString("X-Forwarded-For");
        if (clientIp != null && !clientIp.isBlank()) {
            clientIp = clientIp.split(",")[0].trim();
        } else {
            clientIp = "";
        }
        logEntry.put("client_ip", clientIp);
        logEntry.put("user_agent", requestContext.getHeaderString("User-Agent"));
        logEntry.put("request_headers", reqHeaders);
        logEntry.put("request_body", "");
        logEntry.put("status", responseContext.getStatus());
        logEntry.put("latency", formatLatency(elapsed));
        logEntry.put("latency_ms", latencyMs);
        logEntry.put("response_headers", respHeaders);
        logEntry.put("response_body", responseBody);
        logEntry.put("bytes_out", bytesOut);

        try {
            System.out.println(MAPPER.writeValueAsString(logEntry));
        } catch (Exception ignored) {}
    }

    private Map<String, Object> redactHeaders(Map<String, ? extends List<?>> headers) {
        Map<String, Object> result = new HashMap<>();
        if (headers != null) {
            headers.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    String value = values.getFirst().toString();
                    if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                        result.put(key, "[REDACTED]");
                    } else {
                        result.put(key, value);
                    }
                }
            });
        }
        return result;
    }

    private String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
