package benchmark.filter;

import benchmark.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@Order(1)
public class CombinedWebFilter implements WebFilter {

    private static final Set<String> PII_HEADERS = Set.of(
        "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );
    private static final long MAX_BODY_SIZE = 1_048_576;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        // Request ID
        String requestId = request.getHeaders().getFirst("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        final String rid = requestId;
        exchange.getAttributes().put("requestId", rid);
        response.getHeaders().set("X-Request-ID", rid);

        // CORS
        response.getHeaders().set("Access-Control-Allow-Origin", "*");
        response.getHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
        response.getHeaders().set("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization");

        // Security Headers
        response.getHeaders().set("X-XSS-Protection", "1; mode=block");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("X-Frame-Options", "DENY");
        response.getHeaders().set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.getHeaders().set("Content-Security-Policy", "default-src 'self'");
        response.getHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        response.getHeaders().set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        response.getHeaders().set("Cross-Origin-Opener-Policy", "same-origin");

        // Body Limit
        long contentLength = request.getHeaders().getContentLength();
        if (contentLength > MAX_BODY_SIZE) {
            response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"Request body too large\"}".getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        }

        // Capture response body for logging
        StringBuilder responseBody = new StringBuilder();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux<? extends DataBuffer> fluxBody) {
                    return super.writeWith(fluxBody.map(dataBuffer -> {
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        DataBufferUtils.release(dataBuffer);
                        responseBody.append(new String(content, StandardCharsets.UTF_8));
                        return exchange.getResponse().bufferFactory().wrap(content);
                    }));
                } else if (body instanceof Mono) {
                    return super.writeWith(Mono.from(body).map(dataBuffer -> {
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        DataBufferUtils.release(dataBuffer);
                        responseBody.append(new String(content, StandardCharsets.UTF_8));
                        return exchange.getResponse().bufferFactory().wrap(content);
                    }));
                }
                return super.writeWith(body);
            }
        };

        ServerWebExchange mutatedExchange = exchange.mutate().response(decoratedResponse).build();
        mutatedExchange.getAttributes().put("requestId", rid);

        return chain.filter(mutatedExchange)
            .doOnError(ex -> {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                System.out.println("{\"level\":\"error\",\"error\":\"" + escapeJson(ex.getMessage()) +
                    "\",\"stack\":\"" + escapeJson(sw.toString()) + "\"}");
            })
            .onErrorResume(ex -> {
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] errBody = "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
                DataBuffer buffer = response.bufferFactory().wrap(errBody);
                return response.writeWith(Mono.just(buffer));
            })
            .doFinally(signalType -> {
                try {
                    long latencyNs = System.nanoTime() - start;
                    double latencyMs = latencyNs / 1_000_000.0;
                    HttpStatus status = (HttpStatus) response.getStatusCode();
                    int statusCode = status != null ? status.value() : 0;

                    // Build query params as a map
                    Map<String, String> queryParams = new LinkedHashMap<>();
                    String rawQuery = request.getURI().getQuery();
                    if (rawQuery != null && !rawQuery.isEmpty()) {
                        for (String param : rawQuery.split("&")) {
                            String[] kv = param.split("=", 2);
                            queryParams.put(kv[0], kv.length > 1 ? kv[1] : "");
                        }
                    }

                    // Build request headers map
                    Map<String, String> reqHeaders = new LinkedHashMap<>();
                    for (Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
                        String name = entry.getKey();
                        if (PII_HEADERS.contains(name.toLowerCase())) {
                            reqHeaders.put(name, "[REDACTED]");
                        } else {
                            reqHeaders.put(name, String.join(", ", entry.getValue()));
                        }
                    }

                    // Build response headers map
                    Map<String, String> respHeaders = new LinkedHashMap<>();
                    for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
                        String name = entry.getKey();
                        if (PII_HEADERS.contains(name.toLowerCase())) {
                            respHeaders.put(name, "[REDACTED]");
                        } else {
                            respHeaders.put(name, String.join(", ", entry.getValue()));
                        }
                    }

                    // Client IP from X-Forwarded-For or fallback
                    String clientIp = request.getHeaders().getFirst("X-Forwarded-For");
                    if (clientIp != null && !clientIp.isBlank()) {
                        clientIp = clientIp.split(",")[0].trim();
                    } else {
                        clientIp = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "";
                    }
                    String ua = request.getHeaders().getFirst("User-Agent");
                    String body = responseBody.toString();
                    int bytesOut = body.getBytes(StandardCharsets.UTF_8).length;

                    Map<String, Object> logEntry = new LinkedHashMap<>();
                    logEntry.put("level", "INFO");
                    logEntry.put("message", "http_dump");
                    logEntry.put("request_id", rid);
                    logEntry.put("method", request.getMethod().name());
                    logEntry.put("path", request.getURI().getPath());
                    logEntry.put("query", queryParams);
                    logEntry.put("client_ip", clientIp);
                    logEntry.put("user_agent", ua != null ? ua : "");
                    logEntry.put("request_headers", reqHeaders);
                    logEntry.put("request_body", "");
                    logEntry.put("status", statusCode);
                    logEntry.put("latency", formatLatency(latencyNs));
                    logEntry.put("latency_ms", latencyMs);
                    logEntry.put("response_headers", respHeaders);
                    logEntry.put("response_body", body);
                    logEntry.put("bytes_out", bytesOut);

                    System.out.println(MAPPER.writeValueAsString(logEntry));
                } catch (Exception ignored) {}
            });
    }

    private static String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
