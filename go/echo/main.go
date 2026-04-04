package main

import (
	"bytes"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// --- Request/Response types ---

type CreateOrderReq struct {
	Items    []OrderItem `json:"items"`
	Currency string      `json:"currency"`
}

type GetOrderReq struct {
	UserID  string `param:"userId"`
	OrderID string `param:"orderId"`
	Fields  string `query:"fields"`
	Token   string `header:"X-Api-Key"`
}

type OrderResponse struct {
	OrderID   string      `json:"order_id"`
	UserID    string      `json:"user_id"`
	Status    string      `json:"status"`
	Items     []OrderItem `json:"items"`
	Total     float64     `json:"total"`
	Currency  string      `json:"currency"`
	Fields    string      `json:"fields"`
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

// PII fields to redact
var piiFields = map[string]bool{
	"password": true, "token": true, "secret": true,
	"api_key": true, "ssn": true, "credit_card": true,
	"authorization": true, "cookie": true, "set-cookie": true,
	"x-api-key": true, "x-auth-token": true,
}

func redactValue(key, value string) string {
	if piiFields[strings.ToLower(key)] {
		return "[REDACTED]"
	}
	return value
}

// bodyCapturingWriter wraps http.ResponseWriter to capture response body
type bodyCapturingWriter struct {
	http.ResponseWriter
	body       *bytes.Buffer
	statusCode int
}

func (w *bodyCapturingWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (w *bodyCapturingWriter) WriteHeader(code int) {
	w.statusCode = code
	w.ResponseWriter.WriteHeader(code)
}

// structuredLogger middleware logs a single http_dump line per request
func structuredLogger(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		start := time.Now()

		// Get request ID (set by RequestID middleware on response header)
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		// Log request headers with PII redaction
		reqHeaders := make(map[string]string)
		for key, values := range c.Request().Header {
			if len(values) > 0 {
				reqHeaders[key] = redactValue(key, values[0])
			}
		}

		// Wrap response writer to capture body
		bodyBuf := new(bytes.Buffer)
		writer := &bodyCapturingWriter{
			ResponseWriter: c.Response().Writer,
			body:           bodyBuf,
			statusCode:     http.StatusOK,
		}
		c.Response().Writer = writer

		// Process request
		err := next(c)

		// Log response headers with PII redaction
		respHeaders := make(map[string]string)
		for key, values := range c.Response().Header() {
			if len(values) > 0 {
				respHeaders[key] = redactValue(key, values[0])
			}
		}

		latency := time.Since(start)
		latencyMs := float64(latency.Microseconds()) / 1000.0

		// Build query params map
		queryParams := make(map[string]string)
		for k, v := range c.Request().URL.Query() {
			if len(v) > 0 {
				queryParams[k] = v[0]
			}
		}

		// Single http_dump log line
		slog.Info("http_dump",
			slog.String("request_id", requestID),
			slog.String("method", c.Request().Method),
			slog.String("path", c.Request().URL.Path),
			slog.Any("query", queryParams),
			slog.String("client_ip", c.RealIP()),
			slog.String("user_agent", c.Request().Header.Get("User-Agent")),
			slog.Any("request_headers", reqHeaders),
			slog.Int("status", c.Response().Status),
			slog.String("latency", latency.String()),
			slog.Float64("latency_ms", latencyMs),
			slog.Any("response_headers", respHeaders),
			slog.String("response_body", bodyBuf.String()),
			slog.Int64("bytes_out", c.Response().Size),
		)

		return err
	}
}

func main() {
	// Setup slog JSON handler
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	e := echo.New()
	e.HideBanner = true
	e.HidePort = true

	// Built-in middlewares
	e.Use(middleware.Recover())
	e.Use(middleware.RequestID())
	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins: []string{"*"},
		AllowMethods: []string{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"},
		AllowHeaders: []string{"Origin", "Content-Type", "Accept", "Authorization"},
	}))
	e.Use(middleware.SecureWithConfig(middleware.SecureConfig{
		XSSProtection:         "1; mode=block",
		ContentTypeNosniff:    "nosniff",
		XFrameOptions:         "DENY",
		HSTSMaxAge:            31536000,
		HSTSPreloadEnabled:    false,
		ContentSecurityPolicy: "default-src 'self'",
		ReferrerPolicy:        "strict-origin-when-cross-origin",
	}))
	// Extra security headers not covered by Echo's Secure middleware
	e.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			c.Response().Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
			c.Response().Header().Set("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
			c.Response().Header().Set("Cross-Origin-Opener-Policy", "same-origin")
			return next(c)
		}
	})
	e.Use(middleware.BodyLimit("1M"))

	// Custom structured logger middleware
	e.Use(structuredLogger)

	// Grouped routes: /users/:userId/orders
	orders := e.Group("/users/:userId/orders")

	// POST /users/:userId/orders — create order
	orders.POST("", func(c echo.Context) error {
		var req CreateOrderReq
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		}

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

		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		order := OrderResponse{
			OrderID:   orderID,
			UserID:    userID,
			Status:    "created",
			Items:     req.Items,
			Total:     total,
			Currency:  currency,
			RequestID: requestID,
		}

		orderMu.Lock()
		orderStore[storeKey(userID, orderID)] = order
		orderMu.Unlock()

		return c.JSON(http.StatusCreated, order)
	})

	// PUT /users/:userId/orders/:orderId — update order
	orders.PUT("/:orderId", func(c echo.Context) error {
		var body CreateOrderReq
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		}

		userID := c.Param("userId")
		orderID := c.Param("orderId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		key := storeKey(userID, orderID)

		orderMu.Lock()
		order, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			return c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
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
		order.RequestID = requestID
		orderStore[key] = order
		orderMu.Unlock()

		return c.JSON(http.StatusOK, order)
	})

	// DELETE /users/:userId/orders/:orderId — delete order
	orders.DELETE("/:orderId", func(c echo.Context) error {
		userID := c.Param("userId")
		orderID := c.Param("orderId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		key := storeKey(userID, orderID)

		orderMu.Lock()
		_, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			return c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
		}
		delete(orderStore, key)
		orderMu.Unlock()

		return c.JSON(http.StatusOK, map[string]string{
			"message":    "order deleted",
			"order_id":   orderID,
			"request_id": requestID,
		})
	})

	// GET /users/:userId/orders/:orderId — fetch order from memory
	orders.GET("/:orderId", func(c echo.Context) error {
		userID := c.Param("userId")
		orderID := c.Param("orderId")
		fields := c.QueryParam("fields")
		if fields == "" {
			fields = "*"
		}

		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		orderMu.RLock()
		order, ok := orderStore[storeKey(userID, orderID)]
		orderMu.RUnlock()

		if !ok {
			return c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
		}

		order.Fields = fields
		order.RequestID = requestID
		return c.JSON(http.StatusOK, order)
	})

	// POST /users/:userId/orders/bulk — bulk create orders
	orders.POST("/bulk", func(c echo.Context) error {
		var req BulkCreateOrderReq
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		}

		userID := c.Param("userId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

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
				RequestID: requestID,
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
			RequestID: requestID,
		})
	})

	// GET /users/:userId/orders — list all orders for user
	orders.GET("", func(c echo.Context) error {
		userID := c.Param("userId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		prefix := userID + ":"
		var results []OrderResponse

		orderMu.RLock()
		for key, order := range orderStore {
			if len(key) > len(prefix) && key[:len(prefix)] == prefix {
				order.RequestID = requestID
				results = append(results, order)
			}
		}
		orderMu.RUnlock()

		return c.JSON(http.StatusOK, map[string]any{
			"user_id":    userID,
			"count":      len(results),
			"orders":     results,
			"request_id": requestID,
		})
	})

	// PUT /users/:userId/profile — create/update user profile
	e.PUT("/users/:userId/profile", func(c echo.Context) error {
		userID := c.Param("userId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		var profile UserProfile
		if err := c.Bind(&profile); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		}

		profile.UserID = userID
		profile.RequestID = requestID

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		return c.JSON(http.StatusOK, profile)
	})

	// GET /users/:userId/profile — get user profile
	e.GET("/users/:userId/profile", func(c echo.Context) error {
		userID := c.Param("userId")
		requestID := c.Response().Header().Get(echo.HeaderXRequestID)

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "profile not found"})
		}

		profile.RequestID = requestID
		return c.JSON(http.StatusOK, profile)
	})

	// Start server
	slog.Info("server starting", slog.String("port", "8084"))
	e.Logger.Fatal(e.Start(":8084"))
}
