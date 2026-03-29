process.env.NODE_ENV = "production";

const express = require("express");
const crypto = require("crypto");

const app = express();
const PORT = 8091;

// ── In-memory store ──────────────────────────────────────────────────
const orderStore = new Map();
let orderCounter = 0;

function nextOrderID() {
  return String(++orderCounter);
}

function storeKey(userId, orderId) {
  return `${userId}:${orderId}`;
}

// ── Structured JSON logger setup ─────────────────────────────────────
function log(level, msg, fields) {
  const entry = { time: new Date().toISOString(), level, msg, ...fields };
  process.stdout.write(JSON.stringify(entry) + "\n");
}

// ── Middleware: Recovery ──────────────────────────────────────────────
app.use((req, res, next) => {
  try {
    next();
  } catch (err) {
    log("ERROR", "panic_recovered", {
      error: err.message,
      stack: err.stack,
    });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// ── Middleware: Request ID ────────────────────────────────────────────
app.use((req, res, next) => {
  const requestId = req.headers["x-request-id"] || crypto.randomUUID();
  req.requestId = requestId;
  res.setHeader("X-Request-ID", requestId);
  next();
});

// ── Middleware: CORS ─────────────────────────────────────────────────
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader(
    "Access-Control-Allow-Methods",
    "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"
  );
  res.setHeader(
    "Access-Control-Allow-Headers",
    "Origin, Content-Type, Accept, Authorization"
  );
  if (req.method === "OPTIONS") {
    return res.status(204).end();
  }
  next();
});

// ── Middleware: Security Headers ──────────────────────────────────────
app.use((req, res, next) => {
  res.setHeader("X-XSS-Protection", "1; mode=block");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader(
    "Strict-Transport-Security",
    "max-age=31536000; includeSubDomains"
  );
  res.setHeader("Content-Security-Policy", "default-src 'self'");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader(
    "Permissions-Policy",
    "geolocation=(), microphone=(), camera=()"
  );
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  next();
});

// ── Middleware: Body Limit (1 MB) ────────────────────────────────────
app.use((req, res, next) => {
  const contentLength = parseInt(req.headers["content-length"] || "0", 10);
  if (contentLength > 1 << 20) {
    return res.status(413).json({ error: "Payload Too Large" });
  }
  next();
});

// ── JSON body parser ─────────────────────────────────────────────────
app.use(express.json({ limit: "1mb" }));

// ── PII redaction helpers ────────────────────────────────────────────
const REDACTED_HEADERS = new Set([
  "authorization",
  "cookie",
  "set-cookie",
  "x-api-key",
  "x-auth-token",
]);

const REDACTED_BODY_FIELDS = new Set([
  "password",
  "token",
  "secret",
  "api_key",
  "ssn",
  "credit_card",
]);

function redactHeaders(headers) {
  const out = {};
  for (const [k, v] of Object.entries(headers)) {
    out[k] = REDACTED_HEADERS.has(k.toLowerCase()) ? "[REDACTED]" : v;
  }
  return out;
}

function redactBody(body) {
  if (typeof body !== "object" || body === null) return body;
  const out = {};
  for (const [k, v] of Object.entries(body)) {
    out[k] = REDACTED_BODY_FIELDS.has(k) ? "[REDACTED]" : v;
  }
  return out;
}

// ── Middleware: Structured Logger ─────────────────────────────────────
app.use((req, res, next) => {
  const start = process.hrtime.bigint();

  // Capture response body
  const originalJson = res.json.bind(res);
  let responseBody = "";
  res.json = (body) => {
    responseBody = JSON.stringify(body);
    return originalJson(body);
  };

  res.on("finish", () => {
    const elapsed = Number(process.hrtime.bigint() - start) / 1e6;
    const query = {};
    for (const [k, v] of Object.entries(req.query)) {
      query[k] = v;
    }

    log("INFO", "http_dump", {
      request_id: req.requestId,
      method: req.method,
      path: req.path,
      query,
      client_ip: req.ip,
      user_agent: req.headers["user-agent"] || "",
      request_headers: redactHeaders(req.headers),
      status: res.statusCode,
      latency: `${elapsed.toFixed(3)}ms`,
      latency_ms: parseFloat(elapsed.toFixed(3)),
      response_headers: redactHeaders(res.getHeaders()),
      response_body: responseBody,
      bytes_out: responseBody.length,
    });
  });

  next();
});

// ── Routes ───────────────────────────────────────────────────────────

// POST /users/:userId/orders — Create Order
app.post("/users/:userId/orders", (req, res) => {
  const { userId } = req.params;
  const { items, currency } = req.body;

  let total = 0;
  for (const item of items || []) {
    total += item.price * item.quantity;
  }

  const orderId = nextOrderID();
  const order = {
    order_id: orderId,
    user_id: userId,
    status: "created",
    items: items || [],
    total,
    currency: currency || "USD",
    fields: "",
    request_id: req.requestId,
  };

  orderStore.set(storeKey(userId, orderId), order);
  res.status(201).json(order);
});

// PUT /users/:userId/orders/:orderId — Update Order
app.put("/users/:userId/orders/:orderId", (req, res) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return res.status(404).json({ error: "order not found" });
  }

  const { items, currency } = req.body;
  let total = 0;
  for (const item of items || []) {
    total += item.price * item.quantity;
  }

  const order = {
    order_id: orderId,
    user_id: userId,
    status: "updated",
    items: items || [],
    total,
    currency: currency || "USD",
    fields: "",
    request_id: req.requestId,
  };

  orderStore.set(key, order);
  res.json(order);
});

// DELETE /users/:userId/orders/:orderId — Delete Order
app.delete("/users/:userId/orders/:orderId", (req, res) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return res.status(404).json({ error: "order not found" });
  }

  orderStore.delete(key);
  res.json({
    message: "order deleted",
    order_id: orderId,
    request_id: req.requestId,
  });
});

// GET /users/:userId/orders/:orderId — Fetch Order
app.get("/users/:userId/orders/:orderId", (req, res) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return res.status(404).json({ error: "order not found" });
  }

  const fields = req.query.fields || "*";
  const token = req.headers["x-api-key"] || "";

  const order = { ...orderStore.get(key) };
  order.fields = fields;
  order.request_id = req.requestId;

  res.json(order);
});

// ── Start server ─────────────────────────────────────────────────────
app.listen(PORT, () => {
  log("INFO", "server starting", { port: String(PORT) });
});
