package benchmark.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@Order(5)
public class LoggingFilter implements Filter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PII_HEADERS = Set.of(
        "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper((HttpServletResponse) response);

        long start = System.nanoTime();
        chain.doFilter(request, res);
        long latencyNs = System.nanoTime() - start;

        double latencyMs = latencyNs / 1_000_000.0;
        String requestId = (String) req.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        byte[] bodyBytes = res.getContentAsByteArray();
        String responseBody = new String(bodyBytes, StandardCharsets.UTF_8);
        int bytesOut = bodyBytes.length;

        // Build query params as a map
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (req.getQueryString() != null && !req.getQueryString().isEmpty()) {
            for (String param : req.getQueryString().split("&")) {
                String[] kv = param.split("=", 2);
                queryParams.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }

        // Build request headers map
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            if (PII_HEADERS.contains(name.toLowerCase())) {
                reqHeaders.put(name, "[REDACTED]");
            } else {
                reqHeaders.put(name, req.getHeader(name));
            }
        }

        // Build response headers map
        Map<String, String> respHeaders = new LinkedHashMap<>();
        for (String name : res.getHeaderNames()) {
            if (PII_HEADERS.contains(name.toLowerCase())) {
                respHeaders.put(name, "[REDACTED]");
            } else {
                respHeaders.put(name, res.getHeader(name));
            }
        }

        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("level", "INFO");
        logEntry.put("message", "http_dump");
        logEntry.put("request_id", requestId != null ? requestId : "");
        logEntry.put("method", req.getMethod());
        logEntry.put("path", req.getRequestURI());
        logEntry.put("query", queryParams);
        logEntry.put("client_ip", req.getRemoteAddr());
        logEntry.put("user_agent", req.getHeader("User-Agent") != null ? req.getHeader("User-Agent") : "");
        logEntry.put("request_headers", reqHeaders);
        logEntry.put("status", res.getStatus());
        logEntry.put("latency", formatLatency(latencyNs));
        logEntry.put("latency_ms", latencyMs);
        logEntry.put("response_headers", respHeaders);
        logEntry.put("response_body", responseBody);
        logEntry.put("bytes_out", bytesOut);

        System.out.println(MAPPER.writeValueAsString(logEntry));

        res.copyBodyToResponse();
    }

    private static String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
