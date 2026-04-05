using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using FastEndpoints;
using fast_endpoints;
using PgStore;

var builder = WebApplication.CreateBuilder(args);
builder.Logging.ClearProviders();

builder.Services.AddFastEndpoints();

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
    options.ListenAnyIP(8097);
    options.Limits.MaxRequestBodySize = 1 << 20;
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
        Helpers.LogEntry("ERROR", "panic_recovered", new { error = ex.Message, stack = ex.StackTrace });
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
    var query = context.Request.Query.ToDictionary(q => q.Key, q => q.Value.ToString());
    var clientIp = context.Request.Headers["X-Forwarded-For"].FirstOrDefault()
        ?? context.Connection.RemoteIpAddress?.ToString() ?? "";

    Helpers.LogEntry("INFO", "http_dump", new
    {
        request_id = requestId,
        method = context.Request.Method,
        path = context.Request.Path.Value,
        query,
        client_ip = clientIp,
        user_agent = context.Request.Headers.UserAgent.ToString(),
        request_headers = Helpers.RedactHeaders(context.Request.Headers),
        request_body = requestBody,
        status = context.Response.StatusCode,
        latency = $"{sw.Elapsed.TotalMilliseconds:F3}ms",
        latency_ms = Math.Round(sw.Elapsed.TotalMilliseconds, 3),
        response_headers = Helpers.RedactHeaders(context.Response.Headers),
        response_body = responseBody,
        bytes_out = responseBody.Length
    });
});

app.UseFastEndpoints();

Helpers.LogEntry("INFO", "server starting", new { port = "8097" });
app.Run();

// ── Shared classes ───────────────────────────────────────────────────

namespace fast_endpoints
{
    public static class Mapping
    {
        public static List<PgStore.OrderItem> MapItems(List<OrderItem>? items) =>
            (items ?? []).Select(i => new PgStore.OrderItem
            {
                ProductId = i.ProductId, Name = i.Name, Quantity = i.Quantity, Price = i.Price
            }).ToList();

        public static OrderResponse ToResponse(PgStore.Order o, string fields, string requestId) => new()
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

    public static class Helpers
    {
        private static readonly HashSet<string> RedactedHeaderNames = new(StringComparer.OrdinalIgnoreCase)
        {
            "Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token"
        };

        public static void LogEntry(string level, string msg, object fields)
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

        public static Dictionary<string, string> RedactHeaders(IHeaderDictionary headers)
        {
            var result = new Dictionary<string, string>();
            foreach (var h in headers)
                result[h.Key] = RedactedHeaderNames.Contains(h.Key) ? "[REDACTED]" : h.Value.ToString();
            return result;
        }
    }

    // ── Models ───────────────────────────────────────────────────────

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

    public class ErrorResp
    {
        [JsonPropertyName("error")]
        public string Error { get; set; } = "";
    }

    public class DeleteResp
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

    // ── FastEndpoints ────────────────────────────────────────────────

    public class CreateOrderEndpoint(PgStore.PgStore store) : Endpoint<CreateOrderReq, OrderResponse>
    {
        public override void Configure()
        {
            Post("/users/{userId}/orders");
            AllowAnonymous();
        }

        public override async Task HandleAsync(CreateOrderReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var items = Mapping.MapItems(req.Items);
            var order = await store.CreateOrderAsync(userId, items, req.Currency, ct);
            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            await SendCreatedAtAsync<GetOrderEndpoint>(new { userId, orderId = order.OrderId },
                Mapping.ToResponse(order, "", requestId), cancellation: ct);
        }
    }

    public class UpdateOrderReq : CreateOrderReq;

    public class UpdateOrderEndpoint(PgStore.PgStore store) : Endpoint<UpdateOrderReq, OrderResponse>
    {
        public override void Configure()
        {
            Put("/users/{userId}/orders/{orderId}");
            AllowAnonymous();
        }

        public override async Task HandleAsync(UpdateOrderReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var orderId = Route<string>("orderId")!;
            var items = Mapping.MapItems(req.Items);
            var order = await store.UpdateOrderAsync(userId, orderId, items, req.Currency, ct);

            if (order is null)
            {
                await SendAsync(new OrderResponse(), 404, ct);
                return;
            }

            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            await SendOkAsync(Mapping.ToResponse(order, "", requestId), ct);
        }
    }

    public class DeleteOrderReq
    {
        public string UserId { get; set; } = "";
        public string OrderId { get; set; } = "";
    }

    public class DeleteOrderEndpoint(PgStore.PgStore store) : Endpoint<DeleteOrderReq, DeleteResp>
    {
        public override void Configure()
        {
            Delete("/users/{userId}/orders/{orderId}");
            AllowAnonymous();
        }

        public override async Task HandleAsync(DeleteOrderReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var orderId = Route<string>("orderId")!;
            var deleted = await store.DeleteOrderAsync(userId, orderId, ct);

            if (!deleted)
            {
                await SendAsync(new DeleteResp(), 404, ct);
                return;
            }

            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            await SendOkAsync(new DeleteResp
            {
                Message = "order deleted",
                OrderId = orderId,
                RequestId = requestId
            }, ct);
        }
    }

    public class GetOrderReq
    {
        public string UserId { get; set; } = "";
        public string OrderId { get; set; } = "";
        [QueryParam]
        public string? Fields { get; set; }
    }

    public class GetOrderEndpoint(PgStore.PgStore store) : Endpoint<GetOrderReq, OrderResponse>
    {
        public override void Configure()
        {
            Get("/users/{userId}/orders/{orderId}");
            AllowAnonymous();
        }

        public override async Task HandleAsync(GetOrderReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var orderId = Route<string>("orderId")!;
            var order = await store.GetOrderAsync(userId, orderId, ct);

            if (order is null)
            {
                await SendAsync(new OrderResponse(), 404, ct);
                return;
            }

            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            await SendOkAsync(Mapping.ToResponse(order, req.Fields ?? "*", requestId), ct);
        }
    }

    // ── Bulk Create Orders ──────────────────────────────────────────
    public class BulkCreateOrderEndpoint(PgStore.PgStore store) : Endpoint<BulkCreateOrderReq, BulkOrderResponse>
    {
        public override void Configure()
        {
            Post("/users/{userId}/orders/bulk");
            AllowAnonymous();
        }

        public override async Task HandleAsync(BulkCreateOrderReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            var inputs = (req.Orders ?? []).Select(o => new BulkOrderInput
            {
                Items = Mapping.MapItems(o.Items),
                Currency = o.Currency
            }).ToList();

            var (orders, totalSum) = await store.BulkCreateOrdersAsync(userId, inputs, ct);
            var results = orders.Select(o => Mapping.ToResponse(o, "", requestId)).ToList();

            await SendAsync(new BulkOrderResponse
            {
                UserId = userId,
                Count = results.Count,
                Orders = results,
                TotalSum = totalSum,
                RequestId = requestId
            }, 201, ct);
        }
    }

    // ── List Orders ─────────────────────────────────────────────────
    public class ListOrdersReq
    {
        public string UserId { get; set; } = "";
    }

    public class ListOrdersEndpoint(PgStore.PgStore store) : Endpoint<ListOrdersReq, ListOrdersResponse>
    {
        public override void Configure()
        {
            Get("/users/{userId}/orders");
            AllowAnonymous();
        }

        public override async Task HandleAsync(ListOrdersReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            var orders = await store.ListOrdersAsync(userId, ct);
            var results = orders.Select(o => Mapping.ToResponse(o, "", requestId)).ToList();

            await SendOkAsync(new ListOrdersResponse
            {
                UserId = userId,
                Count = results.Count,
                Orders = results,
                RequestId = requestId
            }, ct);
        }
    }

    // ── Update Profile ──────────────────────────────────────────────
    public class UpdateProfileEndpoint(PgStore.PgStore store) : Endpoint<UserProfile, UserProfile>
    {
        public override void Configure()
        {
            Put("/users/{userId}/profile");
            AllowAnonymous();
        }

        public override async Task HandleAsync(UserProfile req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";
            var p = Mapping.MapProfile(req);
            await store.UpsertProfileAsync(userId, p, ct);
            await SendOkAsync(Mapping.ToProfileResp(p, requestId), ct);
        }
    }

    // ── Get Profile ─────────────────────────────────────────────────
    public class GetProfileReq
    {
        public string UserId { get; set; } = "";
    }

    public class GetProfileEndpoint(PgStore.PgStore store) : Endpoint<GetProfileReq, UserProfile>
    {
        public override void Configure()
        {
            Get("/users/{userId}/profile");
            AllowAnonymous();
        }

        public override async Task HandleAsync(GetProfileReq req, CancellationToken ct)
        {
            var userId = Route<string>("userId")!;
            var requestId = HttpContext.Items["RequestId"]?.ToString() ?? "";

            var profile = await store.GetProfileAsync(userId, ct);
            if (profile is null)
            {
                await SendAsync(new UserProfile(), 404, ct);
                return;
            }

            await SendOkAsync(Mapping.ToProfileResp(profile, requestId), ct);
        }
    }
}
