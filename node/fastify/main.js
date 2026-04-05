process.env.NODE_ENV = "production";

const Fastify = require("fastify");
const crypto = require("crypto");
const PgStore = require("../pgstore");

const app = Fastify({ bodyLimit: 1 << 20, logger: false });
const PORT = 8092;

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

// ── Plugin: CORS ─────────────────────────────────────────────────────
app.register(require("@fastify/cors"), {
  origin: "*",
  methods: ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"],
  allowedHeaders: ["Origin", "Content-Type", "Accept", "Authorization"],
});

// ── Hook: Request ID ─────────────────────────────────────────────────
app.addHook("onRequest", (req, reply, done) => {
  req.requestId = req.headers["x-request-id"] || crypto.randomUUID();
  reply.header("X-Request-ID", req.requestId);
  done();
});

// ── Hook: Security Headers ───────────────────────────────────────────
app.addHook("onRequest", (req, reply, done) => {
  reply.header("X-XSS-Protection", "1; mode=block");
  reply.header("X-Content-Type-Options", "nosniff");
  reply.header("X-Frame-Options", "DENY");
  reply.header(
    "Strict-Transport-Security",
    "max-age=31536000; includeSubDomains"
  );
  reply.header("Content-Security-Policy", "default-src 'self'");
  reply.header("Referrer-Policy", "strict-origin-when-cross-origin");
  reply.header(
    "Permissions-Policy",
    "geolocation=(), microphone=(), camera=()"
  );
  reply.header("Cross-Origin-Opener-Policy", "same-origin");
  done();
});

// ── Hook: Structured Logger ──────────────────────────────────────────
app.addHook("onRequest", (req, reply, done) => {
  req.startTime = process.hrtime.bigint();
  done();
});

app.addHook("onSend", (req, reply, payload, done) => {
  const elapsed = Number(process.hrtime.bigint() - req.startTime) / 1e6;

  const url = new URL(req.url, `http://localhost:${PORT}`);
  const query = {};
  for (const [k, v] of url.searchParams.entries()) {
    query[k] = v;
  }

  const responseHeaders = {};
  const rawHeaders = reply.getHeaders();
  for (const [k, v] of Object.entries(rawHeaders)) {
    responseHeaders[k] = v;
  }

  // Derive client_ip from X-Forwarded-For (match Aarv's RealIP behavior)
  const forwarded = req.headers["x-forwarded-for"];
  const clientIp = forwarded
    ? forwarded.split(",")[0].trim()
    : req.ip || "";

  log("INFO", "http_dump", {
    request_id: req.requestId,
    method: req.method,
    path: url.pathname,
    query,
    client_ip: clientIp,
    user_agent: req.headers["user-agent"] || "",
    request_headers: redactHeaders(req.headers),
    request_body: req.body ? redactBody(req.body) : undefined,
    status: reply.statusCode,
    latency: `${elapsed.toFixed(3)}ms`,
    latency_ms: parseFloat(elapsed.toFixed(3)),
    response_headers: redactHeaders(responseHeaders),
    response_body: typeof payload === "string" ? payload : "",
    bytes_out: typeof payload === "string" ? Buffer.byteLength(payload) : 0,
  });

  done();
});

// ── Error handler (Recovery) ─────────────────────────────────────────
app.setErrorHandler((error, req, reply) => {
  log("ERROR", "panic_recovered", {
    error: error.message,
    stack: error.stack,
  });
  reply.status(500).send({ error: "Internal Server Error" });
});

// ── Routes ───────────────────────────────────────────────────────────

// POST /users/:userId/orders — Create Order
app.post("/users/:userId/orders", async (req, reply) => {
  try {
    const { userId } = req.params;
    const { items, currency } = req.body || {};

    const order = await store.createOrder(userId, items, currency);
    order.request_id = req.requestId;
    reply.status(201).send(order);
  } catch (err) {
    log("ERROR", "create_order_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// PUT /users/:userId/orders/:orderId — Update Order
app.put("/users/:userId/orders/:orderId", async (req, reply) => {
  try {
    const { userId, orderId } = req.params;
    const { items, currency } = req.body || {};

    const order = await store.updateOrder(userId, orderId, items, currency);
    if (!order) {
      return reply.status(404).send({ error: "order not found" });
    }

    order.request_id = req.requestId;
    reply.send(order);
  } catch (err) {
    log("ERROR", "update_order_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// DELETE /users/:userId/orders/:orderId — Delete Order
app.delete("/users/:userId/orders/:orderId", async (req, reply) => {
  try {
    const { userId, orderId } = req.params;
    const deleted = await store.deleteOrder(userId, orderId);

    if (!deleted) {
      return reply.status(404).send({ error: "order not found" });
    }

    reply.send({
      message: "order deleted",
      order_id: orderId,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "delete_order_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/orders/:orderId — Fetch Order
app.get("/users/:userId/orders/:orderId", async (req, reply) => {
  try {
    const { userId, orderId } = req.params;
    const order = await store.getOrder(userId, orderId);

    if (!order) {
      return reply.status(404).send({ error: "order not found" });
    }

    order.fields = req.query.fields || "*";
    order.request_id = req.requestId;
    reply.send(order);
  } catch (err) {
    log("ERROR", "get_order_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// POST /users/:userId/orders/bulk — Bulk Create Orders
app.post("/users/:userId/orders/bulk", async (req, reply) => {
  try {
    const { userId } = req.params;
    const { orders } = req.body || {};

    const result = await store.bulkCreateOrders(userId, orders);

    for (const o of result.orders) {
      o.request_id = req.requestId;
    }

    reply.status(201).send({
      user_id: userId,
      count: result.orders.length,
      orders: result.orders,
      total_sum: result.totalSum,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "bulk_create_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/orders — List All Orders
app.get("/users/:userId/orders", async (req, reply) => {
  try {
    const { userId } = req.params;
    const orders = await store.listOrders(userId);

    for (const o of orders) {
      o.request_id = req.requestId;
    }

    reply.send({
      user_id: userId,
      count: orders.length,
      orders,
      request_id: req.requestId,
    });
  } catch (err) {
    log("ERROR", "list_orders_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// PUT /users/:userId/profile — Create/Update Profile
app.put("/users/:userId/profile", async (req, reply) => {
  try {
    const { userId } = req.params;
    await store.upsertProfile(userId, req.body);

    const profile = { ...req.body, user_id: userId, request_id: req.requestId };
    reply.send(profile);
  } catch (err) {
    log("ERROR", "upsert_profile_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
  }
});

// GET /users/:userId/profile — Get Profile
app.get("/users/:userId/profile", async (req, reply) => {
  try {
    const { userId } = req.params;
    const profile = await store.getProfile(userId);

    if (!profile) {
      return reply.status(404).send({ error: "profile not found" });
    }

    profile.request_id = req.requestId;
    reply.send(profile);
  } catch (err) {
    log("ERROR", "get_profile_failed", { error: err.message });
    reply.status(500).send({ error: "Internal Server Error" });
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

  app.listen({ port: PORT, host: "0.0.0.0" }, (err) => {
    if (err) {
      log("ERROR", "server failed to start", { error: err.message });
      process.exit(1);
    }
    log("INFO", "server starting", { port: String(PORT) });
  });
}

main().catch((err) => {
  log("ERROR", "startup failed", { error: err.message });
  process.exit(1);
});
