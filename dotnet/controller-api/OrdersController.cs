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
}
