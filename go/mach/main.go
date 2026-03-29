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

	"github.com/mrshabel/mach"
	"github.com/mrshabel/mach/middleware"
)

// --- Request/Response types ---

type CreateOrderReq struct {
	Items    []OrderItem `json:"items"`
	Currency string      `json:"currency"`
}

type GetOrderReq struct {
	UserID  string
	OrderID string
	Fields  string
	Token   string
}

// OrderResponse is the static JSON response structure
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

// OrderItem represents a line item in an order
type OrderItem struct {
	ProductID string  `json:"product_id"`
	Name      string  `json:"name"`
	Quantity  int     `json:"quantity"`
	Price     float64 `json:"price"`
}

// --- In-memory store ---

var (
	orderStore   = make(map[string]OrderResponse)
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

// responseWriterWrapper captures response status, bytes written, and body
type responseWriterWrapper struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int
	body         *bytes.Buffer
}

func (w *responseWriterWrapper) WriteHeader(code int) {
	w.statusCode = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *responseWriterWrapper) Write(b []byte) (int, error) {
	w.body.Write(b)
	n, err := w.ResponseWriter.Write(b)
	w.bytesWritten += n
	return n, err
}

// Sensitive fields for PII redaction
var sensitiveFields = map[string]bool{
	"password": true, "token": true, "secret": true,
	"api_key": true, "ssn": true, "credit_card": true,
	"authorization": true, "cookie": true, "set-cookie": true,
	"x-api-key": true, "x-auth-token": true,
}

// headersToMap converts http.Header to map[string]any for logging
func headersToMap(headers http.Header) map[string]any {
	m := make(map[string]any)
	for k, v := range headers {
		if len(v) == 1 {
			m[k] = v[0]
		} else {
			m[k] = v
		}
	}
	return m
}

// redactPII redacts sensitive fields from headers
func redactPII(data map[string]any) map[string]any {
	result := make(map[string]any)
	for k, v := range data {
		if sensitiveFields[strings.ToLower(k)] {
			result[k] = "[REDACTED]"
		} else {
			result[k] = v
		}
	}
	return result
}

// secureMiddleware sets security headers matching aarv secure.DefaultConfig()
func secureMiddleware() mach.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("X-XSS-Protection", "1; mode=block")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			w.Header().Set("X-Frame-Options", "DENY")
			w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
			w.Header().Set("Content-Security-Policy", "default-src 'self'")
			w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
			w.Header().Set("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
			w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
			next.ServeHTTP(w, r)
		})
	}
}

// corsMiddleware sets CORS headers matching aarv cors.DefaultConfig()
func corsMiddleware() mach.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization")
			if r.Method == "OPTIONS" {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// bodyLimitMiddleware rejects requests with body larger than maxBytes
func bodyLimitMiddleware(maxBytes int64) mach.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.ContentLength > maxBytes {
				http.Error(w, `{"error":"request body too large"}`, http.StatusRequestEntityTooLarge)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// structuredLogger logs a single http_dump line per request
func structuredLogger() mach.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()

			// Wrap response writer to capture status, bytes written, and body
			wrapped := &responseWriterWrapper{
				ResponseWriter: w,
				statusCode:     http.StatusOK,
				bytesWritten:   0,
				body:           new(bytes.Buffer),
			}

			// Get request ID (set by RequestID middleware on response header before handler runs)
			requestID := w.Header().Get("X-Request-ID")

			// Get client IP
			clientIP := r.RemoteAddr
			if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
				clientIP = xff
			}
			// Strip port from client IP for consistency
			if idx := strings.LastIndex(clientIP, ":"); idx != -1 {
				clientIP = clientIP[:idx]
			}
			clientIP = strings.Trim(clientIP, "[]")

			// Call next handler
			next.ServeHTTP(wrapped, r)

			latency := time.Since(start)
			latencyMs := float64(latency.Microseconds()) / 1000.0

			// Build query params map
			queryParams := make(map[string]string)
			for k, v := range r.URL.Query() {
				if len(v) > 0 {
					queryParams[k] = v[0]
				}
			}

			// Single http_dump log line
			slog.Info("http_dump",
				slog.String("request_id", requestID),
				slog.String("method", r.Method),
				slog.String("path", r.URL.Path),
				slog.Any("query", queryParams),
				slog.String("client_ip", clientIP),
				slog.String("user_agent", r.UserAgent()),
				slog.Any("request_headers", redactPII(headersToMap(r.Header))),
				slog.Int("status", wrapped.statusCode),
				slog.String("latency", latency.String()),
				slog.Float64("latency_ms", latencyMs),
				slog.Any("response_headers", redactPII(headersToMap(wrapped.Header()))),
				slog.String("response_body", wrapped.body.String()),
				slog.Int("bytes_out", wrapped.bytesWritten),
			)
		})
	}
}

func main() {
	// Set up slog JSON handler
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	// Create Mach app
	app := mach.New()

	// Register middlewares — Recovery and RequestID are top-level mach functions
	app.Use(mach.Recovery())
	app.Use(middleware.RequestID())
	app.Use(corsMiddleware())
	app.Use(secureMiddleware())
	app.Use(bodyLimitMiddleware(1 << 20))
	app.Use(structuredLogger())

	// Grouped routes: /users/{userId}/orders
	orders := app.Group("/users/{userId}/orders")

	// POST /users/{userId}/orders — create order
	orders.POST("", func(c *mach.Context) {
		var req CreateOrderReq
		if err := c.DecodeJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
			return
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

		requestID := middleware.GetRequestID(c.Request.Context())

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

		c.JSON(http.StatusCreated, order)
	})

	// PUT /users/{userId}/orders/{orderId} — update order
	orders.PUT("/{orderId}", func(c *mach.Context) {
		var body CreateOrderReq
		if err := c.DecodeJSON(&body); err != nil {
			c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
			return
		}

		userID := c.Param("userId")
		orderID := c.Param("orderId")
		requestID := middleware.GetRequestID(c.Request.Context())

		key := storeKey(userID, orderID)

		orderMu.Lock()
		order, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
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
		order.RequestID = requestID
		orderStore[key] = order
		orderMu.Unlock()

		c.JSON(http.StatusOK, order)
	})

	// DELETE /users/{userId}/orders/{orderId} — delete order
	orders.DELETE("/{orderId}", func(c *mach.Context) {
		userID := c.Param("userId")
		orderID := c.Param("orderId")
		requestID := middleware.GetRequestID(c.Request.Context())

		key := storeKey(userID, orderID)

		orderMu.Lock()
		_, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
			return
		}
		delete(orderStore, key)
		orderMu.Unlock()

		c.JSON(http.StatusOK, map[string]string{
			"message":    "order deleted",
			"order_id":   orderID,
			"request_id": requestID,
		})
	})

	// GET /users/{userId}/orders/{orderId} — fetch order from memory
	orders.GET("/{orderId}", func(c *mach.Context) {
		userID := c.Param("userId")
		orderID := c.Param("orderId")
		fields := c.DefaultQuery("fields", "*")

		requestID := middleware.GetRequestID(c.Request.Context())

		orderMu.RLock()
		order, ok := orderStore[storeKey(userID, orderID)]
		orderMu.RUnlock()

		if !ok {
			c.JSON(http.StatusNotFound, map[string]string{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
			return
		}

		order.Fields = fields
		order.RequestID = requestID
		c.JSON(http.StatusOK, order)
	})

	slog.Info("server starting", slog.String("port", "8085"))
	if err := app.Run(":8085"); err != nil {
		fmt.Fprintf(os.Stderr, "Server error: %v\n", err)
		os.Exit(1)
	}
}
