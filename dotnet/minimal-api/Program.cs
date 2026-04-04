using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

var builder = WebApplication.CreateBuilder(args);
builder.Logging.ClearProviders();

builder.Services.AddCors(options =>
{
    options.AddDefaultPolicy(policy =>
    {
        policy.AllowAnyOrigin()
              .WithMethods("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
              .WithHeaders("Origin", "Content-Type", "Accept", "Authorization");
    });
});

builder.WebHost.ConfigureKestrel(options =>
{
    options.ListenAnyIP(8093);
    options.Limits.MaxRequestBodySize = 1 << 20; // 1 MB
});

var app = builder.Build();
app.UseCors();

// ── Middleware: Recovery ──────────────────────────────────────────────
app.Use(async (context, next) =>
{
    try
    {
        await next(context);
    }
    catch (Exception ex)
    {
        LogEntry("ERROR", "panic_recovered", new { error = ex.Message, stack = ex.StackTrace });
        context.Response.StatusCode = 500;
        context.Response.ContentType = "application/json";
        await context.Response.WriteAsync("{\"error\":\"Internal Server Error\"}");
    }
});

// ── Middleware: Request ID ────────────────────────────────────────────
app.Use(async (context, next) =>
{
    var requestId = context.Request.Headers["X-Request-ID"].FirstOrDefault() ?? Guid.NewGuid().ToString();
    context.Items["RequestId"] = requestId;
    context.Response.Headers["X-Request-ID"] = requestId;
    await next(context);
});

// ── Middleware: Security Headers ──────────────────────────────────────
app.Use(async (context, next) =>
{
    context.Response.Headers["X-XSS-Protection"] = "1; mode=block";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Response.Headers["X-Frame-Options"] = "DENY";
    context.Response.Headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains";
    context.Response.Headers["Content-Security-Policy"] = "default-src 'self'";
    context.Response.Headers["Referrer-Policy"] = "strict-origin-when-cross-origin";
    context.Response.Headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()";
    context.Response.Headers["Cross-Origin-Opener-Policy"] = "same-origin";
    await next(context);
});

// ── Middleware: Structured Logger ─────────────────────────────────────
app.Use(async (context, next) =>
{
    var sw = Stopwatch.StartNew();
    var originalBody = context.Response.Body;
    using var memStream = new MemoryStream();
    context.Response.Body = memStream;

    await next(context);

    sw.Stop();
    memStream.Seek(0, SeekOrigin.Begin);
    var responseBody = await new StreamReader(memStream).ReadToEndAsync();
    memStream.Seek(0, SeekOrigin.Begin);
    await memStream.CopyToAsync(originalBody);
    context.Response.Body = originalBody;

    var requestId = context.Items["RequestId"]?.ToString() ?? "";
    var query = context.Request.Query.ToDictionary(q => q.Key, q => q.Value.ToString());

    LogEntry("INFO", "http_dump", new
    {
        request_id = requestId,
        method = context.Request.Method,
        path = context.Request.Path.Value,
        query,
        client_ip = context.Connection.RemoteIpAddress?.ToString() ?? "",
        user_agent = context.Request.Headers.UserAgent.ToString(),
        request_headers = RedactHeaders(context.Request.Headers),
        status = context.Response.StatusCode,
        latency = $"{sw.Elapsed.TotalMilliseconds:F3}ms",
        latency_ms = Math.Round(sw.Elapsed.TotalMilliseconds, 3),
        response_headers = RedactHeaders(context.Response.Headers),
        response_body = responseBody,
        bytes_out = responseBody.Length
    });
});

// ── In-memory store ──────────────────────────────────────────────────
var orderStore = new ConcurrentDictionary<string, OrderResponse>();
var profileStore = new ConcurrentDictionary<string, UserProfile>();
var orderCounter = 0;

string NextOrderId() => Interlocked.Increment(ref orderCounter).ToString();
string StoreKey(string userId, string orderId) => $"{userId}:{orderId}";

// ── Routes ───────────────────────────────────────────────────────────

app.MapPost("/users/{userId}/orders", (HttpContext ctx, string userId, CreateOrderReq req) =>
{
    var total = 0.0;
    foreach (var item in req.Items ?? [])
        total += item.Price * item.Quantity;

    var orderId = NextOrderId();
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
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

    orderStore[StoreKey(userId, orderId)] = order;
    return Results.Created($"/users/{userId}/orders/{orderId}", order);
});

app.MapPut("/users/{userId}/orders/{orderId}", (HttpContext ctx, string userId, string orderId, CreateOrderReq req) =>
{
    var key = StoreKey(userId, orderId);
    if (!orderStore.ContainsKey(key))
        return Results.Json(new { error = "order not found" }, statusCode: 404);

    var total = 0.0;
    foreach (var item in req.Items ?? [])
        total += item.Price * item.Quantity;

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
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

    orderStore[key] = order;
    return Results.Ok(order);
});

app.MapDelete("/users/{userId}/orders/{orderId}", (HttpContext ctx, string userId, string orderId) =>
{
    var key = StoreKey(userId, orderId);
    if (!orderStore.TryRemove(key, out _))
        return Results.Json(new { error = "order not found" }, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Ok(new { message = "order deleted", order_id = orderId, request_id = requestId });
});

app.MapGet("/users/{userId}/orders/{orderId}", (HttpContext ctx, string userId, string orderId, string? fields) =>
{
    var key = StoreKey(userId, orderId);
    if (!orderStore.TryGetValue(key, out var order))
        return Results.Json(new { error = "order not found" }, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
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

    return Results.Ok(result);
});

// POST /users/{userId}/orders/bulk — Bulk Create Orders
app.MapPost("/users/{userId}/orders/bulk", (HttpContext ctx, string userId, BulkCreateOrderReq req) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var results = new List<OrderResponse>();
    var totalSum = 0.0;

    foreach (var item in req.Orders ?? [])
    {
        var total = 0.0;
        foreach (var i in item.Items ?? [])
            total += i.Price * i.Quantity;

        var orderId = NextOrderId();
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

        orderStore[StoreKey(userId, orderId)] = order;
        results.Add(order);
        totalSum += total;
    }

    return Results.Created($"/users/{userId}/orders", new BulkOrderResponse
    {
        UserId = userId,
        Count = results.Count,
        Orders = results,
        TotalSum = totalSum,
        RequestId = requestId
    });
});

// GET /users/{userId}/orders — List All Orders
app.MapGet("/users/{userId}/orders", (HttpContext ctx, string userId) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var prefix = $"{userId}:";
    var results = new List<OrderResponse>();

    foreach (var kvp in orderStore)
    {
        if (kvp.Key.StartsWith(prefix))
        {
            var order = kvp.Value;
            order.RequestId = requestId;
            results.Add(order);
        }
    }

    return Results.Ok(new ListOrdersResponse
    {
        UserId = userId,
        Count = results.Count,
        Orders = results,
        RequestId = requestId
    });
});

// PUT /users/{userId}/profile — Create/Update Profile
app.MapPut("/users/{userId}/profile", (HttpContext ctx, string userId, UserProfile profile) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    profile.UserId = userId;
    profile.RequestId = requestId;
    profileStore[userId] = profile;
    return Results.Ok(profile);
});

// GET /users/{userId}/profile — Get Profile
app.MapGet("/users/{userId}/profile", (HttpContext ctx, string userId) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    if (!profileStore.TryGetValue(userId, out var profile))
        return Results.Json(new { error = "profile not found" }, statusCode: 404);

    profile.RequestId = requestId;
    return Results.Ok(profile);
});

LogEntry("INFO", "server starting", new { port = "8093" });
app.Run();

// ── Helpers ──────────────────────────────────────────────────────────

static void LogEntry(string level, string msg, object fields)
{
    var entry = new Dictionary<string, object>
    {
        ["time"] = DateTime.UtcNow.ToString("O"),
        ["level"] = level,
        ["msg"] = msg
    };
    foreach (var prop in fields.GetType().GetProperties())
        entry[prop.Name] = prop.GetValue(fields) ?? "";
    Console.WriteLine(JsonSerializer.Serialize(entry));
}

static Dictionary<string, string> RedactHeaders(IHeaderDictionary headers)
{
    HashSet<string> redacted = new(StringComparer.OrdinalIgnoreCase)
    {
        "Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token"
    };
    var result = new Dictionary<string, string>();
    foreach (var h in headers)
        result[h.Key] = redacted.Contains(h.Key) ? "[REDACTED]" : h.Value.ToString();
    return result;
}

// ── Models ───────────────────────────────────────────────────────────

public class CreateOrderReq
{
    [JsonPropertyName("items")]
    public List<OrderItem> Items { get; set; } = [];

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";
}

public class OrderItem
{
    [JsonPropertyName("product_id")]
    public string ProductId { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("quantity")]
    public int Quantity { get; set; }

    [JsonPropertyName("price")]
    public double Price { get; set; }
}

public class OrderResponse
{
    [JsonPropertyName("order_id")]
    public string OrderId { get; set; } = "";

    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("status")]
    public string Status { get; set; } = "";

    [JsonPropertyName("items")]
    public List<OrderItem> Items { get; set; } = [];

    [JsonPropertyName("total")]
    public double Total { get; set; }

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";

    [JsonPropertyName("fields")]
    public string Fields { get; set; } = "";

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}

public class BulkCreateOrderReq
{
    [JsonPropertyName("orders")]
    public List<CreateOrderReq> Orders { get; set; } = [];
}

public class BulkOrderResponse
{
    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("count")]
    public int Count { get; set; }

    [JsonPropertyName("orders")]
    public List<OrderResponse> Orders { get; set; } = [];

    [JsonPropertyName("total_sum")]
    public double TotalSum { get; set; }

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}

public class ListOrdersResponse
{
    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("count")]
    public int Count { get; set; }

    [JsonPropertyName("orders")]
    public List<OrderResponse> Orders { get; set; } = [];

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}

public class UserProfile
{
    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("email")]
    public string Email { get; set; } = "";

    [JsonPropertyName("phone")]
    public string Phone { get; set; } = "";

    [JsonPropertyName("address")]
    public Address Address { get; set; } = new();

    [JsonPropertyName("preferences")]
    public Preferences Preferences { get; set; } = new();

    [JsonPropertyName("payment_methods")]
    public List<PaymentMethod> PaymentMethods { get; set; } = [];

    [JsonPropertyName("tags")]
    public List<string> Tags { get; set; } = [];

    [JsonPropertyName("metadata")]
    public Dictionary<string, string> Metadata { get; set; } = new();

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}

public class Address
{
    [JsonPropertyName("street")]
    public string Street { get; set; } = "";

    [JsonPropertyName("city")]
    public string City { get; set; } = "";

    [JsonPropertyName("state")]
    public string State { get; set; } = "";

    [JsonPropertyName("zip")]
    public string Zip { get; set; } = "";

    [JsonPropertyName("country")]
    public string Country { get; set; } = "";
}

public class Preferences
{
    [JsonPropertyName("language")]
    public string Language { get; set; } = "";

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";

    [JsonPropertyName("timezone")]
    public string Timezone { get; set; } = "";

    [JsonPropertyName("notifications")]
    public NotificationPrefs Notifications { get; set; } = new();

    [JsonPropertyName("theme")]
    public string Theme { get; set; } = "";
}

public class NotificationPrefs
{
    [JsonPropertyName("email")]
    public bool Email { get; set; }

    [JsonPropertyName("sms")]
    public bool Sms { get; set; }

    [JsonPropertyName("push")]
    public bool Push { get; set; }
}

public class PaymentMethod
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("last4")]
    public string Last4 { get; set; } = "";

    [JsonPropertyName("expiry_month")]
    public int ExpiryMonth { get; set; }

    [JsonPropertyName("expiry_year")]
    public int ExpiryYear { get; set; }

    [JsonPropertyName("is_default")]
    public bool IsDefault { get; set; }
}
