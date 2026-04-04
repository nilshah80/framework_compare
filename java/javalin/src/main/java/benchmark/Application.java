package benchmark;

import benchmark.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class Application {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OrderStore STORE = OrderStore.getInstance();
    private static final Set<String> PII_HEADERS = Set.of(
        "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );
    private static final long MAX_BODY_SIZE = 1_048_576;

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.useVirtualThreads = true;
        });

        // Recovery middleware
        app.exception(Exception.class, (ex, ctx) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                Map<String, String> errLog = new LinkedHashMap<>();
                errLog.put("level", "error");
                errLog.put("error", ex.getMessage());
                errLog.put("stack", sw.toString());
                System.out.println(MAPPER.writeValueAsString(errLog));
            } catch (Exception ignored) {}
            sendJson(ctx, 500, new ErrorResponse("Internal Server Error"));
        });

        // Before handlers (middleware)
        app.before(ctx -> {
            long start = System.nanoTime();
            ctx.attribute("startTime", start);

            // Request ID
            String requestId = ctx.header("X-Request-ID");
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }
            ctx.attribute("requestId", requestId);
            ctx.header("X-Request-ID", requestId);

            // CORS
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization");

            // Security Headers
            ctx.header("X-XSS-Protection", "1; mode=block");
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            ctx.header("Content-Security-Policy", "default-src 'self'");
            ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
            ctx.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            ctx.header("Cross-Origin-Opener-Policy", "same-origin");

            // Body Limit
            String contentLengthStr = ctx.header("Content-Length");
            if (contentLengthStr != null) {
                try {
                    long contentLength = Long.parseLong(contentLengthStr);
                    if (contentLength > MAX_BODY_SIZE) {
                        sendJson(ctx, 413, new ErrorResponse("Request body too large"));
                        ctx.skipRemainingHandlers();
                        return;
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        // After handler (logging)
        app.after(ctx -> {
            logRequest(ctx);
        });

        // Routes
        app.post("/users/{userId}/orders/bulk", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            BulkOrderRequest req = ctx.bodyAsClass(BulkOrderRequest.class);

            List<OrderResponse> results = new ArrayList<>();
            double totalSum = 0;

            for (OrderRequest orderReq : req.getOrders()) {
                String currency = (orderReq.getCurrency() == null || orderReq.getCurrency().isBlank()) ? "USD" : orderReq.getCurrency();
                double total = orderReq.getItems().stream().mapToDouble(i -> i.getQuantity() * i.getPrice()).sum();
                String orderId = String.valueOf(STORE.nextId());

                OrderResponse order = new OrderResponse(orderId, userId, "created", orderReq.getItems(), total, currency, null, requestId);
                STORE.put(userId, orderId, order);
                results.add(order);
                totalSum += total;
            }

            sendJson(ctx, 201, new BulkOrderResponse(userId, results.size(), results, totalSum, requestId));
        });

        app.get("/users/{userId}/orders", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            List<OrderResponse> orders = STORE.listByUser(userId);

            sendJson(ctx, 200, Map.of(
                "user_id", userId,
                "count", orders.size(),
                "orders", orders,
                "request_id", requestId != null ? requestId : ""
            ));
        });

        app.put("/users/{userId}/profile", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            UserProfile body = ctx.bodyAsClass(UserProfile.class);

            body.setUserId(userId);
            body.setRequestId(requestId);
            STORE.putProfile(userId, body);

            sendJson(ctx, 200, body);
        });

        app.get("/users/{userId}/profile", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            UserProfile profile = STORE.getProfile(userId);

            if (profile == null) {
                sendJson(ctx, 404, new ErrorResponse("profile not found"));
                return;
            }

            profile.setRequestId(requestId);
            sendJson(ctx, 200, profile);
        });

        app.post("/users/{userId}/orders", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            OrderRequest req = ctx.bodyAsClass(OrderRequest.class);

            String currency = (req.getCurrency() == null || req.getCurrency().isBlank()) ? "USD" : req.getCurrency();
            double total = req.getItems().stream().mapToDouble(i -> i.getQuantity() * i.getPrice()).sum();
            String orderId = String.valueOf(STORE.nextId());

            OrderResponse order = new OrderResponse(orderId, userId, "created", req.getItems(), total, currency, "*", requestId);
            STORE.put(userId, orderId, order);

            sendJson(ctx, 201, order);
        });

        app.put("/users/{userId}/orders/{orderId}", ctx -> {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.attribute("requestId");

            OrderResponse existing = STORE.get(userId, orderId);
            if (existing == null) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            OrderRequest req = ctx.bodyAsClass(OrderRequest.class);
            String currency = (req.getCurrency() == null || req.getCurrency().isBlank()) ? "USD" : req.getCurrency();
            double total = req.getItems().stream().mapToDouble(i -> i.getQuantity() * i.getPrice()).sum();

            OrderResponse updated = new OrderResponse(orderId, userId, "updated", req.getItems(), total, currency, "*", requestId);
            STORE.put(userId, orderId, updated);

            sendJson(ctx, 200, updated);
        });

        app.delete("/users/{userId}/orders/{orderId}", ctx -> {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.attribute("requestId");

            OrderResponse existing = STORE.remove(userId, orderId);
            if (existing == null) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            sendJson(ctx, 200, new DeleteResponse("order deleted", orderId, requestId));
        });

        app.get("/users/{userId}/orders/{orderId}", ctx -> {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.attribute("requestId");
            String fields = ctx.queryParam("fields");
            if (fields == null || fields.isBlank()) {
                fields = "*";
            }

            OrderResponse existing = STORE.get(userId, orderId);
            if (existing == null) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            OrderResponse withFields = new OrderResponse(
                existing.getOrderId(), existing.getUserId(), existing.getStatus(),
                existing.getItems(), existing.getTotal(), existing.getCurrency(),
                fields, requestId
            );

            sendJson(ctx, 200, withFields);
        });

        app.start(8107);
    }

    private static void sendJson(Context ctx, int status, Object body) {
        try {
            String json = MAPPER.writeValueAsString(body);
            ctx.attribute("responseBody", json);
            ctx.status(status);
            ctx.contentType("application/json");
            ctx.result(json);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("{\"error\":\"Internal Server Error\"}");
            ctx.attribute("responseBody", "{\"error\":\"Internal Server Error\"}");
        }
    }

    private static void logRequest(Context ctx) {
        try {
            Long startTime = ctx.attribute("startTime");
            long latencyNs = startTime != null ? System.nanoTime() - startTime : 0;
            double latencyMs = latencyNs / 1_000_000.0;
            String requestId = ctx.attribute("requestId");

            // Build query params as a map
            Map<String, String> queryParams = new LinkedHashMap<>();
            if (ctx.queryString() != null && !ctx.queryString().isEmpty()) {
                for (String param : ctx.queryString().split("&")) {
                    String[] kv = param.split("=", 2);
                    queryParams.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }

            // Build request headers map
            Map<String, String> reqHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : ctx.headerMap().entrySet()) {
                if (PII_HEADERS.contains(entry.getKey().toLowerCase())) {
                    reqHeaders.put(entry.getKey(), "[REDACTED]");
                } else {
                    reqHeaders.put(entry.getKey(), entry.getValue());
                }
            }

            // Build response headers map
            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (String name : ctx.res().getHeaderNames()) {
                if (PII_HEADERS.contains(name.toLowerCase())) {
                    respHeaders.put(name, "[REDACTED]");
                } else {
                    respHeaders.put(name, ctx.res().getHeader(name));
                }
            }

            String responseBody = ctx.attribute("responseBody");
            if (responseBody == null) responseBody = "";
            int bytesOut = responseBody.getBytes(StandardCharsets.UTF_8).length;

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("level", "INFO");
            logEntry.put("message", "http_dump");
            logEntry.put("request_id", requestId != null ? requestId : "");
            logEntry.put("method", ctx.method().name());
            logEntry.put("path", ctx.path());
            logEntry.put("query", queryParams);
            logEntry.put("client_ip", ctx.ip());
            logEntry.put("user_agent", ctx.header("User-Agent") != null ? ctx.header("User-Agent") : "");
            logEntry.put("request_headers", reqHeaders);
            logEntry.put("status", ctx.res().getStatus());
            logEntry.put("latency", formatLatency(latencyNs));
            logEntry.put("latency_ms", latencyMs);
            logEntry.put("response_headers", respHeaders);
            logEntry.put("response_body", responseBody);
            logEntry.put("bytes_out", bytesOut);

            System.out.println(MAPPER.writeValueAsString(logEntry));
        } catch (Exception ignored) {}
    }

    private static String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
