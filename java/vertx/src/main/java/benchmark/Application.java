package benchmark;

import benchmark.pgstore.*;
import benchmark.pgstore.Order;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class Application {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PgStore store;

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    );

    // --- Request/Response Models ---

    public record OrderRequest(List<Item> items, String currency) {}

    public record Item(
            @JsonProperty("product_id") String productId,
            String name, int quantity, double price) {}

    public record OrderResponse(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("user_id") String userId,
            String status,
            List<Item> items,
            double total,
            String currency,
            String fields,
            @JsonProperty("request_id") String requestId) {}

    public record DeleteResponse(
            String message,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("request_id") String requestId) {}

    public record ErrorResponse(String error) {}

    public record BulkOrderRequest(List<OrderRequest> orders) {}

    public record BulkOrderResponse(
            @JsonProperty("user_id") String userId,
            int count,
            List<OrderResponse> orders,
            @JsonProperty("total_sum") double totalSum,
            @JsonProperty("request_id") String requestId) {}

    public record Address(String street, String city, String state, String zip, String country) {}

    public record NotificationPrefs(boolean email, boolean sms, boolean push) {}

    public record Preferences(String language, String currency, String timezone,
                              NotificationPrefs notifications, String theme) {}

    public record PaymentMethod(String type, String last4,
                                @JsonProperty("expiry_month") int expiryMonth,
                                @JsonProperty("expiry_year") int expiryYear,
                                @JsonProperty("is_default") boolean isDefault) {}

    public record UserProfile(
            @JsonProperty("user_id") String userId,
            String name, String email, String phone,
            Address address, Preferences preferences,
            @JsonProperty("payment_methods") List<PaymentMethod> paymentMethods,
            List<String> tags,
            Map<String, String> metadata,
            @JsonProperty("request_id") String requestId) {}

    // --- Main ---

    public static void main(String[] args) {
        String dsn = System.getenv("PG_DSN");
        if (dsn == null || dsn.isBlank()) {
            dsn = "postgres://postgres:postgres@localhost:5432/benchmark?sslmode=disable";
        }
        store = new PgStore(dsn);
        store.initSchema();

        Vertx vertx = Vertx.vertx();
        Router router = Router.router(vertx);

        // 1. Recovery middleware
        router.route().failureHandler(Application::recoveryHandler);

        // 2. Request ID middleware
        router.route().handler(Application::requestIdHandler);

        // 3. CORS middleware
        router.route().handler(Application::corsHandler);

        // 4. Security headers middleware
        router.route().handler(Application::securityHeadersHandler);

        // 5. Body limit (1MB) + body handler
        router.route().handler(BodyHandler.create().setBodyLimit(1_048_576));

        // 6. Structured logger (start time capture)
        router.route().handler(ctx -> {
            ctx.put("startTime", System.nanoTime());
            ctx.addHeadersEndHandler(v -> {});
            ctx.next();
        });

        // Routes
        String basePath = "/users/:userId/orders";

        router.post(basePath + "/bulk").handler(Application::bulkCreateOrders);
        router.post(basePath).handler(Application::createOrder);
        router.put(basePath + "/:orderId").handler(Application::updateOrder);
        router.delete(basePath + "/:orderId").handler(Application::deleteOrder);
        router.get(basePath + "/:orderId").handler(Application::getOrder);
        router.get(basePath).handler(Application::listOrders);
        router.put("/users/:userId/profile").handler(Application::putProfile);
        router.get("/users/:userId/profile").handler(Application::getProfile);

        // After-handler for structured logging
        router.route().last().handler(ctx -> ctx.next());

        HttpServerOptions options = new HttpServerOptions().setPort(8105);
        HttpServer server = vertx.createHttpServer(options);
        server.requestHandler(router).listen(8105)
                .onSuccess(s -> System.out.println("{\"level\":\"INFO\",\"message\":\"server starting\",\"port\":8105}"))
                .onFailure(err -> {
                    System.err.println("Failed to start server: " + err.getMessage());
                    System.exit(1);
                });
    }

    // --- Middleware handlers ---

    private static void recoveryHandler(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        if (failure != null) {
            StringWriter sw = new StringWriter();
            failure.printStackTrace(new PrintWriter(sw));
            System.err.println(sw);
        }
        sendJson(ctx, 500, new ErrorResponse("Internal Server Error"));
    }

    private static void requestIdHandler(RoutingContext ctx) {
        String requestId = ctx.request().getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ctx.put("requestId", requestId);
        ctx.response().putHeader("X-Request-ID", requestId);
        ctx.next();
    }

    private static void corsHandler(RoutingContext ctx) {
        ctx.response().putHeader("Access-Control-Allow-Origin", "*");
        ctx.response().putHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
        ctx.response().putHeader("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization");
        ctx.next();
    }

    private static void securityHeadersHandler(RoutingContext ctx) {
        ctx.response().putHeader("X-XSS-Protection", "1; mode=block");
        ctx.response().putHeader("X-Content-Type-Options", "nosniff");
        ctx.response().putHeader("X-Frame-Options", "DENY");
        ctx.response().putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        ctx.response().putHeader("Content-Security-Policy", "default-src 'self'");
        ctx.response().putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        ctx.response().putHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        ctx.response().putHeader("Cross-Origin-Opener-Policy", "same-origin");
        ctx.next();
    }

    // --- Route handlers ---

    private static void createOrder(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.get("requestId");
            OrderRequest body = MAPPER.readValue(ctx.body().asString(), OrderRequest.class);

            List<OrderItem> items = body.items().stream()
                .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            Order order = store.createOrder(userId, items, body.currency());

            List<Item> responseItems = order.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(ctx, 201, new OrderResponse(order.orderId(), order.userId(), order.status(),
                responseItems, order.total(), order.currency(), null, requestId));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void updateOrder(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.get("requestId");
            OrderRequest body = MAPPER.readValue(ctx.body().asString(), OrderRequest.class);

            List<OrderItem> items = body.items().stream()
                .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            Optional<Order> updated = store.updateOrder(userId, orderId, items, body.currency());
            if (updated.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = updated.get();
            List<Item> responseItems = o.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(ctx, 200, new OrderResponse(o.orderId(), o.userId(), o.status(),
                responseItems, o.total(), o.currency(), null, requestId));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void deleteOrder(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.get("requestId");

            boolean deleted = store.deleteOrder(userId, orderId);
            if (!deleted) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            sendJson(ctx, 200, new DeleteResponse("order deleted", orderId, requestId));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void getOrder(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.get("requestId");
            String fields = ctx.request().getParam("fields", "*");

            Optional<Order> result = store.getOrder(userId, orderId);
            if (result.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = result.get();
            List<Item> responseItems = o.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(ctx, 200, new OrderResponse(o.orderId(), o.userId(), o.status(),
                    responseItems, o.total(), o.currency(), fields, requestId));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void bulkCreateOrders(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.get("requestId");
            BulkOrderRequest body = MAPPER.readValue(ctx.body().asString(), BulkOrderRequest.class);

            List<BulkOrderInput> inputs = body.orders().stream()
                .map(orderReq -> new BulkOrderInput(
                    orderReq.items().stream()
                        .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
                        .collect(Collectors.toList()),
                    orderReq.currency()))
                .collect(Collectors.toList());

            BulkResult bulkResult = store.bulkCreateOrders(userId, inputs);

            List<OrderResponse> results = new ArrayList<>();
            for (int i = 0; i < bulkResult.orders().size(); i++) {
                Order o = bulkResult.orders().get(i);
                List<Item> responseItems = o.items().stream()
                    .map(it -> new Item(it.productId(), it.name(), it.quantity(), it.price()))
                    .collect(Collectors.toList());
                results.add(new OrderResponse(o.orderId(), o.userId(), o.status(),
                    responseItems, o.total(), o.currency(), null, requestId));
            }

            sendJson(ctx, 201, new BulkOrderResponse(userId, results.size(), results, bulkResult.totalSum(), requestId));
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void listOrders(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.get("requestId");

            List<Order> pgOrders = store.listOrders(userId);
            List<OrderResponse> results = pgOrders.stream()
                .map(o -> {
                    List<Item> responseItems = o.items().stream()
                        .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                        .collect(Collectors.toList());
                    return new OrderResponse(o.orderId(), o.userId(), o.status(),
                        responseItems, o.total(), o.currency(), null, requestId);
                })
                .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("user_id", userId);
            response.put("count", results.size());
            response.put("orders", results);
            response.put("request_id", requestId);

            sendJson(ctx, 200, response);
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void putProfile(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.get("requestId");
            UserProfile body = MAPPER.readValue(ctx.body().asString(), UserProfile.class);

            benchmark.pgstore.Profile pgProfile = toStoreProfile(body);
            store.upsertProfile(userId, pgProfile);

            var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
                body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);

            sendJson(ctx, 200, profile);
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void getProfile(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String requestId = ctx.get("requestId");

            Optional<benchmark.pgstore.Profile> result = store.getProfile(userId);
            if (result.isEmpty()) {
                sendJson(ctx, 404, new ErrorResponse("profile not found"));
                return;
            }

            benchmark.pgstore.Profile p = result.get();
            var withReqId = fromStoreProfile(p, requestId);
            sendJson(ctx, 200, withReqId);
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    // --- Profile conversion ---

    private static benchmark.pgstore.Profile toStoreProfile(UserProfile body) {
        benchmark.pgstore.Address addr = null;
        if (body.address() != null) {
            addr = new benchmark.pgstore.Address(body.address().street(), body.address().city(),
                body.address().state(), body.address().zip(), body.address().country());
        }
        benchmark.pgstore.Preferences prefs = null;
        if (body.preferences() != null) {
            benchmark.pgstore.NotificationPrefs np = null;
            if (body.preferences().notifications() != null) {
                np = new benchmark.pgstore.NotificationPrefs(
                    body.preferences().notifications().email(),
                    body.preferences().notifications().sms(),
                    body.preferences().notifications().push());
            }
            prefs = new benchmark.pgstore.Preferences(body.preferences().language(),
                body.preferences().currency(), body.preferences().timezone(), np, body.preferences().theme());
        }
        List<benchmark.pgstore.PaymentMethod> pms = null;
        if (body.paymentMethods() != null) {
            pms = body.paymentMethods().stream()
                .map(pm -> new benchmark.pgstore.PaymentMethod(pm.type(), pm.last4(),
                    pm.expiryMonth(), pm.expiryYear(), pm.isDefault()))
                .collect(Collectors.toList());
        }
        return new benchmark.pgstore.Profile(body.userId(), body.name(), body.email(), body.phone(),
            addr, prefs, pms, body.tags(), body.metadata());
    }

    private static UserProfile fromStoreProfile(benchmark.pgstore.Profile p, String requestId) {
        Address addr = null;
        if (p.address() != null) {
            addr = new Address(p.address().street(), p.address().city(),
                p.address().state(), p.address().zip(), p.address().country());
        }
        Preferences prefs = null;
        if (p.preferences() != null) {
            NotificationPrefs np = null;
            if (p.preferences().notifications() != null) {
                np = new NotificationPrefs(
                    p.preferences().notifications().email(),
                    p.preferences().notifications().sms(),
                    p.preferences().notifications().push());
            }
            prefs = new Preferences(p.preferences().language(),
                p.preferences().currency(), p.preferences().timezone(), np, p.preferences().theme());
        }
        List<PaymentMethod> pms = null;
        if (p.paymentMethods() != null) {
            pms = p.paymentMethods().stream()
                .map(pm -> new PaymentMethod(pm.type(), pm.last4(),
                    pm.expiryMonth(), pm.expiryYear(), pm.isDefault()))
                .collect(Collectors.toList());
        }
        return new UserProfile(p.userId(), p.name(), p.email(), p.phone(),
            addr, prefs, pms, p.tags(), p.metadata(), requestId);
    }

    // --- Utility ---

    private static void sendJson(RoutingContext ctx, int status, Object body) {
        try {
            String json = MAPPER.writeValueAsString(body);
            long startTime = ctx.get("startTime") != null ? (long) ctx.get("startTime") : System.nanoTime();
            long elapsed = System.nanoTime() - startTime;
            double latencyMs = elapsed / 1_000_000.0;

            // Structured log
            Map<String, Object> reqHeaders = new LinkedHashMap<>();
            ctx.request().headers().forEach(entry -> {
                if (SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase())) {
                    reqHeaders.put(entry.getKey(), "[REDACTED]");
                } else {
                    reqHeaders.put(entry.getKey(), entry.getValue());
                }
            });

            Map<String, String> queryParams = new HashMap<>();
            String queryString = ctx.request().query();
            if (queryString != null && !queryString.isEmpty()) {
                for (String param : queryString.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        queryParams.put(kv[0], kv[1]);
                    }
                }
            }

            Map<String, Object> respHeaders = new LinkedHashMap<>();
            ctx.response().headers().forEach(entry -> {
                if (SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase())) {
                    respHeaders.put(entry.getKey(), "[REDACTED]");
                } else {
                    respHeaders.put(entry.getKey(), entry.getValue());
                }
            });

            String requestId = ctx.get("requestId");

            // Client IP from X-Forwarded-For
            String clientIp = ctx.request().getHeader("X-Forwarded-For");
            if (clientIp != null && !clientIp.isBlank()) {
                clientIp = clientIp.split(",")[0].trim();
            } else {
                clientIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : "";
            }

            // Request body
            String requestBody = ctx.body() != null ? ctx.body().asString() : "";

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("level", "INFO");
            logEntry.put("message", "http_dump");
            logEntry.put("request_id", requestId != null ? requestId : "");
            logEntry.put("method", ctx.request().method().name());
            logEntry.put("path", ctx.request().path());
            logEntry.put("query", queryParams);
            logEntry.put("client_ip", clientIp);
            logEntry.put("user_agent", ctx.request().getHeader("User-Agent"));
            logEntry.put("request_headers", reqHeaders);
            logEntry.put("request_body", requestBody != null ? requestBody : "");
            logEntry.put("status", status);
            logEntry.put("latency", formatLatency(elapsed));
            logEntry.put("latency_ms", latencyMs);
            logEntry.put("response_headers", respHeaders);
            logEntry.put("response_body", json);
            logEntry.put("bytes_out", json.getBytes().length);

            System.out.println(MAPPER.writeValueAsString(logEntry));

            ctx.response()
                    .setStatusCode(status)
                    .putHeader("Content-Type", "application/json")
                    .end(json);
        } catch (Exception e) {
            ctx.response().setStatusCode(500).end("{\"error\":\"Internal Server Error\"}");
        }
    }

    private static String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
