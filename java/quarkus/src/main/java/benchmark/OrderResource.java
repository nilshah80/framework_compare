package benchmark;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

@Path("/users/{userId}/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private final OrderStore store = OrderStore.getInstance();

    @POST
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
    @Path("/{orderId}")
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
    @Path("/{orderId}")
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
    @Path("/{orderId}")
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

    private String getRequestId(jakarta.ws.rs.container.ContainerRequestContext ctx) {
        Object id = ctx.getProperty(RequestIdFilter.REQUEST_ID_PROP);
        return id != null ? id.toString() : "";
    }
}
