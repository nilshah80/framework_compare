package benchmark.controller;

import benchmark.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @PostMapping("/users/{userId}/orders")
    public Mono<ResponseEntity<?>> createOrder(
            @PathVariable String userId,
            @RequestBody OrderRequest req,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();
        double total = req.items().stream().mapToDouble(i -> i.quantity() * i.price()).sum();
        String orderId = String.valueOf(store.nextId());

        OrderResponse order = new OrderResponse(orderId, userId, "created", req.items(), total, currency, "*", requestId);
        store.put(userId, orderId, order);

        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body((Object) order));
    }

    @PutMapping("/users/{userId}/orders/{orderId}")
    public Mono<ResponseEntity<?>> updateOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @RequestBody OrderRequest req,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) new ErrorResponse("order not found")));
        }

        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();
        double total = req.items().stream().mapToDouble(i -> i.quantity() * i.price()).sum();

        OrderResponse updated = new OrderResponse(orderId, userId, "updated", req.items(), total, currency, "*", requestId);
        store.put(userId, orderId, updated);

        return Mono.just(ResponseEntity.ok((Object) updated));
    }

    @DeleteMapping("/users/{userId}/orders/{orderId}")
    public Mono<ResponseEntity<?>> deleteOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        OrderResponse existing = store.remove(userId, orderId);
        if (existing == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) new ErrorResponse("order not found")));
        }

        return Mono.just(ResponseEntity.ok((Object) new DeleteResponse("order deleted", orderId, requestId)));
    }

    @GetMapping("/users/{userId}/orders/{orderId}")
    public Mono<ResponseEntity<?>> getOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @RequestParam(value = "fields", defaultValue = "*") String fields,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) new ErrorResponse("order not found")));
        }

        OrderResponse withFields = new OrderResponse(
            existing.orderId(), existing.userId(), existing.status(),
            existing.items(), existing.total(), existing.currency(),
            fields, requestId
        );

        return Mono.just(ResponseEntity.ok((Object) withFields));
    }

    @PostMapping("/users/{userId}/orders/bulk")
    public Mono<ResponseEntity<?>> bulkCreateOrders(
            @PathVariable String userId,
            @RequestBody BulkOrderRequest req,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        List<OrderResponse> results = new ArrayList<>();
        double totalSum = 0;

        for (OrderRequest orderReq : req.orders()) {
            String currency = (orderReq.currency() == null || orderReq.currency().isBlank()) ? "USD" : orderReq.currency();
            double total = orderReq.items().stream().mapToDouble(i -> i.quantity() * i.price()).sum();
            String orderId = String.valueOf(store.nextId());

            OrderResponse order = new OrderResponse(orderId, userId, "created", orderReq.items(), total, currency, null, requestId);
            store.put(userId, orderId, order);
            results.add(order);
            totalSum += total;
        }

        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(
            (Object) new BulkOrderResponse(userId, results.size(), results, totalSum, requestId)));
    }

    @GetMapping("/users/{userId}/orders")
    public Mono<ResponseEntity<?>> listOrders(
            @PathVariable String userId,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        List<OrderResponse> orders = store.listByUser(userId);

        return Mono.just(ResponseEntity.ok((Object) Map.of(
            "user_id", userId,
            "count", orders.size(),
            "orders", orders,
            "request_id", requestId != null ? requestId : ""
        )));
    }

    @PutMapping("/users/{userId}/profile")
    public Mono<ResponseEntity<?>> putProfile(
            @PathVariable String userId,
            @RequestBody UserProfile body,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        UserProfile profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        store.putProfile(userId, profile);

        return Mono.just(ResponseEntity.ok((Object) profile));
    }

    @GetMapping("/users/{userId}/profile")
    public Mono<ResponseEntity<?>> getProfile(
            @PathVariable String userId,
            ServerWebExchange exchange) {

        String requestId = exchange.getAttribute("requestId");
        UserProfile profile = store.getProfile(userId);
        if (profile == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) new ErrorResponse("profile not found")));
        }

        UserProfile withReqId = new UserProfile(profile.userId(), profile.name(), profile.email(), profile.phone(),
            profile.address(), profile.preferences(), profile.paymentMethods(), profile.tags(), profile.metadata(), requestId);
        return Mono.just(ResponseEntity.ok((Object) withReqId));
    }
}
