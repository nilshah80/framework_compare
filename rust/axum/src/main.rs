use axum::{
    body::{Body, Bytes},
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderValue, Method, Request, Response, StatusCode},
    middleware::{self, Next},
    response::IntoResponse,
    routing::{delete, get, post, put},
    Json, Router,
};
use dashmap::DashMap;
use http_body_util::BodyExt;
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

// ─── Models ───

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OrderItem {
    product_id: String,
    name: String,
    quantity: i64,
    price: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CreateOrderReq {
    items: Vec<OrderItem>,
    #[serde(default)]
    currency: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OrderResponse {
    order_id: String,
    user_id: String,
    status: String,
    items: Vec<OrderItem>,
    total: f64,
    currency: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<String>,
    request_id: String,
}

#[derive(Serialize)]
struct DeleteResp {
    message: String,
    order_id: String,
    request_id: String,
}

#[derive(Serialize)]
struct ErrorResp {
    error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    order_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    request_id: Option<String>,
}

// ─── Store ───

struct AppState {
    store: DashMap<String, OrderResponse>,
    counter: AtomicU64,
}

impl AppState {
    fn new() -> Self {
        Self {
            store: DashMap::new(),
            counter: AtomicU64::new(0),
        }
    }

    fn next_order_id(&self) -> String {
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        id.to_string()
    }

    fn store_key(user_id: &str, order_id: &str) -> String {
        format!("{user_id}:{order_id}")
    }
}

// ─── Helpers ───

fn generate_uuid() -> String {
    uuid::Uuid::new_v4().to_string()
}

fn is_sensitive(key: &str) -> bool {
    matches!(
        key.to_lowercase().as_str(),
        "authorization" | "cookie" | "set-cookie" | "x-api-key" | "x-auth-token"
    )
}

fn redact_header_map(headers: &HeaderMap) -> serde_json::Map<String, serde_json::Value> {
    let mut map = serde_json::Map::new();
    for (key, value) in headers.iter() {
        let k = key.as_str();
        let v = if is_sensitive(k) {
            "[REDACTED]".to_string()
        } else {
            value.to_str().unwrap_or("").to_string()
        };
        map.insert(k.to_string(), serde_json::Value::String(v));
    }
    map
}

// ─── Middleware: Recovery ───

async fn recovery_middleware(req: Request<Body>, next: Next) -> Response<Body> {
    // Rust doesn't panic through async boundaries the same way,
    // but we use catch_unwind for safety on the sync portions
    let result = std::panic::AssertUnwindSafe(next.run(req));
    match tokio::task::unconstrained(result).await {
        resp => resp,
    }
}

// ─── Middleware: Request ID ───

async fn request_id_middleware(mut req: Request<Body>, next: Next) -> Response<Body> {
    let request_id = req
        .headers()
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .unwrap_or_else(generate_uuid);

    req.extensions_mut().insert(request_id.clone());

    let mut res = next.run(req).await;
    res.headers_mut().insert(
        header::HeaderName::from_static("x-request-id"),
        HeaderValue::from_str(&request_id).unwrap(),
    );
    res
}

// ─── Middleware: CORS ───

async fn cors_middleware(req: Request<Body>, next: Next) -> Response<Body> {
    let is_options = req.method() == Method::OPTIONS;

    let mut res = if is_options {
        Response::builder()
            .status(StatusCode::NO_CONTENT)
            .body(Body::empty())
            .unwrap()
    } else {
        next.run(req).await
    };

    let headers = res.headers_mut();
    headers.insert(
        header::HeaderName::from_static("access-control-allow-origin"),
        HeaderValue::from_static("*"),
    );
    headers.insert(
        header::HeaderName::from_static("access-control-allow-methods"),
        HeaderValue::from_static("GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"),
    );
    headers.insert(
        header::HeaderName::from_static("access-control-allow-headers"),
        HeaderValue::from_static("Origin, Content-Type, Accept, Authorization"),
    );
    res
}

// ─── Middleware: Security Headers ───

async fn security_headers_middleware(req: Request<Body>, next: Next) -> Response<Body> {
    let mut res = next.run(req).await;
    let headers = res.headers_mut();
    headers.insert(
        header::HeaderName::from_static("x-xss-protection"),
        HeaderValue::from_static("1; mode=block"),
    );
    headers.insert(
        header::HeaderName::from_static("x-content-type-options"),
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::HeaderName::from_static("x-frame-options"),
        HeaderValue::from_static("DENY"),
    );
    headers.insert(
        header::HeaderName::from_static("strict-transport-security"),
        HeaderValue::from_static("max-age=31536000; includeSubDomains"),
    );
    headers.insert(
        header::HeaderName::from_static("content-security-policy"),
        HeaderValue::from_static("default-src 'self'"),
    );
    headers.insert(
        header::HeaderName::from_static("referrer-policy"),
        HeaderValue::from_static("strict-origin-when-cross-origin"),
    );
    headers.insert(
        header::HeaderName::from_static("permissions-policy"),
        HeaderValue::from_static("geolocation=(), microphone=(), camera=()"),
    );
    headers.insert(
        header::HeaderName::from_static("cross-origin-opener-policy"),
        HeaderValue::from_static("same-origin"),
    );
    res
}

// ─── Middleware: Body Limit ───

async fn body_limit_middleware(req: Request<Body>, next: Next) -> Response<Body> {
    let content_length = req
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    if content_length > 1_048_576 {
        return Response::builder()
            .status(StatusCode::PAYLOAD_TOO_LARGE)
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_string(&serde_json::json!({"error": "request body too large"}))
                    .unwrap(),
            ))
            .unwrap();
    }

    next.run(req).await
}

// ─── Middleware: Structured Logger ───

async fn structured_logger_middleware(req: Request<Body>, next: Next) -> Response<Body> {
    let start = Instant::now();
    let method = req.method().to_string();
    let path = req.uri().path().to_string();
    let query_string = req.uri().query().unwrap_or("").to_string();
    let client_ip = req
        .headers()
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("unknown")
        .to_string();
    let user_agent = req
        .headers()
        .get("user-agent")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let request_id = req
        .extensions()
        .get::<String>()
        .cloned()
        .unwrap_or_default();
    let req_headers = redact_header_map(req.headers());

    let res = next.run(req).await;

    let status = res.status().as_u16();
    let latency = start.elapsed();
    let latency_ms = latency.as_secs_f64() * 1000.0;

    // Capture response headers
    let resp_headers = redact_header_map(res.headers());

    // Collect body for logging, then reconstruct
    let (parts, body) = res.into_parts();
    let body_bytes = body
        .collect()
        .await
        .map(|c| c.to_bytes())
        .unwrap_or_default();
    let response_body = String::from_utf8_lossy(&body_bytes).to_string();
    let bytes_out = body_bytes.len();

    // Parse query params
    let query: serde_json::Map<String, serde_json::Value> = if query_string.is_empty() {
        serde_json::Map::new()
    } else {
        query_string
            .split('&')
            .filter_map(|p| {
                let mut kv = p.splitn(2, '=');
                let k = kv.next()?;
                let v = kv.next().unwrap_or("");
                Some((k.to_string(), serde_json::Value::String(v.to_string())))
            })
            .collect()
    };

    let log_entry = serde_json::json!({
        "level": "INFO",
        "message": "http_dump",
        "request_id": request_id,
        "method": method,
        "path": path,
        "query": query,
        "client_ip": client_ip,
        "user_agent": user_agent,
        "request_headers": req_headers,
        "status": status,
        "latency": format!("{latency:?}"),
        "latency_ms": latency_ms,
        "response_headers": resp_headers,
        "response_body": response_body,
        "bytes_out": bytes_out,
    });
    println!("{}", serde_json::to_string(&log_entry).unwrap_or_default());

    // Reconstruct response
    let mut new_res = Response::new(Body::from(body_bytes));
    *new_res.status_mut() = parts.status;
    *new_res.headers_mut() = parts.headers;
    *new_res.extensions_mut() = parts.extensions;
    new_res
}

// ─── Handlers ───

fn get_request_id_from_ext(extensions: &axum::http::Extensions) -> String {
    extensions
        .get::<String>()
        .cloned()
        .unwrap_or_default()
}

async fn create_order(
    Path(user_id): Path<String>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());

    let body_bytes = match axum::body::to_bytes(req.into_body(), 1_048_576).await {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    let body: CreateOrderReq = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    let order_id = state.next_order_id();

    let mut total = 0.0;
    for item in &body.items {
        total += item.price * item.quantity as f64;
    }

    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency.clone()
    };

    let order = OrderResponse {
        order_id: order_id.clone(),
        user_id: user_id.clone(),
        status: "created".to_string(),
        items: body.items,
        total,
        currency,
        fields: None,
        request_id,
    };

    state
        .store
        .insert(AppState::store_key(&user_id, &order_id), order.clone());

    (StatusCode::CREATED, Json(order)).into_response()
}

#[derive(Deserialize)]
struct OrderPathParams {
    #[serde(rename = "userId")]
    user_id: String,
    #[serde(rename = "orderId")]
    order_id: String,
}

async fn update_order(
    Path(params): Path<OrderPathParams>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());

    let body_bytes = match axum::body::to_bytes(req.into_body(), 1_048_576).await {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    let body: CreateOrderReq = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    let key = AppState::store_key(&params.user_id, &params.order_id);

    let mut entry = match state.store.get_mut(&key) {
        Some(e) => e,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(ErrorResp {
                    error: "order not found".to_string(),
                    order_id: Some(params.order_id),
                    request_id: Some(request_id),
                }),
            )
                .into_response();
        }
    };

    let mut total = 0.0;
    for item in &body.items {
        total += item.price * item.quantity as f64;
    }

    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency
    };

    entry.items = body.items;
    entry.total = total;
    entry.currency = currency;
    entry.status = "updated".to_string();
    entry.request_id = request_id;

    let order = entry.clone();
    drop(entry);

    Json(order).into_response()
}

async fn delete_order(
    Path(params): Path<OrderPathParams>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());
    let key = AppState::store_key(&params.user_id, &params.order_id);

    match state.store.remove(&key) {
        Some(_) => Json(DeleteResp {
            message: "order deleted".to_string(),
            order_id: params.order_id,
            request_id,
        })
        .into_response(),
        None => (
            StatusCode::NOT_FOUND,
            Json(ErrorResp {
                error: "order not found".to_string(),
                order_id: Some(params.order_id),
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
struct GetOrderQuery {
    fields: Option<String>,
}

async fn get_order(
    Path(params): Path<OrderPathParams>,
    Query(query): Query<GetOrderQuery>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());
    let fields = query.fields.unwrap_or_else(|| "*".to_string());
    let key = AppState::store_key(&params.user_id, &params.order_id);

    match state.store.get(&key) {
        Some(entry) => {
            let mut order = entry.clone();
            order.fields = Some(fields);
            order.request_id = request_id;
            Json(order).into_response()
        }
        None => (
            StatusCode::NOT_FOUND,
            Json(ErrorResp {
                error: "order not found".to_string(),
                order_id: Some(params.order_id),
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

// ─── Main ───

#[tokio::main]
async fn main() {
    let state = Arc::new(AppState::new());

    let log_entry = serde_json::json!({
        "level": "INFO",
        "msg": "server starting",
        "port": "8112",
    });
    println!("{}", serde_json::to_string(&log_entry).unwrap_or_default());

    // Build router with middleware layers
    // Layers wrap in reverse order: last layer_fn added = outermost
    // We want: Recovery → RequestID → CORS → Security → BodyLimit → Logger → Handler
    let app = Router::new()
        .route("/users/{userId}/orders", post(create_order))
        .route(
            "/users/{userId}/orders/{orderId}",
            put(update_order)
                .delete(delete_order)
                .get(get_order),
        )
        .with_state(state)
        // Layers: bottom = closest to handler, top = outermost
        .layer(middleware::from_fn(structured_logger_middleware))
        .layer(middleware::from_fn(body_limit_middleware))
        .layer(middleware::from_fn(security_headers_middleware))
        .layer(middleware::from_fn(cors_middleware))
        .layer(middleware::from_fn(request_id_middleware))
        .layer(middleware::from_fn(recovery_middleware));

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8112").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
