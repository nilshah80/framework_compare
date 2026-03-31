use dashmap::DashMap;
use rocket::fairing::{Fairing, Info, Kind};
use rocket::http::{Header, Method, Status};
use rocket::request::{FromRequest, Outcome, Request};
use rocket::response::{self, Responder, Response};
use rocket::serde::json::Json;
use rocket::{catch, catchers, launch, post, put, delete, get, routes, Build, Rocket, State};
use serde::{Deserialize, Serialize};
use std::io::Cursor;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;
use uuid::Uuid;

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

// ─── Store ───

struct AppState {
    store: DashMap<String, StoredOrder>,
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
            "client_ip": req.client_ip().map(|ip| ip.to_string()).unwrap_or("-".to_string()),
            "user_agent": req.headers().get_one("User-Agent").unwrap_or("-"),
            "request_headers": redact_headers(&req_headers),
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
fn create_order(
    user_id: &str,
    body: Json<OrderRequest>,
    state: &State<AppState>,
    req_id: ReqId,
) -> JsonBody<OrderResponse> {
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
        user_id: user_id.to_string(),
        items: body.items.clone(),
        total,
        currency: currency.clone(),
    };
    state
        .store
        .insert(format!("{}:{}", user_id, order_id), stored);

    JsonBody {
        status: Status::Created,
        request_id: req_id.0.clone(),
        body: OrderResponse {
            order_id,
            user_id: user_id.to_string(),
            status: "created".to_string(),
            items: body.items.clone(),
            total,
            currency,
            fields: "*".to_string(),
            request_id: req_id.0,
        },
    }
}

#[put("/users/<user_id>/orders/<order_id>", format = "json", data = "<body>")]
fn update_order(
    user_id: &str,
    order_id: &str,
    body: Json<OrderRequest>,
    state: &State<AppState>,
    req_id: ReqId,
) -> Result<JsonBody<OrderResponse>, JsonBody<ErrorResponse>> {
    let key = format!("{}:{}", user_id, order_id);
    if !state.store.contains_key(&key) {
        return Err(JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: ErrorResponse {
                error: "order not found".to_string(),
            },
        });
    }

    let total: f64 = body.items.iter().map(|i| i.price * i.quantity as f64).sum();
    let currency = if body.currency.is_empty() {
        "USD".to_string()
    } else {
        body.currency.clone()
    };

    let stored = StoredOrder {
        order_id: order_id.to_string(),
        user_id: user_id.to_string(),
        items: body.items.clone(),
        total,
        currency: currency.clone(),
    };
    state.store.insert(key, stored);

    Ok(JsonBody {
        status: Status::Ok,
        request_id: req_id.0.clone(),
        body: OrderResponse {
            order_id: order_id.to_string(),
            user_id: user_id.to_string(),
            status: "updated".to_string(),
            items: body.items.clone(),
            total,
            currency,
            fields: "*".to_string(),
            request_id: req_id.0,
        },
    })
}

#[delete("/users/<user_id>/orders/<order_id>")]
fn delete_order(
    user_id: &str,
    order_id: &str,
    state: &State<AppState>,
    req_id: ReqId,
) -> Result<JsonBody<DeleteResponse>, JsonBody<ErrorResponse>> {
    let key = format!("{}:{}", user_id, order_id);
    match state.store.remove(&key) {
        Some(_) => Ok(JsonBody {
            status: Status::Ok,
            request_id: req_id.0.clone(),
            body: DeleteResponse {
                message: "order deleted".to_string(),
                order_id: order_id.to_string(),
                request_id: req_id.0,
            },
        }),
        None => Err(JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: ErrorResponse {
                error: "order not found".to_string(),
            },
        }),
    }
}

#[get("/users/<user_id>/orders/<order_id>?<fields>")]
fn get_order(
    user_id: &str,
    order_id: &str,
    fields: Option<&str>,
    state: &State<AppState>,
    req_id: ReqId,
) -> Result<JsonBody<OrderResponse>, JsonBody<ErrorResponse>> {
    let key = format!("{}:{}", user_id, order_id);
    match state.store.get(&key) {
        Some(entry) => {
            let stored = entry.value();
            Ok(JsonBody {
                status: Status::Ok,
                request_id: req_id.0.clone(),
                body: OrderResponse {
                    order_id: stored.order_id.clone(),
                    user_id: stored.user_id.clone(),
                    status: "created".to_string(),
                    items: stored.items.clone(),
                    total: stored.total,
                    currency: stored.currency.clone(),
                    fields: fields.unwrap_or("*").to_string(),
                    request_id: req_id.0,
                },
            })
        }
        None => Err(JsonBody {
            status: Status::NotFound,
            request_id: req_id.0.clone(),
            body: ErrorResponse {
                error: "order not found".to_string(),
            },
        }),
    }
}

// ─── Launch ───

#[launch]
fn rocket() -> Rocket<Build> {
    let state = AppState {
        store: DashMap::new(),
        next_id: AtomicU64::new(1),
    };

    rocket::build()
        .manage(state)
        .attach(CorsFairing)
        .attach(SecurityHeadersFairing)
        .attach(LoggerFairing)
        .mount(
            "/",
            routes![create_order, update_order, delete_order, get_order],
        )
        .register("/", catchers![internal_error, payload_too_large, not_found])
}
