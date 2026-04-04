using Microsoft.AspNetCore.Mvc;

namespace controller_api;

[ApiController]
[Route("users/{userId}/orders")]
public class OrdersController(OrderStore store) : ControllerBase
{
    [HttpPost]
    public IActionResult CreateOrder(string userId, [FromBody] CreateOrderReq req)
    {
        var total = 0.0;
        foreach (var item in req.Items ?? [])
            total += item.Price * item.Quantity;

        var orderId = store.NextOrderId();
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var order = new OrderResponse
        {
            OrderId = orderId,
            UserId = userId,
            Status = "created",
            Items = req.Items ?? [],
            Total = total,
            Currency = string.IsNullOrEmpty(req.Currency) ? "USD" : req.Currency,
            Fields = "",
            RequestId = requestId
        };

        store.Set(OrderStore.Key(userId, orderId), order);
        return StatusCode(201, order);
    }

    [HttpPut("{orderId}")]
    public IActionResult UpdateOrder(string userId, string orderId, [FromBody] CreateOrderReq req)
    {
        var key = OrderStore.Key(userId, orderId);
        if (!store.Contains(key))
            return NotFound(new { error = "order not found" });

        var total = 0.0;
        foreach (var item in req.Items ?? [])
            total += item.Price * item.Quantity;

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var order = new OrderResponse
        {
            OrderId = orderId,
            UserId = userId,
            Status = "updated",
            Items = req.Items ?? [],
            Total = total,
            Currency = string.IsNullOrEmpty(req.Currency) ? "USD" : req.Currency,
            Fields = "",
            RequestId = requestId
        };

        store.Set(key, order);
        return Ok(order);
    }

    [HttpDelete("{orderId}")]
    public IActionResult DeleteOrder(string userId, string orderId)
    {
        var key = OrderStore.Key(userId, orderId);
        if (!store.TryRemove(key))
            return NotFound(new { error = "order not found" });

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        return Ok(new { message = "order deleted", order_id = orderId, request_id = requestId });
    }

    [HttpGet("{orderId}")]
    public IActionResult GetOrder(string userId, string orderId, [FromQuery] string? fields)
    {
        var key = OrderStore.Key(userId, orderId);
        if (!store.TryGet(key, out var order) || order is null)
            return NotFound(new { error = "order not found" });

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var result = new OrderResponse
        {
            OrderId = order.OrderId,
            UserId = order.UserId,
            Status = order.Status,
            Items = order.Items,
            Total = order.Total,
            Currency = order.Currency,
            Fields = fields ?? "*",
            RequestId = requestId
        };

        return Ok(result);
    }

    [HttpPost("bulk")]
    public IActionResult BulkCreateOrders(string userId, [FromBody] BulkCreateOrderReq req)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var results = new List<OrderResponse>();
        var totalSum = 0.0;

        foreach (var item in req.Orders ?? [])
        {
            var total = 0.0;
            foreach (var i in item.Items ?? [])
                total += i.Price * i.Quantity;

            var orderId = store.NextOrderId();
            var order = new OrderResponse
            {
                OrderId = orderId,
                UserId = userId,
                Status = "created",
                Items = item.Items ?? [],
                Total = total,
                Currency = string.IsNullOrEmpty(item.Currency) ? "USD" : item.Currency,
                Fields = "",
                RequestId = requestId
            };

            store.Set(OrderStore.Key(userId, orderId), order);
            results.Add(order);
            totalSum += total;
        }

        return StatusCode(201, new BulkOrderResponse
        {
            UserId = userId,
            Count = results.Count,
            Orders = results,
            TotalSum = totalSum,
            RequestId = requestId
        });
    }

    [HttpGet]
    public IActionResult ListOrders(string userId)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var orders = store.GetByUser(userId);
        foreach (var o in orders)
            o.RequestId = requestId;

        return Ok(new ListOrdersResponse
        {
            UserId = userId,
            Count = orders.Count,
            Orders = orders,
            RequestId = requestId
        });
    }
}

[ApiController]
[Route("users/{userId}/profile")]
public class ProfileController(ProfileStore profileStore) : ControllerBase
{
    [HttpPut]
    public IActionResult UpdateProfile(string userId, [FromBody] UserProfile profile)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        profile.UserId = userId;
        profile.RequestId = requestId;
        profileStore.Set(userId, profile);
        return Ok(profile);
    }

    [HttpGet]
    public IActionResult GetProfile(string userId)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        if (!profileStore.TryGet(userId, out var profile) || profile is null)
            return NotFound(new { error = "profile not found" });

        profile.RequestId = requestId;
        return Ok(profile);
    }
}
