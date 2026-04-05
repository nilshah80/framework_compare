package benchmark;

import benchmark.model.*;
import benchmark.pgstore.*;
import benchmark.pgstore.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class Application {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PgStore pgStore;
    private static final Set<String> PII_HEADERS = Set.of(
        "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );
    private static final long MAX_BODY_SIZE = 1_048_576;

    public static void main(String[] args) {
        String dsn = System.getenv("PG_DSN");
        if (dsn == null || dsn.isBlank()) {
            dsn = "postgres://postgres:postgres@localhost:5432/benchmark?sslmode=disable";
        }
        pgStore = new PgStore(dsn);
        pgStore.initSchema();

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

            // Capture request body
            ctx.attribute("requestBody", ctx.body());

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

            List<BulkOrderInput> inputs = req.getOrders().stream()
                .map(orderReq -> new BulkOrderInput(
                    orderReq.getItems().stream()
                        .map(i -> new OrderItem(i.getProductId(), i.getName(), i.getQuantity(), i.getPrice()))
                        .collect(Collectors.toList()),
                    orderReq.getCurrency()))
                .collect(Collectors.toList());

            BulkResult bulkResult = pgStore.bulkCreateOrders(userId, inputs);

            List<OrderResponse> results = new ArrayList<>();
            for (int i = 0; i < bulkResult.orders().size(); i++) {
                Order o = bulkResult.orders().get(i);
                OrderResponse resp = new OrderResponse(o.orderId(), o.userId(), o.status(),
                    req.getOrders().get(i).getItems(), o.total(), o.currency(), null, requestId);
                results.add(resp);
            }

            sendJson(ctx, 201, new BulkOrderResponse(userId, results.size(), results, bulkResult.totalSum(), requestId));
        });

        app.get("/users/{userId}/orders", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            List<Order> pgOrders = pgStore.listOrders(userId);

            List<OrderResponse> orders = pgOrders.stream()
                .map(o -> {
                    List<OrderRequest.Item> responseItems = o.items().stream()
                        .map(i -> {
                            OrderRequest.Item item = new OrderRequest.Item();
                            item.setProductId(i.productId());
                            item.setName(i.name());
                            item.setQuantity(i.quantity());
                            item.setPrice(i.price());
                            return item;
                        })
                        .collect(Collectors.toList());
                    return new OrderResponse(o.orderId(), o.userId(), o.status(),
                        responseItems, o.total(), o.currency(), null, requestId);
                })
                .collect(Collectors.toList());

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

            benchmark.pgstore.Profile pgProfile = toStoreProfile(body);
            pgStore.upsertProfile(userId, pgProfile);

            body.setUserId(userId);
            body.setRequestId(requestId);
            sendJson(ctx, 200, body);
        });

        app.get("/users/{userId}/profile", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");

            Optional<benchmark.pgstore.Profile> result = pgStore.getProfile(userId);
            if (result.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("profile not found"));
                return;
            }

            benchmark.pgstore.Profile p = result.get();
            UserProfile profile = fromStoreProfile(p, requestId);
            sendJson(ctx, 200, profile);
        });

        app.post("/users/{userId}/orders", ctx -> {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.attribute("requestId");
            OrderRequest req = ctx.bodyAsClass(OrderRequest.class);

            List<OrderItem> items = req.getItems().stream()
                .map(i -> new OrderItem(i.getProductId(), i.getName(), i.getQuantity(), i.getPrice()))
                .collect(Collectors.toList());

            Order order = pgStore.createOrder(userId, items, req.getCurrency());

            OrderResponse resp = new OrderResponse(order.orderId(), order.userId(), order.status(),
                req.getItems(), order.total(), order.currency(), "*", requestId);
            sendJson(ctx, 201, resp);
        });

        app.put("/users/{userId}/orders/{orderId}", ctx -> {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.attribute("requestId");

            OrderRequest req = ctx.bodyAsClass(OrderRequest.class);
            List<OrderItem> items = req.getItems().stream()
                .map(i -> new OrderItem(i.getProductId(), i.getName(), i.getQuantity(), i.getPrice()))
                .collect(Collectors.toList());

            Optional<Order> updated = pgStore.updateOrder(userId, orderId, items, req.getCurrency());
            if (updated.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = updated.get();
            OrderResponse resp = new OrderResponse(o.orderId(), o.userId(), o.status(),
                req.getItems(), o.total(), o.currency(), "*", requestId);
            sendJson(ctx, 200, resp);
        });

        app.delete("/users/{userId}/orders/{orderId}", ctx -> {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.attribute("requestId");

            boolean deleted = pgStore.deleteOrder(userId, orderId);
            if (!deleted) {
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

            Optional<Order> result = pgStore.getOrder(userId, orderId);
            if (result.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = result.get();
            List<OrderRequest.Item> responseItems = o.items().stream()
                .map(i -> {
                    OrderRequest.Item item = new OrderRequest.Item();
                    item.setProductId(i.productId());
                    item.setName(i.name());
                    item.setQuantity(i.quantity());
                    item.setPrice(i.price());
                    return item;
                })
                .collect(Collectors.toList());

            OrderResponse resp = new OrderResponse(
                o.orderId(), o.userId(), o.status(),
                responseItems, o.total(), o.currency(),
                fields, requestId
            );

            sendJson(ctx, 200, resp);
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

            // Client IP from X-Forwarded-For
            String clientIp = ctx.header("X-Forwarded-For");
            if (clientIp != null && !clientIp.isBlank()) {
                clientIp = clientIp.split(",")[0].trim();
            } else {
                clientIp = ctx.ip();
            }

            // Request body
            String requestBody = ctx.attribute("requestBody");
            if (requestBody == null) requestBody = "";

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("level", "INFO");
            logEntry.put("message", "http_dump");
            logEntry.put("request_id", requestId != null ? requestId : "");
            logEntry.put("method", ctx.method().name());
            logEntry.put("path", ctx.path());
            logEntry.put("query", queryParams);
            logEntry.put("client_ip", clientIp);
            logEntry.put("user_agent", ctx.header("User-Agent") != null ? ctx.header("User-Agent") : "");
            logEntry.put("request_headers", reqHeaders);
            logEntry.put("request_body", requestBody);
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

    private static benchmark.pgstore.Profile toStoreProfile(UserProfile body) {
        benchmark.pgstore.Address addr = null;
        if (body.getAddress() != null) {
            benchmark.model.Address a = body.getAddress();
            addr = new benchmark.pgstore.Address(a.getStreet(), a.getCity(),
                a.getState(), a.getZip(), a.getCountry());
        }
        benchmark.pgstore.Preferences prefs = null;
        if (body.getPreferences() != null) {
            benchmark.model.Preferences pr = body.getPreferences();
            benchmark.pgstore.NotificationPrefs np = null;
            if (pr.getNotifications() != null) {
                benchmark.model.NotificationPrefs n = pr.getNotifications();
                np = new benchmark.pgstore.NotificationPrefs(n.isEmail(), n.isSms(), n.isPush());
            }
            prefs = new benchmark.pgstore.Preferences(pr.getLanguage(),
                pr.getCurrency(), pr.getTimezone(), np, pr.getTheme());
        }
        List<benchmark.pgstore.PaymentMethod> pms = null;
        if (body.getPaymentMethods() != null) {
            pms = body.getPaymentMethods().stream()
                .map(pm -> new benchmark.pgstore.PaymentMethod(pm.getType(), pm.getLast4(),
                    pm.getExpiryMonth(), pm.getExpiryYear(), pm.isIsDefault()))
                .collect(Collectors.toList());
        }
        return new benchmark.pgstore.Profile(body.getUserId(), body.getName(), body.getEmail(), body.getPhone(),
            addr, prefs, pms, body.getTags(), body.getMetadata());
    }

    private static UserProfile fromStoreProfile(benchmark.pgstore.Profile p, String requestId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(p.userId());
        profile.setName(p.name());
        profile.setEmail(p.email());
        profile.setPhone(p.phone());
        if (p.address() != null) {
            benchmark.model.Address a = new benchmark.model.Address();
            a.setStreet(p.address().street());
            a.setCity(p.address().city());
            a.setState(p.address().state());
            a.setZip(p.address().zip());
            a.setCountry(p.address().country());
            profile.setAddress(a);
        }
        if (p.preferences() != null) {
            benchmark.model.Preferences pr = new benchmark.model.Preferences();
            pr.setLanguage(p.preferences().language());
            pr.setCurrency(p.preferences().currency());
            pr.setTimezone(p.preferences().timezone());
            pr.setTheme(p.preferences().theme());
            if (p.preferences().notifications() != null) {
                benchmark.model.NotificationPrefs np = new benchmark.model.NotificationPrefs();
                np.setEmail(p.preferences().notifications().email());
                np.setSms(p.preferences().notifications().sms());
                np.setPush(p.preferences().notifications().push());
                pr.setNotifications(np);
            }
            profile.setPreferences(pr);
        }
        if (p.paymentMethods() != null) {
            profile.setPaymentMethods(p.paymentMethods().stream()
                .map(pm -> {
                    benchmark.model.PaymentMethod m = new benchmark.model.PaymentMethod();
                    m.setType(pm.type());
                    m.setLast4(pm.last4());
                    m.setExpiryMonth(pm.expiryMonth());
                    m.setExpiryYear(pm.expiryYear());
                    m.setIsDefault(pm.isDefault());
                    return m;
                })
                .collect(Collectors.toList()));
        }
        profile.setTags(p.tags());
        profile.setMetadata(p.metadata());
        profile.setRequestId(requestId);
        return profile;
    }
}
