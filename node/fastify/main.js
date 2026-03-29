process.env.NODE_ENV = "production";

const Fastify = require("fastify");
const crypto = require("crypto");

const app = Fastify({ bodyLimit: 1 << 20, logger: false });
const PORT = 8092;

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

// ── Start server ─────────────────────────────────────────────────────
app.listen({ port: PORT, host: "0.0.0.0" }, (err) => {
  if (err) {
    log("ERROR", "server failed to start", { error: err.message });
    process.exit(1);
  }
  log("INFO", "server starting", { port: String(PORT) });
});
