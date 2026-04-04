package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
)

// --- Request/Response types ---

type CreateOrderReq struct {
	Items    []OrderItem `json:"items"`
	Currency string      `json:"currency"`
}

// GetOrderReq binds request data from multiple sources
type GetOrderReq struct {
	UserID  string `json:"userId"`
	OrderID string `json:"orderId"`
	Fields  string `json:"fields"`
	Token   string `json:"token"`
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

// context key type for request_id
type ctxKey string

const requestIDKey ctxKey = "request_id"

// responseWriter wraps http.ResponseWriter to capture status and body
type responseWriter struct {
	http.ResponseWriter
	body       *bytes.Buffer
	statusCode int
	written    int
}

func (w *responseWriter) WriteHeader(code int) {
	w.statusCode = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *responseWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	n, err := w.ResponseWriter.Write(b)
	w.written += n
	return n, err
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

// writeJSON writes a JSON response with the given status code
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// getRequestID retrieves request_id from context
func getRequestID(r *http.Request) string {
	if v := r.Context().Value(requestIDKey); v != nil {
		return v.(string)
	}
	return ""
}

// recoveryMiddleware catches panics and logs them
func recoveryMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				stack := debug.Stack()
				slog.Error("panic recovered",
					"error", err,
					"stack_trace", string(stack),
					"path", r.URL.Path,
					"method", r.Method,
				)
				writeJSON(w, 500, map[string]string{
					"error": "Internal Server Error",
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// requestIDMiddleware reads or generates request ID
func requestIDMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestID := r.Header.Get("X-Request-ID")
		if requestID == "" {
			requestID = generateUUID()
		}
		ctx := context.WithValue(r.Context(), requestIDKey, requestID)
		w.Header().Set("X-Request-ID", requestID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// corsMiddleware sets CORS headers matching aarv cors.DefaultConfig()
func corsMiddleware(next http.Handler) http.Handler {
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

// secureMiddleware sets security headers matching aarv secure.DefaultConfig()
func secureMiddleware(next http.Handler) http.Handler {
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

// bodyLimitMiddleware rejects requests with body larger than maxBytes
func bodyLimitMiddleware(maxBytes int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.ContentLength > maxBytes {
				writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "request body too large"})
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// structuredLoggerMiddleware logs a single http_dump line per request
func structuredLoggerMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		reqID := getRequestID(r)

		// Capture request headers
		reqHeaders := make(map[string]any)
		for key, values := range r.Header {
			if len(values) > 0 {
				reqHeaders[key] = redactPII(key, values[0])
			}
		}

		// Wrap response writer to capture response body
		blw := &responseWriter{
			ResponseWriter: w,
			body:           bytes.NewBuffer(nil),
			statusCode:     200,
		}

		next.ServeHTTP(blw, r)

		latency := time.Since(start)
		latencyMs := float64(latency.Microseconds()) / 1000.0

		// Build query params map
		queryParams := make(map[string]string)
		for k, v := range r.URL.Query() {
			if len(v) > 0 {
				queryParams[k] = v[0]
			}
		}

		// Capture response headers
		respHeaders := make(map[string]any)
		for key, values := range blw.Header() {
			if len(values) > 0 {
				respHeaders[key] = redactPII(key, values[0])
			}
		}

		// Determine client IP
		clientIP := r.RemoteAddr
		if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
			clientIP = strings.Split(forwarded, ",")[0]
		}

		// Single http_dump log line
		slog.Info("http_dump",
			slog.String("request_id", reqID),
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.Any("query", queryParams),
			slog.String("client_ip", clientIP),
			slog.String("user_agent", r.UserAgent()),
			slog.Any("request_headers", reqHeaders),
			slog.Int("status", blw.statusCode),
			slog.String("latency", latency.String()),
			slog.Float64("latency_ms", latencyMs),
			slog.Any("response_headers", respHeaders),
			slog.String("response_body", blw.body.String()),
			slog.Int("bytes_out", blw.written),
		)
	})
}

func main() {
	// Setup slog with JSON handler
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	r := chi.NewRouter()

	// Register custom middlewares
	r.Use(recoveryMiddleware)
	r.Use(requestIDMiddleware)
	r.Use(corsMiddleware)
	r.Use(secureMiddleware)
	r.Use(bodyLimitMiddleware(1 << 20))
	r.Use(structuredLoggerMiddleware)

	// Grouped routes: /users/{userId}/orders
	r.Route("/users/{userId}/orders", func(r chi.Router) {
		// POST /users/{userId}/orders — create order
		r.Post("/", func(w http.ResponseWriter, r *http.Request) {
			var req CreateOrderReq
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
				return
			}

			userID := chi.URLParam(r, "userId")
			orderID := nextOrderID()

			var total float64
			for _, item := range req.Items {
				total += item.Price * float64(item.Quantity)
			}

			currency := req.Currency
			if currency == "" {
				currency = "USD"
			}

			reqID := getRequestID(r)

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

			writeJSON(w, http.StatusCreated, order)
		})

		// PUT /users/{userId}/orders/{orderId} — update order
		r.Put("/{orderId}", func(w http.ResponseWriter, r *http.Request) {
			var body CreateOrderReq
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
				return
			}

			userID := chi.URLParam(r, "userId")
			orderID := chi.URLParam(r, "orderId")
			reqID := getRequestID(r)

			key := storeKey(userID, orderID)

			orderMu.Lock()
			order, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				writeJSON(w, http.StatusNotFound, map[string]string{
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

			writeJSON(w, http.StatusOK, order)
		})

		// DELETE /users/{userId}/orders/{orderId} — delete order
		r.Delete("/{orderId}", func(w http.ResponseWriter, r *http.Request) {
			userID := chi.URLParam(r, "userId")
			orderID := chi.URLParam(r, "orderId")
			reqID := getRequestID(r)

			key := storeKey(userID, orderID)

			orderMu.Lock()
			_, ok := orderStore[key]
			if !ok {
				orderMu.Unlock()
				writeJSON(w, http.StatusNotFound, map[string]string{
					"error":      "order not found",
					"order_id":   orderID,
					"request_id": reqID,
				})
				return
			}
			delete(orderStore, key)
			orderMu.Unlock()

			writeJSON(w, http.StatusOK, map[string]string{
				"message":    "order deleted",
				"order_id":   orderID,
				"request_id": reqID,
			})
		})

		// GET /users/{userId}/orders/{orderId} — fetch order from memory
		r.Get("/{orderId}", func(w http.ResponseWriter, r *http.Request) {
			userID := chi.URLParam(r, "userId")
			orderID := chi.URLParam(r, "orderId")
			fields := r.URL.Query().Get("fields")
			if fields == "" {
				fields = "*"
			}

			reqID := getRequestID(r)

			orderMu.RLock()
			order, ok := orderStore[storeKey(userID, orderID)]
			orderMu.RUnlock()

			if !ok {
				writeJSON(w, http.StatusNotFound, map[string]string{
					"error":      "order not found",
					"order_id":   orderID,
					"request_id": reqID,
				})
				return
			}

			order.Fields = fields
			order.RequestID = reqID
			writeJSON(w, http.StatusOK, order)
		})

		// POST /users/{userId}/orders/bulk — bulk create orders
		r.Post("/bulk", func(w http.ResponseWriter, r *http.Request) {
			var req BulkCreateOrderReq
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
				return
			}

			userID := chi.URLParam(r, "userId")
			reqID := getRequestID(r)

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

			writeJSON(w, http.StatusCreated, BulkOrderResponse{
				UserID:    userID,
				Count:     len(results),
				Orders:    results,
				TotalSum:  totalSum,
				RequestID: reqID,
			})
		})

		// GET /users/{userId}/orders — list all orders for user
		r.Get("/", func(w http.ResponseWriter, r *http.Request) {
			userID := chi.URLParam(r, "userId")
			reqID := getRequestID(r)

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

			writeJSON(w, http.StatusOK, map[string]any{
				"user_id":    userID,
				"count":      len(results),
				"orders":     results,
				"request_id": reqID,
			})
		})
	})

	// Profile routes: /users/{userId}/profile
	r.Put("/users/{userId}/profile", func(w http.ResponseWriter, r *http.Request) {
		userID := chi.URLParam(r, "userId")
		reqID := getRequestID(r)

		var profile UserProfile
		if err := json.NewDecoder(r.Body).Decode(&profile); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
			return
		}

		profile.UserID = userID
		profile.RequestID = reqID

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		writeJSON(w, http.StatusOK, profile)
	})

	r.Get("/users/{userId}/profile", func(w http.ResponseWriter, r *http.Request) {
		userID := chi.URLParam(r, "userId")
		reqID := getRequestID(r)

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "profile not found"})
			return
		}

		profile.RequestID = reqID
		writeJSON(w, http.StatusOK, profile)
	})

	// Start server on port 8087
	slog.Info("server starting", slog.String("port", "8087"))
	if err := http.ListenAndServe(":8087", r); err != nil {
		slog.Error("failed to start server", "error", err)
		os.Exit(1)
	}
}
