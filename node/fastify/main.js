process.env.NODE_ENV = "production";

const Fastify = require("fastify");
const crypto = require("crypto");

const app = Fastify({ bodyLimit: 1 << 20, logger: false });
const PORT = 8092;

// ── In-memory store ──────────────────────────────────────────────────
const orderStore = new Map();
const profileStore = new Map();
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

  log("INFO", "http_dump", {
    request_id: req.requestId,
    method: req.method,
    path: url.pathname,
    query,
    client_ip: req.ip,
    user_agent: req.headers["user-agent"] || "",
    request_headers: redactHeaders(req.headers),
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
app.post("/users/:userId/orders", (req, reply) => {
  const { userId } = req.params;
  const { items, currency } = req.body || {};

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
  reply.status(201).send(order);
});

// PUT /users/:userId/orders/:orderId — Update Order
app.put("/users/:userId/orders/:orderId", (req, reply) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return reply.status(404).send({ error: "order not found" });
  }

  const { items, currency } = req.body || {};
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
  reply.send(order);
});

// DELETE /users/:userId/orders/:orderId — Delete Order
app.delete("/users/:userId/orders/:orderId", (req, reply) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return reply.status(404).send({ error: "order not found" });
  }

  orderStore.delete(key);
  reply.send({
    message: "order deleted",
    order_id: orderId,
    request_id: req.requestId,
  });
});

// GET /users/:userId/orders/:orderId — Fetch Order
app.get("/users/:userId/orders/:orderId", (req, reply) => {
  const { userId, orderId } = req.params;
  const key = storeKey(userId, orderId);

  if (!orderStore.has(key)) {
    return reply.status(404).send({ error: "order not found" });
  }

  const fields = req.query.fields || "*";

  const order = { ...orderStore.get(key) };
  order.fields = fields;
  order.request_id = req.requestId;

  reply.send(order);
});

// POST /users/:userId/orders/bulk — Bulk Create Orders
app.post("/users/:userId/orders/bulk", (req, reply) => {
  const { userId } = req.params;
  const { orders } = req.body || {};

  const results = [];
  let totalSum = 0;

  for (const item of orders || []) {
    let total = 0;
    for (const i of item.items || []) {
      total += i.price * i.quantity;
    }

    const orderId = nextOrderID();
    const order = {
      order_id: orderId,
      user_id: userId,
      status: "created",
      items: item.items || [],
      total,
      currency: item.currency || "USD",
      fields: "",
      request_id: req.requestId,
    };

    orderStore.set(storeKey(userId, orderId), order);
    results.push(order);
    totalSum += total;
  }

  reply.status(201).send({
    user_id: userId,
    count: results.length,
    orders: results,
    total_sum: totalSum,
    request_id: req.requestId,
  });
});

// GET /users/:userId/orders — List All Orders
app.get("/users/:userId/orders", (req, reply) => {
  const { userId } = req.params;
  const prefix = `${userId}:`;
  const results = [];

  for (const [key, order] of orderStore) {
    if (key.startsWith(prefix)) {
      results.push({ ...order, request_id: req.requestId });
    }
  }

  reply.send({
    user_id: userId,
    count: results.length,
    orders: results,
    request_id: req.requestId,
  });
});

// PUT /users/:userId/profile — Create/Update Profile
app.put("/users/:userId/profile", (req, reply) => {
  const { userId } = req.params;
  const profile = { ...req.body, user_id: userId, request_id: req.requestId };
  profileStore.set(userId, profile);
  reply.send(profile);
});

// GET /users/:userId/profile — Get Profile
app.get("/users/:userId/profile", (req, reply) => {
  const { userId } = req.params;

  if (!profileStore.has(userId)) {
    return reply.status(404).send({ error: "profile not found" });
  }

  const profile = { ...profileStore.get(userId), request_id: req.requestId };
  reply.send(profile);
});

// ── Start server ─────────────────────────────────────────────────────
app.listen({ port: PORT, host: "0.0.0.0" }, (err) => {
  if (err) {
    log("ERROR", "server failed to start", { error: err.message });
    process.exit(1);
  }
  log("INFO", "server starting", { port: String(PORT) });
});
