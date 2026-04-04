use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;
use warp::http::{HeaderMap, Response, StatusCode};
use warp::hyper::body::Bytes;
use warp::{Filter, Rejection, Reply};

// ─── Models ───

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OrderItem {
    product_id: String,
    name: String,
    quantity: u64,
    price: f64,
}

#[derive(Debug, Clone, Deserialize)]
struct OrderRequest {
    items: Vec<OrderItem>,
    #[serde(default = "default_currency")]
    currency: String,
}

fn default_currency() -> String {
    "USD".to_string()
}

#[derive(Debug, Clone, Serialize)]
struct OrderResponse {
    order_id: String,
    user_id: String,
    status: String,
    items: Vec<OrderItem>,
    total: f64,
    currency: String,
    fields: String,
    request_id: String,
}

#[derive(Debug, Clone, Serialize)]
struct DeleteResponse {
    message: String,
    order_id: String,
    request_id: String,
}

#[derive(Debug, Clone, Serialize)]
struct ErrorResponse {
    error: String,
}

#[derive(Debug, Clone, Deserialize)]
struct GetOrderQuery {
    fields: Option<String>,
}

// ─── Bulk Orders types ───

#[derive(Debug, Clone, Deserialize)]
struct BulkCreateOrderReq {
    orders: Vec<OrderRequest>,
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
    address: WarpAddress,
    #[serde(default)]
    preferences: WarpPreferences,
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
struct WarpAddress {
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
struct WarpPreferences {
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

// ─── Store ───

struct AppState {
    store: DashMap<String, StoredOrder>,
    profiles: DashMap<String, UserProfile>,
    next_id: AtomicU64,
}

#[derive(Debug, Clone)]
struct StoredOrder {
    order_id: String,
    user_id: String,
    items: Vec<OrderItem>,
    total: f64,
    currency: String,
}

// ─── PII Redaction ───

const PII_HEADERS: &[&str] = &[
    "authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-auth-token",
];

fn redact_header_map(headers: &HeaderMap) -> serde_json::Value {
    let map: serde_json::Map<String, serde_json::Value> = headers
        .iter()
        .map(|(k, v)| {
            let key = k.as_str().to_lowercase();
            let val = if PII_HEADERS.contains(&key.as_str()) {
                "[REDACTED]".to_string()
            } else {
                v.to_str().unwrap_or("-").to_string()
            };
            (k.to_string(), serde_json::Value::String(val))
        })
        .collect();
    serde_json::Value::Object(map)
}

// ─── Helpers ───

fn json_response<T: Serialize>(status: StatusCode, body: &T, request_id: &str) -> Response<String> {
    let json_str = serde_json::to_string(body).unwrap();
    Response::builder()
        .status(status)
        .header("Content-Type", "application/json")
        .header("X-Request-ID", request_id)
        .header("X-XSS-Protection", "1; mode=block")
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Frame-Options", "DENY")
        .header(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        )
        .header("Content-Security-Policy", "default-src 'self'")
        .header("Referrer-Policy", "strict-origin-when-cross-origin")
        .header(
            "Permissions-Policy",
            "geolocation=(), microphone=(), camera=()",
        )
        .header("Cross-Origin-Opener-Policy", "same-origin")
        .header("Access-Control-Allow-Origin", "*")
        .header(
            "Access-Control-Allow-Methods",
            "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS",
        )
        .header(
            "Access-Control-Allow-Headers",
            "Origin,Content-Type,Accept,Authorization",
        )
        .body(json_str)
        .unwrap()
}

fn extract_request_id(headers: &HeaderMap) -> String {
    headers
        .get("X-Request-ID")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .unwrap_or_else(|| Uuid::new_v4().to_string())
}

// ─── Handlers ───

async fn handle_create_order(
    user_id: String,
    headers: HeaderMap,
    body_bytes: Bytes,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);

    if body_bytes.len() > 1_048_576 {
        let resp = json_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            &ErrorResponse {
                error: "Payload too large".to_string(),
            },
            &request_id,
        );
        log_request("POST", &format!("/users/{}/orders", user_id), "", &headers, &request_id, &resp, start);
        return Ok(resp);
    }

    let body: OrderRequest = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            let resp = json_response(
                StatusCode::BAD_REQUEST,
                &ErrorResponse {
                    error: "invalid JSON".to_string(),
                },
                &request_id,
            );
            log_request("POST", &format!("/users/{}/orders", user_id), "", &headers, &request_id, &resp, start);
            return Ok(resp);
        }
    };

    let id = state.next_id.fetch_add(1, Ordering::SeqCst);
    let order_id = id.to_string();
    let total: f64 = body.items.iter().map(|i| i.price * i.quantity as f64).sum();
    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency.clone()
    };

    let stored = StoredOrder {
        order_id: order_id.clone(),
        user_id: user_id.clone(),
        items: body.items.clone(),
        total,
        currency: currency.clone(),
    };
    state
        .store
        .insert(format!("{}:{}", user_id, order_id), stored);

    let order_resp = OrderResponse {
        order_id,
        user_id,
        status: "created".to_string(),
        items: body.items,
        total,
        currency,
        fields: "*".to_string(),
        request_id: request_id.clone(),
    };

    let resp = json_response(StatusCode::CREATED, &order_resp, &request_id);
    log_request("POST", &format!("/users/{}/orders", order_resp.user_id), "", &headers, &request_id, &resp, start);
    Ok(resp)
}

async fn handle_update_order(
    user_id: String,
    order_id: String,
    headers: HeaderMap,
    body_bytes: Bytes,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);

    if body_bytes.len() > 1_048_576 {
        let resp = json_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            &ErrorResponse {
                error: "Payload too large".to_string(),
            },
            &request_id,
        );
        log_request("PUT", &format!("/users/{}/orders/{}", user_id, order_id), "", &headers, &request_id, &resp, start);
        return Ok(resp);
    }

    let body: OrderRequest = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            let resp = json_response(
                StatusCode::BAD_REQUEST,
                &ErrorResponse {
                    error: "invalid JSON".to_string(),
                },
                &request_id,
            );
            log_request("PUT", &format!("/users/{}/orders/{}", user_id, order_id), "", &headers, &request_id, &resp, start);
            return Ok(resp);
        }
    };

    let key = format!("{}:{}", user_id, order_id);
    if !state.store.contains_key(&key) {
        let resp = json_response(
            StatusCode::NOT_FOUND,
            &ErrorResponse {
                error: "order not found".to_string(),
            },
            &request_id,
        );
        log_request("PUT", &format!("/users/{}/orders/{}", user_id, order_id), "", &headers, &request_id, &resp, start);
        return Ok(resp);
    }

    let total: f64 = body.items.iter().map(|i| i.price * i.quantity as f64).sum();
    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency.clone()
    };

    let stored = StoredOrder {
        order_id: order_id.clone(),
        user_id: user_id.clone(),
        items: body.items.clone(),
        total,
        currency: currency.clone(),
    };
    state.store.insert(key, stored);

    let order_resp = OrderResponse {
        order_id,
        user_id,
        status: "updated".to_string(),
        items: body.items,
        total,
        currency,
        fields: "*".to_string(),
        request_id: request_id.clone(),
    };

    let resp = json_response(StatusCode::OK, &order_resp, &request_id);
    log_request("PUT", &format!("/users/{}/orders/{}", order_resp.user_id, order_resp.order_id), "", &headers, &request_id, &resp, start);
    Ok(resp)
}

async fn handle_delete_order(
    user_id: String,
    order_id: String,
    headers: HeaderMap,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);
    let key = format!("{}:{}", user_id, order_id);
    let path = format!("/users/{}/orders/{}", user_id, order_id);

    match state.store.remove(&key) {
        Some(_) => {
            let del_resp = DeleteResponse {
                message: "order deleted".to_string(),
                order_id,
                request_id: request_id.clone(),
            };
            let resp = json_response(StatusCode::OK, &del_resp, &request_id);
            log_request("DELETE", &path, "", &headers, &request_id, &resp, start);
            Ok(resp)
        }
        None => {
            let resp = json_response(
                StatusCode::NOT_FOUND,
                &ErrorResponse {
                    error: "order not found".to_string(),
                },
                &request_id,
            );
            log_request("DELETE", &path, "", &headers, &request_id, &resp, start);
            Ok(resp)
        }
    }
}

async fn handle_get_order(
    user_id: String,
    order_id: String,
    query: GetOrderQuery,
    headers: HeaderMap,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);
    let key = format!("{}:{}", user_id, order_id);
    let fields = query.fields.unwrap_or_else(|| "*".to_string());
    let query_str = format!("fields={}", fields);
    let path = format!("/users/{}/orders/{}", user_id, order_id);

    match state.store.get(&key) {
        Some(entry) => {
            let stored = entry.value();
            let order_resp = OrderResponse {
                order_id: stored.order_id.clone(),
                user_id: stored.user_id.clone(),
                status: "created".to_string(),
                items: stored.items.clone(),
                total: stored.total,
                currency: stored.currency.clone(),
                fields,
                request_id: request_id.clone(),
            };
            let resp = json_response(StatusCode::OK, &order_resp, &request_id);
            log_request("GET", &path, &query_str, &headers, &request_id, &resp, start);
            Ok(resp)
        }
        None => {
            let resp = json_response(
                StatusCode::NOT_FOUND,
                &ErrorResponse {
                    error: "order not found".to_string(),
                },
                &request_id,
            );
            log_request("GET", &path, &query_str, &headers, &request_id, &resp, start);
            Ok(resp)
        }
    }
}

// ─── Bulk + List + Profile Handlers ───

async fn handle_bulk_create_orders(
    user_id: String,
    headers: HeaderMap,
    body_bytes: Bytes,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);

    if body_bytes.len() > 1_048_576 {
        let resp = json_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            &ErrorResponse {
                error: "Payload too large".to_string(),
            },
            &request_id,
        );
        log_request("POST", &format!("/users/{}/orders/bulk", user_id), "", &headers, &request_id, &resp, start);
        return Ok(resp);
    }

    let body: BulkCreateOrderReq = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            let resp = json_response(
                StatusCode::BAD_REQUEST,
                &ErrorResponse {
                    error: "invalid JSON".to_string(),
                },
                &request_id,
            );
            log_request("POST", &format!("/users/{}/orders/bulk", user_id), "", &headers, &request_id, &resp, start);
            return Ok(resp);
        }
    };

    let mut results = Vec::new();
    let mut total_sum = 0.0;

    for order_req in &body.orders {
        let id = state.next_id.fetch_add(1, Ordering::SeqCst);
        let order_id = id.to_string();
        let total: f64 = order_req.items.iter().map(|i| i.price * i.quantity as f64).sum();
        let currency = if order_req.currency.is_empty() {
            "USD".to_string()
        } else {
            order_req.currency.clone()
        };

        let stored = StoredOrder {
            order_id: order_id.clone(),
            user_id: user_id.clone(),
            items: order_req.items.clone(),
            total,
            currency: currency.clone(),
        };
        state.store.insert(format!("{}:{}", user_id, order_id), stored);

        results.push(OrderResponse {
            order_id,
            user_id: user_id.clone(),
            status: "created".to_string(),
            items: order_req.items.clone(),
            total,
            currency,
            fields: "*".to_string(),
            request_id: request_id.clone(),
        });
        total_sum += total;
    }

    let bulk_resp = BulkOrderResponse {
        user_id: user_id.clone(),
        count: results.len(),
        orders: results,
        total_sum,
        request_id: request_id.clone(),
    };

    let resp = json_response(StatusCode::CREATED, &bulk_resp, &request_id);
    log_request("POST", &format!("/users/{}/orders/bulk", user_id), "", &headers, &request_id, &resp, start);
    Ok(resp)
}

async fn handle_list_orders(
    user_id: String,
    headers: HeaderMap,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);
    let prefix = format!("{}:", user_id);
    let path = format!("/users/{}/orders", user_id);

    let mut results = Vec::new();
    for entry in state.store.iter() {
        if entry.key().starts_with(&prefix) {
            let stored = entry.value();
            results.push(OrderResponse {
                order_id: stored.order_id.clone(),
                user_id: stored.user_id.clone(),
                status: "created".to_string(),
                items: stored.items.clone(),
                total: stored.total,
                currency: stored.currency.clone(),
                fields: "*".to_string(),
                request_id: request_id.clone(),
            });
        }
    }

    let list_resp = ListOrdersResponse {
        user_id,
        count: results.len(),
        orders: results,
        request_id: request_id.clone(),
    };

    let resp = json_response(StatusCode::OK, &list_resp, &request_id);
    log_request("GET", &path, "", &headers, &request_id, &resp, start);
    Ok(resp)
}

async fn handle_put_profile(
    user_id: String,
    headers: HeaderMap,
    body_bytes: Bytes,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);
    let path = format!("/users/{}/profile", user_id);

    if body_bytes.len() > 1_048_576 {
        let resp = json_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            &ErrorResponse {
                error: "Payload too large".to_string(),
            },
            &request_id,
        );
        log_request("PUT", &path, "", &headers, &request_id, &resp, start);
        return Ok(resp);
    }

    let mut profile: UserProfile = match serde_json::from_slice(&body_bytes) {
        Ok(b) => b,
        Err(_) => {
            let resp = json_response(
                StatusCode::BAD_REQUEST,
                &ErrorResponse {
                    error: "invalid JSON".to_string(),
                },
                &request_id,
            );
            log_request("PUT", &path, "", &headers, &request_id, &resp, start);
            return Ok(resp);
        }
    };

    profile.user_id = user_id.clone();
    profile.request_id = request_id.clone();

    state.profiles.insert(user_id, profile.clone());

    let resp = json_response(StatusCode::OK, &profile, &request_id);
    log_request("PUT", &path, "", &headers, &request_id, &resp, start);
    Ok(resp)
}

async fn handle_get_profile(
    user_id: String,
    headers: HeaderMap,
    state: Arc<AppState>,
) -> Result<impl Reply, Rejection> {
    let start = Instant::now();
    let request_id = extract_request_id(&headers);
    let path = format!("/users/{}/profile", user_id);

    match state.profiles.get(&user_id) {
        Some(entry) => {
            let mut profile = entry.value().clone();
            profile.request_id = request_id.clone();
            let resp = json_response(StatusCode::OK, &profile, &request_id);
            log_request("GET", &path, "", &headers, &request_id, &resp, start);
            Ok(resp)
        }
        None => {
            let resp = json_response(
                StatusCode::NOT_FOUND,
                &ErrorResponse {
                    error: "profile not found".to_string(),
                },
                &request_id,
            );
            log_request("GET", &path, "", &headers, &request_id, &resp, start);
            Ok(resp)
        }
    }
}

// ─── Structured Logger ───

fn log_request(
    method: &str,
    path: &str,
    query: &str,
    req_headers: &HeaderMap,
    request_id: &str,
    response: &Response<String>,
    start: Instant,
) {
    let latency = start.elapsed();
    let body_str = response.body();
    let resp_headers = response.headers();

    let client_ip = req_headers
        .get("X-Forwarded-For")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-");
    let user_agent = req_headers
        .get("User-Agent")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-");

    let query_map: serde_json::Map<String, serde_json::Value> = if query.is_empty() {
        serde_json::Map::new()
    } else {
        query
            .split('&')
            .filter_map(|p| {
                let mut parts = p.splitn(2, '=');
                let k = parts.next()?;
                let v = parts.next().unwrap_or("");
                Some((k.to_string(), serde_json::Value::String(v.to_string())))
            })
            .collect()
    };

    let log = serde_json::json!({
        "level": "INFO",
        "message": "http_dump",
        "request_id": request_id,
        "method": method,
        "path": path,
        "query": query_map,
        "client_ip": client_ip,
        "user_agent": user_agent,
        "request_headers": redact_header_map(req_headers),
        "status": response.status().as_u16(),
        "latency": format!("{:?}", latency),
        "latency_ms": latency.as_secs_f64() * 1000.0,
        "response_headers": redact_header_map(resp_headers),
        "response_body": body_str,
        "bytes_out": body_str.len(),
    });
    println!("{}", serde_json::to_string(&log).unwrap());
}

// ─── Recovery handler ───

async fn handle_rejection(err: Rejection) -> Result<impl Reply, Infallible> {
    let (status, message) = if err.is_not_found() {
        (StatusCode::NOT_FOUND, "not found")
    } else if err.find::<warp::reject::PayloadTooLarge>().is_some() {
        (StatusCode::PAYLOAD_TOO_LARGE, "Payload too large")
    } else {
        (StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error")
    };

    let body = ErrorResponse {
        error: message.to_string(),
    };
    let json_str = serde_json::to_string(&body).unwrap();

    Ok(Response::builder()
        .status(status)
        .header("Content-Type", "application/json")
        .body(json_str)
        .unwrap())
}

// ─── Main ───

#[tokio::main]
async fn main() {
    let state = Arc::new(AppState {
        store: DashMap::new(),
        profiles: DashMap::new(),
        next_id: AtomicU64::new(1),
    });

    let state_filter = {
        let state = state.clone();
        warp::any().map(move || state.clone())
    };

    // POST /users/{userId}/orders
    let create = warp::post()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(warp::body::bytes())
        .and(state_filter.clone())
        .and_then(handle_create_order);

    // PUT /users/{userId}/orders/{orderId}
    let update = warp::put()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path::param::<String>())
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(warp::body::bytes())
        .and(state_filter.clone())
        .and_then(handle_update_order);

    // DELETE /users/{userId}/orders/{orderId}
    let delete = warp::delete()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path::param::<String>())
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(state_filter.clone())
        .and_then(handle_delete_order);

    // GET /users/{userId}/orders/{orderId}?fields=X
    let get = warp::get()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path::param::<String>())
        .and(warp::path::end())
        .and(warp::query::<GetOrderQuery>())
        .and(warp::header::headers_cloned())
        .and(state_filter.clone())
        .and_then(handle_get_order);

    // POST /users/{userId}/orders/bulk
    let bulk_create = warp::post()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path("bulk"))
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(warp::body::bytes())
        .and(state_filter.clone())
        .and_then(handle_bulk_create_orders);

    // GET /users/{userId}/orders
    let list = warp::get()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("orders"))
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(state_filter.clone())
        .and_then(handle_list_orders);

    // PUT /users/{userId}/profile
    let put_profile = warp::put()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("profile"))
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(warp::body::bytes())
        .and(state_filter.clone())
        .and_then(handle_put_profile);

    // GET /users/{userId}/profile
    let get_profile = warp::get()
        .and(warp::path("users"))
        .and(warp::path::param::<String>())
        .and(warp::path("profile"))
        .and(warp::path::end())
        .and(warp::header::headers_cloned())
        .and(state_filter.clone())
        .and_then(handle_get_profile);

    // OPTIONS preflight
    let options = warp::options().map(|| {
        Response::builder()
            .status(200)
            .header("Access-Control-Allow-Origin", "*")
            .header(
                "Access-Control-Allow-Methods",
                "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS",
            )
            .header(
                "Access-Control-Allow-Headers",
                "Origin,Content-Type,Accept,Authorization",
            )
            .body(String::new())
            .unwrap()
    });

    let routes = bulk_create
        .or(create)
        .or(list)
        .or(update)
        .or(delete)
        .or(get)
        .or(put_profile)
        .or(get_profile)
        .or(options)
        .recover(handle_rejection);

    println!("Warp server listening on 0.0.0.0:8114");
    warp::serve(routes).run(([0, 0, 0, 0], 8114)).await;
}
