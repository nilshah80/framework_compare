# Web Framework Benchmark

A comprehensive benchmark comparing **27 web frameworks** across **5 languages** (Go, Rust, Java, .NET, Node.js) using identical API endpoints, middleware stacks, and PostgreSQL-backed storage.

## Frameworks

### Go (8 frameworks)
| Framework | Port | Type |
|-----------|:----:|------|
| Aarv | 8081 | Plugin-based, declarative binding |
| Gin | 8082 | Radix tree router |
| Fiber | 8083 | fasthttp-based |
| Echo | 8084 | High performance, minimalist |
| Mach | 8085 | Lightweight net/http |
| Aarv-Segmentio | 8086 | Aarv + segmentio JSON codec |
| Chi | 8087 | Lightweight net/http router |
| net/http | 8088 | Go stdlib only (zero dependencies) |

### Rust (5 frameworks)
| Framework | Port | Type |
|-----------|:----:|------|
| Actix-web | 8111 | Actor-based, multi-threaded |
| Axum | 8112 | Tower/Hyper-based |
| Rocket | 8113 | Macro-driven |
| Warp | 8114 | Filter-based composition |
| Poem | 8115 | Clean middleware API |

### Java (7 frameworks)
| Framework | Port | Type |
|-----------|:----:|------|
| Spring WebMVC | 8101 | Servlet/Tomcat |
| Spring WebFlux | 8102 | Reactive/Netty |
| Quarkus | 8103 | Cloud-native, RESTEasy |
| Micronaut | 8104 | Compile-time DI |
| Vert.x | 8105 | Event-driven/Netty |
| Helidon SE | 8106 | Loom/Virtual threads |
| Javalin | 8107 | Lightweight/Jetty |

### .NET (5 frameworks)
| Framework | Port | Type |
|-----------|:----:|------|
| Minimal API | 8093 | ASP.NET Core minimal |
| Controller API | 8094 | ASP.NET MVC controllers |
| AOT | 8095 | Native AOT + source-gen JSON |
| Carter | 8096 | Carter modules |
| FastEndpoints | 8097 | Structured endpoints |

### Node.js (2 frameworks)
| Framework | Port | Type |
|-----------|:----:|------|
| Express | 8091 | Synchronous middleware |
| Fastify | 8092 | Schema-based, fast serialization |

## API Endpoints (8 total)

All frameworks implement identical endpoints:

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/users/{userId}/orders` | Create order | 201 |
| GET | `/users/{userId}/orders/{orderId}?fields=X` | Get order | 200/404 |
| PUT | `/users/{userId}/orders/{orderId}` | Update order | 200/404 |
| DELETE | `/users/{userId}/orders/{orderId}` | Delete order | 200/404 |
| POST | `/users/{userId}/orders/bulk` | Bulk create 50 orders (~9KB req, ~15KB resp) | 201 |
| GET | `/users/{userId}/orders` | List all user orders (~20KB resp) | 200 |
| PUT | `/users/{userId}/profile` | Create/update nested profile (~650B) | 200 |
| GET | `/users/{userId}/profile` | Get nested profile (~700B) | 200/404 |

## Middleware Stack (identical across all 27 frameworks)

Every framework runs these 6 middleware layers in order:

1. **Recovery** — catches panics/exceptions, returns 500 JSON
2. **Request ID** — reads `X-Request-ID` header or generates UUID, sets response header
3. **CORS** — `Access-Control-Allow-Origin: *`, standard methods/headers
4. **Security Headers** — X-XSS-Protection, X-Content-Type-Options, X-Frame-Options, HSTS, CSP, Referrer-Policy, Permissions-Policy, Cross-Origin-Opener-Policy
5. **Body Limit** — rejects >1MB with 413
6. **Structured Logger** — JSON log per request with:
   - `request_id`, `method`, `path`, `query`, `client_ip` (from X-Forwarded-For), `user_agent`
   - `request_headers` (PII redacted), `request_body` (captured before handler)
   - `status`, `latency`, `latency_ms`
   - `response_headers` (PII redacted), `response_body` (captured), `bytes_out`
   - PII redacted: Authorization, Cookie, Set-Cookie, X-Api-Key, X-Auth-Token → `[REDACTED]`

## Storage

### In-Memory (default)
- `ConcurrentHashMap` / `sync.RWMutex` + `map` / `DashMap`
- Atomic counter for auto-increment order IDs

### PostgreSQL 18 (`DB=pg`)
- Shared pgstore package per language — **identical SQL, pool settings, and driver config**
- Connection pool: max 50, min 10 (same for all frameworks)
- Schema: `orders` table (BIGSERIAL PK, user_id, items JSONB, total, currency) + `profiles` table (user_id PK, data JSONB)
- Drivers: pgx (Go), sqlx (Rust), HikariCP+JDBC (Java), Npgsql (.NET), pg (Node.js)
- PostgreSQL runs in Docker: `--cpus=2 --memory=1g`

## Prerequisites

- **Go** 1.26+
- **Rust** 1.94+
- **Java** 26 + Maven
- **.NET** 10.0 SDK
- **Node.js** 22+
- **Docker** (for PostgreSQL, constrained mode)
- **wrk** (`brew install wrk`)

## Quick Start

```bash
# In-memory, native, all Go frameworks, CRUD only
CONSTRAIN=0 ENDPOINTS=crud ./benchmark.sh 10s 4 100 20 go

# PostgreSQL, native, specific frameworks
DB=pg CONSTRAIN=0 ENDPOINTS=get,post,put ./benchmark.sh 10s 4 100 20 aarv gin actix-web

# PostgreSQL, Docker constrained (1 CPU, 512MB), all endpoints
DB=pg CONSTRAIN=1 ./benchmark.sh 10s 4 100 30 go rust

# All 27 frameworks, PostgreSQL, constrained
DB=pg CONSTRAIN=1 ENDPOINTS=crud ./benchmark.sh 10s 4 100 30
```

## Benchmark Flow

### PostgreSQL Pre-warm (once at script start)
- INSERT 1000 rows → SELECT → UPDATE → DELETE → ANALYZE
- Populates PostgreSQL shared_buffers and plan cache

### Per-Framework Flow
```
1. Start server
2. Capture idle resource stats (threads, memory, CPU)
3. ── Phase 1: Pre-warm (15s heavy load) ──
   - Seed 50 orders for "warmuser"
   - 5s GET at full load (warms PG plan cache + connection pool)
   - 5s POST at full load (warms INSERT path + JIT)
   - 3s PUT (warms UPDATE path)
   - Warm profile endpoints
4. ── Phase 2: Clean state ──
   - TRUNCATE orders + profiles (reset to 0 rows)
   - Seed exactly 100 orders (consistent starting state)
5. Validate all endpoints return expected HTTP status
6. Seed profile for PROFILE-GET
7. Start background peak memory/CPU sampler
8. ── Per-endpoint benchmarks ──
   - Each gets 3s warmup + 10s measurement
   - Order: GET → POST → PUT → P-PUT → P-GET → LIST → BULK → DELETE
9. Capture peak + final resource stats
10. Stop server, cool-down before next framework
```

## Docker Constrained Mode (`CONSTRAIN=1`)

Each framework runs in its own Docker container with hard resource limits:

| Resource | Limit |
|----------|-------|
| CPU | 1 core (`--cpus=1`) |
| Memory | 512MB (`--memory=512m`) |
| Go | `GOMAXPROCS=1` |
| Rust | `TOKIO_WORKER_THREADS=1` |
| Java | `-XX:ActiveProcessorCount=1 -Xmx384m` |
| .NET | `DOTNET_PROCESSOR_COUNT=1` |
| Node | `UV_THREADPOOL_SIZE=1` |

## Configuration

| Env Variable | Default | Options |
|-------------|---------|---------|
| `ENDPOINTS` | `all` | `all`, `crud`, `large`, or comma-separated: `get,post,put` |
| `CONSTRAIN` | `1` | `0` (native), `1` (Docker limited) |
| `DB` | `memory` | `memory`, `pg` (PostgreSQL 18) |
| `COOLDOWN` | 4th CLI arg (20) | Seconds between frameworks |

## Project Structure

```
framework_compare/
├── benchmark.sh              # Main benchmark script
├── docker/                   # Dockerfiles per language
│   ├── go.Dockerfile
│   ├── rust.Dockerfile
│   ├── java.Dockerfile
│   ├── dotnet.Dockerfile
│   └── node.Dockerfile
├── lua/                      # wrk Lua scripts
│   ├── wrk_post.lua
│   ├── wrk_put.lua
│   ├── wrk_delete.lua
│   ├── wrk_bulk_post.lua
│   └── wrk_profile_put.lua
├── go/
│   ├── pgstore/              # Shared PostgreSQL store (pgx v5)
│   ├── aarv/                 # Port 8081
│   ├── gin/                  # Port 8082
│   ├── fiber/                # Port 8083
│   ├── echo/                 # Port 8084
│   ├── mach/                 # Port 8085
│   ├── aarv-segmentio/       # Port 8086
│   ├── chi/                  # Port 8087
│   └── nethttp/              # Port 8088 (stdlib only)
├── rust/
│   ├── pgstore/              # Shared PostgreSQL store (sqlx)
│   ├── actix-web/            # Port 8111
│   ├── axum/                 # Port 8112
│   ├── rocket/               # Port 8113
│   ├── warp/                 # Port 8114
│   └── poem/                 # Port 8115
├── java/
│   ├── pgstore/              # Shared PostgreSQL store (HikariCP)
│   ├── spring-webmvc/        # Port 8101
│   ├── spring-webflux/       # Port 8102
│   ├── quarkus/              # Port 8103
│   ├── micronaut/            # Port 8104
│   ├── vertx/                # Port 8105
│   ├── helidon/              # Port 8106
│   └── javalin/              # Port 8107
├── dotnet/
│   ├── PgStore/              # Shared PostgreSQL store (Npgsql)
│   ├── minimal-api/          # Port 8093
│   ├── controller-api/       # Port 8094
│   ├── aot/                  # Port 8095
│   ├── carter/               # Port 8096
│   └── fast-endpoints/       # Port 8097
└── node/
    ├── pgstore/              # Shared PostgreSQL store (pg)
    ├── express/              # Port 8091
    └── fastify/              # Port 8092
```

## Fairness Guarantees

- **Same SQL**: Every language uses identical DDL, queries, and pool settings via shared pgstore
- **Same middleware**: All 27 frameworks log request bodies, capture response bodies, redact PII, parse X-Forwarded-For
- **Same warmup**: 15s pre-warm + truncate + re-seed ensures consistent PG cache state
- **Randomized order**: Framework run order is shuffled to eliminate positional bias
- **Peak memory**: Sampled every 2s throughout the run, not just a single snapshot
- **Docker stats**: CPU% and memory from `docker stats` (real-time, not cumulative)
