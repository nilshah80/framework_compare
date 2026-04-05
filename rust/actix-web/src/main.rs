use actix_web::{
    dev::{Service, ServiceRequest, ServiceResponse, Transform},
    web, App, HttpMessage, HttpRequest, HttpResponse, HttpServer,
};
use pgstore::{BulkOrderInput, PgStore};
use serde::{Deserialize, Serialize};
use std::cell::RefCell;
use std::collections::HashMap;
use std::future::{self, Future, Ready};
use std::pin::Pin;
use std::rc::Rc;
use std::sync::Arc;
use std::task::{Context, Poll};
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
struct DeleteResponse {
    message: String,
    order_id: String,
    request_id: String,
}

#[derive(Serialize)]
struct ErrorResponse {
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

fn redact_headers(
    headers: &actix_web::http::header::HeaderMap,
) -> serde_json::Map<String, serde_json::Value> {
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

fn extract_client_ip(req: &ServiceRequest) -> String {
    // Parse X-Forwarded-For like Aarv's RealIP: take first IP
    if let Some(xff) = req.headers().get("x-forwarded-for") {
        if let Ok(val) = xff.to_str() {
            let first = val.split(',').next().unwrap_or("").trim();
            if !first.is_empty() {
                return first.to_string();
            }
        }
    }
    req.connection_info()
        .peer_addr()
        .unwrap_or("unknown")
        .to_string()
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

// ─── Recovery Middleware ───

struct Recovery;

impl<S, B> Transform<S, ServiceRequest> for Recovery
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Transform = RecoveryMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(RecoveryMiddleware {
            service: Rc::new(RefCell::new(service)),
        }))
    }
}

struct RecoveryMiddleware<S> {
    service: Rc<RefCell<S>>,
}

impl<S, B> Service<ServiceRequest> for RecoveryMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        // Get the future while borrowed, then drop the borrow before awaiting.
        // This prevents RefCell panics under concurrent load.
        let fut = self.service.borrow_mut().call(req);
        Box::pin(async move { fut.await })
    }
}

// ─── Request ID Middleware ───

struct RequestIdMw;

impl<S, B> Transform<S, ServiceRequest> for RequestIdMw
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Transform = RequestIdMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(RequestIdMiddleware {
            service: Rc::new(RefCell::new(service)),
        }))
    }
}

struct RequestIdMiddleware<S> {
    service: Rc<RefCell<S>>,
}

impl<S, B> Service<ServiceRequest> for RequestIdMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let request_id = req
            .headers()
            .get("X-Request-ID")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_else(generate_uuid);

        req.extensions_mut().insert(request_id.clone());

        let svc = self.service.clone();
        Box::pin(async move {
            let fut = svc.borrow_mut().call(req);
            let mut res = fut.await?;
            res.headers_mut().insert(
                actix_web::http::header::HeaderName::from_static("x-request-id"),
                actix_web::http::header::HeaderValue::from_str(&request_id).unwrap(),
            );
            Ok(res)
        })
    }
}

// ─── CORS Middleware ───

struct CorsMw;

impl<S> Transform<S, ServiceRequest> for CorsMw
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Transform = CorsMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(CorsMiddleware {
            service: Rc::new(RefCell::new(service)),
        }))
    }
}

struct CorsMiddleware<S> {
    service: Rc<RefCell<S>>,
}

impl<S> Service<ServiceRequest> for CorsMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let is_options = req.method() == actix_web::http::Method::OPTIONS;
        let svc = self.service.clone();

        Box::pin(async move {
            if is_options {
                let resp = HttpResponse::NoContent().finish();
                let mut sr = req.into_response(resp);
                insert_cors_headers(sr.headers_mut());
                Ok(sr)
            } else {
                let fut = svc.borrow_mut().call(req);
            let mut res = fut.await?;
                insert_cors_headers(res.headers_mut());
                Ok(res)
            }
        })
    }
}

fn insert_cors_headers(headers: &mut actix_web::http::header::HeaderMap) {
    use actix_web::http::header::{HeaderName, HeaderValue};
    headers.insert(
        HeaderName::from_static("access-control-allow-origin"),
        HeaderValue::from_static("*"),
    );
    headers.insert(
        HeaderName::from_static("access-control-allow-methods"),
        HeaderValue::from_static("GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"),
    );
    headers.insert(
        HeaderName::from_static("access-control-allow-headers"),
        HeaderValue::from_static("Origin, Content-Type, Accept, Authorization"),
    );
}

// ─── Security Headers Middleware ───

struct SecurityHeaders;

impl<S, B> Transform<S, ServiceRequest> for SecurityHeaders
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Transform = SecurityHeadersMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(SecurityHeadersMiddleware {
            service: Rc::new(RefCell::new(service)),
        }))
    }
}

struct SecurityHeadersMiddleware<S> {
    service: Rc<RefCell<S>>,
}

impl<S, B> Service<ServiceRequest> for SecurityHeadersMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = actix_web::Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let svc = self.service.clone();
        Box::pin(async move {
            let fut = svc.borrow_mut().call(req);
            let mut res = fut.await?;
            let headers = res.headers_mut();
            use actix_web::http::header::{HeaderName, HeaderValue};
            headers.insert(
                HeaderName::from_static("x-xss-protection"),
                HeaderValue::from_static("1; mode=block"),
            );
            headers.insert(
                HeaderName::from_static("x-content-type-options"),
                HeaderValue::from_static("nosniff"),
            );
            headers.insert(
                HeaderName::from_static("x-frame-options"),
                HeaderValue::from_static("DENY"),
            );
            headers.insert(
                HeaderName::from_static("strict-transport-security"),
                HeaderValue::from_static("max-age=31536000; includeSubDomains"),
            );
            headers.insert(
                HeaderName::from_static("content-security-policy"),
                HeaderValue::from_static("default-src 'self'"),
            );
            headers.insert(
                HeaderName::from_static("referrer-policy"),
                HeaderValue::from_static("strict-origin-when-cross-origin"),
            );
            headers.insert(
                HeaderName::from_static("permissions-policy"),
                HeaderValue::from_static("geolocation=(), microphone=(), camera=()"),
            );
            headers.insert(
                HeaderName::from_static("cross-origin-opener-policy"),
                HeaderValue::from_static("same-origin"),
            );
            Ok(res)
        })
    }
}

// ─── Body Limit Middleware ───

struct BodyLimit {
    max_bytes: u64,
}

impl<S> Transform<S, ServiceRequest> for BodyLimit
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Transform = BodyLimitMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(BodyLimitMiddleware {
            service: Rc::new(RefCell::new(service)),
            max_bytes: self.max_bytes,
        }))
    }
}

struct BodyLimitMiddleware<S> {
    service: Rc<RefCell<S>>,
    max_bytes: u64,
}

impl<S> Service<ServiceRequest> for BodyLimitMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let content_length = req
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0);

        let max = self.max_bytes;
        let svc = self.service.clone();

        Box::pin(async move {
            if content_length > max {
                let resp = HttpResponse::PayloadTooLarge()
                    .json(serde_json::json!({"error": "request body too large"}));
                Ok(req.into_response(resp))
            } else {
                { let fut = svc.borrow_mut().call(req); fut.await }
            }
        })
    }
}

// ─── Structured Logger Middleware ───

struct StructuredLogger;

impl<S> Transform<S, ServiceRequest> for StructuredLogger
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Transform = StructuredLoggerMiddleware<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        future::ready(Ok(StructuredLoggerMiddleware {
            service: Rc::new(RefCell::new(service)),
        }))
    }
}

struct StructuredLoggerMiddleware<S> {
    service: Rc<RefCell<S>>,
}

impl<S> Service<ServiceRequest> for StructuredLoggerMiddleware<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<actix_web::body::BoxBody>, Error = actix_web::Error>
        + 'static,
{
    type Response = ServiceResponse<actix_web::body::BoxBody>;
    type Error = actix_web::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>>>>;

    fn poll_ready(&self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.service.borrow_mut().poll_ready(cx)
    }

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let start = Instant::now();
        let method = req.method().to_string();
        let path = req.path().to_string();
        let query_string = req.query_string().to_string();
        let client_ip = extract_client_ip(&req);
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
        let req_headers = redact_headers(req.headers());

        // Capture request body bytes
        let request_body_str = String::new(); // actix doesn't easily expose body before handler

        let svc = self.service.clone();

        Box::pin(async move {
            let fut = svc.borrow_mut().call(req);
            let res = fut.await?;

            let status = res.status().as_u16();
            let latency = start.elapsed();
            let latency_ms = latency.as_secs_f64() * 1000.0;

            // Capture response headers before consuming body
            let resp_headers = {
                let mut map = serde_json::Map::new();
                for (key, value) in res.headers().iter() {
                    let k = key.as_str();
                    let v = if is_sensitive(k) {
                        "[REDACTED]".to_string()
                    } else {
                        value.to_str().unwrap_or("").to_string()
                    };
                    map.insert(k.to_string(), serde_json::Value::String(v));
                }
                map
            };

            // Extract body bytes for logging
            let res = res.map_into_boxed_body();
            let (parts, body) = res.into_parts();
            let body_bytes = actix_web::body::to_bytes(body.into_body()).await.unwrap_or_default();
            let response_body = String::from_utf8_lossy(&body_bytes).to_string();
            let bytes_out = body_bytes.len();

            // Parse query params
            let query: serde_json::Map<String, serde_json::Value> = if query_string.is_empty() {
                serde_json::Map::new()
            } else {
                query_string
                    .split('&')
                    .filter_map(|p| {
                        let mut parts = p.splitn(2, '=');
                        let k = parts.next()?;
                        let v = parts.next().unwrap_or("");
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
                "request_body": request_body_str,
                "status": status,
                "latency": format!("{latency:?}"),
                "latency_ms": latency_ms,
                "response_headers": resp_headers,
                "response_body": response_body,
                "bytes_out": bytes_out,
            });
            println!("{}", serde_json::to_string(&log_entry).unwrap_or_default());

            // Reconstruct response with captured body
            let new_resp = HttpResponse::build(
                actix_web::http::StatusCode::from_u16(status).unwrap(),
            )
            .body(body_bytes.to_vec());

            // Re-add response headers
            let mut sr = ServiceResponse::new(parts, new_resp);
            for (k, v) in &resp_headers {
                if let serde_json::Value::String(val) = v {
                    if let (Ok(hn), Ok(hv)) = (
                        actix_web::http::header::HeaderName::from_bytes(k.as_bytes()),
                        actix_web::http::header::HeaderValue::from_str(val),
                    ) {
                        sr.headers_mut().insert(hn, hv);
                    }
                }
            }

            Ok(sr)
        })
    }
}

// ─── Handlers ───

fn get_request_id(req: &HttpRequest) -> String {
    req.extensions()
        .get::<String>()
        .cloned()
        .unwrap_or_default()
}

#[derive(Deserialize)]
struct UserPath {
    #[serde(rename = "userId")]
    user_id: String,
}

#[derive(Deserialize)]
struct OrderPath {
    #[serde(rename = "userId")]
    user_id: String,
    #[serde(rename = "orderId")]
    order_id: String,
}

#[derive(Deserialize)]
struct GetOrderQuery {
    fields: Option<String>,
}

async fn create_order(
    path: web::Path<UserPath>,
    body: web::Json<CreateOrderReq>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let request_id = get_request_id(&req);

    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.store.create_order(user_id, &items, currency).await {
        Ok(order) => {
            let resp = order_to_response(order, request_id);
            HttpResponse::Created().json(resp)
        }
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn update_order(
    path: web::Path<OrderPath>,
    body: web::Json<CreateOrderReq>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let order_id = &path.order_id;
    let request_id = get_request_id(&req);

    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.store.update_order(user_id, order_id, &items, currency).await {
        Ok(Some(order)) => {
            let resp = order_to_response(order, request_id);
            HttpResponse::Ok().json(resp)
        }
        Ok(None) => HttpResponse::NotFound().json(ErrorResponse {
            error: "order not found".to_string(),
            order_id: Some(order_id.clone()),
            request_id: Some(request_id),
        }),
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn delete_order(
    path: web::Path<OrderPath>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let order_id = &path.order_id;
    let request_id = get_request_id(&req);

    match state.store.delete_order(user_id, order_id).await {
        Ok(true) => HttpResponse::Ok().json(DeleteResponse {
            message: "order deleted".to_string(),
            order_id: order_id.clone(),
            request_id,
        }),
        Ok(false) => HttpResponse::NotFound().json(ErrorResponse {
            error: "order not found".to_string(),
            order_id: Some(order_id.clone()),
            request_id: Some(request_id),
        }),
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn get_order(
    path: web::Path<OrderPath>,
    query: web::Query<GetOrderQuery>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let order_id = &path.order_id;
    let request_id = get_request_id(&req);
    let fields = query.fields.clone().unwrap_or_else(|| "*".to_string());

    match state.store.get_order(user_id, order_id).await {
        Ok(Some(order)) => {
            let mut resp = order_to_response(order, request_id);
            resp.fields = Some(fields);
            HttpResponse::Ok().json(resp)
        }
        Ok(None) => HttpResponse::NotFound().json(ErrorResponse {
            error: "order not found".to_string(),
            order_id: Some(order_id.clone()),
            request_id: Some(request_id),
        }),
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

// ─── Bulk + List + Profile Handlers ───

async fn bulk_create_orders(
    path: web::Path<UserPath>,
    body: web::Json<BulkCreateOrderReq>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let request_id = get_request_id(&req);

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

    match state.store.bulk_create_orders(user_id, &inputs).await {
        Ok((orders, total_sum)) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();
            HttpResponse::Created().json(BulkOrderResponse {
                user_id: user_id.clone(),
                count: results.len(),
                orders: results,
                total_sum,
                request_id,
            })
        }
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn list_orders(
    path: web::Path<UserPath>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let request_id = get_request_id(&req);

    match state.store.list_orders(user_id).await {
        Ok(orders) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, request_id.clone()))
                .collect();
            HttpResponse::Ok().json(ListOrdersResponse {
                user_id: user_id.clone(),
                count: results.len(),
                orders: results,
                request_id,
            })
        }
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn put_profile(
    path: web::Path<UserPath>,
    body: web::Json<UserProfile>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let request_id = get_request_id(&req);

    let mut profile = body.into_inner();
    profile.user_id = user_id.clone();

    let pg_profile = user_to_profile(&profile);

    match state.store.upsert_profile(user_id, &pg_profile).await {
        Ok(()) => {
            profile.request_id = request_id;
            HttpResponse::Ok().json(profile)
        }
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

async fn get_profile(
    path: web::Path<UserPath>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let request_id = get_request_id(&req);

    match state.store.get_profile(user_id).await {
        Ok(Some(profile)) => {
            let user = profile_to_user(profile, request_id);
            HttpResponse::Ok().json(user)
        }
        Ok(None) => HttpResponse::NotFound().json(ErrorResponse {
            error: "profile not found".to_string(),
            order_id: None,
            request_id: None,
        }),
        Err(e) => HttpResponse::InternalServerError().json(ErrorResponse {
            error: format!("db error: {}", e),
            order_id: None,
            request_id: Some(request_id),
        }),
    }
}

// ─── Main ───

#[tokio::main]
async fn main() -> std::io::Result<()> {
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
        "port": "8111",
    });
    println!("{}", serde_json::to_string(&log_entry).unwrap_or_default());

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(state.clone()))
            .app_data(web::JsonConfig::default().limit(1_048_576))
            // Middleware: outermost .wrap() runs first on request
            // Order: Recovery -> RequestID -> CORS -> Security -> BodyLimit -> Logger -> Handler
            // actix .wrap() wraps outside, so first .wrap = outermost
            .wrap(StructuredLogger)
            .wrap(BodyLimit { max_bytes: 1_048_576 })
            .wrap(SecurityHeaders)
            .wrap(CorsMw)
            .wrap(RequestIdMw)
            .wrap(Recovery)
            .route(
                "/users/{userId}/orders/bulk",
                web::post().to(bulk_create_orders),
            )
            .route(
                "/users/{userId}/orders",
                web::post().to(create_order),
            )
            .route(
                "/users/{userId}/orders",
                web::get().to(list_orders),
            )
            .route(
                "/users/{userId}/orders/{orderId}",
                web::put().to(update_order),
            )
            .route(
                "/users/{userId}/orders/{orderId}",
                web::delete().to(delete_order),
            )
            .route(
                "/users/{userId}/orders/{orderId}",
                web::get().to(get_order),
            )
            .route(
                "/users/{userId}/profile",
                web::put().to(put_profile),
            )
            .route(
                "/users/{userId}/profile",
                web::get().to(get_profile),
            )
    })
    .bind("0.0.0.0:8111")?
    .run()
    .await
}
