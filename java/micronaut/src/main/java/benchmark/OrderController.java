package benchmark;

import benchmark.model.*;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller("/users/{userId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @Post("/orders")
    public HttpResponse<?> createOrder(@PathVariable String userId,
                                       @Body OrderRequest body,
                                       HttpRequest<?> request) {
        String requestId = getRequestId(request);
        String orderId = String.valueOf(store.nextId());

        double total = 0;
        if (body.items() != null) {
            for (var item : body.items()) {
                total += item.price() * item.quantity();
            }
        }

        String currency = (body.currency() == null || body.currency().isBlank()) ? "USD" : body.currency();

        var order = new OrderResponse(orderId, userId, "created", body.items(), total, currency, null, requestId);
        store.put(userId, orderId, order);

        return HttpResponse.created(order);
    }

    @Put("/orders/{orderId}")
    public HttpResponse<?> updateOrder(@PathVariable String userId,
                                       @PathVariable String orderId,
                                       @Body OrderRequest body,
                                       HttpRequest<?> request) {
        String requestId = getRequestId(request);

        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return HttpResponse.notFound(new ErrorResponse("order not found"));
        }

        double total = 0;
        if (body.items() != null) {
            for (var item : body.items()) {
                total += item.price() * item.quantity();
            }
        }

        String currency = (body.currency() == null || body.currency().isBlank()) ? "USD" : body.currency();

        var updated = new OrderResponse(orderId, userId, "updated", body.items(), total, currency, null, requestId);
        store.put(userId, orderId, updated);

        return HttpResponse.ok(updated);
    }

    @Delete("/orders/{orderId}")
    public HttpResponse<?> deleteOrder(@PathVariable String userId,
                                       @PathVariable String orderId,
                                       HttpRequest<?> request) {
        String requestId = getRequestId(request);

        OrderResponse existing = store.remove(userId, orderId);
        if (existing == null) {
            return HttpResponse.notFound(new ErrorResponse("order not found"));
        }

        return HttpResponse.ok(new DeleteResponse("order deleted", orderId, requestId));
    }

    @Get("/orders/{orderId}{?fields}")
    public HttpResponse<?> getOrder(@PathVariable String userId,
                                    @PathVariable String orderId,
                                    @QueryValue(defaultValue = "*") String fields,
                                    HttpRequest<?> request) {
        String requestId = getRequestId(request);

        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return HttpResponse.notFound(new ErrorResponse("order not found"));
        }

        var result = new OrderResponse(existing.orderId(), existing.userId(), existing.status(),
                existing.items(), existing.total(), existing.currency(), fields, requestId);
        return HttpResponse.ok(result);
    }

    @Post("/orders/bulk")
    public HttpResponse<?> bulkCreateOrders(@PathVariable String userId,
                                            @Body BulkOrderRequest body,
                                            HttpRequest<?> request) {
        String requestId = getRequestId(request);
        List<OrderResponse> results = new ArrayList<>();
        double totalSum = 0;

        for (OrderRequest orderReq : body.orders()) {
            String orderId = String.valueOf(store.nextId());
            double total = 0;
            if (orderReq.items() != null) {
                for (var item : orderReq.items()) {
                    total += item.price() * item.quantity();
                }
            }
            String currency = (orderReq.currency() == null || orderReq.currency().isBlank()) ? "USD" : orderReq.currency();
            var order = new OrderResponse(orderId, userId, "created", orderReq.items(), total, currency, null, requestId);
            store.put(userId, orderId, order);
            results.add(order);
            totalSum += total;
        }

        return HttpResponse.created(new BulkOrderResponse(userId, results.size(), results, totalSum, requestId));
    }

    @Get("/orders")
    public HttpResponse<?> listOrders(@PathVariable String userId,
                                      HttpRequest<?> request) {
        String requestId = getRequestId(request);
        List<OrderResponse> orders = store.listByUser(userId);

        return HttpResponse.ok(Map.of(
            "user_id", userId,
            "count", orders.size(),
            "orders", orders,
            "request_id", requestId
        ));
    }

    @Put("/profile")
    public HttpResponse<?> putProfile(@PathVariable String userId,
                                      @Body UserProfile body,
                                      HttpRequest<?> request) {
        String requestId = getRequestId(request);
        var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        store.putProfile(userId, profile);
        return HttpResponse.ok(profile);
    }

    @Get("/profile")
    public HttpResponse<?> getProfile(@PathVariable String userId,
                                      HttpRequest<?> request) {
        String requestId = getRequestId(request);
        UserProfile profile = store.getProfile(userId);
        if (profile == null) {
            return HttpResponse.notFound(new ErrorResponse("profile not found"));
        }
        var withReqId = new UserProfile(profile.userId(), profile.name(), profile.email(), profile.phone(),
            profile.address(), profile.preferences(), profile.paymentMethods(), profile.tags(), profile.metadata(), requestId);
        return HttpResponse.ok(withReqId);
    }

    private String getRequestId(HttpRequest<?> request) {
        return request.getAttribute("requestId", String.class).orElse("");
    }
}
