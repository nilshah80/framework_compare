// Package pgstore provides a shared PostgreSQL-backed order store
// used identically by all Go web framework benchmarks.
// This ensures the DB layer is byte-for-byte identical across frameworks.
package pgstore

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ── Types (matching the JSON shapes used by all frameworks) ──

type OrderItem struct {
	ProductID string  `json:"product_id"`
	Name      string  `json:"name"`
	Quantity  int     `json:"quantity"`
	Price     float64 `json:"price"`
}

type Order struct {
	OrderID  string      `json:"order_id"`
	UserID   string      `json:"user_id"`
	Status   string      `json:"status"`
	Items    []OrderItem `json:"items"`
	Total    float64     `json:"total"`
	Currency string      `json:"currency"`
}

// ── Profile types ──

type Profile struct {
	UserID      string            `json:"user_id"`
	Name        string            `json:"name"`
	Email       string            `json:"email"`
	Phone       string            `json:"phone"`
	Address     Address           `json:"address"`
	Preferences Preferences       `json:"preferences"`
	Payment     []PaymentMethod   `json:"payment_methods"`
	Tags        []string          `json:"tags"`
	Metadata    map[string]string `json:"metadata"`
}

type Address struct {
	Street  string `json:"street"`
	City    string `json:"city"`
	State   string `json:"state"`
	Zip     string `json:"zip"`
	Country string `json:"country"`
}

type Preferences struct {
	Language      string            `json:"language"`
	Currency      string            `json:"currency"`
	Timezone      string            `json:"timezone"`
	Notifications NotificationPrefs `json:"notifications"`
	Theme         string            `json:"theme"`
}

type NotificationPrefs struct {
	Email bool `json:"email"`
	SMS   bool `json:"sms"`
	Push  bool `json:"push"`
}

type PaymentMethod struct {
	Type        string `json:"type"`
	Last4       string `json:"last4"`
	ExpiryMonth int    `json:"expiry_month"`
	ExpiryYear  int    `json:"expiry_year"`
	IsDefault   bool   `json:"is_default"`
}

// ── Store ──

type PgStore struct {
	pool *pgxpool.Pool
	mu   sync.Mutex // only for schema init
}

// New creates a PgStore connected to the given PostgreSQL DSN.
// Pool settings: max 50 conns, min 10 — same for all frameworks.
func New(ctx context.Context, dsn string) (*PgStore, error) {
	config, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("pgstore: parse config: %w", err)
	}
	config.MaxConns = 50
	config.MinConns = 10

	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("pgstore: connect: %w", err)
	}

	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("pgstore: ping: %w", err)
	}

	return &PgStore{pool: pool}, nil
}

// InitSchema creates the orders table and index if they don't exist.
func (s *PgStore) InitSchema(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	_, err := s.pool.Exec(ctx, `
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
	`)
	return err
}

// Truncate removes all rows from the orders table.
// Called before each benchmark run for a clean state.
func (s *PgStore) Truncate(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, "TRUNCATE TABLE orders RESTART IDENTITY; TRUNCATE TABLE profiles")
	return err
}

// Close closes the connection pool.
func (s *PgStore) Close() {
	s.pool.Close()
}

// ── CRUD Operations ──

// CreateOrder inserts a new order and returns it with the generated ID.
func (s *PgStore) CreateOrder(ctx context.Context, userID string, items []OrderItem, currency string) (Order, error) {
	if currency == "" {
		currency = "USD"
	}

	var total float64
	for _, item := range items {
		total += item.Price * float64(item.Quantity)
	}

	itemsJSON, err := json.Marshal(items)
	if err != nil {
		return Order{}, fmt.Errorf("pgstore: marshal items: %w", err)
	}

	var id int64
	err = s.pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, status, items, total, currency)
		 VALUES ($1, 'created', $2, $3, $4)
		 RETURNING id`,
		userID, itemsJSON, total, currency,
	).Scan(&id)
	if err != nil {
		return Order{}, fmt.Errorf("pgstore: insert: %w", err)
	}

	return Order{
		OrderID:  fmt.Sprintf("%d", id),
		UserID:   userID,
		Status:   "created",
		Items:    items,
		Total:    total,
		Currency: currency,
	}, nil
}

// GetOrder retrieves an order by user ID and order ID.
func (s *PgStore) GetOrder(ctx context.Context, userID, orderID string) (Order, bool, error) {
	var o Order
	var itemsJSON []byte

	err := s.pool.QueryRow(ctx,
		`SELECT id, user_id, status, items, total, currency
		 FROM orders WHERE id = $1 AND user_id = $2`,
		orderID, userID,
	).Scan(&o.OrderID, &o.UserID, &o.Status, &itemsJSON, &o.Total, &o.Currency)

	if err != nil {
		if err.Error() == "no rows in result set" {
			return Order{}, false, nil
		}
		return Order{}, false, fmt.Errorf("pgstore: get: %w", err)
	}

	// Scan returns id as string since we scan into string field
	if err := json.Unmarshal(itemsJSON, &o.Items); err != nil {
		return Order{}, false, fmt.Errorf("pgstore: unmarshal items: %w", err)
	}

	return o, true, nil
}

// UpdateOrder updates an existing order.
func (s *PgStore) UpdateOrder(ctx context.Context, userID, orderID string, items []OrderItem, currency string) (Order, bool, error) {
	if currency == "" {
		currency = "USD"
	}

	var total float64
	for _, item := range items {
		total += item.Price * float64(item.Quantity)
	}

	itemsJSON, err := json.Marshal(items)
	if err != nil {
		return Order{}, false, fmt.Errorf("pgstore: marshal items: %w", err)
	}

	tag, err := s.pool.Exec(ctx,
		`UPDATE orders SET status = 'updated', items = $1, total = $2, currency = $3
		 WHERE id = $4 AND user_id = $5`,
		itemsJSON, total, currency, orderID, userID,
	)
	if err != nil {
		return Order{}, false, fmt.Errorf("pgstore: update: %w", err)
	}

	if tag.RowsAffected() == 0 {
		return Order{}, false, nil
	}

	return Order{
		OrderID:  orderID,
		UserID:   userID,
		Status:   "updated",
		Items:    items,
		Total:    total,
		Currency: currency,
	}, true, nil
}

// DeleteOrder removes an order and returns true if it existed.
func (s *PgStore) DeleteOrder(ctx context.Context, userID, orderID string) (bool, error) {
	tag, err := s.pool.Exec(ctx,
		`DELETE FROM orders WHERE id = $1 AND user_id = $2`,
		orderID, userID,
	)
	if err != nil {
		return false, fmt.Errorf("pgstore: delete: %w", err)
	}

	return tag.RowsAffected() > 0, nil
}

// ── Bulk & List Operations ──

// BulkCreateOrders inserts multiple orders in a single transaction.
func (s *PgStore) BulkCreateOrders(ctx context.Context, userID string, orders []struct {
	Items    []OrderItem
	Currency string
}) ([]Order, float64, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, 0, fmt.Errorf("pgstore: begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	var results []Order
	var totalSum float64

	for _, req := range orders {
		currency := req.Currency
		if currency == "" {
			currency = "USD"
		}
		var total float64
		for _, item := range req.Items {
			total += item.Price * float64(item.Quantity)
		}

		itemsJSON, err := json.Marshal(req.Items)
		if err != nil {
			return nil, 0, fmt.Errorf("pgstore: marshal items: %w", err)
		}

		var id int64
		err = tx.QueryRow(ctx,
			`INSERT INTO orders (user_id, status, items, total, currency)
			 VALUES ($1, 'created', $2, $3, $4) RETURNING id`,
			userID, itemsJSON, total, currency,
		).Scan(&id)
		if err != nil {
			return nil, 0, fmt.Errorf("pgstore: bulk insert: %w", err)
		}

		results = append(results, Order{
			OrderID:  fmt.Sprintf("%d", id),
			UserID:   userID,
			Status:   "created",
			Items:    req.Items,
			Total:    total,
			Currency: currency,
		})
		totalSum += total
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, 0, fmt.Errorf("pgstore: commit: %w", err)
	}

	return results, totalSum, nil
}

// ListOrders returns all orders for a given user.
func (s *PgStore) ListOrders(ctx context.Context, userID string) ([]Order, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, user_id, status, items, total, currency
		 FROM orders WHERE user_id = $1 ORDER BY id`,
		userID,
	)
	if err != nil {
		return nil, fmt.Errorf("pgstore: list: %w", err)
	}
	defer rows.Close()

	var orders []Order
	for rows.Next() {
		var o Order
		var itemsJSON []byte
		if err := rows.Scan(&o.OrderID, &o.UserID, &o.Status, &itemsJSON, &o.Total, &o.Currency); err != nil {
			return nil, fmt.Errorf("pgstore: scan: %w", err)
		}
		if err := json.Unmarshal(itemsJSON, &o.Items); err != nil {
			return nil, fmt.Errorf("pgstore: unmarshal items: %w", err)
		}
		orders = append(orders, o)
	}

	return orders, rows.Err()
}

// ── Profile Operations ──

// UpsertProfile creates or updates a user profile (stored as JSONB).
func (s *PgStore) UpsertProfile(ctx context.Context, userID string, profile Profile) error {
	profile.UserID = userID
	data, err := json.Marshal(profile)
	if err != nil {
		return fmt.Errorf("pgstore: marshal profile: %w", err)
	}

	_, err = s.pool.Exec(ctx,
		`INSERT INTO profiles (user_id, data) VALUES ($1, $2)
		 ON CONFLICT (user_id) DO UPDATE SET data = $2`,
		userID, data,
	)
	return err
}

// GetProfile retrieves a user profile.
func (s *PgStore) GetProfile(ctx context.Context, userID string) (Profile, bool, error) {
	var data []byte
	err := s.pool.QueryRow(ctx,
		`SELECT data FROM profiles WHERE user_id = $1`,
		userID,
	).Scan(&data)
	if err != nil {
		if err.Error() == "no rows in result set" {
			return Profile{}, false, nil
		}
		return Profile{}, false, fmt.Errorf("pgstore: get profile: %w", err)
	}

	var p Profile
	if err := json.Unmarshal(data, &p); err != nil {
		return Profile{}, false, fmt.Errorf("pgstore: unmarshal profile: %w", err)
	}
	return p, true, nil
}
