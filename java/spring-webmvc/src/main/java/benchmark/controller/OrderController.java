package benchmark.controller;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import benchmark.pgstore.*;
import benchmark.pgstore.Order;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
public class OrderController {

    private static final PgStore store;

    static {
        String dsn = System.getenv("PG_DSN");
        if (dsn == null || dsn.isBlank()) {
            dsn = "postgres://postgres:postgres@localhost:5432/benchmark?sslmode=disable";
        }
        store = new PgStore(dsn);
        store.initSchema();
    }

    @PostMapping("/users/{userId}/orders")
    public ResponseEntity<?> createOrder(
            @PathVariable String userId,
            @RequestBody OrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        List<OrderItem> items = req.items().stream()
            .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        Order order = store.createOrder(userId, items, req.currency());

        return ResponseEntity.status(HttpStatus.CREATED).body(
            new OrderResponse(order.orderId(), order.userId(), order.status(),
                req.items(), order.total(), order.currency(), "*", requestId));
    }

    @PutMapping("/users/{userId}/orders/{orderId}")
    public ResponseEntity<?> updateOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @RequestBody OrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        List<OrderItem> items = req.items().stream()
            .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        Optional<Order> updated = store.updateOrder(userId, orderId, items, req.currency());
        if (updated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("order not found"));
        }

        Order o = updated.get();
        return ResponseEntity.ok(
            new OrderResponse(o.orderId(), o.userId(), o.status(),
                req.items(), o.total(), o.currency(), "*", requestId));
    }

    @DeleteMapping("/users/{userId}/orders/{orderId}")
    public ResponseEntity<?> deleteOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        boolean deleted = store.deleteOrder(userId, orderId);
        if (!deleted) {
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
        Optional<Order> result = store.getOrder(userId, orderId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("order not found"));
        }

        Order o = result.get();
        List<OrderRequest.Item> responseItems = o.items().stream()
            .map(i -> new OrderRequest.Item(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        return ResponseEntity.ok(
            new OrderResponse(o.orderId(), o.userId(), o.status(),
                responseItems, o.total(), o.currency(), fields, requestId));
    }

    @PostMapping("/users/{userId}/orders/bulk")
    public ResponseEntity<?> bulkCreateOrders(
            @PathVariable String userId,
            @RequestBody BulkOrderRequest req,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);

        List<BulkOrderInput> inputs = req.orders().stream()
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
            results.add(new OrderResponse(o.orderId(), o.userId(), o.status(),
                req.orders().get(i).items(), o.total(), o.currency(), null, requestId));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            new BulkOrderResponse(userId, results.size(), results, bulkResult.totalSum(), requestId));
    }

    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<?> listOrders(
            @PathVariable String userId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        List<Order> pgOrders = store.listOrders(userId);

        List<OrderResponse> orders = pgOrders.stream()
            .map(o -> {
                List<OrderRequest.Item> responseItems = o.items().stream()
                    .map(i -> new OrderRequest.Item(i.productId(), i.name(), i.quantity(), i.price()))
                    .collect(Collectors.toList());
                return new OrderResponse(o.orderId(), o.userId(), o.status(),
                    responseItems, o.total(), o.currency(), null, requestId);
            })
            .collect(Collectors.toList());

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

        benchmark.pgstore.Profile pgProfile = toStoreProfile(body);
        store.upsertProfile(userId, pgProfile);

        UserProfile profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<?> getProfile(
            @PathVariable String userId,
            HttpServletRequest servletReq) {

        String requestId = (String) servletReq.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        Optional<benchmark.pgstore.Profile> result = store.getProfile(userId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("profile not found"));
        }

        benchmark.pgstore.Profile p = result.get();
        UserProfile profile = fromStoreProfile(p, requestId);
        return ResponseEntity.ok(profile);
    }

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
        benchmark.model.Address addr = null;
        if (p.address() != null) {
            addr = new benchmark.model.Address(p.address().street(), p.address().city(),
                p.address().state(), p.address().zip(), p.address().country());
        }

        benchmark.model.Preferences prefs = null;
        if (p.preferences() != null) {
            benchmark.model.NotificationPrefs np = null;
            if (p.preferences().notifications() != null) {
                np = new benchmark.model.NotificationPrefs(
                    p.preferences().notifications().email(),
                    p.preferences().notifications().sms(),
                    p.preferences().notifications().push());
            }
            prefs = new benchmark.model.Preferences(p.preferences().language(),
                p.preferences().currency(), p.preferences().timezone(), np, p.preferences().theme());
        }

        List<benchmark.model.PaymentMethod> pms = null;
        if (p.paymentMethods() != null) {
            pms = p.paymentMethods().stream()
                .map(pm -> new benchmark.model.PaymentMethod(pm.type(), pm.last4(),
                    pm.expiryMonth(), pm.expiryYear(), pm.isDefault()))
                .collect(Collectors.toList());
        }

        return new UserProfile(p.userId(), p.name(), p.email(), p.phone(),
            addr, prefs, pms, p.tags(), p.metadata(), requestId);
    }
}
