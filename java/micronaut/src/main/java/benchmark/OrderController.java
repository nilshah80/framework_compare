package benchmark;

import benchmark.model.*;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;

@Controller("/users/{userId}/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderController {

    private final OrderStore store = OrderStore.getInstance();

    @Post
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

    @Put("/{orderId}")
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

    @Delete("/{orderId}")
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

    @Get("/{orderId}{?fields}")
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

    private String getRequestId(HttpRequest<?> request) {
        return request.getAttribute("requestId", String.class).orElse("");
    }
}
