# Go Web Framework Benchmark

A side-by-side comparison of five Go web frameworks using an identical API endpoint, middleware stack, and static JSON response. Designed for fair performance benchmarking with `wrk`.

## Frameworks

| Framework | Version | Port | GitHub |
|-----------|---------|------|--------|
| **Aarv** | v0.4.0 | 8081 | [nilshah80/aarv](https://github.com/nilshah80/aarv) |
| **Gin** | v1.10.0 | 8082 | [gin-gonic/gin](https://github.com/gin-gonic/gin) |
| **Fiber** | v2.52.6 | 8083 | [gofiber/fiber](https://github.com/gofiber/fiber) |
| **Echo** | v4.13.3 | 8084 | [labstack/echo](https://github.com/labstack/echo) |
| **Mach** | v0.3.1 | 8085 | [mrshabel/mach](https://github.com/mrshabel/mach) |

## Prerequisites

- **Go** 1.22+ (uses `net/http` path parameter syntax `{param}`)
- **wrk** for benchmarking (`brew install wrk` on macOS, `apt install wrk` on Linux)
- **curl** for smoke tests
- **make** for build orchestration

## Project Structure

```
go/
├── README.md
├── Makefile            # Build, run, test, and benchmark commands
├── benchmark.sh        # wrk benchmark script with comparison table
├── aarv/
│   ├── go.mod
│   └── main.go         # Aarv — uses plugin system (recover, requestid, verboselog)
├── gin/
│   ├── go.mod
│   └── main.go         # Gin — all custom middleware, no third-party middleware
├── fiber/
│   ├── go.mod
│   └── main.go         # Fiber — built-in recover + requestid, custom logger
├── echo/
│   ├── go.mod
│   └── main.go         # Echo — built-in Recover + RequestID, custom logger
└── mach/
    ├── go.mod
    └── main.go          # Mach — built-in Recovery + RequestID, custom logger
```

## API Endpoint

All frameworks expose the same endpoint:

```
GET /users/{userId}/orders/{orderId}?fields=<value>
Header: X-Api-Key: <token>
```

### Multi-Source Request Binding

| Source | Field | Struct Tag |
|--------|-------|------------|
| Path param | `userId` | `param:"userId"` |
| Path param | `orderId` | `param:"orderId"` |
| Query string | `fields` | `query:"fields"` (default: `*`) |
| Header | `X-Api-Key` | `header:"X-Api-Key"` |

### Sample Request

```bash
curl -s -H "X-Api-Key: my-secret-token" \
  "http://localhost:8081/users/user123/orders/42?fields=id,status"
```

### Static JSON Response

```json
{
  "order_id": "42",
  "user_id": "user123",
  "status": "completed",
  "items": [
    { "product_id": "prod_001", "name": "Widget", "quantity": 2, "price": 29.99 },
    { "product_id": "prod_002", "name": "Gadget", "quantity": 1, "price": 49.99 }
  ],
  "total": 109.97,
  "currency": "USD",
  "fields": "id,status",
  "request_id": "<uuid>"
}
```

## Middleware Stack

Every framework runs the same three middlewares in this order:

### 1. Recovery

Catches panics in downstream handlers, logs the stack trace via `slog`, and returns a JSON 500 error response instead of crashing the server.

### 2. Request ID Propagation

Reads the incoming `X-Request-ID` header. If absent, generates a UUID v4. Sets the ID on the response `X-Request-ID` header and stores it in the request context for downstream use.

### 3. Structured Logger (with PII Redaction)

Logs every request/response cycle as structured JSON via `slog`. Captures:

- **Request**: method, path, query params, client IP, user agent
- **Request headers**: all headers, with sensitive ones redacted
- **Response**: status code, latency (ms), bytes written
- **Response headers**: all headers, with sensitive ones redacted

**Redacted headers** (replaced with `[REDACTED]`):
`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`

**Redacted body fields** (replaced with `[REDACTED]`):
`password`, `token`, `secret`, `api_key`, `ssn`, `credit_card`

### Framework-Specific Middleware Notes

| Framework | Recovery | Request ID | Logger |
|-----------|----------|------------|--------|
| **Aarv** | `plugins/recover` (plugin) | `plugins/requestid` (plugin) | `plugins/verboselog` (plugin with config) |
| **Gin** | Custom `gin.HandlerFunc` | Custom `gin.HandlerFunc` | Custom with response body capture |
| **Fiber** | Built-in `fiber.Recover()` | Built-in `requestid.New()` | Custom `fiber.Handler` |
| **Echo** | Built-in `middleware.Recover()` | Built-in `middleware.RequestID()` | Custom `echo.MiddlewareFunc` |
| **Mach** | Built-in `middleware.Recovery()` | Built-in `middleware.RequestID()` | Custom `mach.MiddlewareFunc` |

## Quick Start

```bash
cd go/

# 1. Build all frameworks (runs go mod tidy + go build)
make build

# 2. Start all servers in background
make start-all

# 3. Smoke test — hit each endpoint once
make test

# 4. Run the full benchmark
make benchmark

# 5. Stop all servers
make stop-all

# 6. Clean build artifacts
make clean
```

## Make Targets

| Target | Description |
|--------|-------------|
| `make build` | Build all 5 framework binaries into `bin/` |
| `make build-<name>` | Build a single framework (e.g., `make build-aarv`) |
| `make run-<name>` | Run a single framework in foreground (e.g., `make run-gin`) |
| `make start-all` | Build and start all 5 servers in background |
| `make stop-all` | Kill all running framework servers |
| `make test` | Smoke test all endpoints with curl |
| `make benchmark` | Run the wrk benchmark script |
| `make tidy` | Run `go mod tidy` in all framework directories |
| `make clean` | Remove `bin/`, `logs/`, and `results/` directories |

## Benchmarking

The `benchmark.sh` script uses `wrk` to load-test each framework with identical settings.

### Default Parameters

| Parameter | Default | CLI Arg |
|-----------|---------|---------|
| Duration | 10s | 1st arg |
| Threads | 4 | 2nd arg |
| Connections | 100 | 3rd arg |

### Custom Benchmark Run

```bash
# 30 seconds, 8 threads, 200 connections
./benchmark.sh 30s 8 200
```

### What the Script Does

1. Checks all 5 servers are responding with HTTP 200
2. Warms up each server (3s, 2 threads, 10 connections)
3. Runs the full benchmark for each framework with `--latency` flag
4. Saves detailed results to `results/benchmark_<timestamp>.txt`
5. Prints a quick comparison table:

```
Framework      Req/sec  Avg Latency  P99 Latency  Transfer/s
------------------------------------------------------------
aarv           45000       2.1ms        8.5ms       12.3MB
gin            42000       2.3ms        9.1ms       11.8MB
fiber          48000       1.9ms        7.8ms       13.1MB
echo           43000       2.2ms        8.9ms       12.0MB
mach           41000       2.4ms        9.5ms       11.5MB
```

*(Numbers above are illustrative — actual results depend on your hardware.)*

### Results Directory

After benchmarking, the `results/` folder contains:

- `benchmark_<timestamp>.txt` — Full output from all frameworks
- `<framework>_<timestamp>.txt` — Individual per-framework results

## Running Individual Frameworks

To run a single framework in the foreground (useful for debugging):

```bash
# Run Aarv on port 8081
make run-aarv

# Or directly
cd aarv && go run .
```

## Adding a New Framework

1. Create a new directory: `mkdir myframework`
2. Add `go.mod` and `main.go` following the same patterns
3. Implement the same endpoint with the same 3 middlewares
4. Pick the next port (8086+)
5. Add the framework name to `FRAMEWORKS` in the `Makefile`
6. Add the port mapping in `benchmark.sh`
