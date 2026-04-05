package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"

	"benchmark/pgstore"

	"github.com/nilshah80/aarv"
	"github.com/nilshah80/aarv/plugins/bodylimit"
	"github.com/nilshah80/aarv/plugins/cors"
	"github.com/nilshah80/aarv/plugins/recover"
	"github.com/nilshah80/aarv/plugins/requestid"
	"github.com/nilshah80/aarv/plugins/secure"
	"github.com/nilshah80/aarv/plugins/verboselog"
)

var pgStore *pgstore.PgStore

func toPgItems(items []OrderItem) []pgstore.OrderItem {
	result := make([]pgstore.OrderItem, len(items))
	for i, item := range items {
		result[i] = pgstore.OrderItem{ProductID: item.ProductID, Name: item.Name, Quantity: item.Quantity, Price: item.Price}
	}
	return result
}

func fromPgItems(items []pgstore.OrderItem) []OrderItem {
	result := make([]OrderItem, len(items))
	for i, item := range items {
		result[i] = OrderItem{ProductID: item.ProductID, Name: item.Name, Quantity: item.Quantity, Price: item.Price}
	}
	return result
}

func toPgProfile(p UserProfile) pgstore.Profile {
	payment := make([]pgstore.PaymentMethod, len(p.Payment))
	for i, pm := range p.Payment {
		payment[i] = pgstore.PaymentMethod{Type: pm.Type, Last4: pm.Last4, ExpiryMonth: pm.ExpiryMonth, ExpiryYear: pm.ExpiryYear, IsDefault: pm.IsDefault}
	}
	return pgstore.Profile{
		Name: p.Name, Email: p.Email, Phone: p.Phone,
		Address: pgstore.Address{Street: p.Address.Street, City: p.Address.City, State: p.Address.State, Zip: p.Address.Zip, Country: p.Address.Country},
		Preferences: pgstore.Preferences{Language: p.Preferences.Language, Currency: p.Preferences.Currency, Timezone: p.Preferences.Timezone,
			Notifications: pgstore.NotificationPrefs{Email: p.Preferences.Notifications.Email, SMS: p.Preferences.Notifications.SMS, Push: p.Preferences.Notifications.Push},
			Theme: p.Preferences.Theme},
		Payment: payment, Tags: p.Tags, Metadata: p.Metadata,
	}
}

func fromPgProfile(p pgstore.Profile) UserProfile {
	payment := make([]PaymentMethod, len(p.Payment))
	for i, pm := range p.Payment {
		payment[i] = PaymentMethod{Type: pm.Type, Last4: pm.Last4, ExpiryMonth: pm.ExpiryMonth, ExpiryYear: pm.ExpiryYear, IsDefault: pm.IsDefault}
	}
	return UserProfile{
		UserID: p.UserID, Name: p.Name, Email: p.Email, Phone: p.Phone,
		Address: Address{Street: p.Address.Street, City: p.Address.City, State: p.Address.State, Zip: p.Address.Zip, Country: p.Address.Country},
		Preferences: Preferences{Language: p.Preferences.Language, Currency: p.Preferences.Currency, Timezone: p.Preferences.Timezone,
			Notifications: NotificationPrefs{Email: p.Preferences.Notifications.Email, SMS: p.Preferences.Notifications.SMS, Push: p.Preferences.Notifications.Push},
			Theme: p.Preferences.Theme},
		Payment: payment, Tags: p.Tags, Metadata: p.Metadata,
	}
}

// --- Request/Response types ---

type CreateOrderReq struct {
	Items    []OrderItem `json:"items"`
	Currency string      `json:"currency"`
}

type GetOrderReq struct {
	UserID  string `param:"userId"`
	OrderID string `param:"orderId"`
	Fields  string `query:"fields" default:"*"`
	Token   string `header:"X-Api-Key"`
}

type OrderResponse struct {
	OrderID   string      `json:"order_id"`
	UserID    string      `json:"user_id"`
	Status    string      `json:"status"`
	Items     []OrderItem `json:"items"`
	Total     float64     `json:"total"`
	Currency  string      `json:"currency"`
	Fields    string      `json:"fields,omitempty"`
	RequestID string      `json:"request_id"`
}

type OrderItem struct {
	ProductID string  `json:"product_id"`
	Name      string  `json:"name"`
	Quantity  int     `json:"quantity"`
	Price     float64 `json:"price"`
}

// --- Bulk Orders types ---

type BulkCreateOrderReq struct {
	Orders []CreateOrderReq `json:"orders"`
}

type BulkOrderResponse struct {
	UserID    string          `json:"user_id"`
	Count     int             `json:"count"`
	Orders    []OrderResponse `json:"orders"`
	TotalSum  float64         `json:"total_sum"`
	RequestID string          `json:"request_id"`
}

// --- User Profile types (deeply nested) ---

type UserProfile struct {
	UserID      string            `json:"user_id"`
	Name        string            `json:"name"`
	Email       string            `json:"email"`
	Phone       string            `json:"phone"`
	Address     Address           `json:"address"`
	Preferences Preferences       `json:"preferences"`
	Payment     []PaymentMethod   `json:"payment_methods"`
	Tags        []string          `json:"tags"`
	Metadata    map[string]string `json:"metadata"`
	RequestID   string            `json:"request_id"`
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

// --- In-memory store ---

var (
	orderStore   = make(map[string]OrderResponse) // key: "userId:orderId"
	profileStore = make(map[string]UserProfile)
	orderMu      sync.RWMutex
	profileMu    sync.RWMutex
	orderCounter int64
	counterMu    sync.Mutex
)

func nextOrderID() string {
	counterMu.Lock()
	orderCounter++
	id := fmt.Sprintf("%d", orderCounter)
	counterMu.Unlock()
	return id
}

func storeKey(userID, orderID string) string {
	return userID + ":" + orderID
}

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	if dsn := os.Getenv("PG_DSN"); dsn != "" {
		var err error
		pgStore, err = pgstore.New(context.Background(), dsn)
		if err != nil {
			slog.Error("failed to connect to PostgreSQL", "error", err)
			os.Exit(1)
		}
		defer pgStore.Close()
		if err := pgStore.InitSchema(context.Background()); err != nil {
			slog.Error("failed to init schema", "error", err)
			os.Exit(1)
		}
		slog.Info("using PostgreSQL store", "dsn", dsn)
	} else {
		slog.Info("using in-memory store")
	}

	app := aarv.New(aarv.WithBanner(false))

	// Register middleware plugins
	app.Use(recover.New())
	app.Use(requestid.New())
	app.Use(cors.New())
	app.Use(secure.New())
	app.Use(bodylimit.New(1 << 20))
	logCfg := verboselog.DefaultConfig()
	logCfg.LogContentInfo = false
	app.Use(verboselog.New(logCfg))

	// Grouped routes: /users/{userId}/orders
	app.Group("/users/{userId}/orders", func(g *aarv.RouteGroup) {
		// POST /users/{userId}/orders — create order
		g.Post("", aarv.BindReq(func(c *aarv.Context, req CreateOrderReq) error {
			userID := c.Param("userId")
			reqID := c.RequestID()

			if pgStore != nil {
				order, err := pgStore.CreateOrder(c.Request().Context(), userID, toPgItems(req.Items), req.Currency)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				return c.JSON(http.StatusCreated, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					RequestID: reqID,
				})
			}

			orderID := nextOrderID()

			var total float64
			for _, item := range req.Items {
				total += item.Price * float64(item.Quantity)
			}

			currency := req.Currency
			if currency == "" {
				currency = "USD"
			}

			order := OrderResponse{
				OrderID:   orderID,
				UserID:    userID,
				Status:    "created",
				Items:     req.Items,
				Total:     total,
				Currency:  currency,
				RequestID: reqID,
			}

			orderMu.Lock()
			orderStore[storeKey(userID, orderID)] = order
			orderMu.Unlock()

			return c.JSON(http.StatusCreated, order)
		}))

		// PUT /users/{userId}/orders/{orderId} — update order
		g.Put("/{orderId}", aarv.BindReq(func(c *aarv.Context, req GetOrderReq) error {
			var body CreateOrderReq
			if err := c.BindJSON(&body); err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
			}

			if pgStore != nil {
				order, found, err := pgStore.UpdateOrder(c.Request().Context(), req.UserID, req.OrderID, toPgItems(body.Items), body.Currency)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				if !found {
					return c.JSON(http.StatusNotFound, map[string]string{"error": "order not found", "order_id": req.OrderID, "request_id": c.RequestID()})
				}
				return c.JSON(http.StatusOK, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					RequestID: c.RequestID(),
				})
			}

			key := storeKey(req.UserID, req.OrderID)

			orderMu.Lock()
			order, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				return c.JSON(http.StatusNotFound, map[string]string{
					"error":      "order not found",
					"order_id":   req.OrderID,
					"request_id": c.RequestID(),
				})
			}

			order.Items = body.Items
			var total float64
			for _, item := range body.Items {
				total += item.Price * float64(item.Quantity)
			}
			order.Total = total

			currency := body.Currency
			if currency == "" {
				currency = "USD"
			}
			order.Currency = currency
			order.Status = "updated"
			order.RequestID = c.RequestID()
			orderStore[key] = order
			orderMu.Unlock()

			return c.JSON(http.StatusOK, order)
		}))

		// DELETE /users/{userId}/orders/{orderId} — delete order
		g.Delete("/{orderId}", aarv.BindReq(func(c *aarv.Context, req GetOrderReq) error {
			if pgStore != nil {
				found, err := pgStore.DeleteOrder(c.Request().Context(), req.UserID, req.OrderID)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				if !found {
					return c.JSON(http.StatusNotFound, map[string]string{"error": "order not found", "order_id": req.OrderID, "request_id": c.RequestID()})
				}
				return c.JSON(http.StatusOK, map[string]string{"message": "order deleted", "order_id": req.OrderID, "request_id": c.RequestID()})
			}

			key := storeKey(req.UserID, req.OrderID)

			orderMu.Lock()
			_, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				return c.JSON(http.StatusNotFound, map[string]string{
					"error":      "order not found",
					"order_id":   req.OrderID,
					"request_id": c.RequestID(),
				})
			}
			delete(orderStore, key)
			orderMu.Unlock()

			return c.JSON(http.StatusOK, map[string]string{
				"message":    "order deleted",
				"order_id":   req.OrderID,
				"request_id": c.RequestID(),
			})
		}))

		// GET /users/{userId}/orders/{orderId} — fetch order from memory
		g.Get("/{orderId}", aarv.BindReq(func(c *aarv.Context, req GetOrderReq) error {
			if pgStore != nil {
				order, found, err := pgStore.GetOrder(c.Request().Context(), req.UserID, req.OrderID)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				if !found {
					return c.JSON(http.StatusNotFound, map[string]string{"error": "order not found", "order_id": req.OrderID, "request_id": c.RequestID()})
				}
				return c.JSON(http.StatusOK, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					Fields: req.Fields, RequestID: c.RequestID(),
				})
			}

			orderMu.RLock()
			order, ok := orderStore[storeKey(req.UserID, req.OrderID)]
			orderMu.RUnlock()

			if !ok {
				return c.JSON(http.StatusNotFound, map[string]string{
					"error":      "order not found",
					"order_id":   req.OrderID,
					"request_id": c.RequestID(),
				})
			}

			order.Fields = req.Fields
			order.RequestID = c.RequestID()
			return c.JSON(http.StatusOK, order)
		}))

		// POST /users/{userId}/orders/bulk — bulk create orders
		g.Post("/bulk", aarv.BindReq(func(c *aarv.Context, req BulkCreateOrderReq) error {
			userID := c.Param("userId")
			reqID := c.RequestID()

			if pgStore != nil {
				var bulkItems []struct {
					Items    []pgstore.OrderItem
					Currency string
				}
				for _, o := range req.Orders {
					bulkItems = append(bulkItems, struct {
						Items    []pgstore.OrderItem
						Currency string
					}{Items: toPgItems(o.Items), Currency: o.Currency})
				}
				orders, totalSum, err := pgStore.BulkCreateOrders(c.Request().Context(), userID, bulkItems)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				var results []OrderResponse
				for _, o := range orders {
					results = append(results, OrderResponse{
						OrderID: o.OrderID, UserID: o.UserID, Status: o.Status,
						Items: fromPgItems(o.Items), Total: o.Total, Currency: o.Currency,
						RequestID: reqID,
					})
				}
				return c.JSON(http.StatusCreated, BulkOrderResponse{
					UserID: userID, Count: len(results), Orders: results,
					TotalSum: totalSum, RequestID: reqID,
				})
			}

			var results []OrderResponse
			var totalSum float64

			orderMu.Lock()
			for _, item := range req.Orders {
				orderID := nextOrderID()
				var total float64
				for _, i := range item.Items {
					total += i.Price * float64(i.Quantity)
				}
				currency := item.Currency
				if currency == "" {
					currency = "USD"
				}
				order := OrderResponse{
					OrderID:   orderID,
					UserID:    userID,
					Status:    "created",
					Items:     item.Items,
					Total:     total,
					Currency:  currency,
					RequestID: reqID,
				}
				orderStore[storeKey(userID, orderID)] = order
				results = append(results, order)
				totalSum += total
			}
			orderMu.Unlock()

			return c.JSON(http.StatusCreated, BulkOrderResponse{
				UserID:    userID,
				Count:     len(results),
				Orders:    results,
				TotalSum:  totalSum,
				RequestID: reqID,
			})
		}))

		// GET /users/{userId}/orders — list all orders for user
		g.Get("", func(c *aarv.Context) error {
			userID := c.Param("userId")
			reqID := c.RequestID()

			if pgStore != nil {
				pgOrders, err := pgStore.ListOrders(c.Request().Context(), userID)
				if err != nil {
					return c.JSON(500, map[string]string{"error": "database error"})
				}
				var results []OrderResponse
				for _, o := range pgOrders {
					results = append(results, OrderResponse{
						OrderID: o.OrderID, UserID: o.UserID, Status: o.Status,
						Items: fromPgItems(o.Items), Total: o.Total, Currency: o.Currency,
						RequestID: reqID,
					})
				}
				return c.JSON(http.StatusOK, map[string]any{
					"user_id": userID, "count": len(results),
					"orders": results, "request_id": reqID,
				})
			}

			prefix := userID + ":"
			var results []OrderResponse

			orderMu.RLock()
			for key, order := range orderStore {
				if len(key) > len(prefix) && key[:len(prefix)] == prefix {
					order.RequestID = reqID
					results = append(results, order)
				}
			}
			orderMu.RUnlock()

			return c.JSON(http.StatusOK, map[string]any{
				"user_id":    userID,
				"count":      len(results),
				"orders":     results,
				"request_id": reqID,
			})
		})
	})

	// Profile routes: /users/{userId}/profile
	app.Put("/users/{userId}/profile", func(c *aarv.Context) error {
		userID := c.Param("userId")
		reqID := c.RequestID()

		var profile UserProfile
		if err := c.BindJSON(&profile); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		}

		profile.UserID = userID
		profile.RequestID = reqID

		if pgStore != nil {
			if err := pgStore.UpsertProfile(c.Request().Context(), userID, toPgProfile(profile)); err != nil {
				return c.JSON(500, map[string]string{"error": "database error"})
			}
			return c.JSON(http.StatusOK, profile)
		}

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		return c.JSON(http.StatusOK, profile)
	})

	app.Get("/users/{userId}/profile", func(c *aarv.Context) error {
		userID := c.Param("userId")
		reqID := c.RequestID()

		if pgStore != nil {
			p, found, err := pgStore.GetProfile(c.Request().Context(), userID)
			if err != nil {
				return c.JSON(500, map[string]string{"error": "database error"})
			}
			if !found {
				return c.JSON(http.StatusNotFound, map[string]string{"error": "profile not found"})
			}
			result := fromPgProfile(p)
			result.RequestID = reqID
			return c.JSON(http.StatusOK, result)
		}

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "profile not found"})
		}

		profile.RequestID = reqID
		return c.JSON(http.StatusOK, profile)
	})

	slog.Info("server starting", slog.String("port", "8081"))
	app.Listen(":8081")
}
