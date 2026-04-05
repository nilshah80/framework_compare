use bigdecimal::BigDecimal;
use serde::{Deserialize, Serialize};
use sqlx::postgres::PgPoolOptions;
use sqlx::{PgPool, Row};
use std::collections::HashMap;

// ── Types (matching the JSON shapes used by all frameworks) ──

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderItem {
    pub product_id: String,
    pub name: String,
    pub quantity: i64,
    pub price: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Order {
    pub order_id: String,
    pub user_id: String,
    pub status: String,
    pub items: Vec<OrderItem>,
    pub total: f64,
    pub currency: String,
}

// ── Profile types ──

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    pub user_id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub email: String,
    #[serde(default)]
    pub phone: String,
    #[serde(default)]
    pub address: Address,
    #[serde(default)]
    pub preferences: Preferences,
    #[serde(default)]
    pub payment_methods: Vec<PaymentMethod>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Address {
    #[serde(default)]
    pub street: String,
    #[serde(default)]
    pub city: String,
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub zip: String,
    #[serde(default)]
    pub country: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Preferences {
    #[serde(default)]
    pub language: String,
    #[serde(default)]
    pub currency: String,
    #[serde(default)]
    pub timezone: String,
    #[serde(default)]
    pub notifications: NotificationPrefs,
    #[serde(default)]
    pub theme: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct NotificationPrefs {
    #[serde(default)]
    pub email: bool,
    #[serde(default)]
    pub sms: bool,
    #[serde(default)]
    pub push: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentMethod {
    #[serde(rename = "type")]
    pub type_: String,
    pub last4: String,
    pub expiry_month: i32,
    pub expiry_year: i32,
    pub is_default: bool,
}

// ── Bulk order input ──

pub struct BulkOrderInput {
    pub items: Vec<OrderItem>,
    pub currency: String,
}

// ── Store ──

#[derive(Clone)]
pub struct PgStore {
    pool: PgPool,
}

fn bigdecimal_to_f64(bd: BigDecimal) -> f64 {
    use std::str::FromStr;
    f64::from_str(&bd.to_string()).unwrap_or(0.0)
}

impl PgStore {
    /// Create a new PgStore connected to the given PostgreSQL DSN.
    /// Pool settings: max 50 conns, min 10 -- same for all frameworks.
    pub async fn new(dsn: &str) -> Result<Self, sqlx::Error> {
        let pool = PgPoolOptions::new()
            .max_connections(50)
            .min_connections(10)
            .connect(dsn)
            .await?;

        // Ping to verify connection
        sqlx::query("SELECT 1").execute(&pool).await?;

        Ok(Self { pool })
    }

    /// Creates the orders table, profiles table, and index if they don't exist.
    pub async fn init_schema(&self) -> Result<(), sqlx::Error> {
        sqlx::query(
            "CREATE TABLE IF NOT EXISTS orders (
                id         BIGSERIAL PRIMARY KEY,
                user_id    VARCHAR(255) NOT NULL,
                status     VARCHAR(50) NOT NULL DEFAULT 'created',
                items      JSONB NOT NULL DEFAULT '[]',
                total      NUMERIC(12,2) NOT NULL DEFAULT 0,
                currency   VARCHAR(10) NOT NULL DEFAULT 'USD',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )",
        )
        .execute(&self.pool)
        .await?;

        sqlx::query("CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id)")
            .execute(&self.pool)
            .await?;

        sqlx::query(
            "CREATE TABLE IF NOT EXISTS profiles (
                user_id  VARCHAR(255) PRIMARY KEY,
                data     JSONB NOT NULL DEFAULT '{}'
            )",
        )
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    /// Removes all rows from orders and profiles tables.
    pub async fn truncate(&self) -> Result<(), sqlx::Error> {
        sqlx::query("TRUNCATE TABLE orders RESTART IDENTITY")
            .execute(&self.pool)
            .await?;
        sqlx::query("TRUNCATE TABLE profiles")
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    // ── CRUD Operations ──

    /// Inserts a new order and returns it with the generated ID.
    pub async fn create_order(
        &self,
        user_id: &str,
        items: &[OrderItem],
        currency: &str,
    ) -> Result<Order, sqlx::Error> {
        let currency = if currency.is_empty() { "USD" } else { currency };

        let total: f64 = items
            .iter()
            .map(|item| item.price * item.quantity as f64)
            .sum();

        let items_json = serde_json::to_value(items).unwrap_or_default();

        let row = sqlx::query(
            "INSERT INTO orders (user_id, status, items, total, currency) \
             VALUES ($1, 'created', $2, $3, $4) RETURNING id",
        )
        .bind(user_id)
        .bind(&items_json)
        .bind(BigDecimal::try_from(total).unwrap_or_default())
        .bind(currency)
        .fetch_one(&self.pool)
        .await?;

        let id: i64 = row.get("id");

        Ok(Order {
            order_id: id.to_string(),
            user_id: user_id.to_string(),
            status: "created".to_string(),
            items: items.to_vec(),
            total,
            currency: currency.to_string(),
        })
    }

    /// Retrieves an order by user ID and order ID.
    pub async fn get_order(
        &self,
        user_id: &str,
        order_id: &str,
    ) -> Result<Option<Order>, sqlx::Error> {
        let oid: i64 = order_id.parse().unwrap_or(-1);
        let row = sqlx::query(
            "SELECT id, user_id, status, items, total, currency \
             FROM orders WHERE id = $1 AND user_id = $2",
        )
        .bind(oid)
        .bind(user_id)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            None => Ok(None),
            Some(row) => Ok(Some(row_to_order(&row))),
        }
    }

    /// Updates an existing order.
    pub async fn update_order(
        &self,
        user_id: &str,
        order_id: &str,
        items: &[OrderItem],
        currency: &str,
    ) -> Result<Option<Order>, sqlx::Error> {
        let currency = if currency.is_empty() { "USD" } else { currency };

        let total: f64 = items
            .iter()
            .map(|item| item.price * item.quantity as f64)
            .sum();

        let items_json = serde_json::to_value(items).unwrap_or_default();
        let oid: i64 = order_id.parse().unwrap_or(-1);

        let result = sqlx::query(
            "UPDATE orders SET status = 'updated', items = $1, total = $2, currency = $3 \
             WHERE id = $4 AND user_id = $5",
        )
        .bind(&items_json)
        .bind(BigDecimal::try_from(total).unwrap_or_default())
        .bind(currency)
        .bind(oid)
        .bind(user_id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Ok(None);
        }

        Ok(Some(Order {
            order_id: order_id.to_string(),
            user_id: user_id.to_string(),
            status: "updated".to_string(),
            items: items.to_vec(),
            total,
            currency: currency.to_string(),
        }))
    }

    /// Removes an order and returns true if it existed.
    pub async fn delete_order(
        &self,
        user_id: &str,
        order_id: &str,
    ) -> Result<bool, sqlx::Error> {
        let oid: i64 = order_id.parse().unwrap_or(-1);
        let result = sqlx::query("DELETE FROM orders WHERE id = $1 AND user_id = $2")
            .bind(oid)
            .bind(user_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    // ── Bulk & List Operations ──

    /// Inserts multiple orders in a single transaction.
    pub async fn bulk_create_orders(
        &self,
        user_id: &str,
        orders: &[BulkOrderInput],
    ) -> Result<(Vec<Order>, f64), sqlx::Error> {
        let mut tx = self.pool.begin().await?;
        let mut results = Vec::new();
        let mut total_sum = 0.0;

        for req in orders {
            let currency = if req.currency.is_empty() {
                "USD"
            } else {
                &req.currency
            };
            let total: f64 = req
                .items
                .iter()
                .map(|item| item.price * item.quantity as f64)
                .sum();

            let items_json = serde_json::to_value(&req.items).unwrap_or_default();

            let row = sqlx::query(
                "INSERT INTO orders (user_id, status, items, total, currency) \
                 VALUES ($1, 'created', $2, $3, $4) RETURNING id",
            )
            .bind(user_id)
            .bind(&items_json)
            .bind(BigDecimal::try_from(total).unwrap_or_default())
            .bind(currency)
            .fetch_one(&mut *tx)
            .await?;

            let id: i64 = row.get("id");

            results.push(Order {
                order_id: id.to_string(),
                user_id: user_id.to_string(),
                status: "created".to_string(),
                items: req.items.clone(),
                total,
                currency: currency.to_string(),
            });
            total_sum += total;
        }

        tx.commit().await?;
        Ok((results, total_sum))
    }

    /// Returns all orders for a given user.
    pub async fn list_orders(&self, user_id: &str) -> Result<Vec<Order>, sqlx::Error> {
        let rows = sqlx::query(
            "SELECT id, user_id, status, items, total, currency \
             FROM orders WHERE user_id = $1 ORDER BY id",
        )
        .bind(user_id)
        .fetch_all(&self.pool)
        .await?;

        let orders = rows.iter().map(row_to_order).collect();
        Ok(orders)
    }

    // ── Profile Operations ──

    /// Creates or updates a user profile (stored as JSONB).
    pub async fn upsert_profile(
        &self,
        user_id: &str,
        profile: &Profile,
    ) -> Result<(), sqlx::Error> {
        let mut p = profile.clone();
        p.user_id = user_id.to_string();
        let data = serde_json::to_value(&p).unwrap_or_default();

        sqlx::query(
            "INSERT INTO profiles (user_id, data) VALUES ($1, $2) \
             ON CONFLICT (user_id) DO UPDATE SET data = $2",
        )
        .bind(user_id)
        .bind(&data)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    /// Retrieves a user profile.
    pub async fn get_profile(&self, user_id: &str) -> Result<Option<Profile>, sqlx::Error> {
        let row = sqlx::query("SELECT data FROM profiles WHERE user_id = $1")
            .bind(user_id)
            .fetch_optional(&self.pool)
            .await?;

        match row {
            None => Ok(None),
            Some(row) => {
                let data: serde_json::Value = row.get("data");
                let profile: Profile =
                    serde_json::from_value(data).unwrap_or_else(|_| Profile {
                        user_id: user_id.to_string(),
                        name: String::new(),
                        email: String::new(),
                        phone: String::new(),
                        address: Address::default(),
                        preferences: Preferences::default(),
                        payment_methods: Vec::new(),
                        tags: Vec::new(),
                        metadata: HashMap::new(),
                    });
                Ok(Some(profile))
            }
        }
    }
}

/// Convert a database row to an Order struct.
fn row_to_order(row: &sqlx::postgres::PgRow) -> Order {
    let id: i64 = row.get("id");
    let user_id: String = row.get("user_id");
    let status: String = row.get("status");
    let items_json: serde_json::Value = row.get("items");
    let total_bd: BigDecimal = row.get("total");
    let currency: String = row.get("currency");

    let items: Vec<OrderItem> = serde_json::from_value(items_json).unwrap_or_default();

    Order {
        order_id: id.to_string(),
        user_id,
        status,
        items,
        total: bigdecimal_to_f64(total_bd),
        currency,
    }
}
