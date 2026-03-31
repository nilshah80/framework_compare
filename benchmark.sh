#!/usr/bin/env bash

# ============================================================
# Web Framework Benchmark Script
# ============================================================
# Benchmarks Go, .NET, and Node.js frameworks
# Each framework is started, benchmarked, and stopped one by one
# No overlap between frameworks or endpoints
#
# Usage:
#   ./benchmark.sh [duration] [threads] [connections] [cooldown] [filter...]
#
# Examples:
#   ./benchmark.sh                          # all frameworks, defaults
#   ./benchmark.sh 10s 4 100 20             # all frameworks, custom config
#   ./benchmark.sh 10s 4 100 20 aarv gin    # only aarv and gin
#   ./benchmark.sh 10s 4 100 20 fiber       # only fiber
#   ./benchmark.sh 10s 4 100 20 go          # all Go frameworks
#   ./benchmark.sh 10s 4 100 20 dotnet      # all .NET frameworks
#   ./benchmark.sh 10s 4 100 20 node        # all Node frameworks
# ============================================================

DURATION="${1:-10s}"
THREADS="${2:-4}"
CONNECTIONS="${3:-100}"
COOLDOWN="${4:-20}"
shift 4 2>/dev/null || true
FILTER_ARGS=("$@")
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LUA_DIR="$SCRIPT_DIR/lua"
LOGS_DIR="$SCRIPT_DIR/logs"

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR" "$LOGS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
SUMMARY_FILE="$RESULTS_DIR/benchmark_${TIMESTAMP}.txt"

POST_BODY='{"items":[{"product_id":"prod_001","name":"Widget","quantity":2,"price":29.99},{"product_id":"prod_002","name":"Gadget","quantity":1,"price":49.99}],"currency":"USD"}'

# ── Framework definitions ─────────────────────────────────────
declare -a FW_NAMES FW_PORTS FW_LANGS FW_START_CMDS FW_DIRS

add_fw() { FW_NAMES+=("$1"); FW_PORTS+=("$2"); FW_LANGS+=("$3"); FW_DIRS+=("$4"); FW_START_CMDS+=("$5"); }

# Go frameworks
add_fw "aarv"             8081 "Go"   "$SCRIPT_DIR/go/aarv"             "./aarv"
add_fw "gin"              8082 "Go"   "$SCRIPT_DIR/go/gin"              "./gin"
add_fw "fiber"            8083 "Go"   "$SCRIPT_DIR/go/fiber"            "./fiber"
add_fw "echo"             8084 "Go"   "$SCRIPT_DIR/go/echo"             "./echo"
add_fw "mach"             8085 "Go"   "$SCRIPT_DIR/go/mach"             "./mach"
add_fw "aarv-segmentio"   8086 "Go"   "$SCRIPT_DIR/go/aarv-segmentio"   "./aarv-segmentio"

# .NET frameworks
add_fw "minimal-api"      8093 ".NET" "$SCRIPT_DIR/dotnet/minimal-api"      "dotnet run -c Release"
add_fw "controller-api"   8094 ".NET" "$SCRIPT_DIR/dotnet/controller-api"   "dotnet run -c Release"
add_fw "aot"              8095 ".NET" "$SCRIPT_DIR/dotnet/aot"              "dotnet run -c Release"
add_fw "carter"           8096 ".NET" "$SCRIPT_DIR/dotnet/carter"           "dotnet run -c Release"
add_fw "fast-endpoints"   8097 ".NET" "$SCRIPT_DIR/dotnet/fast-endpoints"   "dotnet run -c Release"

# Java frameworks (pre-built jars)
add_fw "spring-webmvc"    8101 "Java" "$SCRIPT_DIR/java/spring-webmvc"      "java -jar target/spring-webmvc-1.0.0.jar"
add_fw "spring-webflux"   8102 "Java" "$SCRIPT_DIR/java/spring-webflux"     "java -jar target/spring-webflux-1.0.0.jar"
add_fw "quarkus"          8103 "Java" "$SCRIPT_DIR/java/quarkus"            "java -jar target/quarkus-app/quarkus-run.jar"
add_fw "micronaut"        8104 "Java" "$SCRIPT_DIR/java/micronaut"          "java -Dmicronaut.server.port=8104 -jar target/micronaut-benchmark-1.0.0.jar"
add_fw "vertx"            8105 "Java" "$SCRIPT_DIR/java/vertx"              "java -jar target/vertx-benchmark-1.0.0-fat.jar"
add_fw "helidon"          8106 "Java" "$SCRIPT_DIR/java/helidon"            "java -jar target/helidon-benchmark.jar"
add_fw "javalin"          8107 "Java" "$SCRIPT_DIR/java/javalin"            "java -jar target/javalin-1.0.0.jar"

# Rust frameworks (pre-built release binaries)
add_fw "actix-web"        8111 "Rust" "$SCRIPT_DIR/rust/actix-web"          "./target/release/benchmark-actix-web"
add_fw "axum"             8112 "Rust" "$SCRIPT_DIR/rust/axum"               "./target/release/benchmark-axum"
add_fw "rocket"           8113 "Rust" "$SCRIPT_DIR/rust/rocket"             "./target/release/benchmark-rocket"
add_fw "warp"             8114 "Rust" "$SCRIPT_DIR/rust/warp"               "./target/release/benchmark-warp"
add_fw "poem"             8115 "Rust" "$SCRIPT_DIR/rust/poem"               "./target/release/benchmark-poem"

# Node.js frameworks
add_fw "express"          8091 "Node" "$SCRIPT_DIR/node/express"            "node main.js"
add_fw "fastify"          8092 "Node" "$SCRIPT_DIR/node/fastify"            "node main.js"

TOTAL=${#FW_NAMES[@]}

# ── Filter frameworks if arguments provided ───────────────────
# Supports: framework names (aarv, gin), or language groups (go, dotnet, node)
ACTIVE_INDICES=()
if [ ${#FILTER_ARGS[@]} -gt 0 ]; then
    for j in $(seq 0 $((TOTAL - 1))); do
        for filter in "${FILTER_ARGS[@]}"; do
            matched=false
            # Match by framework name (case-insensitive)
            if [ "$(echo "${FW_NAMES[$j]}" | tr '[:upper:]' '[:lower:]')" = "$(echo "$filter" | tr '[:upper:]' '[:lower:]')" ]; then
                matched=true
            fi
            # Match by language group
            case "$(echo "$filter" | tr '[:upper:]' '[:lower:]')" in
                go)     [ "${FW_LANGS[$j]}" = "Go" ] && matched=true ;;
                java)   [ "${FW_LANGS[$j]}" = "Java" ] && matched=true ;;
                rust)   [ "${FW_LANGS[$j]}" = "Rust" ] && matched=true ;;
                dotnet|.net) [ "${FW_LANGS[$j]}" = ".NET" ] && matched=true ;;
                node)   [ "${FW_LANGS[$j]}" = "Node" ] && matched=true ;;
            esac
            if [ "$matched" = true ]; then
                ACTIVE_INDICES+=("$j")
                break
            fi
        done
    done
    if [ ${#ACTIVE_INDICES[@]} -eq 0 ]; then
        echo "ERROR: No frameworks matched filter: ${FILTER_ARGS[*]}"
        echo "Available: ${FW_NAMES[*]}"
        echo "Groups: go, dotnet, node"
        exit 1
    fi
    TOTAL=${#ACTIVE_INDICES[@]}
else
    for j in $(seq 0 $((TOTAL - 1))); do ACTIVE_INDICES+=("$j"); done
fi

# ── Randomize framework order (Fisher-Yates shuffle) ──────────
# Eliminates positional bias from thermal throttling / CPU state
INDICES=("${ACTIVE_INDICES[@]}")
for ((j=${#INDICES[@]}-1; j>0; j--)); do
    k=$((RANDOM % (j + 1)))
    tmp=${INDICES[$j]}
    INDICES[$j]=${INDICES[$k]}
    INDICES[$k]=$tmp
done

# ── Pre-flight checks ────────────────────────────────────────
if ! command -v wrk &> /dev/null; then
    echo "ERROR: wrk is not installed."
    echo "  macOS: brew install wrk"
    exit 1
fi

# Build Go binaries
echo "Building Go binaries..."
for dir in "$SCRIPT_DIR"/go/*/; do
    name=$(basename "$dir")
    if [ ! -f "$dir/$name" ] || [ "$dir/main.go" -nt "$dir/$name" ]; then
        echo "  Building $name..."
        if [ "$name" = "aarv-segmentio" ]; then
            (cd "$dir" && GONOSUMCHECK='*' GONOSUMDB='*' go build -ldflags="-s -w" -o "$name" .) || { echo "  FAILED to build $name"; exit 1; }
        else
            (cd "$dir" && go build -ldflags="-s -w" -o "$name" .) || { echo "  FAILED to build $name"; exit 1; }
        fi
    fi
done
echo ""

# Build Java jars (if source is newer than jar)
echo "Building Java projects..."
for dir in "$SCRIPT_DIR"/java/*/; do
    name=$(basename "$dir")
    pom="$dir/pom.xml"
    [ ! -f "$pom" ] && continue
    # Check if any jar exists in target
    jar_count=$(find "$dir/target" -name "*.jar" 2>/dev/null | head -1)
    if [ -z "$jar_count" ] || [ "$pom" -nt "$jar_count" ]; then
        echo "  Building $name..."
        (cd "$dir" && mvn package -DskipTests -q) || { echo "  FAILED to build $name"; }
    fi
done
echo ""

# Build Rust release binaries (if source is newer than binary)
echo "Building Rust projects..."
for dir in "$SCRIPT_DIR"/rust/*/; do
    name=$(basename "$dir")
    cargo_toml="$dir/Cargo.toml"
    [ ! -f "$cargo_toml" ] && continue
    binary="$dir/target/release/benchmark-$name"
    if [ ! -f "$binary" ] || [ "$dir/src/main.rs" -nt "$binary" ]; then
        echo "  Building $name..."
        (cd "$dir" && cargo build --release -q) || { echo "  FAILED to build $name"; }
    fi
done
echo ""

CPU_CORES=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo "?")
TOTAL_RAM=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0fGB", $1/1024/1024/1024}' 2>/dev/null || echo "?")

echo "============================================================"
echo "  Web Framework Benchmark"
echo "============================================================"
echo "  Duration:    $DURATION per endpoint"
echo "  Threads:     $THREADS"
echo "  Connections: $CONNECTIONS"
echo "  Cooldown:    ${COOLDOWN}s between frameworks"
echo "  Frameworks:  $TOTAL"
echo "  Endpoints:   GET, POST, PUT, DELETE"
echo "  System:      $CPU_CORES cores, $TOTAL_RAM RAM"
echo "  Timestamp:   $TIMESTAMP"
RUN_ORDER=""
for idx in "${INDICES[@]}"; do RUN_ORDER="$RUN_ORDER ${FW_NAMES[$idx]}"; done
echo "  Run Order:  $RUN_ORDER"
echo "============================================================"
echo ""

# ── Summary file header ──────────────────────────────────────
{
    echo "============================================================"
    echo "  Web Framework Benchmark Results"
    echo "============================================================"
    echo "  Duration:    $DURATION per endpoint"
    echo "  Threads:     $THREADS"
    echo "  Connections: $CONNECTIONS"
    echo "  Cooldown:    ${COOLDOWN}s between frameworks"
    echo "  System:      $CPU_CORES cores, $TOTAL_RAM RAM"
    echo "  Run Order:  $RUN_ORDER"
    echo "  Date:        $(date)"
    echo "============================================================"
    echo ""
} > "$SUMMARY_FILE"

# ── Helper: wait for server to respond ────────────────────────
wait_for_server() {
    local port=$1 max_wait=30 elapsed=0
    while true; do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
            "http://localhost:$port/users/healthcheck/orders" \
            -H "Content-Type: application/json" \
            -d '{"items":[{"product_id":"hc","name":"HC","quantity":1,"price":1}],"currency":"USD"}' 2>/dev/null) || true
        if [ "$code" = "201" ]; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge $max_wait ]; then
            echo "  TIMEOUT waiting for port $port (last HTTP code: $code)"
            return 1
        fi
    done
}

# ── Helper: kill ALL processes on a port ──────────────────────
kill_port() {
    local port=$1 my_pid=$$
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        for pid in $pids; do
            [ "$pid" = "$my_pid" ] && continue
            pkill -P "$pid" 2>/dev/null || true
            kill "$pid" 2>/dev/null || true
        done
        sleep 2
        pids=$(lsof -ti:"$port" 2>/dev/null || true)
        if [ -n "$pids" ]; then
            for pid in $pids; do
                [ "$pid" = "$my_pid" ] && continue
                kill -9 "$pid" 2>/dev/null || true
            done
            sleep 1
        fi
    fi
}

# ── Helper: verify port is free ───────────────────────────────
ensure_port_free() {
    local port=$1 max_wait=10 elapsed=0
    while lsof -ti:"$port" > /dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge $max_wait ]; then
            echo "  ERROR: port $port still in use after ${max_wait}s"
            return 1
        fi
    done
    return 0
}

# ── Helper: find actual server PID (child of subshell) ────────
find_server_pid() {
    local port=$1
    # lsof gives us the PID that actually owns the listening socket
    lsof -ti:"$port" -sTCP:LISTEN 2>/dev/null | head -1
}

# ── Helper: get process resource stats ─────────────────────────
# Uses ps for CPU% and RSS (reliable per-process values),
# and top -l 1 for thread count (ps doesn't expose this on macOS).
# Returns: threads|memory|cpu
get_process_stats() {
    local pid=$1
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "N/A|N/A|N/A"
        return
    fi

    # CPU% and RSS from ps (always reliable, no delta needed)
    local cpu rss_kb
    cpu=$(ps -o %cpu= -p "$pid" 2>/dev/null | tr -d ' ')
    rss_kb=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')

    # Human-readable memory
    local mem="N/A"
    if [ -n "$rss_kb" ] && [ "$rss_kb" -gt 0 ] 2>/dev/null; then
        if [ "$rss_kb" -ge 1048576 ]; then
            mem=$(awk "BEGIN{printf \"%.1fGB\", $rss_kb/1048576}")
        elif [ "$rss_kb" -ge 1024 ]; then
            mem=$(awk "BEGIN{printf \"%.0fMB\", $rss_kb/1024}")
        else
            mem="${rss_kb}KB"
        fi
    fi

    # Thread count from top (only source on macOS)
    # top reports "28/11" meaning total/running — extract total
    local threads="N/A"
    local top_line
    top_line=$(top -l 1 -pid "$pid" -stats th 2>/dev/null | tail -1)
    if [ -n "$top_line" ] && ! echo "$top_line" | grep -q "^#TH"; then
        # Strip "/running" suffix if present (e.g., "28/11" -> "28")
        threads=$(echo "$top_line" | awk '{print $1}' | cut -d'/' -f1)
    fi

    echo "${threads}|${mem}|${cpu}%"
}

# ── Helper: seed orders and return a valid order ID ───────────
seed_and_get_id() {
    local port=$1 count=${2:-100}
    local last_id=""
    for s in $(seq 1 "$count"); do
        local resp
        resp=$(curl -s -X POST "http://localhost:$port/users/user123/orders" \
            -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
            -d "$POST_BODY")
        # Extract order_id from JSON response
        last_id=$(echo "$resp" | grep -o '"order_id":"[^"]*"' | head -1 | cut -d'"' -f4)
    done
    echo "$last_id"
}

# ── Helper: verify a URL returns expected HTTP status ─────────
assert_status() {
    local method=$1 url=$2 expected=$3 body=$4
    local code
    if [ -n "$body" ]; then
        code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
            -d "$body")
    else
        code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$url" \
            -H "X-Api-Key: bench-token")
    fi
    if [ "$code" != "$expected" ]; then
        echo "  FAIL: $method $url -> $code (expected $expected)"
        return 1
    fi
    return 0
}

# ── Benchmark loop: one framework at a time (randomized order) ─
RUN_NUM=0
for i in "${INDICES[@]}"; do
    RUN_NUM=$((RUN_NUM + 1))
    fw="${FW_NAMES[$i]}"
    port="${FW_PORTS[$i]}"
    lang="${FW_LANGS[$i]}"
    dir="${FW_DIRS[$i]}"
    cmd="${FW_START_CMDS[$i]}"

    LOG_FILE="$LOGS_DIR/${fw}_${TIMESTAMP}.log"

    echo "============================================================"
    echo "  [$RUN_NUM/$TOTAL] $fw ($lang, port $port)"
    echo "============================================================"

    # Ensure port is free
    kill_port "$port"
    if ! ensure_port_free "$port"; then
        echo "  SKIPPING $fw — port $port is stuck"
        echo ""
        continue
    fi

    # Start server — logs go to a file
    echo "  Starting $fw..."
    (cd "$dir" && ASPNETCORE_ENVIRONMENT=Production DOTNET_ENVIRONMENT=Production $cmd) > "$LOG_FILE" 2>&1 &
    SUBSHELL_PID=$!

    # Wait for server to respond
    if ! wait_for_server "$port"; then
        echo "  SKIPPING $fw — server did not start"
        kill_port "$port"
        echo ""
        continue
    fi

    # Find the ACTUAL server PID (the process listening on the port)
    SERVER_PID=$(find_server_pid "$port")
    echo "  Server ready (PID $SERVER_PID)"

    # ── Resource stats: IDLE ──────────────────────────────────
    IDLE_STATS=$(get_process_stats "$SERVER_PID")
    IFS='|' read -r IDLE_TH IDLE_MEM IDLE_CPU <<< "$IDLE_STATS"
    echo "  Resources (idle):  Threads=$IDLE_TH  Memory=$IDLE_MEM  CPU=$IDLE_CPU"

    # ── Seed orders and capture a valid order ID ──────────────
    echo "  Seeding 100 orders..."
    ORDER_ID=$(seed_and_get_id "$port" 100)
    if [ -z "$ORDER_ID" ]; then
        echo "  SKIPPING $fw — could not seed orders"
        kill_port "$port"
        echo ""
        continue
    fi
    echo "  Using order_id=$ORDER_ID for GET/PUT/DELETE targets"

    # ── Validate all endpoints return expected status ─────────
    echo "  Validating endpoints..."
    VALID=true
    assert_status "GET"    "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=id,status" "200" "" || VALID=false
    assert_status "PUT"    "http://localhost:$port/users/user123/orders/$ORDER_ID" "200" "$POST_BODY" || VALID=false
    assert_status "POST"   "http://localhost:$port/users/user123/orders" "201" "$POST_BODY" || VALID=false
    # Don't validate DELETE here — it would consume the order
    echo "    All endpoints OK"

    if [ "$VALID" = false ]; then
        echo "  SKIPPING $fw — endpoint validation failed"
        kill_port "$port"
        echo ""
        continue
    fi

    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"
    {
        echo "Framework: $fw ($lang)"
        echo "Port: $port"
        echo "Order ID: $ORDER_ID"
        echo "Date: $(date)"
        echo ""
    } > "$RESULT_FILE"

    # ══════════════════════════════════════════════════════════
    # GET benchmark — target: /users/user123/orders/{ORDER_ID}
    # The order exists and will keep existing (GET doesn't mutate)
    # ══════════════════════════════════════════════════════════
    echo "  Warming up GET (3s)..."
    wrk -t2 -c10 -d3s "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=id,status" \
        -H "X-Api-Key: bench-token" > /dev/null 2>&1 || true
    sleep 1

    echo "  Benchmarking GET..."
    wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -H "X-Api-Key: bench-token" \
        "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=id,status" > /tmp/_wrk_$$.txt 2>&1 &
    WRK_PID=$!
    # Capture resource stats mid-benchmark
    sleep 3
    LOAD_STATS=$(get_process_stats "$SERVER_PID")
    IFS='|' read -r LOAD_TH LOAD_MEM LOAD_CPU <<< "$LOAD_STATS"
    wait $WRK_PID 2>/dev/null || true
    GET_RESULT=$(cat /tmp/_wrk_$$.txt)
    echo "$GET_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
    { echo "--- GET ---"; echo "$GET_RESULT"; echo ""; } >> "$RESULT_FILE"

    # Verify no 404s in GET
    GET_NON2XX=$(echo "$GET_RESULT" | grep "Non-2xx" | awk '{print $NF}')
    if [ -n "$GET_NON2XX" ] && [ "$GET_NON2XX" != "0" ]; then
        echo "  ERROR: GET had $GET_NON2XX non-2xx responses — results invalid!"
    fi
    echo "  Resources (load): Threads=$LOAD_TH  Memory=$LOAD_MEM  CPU=$LOAD_CPU"
    sleep 2

    # ══════════════════════════════════════════════════════════
    # POST benchmark — creates new orders (always 201)
    # ══════════════════════════════════════════════════════════
    echo "  Warming up POST (3s)..."
    wrk -t2 -c10 -d3s -s "$LUA_DIR/wrk_post.lua" \
        "http://localhost:$port/users/user123/orders" > /dev/null 2>&1 || true
    sleep 1

    echo "  Benchmarking POST..."
    POST_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_post.lua" \
        "http://localhost:$port/users/user123/orders" 2>&1) || true
    echo "$POST_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
    { echo "--- POST ---"; echo "$POST_RESULT"; echo ""; } >> "$RESULT_FILE"
    sleep 2

    # ══════════════════════════════════════════════════════════
    # PUT benchmark — updates the same order (always 200)
    # Re-verify the order still exists first
    # ══════════════════════════════════════════════════════════
    assert_status "GET" "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=check" "200" "" || {
        echo "  WARNING: order $ORDER_ID disappeared before PUT benchmark"
    }

    echo "  Warming up PUT (3s)..."
    wrk -t2 -c10 -d3s -s "$LUA_DIR/wrk_put.lua" \
        "http://localhost:$port/users/user123/orders/$ORDER_ID" > /dev/null 2>&1 || true
    sleep 1

    echo "  Benchmarking PUT..."
    PUT_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_put.lua" \
        "http://localhost:$port/users/user123/orders/$ORDER_ID" 2>&1) || true
    echo "$PUT_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
    { echo "--- PUT ---"; echo "$PUT_RESULT"; echo ""; } >> "$RESULT_FILE"

    PUT_NON2XX=$(echo "$PUT_RESULT" | grep "Non-2xx" | awk '{print $NF}')
    if [ -n "$PUT_NON2XX" ] && [ "$PUT_NON2XX" != "0" ]; then
        echo "  ERROR: PUT had $PUT_NON2XX non-2xx responses — results invalid!"
    fi
    sleep 2

    # ══════════════════════════════════════════════════════════
    # DELETE benchmark — successful deletes (200) using sequential unique IDs
    # Pre-seed a large pool of orders, then each wrk thread deletes
    # unique IDs from non-overlapping ranges via wrk_delete.lua.
    # ══════════════════════════════════════════════════════════

    # Seed a massive pool: run POST at full load for 30s to build up orders.
    # Must create more orders than DELETE can consume in $DURATION + warmup.
    echo "  Seeding DELETE pool (30s POST burst at full load)..."
    wrk -t${THREADS} -c${CONNECTIONS} -d30s -s "$LUA_DIR/wrk_post.lua" \
        "http://localhost:$port/users/user123/orders" > /dev/null 2>&1 || true
    # Get the current order counter — this is the ceiling of what exists
    DEL_POOL_END=$(curl -s -X POST "http://localhost:$port/users/user123/orders" \
        -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
        -d "$POST_BODY" | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4)
    echo "  DELETE pool: ~$DEL_POOL_END orders available"

    # Verify deletes work at different points in the pool
    assert_status "DELETE" "http://localhost:$port/users/user123/orders/5000" "200" "" || {
        echo "  WARNING: DELETE validation failed at ID 5000"
    }
    assert_status "DELETE" "http://localhost:$port/users/user123/orders/100000" "200" "" || {
        echo "  WARNING: DELETE validation failed at ID 100000"
    }

    # No warmup for DELETE — warmup would consume pool IDs.
    # The POST seeding burst already warmed up the server.

    # Benchmark: start from 200000 (past validation deletes at 5000 and 100000)
    echo "  Benchmarking DELETE..."
    DEL_RESULT=$(DELETE_START_ID=200000 \
        wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_delete.lua" \
        "http://localhost:$port" 2>&1) || true
    echo "$DEL_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
    { echo "--- DELETE ---"; echo "$DEL_RESULT"; echo ""; } >> "$RESULT_FILE"

    DEL_NON2XX=$(echo "$DEL_RESULT" | grep "Non-2xx" | awk '{print $NF}')
    if [ -n "$DEL_NON2XX" ] && [ "$DEL_NON2XX" != "0" ]; then
        echo "  WARNING: DELETE had $DEL_NON2XX non-2xx responses (pool may have been too small)"
    fi

    # ── Resource stats: AFTER LOAD ────────────────────────────
    AFTER_STATS=$(get_process_stats "$SERVER_PID")
    IFS='|' read -r AFTER_TH AFTER_MEM AFTER_CPU <<< "$AFTER_STATS"
    echo "  Resources (after): Threads=$AFTER_TH  Memory=$AFTER_MEM  CPU=$AFTER_CPU"

    # Write resource stats to result file
    {
        echo "--- RESOURCES ---"
        echo "Idle:  Threads=$IDLE_TH  Memory=$IDLE_MEM  CPU=$IDLE_CPU"
        echo "Load:  Threads=$LOAD_TH  Memory=$LOAD_MEM  CPU=$LOAD_CPU"
        echo "After: Threads=$AFTER_TH  Memory=$AFTER_MEM  CPU=$AFTER_CPU"
    } >> "$RESULT_FILE"

    # Append to summary
    {
        echo "============================================================"
        echo "  $fw ($lang, port $port)"
        echo "============================================================"
        cat "$RESULT_FILE"
        echo ""
    } >> "$SUMMARY_FILE"

    # Stop server
    echo "  Stopping $fw..."
    kill_port "$port"
    kill $SUBSHELL_PID 2>/dev/null || true
    wait $SUBSHELL_PID 2>/dev/null || true

    if ! ensure_port_free "$port"; then
        echo "  WARNING: force-killing remaining processes on port $port"
        lsof -ti:"$port" 2>/dev/null | xargs kill -9 2>/dev/null || true
        sleep 2
    fi

    LOG_BYTES=$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ')
    LOG_HUMAN=$(awk "BEGIN{
        b=$LOG_BYTES;
        if(b>=1073741824) printf \"%.1fGB\",b/1073741824;
        else if(b>=1048576) printf \"%.1fMB\",b/1048576;
        else if(b>=1024) printf \"%.1fKB\",b/1024;
        else printf \"%dB\",b
    }" 2>/dev/null || echo "${LOG_BYTES}B")
    echo "  Done. (log: $LOG_HUMAN)"
    echo ""

    # Cool-down between frameworks
    if [ $RUN_NUM -lt $TOTAL ]; then
        echo "  Cooling down ${COOLDOWN}s..."
        sleep "$COOLDOWN"
    fi
done

rm -f /tmp/_wrk_$$.txt

# ── Comparison table ──────────────────────────────────────────
echo ""
echo "============================================================"
echo "  Benchmark Complete!"
echo "  Results: $RESULTS_DIR/"
echo "  Logs:    $LOGS_DIR/"
echo "============================================================"
echo ""

HEADER_FMT="%-18s %5s %10s %10s %10s %10s %10s  %8s %7s\n"
DIVIDER="------------------------------------------------------------------------------------------------------------"

echo "CRUD Throughput (sorted by average of GET + POST + PUT + DELETE):"
echo "  Threads/Memory: single sample during GET load — indicative, not precise."
echo ""
printf "$HEADER_FMT" "Framework" "Lang" "GET r/s" "POST r/s" "PUT r/s" "DEL r/s" "AVG r/s" "Threads" "Memory"
echo "$DIVIDER"

ROWS=""
for idx in "${ACTIVE_INDICES[@]}"; do
    fw="${FW_NAMES[$idx]}"
    lang="${FW_LANGS[$idx]}"
    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"

    if [ ! -f "$RESULT_FILE" ]; then
        continue
    fi

    GET_RPS=$(awk '/^--- GET ---$/,/^--- POST ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    POST_RPS=$(awk '/^--- POST ---$/,/^--- PUT ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    PUT_RPS=$(awk '/^--- PUT ---$/,/^--- DELETE ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    DEL_RPS=$(awk '/^--- DELETE ---$/,/^--- RESOURCES ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')

    LOAD_LINE=$(grep "^Load:" "$RESULT_FILE" 2>/dev/null || echo "")
    RES_TH=$(echo "$LOAD_LINE" | sed 's/.*Threads=\([^ ]*\).*/\1/')
    RES_MEM=$(echo "$LOAD_LINE" | sed 's/.*Memory=\([^ ]*\).*/\1/')

    if [ -n "$GET_RPS" ] && [ -n "$POST_RPS" ] && [ -n "$PUT_RPS" ] && [ -n "$DEL_RPS" ]; then
        AVG_RPS=$(echo "$GET_RPS $POST_RPS $PUT_RPS $DEL_RPS" | awk '{printf "%.2f", ($1+$2+$3+$4)/4}')
    else
        AVG_RPS="N/A"
    fi

    ROWS="${ROWS}${AVG_RPS} $(printf "$HEADER_FMT" "$fw" "$lang" "${GET_RPS:-N/A}" "${POST_RPS:-N/A}" "${PUT_RPS:-N/A}" "${DEL_RPS:-N/A}" "$AVG_RPS" "${RES_TH:-N/A}" "${RES_MEM:-N/A}")\n"
done

echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
    echo "$line" | awk '{$1=""; print substr($0,2)}'
done

echo "$DIVIDER"

# Check for DELETE non-2xx warnings
echo ""
DEL_ISSUES=false
for idx in "${ACTIVE_INDICES[@]}"; do
    fw="${FW_NAMES[$idx]}"
    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"
    [ ! -f "$RESULT_FILE" ] && continue
    NON2XX=$(awk '/^--- DELETE ---$/,/^--- RESOURCES ---$/' "$RESULT_FILE" | grep "Non-2xx" | awk '{print $NF}')
    if [ -n "$NON2XX" ] && [ "$NON2XX" != "0" ]; then
        echo "  WARNING: $fw DELETE had $NON2XX non-2xx responses (pool exhausted)"
        DEL_ISSUES=true
    fi
done
if [ "$DEL_ISSUES" = false ]; then
    echo "  All DELETE benchmarks returned 200 (verified)"
fi

# Append to summary file
{
    echo ""
    echo "CRUD Throughput (sorted by average):"
    echo "  Threads/Memory: single sample during GET load — indicative, not precise."
    echo ""
    printf "$HEADER_FMT" "Framework" "Lang" "GET r/s" "POST r/s" "PUT r/s" "DEL r/s" "AVG r/s" "Threads" "Memory"
    echo "$DIVIDER"
    echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
        echo "$line" | awk '{$1=""; print substr($0,2)}'
    done
    echo "$DIVIDER"
} >> "$SUMMARY_FILE"
