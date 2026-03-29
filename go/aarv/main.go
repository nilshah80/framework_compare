package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"

	"github.com/nilshah80/aarv"
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

// --- In-memory store ---

var (
	orderStore   = make(map[string]OrderResponse) // key: "userId:orderId"
	orderMu      sync.RWMutex
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

		// GET /users/{userId}/orders/{orderId} — fetch order from memory
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
	})

	slog.Info("server starting", slog.String("port", "8081"))
	app.Listen(":8081")
}
