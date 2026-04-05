package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"benchmark/pgstore"

	"github.com/gin-gonic/gin"
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

// GetOrderReq binds request data from multiple sources
type GetOrderReq struct {
	UserID  string `uri:"userId"`
	OrderID string `uri:"orderId"`
	Fields  string `form:"fields"`
	Token   string `header:"X-Api-Key"`
}

// OrderResponse is the response structure
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
	UserID      string           `json:"user_id"`
	Name        string           `json:"name"`
	Email       string           `json:"email"`
	Phone       string           `json:"phone"`
	Address     Address          `json:"address"`
	Preferences Preferences      `json:"preferences"`
	Payment     []PaymentMethod  `json:"payment_methods"`
	Tags        []string         `json:"tags"`
	Metadata    map[string]string `json:"metadata"`
	RequestID   string           `json:"request_id"`
}

type Address struct {
	Street  string `json:"street"`
	City    string `json:"city"`
	State   string `json:"state"`
	Zip     string `json:"zip"`
	Country string `json:"country"`
}

type Preferences struct {
	Language     string            `json:"language"`
	Currency     string            `json:"currency"`
	Timezone     string            `json:"timezone"`
	Notifications NotificationPrefs `json:"notifications"`
	Theme        string            `json:"theme"`
}

type NotificationPrefs struct {
	Email bool `json:"email"`
	SMS   bool `json:"sms"`
	Push  bool `json:"push"`
}

type PaymentMethod struct {
	Type       string `json:"type"`
	Last4      string `json:"last4"`
	ExpiryMonth int   `json:"expiry_month"`
	ExpiryYear  int   `json:"expiry_year"`
	IsDefault  bool   `json:"is_default"`
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

// responseWriter wraps gin.ResponseWriter to capture response body
type responseWriter struct {
	gin.ResponseWriter
	body *bytes.Buffer
}

func (w *responseWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (w *responseWriter) WriteString(s string) (int, error) {
	w.body.WriteString(s)
	return w.ResponseWriter.WriteString(s)
}

// generateUUID generates a UUID v4 without external dependencies
func generateUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return ""
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80

	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// Sensitive fields for PII redaction
var sensitiveHeaders = map[string]bool{
	"authorization": true, "cookie": true, "set-cookie": true,
	"x-api-key": true, "x-auth-token": true,
}

// redactPII redacts sensitive fields in headers
func redactPII(key string, value any) any {
	if sensitiveHeaders[strings.ToLower(key)] {
		if s, ok := value.(string); ok && len(s) > 0 {
			return "[REDACTED]"
		}
	}
	return value
}

// recoveryMiddleware catches panics and logs them
func recoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				stack := debug.Stack()
				slog.Error("panic recovered",
					"error", err,
					"stack_trace", string(stack),
					"path", c.Request.URL.Path,
					"method", c.Request.Method,
				)
				c.JSON(500, gin.H{
					"error": "Internal Server Error",
				})
			}
		}()
		c.Next()
	}
}

// requestIDMiddleware reads or generates request ID
func requestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = generateUUID()
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

// secureMiddleware sets security headers matching aarv secure.DefaultConfig()
func secureMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("X-XSS-Protection", "1; mode=block")
		c.Header("X-Content-Type-Options", "nosniff")
		c.Header("X-Frame-Options", "DENY")
		c.Header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		c.Header("Content-Security-Policy", "default-src 'self'")
		c.Header("Referrer-Policy", "strict-origin-when-cross-origin")
		c.Header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
		c.Header("Cross-Origin-Opener-Policy", "same-origin")
		c.Next()
	}
}

// corsMiddleware sets CORS headers matching aarv cors.DefaultConfig()
func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// bodyLimitMiddleware rejects requests with body larger than maxBytes
func bodyLimitMiddleware(maxBytes int64) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.ContentLength > maxBytes {
			c.AbortWithStatusJSON(http.StatusRequestEntityTooLarge, gin.H{"error": "request body too large"})
			return
		}
		c.Next()
	}
}

// structuredLoggerMiddleware logs a single http_dump line per request
func structuredLoggerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		requestID, _ := c.Get("request_id")
		reqID, _ := requestID.(string)

		// Capture request headers
		reqHeaders := make(map[string]any)
		for key, values := range c.Request.Header {
			if len(values) > 0 {
				reqHeaders[key] = redactPII(key, values[0])
			}
		}

		// Read request body before handler executes (matching aarv's verboselog)
		var reqBody string
		if c.Request.ContentLength > 0 {
			body, _ := io.ReadAll(io.LimitReader(c.Request.Body, 65536))
			c.Request.Body.Close()
			reqBody = string(body)
			c.Request.Body = io.NopCloser(bytes.NewReader(body))
		}

		// Wrap response writer to capture response body
		blw := &responseWriter{
			ResponseWriter: c.Writer,
			body:           bytes.NewBuffer(nil),
		}
		c.Writer = blw

		c.Next()

		latency := time.Since(start)
		latencyMs := float64(latency.Microseconds()) / 1000.0

		// Build query params map
		queryParams := make(map[string]string)
		for k, v := range c.Request.URL.Query() {
			if len(v) > 0 {
				queryParams[k] = v[0]
			}
		}

		// Capture response headers
		respHeaders := make(map[string]any)
		for key, values := range c.Writer.Header() {
			if len(values) > 0 {
				respHeaders[key] = redactPII(key, values[0])
			}
		}

		// Single http_dump log line
		slog.Info("http_dump",
			slog.String("request_id", reqID),
			slog.String("method", c.Request.Method),
			slog.String("path", c.Request.URL.Path),
			slog.Any("query", queryParams),
			slog.String("client_ip", c.ClientIP()),
			slog.String("user_agent", c.Request.UserAgent()),
			slog.Any("request_headers", reqHeaders),
			slog.String("request_body", reqBody),
			slog.Int("status", c.Writer.Status()),
			slog.String("latency", latency.String()),
			slog.Float64("latency_ms", latencyMs),
			slog.Any("response_headers", respHeaders),
			slog.String("response_body", blw.body.String()),
			slog.Int("bytes_out", c.Writer.Size()),
		)
	}
}

func main() {
	// Setup slog with JSON handler
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

	gin.SetMode(gin.ReleaseMode)
	router := gin.New()

	// Register custom middlewares
	router.Use(recoveryMiddleware())
	router.Use(requestIDMiddleware())
	router.Use(corsMiddleware())
	router.Use(secureMiddleware())
	router.Use(bodyLimitMiddleware(1 << 20))
	router.Use(structuredLoggerMiddleware())

	// Grouped routes: /users/:userId/orders
	orders := router.Group("/users/:userId/orders")
	{
		// POST /users/:userId/orders — create order
		orders.POST("", func(c *gin.Context) {
			var req CreateOrderReq
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
				return
			}

			userID := c.Param("userId")
			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

			if pgStore != nil {
				order, err := pgStore.CreateOrder(c.Request.Context(), userID, toPgItems(req.Items), req.Currency)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				c.JSON(http.StatusCreated, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					RequestID: reqID,
				})
				return
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

			c.JSON(http.StatusCreated, order)
		})

		// PUT /users/:userId/orders/:orderId — update order
		orders.PUT("/:orderId", func(c *gin.Context) {
			var body CreateOrderReq
			if err := c.ShouldBindJSON(&body); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
				return
			}

			userID := c.Param("userId")
			orderID := c.Param("orderId")
			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

			if pgStore != nil {
				order, found, err := pgStore.UpdateOrder(c.Request.Context(), userID, orderID, toPgItems(body.Items), body.Currency)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				if !found {
					c.JSON(http.StatusNotFound, gin.H{"error": "order not found", "order_id": orderID, "request_id": reqID})
					return
				}
				c.JSON(http.StatusOK, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					RequestID: reqID,
				})
				return
			}

			key := storeKey(userID, orderID)

			orderMu.Lock()
			order, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				c.JSON(http.StatusNotFound, gin.H{
					"error":      "order not found",
					"order_id":   orderID,
					"request_id": reqID,
				})
				return
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
			order.RequestID = reqID
			orderStore[key] = order
			orderMu.Unlock()

			c.JSON(http.StatusOK, order)
		})

		// DELETE /users/:userId/orders/:orderId — delete order
		orders.DELETE("/:orderId", func(c *gin.Context) {
			userID := c.Param("userId")
			orderID := c.Param("orderId")
			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

			if pgStore != nil {
				found, err := pgStore.DeleteOrder(c.Request.Context(), userID, orderID)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				if !found {
					c.JSON(http.StatusNotFound, gin.H{"error": "order not found", "order_id": orderID, "request_id": reqID})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "order deleted", "order_id": orderID, "request_id": reqID})
				return
			}

			key := storeKey(userID, orderID)

			orderMu.Lock()
			_, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				c.JSON(http.StatusNotFound, gin.H{
					"error":      "order not found",
					"order_id":   orderID,
					"request_id": reqID,
				})
				return
			}
			delete(orderStore, key)
			orderMu.Unlock()

			c.JSON(http.StatusOK, gin.H{
				"message":    "order deleted",
				"order_id":   orderID,
				"request_id": reqID,
			})
		})

		// GET /users/:userId/orders/:orderId — fetch order from memory
		orders.GET("/:orderId", func(c *gin.Context) {
			userID := c.Param("userId")
			orderID := c.Param("orderId")
			fields := c.DefaultQuery("fields", "*")

			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

			if pgStore != nil {
				order, found, err := pgStore.GetOrder(c.Request.Context(), userID, orderID)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				if !found {
					c.JSON(http.StatusNotFound, gin.H{"error": "order not found", "order_id": orderID, "request_id": reqID})
					return
				}
				c.JSON(http.StatusOK, OrderResponse{
					OrderID: order.OrderID, UserID: order.UserID, Status: order.Status,
					Items: fromPgItems(order.Items), Total: order.Total, Currency: order.Currency,
					Fields: fields, RequestID: reqID,
				})
				return
			}

			orderMu.RLock()
			order, ok := orderStore[storeKey(userID, orderID)]
			orderMu.RUnlock()

			if !ok {
				c.JSON(http.StatusNotFound, gin.H{
					"error":      "order not found",
					"order_id":   orderID,
					"request_id": reqID,
				})
				return
			}

			order.Fields = fields
			order.RequestID = reqID
			c.JSON(http.StatusOK, order)
		})

		// POST /users/:userId/orders/bulk — bulk create orders
		orders.POST("/bulk", func(c *gin.Context) {
			var req BulkCreateOrderReq
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
				return
			}

			userID := c.Param("userId")
			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

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
				orders, totalSum, err := pgStore.BulkCreateOrders(c.Request.Context(), userID, bulkItems)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				var results []OrderResponse
				for _, o := range orders {
					results = append(results, OrderResponse{
						OrderID: o.OrderID, UserID: o.UserID, Status: o.Status,
						Items: fromPgItems(o.Items), Total: o.Total, Currency: o.Currency,
						RequestID: reqID,
					})
				}
				c.JSON(http.StatusCreated, BulkOrderResponse{
					UserID: userID, Count: len(results), Orders: results,
					TotalSum: totalSum, RequestID: reqID,
				})
				return
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

			c.JSON(http.StatusCreated, BulkOrderResponse{
				UserID:    userID,
				Count:     len(results),
				Orders:    results,
				TotalSum:  totalSum,
				RequestID: reqID,
			})
		})

		// GET /users/:userId/orders — list all orders for user
		orders.GET("", func(c *gin.Context) {
			userID := c.Param("userId")
			requestID, _ := c.Get("request_id")
			reqID, _ := requestID.(string)

			if pgStore != nil {
				pgOrders, err := pgStore.ListOrders(c.Request.Context(), userID)
				if err != nil {
					c.JSON(500, gin.H{"error": "database error"})
					return
				}
				var results []OrderResponse
				for _, o := range pgOrders {
					results = append(results, OrderResponse{
						OrderID: o.OrderID, UserID: o.UserID, Status: o.Status,
						Items: fromPgItems(o.Items), Total: o.Total, Currency: o.Currency,
						RequestID: reqID,
					})
				}
				c.JSON(http.StatusOK, gin.H{
					"user_id": userID, "count": len(results),
					"orders": results, "request_id": reqID,
				})
				return
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

			c.JSON(http.StatusOK, gin.H{
				"user_id":    userID,
				"count":      len(results),
				"orders":     results,
				"request_id": reqID,
			})
		})
	}

	// Profile routes: /users/:userId/profile
	router.PUT("/users/:userId/profile", func(c *gin.Context) {
		userID := c.Param("userId")
		requestID, _ := c.Get("request_id")
		reqID, _ := requestID.(string)

		var profile UserProfile
		if err := c.ShouldBindJSON(&profile); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
			return
		}

		profile.UserID = userID
		profile.RequestID = reqID

		if pgStore != nil {
			if err := pgStore.UpsertProfile(c.Request.Context(), userID, toPgProfile(profile)); err != nil {
				c.JSON(500, gin.H{"error": "database error"})
				return
			}
			c.JSON(http.StatusOK, profile)
			return
		}

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		c.JSON(http.StatusOK, profile)
	})

	router.GET("/users/:userId/profile", func(c *gin.Context) {
		userID := c.Param("userId")
		requestID, _ := c.Get("request_id")
		reqID, _ := requestID.(string)

		if pgStore != nil {
			p, found, err := pgStore.GetProfile(c.Request.Context(), userID)
			if err != nil {
				c.JSON(500, gin.H{"error": "database error"})
				return
			}
			if !found {
				c.JSON(http.StatusNotFound, gin.H{"error": "profile not found"})
				return
			}
			result := fromPgProfile(p)
			result.RequestID = reqID
			c.JSON(http.StatusOK, result)
			return
		}

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "profile not found"})
			return
		}

		profile.RequestID = reqID
		c.JSON(http.StatusOK, profile)
	})

	// Start server on port 8082
	slog.Info("server starting", slog.String("port", "8082"))
	if err := router.Run(":8082"); err != nil {
		slog.Error("failed to start server", "error", err)
		os.Exit(1)
	}
}
