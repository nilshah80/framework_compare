use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderValue, Method, Request, Response, StatusCode},
    middleware::{self, Next},
    response::IntoResponse,
    routing::{post, put},
    Json, Router,
};
use http_body_util::BodyExt;
use pgstore::{BulkOrderInput, PgStore};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
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

// ─── Bulk Orders types ───

#[derive(Debug, Clone, Deserialize)]
struct BulkCreateOrderReq {
    orders: Vec<CreateOrderReq>,
}

#[derive(Debug, Clone, Serialize)]
struct BulkOrderResponse {
    user_id: String,
    count: usize,
    orders: Vec<OrderResponse>,
    total_sum: f64,
    request_id: String,
}

// ─── List Orders response ───

#[derive(Debug, Clone, Serialize)]
struct ListOrdersResponse {
    user_id: String,
    count: usize,
    orders: Vec<OrderResponse>,
    request_id: String,
}

// ─── User Profile types ───

#[derive(Debug, Clone, Serialize, Deserialize)]
struct UserProfile {
    #[serde(default)]
    user_id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    email: String,
    #[serde(default)]
    phone: String,
    #[serde(default)]
    address: Address,
    #[serde(default)]
    preferences: Preferences,
    #[serde(default)]
    payment_methods: Vec<PaymentMethod>,
    #[serde(default)]
    tags: Vec<String>,
    #[serde(default)]
    metadata: HashMap<String, String>,
    #[serde(default)]
    request_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct Address {
    #[serde(default)]
    street: String,
    #[serde(default)]
    city: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    zip: String,
    #[serde(default)]
    country: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct Preferences {
    #[serde(default)]
    language: String,
    #[serde(default)]
    currency: String,
    #[serde(default)]
    timezone: String,
    #[serde(default)]
    notifications: NotificationPrefs,
    #[serde(default)]
    theme: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct NotificationPrefs {
    #[serde(default)]
    email: bool,
    #[serde(default)]
    sms: bool,
    #[serde(default)]
    push: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PaymentMethod {
    #[serde(rename = "type")]
    type_: String,
    last4: String,
    expiry_month: i32,
    expiry_year: i32,
    is_default: bool,
}

// ─── App State ───

struct AppState {
    store: PgStore,
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

fn extract_client_ip(headers: &HeaderMap) -> String {
    if let Some(xff) = headers.get("x-forwarded-for") {
        if let Ok(val) = xff.to_str() {
            let first = val.split(',').next().unwrap_or("").trim();
            if !first.is_empty() {
                return first.to_string();
            }
        }
    }
    "unknown".to_string()
}

fn to_pgstore_items(items: &[OrderItem]) -> Vec<pgstore::OrderItem> {
    items
        .iter()
        .map(|i| pgstore::OrderItem {
            product_id: i.product_id.clone(),
            name: i.name.clone(),
            quantity: i.quantity,
            price: i.price,
        })
        .collect()
}

fn from_pgstore_items(items: &[pgstore::OrderItem]) -> Vec<OrderItem> {
    items
        .iter()
        .map(|i| OrderItem {
            product_id: i.product_id.clone(),
            name: i.name.clone(),
            quantity: i.quantity,
            price: i.price,
        })
        .collect()
}

fn order_to_response(o: pgstore::Order, request_id: String) -> OrderResponse {
    OrderResponse {
        order_id: o.order_id,
        user_id: o.user_id,
        status: o.status,
        items: from_pgstore_items(&o.items),
        total: o.total,
        currency: o.currency,
        fields: None,
        request_id,
    }
}

fn profile_to_user(p: pgstore::Profile, request_id: String) -> UserProfile {
    UserProfile {
        user_id: p.user_id,
        name: p.name,
        email: p.email,
        phone: p.phone,
        address: Address {
            street: p.address.street,
            city: p.address.city,
            state: p.address.state,
            zip: p.address.zip,
            country: p.address.country,
        },
        preferences: Preferences {
            language: p.preferences.language,
            currency: p.preferences.currency,
            timezone: p.preferences.timezone,
            notifications: NotificationPrefs {
                email: p.preferences.notifications.email,
                sms: p.preferences.notifications.sms,
                push: p.preferences.notifications.push,
            },
            theme: p.preferences.theme,
        },
        payment_methods: p
            .payment_methods
            .iter()
            .map(|pm| PaymentMethod {
                type_: pm.type_.clone(),
                last4: pm.last4.clone(),
                expiry_month: pm.expiry_month,
                expiry_year: pm.expiry_year,
                is_default: pm.is_default,
            })
            .collect(),
        tags: p.tags,
        metadata: p.metadata,
        request_id,
    }
}

fn user_to_profile(u: &UserProfile) -> pgstore::Profile {
    pgstore::Profile {
        user_id: u.user_id.clone(),
        name: u.name.clone(),
        email: u.email.clone(),
        phone: u.phone.clone(),
        address: pgstore::Address {
            street: u.address.street.clone(),
            city: u.address.city.clone(),
            state: u.address.state.clone(),
            zip: u.address.zip.clone(),
            country: u.address.country.clone(),
        },
        preferences: pgstore::Preferences {
            language: u.preferences.language.clone(),
            currency: u.preferences.currency.clone(),
            timezone: u.preferences.timezone.clone(),
            notifications: pgstore::NotificationPrefs {
                email: u.preferences.notifications.email,
                sms: u.preferences.notifications.sms,
                push: u.preferences.notifications.push,
            },
            theme: u.preferences.theme.clone(),
        },
        payment_methods: u
            .payment_methods
            .iter()
            .map(|pm| pgstore::PaymentMethod {
                type_: pm.type_.clone(),
                last4: pm.last4.clone(),
                expiry_month: pm.expiry_month,
                expiry_year: pm.expiry_year,
                is_default: pm.is_default,
            })
            .collect(),
        tags: u.tags.clone(),
        metadata: u.metadata.clone(),
    }
}

// ─── Middleware: Recovery ───

async fn recovery_middleware(req: Request<Body>, next: Next) -> Response<Body> {
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
    let client_ip = extract_client_ip(req.headers());
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

    // Capture request body
    let (parts, body) = req.into_parts();
    let req_body_bytes = body
        .collect()
        .await
        .map(|c| c.to_bytes())
        .unwrap_or_default();
    let request_body = String::from_utf8_lossy(&req_body_bytes).to_string();

    // Reconstruct request
    let req = Request::from_parts(parts, Body::from(req_body_bytes));

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
        "request_body": request_body,
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

    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.store.create_order(&user_id, &items, currency).await {
        Ok(order) => {
            let resp = order_to_response(order, request_id);
            (StatusCode::CREATED, Json(resp)).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
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

    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.store.update_order(&params.user_id, &params.order_id, &items, currency).await {
        Ok(Some(order)) => {
            let resp = order_to_response(order, request_id);
            Json(resp).into_response()
        }
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(ErrorResp {
                error: "order not found".to_string(),
                order_id: Some(params.order_id),
                request_id: Some(request_id),
            }),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

async fn delete_order(
    Path(params): Path<OrderPathParams>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());

    match state.store.delete_order(&params.user_id, &params.order_id).await {
        Ok(true) => Json(DeleteResp {
            message: "order deleted".to_string(),
            order_id: params.order_id,
            request_id,
        })
        .into_response(),
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(ErrorResp {
                error: "order not found".to_string(),
                order_id: Some(params.order_id),
                request_id: Some(request_id),
            }),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
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

    match state.store.get_order(&params.user_id, &params.order_id).await {
        Ok(Some(order)) => {
            let mut resp = order_to_response(order, request_id);
            resp.fields = Some(fields);
            Json(resp).into_response()
        }
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(ErrorResp {
                error: "order not found".to_string(),
                order_id: Some(params.order_id),
                request_id: Some(request_id),
            }),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

// ─── Bulk + List + Profile Handlers ───

async fn bulk_create_orders(
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

    let body: BulkCreateOrderReq = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    let inputs: Vec<BulkOrderInput> = body
        .orders
        .iter()
        .map(|o| BulkOrderInput {
            items: to_pgstore_items(&o.items),
            currency: if o.currency.is_empty() {
                "USD".to_string()
            } else {
                o.currency.clone()
            },
        })
        .collect();

    match state.store.bulk_create_orders(&user_id, &inputs).await {
        Ok((orders, total_sum)) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();
            (
                StatusCode::CREATED,
                Json(BulkOrderResponse {
                    user_id,
                    count: results.len(),
                    orders: results,
                    total_sum,
                    request_id,
                }),
            )
                .into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

async fn list_orders(
    Path(user_id): Path<String>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());

    match state.store.list_orders(&user_id).await {
        Ok(orders) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();
            Json(ListOrdersResponse {
                user_id,
                count: results.len(),
                orders: results,
                request_id,
            })
            .into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

async fn put_profile(
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

    let mut profile: UserProfile = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "invalid request body"})),
            )
                .into_response();
        }
    };

    profile.user_id = user_id.clone();
    let pg_profile = user_to_profile(&profile);

    match state.store.upsert_profile(&user_id, &pg_profile).await {
        Ok(()) => {
            profile.request_id = request_id;
            Json(profile).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

async fn get_profile(
    Path(user_id): Path<String>,
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> impl IntoResponse {
    let request_id = get_request_id_from_ext(req.extensions());

    match state.store.get_profile(&user_id).await {
        Ok(Some(profile)) => {
            let user = profile_to_user(profile, request_id);
            Json(user).into_response()
        }
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "profile not found"})),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResp {
                error: format!("db error: {}", e),
                order_id: None,
                request_id: Some(request_id),
            }),
        )
            .into_response(),
    }
}

// ─── Main ───

#[tokio::main]
async fn main() {
    let pg_dsn = std::env::var("PG_DSN").expect("PG_DSN environment variable must be set");
    let pg_store = PgStore::new(&pg_dsn)
        .await
        .expect("Failed to connect to PostgreSQL");
    pg_store
        .init_schema()
        .await
        .expect("Failed to initialize schema");

    let state = Arc::new(AppState { store: pg_store });

    let log_entry = serde_json::json!({
        "level": "INFO",
        "msg": "server starting",
        "port": "8112",
    });
    println!("{}", serde_json::to_string(&log_entry).unwrap_or_default());

    let app = Router::new()
        .route("/users/{userId}/orders/bulk", post(bulk_create_orders))
        .route(
            "/users/{userId}/orders",
            post(create_order).get(list_orders),
        )
        .route(
            "/users/{userId}/orders/{orderId}",
            put(update_order)
                .delete(delete_order)
                .get(get_order),
        )
        .route(
            "/users/{userId}/profile",
            put(put_profile).get(get_profile),
        )
        .with_state(state)
        .layer(middleware::from_fn(structured_logger_middleware))
        .layer(middleware::from_fn(body_limit_middleware))
        .layer(middleware::from_fn(security_headers_middleware))
        .layer(middleware::from_fn(cors_middleware))
        .layer(middleware::from_fn(request_id_middleware))
        .layer(middleware::from_fn(recovery_middleware));

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8112").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
