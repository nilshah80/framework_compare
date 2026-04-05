package benchmark;

import benchmark.pgstore.*;
import benchmark.pgstore.Order;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

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

    // --- Models ---

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

        WebServer server = WebServer.builder()
                .port(8106)
                .routing(Application::routing)
                .build()
                .start();

        System.out.println("{\"level\":\"INFO\",\"message\":\"server starting\",\"port\":" + server.port() + "}");
    }

    private static void routing(HttpRouting.Builder routing) {
        routing.any(Application::middlewareHandler)
                .post("/users/{userId}/orders/bulk", Application::bulkCreateOrders)
                .post("/users/{userId}/orders", Application::createOrder)
                .put("/users/{userId}/orders/{orderId}", Application::updateOrder)
                .delete("/users/{userId}/orders/{orderId}", Application::deleteOrder)
                .get("/users/{userId}/orders/{orderId}", Application::getOrder)
                .get("/users/{userId}/orders", Application::listOrders)
                .put("/users/{userId}/profile", Application::putProfile)
                .get("/users/{userId}/profile", Application::getProfile)
                .error(Exception.class, Application::errorHandler);
    }

    // --- Middleware ---

    private static void middlewareHandler(ServerRequest req, ServerResponse res) {
        long startTime = System.nanoTime();

        String requestId = req.headers().contains(HeaderNames.create("X-Request-ID"))
                ? req.headers().get(HeaderNames.create("X-Request-ID")).get()
                : UUID.randomUUID().toString();
        req.context().register("requestId", requestId);
        req.context().register("startTime", startTime);

        res.header(HeaderNames.create("X-Request-ID"), requestId);

        // CORS
        res.header(HeaderNames.create("Access-Control-Allow-Origin"), "*");
        res.header(HeaderNames.create("Access-Control-Allow-Methods"), "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
        res.header(HeaderNames.create("Access-Control-Allow-Headers"), "Origin,Content-Type,Accept,Authorization");

        // Security headers
        res.header(HeaderNames.create("X-XSS-Protection"), "1; mode=block");
        res.header(HeaderNames.create("X-Content-Type-Options"), "nosniff");
        res.header(HeaderNames.create("X-Frame-Options"), "DENY");
        res.header(HeaderNames.create("Strict-Transport-Security"), "max-age=31536000; includeSubDomains");
        res.header(HeaderNames.create("Content-Security-Policy"), "default-src 'self'");
        res.header(HeaderNames.create("Referrer-Policy"), "strict-origin-when-cross-origin");
        res.header(HeaderNames.create("Permissions-Policy"), "geolocation=(), microphone=(), camera=()");
        res.header(HeaderNames.create("Cross-Origin-Opener-Policy"), "same-origin");

        // Body limit
        if (req.headers().contains(HeaderNames.CONTENT_LENGTH)) {
            long contentLength = Long.parseLong(req.headers().get(HeaderNames.CONTENT_LENGTH).get());
            if (contentLength > 1_048_576) {
                sendJson(req, res, 413, new ErrorResponse("request body too large"));
                return;
            }
        }

        res.next();
    }

    private static void errorHandler(ServerRequest req, ServerResponse res, Exception ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        System.err.println(sw);
        sendJson(req, res, 500, new ErrorResponse("Internal Server Error"));
    }

    // --- Route handlers ---

    private static void createOrder(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String bodyStr = req.content().as(String.class);
            req.context().register("requestBody", bodyStr);
            OrderRequest body = MAPPER.readValue(bodyStr, OrderRequest.class);

            List<OrderItem> items = body.items().stream()
                .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            Order order = store.createOrder(userId, items, body.currency());

            List<Item> responseItems = order.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(req, res, 201, new OrderResponse(order.orderId(), order.userId(), order.status(),
                responseItems, order.total(), order.currency(), null, requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void updateOrder(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String orderId = req.path().pathParameters().get("orderId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String bodyStr = req.content().as(String.class);
            req.context().register("requestBody", bodyStr);
            OrderRequest body = MAPPER.readValue(bodyStr, OrderRequest.class);

            List<OrderItem> items = body.items().stream()
                .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            Optional<Order> updated = store.updateOrder(userId, orderId, items, body.currency());
            if (updated.isEmpty()) {
                sendJson(req, res, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = updated.get();
            List<Item> responseItems = o.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(req, res, 200, new OrderResponse(o.orderId(), o.userId(), o.status(),
                responseItems, o.total(), o.currency(), null, requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void deleteOrder(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String orderId = req.path().pathParameters().get("orderId");
            String requestId = req.context().get("requestId", String.class).orElse("");

            boolean deleted = store.deleteOrder(userId, orderId);
            if (!deleted) {
                sendJson(req, res, 404, new ErrorResponse("order not found"));
                return;
            }

            sendJson(req, res, 200, new DeleteResponse("order deleted", orderId, requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void getOrder(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String orderId = req.path().pathParameters().get("orderId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String fields = req.query().first("fields").orElse("*");

            Optional<Order> result = store.getOrder(userId, orderId);
            if (result.isEmpty()) {
                sendJson(req, res, 404, new ErrorResponse("order not found"));
                return;
            }

            Order o = result.get();
            List<Item> responseItems = o.items().stream()
                .map(i -> new Item(i.productId(), i.name(), i.quantity(), i.price()))
                .collect(Collectors.toList());

            sendJson(req, res, 200, new OrderResponse(o.orderId(), o.userId(), o.status(),
                    responseItems, o.total(), o.currency(), fields, requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void bulkCreateOrders(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String bodyStr = req.content().as(String.class);
            req.context().register("requestBody", bodyStr);
            BulkOrderRequest body = MAPPER.readValue(bodyStr, BulkOrderRequest.class);

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

            sendJson(req, res, 201, new BulkOrderResponse(userId, results.size(), results, bulkResult.totalSum(), requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void listOrders(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");

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

            sendJson(req, res, 200, response);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void putProfile(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String bodyStr = req.content().as(String.class);
            req.context().register("requestBody", bodyStr);
            UserProfile body = MAPPER.readValue(bodyStr, UserProfile.class);

            benchmark.pgstore.Profile pgProfile = toStoreProfile(body);
            store.upsertProfile(userId, pgProfile);

            var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
                body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);

            sendJson(req, res, 200, profile);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void getProfile(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");

            Optional<benchmark.pgstore.Profile> result = store.getProfile(userId);
            if (result.isEmpty()) {
                sendJson(req, res, 404, new ErrorResponse("profile not found"));
                return;
            }

            benchmark.pgstore.Profile p = result.get();
            var withReqId = fromStoreProfile(p, requestId);
            sendJson(req, res, 200, withReqId);
        } catch (Exception e) {
            errorHandler(req, res, e);
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

    private static void sendJson(ServerRequest req, ServerResponse res, int status, Object body) {
        try {
            String json = MAPPER.writeValueAsString(body);
            long startTime = req.context().get("startTime", Long.class).orElse(System.nanoTime());
            long elapsed = System.nanoTime() - startTime;
            double latencyMs = elapsed / 1_000_000.0;

            // Structured log
            Map<String, Object> reqHeaders = new LinkedHashMap<>();
            for (var header : req.headers()) {
                String key = header.name();
                String value = header.get();
                if (SENSITIVE_HEADERS.contains(key.toLowerCase())) {
                    reqHeaders.put(key, "[REDACTED]");
                } else {
                    reqHeaders.put(key, value);
                }
            }

            Map<String, String> queryParams = new HashMap<>();
            try {
                for (String paramName : req.query().names()) {
                    req.query().first(paramName).ifPresent(v -> queryParams.put(paramName, v));
                }
            } catch (Exception ignored) {}

            Map<String, Object> respHeaders = new LinkedHashMap<>();
            try {
                for (var header : res.headers()) {
                    String hdrName = header.name();
                    String hdrValue = header.get();
                    if (SENSITIVE_HEADERS.contains(hdrName.toLowerCase())) {
                        respHeaders.put(hdrName, "[REDACTED]");
                    } else {
                        respHeaders.put(hdrName, hdrValue);
                    }
                }
            } catch (Exception ignored) {}

            String requestId = req.context().get("requestId", String.class).orElse("");

            // Client IP from X-Forwarded-For
            String clientIp;
            if (req.headers().contains(HeaderNames.create("X-Forwarded-For"))) {
                clientIp = req.headers().get(HeaderNames.create("X-Forwarded-For")).get().split(",")[0].trim();
            } else {
                clientIp = req.remotePeer().host();
            }

            String userAgent = req.headers().contains(HeaderNames.USER_AGENT)
                    ? req.headers().get(HeaderNames.USER_AGENT).get()
                    : "";

            // Request body
            String requestBody = req.context().get("requestBody", String.class).orElse("");

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("level", "INFO");
            logEntry.put("message", "http_dump");
            logEntry.put("request_id", requestId);
            logEntry.put("method", req.prologue().method().text());
            logEntry.put("path", req.path().path());
            logEntry.put("query", queryParams);
            logEntry.put("client_ip", clientIp);
            logEntry.put("user_agent", userAgent);
            logEntry.put("request_headers", reqHeaders);
            logEntry.put("request_body", requestBody);
            logEntry.put("status", status);
            logEntry.put("latency", formatLatency(elapsed));
            logEntry.put("latency_ms", latencyMs);
            logEntry.put("response_headers", respHeaders);
            logEntry.put("response_body", json);
            logEntry.put("bytes_out", json.getBytes().length);

            System.out.println(MAPPER.writeValueAsString(logEntry));

            res.status(Status.create(status));
            res.header(HeaderNames.CONTENT_TYPE, "application/json");
            res.send(json);
        } catch (Exception e) {
            res.status(Status.create(500));
            res.send("{\"error\":\"Internal Server Error\"}");
        }
    }

    private static String formatLatency(long nanos) {
        if (nanos < 1_000_000) {
            return (nanos / 1000.0) + "us";
        }
        return (nanos / 1_000_000.0) + "ms";
    }
}
