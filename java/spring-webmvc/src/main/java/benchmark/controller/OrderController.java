package benchmark.controller;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @PostMapping("/users/{userId}/orders")
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

    @PutMapping("/users/{userId}/orders/{orderId}")
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

    @DeleteMapping("/users/{userId}/orders/{orderId}")
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

    @GetMapping("/users/{userId}/orders/{orderId}")
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

    @PostMapping("/users/{userId}/orders/bulk")
    public ResponseEntity<?> bulkCreateOrders(
            @PathVariable String userId,
            @RequestBody BulkOrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
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

        return ResponseEntity.status(HttpStatus.CREATED).body(
            new BulkOrderResponse(userId, results.size(), results, totalSum, requestId));
    }

    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<?> listOrders(
            @PathVariable String userId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        List<OrderResponse> orders = store.listByUser(userId);

        return ResponseEntity.ok(Map.of(
            "user_id", userId,
            "count", orders.size(),
            "orders", orders,
            "request_id", requestId != null ? requestId : ""
        ));
    }

    @PutMapping("/users/{userId}/profile")
    public ResponseEntity<?> putProfile(
            @PathVariable String userId,
            @RequestBody UserProfile body,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        UserProfile profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        store.putProfile(userId, profile);

        return ResponseEntity.ok(profile);
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<?> getProfile(
            @PathVariable String userId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        UserProfile profile = store.getProfile(userId);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("profile not found"));
        }

        UserProfile withReqId = new UserProfile(profile.userId(), profile.name(), profile.email(), profile.phone(),
            profile.address(), profile.preferences(), profile.paymentMethods(), profile.tags(), profile.metadata(), requestId);
        return ResponseEntity.ok(withReqId);
    }
}
