use pgstore::{BulkOrderInput, PgStore};
use rocket::fairing::{Fairing, Info, Kind};
use rocket::http::{Header, Method, Status};
use rocket::request::{FromRequest, Outcome, Request};
use rocket::response::{self, Responder, Response};
use rocket::serde::json::Json;
use rocket::{catch, catchers, launch, post, put, delete, get, routes, Build, Rocket, State};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Cursor;
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
    address: Address,
    #[serde(default)]
    preferences: ProfilePreferences,
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
struct ProfilePreferences {
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
        address: Address {
            street: p.address.street,
            city: p.address.city,
            state: p.address.state,
            zip: p.address.zip,
            country: p.address.country,
        },
        preferences: ProfilePreferences {
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

// ─── Request ID guard ───

struct ReqId(String);

#[rocket::async_trait]
impl<'r> FromRequest<'r> for ReqId {
    type Error = ();
    async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
        let id = req
            .headers()
            .get_one("X-Request-ID")
            .map(|s| s.to_string())
            .unwrap_or_else(|| Uuid::new_v4().to_string());
        Outcome::Success(ReqId(id))
    }
}

// ─── Custom JSON responder that captures body for logging ───

struct JsonBody<T: Serialize> {
    status: Status,
    body: T,
    request_id: String,
}

impl<'r, T: Serialize> Responder<'r, 'static> for JsonBody<T> {
    fn respond_to(self, _req: &'r Request<'_>) -> response::Result<'static> {
        let json_str = serde_json::to_string(&self.body).unwrap();
        let len = json_str.len();
        Response::build()
            .status(self.status)
            .header(Header::new("Content-Type", "application/json"))
            .header(Header::new("X-Request-ID", self.request_id))
            .header(Header::new("X-Response-Body-Len", len.to_string()))
            .sized_body(json_str.len(), Cursor::new(json_str))
            .ok()
    }
}

// ─── Fairings (middleware) ───

// CORS Fairing
struct CorsFairing;

#[rocket::async_trait]
impl Fairing for CorsFairing {
    fn info(&self) -> Info {
        Info {
            name: "CORS",
            kind: Kind::Response,
        }
    }
    async fn on_response<'r>(&self, request: &'r Request<'_>, response: &mut Response<'r>) {
        response.set_header(Header::new("Access-Control-Allow-Origin", "*"));
        response.set_header(Header::new(
            "Access-Control-Allow-Methods",
            "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS",
        ));
        response.set_header(Header::new(
            "Access-Control-Allow-Headers",
            "Origin,Content-Type,Accept,Authorization",
        ));
        if request.method() == Method::Options {
            response.set_status(Status::Ok);
        }
    }
}

// Security Headers Fairing
struct SecurityHeadersFairing;

#[rocket::async_trait]
impl Fairing for SecurityHeadersFairing {
    fn info(&self) -> Info {
        Info {
            name: "Security Headers",
            kind: Kind::Response,
        }
    }
    async fn on_response<'r>(&self, _req: &'r Request<'_>, response: &mut Response<'r>) {
        response.set_header(Header::new("X-XSS-Protection", "1; mode=block"));
        response.set_header(Header::new("X-Content-Type-Options", "nosniff"));
        response.set_header(Header::new("X-Frame-Options", "DENY"));
        response.set_header(Header::new(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        ));
        response.set_header(Header::new(
            "Content-Security-Policy",
            "default-src 'self'",
        ));
        response.set_header(Header::new(
            "Referrer-Policy",
            "strict-origin-when-cross-origin",
        ));
        response.set_header(Header::new(
            "Permissions-Policy",
            "geolocation=(), microphone=(), camera=()",
        ));
        response.set_header(Header::new("Cross-Origin-Opener-Policy", "same-origin"));
    }
}

// Structured Logger Fairing
struct LoggerFairing;

const PII_HEADERS: &[&str] = &[
    "authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-auth-token",
];

fn redact_headers(headers: &[(&str, &str)]) -> serde_json::Value {
    let map: serde_json::Map<String, serde_json::Value> = headers
        .iter()
        .map(|(k, v)| {
            let key = k.to_lowercase();
            let val = if PII_HEADERS.contains(&key.as_str()) {
                "[REDACTED]".to_string()
            } else {
                v.to_string()
            };
            (k.to_string(), serde_json::Value::String(val))
        })
        .collect();
    serde_json::Value::Object(map)
}

fn extract_client_ip(req: &Request<'_>) -> String {
    if let Some(xff) = req.headers().get_one("X-Forwarded-For") {
        let first = xff.split(',').next().unwrap_or("").trim();
        if !first.is_empty() {
            return first.to_string();
        }
    }
    req.client_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|| "-".to_string())
}

#[rocket::async_trait]
impl Fairing for LoggerFairing {
    fn info(&self) -> Info {
        Info {
            name: "Structured Logger",
            kind: Kind::Request | Kind::Response,
        }
    }

    async fn on_request(&self, req: &mut Request<'_>, _data: &mut rocket::Data<'_>) {
        req.local_cache(|| Instant::now());
    }

    async fn on_response<'r>(&self, req: &'r Request<'_>, response: &mut Response<'r>) {
        let start = req.local_cache(|| Instant::now());
        let latency = start.elapsed();

        let request_id = req
            .headers()
            .get_one("X-Request-ID")
            .or_else(|| response.headers().get_one("X-Request-ID"))
            .unwrap_or("-")
            .to_string();

        let client_ip = extract_client_ip(req);

        let req_headers_owned: Vec<(String, String)> = req
            .headers()
            .iter()
            .map(|h| (h.name().as_str().to_string(), h.value().to_string()))
            .collect();
        let req_headers: Vec<(&str, &str)> = req_headers_owned
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();

        let resp_headers: Vec<(String, String)> = response
            .headers()
            .iter()
            .map(|h| (h.name().as_str().to_string(), h.value().to_string()))
            .collect();

        let body_str = response
            .body_mut()
            .to_string()
            .await
            .unwrap_or_default();
        let bytes_out = body_str.len();

        // Re-set the body since we consumed it
        response.set_sized_body(body_str.len(), Cursor::new(body_str.clone()));

        let resp_headers_ref: Vec<(&str, &str)> = resp_headers
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();

        let query_string = req.uri().query().map(|q| q.to_string()).unwrap_or_default();
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

        let log = serde_json::json!({
            "level": "INFO",
            "message": "http_dump",
            "request_id": request_id,
            "method": req.method().as_str(),
            "path": req.uri().path().to_string(),
            "query": query,
            "client_ip": client_ip,
            "user_agent": req.headers().get_one("User-Agent").unwrap_or("-"),
            "request_headers": redact_headers(&req_headers),
            "request_body": "",
            "status": response.status().code,
            "latency": format!("{:?}", latency),
            "latency_ms": latency.as_secs_f64() * 1000.0,
            "response_headers": redact_headers(&resp_headers_ref),
            "response_body": body_str,
            "bytes_out": bytes_out,
        });
        println!("{}", serde_json::to_string(&log).unwrap());
    }
}

// ─── Catchers (recovery / error handling) ───

#[catch(500)]
fn internal_error() -> Json<ErrorResponse> {
    Json(ErrorResponse {
        error: "Internal Server Error".to_string(),
    })
}

#[catch(413)]
fn payload_too_large() -> Json<ErrorResponse> {
    Json(ErrorResponse {
        error: "Payload too large".to_string(),
    })
}

#[catch(404)]
fn not_found() -> Json<ErrorResponse> {
    Json(ErrorResponse {
        error: "not found".to_string(),
    })
}

// ─── Routes ───

#[post("/users/<user_id>/orders", format = "json", data = "<body>")]
async fn create_order(
    user_id: &str,
    body: Json<OrderRequest>,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.create_order(user_id, &items, currency).await {
        Ok(order) => {
            let resp = order_to_response(order, req_id.0.clone());
            JsonBody {
                status: Status::Created,
                request_id: req_id.0,
                body: serde_json::to_value(resp).unwrap(),
            }
        }
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[put("/users/<user_id>/orders/<order_id>", format = "json", data = "<body>")]
async fn update_order(
    user_id: &str,
    order_id: &str,
    body: Json<OrderRequest>,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    let items = to_pgstore_items(&body.items);
    let currency = if body.currency.is_empty() { "USD" } else { &body.currency };

    match state.update_order(user_id, order_id, &items, currency).await {
        Ok(Some(order)) => {
            let resp = order_to_response(order, req_id.0.clone());
            JsonBody {
                status: Status::Ok,
                request_id: req_id.0,
                body: serde_json::to_value(resp).unwrap(),
            }
        }
        Ok(None) => JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": "order not found"}),
        },
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[delete("/users/<user_id>/orders/<order_id>")]
async fn delete_order(
    user_id: &str,
    order_id: &str,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    match state.delete_order(user_id, order_id).await {
        Ok(true) => JsonBody {
            status: Status::Ok,
            request_id: req_id.0.clone(),
            body: serde_json::to_value(DeleteResponse {
                message: "order deleted".to_string(),
                order_id: order_id.to_string(),
                request_id: req_id.0,
            })
            .unwrap(),
        },
        Ok(false) => JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": "order not found"}),
        },
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[get("/users/<user_id>/orders/<order_id>?<fields>")]
async fn get_order(
    user_id: &str,
    order_id: &str,
    fields: Option<&str>,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    match state.get_order(user_id, order_id).await {
        Ok(Some(order)) => {
            let mut resp = order_to_response(order, req_id.0.clone());
            resp.fields = fields.unwrap_or("*").to_string();
            JsonBody {
                status: Status::Ok,
                request_id: req_id.0,
                body: serde_json::to_value(resp).unwrap(),
            }
        }
        Ok(None) => JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": "order not found"}),
        },
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

// ─── Bulk + List + Profile Routes ───

#[post("/users/<user_id>/orders/bulk", format = "json", data = "<body>")]
async fn bulk_create_orders(
    user_id: &str,
    body: Json<BulkCreateOrderReq>,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
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

    match state.bulk_create_orders(user_id, &inputs).await {
        Ok((orders, total_sum)) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, req_id.0.clone()))
                .collect();
            JsonBody {
                status: Status::Created,
                request_id: req_id.0.clone(),
                body: serde_json::to_value(BulkOrderResponse {
                    user_id: user_id.to_string(),
                    count: results.len(),
                    orders: results,
                    total_sum,
                    request_id: req_id.0,
                })
                .unwrap(),
            }
        }
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[get("/users/<user_id>/orders")]
async fn list_orders(
    user_id: &str,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    match state.list_orders(user_id).await {
        Ok(orders) => {
            let results: Vec<OrderResponse> = orders
                .into_iter()
                .map(|o| order_to_response(o, req_id.0.clone()))
                .collect();
            JsonBody {
                status: Status::Ok,
                request_id: req_id.0.clone(),
                body: serde_json::to_value(ListOrdersResponse {
                    user_id: user_id.to_string(),
                    count: results.len(),
                    orders: results,
                    request_id: req_id.0,
                })
                .unwrap(),
            }
        }
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[put("/users/<user_id>/profile", format = "json", data = "<body>")]
async fn put_profile(
    user_id: &str,
    body: Json<UserProfile>,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    let mut profile = body.into_inner();
    profile.user_id = user_id.to_string();

    let pg_profile = user_to_profile(&profile);

    match state.upsert_profile(user_id, &pg_profile).await {
        Ok(()) => {
            profile.request_id = req_id.0.clone();
            JsonBody {
                status: Status::Ok,
                request_id: req_id.0,
                body: serde_json::to_value(profile).unwrap(),
            }
        }
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

#[get("/users/<user_id>/profile")]
async fn get_profile(
    user_id: &str,
    state: &State<PgStore>,
    req_id: ReqId,
) -> JsonBody<serde_json::Value> {
    match state.get_profile(user_id).await {
        Ok(Some(profile)) => {
            let user = profile_to_user(profile, req_id.0.clone());
            JsonBody {
                status: Status::Ok,
                request_id: req_id.0,
                body: serde_json::to_value(user).unwrap(),
            }
        }
        Ok(None) => JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": "profile not found"}),
        },
        Err(e) => JsonBody {
            status: Status::InternalServerError,
            request_id: req_id.0.clone(),
            body: serde_json::json!({"error": format!("db error: {}", e)}),
        },
    }
}

// ─── Launch ───

#[launch]
async fn rocket() -> Rocket<Build> {
    let pg_dsn = std::env::var("PG_DSN").expect("PG_DSN environment variable must be set");
    let pg_store = PgStore::new(&pg_dsn)
        .await
        .expect("Failed to connect to PostgreSQL");
    pg_store
        .init_schema()
        .await
        .expect("Failed to initialize schema");

    rocket::build()
        .manage(pg_store)
        .attach(CorsFairing)
        .attach(SecurityHeadersFairing)
        .attach(LoggerFairing)
        .mount(
            "/",
            routes![
                create_order,
                update_order,
                delete_order,
                get_order,
                bulk_create_orders,
                list_orders,
                put_profile,
                get_profile
            ],
        )
        .register("/", catchers![internal_error, payload_too_large, not_found])
}
