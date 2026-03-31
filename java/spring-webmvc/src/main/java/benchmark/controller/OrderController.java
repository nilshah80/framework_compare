package benchmark.controller;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/{userId}/orders")
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @PostMapping
    public ResponseEntity<?> createOrder(
            @PathVariable String userId,
            @RequestBody OrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();
        double total = req.items().stream().mapToDouble(i -> i.quantity() * i.price()).sum();
        String orderId = String.valueOf(store.nextId());

        OrderResponse order = new OrderResponse(orderId, userId, "created", req.items(), total, currency, "*", requestId);
        store.put(userId, orderId, order);

        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PutMapping("/{orderId}")
    public ResponseEntity<?> updateOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @RequestBody OrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("order not found"));
        }

        String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();
        double total = req.items().stream().mapToDouble(i -> i.quantity() * i.price()).sum();

        OrderResponse updated = new OrderResponse(orderId, userId, "updated", req.items(), total, currency, "*", requestId);
        store.put(userId, orderId, updated);

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<?> deleteOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        OrderResponse existing = store.remove(userId, orderId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("order not found"));
        }

        return ResponseEntity.ok(new DeleteResponse("order deleted", orderId, requestId));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @RequestParam(value = "fields", defaultValue = "*") String fields,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("order not found"));
        }

        OrderResponse withFields = new OrderResponse(
            existing.orderId(), existing.userId(), existing.status(),
            existing.items(), existing.total(), existing.currency(),
            fields, requestId
        );

        return ResponseEntity.ok(withFields);
    }
}
