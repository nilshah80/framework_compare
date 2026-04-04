package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/helmet"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/gofiber/fiber/v2/middleware/requestid"
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

// Sensitive fields for PII redaction
var sensitiveHeaderFields = []string{"password", "token", "secret", "api_key", "ssn", "credit_card", "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"}

// redactSensitiveFields redacts PII from header values
func redactSensitiveFields(key string, value string) string {
	lowerKey := strings.ToLower(key)
	for _, field := range sensitiveHeaderFields {
		if strings.Contains(lowerKey, field) {
			return "[REDACTED]"
		}
	}
	return value
}

// structuredLoggerMiddleware logs a single http_dump line per request
func structuredLoggerMiddleware(c *fiber.Ctx) error {
	start := time.Now()

	// Extract request ID from locals
	requestID, _ := c.Locals("requestid").(string)

	// Build request headers map with PII redaction
	reqHeaders := make(map[string]string)
	c.Request().Header.VisitAll(func(key, value []byte) {
		reqHeaders[string(key)] = redactSensitiveFields(string(key), string(value))
	})

	// Continue processing
	err := c.Next()

	// Build response headers map with PII redaction
	respHeaders := make(map[string]string)
	c.Response().Header.VisitAll(func(key, value []byte) {
		respHeaders[string(key)] = redactSensitiveFields(string(key), string(value))
	})

	latency := time.Since(start)
	latencyMs := float64(latency.Microseconds()) / 1000.0

	// Build query params map
	queryParams := make(map[string]string)
	c.Request().URI().QueryArgs().VisitAll(func(key, value []byte) {
		queryParams[string(key)] = string(value)
	})

	// Single http_dump log line
	slog.Info("http_dump",
		slog.String("request_id", requestID),
		slog.String("method", c.Method()),
		slog.String("path", c.Path()),
		slog.Any("query", queryParams),
		slog.String("client_ip", c.IP()),
		slog.String("user_agent", c.Get("User-Agent")),
		slog.Any("request_headers", reqHeaders),
		slog.Int("status", c.Response().StatusCode()),
		slog.String("latency", latency.String()),
		slog.Float64("latency_ms", latencyMs),
		slog.Any("response_headers", respHeaders),
		slog.String("response_body", string(c.Response().Body())),
		slog.Int("bytes_out", len(c.Response().Body())),
	)

	return err
}

func main() {
	// Set up slog JSON handler for structured logging
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	app := fiber.New(fiber.Config{
		DisableStartupMessage: true,
		BodyLimit:             1 << 20, // 1MB
	})

	// Register middlewares
	app.Use(recover.New())
	app.Use(requestid.New())
	app.Use(cors.New(cors.Config{
		AllowOrigins: "*",
		AllowMethods: "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS",
		AllowHeaders: "Origin, Content-Type, Accept, Authorization",
	}))
	app.Use(helmet.New(helmet.Config{
		XSSProtection:           "1; mode=block",
		ContentTypeNosniff:      "nosniff",
		XFrameOptions:           "DENY",
		HSTSMaxAge:              31536000,
		HSTSExcludeSubdomains:   false,
		ContentSecurityPolicy:   "default-src 'self'",
		ReferrerPolicy:          "strict-origin-when-cross-origin",
		PermissionPolicy:        "geolocation=(), microphone=(), camera=()",
		CrossOriginOpenerPolicy: "same-origin",
	}))
	// HSTS is only sent on HTTPS by helmet; add it manually for HTTP parity
	app.Use(func(c *fiber.Ctx) error {
		c.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		return c.Next()
	})
	app.Use(structuredLoggerMiddleware)

	// Grouped routes: /users/:userId/orders
	orders := app.Group("/users/:userId/orders")

	// POST /users/:userId/orders — create order
	orders.Post("/", func(c *fiber.Ctx) error {
		var req CreateOrderReq
		if err := c.BodyParser(&req); err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
		}

		userID := c.Params("userId")
		orderID := nextOrderID()

		var total float64
		for _, item := range req.Items {
			total += item.Price * float64(item.Quantity)
		}

		currency := req.Currency
		if currency == "" {
			currency = "USD"
		}

		requestID, _ := c.Locals("requestid").(string)

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

		return c.Status(http.StatusCreated).JSON(order)
	})

	// PUT /users/:userId/orders/:orderId — update order
	orders.Put("/:orderId", func(c *fiber.Ctx) error {
		var body CreateOrderReq
		if err := c.BodyParser(&body); err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
		}

		userID := c.Params("userId")
		orderID := c.Params("orderId")
		requestID, _ := c.Locals("requestid").(string)

		key := storeKey(userID, orderID)

		orderMu.Lock()
		order, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			return c.Status(http.StatusNotFound).JSON(fiber.Map{
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

		return c.JSON(order)
	})

	// DELETE /users/:userId/orders/:orderId — delete order
	orders.Delete("/:orderId", func(c *fiber.Ctx) error {
		userID := c.Params("userId")
		orderID := c.Params("orderId")
		requestID, _ := c.Locals("requestid").(string)

		key := storeKey(userID, orderID)

		orderMu.Lock()
		_, ok := orderStore[key]
		if !ok {
			orderMu.Unlock()
			return c.Status(http.StatusNotFound).JSON(fiber.Map{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
		}
		delete(orderStore, key)
		orderMu.Unlock()

		return c.JSON(fiber.Map{
			"message":    "order deleted",
			"order_id":   orderID,
			"request_id": requestID,
		})
	})

	// GET /users/:userId/orders/:orderId — fetch order from memory
	orders.Get("/:orderId", func(c *fiber.Ctx) error {
		userID := c.Params("userId")
		orderID := c.Params("orderId")
		fields := c.Query("fields", "*")

		requestID, _ := c.Locals("requestid").(string)

		orderMu.RLock()
		order, ok := orderStore[storeKey(userID, orderID)]
		orderMu.RUnlock()

		if !ok {
			return c.Status(http.StatusNotFound).JSON(fiber.Map{
				"error":      "order not found",
				"order_id":   orderID,
				"request_id": requestID,
			})
		}

		order.Fields = fields
		order.RequestID = requestID
		return c.JSON(order)
	})

	// POST /users/:userId/orders/bulk — bulk create orders
	orders.Post("/bulk", func(c *fiber.Ctx) error {
		var req BulkCreateOrderReq
		if err := c.BodyParser(&req); err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
		}

		userID := c.Params("userId")
		requestID, _ := c.Locals("requestid").(string)

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

		return c.Status(http.StatusCreated).JSON(BulkOrderResponse{
			UserID:    userID,
			Count:     len(results),
			Orders:    results,
			TotalSum:  totalSum,
			RequestID: requestID,
		})
	})

	// GET /users/:userId/orders — list all orders for user
	orders.Get("/", func(c *fiber.Ctx) error {
		userID := c.Params("userId")
		requestID, _ := c.Locals("requestid").(string)

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

		return c.JSON(fiber.Map{
			"user_id":    userID,
			"count":      len(results),
			"orders":     results,
			"request_id": requestID,
		})
	})

	// PUT /users/:userId/profile — create/update user profile
	app.Put("/users/:userId/profile", func(c *fiber.Ctx) error {
		userID := c.Params("userId")
		requestID, _ := c.Locals("requestid").(string)

		var profile UserProfile
		if err := c.BodyParser(&profile); err != nil {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
		}

		profile.UserID = userID
		profile.RequestID = requestID

		profileMu.Lock()
		profileStore[userID] = profile
		profileMu.Unlock()

		return c.JSON(profile)
	})

	// GET /users/:userId/profile — get user profile
	app.Get("/users/:userId/profile", func(c *fiber.Ctx) error {
		userID := c.Params("userId")
		requestID, _ := c.Locals("requestid").(string)

		profileMu.RLock()
		profile, ok := profileStore[userID]
		profileMu.RUnlock()

		if !ok {
			return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "profile not found"})
		}

		profile.RequestID = requestID
		return c.JSON(profile)
	})

	// Start server on port 8083
	slog.Info("server starting", slog.String("port", "8083"))
	if err := app.Listen(":8083"); err != nil {
		slog.Error("server error", slog.String("error", err.Error()))
	}
}
