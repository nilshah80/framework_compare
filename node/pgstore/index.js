// Shared PostgreSQL-backed order store for Node.js framework benchmarks.
// This ensures the DB layer is identical across frameworks.
const { Pool } = require("pg");

class PgStore {
  constructor(dsn) {
    this.pool = new Pool({
      connectionString: dsn,
      max: 50,
      min: 10,
    });
  }

  async initSchema() {
    await this.pool.query(`
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
    `);
  }

  async truncate() {
    await this.pool.query(
      "TRUNCATE TABLE orders RESTART IDENTITY; TRUNCATE TABLE profiles"
    );
  }

  async close() {
    await this.pool.end();
  }

  // ── CRUD Operations ──

  async createOrder(userId, items, currency) {
    currency = currency || "USD";
    let total = 0;
    for (const item of items || []) {
      total += item.price * item.quantity;
    }

    const result = await this.pool.query(
      `INSERT INTO orders (user_id, status, items, total, currency)
       VALUES ($1, 'created', $2, $3, $4)
       RETURNING id`,
      [userId, JSON.stringify(items || []), total, currency]
    );

    const id = String(result.rows[0].id);
    return {
      order_id: id,
      user_id: userId,
      status: "created",
      items: items || [],
      total,
      currency,
    };
  }

  async getOrder(userId, orderId) {
    const result = await this.pool.query(
      `SELECT id, user_id, status, items, total, currency
       FROM orders WHERE id = $1 AND user_id = $2`,
      [orderId, userId]
    );

    if (result.rows.length === 0) return null;

    const row = result.rows[0];
    return {
      order_id: String(row.id),
      user_id: row.user_id,
      status: row.status,
      items: row.items,
      total: parseFloat(row.total),
      currency: row.currency,
    };
  }

  async updateOrder(userId, orderId, items, currency) {
    currency = currency || "USD";
    let total = 0;
    for (const item of items || []) {
      total += item.price * item.quantity;
    }

    const result = await this.pool.query(
      `UPDATE orders SET status = 'updated', items = $1, total = $2, currency = $3
       WHERE id = $4 AND user_id = $5`,
      [JSON.stringify(items || []), total, currency, orderId, userId]
    );

    if (result.rowCount === 0) return null;

    return {
      order_id: orderId,
      user_id: userId,
      status: "updated",
      items: items || [],
      total,
      currency,
    };
  }

  async deleteOrder(userId, orderId) {
    const result = await this.pool.query(
      `DELETE FROM orders WHERE id = $1 AND user_id = $2`,
      [orderId, userId]
    );
    return result.rowCount > 0;
  }

  // ── Bulk & List Operations ──

  async bulkCreateOrders(userId, orders) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");

      const results = [];
      let totalSum = 0;

      for (const req of orders || []) {
        const currency = req.currency || "USD";
        let total = 0;
        for (const item of req.items || []) {
          total += item.price * item.quantity;
        }

        const res = await client.query(
          `INSERT INTO orders (user_id, status, items, total, currency)
           VALUES ($1, 'created', $2, $3, $4) RETURNING id`,
          [userId, JSON.stringify(req.items || []), total, currency]
        );

        const id = String(res.rows[0].id);
        results.push({
          order_id: id,
          user_id: userId,
          status: "created",
          items: req.items || [],
          total,
          currency,
        });
        totalSum += total;
      }

      await client.query("COMMIT");
      return { orders: results, totalSum };
    } catch (err) {
      await client.query("ROLLBACK");
      throw err;
    } finally {
      client.release();
    }
  }

  async listOrders(userId) {
    const result = await this.pool.query(
      `SELECT id, user_id, status, items, total, currency
       FROM orders WHERE user_id = $1 ORDER BY id`,
      [userId]
    );

    return result.rows.map((row) => ({
      order_id: String(row.id),
      user_id: row.user_id,
      status: row.status,
      items: row.items,
      total: parseFloat(row.total),
      currency: row.currency,
    }));
  }

  // ── Profile Operations ──

  async upsertProfile(userId, profile) {
    profile.user_id = userId;
    await this.pool.query(
      `INSERT INTO profiles (user_id, data) VALUES ($1, $2)
       ON CONFLICT (user_id) DO UPDATE SET data = $2`,
      [userId, JSON.stringify(profile)]
    );
  }

  async getProfile(userId) {
    const result = await this.pool.query(
      `SELECT data FROM profiles WHERE user_id = $1`,
      [userId]
    );

    if (result.rows.length === 0) return null;
    return result.rows[0].data;
  }
}

module.exports = PgStore;
