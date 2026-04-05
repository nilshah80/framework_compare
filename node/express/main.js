process.env.NODE_ENV = "production";

const express = require("express");
const crypto = require("crypto");
const PgStore = require("../pgstore");

const app = express();
const PORT = 8091;

let store;

// ── Structured JSON logger setup ─────────────────────────────────────
function log(level, msg, fields) {
  const entry = { time: new Date().toISOString(), level, msg, ...fields };
  process.stdout.write(JSON.stringify(entry) + "\n");
}

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

  // Derive client_ip from X-Forwarded-For (match Aarv's RealIP behavior)
  const forwarded = req.headers["x-forwarded-for"];
  const clientIp = forwarded
    ? forwarded.split(",")[0].trim()
    : req.socket.remoteAddress || "";

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
      client_ip: clientIp,
      user_agent: req.headers["user-agent"] || "",
      request_headers: redactHeaders(req.headers),
      request_body: req.body ? redactBody(req.body) : undefined,
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
app.post("/users/:userId/orders", async (req, res) => {
  try {
    const { userId } = req.params;
    const { items, currency } = req.body;

    const order = await store.createOrder(userId, items, currency);
    order.request_id = req.requestId;
    res.status(201).json(order);
  } catch (err) {
    log("ERROR", "create_order_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// PUT /users/:userId/orders/:orderId — Update Order
app.put("/users/:userId/orders/:orderId", async (req, res) => {
  try {
    const { userId, orderId } = req.params;
    const { items, currency } = req.body;

    const order = await store.updateOrder(userId, orderId, items, currency);
    if (!order) {
      return res.status(404).json({ error: "order not found" });
    }

    order.request_id = req.requestId;
    res.json(order);
  } catch (err) {
    log("ERROR", "update_order_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// DELETE /users/:userId/orders/:orderId — Delete Order
app.delete("/users/:userId/orders/:orderId", async (req, res) => {
  try {
    const { userId, orderId } = req.params;
    const deleted = await store.deleteOrder(userId, orderId);

    if (!deleted) {
      return res.status(404).json({ error: "order not found" });
    }

    res.json({
      message: "order deleted",
      order_id: orderId,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "delete_order_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/orders/:orderId — Fetch Order
app.get("/users/:userId/orders/:orderId", async (req, res) => {
  try {
    const { userId, orderId } = req.params;
    const order = await store.getOrder(userId, orderId);

    if (!order) {
      return res.status(404).json({ error: "order not found" });
    }

    order.fields = req.query.fields || "*";
    order.request_id = req.requestId;
    res.json(order);
  } catch (err) {
    log("ERROR", "get_order_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// POST /users/:userId/orders/bulk — Bulk Create Orders
app.post("/users/:userId/orders/bulk", async (req, res) => {
  try {
    const { userId } = req.params;
    const { orders } = req.body || {};

    const result = await store.bulkCreateOrders(userId, orders);

    for (const o of result.orders) {
      o.request_id = req.requestId;
    }

    res.status(201).json({
      user_id: userId,
      count: result.orders.length,
      orders: result.orders,
      total_sum: result.totalSum,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "bulk_create_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/orders — List All Orders
app.get("/users/:userId/orders", async (req, res) => {
  try {
    const { userId } = req.params;
    const orders = await store.listOrders(userId);

    for (const o of orders) {
      o.request_id = req.requestId;
    }

    res.json({
      user_id: userId,
      count: orders.length,
      orders,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "list_orders_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// PUT /users/:userId/profile — Create/Update Profile
app.put("/users/:userId/profile", async (req, res) => {
  try {
    const { userId } = req.params;
    await store.upsertProfile(userId, req.body);

    const profile = { ...req.body, user_id: userId, request_id: req.requestId };
    res.json(profile);
  } catch (err) {
    log("ERROR", "upsert_profile_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/profile — Get Profile
app.get("/users/:userId/profile", async (req, res) => {
  try {
    const { userId } = req.params;
    const profile = await store.getProfile(userId);

    if (!profile) {
      return res.status(404).json({ error: "profile not found" });
    }

    profile.request_id = req.requestId;
    res.json(profile);
  } catch (err) {
    log("ERROR", "get_profile_failed", { error: err.message });
    res.status(500).json({ error: "Internal Server Error" });
  }
});

// ── Start server ─────────────────────────────────────────────────────
async function main() {
  const dsn = process.env.PG_DSN;
  if (!dsn) {
    console.error("PG_DSN environment variable is required");
    process.exit(1);
  }

  store = new PgStore(dsn);
  await store.initSchema();
  log("INFO", "using PostgreSQL store", { dsn: dsn.replace(/\/\/.*@/, "//***@") });

  app.listen(PORT, () => {
    log("INFO", "server starting", { port: String(PORT) });
  });
}

main().catch((err) => {
  log("ERROR", "startup failed", { error: err.message });
  process.exit(1);
});
