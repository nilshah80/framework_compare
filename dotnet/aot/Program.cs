using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using PgStore;

var builder = WebApplication.CreateSlimBuilder(args);
builder.Logging.ClearProviders();

builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.TypeInfoResolverChain.Insert(0, AppJsonContext.Default);
});

// ── PgStore singleton ────────────────────────────────────────────────
var pgDsn = Environment.GetEnvironmentVariable("PG_DSN")
    ?? throw new InvalidOperationException("PG_DSN environment variable is required");
var pgStore = new PgStore.PgStore(pgDsn);
await pgStore.InitSchemaAsync();
builder.Services.AddSingleton(pgStore);

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

    // Capture request body
    context.Request.EnableBuffering();
    using var reqReader = new StreamReader(context.Request.Body, leaveOpen: true);
    var requestBody = await reqReader.ReadToEndAsync();
    context.Request.Body.Position = 0;

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

    var clientIp = context.Request.Headers["X-Forwarded-For"].FirstOrDefault()
        ?? context.Connection.RemoteIpAddress?.ToString() ?? "";

    var logData = new Dictionary<string, object>
    {
        ["request_id"] = requestId,
        ["method"] = context.Request.Method,
        ["path"] = context.Request.Path.Value ?? "",
        ["query"] = query,
        ["client_ip"] = clientIp,
        ["user_agent"] = context.Request.Headers.UserAgent.ToString(),
        ["request_headers"] = RedactHeaders(context.Request.Headers),
        ["request_body"] = requestBody,
        ["status"] = context.Response.StatusCode,
        ["latency"] = $"{sw.Elapsed.TotalMilliseconds:F3}ms",
        ["latency_ms"] = Math.Round(sw.Elapsed.TotalMilliseconds, 3),
        ["response_headers"] = RedactHeaders(context.Response.Headers),
        ["response_body"] = responseBody,
        ["bytes_out"] = responseBody.Length
    };

    LogEntry("INFO", "http_dump", logData);
});

// ── Routes ───────────────────────────────────────────────────────────

app.MapPost("/users/{userId}/orders", async (HttpContext ctx, string userId, CreateOrderReq req, PgStore.PgStore s) =>
{
    var items = MapItems(req.Items);
    var order = await s.CreateOrderAsync(userId, items, req.Currency);
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Created($"/users/{userId}/orders/{order.OrderId}", ToResponse(order, "", requestId));
});

app.MapPut("/users/{userId}/orders/{orderId}", async (HttpContext ctx, string userId, string orderId, CreateOrderReq req, PgStore.PgStore s) =>
{
    var items = MapItems(req.Items);
    var order = await s.UpdateOrderAsync(userId, orderId, items, req.Currency);
    if (order is null)
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Ok(ToResponse(order, "", requestId));
});

app.MapDelete("/users/{userId}/orders/{orderId}", async (HttpContext ctx, string userId, string orderId, PgStore.PgStore s) =>
{
    var deleted = await s.DeleteOrderAsync(userId, orderId);
    if (!deleted)
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Ok(new DeleteResponse { Message = "order deleted", OrderId = orderId, RequestId = requestId });
});

app.MapGet("/users/{userId}/orders/{orderId}", async (HttpContext ctx, string userId, string orderId, string? fields, PgStore.PgStore s) =>
{
    var order = await s.GetOrderAsync(userId, orderId);
    if (order is null)
        return Results.Json(new ErrorResponse { Error = "order not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    return Results.Ok(ToResponse(order, fields ?? "*", requestId));
});

app.MapPost("/users/{userId}/orders/bulk", async (HttpContext ctx, string userId, BulkCreateOrderReq req, PgStore.PgStore s) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var inputs = (req.Orders ?? []).Select(o => new BulkOrderInput
    {
        Items = MapItems(o.Items),
        Currency = o.Currency
    }).ToList();

    var (orders, totalSum) = await s.BulkCreateOrdersAsync(userId, inputs);
    var results = orders.Select(o => ToResponse(o, "", requestId)).ToList();

    return Results.Created($"/users/{userId}/orders", new BulkOrderResponse
    {
        UserId = userId,
        Count = results.Count,
        Orders = results,
        TotalSum = totalSum,
        RequestId = requestId
    });
});

app.MapGet("/users/{userId}/orders", async (HttpContext ctx, string userId, PgStore.PgStore s) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var orders = await s.ListOrdersAsync(userId);
    var results = orders.Select(o => ToResponse(o, "", requestId)).ToList();

    return Results.Ok(new ListOrdersResponse
    {
        UserId = userId,
        Count = results.Count,
        Orders = results,
        RequestId = requestId
    });
});

app.MapPut("/users/{userId}/profile", async (HttpContext ctx, string userId, UserProfile profile, PgStore.PgStore s) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var p = MapProfile(profile);
    await s.UpsertProfileAsync(userId, p);
    return Results.Ok(ToProfileResp(p, requestId));
});

app.MapGet("/users/{userId}/profile", async (HttpContext ctx, string userId, PgStore.PgStore s) =>
{
    var requestId = ctx.Items["RequestId"]?.ToString() ?? "";
    var profile = await s.GetProfileAsync(userId);
    if (profile is null)
        return Results.Json(new ErrorResponse { Error = "profile not found" }, AppJsonContext.Default.ErrorResponse, statusCode: 404);

    return Results.Ok(ToProfileResp(profile, requestId));
});

LogEntry("INFO", "server starting", new Dictionary<string, object> { ["port"] = "8095" });
app.Run();

// ── Helpers ──────────────────────────────────────────────────────────

static List<PgStore.OrderItem> MapItems(List<OrderItem>? items) =>
    (items ?? []).Select(i => new PgStore.OrderItem
    {
        ProductId = i.ProductId, Name = i.Name, Quantity = i.Quantity, Price = i.Price
    }).ToList();

static OrderResponse ToResponse(Order o, string fields, string requestId) => new()
{
    OrderId = o.OrderId, UserId = o.UserId, Status = o.Status,
    Items = o.Items.Select(i => new OrderItem
    {
        ProductId = i.ProductId, Name = i.Name, Quantity = i.Quantity, Price = i.Price
    }).ToList(),
    Total = o.Total, Currency = o.Currency, Fields = fields, RequestId = requestId
};

static Profile MapProfile(UserProfile r) => new()
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

static UserProfile ToProfileResp(Profile p, string requestId) => new()
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

[JsonSerializable(typeof(CreateOrderReq))]
[JsonSerializable(typeof(OrderItem))]
[JsonSerializable(typeof(OrderResponse))]
[JsonSerializable(typeof(ErrorResponse))]
[JsonSerializable(typeof(DeleteResponse))]
[JsonSerializable(typeof(BulkCreateOrderReq))]
[JsonSerializable(typeof(BulkOrderResponse))]
[JsonSerializable(typeof(ListOrdersResponse))]
[JsonSerializable(typeof(UserProfile))]
[JsonSerializable(typeof(Address))]
[JsonSerializable(typeof(Preferences))]
[JsonSerializable(typeof(NotificationPrefs))]
[JsonSerializable(typeof(PaymentMethod))]
[JsonSerializable(typeof(List<OrderItem>))]
[JsonSerializable(typeof(List<OrderResponse>))]
[JsonSerializable(typeof(List<PaymentMethod>))]
[JsonSerializable(typeof(List<string>))]
[JsonSerializable(typeof(Dictionary<string, object>))]
[JsonSerializable(typeof(Dictionary<string, string>))]
internal partial class AppJsonContext : JsonSerializerContext { }
