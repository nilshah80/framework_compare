package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"

	"github.com/nilshah80/aarv"
	segmentiocodec "github.com/nilshah80/aarv/codec/segmentio"
	"github.com/nilshah80/aarv/plugins/bodylimit"
	"github.com/nilshah80/aarv/plugins/cors"
	"github.com/nilshah80/aarv/plugins/recover"
	"github.com/nilshah80/aarv/plugins/requestid"
	"github.com/nilshah80/aarv/plugins/secure"
	"github.com/nilshah80/aarv/plugins/verboselog"
)

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
	orderStore   = make(map[string]OrderResponse)
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

	app := aarv.New(
		aarv.WithBanner(false),
		aarv.WithCodec(segmentiocodec.New()),
	)

	app.Use(recover.New())
	app.Use(requestid.New())
	app.Use(cors.New())
	app.Use(secure.New())
	app.Use(bodylimit.New(1 << 20))
	logCfg := verboselog.DefaultConfig()
	logCfg.LogContentInfo = false
	app.Use(verboselog.New(logCfg))

	app.Group("/users/{userId}/orders", func(g *aarv.RouteGroup) {
		g.Post("", aarv.BindReq(func(c *aarv.Context, req CreateOrderReq) error {
			userID := c.Param("userId")
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
				RequestID: c.RequestID(),
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

		g.Get("/{orderId}", aarv.BindReq(func(c *aarv.Context, req GetOrderReq) error {
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

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		return c.JSON(http.StatusOK, profile)
	})

	app.Get("/users/{userId}/profile", func(c *aarv.Context) error {
		userID := c.Param("userId")
		reqID := c.RequestID()

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "profile not found"})
		}

		profile.RequestID = reqID
		return c.JSON(http.StatusOK, profile)
	})

	slog.Info("server starting", slog.String("port", "8086"))
	app.Listen(":8086")
}
