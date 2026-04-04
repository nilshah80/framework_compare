package benchmark;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/users/{userId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private final OrderStore store = OrderStore.getInstance();

    @POST
    @Path("/orders")
    public Response createOrder(@PathParam("userId") String userId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                OrderRequest body) {
        String requestId = getRequestId(ctx);
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

        return Response.status(201).entity(order).build();
    }

    @PUT
    @Path("/orders/{orderId}")
    public Response updateOrder(@PathParam("userId") String userId,
                                @PathParam("orderId") String orderId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                OrderRequest body) {
        String requestId = getRequestId(ctx);

        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return Response.status(404).entity(new ErrorResponse("order not found")).build();
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

        return Response.ok(updated).build();
    }

    @DELETE
    @Path("/orders/{orderId}")
    public Response deleteOrder(@PathParam("userId") String userId,
                                @PathParam("orderId") String orderId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);

        OrderResponse existing = store.remove(userId, orderId);
        if (existing == null) {
            return Response.status(404).entity(new ErrorResponse("order not found")).build();
        }

        return Response.ok(new DeleteResponse("order deleted", orderId, requestId)).build();
    }

    @GET
    @Path("/orders/{orderId}")
    public Response getOrder(@PathParam("userId") String userId,
                             @PathParam("orderId") String orderId,
                             @RestQuery @DefaultValue("*") String fields,
                             @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);

        OrderResponse existing = store.get(userId, orderId);
        if (existing == null) {
            return Response.status(404).entity(new ErrorResponse("order not found")).build();
        }

        var result = new OrderResponse(existing.orderId(), existing.userId(), existing.status(),
                existing.items(), existing.total(), existing.currency(), fields, requestId);
        return Response.ok(result).build();
    }

    @POST
    @Path("/orders/bulk")
    public Response bulkCreateOrders(@PathParam("userId") String userId,
                                     @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                     BulkOrderRequest body) {
        String requestId = getRequestId(ctx);
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

        return Response.status(201).entity(new BulkOrderResponse(userId, results.size(), results, totalSum, requestId)).build();
    }

    @GET
    @Path("/orders")
    public Response listOrders(@PathParam("userId") String userId,
                               @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);
        List<OrderResponse> orders = store.listByUser(userId);

        return Response.ok(Map.of(
            "user_id", userId,
            "count", orders.size(),
            "orders", orders,
            "request_id", requestId
        )).build();
    }

    @PUT
    @Path("/profile")
    public Response putProfile(@PathParam("userId") String userId,
                               @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                               UserProfile body) {
        String requestId = getRequestId(ctx);
        var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        store.putProfile(userId, profile);
        return Response.ok(profile).build();
    }

    @GET
    @Path("/profile")
    public Response getProfile(@PathParam("userId") String userId,
                               @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);
        UserProfile profile = store.getProfile(userId);
        if (profile == null) {
            return Response.status(404).entity(new ErrorResponse("profile not found")).build();
        }
        var withReqId = new UserProfile(profile.userId(), profile.name(), profile.email(), profile.phone(),
            profile.address(), profile.preferences(), profile.paymentMethods(), profile.tags(), profile.metadata(), requestId);
        return Response.ok(withReqId).build();
    }

    private String getRequestId(jakarta.ws.rs.container.ContainerRequestContext ctx) {
        Object id = ctx.getProperty(RequestIdFilter.REQUEST_ID_PROP);
        return id != null ? id.toString() : "";
    }
}
