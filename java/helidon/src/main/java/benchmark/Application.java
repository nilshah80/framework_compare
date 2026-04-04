package benchmark;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Application {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, OrderResponse> store = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UserProfile> profileStore = new ConcurrentHashMap<>();
    private static final AtomicLong counter = new AtomicLong(0);

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

    // --- Middleware (all-in-one filter) ---

    private static void middlewareHandler(ServerRequest req, ServerResponse res) {
        long startTime = System.nanoTime();

        // Request ID
        String requestId = req.headers().contains(HeaderNames.create("X-Request-ID"))
                ? req.headers().get(HeaderNames.create("X-Request-ID")).get()
                : UUID.randomUUID().toString();
        req.context().register("requestId", requestId);
        req.context().register("startTime", startTime);

        // Set response headers
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

        // Body limit check
        if (req.headers().contains(HeaderNames.CONTENT_LENGTH)) {
            long contentLength = Long.parseLong(req.headers().get(HeaderNames.CONTENT_LENGTH).get());
            if (contentLength > 1_048_576) {
                sendJson(req, res, 413, new ErrorResponse("request body too large"));
                return;
            }
        }

        res.next();
    }

    // --- Error handler ---

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
            OrderRequest body = MAPPER.readValue(bodyStr, OrderRequest.class);

            String orderId = String.valueOf(counter.incrementAndGet());
            double total = 0;
            if (body.items() != null) {
                for (var item : body.items()) {
                    total += item.price() * item.quantity();
                }
            }
            String currency = (body.currency() == null || body.currency().isBlank()) ? "USD" : body.currency();

            var order = new OrderResponse(orderId, userId, "created", body.items(), total, currency, null, requestId);
            store.put(userId + ":" + orderId, order);

            sendJson(req, res, 201, order);
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
            OrderRequest body = MAPPER.readValue(bodyStr, OrderRequest.class);

            String key = userId + ":" + orderId;
            OrderResponse existing = store.get(key);
            if (existing == null) {
                sendJson(req, res, 404, new ErrorResponse("order not found"));
                return;
            }

            double total = 0;
            if (body.items() != null) {
                for (var item : body.items()) {
                    total += item.price() * item.quantity();
                }
            }
            String currency = (body.currency() == null || body.currency().isBlank()) ? "USD" : body.currency();

            var updated = new OrderResponse(orderId, userId, "updated", body.items(), total, currency, null, requestId);
            store.put(key, updated);

            sendJson(req, res, 200, updated);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void deleteOrder(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String orderId = req.path().pathParameters().get("orderId");
            String requestId = req.context().get("requestId", String.class).orElse("");

            String key = userId + ":" + orderId;
            OrderResponse existing = store.remove(key);
            if (existing == null) {
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

            String key = userId + ":" + orderId;
            OrderResponse existing = store.get(key);
            if (existing == null) {
                sendJson(req, res, 404, new ErrorResponse("order not found"));
                return;
            }

            var result = new OrderResponse(existing.orderId(), existing.userId(), existing.status(),
                    existing.items(), existing.total(), existing.currency(), fields, requestId);
            sendJson(req, res, 200, result);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void bulkCreateOrders(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String bodyStr = req.content().as(String.class);
            BulkOrderRequest body = MAPPER.readValue(bodyStr, BulkOrderRequest.class);

            List<OrderResponse> results = new ArrayList<>();
            double totalSum = 0;

            for (var orderReq : body.orders()) {
                String orderId = String.valueOf(counter.incrementAndGet());
                double total = 0;
                if (orderReq.items() != null) {
                    for (var item : orderReq.items()) {
                        total += item.price() * item.quantity();
                    }
                }
                String currency = (orderReq.currency() == null || orderReq.currency().isBlank()) ? "USD" : orderReq.currency();
                var order = new OrderResponse(orderId, userId, "created", orderReq.items(), total, currency, null, requestId);
                store.put(userId + ":" + orderId, order);
                results.add(order);
                totalSum += total;
            }

            sendJson(req, res, 201, new BulkOrderResponse(userId, results.size(), results, totalSum, requestId));
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void listOrders(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");
            String prefix = userId + ":";
            List<OrderResponse> results = new ArrayList<>();

            store.forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    results.add(value);
                }
            });

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
            UserProfile body = MAPPER.readValue(bodyStr, UserProfile.class);

            var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
                body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
            profileStore.put(userId, profile);

            sendJson(req, res, 200, profile);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
    }

    private static void getProfile(ServerRequest req, ServerResponse res) {
        try {
            String userId = req.path().pathParameters().get("userId");
            String requestId = req.context().get("requestId", String.class).orElse("");

            UserProfile profile = profileStore.get(userId);
            if (profile == null) {
                sendJson(req, res, 404, new ErrorResponse("profile not found"));
                return;
            }

            var withReqId = new UserProfile(profile.userId(), profile.name(), profile.email(), profile.phone(),
                profile.address(), profile.preferences(), profile.paymentMethods(), profile.tags(), profile.metadata(), requestId);
            sendJson(req, res, 200, withReqId);
        } catch (Exception e) {
            errorHandler(req, res, e);
        }
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
            String clientIp = req.remotePeer().host();
            String userAgent = req.headers().contains(HeaderNames.USER_AGENT)
                    ? req.headers().get(HeaderNames.USER_AGENT).get()
                    : "";

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
