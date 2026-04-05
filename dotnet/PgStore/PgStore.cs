using System.Text.Json;
using Npgsql;

namespace PgStore;

/// <summary>
/// Shared PostgreSQL-backed store used identically by all .NET web framework benchmarks.
/// This ensures the DB layer is byte-for-byte identical across frameworks.
/// </summary>
public sealed class PgStore : IAsyncDisposable
{
    private readonly NpgsqlDataSource _dataSource;

    public PgStore(string connectionString)
    {
        // Convert PostgreSQL URI format (postgres://user:pass@host:port/db) to ADO.NET format
        if (connectionString.StartsWith("postgres://") || connectionString.StartsWith("postgresql://"))
        {
            var uri = new Uri(connectionString);
            var userInfo = uri.UserInfo.Split(':');
            connectionString = $"Host={uri.Host};Port={uri.Port};Database={uri.AbsolutePath.TrimStart('/')};Username={userInfo[0]};Password={Uri.UnescapeDataString(userInfo.Length > 1 ? userInfo[1] : "")}";
        }

        var builder = new NpgsqlDataSourceBuilder(connectionString);
        builder.ConnectionStringBuilder.MaxPoolSize = 50;
        builder.ConnectionStringBuilder.MinPoolSize = 10;
        _dataSource = builder.Build();
    }

    public async ValueTask DisposeAsync()
    {
        await _dataSource.DisposeAsync();
    }

    /// <summary>Creates the orders + profiles tables and index if they don't exist.</summary>
    public async Task InitSchemaAsync(CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand("""
            CREATE TABLE IF NOT EXISTS orders (
                id         BIGSERIAL PRIMARY KEY,
                user_id    VARCHAR(255) NOT NULL,
                status     VARCHAR(50) NOT NULL DEFAULT 'created',
                items      JSONB NOT NULL DEFAULT '[]',
                total      NUMERIC(12,2) NOT NULL DEFAULT 0,
                currency   VARCHAR(10) NOT NULL DEFAULT 'USD',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
            CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);

            CREATE TABLE IF NOT EXISTS profiles (
                user_id  VARCHAR(255) PRIMARY KEY,
                data     JSONB NOT NULL DEFAULT '{}'
            );
            """);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    /// <summary>Truncates both tables. Called before each benchmark run for a clean state.</summary>
    public async Task TruncateAsync(CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand(
            "TRUNCATE TABLE orders RESTART IDENTITY; TRUNCATE TABLE profiles");
        await cmd.ExecuteNonQueryAsync(ct);
    }

    // ── CRUD Operations ──

    public async Task<Order> CreateOrderAsync(string userId, List<OrderItem> items, string currency, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(currency)) currency = "USD";

        var total = 0.0;
        foreach (var item in items)
            total += item.Price * item.Quantity;

        var itemsJson = JsonSerializer.Serialize(items);

        await using var cmd = _dataSource.CreateCommand(
            "INSERT INTO orders (user_id, status, items, total, currency) VALUES ($1, 'created', $2, $3, $4) RETURNING id");
        cmd.Parameters.AddWithValue(userId);
        cmd.Parameters.AddWithValue(NpgsqlTypes.NpgsqlDbType.Jsonb, itemsJson);
        cmd.Parameters.AddWithValue(total);
        cmd.Parameters.AddWithValue(currency);

        var id = (long)(await cmd.ExecuteScalarAsync(ct))!;

        return new Order
        {
            OrderId = id.ToString(),
            UserId = userId,
            Status = "created",
            Items = items,
            Total = total,
            Currency = currency
        };
    }

    public async Task<Order?> GetOrderAsync(string userId, string orderId, CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand(
            "SELECT id, user_id, status, items, total, currency FROM orders WHERE id = $1 AND user_id = $2");
        cmd.Parameters.AddWithValue(long.Parse(orderId));
        cmd.Parameters.AddWithValue(userId);

        await using var reader = await cmd.ExecuteReaderAsync(ct);
        if (!await reader.ReadAsync(ct))
            return null;

        var itemsJson = reader.GetString(3);
        return new Order
        {
            OrderId = reader.GetInt64(0).ToString(),
            UserId = reader.GetString(1),
            Status = reader.GetString(2),
            Items = JsonSerializer.Deserialize<List<OrderItem>>(itemsJson) ?? [],
            Total = reader.GetDouble(4),
            Currency = reader.GetString(5)
        };
    }

    public async Task<Order?> UpdateOrderAsync(string userId, string orderId, List<OrderItem> items, string currency, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(currency)) currency = "USD";

        var total = 0.0;
        foreach (var item in items)
            total += item.Price * item.Quantity;

        var itemsJson = JsonSerializer.Serialize(items);

        await using var cmd = _dataSource.CreateCommand(
            "UPDATE orders SET status = 'updated', items = $1, total = $2, currency = $3 WHERE id = $4 AND user_id = $5");
        cmd.Parameters.AddWithValue(NpgsqlTypes.NpgsqlDbType.Jsonb, itemsJson);
        cmd.Parameters.AddWithValue(total);
        cmd.Parameters.AddWithValue(currency);
        cmd.Parameters.AddWithValue(long.Parse(orderId));
        cmd.Parameters.AddWithValue(userId);

        var rows = await cmd.ExecuteNonQueryAsync(ct);
        if (rows == 0)
            return null;

        return new Order
        {
            OrderId = orderId,
            UserId = userId,
            Status = "updated",
            Items = items,
            Total = total,
            Currency = currency
        };
    }

    public async Task<bool> DeleteOrderAsync(string userId, string orderId, CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand(
            "DELETE FROM orders WHERE id = $1 AND user_id = $2");
        cmd.Parameters.AddWithValue(long.Parse(orderId));
        cmd.Parameters.AddWithValue(userId);

        var rows = await cmd.ExecuteNonQueryAsync(ct);
        return rows > 0;
    }

    // ── Bulk & List Operations ──

    public async Task<(List<Order> Orders, double TotalSum)> BulkCreateOrdersAsync(
        string userId, List<BulkOrderInput> orders, CancellationToken ct = default)
    {
        await using var conn = await _dataSource.OpenConnectionAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);

        var results = new List<Order>();
        var totalSum = 0.0;

        foreach (var req in orders)
        {
            var currency = string.IsNullOrEmpty(req.Currency) ? "USD" : req.Currency;
            var total = 0.0;
            foreach (var item in req.Items)
                total += item.Price * item.Quantity;

            var itemsJson = JsonSerializer.Serialize(req.Items);

            await using var cmd = new NpgsqlCommand(
                "INSERT INTO orders (user_id, status, items, total, currency) VALUES ($1, 'created', $2, $3, $4) RETURNING id",
                conn, tx);
            cmd.Parameters.AddWithValue(userId);
            cmd.Parameters.AddWithValue(NpgsqlTypes.NpgsqlDbType.Jsonb, itemsJson);
            cmd.Parameters.AddWithValue(total);
            cmd.Parameters.AddWithValue(currency);

            var id = (long)(await cmd.ExecuteScalarAsync(ct))!;

            results.Add(new Order
            {
                OrderId = id.ToString(),
                UserId = userId,
                Status = "created",
                Items = req.Items,
                Total = total,
                Currency = currency
            });
            totalSum += total;
        }

        await tx.CommitAsync(ct);
        return (results, totalSum);
    }

    public async Task<List<Order>> ListOrdersAsync(string userId, CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand(
            "SELECT id, user_id, status, items, total, currency FROM orders WHERE user_id = $1 ORDER BY id");
        cmd.Parameters.AddWithValue(userId);

        await using var reader = await cmd.ExecuteReaderAsync(ct);
        var orders = new List<Order>();

        while (await reader.ReadAsync(ct))
        {
            var itemsJson = reader.GetString(3);
            orders.Add(new Order
            {
                OrderId = reader.GetInt64(0).ToString(),
                UserId = reader.GetString(1),
                Status = reader.GetString(2),
                Items = JsonSerializer.Deserialize<List<OrderItem>>(itemsJson) ?? [],
                Total = reader.GetDouble(4),
                Currency = reader.GetString(5)
            });
        }

        return orders;
    }

    // ── Profile Operations ──

    public async Task UpsertProfileAsync(string userId, Profile profile, CancellationToken ct = default)
    {
        profile.UserId = userId;
        var data = JsonSerializer.Serialize(profile);

        await using var cmd = _dataSource.CreateCommand(
            "INSERT INTO profiles (user_id, data) VALUES ($1, $2) ON CONFLICT (user_id) DO UPDATE SET data = $2");
        cmd.Parameters.AddWithValue(userId);
        cmd.Parameters.AddWithValue(NpgsqlTypes.NpgsqlDbType.Jsonb, data);

        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task<Profile?> GetProfileAsync(string userId, CancellationToken ct = default)
    {
        await using var cmd = _dataSource.CreateCommand(
            "SELECT data FROM profiles WHERE user_id = $1");
        cmd.Parameters.AddWithValue(userId);

        await using var reader = await cmd.ExecuteReaderAsync(ct);
        if (!await reader.ReadAsync(ct))
            return null;

        var data = reader.GetString(0);
        return JsonSerializer.Deserialize<Profile>(data);
    }
}

/// <summary>Input type for bulk order creation.</summary>
public class BulkOrderInput
{
    public List<OrderItem> Items { get; set; } = [];
    public string Currency { get; set; } = "";
}
