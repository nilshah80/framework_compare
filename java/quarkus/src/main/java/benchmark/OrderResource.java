package benchmark;

import benchmark.filter.RequestIdFilter;
import benchmark.model.*;
import benchmark.pgstore.*;
import benchmark.pgstore.Order;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/users/{userId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private static final PgStore store;

    static {
        String dsn = System.getenv("PG_DSN");
        if (dsn == null || dsn.isBlank()) {
            dsn = "postgres://postgres:postgres@localhost:5432/benchmark?sslmode=disable";
        }
        store = new PgStore(dsn);
        store.initSchema();
    }

    @POST
    @Path("/orders")
    public Response createOrder(@PathParam("userId") String userId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                OrderRequest body) {
        String requestId = getRequestId(ctx);
        List<OrderItem> items = body.items().stream()
            .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        Order order = store.createOrder(userId, items, body.currency());

        var resp = new OrderResponse(order.orderId(), order.userId(), order.status(),
            body.items(), order.total(), order.currency(), null, requestId);
        return Response.status(201).entity(resp).build();
    }

    @PUT
    @Path("/orders/{orderId}")
    public Response updateOrder(@PathParam("userId") String userId,
                                @PathParam("orderId") String orderId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                OrderRequest body) {
        String requestId = getRequestId(ctx);
        List<OrderItem> items = body.items().stream()
            .map(i -> new OrderItem(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        Optional<Order> updated = store.updateOrder(userId, orderId, items, body.currency());
        if (updated.isEmpty()) {
            return Response.status(404).entity(new ErrorResponse("order not found")).build();
        }

        Order o = updated.get();
        var resp = new OrderResponse(o.orderId(), o.userId(), o.status(),
            body.items(), o.total(), o.currency(), null, requestId);
        return Response.ok(resp).build();
    }

    @DELETE
    @Path("/orders/{orderId}")
    public Response deleteOrder(@PathParam("userId") String userId,
                                @PathParam("orderId") String orderId,
                                @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);
        boolean deleted = store.deleteOrder(userId, orderId);
        if (!deleted) {
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
        Optional<Order> result = store.getOrder(userId, orderId);
        if (result.isEmpty()) {
            return Response.status(404).entity(new ErrorResponse("order not found")).build();
        }

        Order o = result.get();
        List<OrderRequest.Item> responseItems = o.items().stream()
            .map(i -> new OrderRequest.Item(i.productId(), i.name(), i.quantity(), i.price()))
            .collect(Collectors.toList());

        var resp = new OrderResponse(o.orderId(), o.userId(), o.status(),
            responseItems, o.total(), o.currency(), fields, requestId);
        return Response.ok(resp).build();
    }

    @POST
    @Path("/orders/bulk")
    public Response bulkCreateOrders(@PathParam("userId") String userId,
                                     @Context jakarta.ws.rs.container.ContainerRequestContext ctx,
                                     BulkOrderRequest body) {
        String requestId = getRequestId(ctx);

        List<BulkOrderInput> inputs = body.orders().stream()
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
                body.orders().get(i).items(), o.total(), o.currency(), null, requestId));
        }

        return Response.status(201).entity(new BulkOrderResponse(userId, results.size(), results, bulkResult.totalSum(), requestId)).build();
    }

    @GET
    @Path("/orders")
    public Response listOrders(@PathParam("userId") String userId,
                               @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);
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
        benchmark.pgstore.Profile pgProfile = toStoreProfile(body);
        store.upsertProfile(userId, pgProfile);

        var profile = new UserProfile(userId, body.name(), body.email(), body.phone(),
            body.address(), body.preferences(), body.paymentMethods(), body.tags(), body.metadata(), requestId);
        return Response.ok(profile).build();
    }

    @GET
    @Path("/profile")
    public Response getProfile(@PathParam("userId") String userId,
                               @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        String requestId = getRequestId(ctx);
        Optional<benchmark.pgstore.Profile> result = store.getProfile(userId);
        if (result.isEmpty()) {
            return Response.status(404).entity(new ErrorResponse("profile not found")).build();
        }
        benchmark.pgstore.Profile p = result.get();
        var withReqId = fromStoreProfile(p, requestId);
        return Response.ok(withReqId).build();
    }

    private String getRequestId(jakarta.ws.rs.container.ContainerRequestContext ctx) {
        Object id = ctx.getProperty(RequestIdFilter.REQUEST_ID_PROP);
        return id != null ? id.toString() : "";
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
