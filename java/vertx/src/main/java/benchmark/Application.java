package benchmark;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Application {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, OrderResponse> store = new ConcurrentHashMap<>();
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

    // --- Main ---

    public static void main(String[] args) {
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

        router.post(basePath).handler(Application::createOrder);
        router.put(basePath + "/:orderId").handler(Application::updateOrder);
        router.delete(basePath + "/:orderId").handler(Application::deleteOrder);
        router.get(basePath + "/:orderId").handler(Application::getOrder);

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

            sendJson(ctx, 201, order);
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

            String key = userId + ":" + orderId;
            OrderResponse existing = store.get(key);
            if (existing == null) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
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

            sendJson(ctx, 200, updated);
        } catch (Exception e) {
            ctx.fail(e);
        }
    }

    private static void deleteOrder(RoutingContext ctx) {
        try {
            String userId = ctx.pathParam("userId");
            String orderId = ctx.pathParam("orderId");
            String requestId = ctx.get("requestId");

            String key = userId + ":" + orderId;
            OrderResponse existing = store.remove(key);
            if (existing == null) {
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

            String key = userId + ":" + orderId;
            OrderResponse existing = store.get(key);
            if (existing == null) {
                sendJson(ctx, 404, new ErrorResponse("order not found"));
                return;
            }

            var result = new OrderResponse(existing.orderId(), existing.userId(), existing.status(),
                    existing.items(), existing.total(), existing.currency(), fields, requestId);
            sendJson(ctx, 200, result);
        } catch (Exception e) {
            ctx.fail(e);
        }
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
            ctx.request().params().forEach(entry -> {
                // skip path params
                if (!entry.getKey().equals("userId") && !entry.getKey().equals("orderId")) {
                    queryParams.put(entry.getKey(), entry.getValue());
                }
            });
            // re-read query params properly
            queryParams.clear();
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

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("level", "INFO");
            logEntry.put("message", "http_dump");
            logEntry.put("request_id", requestId != null ? requestId : "");
            logEntry.put("method", ctx.request().method().name());
            logEntry.put("path", ctx.request().path());
            logEntry.put("query", queryParams);
            logEntry.put("client_ip", ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : "");
            logEntry.put("user_agent", ctx.request().getHeader("User-Agent"));
            logEntry.put("request_headers", reqHeaders);
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
