package benchmark.pgstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.net.URI;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared PostgreSQL-backed store used identically by all Java web framework benchmarks.
 * Mirrors the Go pgstore package byte-for-byte in SQL and behaviour.
 */
public class PgStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HikariDataSource dataSource;

    /**
     * Creates a PgStore connected to the given PostgreSQL DSN.
     * DSN format: postgres://user:pass@host:port/dbname?sslmode=disable
     * Pool settings: max 50 conns, min 10.
     */
    public PgStore(String dsn) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dsnToJdbcUrl(dsn));
        config.setMaximumPoolSize(50);
        config.setMinimumIdle(10);
        config.setDriverClassName("org.postgresql.Driver");

        // Extract user/password from DSN
        try {
            URI uri = new URI(dsn.replace("postgres://", "http://").replace("postgresql://", "http://"));
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                config.setUsername(parts[0]);
                if (parts.length > 1) {
                    config.setPassword(parts[1]);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("pgstore: parse DSN: " + e.getMessage(), e);
        }

        this.dataSource = new HikariDataSource(config);

        // Ping
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(5);
        } catch (SQLException e) {
            dataSource.close();
            throw new RuntimeException("pgstore: ping: " + e.getMessage(), e);
        }
    }

    /**
     * Creates the orders + profiles tables and index if they don't exist.
     */
    public synchronized void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
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
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: initSchema: " + e.getMessage(), e);
        }
    }

    /**
     * Truncates both tables. Called before each benchmark run for a clean state.
     */
    public void truncate() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE orders RESTART IDENTITY; TRUNCATE TABLE profiles");
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: truncate: " + e.getMessage(), e);
        }
    }

    // -- CRUD Operations --

    /**
     * Inserts a new order and returns it with the generated ID.
     */
    public Order createOrder(String userId, List<OrderItem> items, String currency) {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }

        double total = 0;
        for (OrderItem item : items) {
            total += item.price() * item.quantity();
        }

        String itemsJson;
        try {
            itemsJson = MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("pgstore: marshal items: " + e.getMessage(), e);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO orders (user_id, status, items, total, currency) VALUES (?, 'created', ?::jsonb, ?, ?) RETURNING id")) {
            ps.setString(1, userId);
            ps.setString(2, itemsJson);
            ps.setDouble(3, total);
            ps.setString(4, currency);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                return new Order(String.valueOf(id), userId, "created", items, total, currency);
            }
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: insert: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves an order by user ID and order ID.
     */
    public Optional<Order> getOrder(String userId, String orderId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, user_id, status, items, total, currency FROM orders WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, Long.parseLong(orderId));
            ps.setString(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String id = String.valueOf(rs.getLong("id"));
                String uid = rs.getString("user_id");
                String status = rs.getString("status");
                String itemsJson = rs.getString("items");
                double total = rs.getDouble("total");
                String cur = rs.getString("currency");

                List<OrderItem> items = MAPPER.readValue(itemsJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, OrderItem.class));
                return Optional.of(new Order(id, uid, status, items, total, cur));
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("pgstore: get: " + e.getMessage(), e);
        }
    }

    /**
     * Updates an existing order.
     */
    public Optional<Order> updateOrder(String userId, String orderId, List<OrderItem> items, String currency) {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }

        double total = 0;
        for (OrderItem item : items) {
            total += item.price() * item.quantity();
        }

        String itemsJson;
        try {
            itemsJson = MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("pgstore: marshal items: " + e.getMessage(), e);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE orders SET status = 'updated', items = ?::jsonb, total = ?, currency = ? WHERE id = ? AND user_id = ?")) {
            ps.setString(1, itemsJson);
            ps.setDouble(2, total);
            ps.setString(3, currency);
            ps.setLong(4, Long.parseLong(orderId));
            ps.setString(5, userId);

            int rows = ps.executeUpdate();
            if (rows == 0) {
                return Optional.empty();
            }

            return Optional.of(new Order(orderId, userId, "updated", items, total, currency));
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: update: " + e.getMessage(), e);
        }
    }

    /**
     * Removes an order and returns true if it existed.
     */
    public boolean deleteOrder(String userId, String orderId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM orders WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, Long.parseLong(orderId));
            ps.setString(2, userId);

            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: delete: " + e.getMessage(), e);
        }
    }

    // -- Bulk & List Operations --

    /**
     * Inserts multiple orders in a single transaction.
     */
    public BulkResult bulkCreateOrders(String userId, List<BulkOrderInput> orders) {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            List<Order> results = new ArrayList<>();
            double totalSum = 0;

            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO orders (user_id, status, items, total, currency) VALUES (?, 'created', ?::jsonb, ?, ?) RETURNING id")) {

                for (BulkOrderInput req : orders) {
                    String currency = (req.currency() == null || req.currency().isBlank()) ? "USD" : req.currency();
                    double total = 0;
                    for (OrderItem item : req.items()) {
                        total += item.price() * item.quantity();
                    }

                    String itemsJson = MAPPER.writeValueAsString(req.items());

                    ps.setString(1, userId);
                    ps.setString(2, itemsJson);
                    ps.setDouble(3, total);
                    ps.setString(4, currency);

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        long id = rs.getLong(1);
                        results.add(new Order(String.valueOf(id), userId, "created", req.items(), total, currency));
                    }
                    totalSum += total;
                }
            }

            conn.commit();
            return new BulkResult(results, totalSum);
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new RuntimeException("pgstore: bulk insert: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * Returns all orders for a given user.
     */
    public List<Order> listOrders(String userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, user_id, status, items, total, currency FROM orders WHERE user_id = ? ORDER BY id")) {
            ps.setString(1, userId);

            List<Order> orders = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = String.valueOf(rs.getLong("id"));
                    String uid = rs.getString("user_id");
                    String status = rs.getString("status");
                    String itemsJson = rs.getString("items");
                    double total = rs.getDouble("total");
                    String currency = rs.getString("currency");

                    List<OrderItem> items = MAPPER.readValue(itemsJson,
                        MAPPER.getTypeFactory().constructCollectionType(List.class, OrderItem.class));
                    orders.add(new Order(id, uid, status, items, total, currency));
                }
            }
            return orders;
        } catch (SQLException | IOException e) {
            throw new RuntimeException("pgstore: list: " + e.getMessage(), e);
        }
    }

    // -- Profile Operations --

    /**
     * Creates or updates a user profile (stored as JSONB).
     */
    public void upsertProfile(String userId, Profile profile) {
        Profile withUserId = new Profile(userId, profile.name(), profile.email(), profile.phone(),
            profile.address(), profile.preferences(), profile.paymentMethods(),
            profile.tags(), profile.metadata());

        String data;
        try {
            data = MAPPER.writeValueAsString(withUserId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("pgstore: marshal profile: " + e.getMessage(), e);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO profiles (user_id, data) VALUES (?, ?::jsonb) ON CONFLICT (user_id) DO UPDATE SET data = ?::jsonb")) {
            ps.setString(1, userId);
            ps.setString(2, data);
            ps.setString(3, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("pgstore: upsert profile: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves a user profile.
     */
    public Optional<Profile> getProfile(String userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT data FROM profiles WHERE user_id = ?")) {
            ps.setString(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String data = rs.getString("data");
                Profile profile = MAPPER.readValue(data, Profile.class);
                return Optional.of(profile);
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("pgstore: get profile: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    // -- Utility --

    private static String dsnToJdbcUrl(String dsn) {
        // Convert postgres://user:pass@host:port/db?params to jdbc:postgresql://host:port/db?params
        try {
            URI uri = new URI(dsn.replace("postgres://", "http://").replace("postgresql://", "http://"));
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath(); // /dbname
            String query = uri.getQuery();
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
            if (query != null && !query.isEmpty()) {
                jdbcUrl += "?" + query;
            }
            return jdbcUrl;
        } catch (Exception e) {
            throw new RuntimeException("pgstore: invalid DSN: " + dsn, e);
        }
    }
}
