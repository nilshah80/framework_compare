using Microsoft.AspNetCore.Mvc;
using PgStore;

namespace controller_api;

[ApiController]
[Route("users/{userId}/orders")]
public class OrdersController(PgStore.PgStore store) : ControllerBase
{
    [HttpPost]
    public async Task<IActionResult> CreateOrder(string userId, [FromBody] CreateOrderReq req)
    {
        var items = Mapping.MapItems(req.Items);
        var order = await store.CreateOrderAsync(userId, items, req.Currency);
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        return StatusCode(201, Mapping.ToResponse(order, "", requestId));
    }

    [HttpPut("{orderId}")]
    public async Task<IActionResult> UpdateOrder(string userId, string orderId, [FromBody] CreateOrderReq req)
    {
        var items = Mapping.MapItems(req.Items);
        var order = await store.UpdateOrderAsync(userId, orderId, items, req.Currency);
        if (order is null)
            return NotFound(new { error = "order not found" });

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        return Ok(Mapping.ToResponse(order, "", requestId));
    }

    [HttpDelete("{orderId}")]
    public async Task<IActionResult> DeleteOrder(string userId, string orderId)
    {
        var deleted = await store.DeleteOrderAsync(userId, orderId);
        if (!deleted)
            return NotFound(new { error = "order not found" });

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        return Ok(new { message = "order deleted", order_id = orderId, request_id = requestId });
    }

    [HttpGet("{orderId}")]
    public async Task<IActionResult> GetOrder(string userId, string orderId, [FromQuery] string? fields)
    {
        var order = await store.GetOrderAsync(userId, orderId);
        if (order is null)
            return NotFound(new { error = "order not found" });

        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        return Ok(Mapping.ToResponse(order, fields ?? "*", requestId));
    }

    [HttpPost("bulk")]
    public async Task<IActionResult> BulkCreateOrders(string userId, [FromBody] BulkCreateOrderReq req)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var inputs = (req.Orders ?? []).Select(o => new BulkOrderInput
        {
            Items = Mapping.MapItems(o.Items),
            Currency = o.Currency
        }).ToList();

        var (orders, totalSum) = await store.BulkCreateOrdersAsync(userId, inputs);
        var results = orders.Select(o => Mapping.ToResponse(o, "", requestId)).ToList();

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
    public async Task<IActionResult> ListOrders(string userId)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var orders = await store.ListOrdersAsync(userId);
        var results = orders.Select(o => Mapping.ToResponse(o, "", requestId)).ToList();

        return Ok(new ListOrdersResponse
        {
            UserId = userId,
            Count = results.Count,
            Orders = results,
            RequestId = requestId
        });
    }
}

[ApiController]
[Route("users/{userId}/profile")]
public class ProfileController(PgStore.PgStore store) : ControllerBase
{
    [HttpPut]
    public async Task<IActionResult> UpdateProfile(string userId, [FromBody] UserProfile profile)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var p = Mapping.MapProfile(profile);
        await store.UpsertProfileAsync(userId, p);
        return Ok(Mapping.ToProfileResp(p, requestId));
    }

    [HttpGet]
    public async Task<IActionResult> GetProfile(string userId)
    {
        var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
        var profile = await store.GetProfileAsync(userId);
        if (profile is null)
            return NotFound(new { error = "profile not found" });

        return Ok(Mapping.ToProfileResp(profile, requestId));
    }
}

public static class Mapping
{
    public static List<PgStore.OrderItem> MapItems(List<OrderItem>? items) =>
        (items ?? []).Select(i => new PgStore.OrderItem
        {
            ProductId = i.ProductId, Name = i.Name, Quantity = i.Quantity, Price = i.Price
        }).ToList();

    public static OrderResponse ToResponse(Order o, string fields, string requestId) => new()
    {
        OrderId = o.OrderId, UserId = o.UserId, Status = o.Status,
        Items = o.Items.Select(i => new OrderItem
        {
            ProductId = i.ProductId, Name = i.Name, Quantity = i.Quantity, Price = i.Price
        }).ToList(),
        Total = o.Total, Currency = o.Currency, Fields = fields, RequestId = requestId
    };

    public static Profile MapProfile(UserProfile r) => new()
    {
        Name = r.Name, Email = r.Email, Phone = r.Phone,
        Address = new PgStore.Address
        {
            Street = r.Address.Street, City = r.Address.City,
            State = r.Address.State, Zip = r.Address.Zip, Country = r.Address.Country
        },
        Preferences = new PgStore.Preferences
        {
            Language = r.Preferences.Language, Currency = r.Preferences.Currency,
            Timezone = r.Preferences.Timezone, Theme = r.Preferences.Theme,
            Notifications = new PgStore.NotificationPrefs
            {
                Email = r.Preferences.Notifications.Email,
                Sms = r.Preferences.Notifications.Sms,
                Push = r.Preferences.Notifications.Push
            }
        },
        PaymentMethods = r.PaymentMethods.Select(pm => new PgStore.PaymentMethod
        {
            Type = pm.Type, Last4 = pm.Last4, ExpiryMonth = pm.ExpiryMonth,
            ExpiryYear = pm.ExpiryYear, IsDefault = pm.IsDefault
        }).ToList(),
        Tags = r.Tags, Metadata = r.Metadata
    };

    public static UserProfile ToProfileResp(Profile p, string requestId) => new()
    {
        UserId = p.UserId, Name = p.Name, Email = p.Email, Phone = p.Phone,
        Address = new Address
        {
            Street = p.Address.Street, City = p.Address.City,
            State = p.Address.State, Zip = p.Address.Zip, Country = p.Address.Country
        },
        Preferences = new Preferences
        {
            Language = p.Preferences.Language, Currency = p.Preferences.Currency,
            Timezone = p.Preferences.Timezone, Theme = p.Preferences.Theme,
            Notifications = new NotificationPrefs
            {
                Email = p.Preferences.Notifications.Email,
                Sms = p.Preferences.Notifications.Sms,
                Push = p.Preferences.Notifications.Push
            }
        },
        PaymentMethods = p.PaymentMethods.Select(pm => new PaymentMethod
        {
            Type = pm.Type, Last4 = pm.Last4, ExpiryMonth = pm.ExpiryMonth,
            ExpiryYear = pm.ExpiryYear, IsDefault = pm.IsDefault
        }).ToList(),
        Tags = p.Tags, Metadata = p.Metadata, RequestId = requestId
    };
}
