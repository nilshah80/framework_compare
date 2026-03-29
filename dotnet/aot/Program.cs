using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

var builder = WebApplication.CreateSlimBuilder(args);
builder.Logging.ClearProviders();

builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.TypeInfoResolverChain.Insert(0, AppJsonContext.Default);
});

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
    options.ListenAnyIP(8095);
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
        LogEntry("ERROR", "panic_recovered", new Dictionary<string, object>
        {
            ["error"] = ex.Message,
            ["stack"] = ex.StackTrace ?? ""
        });
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
    var query = new Dictionary<string, string>();
    foreach (var q in context.Request.Query)
        query[q.Key] = q.Value.ToString();

    var logData = new Dictionary<string, object>
    {
        ["request_id"] = requestId,
        ["method"] = context.Request.Method,
        ["path"] = context.Request.Path.Value ?? "",
        ["query"] = query,
        ["client_ip"] = context.Connection.RemoteIpAddress?.ToString() ?? "",
        ["user_agent"] = context.Request.Headers.UserAgent.ToString(),
        ["request_headers"] = RedactHeaders(context.Request.Headers),
        ["status"] = context.Response.StatusCode,
        ["latency"] = $"{sw.Elapsed.TotalMilliseconds:F3}ms",
        ["latency_ms"] = Math.Round(sw.Elapsed.TotalMilliseconds, 3),
        ["response_headers"] = RedactHeaders(context.Response.Headers),
        ["response_body"] = responseBody,
        ["bytes_out"] = responseBody.Length
    };

    LogEntry("INFO", "http_dump", logData);
});

// ── In-memory store ──────────────────────────────────────────────────
var orderStore = new ConcurrentDictionary<string, OrderResponse>();
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
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

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
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Ok(new DeleteResponse { Message = "order deleted", OrderId = orderId, RequestId = requestId });
});

app.MapGet("/users/{userId}/orders/{orderId}", (HttpContext ctx, string userId, string orderId, string? fields) =>
{
    var key = StoreKey(userId, orderId);
    if (!orderStore.TryGetValue(key, out var order))
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

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

LogEntry("INFO", "server starting", new Dictionary<string, object> { ["port"] = "8095" });
app.Run();

// ── Helpers ──────────────────────────────────────────────────────────

static void LogEntry(string level, string msg, Dictionary<string, object> fields)
{
    fields["time"] = DateTime.UtcNow.ToString("O");
    fields["level"] = level;
    fields["msg"] = msg;
    Console.WriteLine(JsonSerializer.Serialize(fields, AppJsonContext.Default.DictionaryStringObject));
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

// ── Models (AOT-compatible with source generators) ───────────────────

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

public class ErrorResponse
{
    [JsonPropertyName("error")]
    public string Error { get; set; } = "";
}

public class DeleteResponse
{
    [JsonPropertyName("message")]
    public string Message { get; set; } = "";

    [JsonPropertyName("order_id")]
    public string OrderId { get; set; } = "";

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}

[JsonSerializable(typeof(CreateOrderReq))]
[JsonSerializable(typeof(OrderItem))]
[JsonSerializable(typeof(OrderResponse))]
[JsonSerializable(typeof(ErrorResponse))]
[JsonSerializable(typeof(DeleteResponse))]
[JsonSerializable(typeof(List<OrderItem>))]
[JsonSerializable(typeof(Dictionary<string, object>))]
[JsonSerializable(typeof(Dictionary<string, string>))]
internal partial class AppJsonContext : JsonSerializerContext { }
