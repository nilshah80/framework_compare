package benchmark.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

@Filter("/**")
public class MiddlewareFilter implements HttpServerFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        long startTime = System.nanoTime();

        // Request ID
        String requestId = request.getHeaders().get("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute("requestId", requestId);
        final String reqId = requestId;

        return Flux.from(chain.proceed(request))
                .map(response -> {
                    // Request ID header
                    response.header("X-Request-ID", reqId);

                    // CORS
                    response.header("Access-Control-Allow-Origin", "*");
                    response.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
                    response.header("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization");

                    // Security headers
                    response.header("X-XSS-Protection", "1; mode=block");
                    response.header("X-Content-Type-Options", "nosniff");
                    response.header("X-Frame-Options", "DENY");
                    response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                    response.header("Content-Security-Policy", "default-src 'self'");
                    response.header("Referrer-Policy", "strict-origin-when-cross-origin");
                    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
                    response.header("Cross-Origin-Opener-Policy", "same-origin");

                    // Structured logging
                    long elapsed = System.nanoTime() - startTime;
                    double latencyMs = elapsed / 1_000_000.0;

                    Map<String, Object> reqHeaders = redactHeaders(request.getHeaders());
                    Map<String, String> queryParams = new HashMap<>();
                    request.getParameters().forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) {
                            queryParams.put(k, v.getFirst());
                        }
                    });

                    Map<String, Object> respHeaders = new HashMap<>();
                    response.getHeaders().forEachValue((key, value) -> {
                        if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                            respHeaders.put(key, "[REDACTED]");
                        } else {
                            respHeaders.put(key, value);
                        }
                    });

                    String responseBody = "";
                    int bytesOut = 0;
                    if (response.body() != null) {
                        try {
                            responseBody = MAPPER.writeValueAsString(response.body());
                            bytesOut = responseBody.getBytes().length;
                        } catch (Exception ignored) {}
                    }

                    Map<String, Object> logEntry = new LinkedHashMap<>();
                    logEntry.put("level", "INFO");
                    logEntry.put("message", "http_dump");
                    logEntry.put("request_id", reqId);
                    logEntry.put("method", request.getMethodName());
                    logEntry.put("path", request.getPath());
                    logEntry.put("query", queryParams);
                    // Client IP from X-Forwarded-For
                    String xff = request.getHeaders().get("X-Forwarded-For");
                    String clientIp;
                    if (xff != null && !xff.isBlank()) {
                        clientIp = xff.split(",")[0].trim();
                    } else {
                        clientIp = request.getRemoteAddress().getAddress().getHostAddress();
                    }
                    logEntry.put("client_ip", clientIp);
                    logEntry.put("user_agent", request.getHeaders().get("User-Agent"));
                    logEntry.put("request_headers", reqHeaders);
                    logEntry.put("request_body", "");
                    logEntry.put("status", response.code());
                    logEntry.put("latency", formatLatency(elapsed));
                    logEntry.put("latency_ms", latencyMs);
                    logEntry.put("response_headers", respHeaders);
                    logEntry.put("response_body", responseBody);
                    logEntry.put("bytes_out", bytesOut);

                    try {
                        System.out.println(MAPPER.writeValueAsString(logEntry));
                    } catch (Exception ignored) {}

                    return response;
                })
                .onErrorResume(throwable -> {
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    System.err.println(sw);
                    MutableHttpResponse<?> errorResponse = HttpResponse.serverError()
                            .body(Map.of("error", "Internal Server Error"))
                            .contentType(MediaType.APPLICATION_JSON_TYPE);
                    errorResponse.header("X-Request-ID", reqId);
                    return (Publisher) Mono.just(errorResponse);
                });
    }

    private Map<String, Object> redactHeaders(HttpHeaders headers) {
        Map<String, Object> result = new HashMap<>();
        headers.forEachValue((key, value) -> {
            if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                result.put(key, "[REDACTED]");
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    private String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
