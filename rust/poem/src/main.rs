use pgstore::{BulkOrderInput, PgStore};
use poem::http::{HeaderMap, HeaderValue, Method, StatusCode};
use poem::web::{Json, Path, Query};
use poem::{
    handler, Endpoint, EndpointExt, IntoResponse, Middleware, Request, Response,
    Result, Route, Server,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;

// ─── Models ───

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OrderItem {
    product_id: String,
    name: String,
    quantity: i64,
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
    address: PoemAddress,
    #[serde(default)]
    preferences: PoemPreferences,
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
struct PoemAddress {
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
struct PoemPreferences {
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

// ─── Conversion helpers ───

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
        fields: "*".to_string(),
        request_id,
    }
}

fn profile_to_user(p: pgstore::Profile, request_id: String) -> UserProfile {
    UserProfile {
        user_id: p.user_id,
        name: p.name,
        email: p.email,
        phone: p.phone,
        address: PoemAddress {
            street: p.address.street,
            city: p.address.city,
            state: p.address.state,
            zip: p.address.zip,
            country: p.address.country,
        },
        preferences: PoemPreferences {
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

fn redact_response_headers(headers: &HeaderMap) -> serde_json::Value {
    redact_header_map(headers)
}

// ─── Request ID helper ───

fn extract_request_id(req: &Request) -> String {
    req.header("X-Request-ID")
        .map(|s: &str| s.to_string())
        .unwrap_or_else(|| Uuid::new_v4().to_string())
}

fn extract_client_ip(req: &Request) -> String {
    if let Some(xff) = req.header("X-Forwarded-For") {
        let first = xff.split(',').next().unwrap_or("").trim();
        if !first.is_empty() {
            return first.to_string();
        }
    }
    "-".to_string()
}

// ─── Middleware: Recovery ───

struct RecoveryMiddleware;

impl<E: Endpoint> Middleware<E> for RecoveryMiddleware {
    type Output = RecoveryEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        RecoveryEndpoint(ep)
    }
}

struct RecoveryEndpoint<E>(E);

impl<E: Endpoint> Endpoint for RecoveryEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        match tokio::task::spawn(async { Ok::<(), ()>(()) }).await {
            _ => {}
        }
        match self.0.call(req).await {
            Ok(resp) => Ok(resp.into_response()),
            Err(_) => {
                let body = serde_json::to_string(&ErrorResponse {
                    error: "Internal Server Error".to_string(),
                })
                .unwrap();
                Ok(Response::builder()
                    .status(StatusCode::INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body(body))
            }
        }
    }
}

// ─── Middleware: Request ID ───

struct RequestIdMiddleware;

impl<E: Endpoint> Middleware<E> for RequestIdMiddleware {
    type Output = RequestIdEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        RequestIdEndpoint(ep)
    }
}

struct RequestIdEndpoint<E>(E);

impl<E: Endpoint> Endpoint for RequestIdEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        let request_id = extract_request_id(&req);
        let mut resp = self.0.call(req).await?.into_response();
        resp.headers_mut().insert(
            "X-Request-ID",
            HeaderValue::from_str(&request_id).unwrap(),
        );
        Ok(resp)
    }
}

// ─── Middleware: CORS ───

struct CorsMiddleware;

impl<E: Endpoint> Middleware<E> for CorsMiddleware {
    type Output = CorsEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        CorsEndpoint(ep)
    }
}

struct CorsEndpoint<E>(E);

impl<E: Endpoint> Endpoint for CorsEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        let is_options = req.method() == Method::OPTIONS;

        let mut resp = if is_options {
            Response::builder().status(StatusCode::OK).body("")
        } else {
            self.0.call(req).await?.into_response()
        };

        let h = resp.headers_mut();
        h.insert(
            "Access-Control-Allow-Origin",
            HeaderValue::from_static("*"),
        );
        h.insert(
            "Access-Control-Allow-Methods",
            HeaderValue::from_static("GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS"),
        );
        h.insert(
            "Access-Control-Allow-Headers",
            HeaderValue::from_static("Origin,Content-Type,Accept,Authorization"),
        );
        Ok(resp)
    }
}

// ─── Middleware: Security Headers ───

struct SecurityHeadersMiddleware;

impl<E: Endpoint> Middleware<E> for SecurityHeadersMiddleware {
    type Output = SecurityHeadersEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        SecurityHeadersEndpoint(ep)
    }
}

struct SecurityHeadersEndpoint<E>(E);

impl<E: Endpoint> Endpoint for SecurityHeadersEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        let mut resp = self.0.call(req).await?.into_response();
        let h = resp.headers_mut();
        h.insert(
            "X-XSS-Protection",
            HeaderValue::from_static("1; mode=block"),
        );
        h.insert(
            "X-Content-Type-Options",
            HeaderValue::from_static("nosniff"),
        );
        h.insert("X-Frame-Options", HeaderValue::from_static("DENY"));
        h.insert(
            "Strict-Transport-Security",
            HeaderValue::from_static("max-age=31536000; includeSubDomains"),
        );
        h.insert(
            "Content-Security-Policy",
            HeaderValue::from_static("default-src 'self'"),
        );
        h.insert(
            "Referrer-Policy",
            HeaderValue::from_static("strict-origin-when-cross-origin"),
        );
        h.insert(
            "Permissions-Policy",
            HeaderValue::from_static("geolocation=(), microphone=(), camera=()"),
        );
        h.insert(
            "Cross-Origin-Opener-Policy",
            HeaderValue::from_static("same-origin"),
        );
        Ok(resp)
    }
}

// ─── Middleware: Body Limit ───

struct BodyLimitMiddleware {
    max_bytes: usize,
}

impl<E: Endpoint> Middleware<E> for BodyLimitMiddleware {
    type Output = BodyLimitEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        BodyLimitEndpoint {
            inner: ep,
            max_bytes: self.max_bytes,
        }
    }
}

struct BodyLimitEndpoint<E> {
    inner: E,
    max_bytes: usize,
}

impl<E: Endpoint> Endpoint for BodyLimitEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        if let Some(len) = req.header("content-length") {
            if let Ok(n) = len.parse::<usize>() {
                if n > self.max_bytes {
                    let body = serde_json::to_string(&ErrorResponse {
                        error: "Payload too large".to_string(),
                    })
                    .unwrap();
                    return Ok(Response::builder()
                        .status(StatusCode::PAYLOAD_TOO_LARGE)
                        .header("Content-Type", "application/json")
                        .body(body));
                }
            }
        }
        Ok(self.inner.call(req).await?.into_response())
    }
}

// ─── Middleware: Structured Logger ───

struct LoggerMiddleware;

impl<E: Endpoint> Middleware<E> for LoggerMiddleware {
    type Output = LoggerEndpoint<E>;
    fn transform(&self, ep: E) -> Self::Output {
        LoggerEndpoint(ep)
    }
}

struct LoggerEndpoint<E>(E);

impl<E: Endpoint> Endpoint for LoggerEndpoint<E> {
    type Output = Response;

    async fn call(&self, req: Request) -> Result<Self::Output> {
        let start = Instant::now();
        let method = req.method().to_string();
        let path = req.uri().path().to_string();
        let query = req.uri().query().unwrap_or("").to_string();
        let client_ip = extract_client_ip(&req);
        let user_agent = req
            .header("User-Agent")
            .map(|s: &str| s.to_string())
            .unwrap_or_else(|| "-".to_string());
        let request_id = extract_request_id(&req);
        let req_headers = redact_header_map(req.headers());

        let mut resp = self.0.call(req).await?.into_response();
        let latency = start.elapsed();

        let body_bytes = resp.take_body().into_bytes().await.unwrap_or_default();
        let body_str = String::from_utf8_lossy(&body_bytes).to_string();
        let bytes_out = body_bytes.len();

        let resp_headers = redact_response_headers(resp.headers());

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
            "request_headers": req_headers,
            "request_body": "",
            "status": resp.status().as_u16(),
            "latency": format!("{:?}", latency),
            "latency_ms": latency.as_secs_f64() * 1000.0,
            "response_headers": resp_headers,
            "response_body": body_str,
            "bytes_out": bytes_out,
        });
        println!("{}", serde_json::to_string(&log).unwrap());

        // Restore the body
        resp.set_body(body_bytes);

        Ok(resp)
    }
}

// ─── Handlers ───

#[handler]
async fn create_order(
    Path(user_id): Path<String>,
    req: &Request,
    body: Json<OrderRequest>,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);
    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.create_order(&user_id, &items, currency).await {
        Ok(order) => {
            let resp_body = order_to_response(order, request_id.clone());
            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::CREATED)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn update_order(
    Path((user_id, order_id)): Path<(String, String)>,
    req: &Request,
    body: Json<OrderRequest>,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);
    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.update_order(&user_id, &order_id, &items, currency).await {
        Ok(Some(order)) => {
            let resp_body = order_to_response(order, request_id.clone());
            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Ok(None) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: "order not found".to_string(),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::NOT_FOUND)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn delete_order(
    Path((user_id, order_id)): Path<(String, String)>,
    req: &Request,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);

    match state.delete_order(&user_id, &order_id).await {
        Ok(true) => {
            let resp_body = DeleteResponse {
                message: "order deleted".to_string(),
                order_id,
                request_id: request_id.clone(),
            };
            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Ok(false) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: "order not found".to_string(),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::NOT_FOUND)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn get_order(
    Path((user_id, order_id)): Path<(String, String)>,
    Query(query): Query<GetOrderQuery>,
    req: &Request,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);
    let fields = query.fields.unwrap_or_else(|| "*".to_string());

    match state.get_order(&user_id, &order_id).await {
        Ok(Some(order)) => {
            let mut resp_body = order_to_response(order, request_id.clone());
            resp_body.fields = fields;
            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Ok(None) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: "order not found".to_string(),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::NOT_FOUND)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

// ─── Bulk + List + Profile Handlers ───

#[handler]
async fn bulk_create_orders(
    Path(user_id): Path<String>,
    req: &Request,
    body: Json<BulkCreateOrderReq>,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);

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

    match state.bulk_create_orders(&user_id, &inputs).await {
        Ok((orders, total_sum)) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();

            let resp_body = BulkOrderResponse {
                user_id,
                count: results.len(),
                orders: results,
                total_sum,
                request_id: request_id.clone(),
            };

            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::CREATED)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn list_orders(
    Path(user_id): Path<String>,
    req: &Request,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);

    match state.list_orders(&user_id).await {
        Ok(orders) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();

            let resp_body = ListOrdersResponse {
                user_id,
                count: results.len(),
                orders: results,
                request_id: request_id.clone(),
            };

            let json_str = serde_json::to_string(&resp_body).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn put_profile(
    Path(user_id): Path<String>,
    req: &Request,
    body: Json<UserProfile>,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);

    let mut profile = body.0;
    profile.user_id = user_id.clone();
    let pg_profile = user_to_profile(&profile);

    match state.upsert_profile(&user_id, &pg_profile).await {
        Ok(()) => {
            profile.request_id = request_id.clone();
            let json_str = serde_json::to_string(&profile).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

#[handler]
async fn get_profile(
    Path(user_id): Path<String>,
    req: &Request,
    state: poem::web::Data<&Arc<PgStore>>,
) -> Response {
    let request_id = extract_request_id(req);

    match state.get_profile(&user_id).await {
        Ok(Some(profile)) => {
            let user = profile_to_user(profile, request_id.clone());
            let json_str = serde_json::to_string(&user).unwrap();
            Response::builder()
                .status(StatusCode::OK)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Ok(None) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: "profile not found".to_string(),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::NOT_FOUND)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
        Err(e) => {
            let json_str = serde_json::to_string(&ErrorResponse {
                error: format!("db error: {}", e),
            })
            .unwrap();
            Response::builder()
                .status(StatusCode::INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .header("X-Request-ID", request_id)
                .body(json_str)
        }
    }
}

// ─── Main ───

#[tokio::main]
async fn main() -> std::result::Result<(), std::io::Error> {
    let pg_dsn = std::env::var("PG_DSN").expect("PG_DSN environment variable must be set");
    let pg_store = PgStore::new(&pg_dsn)
        .await
        .expect("Failed to connect to PostgreSQL");
    pg_store
        .init_schema()
        .await
        .expect("Failed to initialize schema");

    let state = Arc::new(pg_store);

    let app = Route::new()
        .at("/users/:user_id/orders/bulk", poem::RouteMethod::new().post(bulk_create_orders))
        .at("/users/:user_id/orders", poem::RouteMethod::new().post(create_order).get(list_orders))
        .at(
            "/users/:user_id/orders/:order_id",
            poem::RouteMethod::new()
                .put(update_order)
                .delete(delete_order)
                .get(get_order),
        )
        .at("/users/:user_id/profile", poem::RouteMethod::new().put(put_profile).get(get_profile))
        .data(state)
        .with(LoggerMiddleware)
        .with(BodyLimitMiddleware {
            max_bytes: 1_048_576,
        })
        .with(SecurityHeadersMiddleware)
        .with(CorsMiddleware)
        .with(RequestIdMiddleware)
        .with(RecoveryMiddleware);

    println!("Poem server listening on 0.0.0.0:8115");
    Server::new(poem::listener::TcpListener::bind("0.0.0.0:8115"))
        .run(app)
        .await
}
