package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
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

// --- Response writer wrapper to capture status, body, bytes ---

type responseWriter struct {
	http.ResponseWriter
	statusCode int
	body       *bytes.Buffer
	bytesOut   int
}

func newResponseWriter(w http.ResponseWriter) *responseWriter {
	return &responseWriter{
		ResponseWriter: w,
		statusCode:     http.StatusOK,
		body:           bytes.NewBuffer(nil),
	}
}

func (rw *responseWriter) WriteHeader(code int) {
	rw.statusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

func (rw *responseWriter) Write(b []byte) (int, error) {
	rw.body.Write(b)
	n, err := rw.ResponseWriter.Write(b)
	rw.bytesOut += n
	return n, err
}

// --- UUID v4 generation via crypto/rand ---

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

// --- PII redaction ---

var sensitiveHeaders = map[string]bool{
	"authorization": true, "cookie": true, "set-cookie": true,
	"x-api-key": true, "x-auth-token": true,
}

func redactPII(key string, value any) any {
	if sensitiveHeaders[strings.ToLower(key)] {
		if s, ok := value.(string); ok && len(s) > 0 {
			return "[REDACTED]"
		}
	}
	return value
}

// --- Context key for request ID ---

type ctxKey string

const requestIDKey ctxKey = "request_id"

// --- JSON helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, fields map[string]string) {
	writeJSON(w, status, fields)
}

// --- Middleware ---

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
				writeJSON(w, 500, map[string]string{"error": "Internal Server Error"})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

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

func securityMiddleware(next http.Handler) http.Handler {
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

func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		if ip := strings.TrimSpace(parts[0]); ip != "" {
			return ip
		}
	}
	return r.RemoteAddr
}

func structuredLoggerMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		reqID, _ := r.Context().Value(requestIDKey).(string)

		// Capture request headers
		reqHeaders := make(map[string]any)
		for key, values := range r.Header {
			if len(values) > 0 {
				reqHeaders[key] = redactPII(key, values[0])
			}
		}

		// Read request body before handler executes
		var reqBody string
		if r.ContentLength > 0 {
			body, _ := io.ReadAll(io.LimitReader(r.Body, 65536))
			r.Body.Close()
			reqBody = string(body)
			r.Body = io.NopCloser(bytes.NewReader(body))
		}

		// Wrap response writer to capture response body
		rw := newResponseWriter(w)

		next.ServeHTTP(rw, r)

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
		for key, values := range rw.Header() {
			if len(values) > 0 {
				respHeaders[key] = redactPII(key, values[0])
			}
		}

		// Single http_dump log line
		slog.Info("http_dump",
			slog.String("request_id", reqID),
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.Any("query", queryParams),
			slog.String("client_ip", clientIP(r)),
			slog.String("user_agent", r.UserAgent()),
			slog.Any("request_headers", reqHeaders),
			slog.String("request_body", reqBody),
			slog.Int("status", rw.statusCode),
			slog.String("latency", latency.String()),
			slog.Float64("latency_ms", latencyMs),
			slog.Any("response_headers", respHeaders),
			slog.String("response_body", rw.body.String()),
			slog.Int("bytes_out", rw.bytesOut),
		)
	})
}

// --- Handlers ---

func createOrder(w http.ResponseWriter, r *http.Request) {
	var req CreateOrderReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		return
	}

	userID := r.PathValue("userId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		order, err := pgStore.CreateOrder(r.Context(), userID, toPgItems(req.Items), req.Currency)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		writeJSON(w, http.StatusCreated, OrderResponse{
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

	writeJSON(w, http.StatusCreated, order)
}

func updateOrder(w http.ResponseWriter, r *http.Request) {
	var body CreateOrderReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		return
	}

	userID := r.PathValue("userId")
	orderID := r.PathValue("orderId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		order, found, err := pgStore.UpdateOrder(r.Context(), userID, orderID, toPgItems(body.Items), body.Currency)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		if !found {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "order not found", "order_id": orderID, "request_id": reqID})
			return
		}
		writeJSON(w, http.StatusOK, OrderResponse{
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
}

func deleteOrder(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	orderID := r.PathValue("orderId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		found, err := pgStore.DeleteOrder(r.Context(), userID, orderID)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		if !found {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "order not found", "order_id": orderID, "request_id": reqID})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"message": "order deleted", "order_id": orderID, "request_id": reqID})
		return
	}

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
}

func getOrder(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	orderID := r.PathValue("orderId")
	fields := r.URL.Query().Get("fields")
	if fields == "" {
		fields = "*"
	}

	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		order, found, err := pgStore.GetOrder(r.Context(), userID, orderID)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		if !found {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "order not found", "order_id": orderID, "request_id": reqID})
			return
		}
		writeJSON(w, http.StatusOK, OrderResponse{
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
}

func bulkCreateOrders(w http.ResponseWriter, r *http.Request) {
	var req BulkCreateOrderReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		return
	}

	userID := r.PathValue("userId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

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
		orders, totalSum, err := pgStore.BulkCreateOrders(r.Context(), userID, bulkItems)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
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
		writeJSON(w, http.StatusCreated, BulkOrderResponse{
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

	writeJSON(w, http.StatusCreated, BulkOrderResponse{
		UserID:    userID,
		Count:     len(results),
		Orders:    results,
		TotalSum:  totalSum,
		RequestID: reqID,
	})
}

func listOrders(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		pgOrders, err := pgStore.ListOrders(r.Context(), userID)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
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
		writeJSON(w, http.StatusOK, map[string]any{
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

	writeJSON(w, http.StatusOK, map[string]any{
		"user_id":    userID,
		"count":      len(results),
		"orders":     results,
		"request_id": reqID,
	})
}

func updateProfile(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	var profile UserProfile
	if err := json.NewDecoder(r.Body).Decode(&profile); err != nil {
		writeError(w, http.StatusBadRequest, map[string]string{"error": "invalid request body"})
		return
	}

	profile.UserID = userID
	profile.RequestID = reqID

	if pgStore != nil {
		if err := pgStore.UpsertProfile(r.Context(), userID, toPgProfile(profile)); err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		writeJSON(w, http.StatusOK, profile)
		return
	}

	profileMu.Lock()
	profileStore[userID] = profile
	profileMu.Unlock()

	writeJSON(w, http.StatusOK, profile)
}

func getProfile(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	reqID, _ := r.Context().Value(requestIDKey).(string)

	if pgStore != nil {
		p, found, err := pgStore.GetProfile(r.Context(), userID)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "database error"})
			return
		}
		if !found {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "profile not found"})
			return
		}
		result := fromPgProfile(p)
		result.RequestID = reqID
		writeJSON(w, http.StatusOK, result)
		return
	}

	profileMu.RLock()
	profile, ok := profileStore[userID]
	profileMu.RUnlock()

	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "profile not found"})
		return
	}

	profile.RequestID = reqID
	writeJSON(w, http.StatusOK, profile)
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

	mux := http.NewServeMux()

	// Order routes
	mux.HandleFunc("POST /users/{userId}/orders/bulk", bulkCreateOrders)
	mux.HandleFunc("POST /users/{userId}/orders", createOrder)
	mux.HandleFunc("PUT /users/{userId}/orders/{orderId}", updateOrder)
	mux.HandleFunc("DELETE /users/{userId}/orders/{orderId}", deleteOrder)
	mux.HandleFunc("GET /users/{userId}/orders/{orderId}", getOrder)
	mux.HandleFunc("GET /users/{userId}/orders", listOrders)

	// Profile routes
	mux.HandleFunc("PUT /users/{userId}/profile", updateProfile)
	mux.HandleFunc("GET /users/{userId}/profile", getProfile)

	// Apply middleware chain (outermost first in execution order)
	var handler http.Handler = mux
	handler = structuredLoggerMiddleware(handler)
	handler = bodyLimitMiddleware(1 << 20)(handler)
	handler = securityMiddleware(handler)
	handler = corsMiddleware(handler)
	handler = requestIDMiddleware(handler)
	handler = recoveryMiddleware(handler)

	slog.Info("server starting", slog.String("port", "8088"))
	if err := http.ListenAndServe(":8088", handler); err != nil {
		slog.Error("failed to start server", "error", err)
		os.Exit(1)
	}
}
