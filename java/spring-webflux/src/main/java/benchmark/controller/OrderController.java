package benchmark.controller;

import benchmark.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/users/{userId}/orders")
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @PostMapping
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

    @PutMapping("/{orderId}")
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

    @DeleteMapping("/{orderId}")
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

    @GetMapping("/{orderId}")
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
}
