using System.Diagnostics;
using System.Text.Json;
using controller_api;

var builder = WebApplication.CreateBuilder(args);
builder.Logging.ClearProviders();

builder.Services.AddControllers();
builder.Services.AddSingleton<OrderStore>();
builder.Services.AddSingleton<ProfileStore>();

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
    options.ListenAnyIP(8094);
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

    Helpers.LogEntry("INFO", "http_dump", new
    {
        request_id = requestId,
        method = context.Request.Method,
        path = context.Request.Path.Value,
        query,
        client_ip = context.Connection.RemoteIpAddress?.ToString() ?? "",
        user_agent = context.Request.Headers.UserAgent.ToString(),
        request_headers = Helpers.RedactHeaders(context.Request.Headers),
        status = context.Response.StatusCode,
        latency = $"{sw.Elapsed.TotalMilliseconds:F3}ms",
        latency_ms = Math.Round(sw.Elapsed.TotalMilliseconds, 3),
        response_headers = Helpers.RedactHeaders(context.Response.Headers),
        response_body = responseBody,
        bytes_out = responseBody.Length
    });
});

app.MapControllers();

Helpers.LogEntry("INFO", "server starting", new { port = "8094" });
app.Run();
