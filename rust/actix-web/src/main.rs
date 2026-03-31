use actix_web::{
    dev::{Service, ServiceRequest, ServiceResponse, Transform},
    web, App, HttpMessage, HttpRequest, HttpResponse, HttpServer,
};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::cell::RefCell;
use std::future::{self, Future, Ready};
use std::pin::Pin;
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
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
        let svc = self.service.clone();
        Box::pin(async move {
            let res = svc.borrow_mut().call(req).await;
            match res {
                Ok(resp) => Ok(resp),
                Err(e) => Err(e),
            }
        })
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
            let mut res = svc.borrow_mut().call(req).await?;
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
                let mut res = svc.borrow_mut().call(req).await?;
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
            let mut res = svc.borrow_mut().call(req).await?;
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
                svc.borrow_mut().call(req).await
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
        let client_ip = req
            .connection_info()
            .peer_addr()
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
        let req_headers = redact_headers(req.headers());

        let svc = self.service.clone();

        Box::pin(async move {
            let res = svc.borrow_mut().call(req).await?;

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
                    // Use original (non-redacted) values from the actual response
                    // resp_headers already has correct values
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
    let order_id = state.next_order_id();
    let request_id = get_request_id(&req);

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
        items: body.items.clone(),
        total,
        currency,
        fields: None,
        request_id,
    };

    state
        .store
        .insert(AppState::store_key(user_id, &order_id), order.clone());

    HttpResponse::Created().json(order)
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
    let key = AppState::store_key(user_id, order_id);

    let mut entry = match state.store.get_mut(&key) {
        Some(e) => e,
        None => {
            return HttpResponse::NotFound().json(ErrorResponse {
                error: "order not found".to_string(),
                order_id: Some(order_id.clone()),
                request_id: Some(request_id),
            });
        }
    };

    let mut total = 0.0;
    for item in &body.items {
        total += item.price * item.quantity as f64;
    }

    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency.clone()
    };

    entry.items = body.items.clone();
    entry.total = total;
    entry.currency = currency;
    entry.status = "updated".to_string();
    entry.request_id = request_id;

    let order = entry.clone();
    drop(entry);

    HttpResponse::Ok().json(order)
}

async fn delete_order(
    path: web::Path<OrderPath>,
    state: web::Data<Arc<AppState>>,
    req: HttpRequest,
) -> HttpResponse {
    let user_id = &path.user_id;
    let order_id = &path.order_id;
    let request_id = get_request_id(&req);
    let key = AppState::store_key(user_id, order_id);

    match state.store.remove(&key) {
        Some(_) => HttpResponse::Ok().json(DeleteResponse {
            message: "order deleted".to_string(),
            order_id: order_id.clone(),
            request_id,
        }),
        None => HttpResponse::NotFound().json(ErrorResponse {
            error: "order not found".to_string(),
            order_id: Some(order_id.clone()),
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
    let key = AppState::store_key(user_id, order_id);

    match state.store.get(&key) {
        Some(entry) => {
            let mut order = entry.clone();
            order.fields = Some(fields);
            order.request_id = request_id;
            HttpResponse::Ok().json(order)
        }
        None => HttpResponse::NotFound().json(ErrorResponse {
            error: "order not found".to_string(),
            order_id: Some(order_id.clone()),
            request_id: Some(request_id),
        }),
    }
}

// ─── Main ───

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let state = Arc::new(AppState::new());

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
            // Order: Recovery → RequestID → CORS → Security → BodyLimit → Logger → Handler
            // actix .wrap() wraps outside, so first .wrap = outermost
            .wrap(StructuredLogger)
            .wrap(BodyLimit { max_bytes: 1_048_576 })
            .wrap(SecurityHeaders)
            .wrap(CorsMw)
            .wrap(RequestIdMw)
            .wrap(Recovery)
            .route(
                "/users/{userId}/orders",
                web::post().to(create_order),
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
    })
    .bind("0.0.0.0:8111")?
    .run()
    .await
}
