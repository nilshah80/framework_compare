#!/usr/bin/env bash
set -e

# ============================================================
# Web Framework Benchmark Script
# ============================================================
# Benchmarks Go, .NET, and Node.js frameworks
# Each framework is started, benchmarked, and stopped one by one
# No overlap between frameworks or endpoints
#
# Usage: ./benchmark.sh [duration] [threads] [connections]
# ============================================================

DURATION="${1:-10s}"
THREADS="${2:-4}"
CONNECTIONS="${3:-100}"
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

# Go frameworks (pre-built binaries)
add_fw "aarv"             8081 "Go"   "$SCRIPT_DIR/go/aarv"             "./aarv"
add_fw "gin"              8082 "Go"   "$SCRIPT_DIR/go/gin"              "./gin"
add_fw "fiber"            8083 "Go"   "$SCRIPT_DIR/go/fiber"            "./fiber"
add_fw "echo"             8084 "Go"   "$SCRIPT_DIR/go/echo"             "./echo"
add_fw "mach"             8085 "Go"   "$SCRIPT_DIR/go/mach"             "./mach"
add_fw "aarv-segmentio"   8086 "Go"   "$SCRIPT_DIR/go/aarv-segmentio"   "./aarv-segmentio"

# .NET frameworks (dotnet run -c Release)
add_fw "minimal-api"      8093 ".NET" "$SCRIPT_DIR/dotnet/minimal-api"      "dotnet run -c Release"
add_fw "controller-api"   8094 ".NET" "$SCRIPT_DIR/dotnet/controller-api"   "dotnet run -c Release"
add_fw "aot"              8095 ".NET" "$SCRIPT_DIR/dotnet/aot"              "dotnet run -c Release"
add_fw "carter"           8096 ".NET" "$SCRIPT_DIR/dotnet/carter"           "dotnet run -c Release"
add_fw "fast-endpoints"   8097 ".NET" "$SCRIPT_DIR/dotnet/fast-endpoints"   "dotnet run -c Release"

# Node.js frameworks
add_fw "express"          8091 "Node" "$SCRIPT_DIR/node/express"            "node main.js"
add_fw "fastify"          8092 "Node" "$SCRIPT_DIR/node/fastify"            "node main.js"

TOTAL=${#FW_NAMES[@]}

# ── Pre-flight checks ────────────────────────────────────────
if ! command -v wrk &> /dev/null; then
    echo "ERROR: wrk is not installed."
    echo "  macOS: brew install wrk"
    echo "  Linux: sudo apt-get install wrk"
    exit 1
fi

# Build Go binaries with release flags
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

echo "============================================================"
echo "  Web Framework Benchmark"
echo "============================================================"
echo "  Duration:    $DURATION"
echo "  Threads:     $THREADS"
echo "  Connections: $CONNECTIONS"
echo "  Frameworks:  $TOTAL"
echo "  Endpoints:   GET, POST, PUT, DELETE"
echo "  Timestamp:   $TIMESTAMP"
echo "============================================================"
echo ""

# ── Summary file header ──────────────────────────────────────
{
    echo "============================================================"
    echo "  Web Framework Benchmark Results"
    echo "============================================================"
    echo "  Duration:    $DURATION"
    echo "  Threads:     $THREADS"
    echo "  Connections: $CONNECTIONS"
    echo "  Frameworks:  $TOTAL"
    echo "  Date:        $(date)"
    echo "============================================================"
    echo ""
} > "$SUMMARY_FILE"

# ── Helper: wait for server to respond on the actual endpoint ─
wait_for_server() {
    local port=$1 max_wait=20 elapsed=0
    while true; do
        # Try POST to create an order — this is an endpoint every framework has
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
    local port=$1
    local my_pid=$$
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        for pid in $pids; do
            # Skip our own process
            if [ "$pid" = "$my_pid" ]; then
                continue
            fi
            # Kill the pid and its children via pkill -P
            pkill -P "$pid" 2>/dev/null || true
            kill "$pid" 2>/dev/null || true
        done
        sleep 2
        # Force kill anything still on that port
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

# ── Helper: verify port is completely free ────────────────────
ensure_port_free() {
    local port=$1 max_wait=10 elapsed=0
    while lsof -ti:"$port" > /dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge $max_wait ]; then
            echo "  ERROR: port $port still in use after ${max_wait}s"
            lsof -i:"$port" 2>/dev/null || true
            return 1
        fi
    done
    return 0
}

# ── Benchmark loop: one framework at a time ──────────────────
for i in $(seq 0 $((TOTAL - 1))); do
    fw="${FW_NAMES[$i]}"
    port="${FW_PORTS[$i]}"
    lang="${FW_LANGS[$i]}"
    dir="${FW_DIRS[$i]}"
    cmd="${FW_START_CMDS[$i]}"

    LOG_FILE="$LOGS_DIR/${fw}_${TIMESTAMP}.log"

    echo "============================================================"
    echo "  [$((i+1))/$TOTAL] $fw ($lang, port $port)"
    echo "============================================================"

    # Ensure port is free from any previous run
    kill_port "$port"
    if ! ensure_port_free "$port"; then
        echo "  SKIPPING $fw — port $port is stuck"
        echo ""
        continue
    fi

    # Start server — logs go to a file, not /dev/null
    echo "  Starting $fw..."
    (cd "$dir" && ASPNETCORE_ENVIRONMENT=Production DOTNET_ENVIRONMENT=Production $cmd) > "$LOG_FILE" 2>&1 &
    SERVER_PID=$!

    # Wait for server to actually respond to requests
    if ! wait_for_server "$port"; then
        echo "  SKIPPING $fw — server did not start"
        kill_port "$port"
        echo ""
        continue
    fi
    echo "  Server ready (PID $SERVER_PID, log: $LOG_FILE)"

    # Seed 100 orders
    echo "  Seeding orders..."
    for s in $(seq 1 100); do
        curl -s -o /dev/null -X POST "http://localhost:$port/users/user123/orders" \
            -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
            -d "$POST_BODY"
    done

    # Test all endpoints
    echo "  Testing endpoints..."
    post_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:$port/users/user123/orders" \
        -H "Content-Type: application/json" -d "$POST_BODY")
    get_code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$port/users/user123/orders/50?fields=id,status" \
        -H "X-Api-Key: bench-token")
    put_code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:$port/users/user123/orders/50" \
        -H "Content-Type: application/json" -d "$POST_BODY")
    del_code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "http://localhost:$port/users/user123/orders/50")
    echo "    POST=$post_code GET=$get_code PUT=$put_code DELETE=$del_code"

    if [ "$get_code" != "200" ]; then
        echo "  WARNING: GET returned $get_code, results may be unreliable"
    fi

    # Re-seed order 50 for GET/PUT benchmarks after delete
    curl -s -o /dev/null -X POST "http://localhost:$port/users/user123/orders" \
        -H "Content-Type: application/json" -d "$POST_BODY"

    # Warmup
    echo "  Warming up (3s)..."
    wrk -t2 -c10 -d3s "http://localhost:$port/users/user123/orders/50?fields=id,status" \
        -H "X-Api-Key: bench-token" > /dev/null 2>&1 || true
    sleep 1

    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"
    {
        echo "Framework: $fw ($lang)"
        echo "Port: $port"
        echo "Date: $(date)"
        echo ""
    } > "$RESULT_FILE"

    # ── Benchmark GET ─────────────────────────────────────────
    echo "  Benchmarking GET..."
    GET_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -H "X-Api-Key: bench-token" \
        "http://localhost:$port/users/user123/orders/50?fields=id,status" 2>&1) || true
    echo "$GET_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec"
    { echo "--- GET ---"; echo "$GET_RESULT"; echo ""; } >> "$RESULT_FILE"
    sleep 2

    # ── Benchmark POST ────────────────────────────────────────
    echo "  Benchmarking POST..."
    POST_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_post.lua" \
        "http://localhost:$port/users/user123/orders" 2>&1) || true
    echo "$POST_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec"
    { echo "--- POST ---"; echo "$POST_RESULT"; echo ""; } >> "$RESULT_FILE"
    sleep 2

    # ── Benchmark PUT ─────────────────────────────────────────
    echo "  Benchmarking PUT..."
    PUT_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_put.lua" \
        "http://localhost:$port/users/user123/orders/50" 2>&1) || true
    echo "$PUT_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec"
    { echo "--- PUT ---"; echo "$PUT_RESULT"; echo ""; } >> "$RESULT_FILE"
    sleep 2

    # ── Benchmark DELETE ──────────────────────────────────────
    echo "  Benchmarking DELETE..."
    DEL_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
        -s "$LUA_DIR/wrk_delete.lua" \
        "http://localhost:$port/users/user123/orders/50" 2>&1) || true
    echo "$DEL_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec"
    { echo "--- DELETE ---"; echo "$DEL_RESULT"; echo ""; } >> "$RESULT_FILE"

    # Append to summary
    {
        echo "============================================================"
        echo "  $fw ($lang, port $port)"
        echo "============================================================"
        cat "$RESULT_FILE"
        echo ""
    } >> "$SUMMARY_FILE"

    # Stop server — kill process tree, then verify port is free
    echo "  Stopping $fw..."
    kill_port "$port"
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true

    # Verify port is truly free before moving on
    if ! ensure_port_free "$port"; then
        echo "  WARNING: force-killing remaining processes on port $port"
        lsof -ti:"$port" 2>/dev/null | xargs kill -9 2>/dev/null || true
        sleep 2
    fi

    LOG_SIZE=$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ')
    echo "  Done. (log: ${LOG_SIZE} bytes written to $LOG_FILE)"
    echo ""

    # Cool-down between frameworks — let CPU settle
    sleep 5
done

# ── Comparison table ──────────────────────────────────────────
echo "============================================================"
echo "  Benchmark Complete!"
echo "  Results saved to: $SUMMARY_FILE"
echo "  Logs saved to:    $LOGS_DIR/"
echo "============================================================"
echo ""
echo "Comparison Table (sorted by avg Req/sec across all endpoints):"
echo "-------------------------------------------------------------------------------------"
printf "%-18s %5s %10s %10s %10s %10s %10s\n" "Framework" "Lang" "GET r/s" "POST r/s" "PUT r/s" "DEL r/s" "AVG r/s"
echo "-------------------------------------------------------------------------------------"

ROWS=""
for i in $(seq 0 $((TOTAL - 1))); do
    fw="${FW_NAMES[$i]}"
    lang="${FW_LANGS[$i]}"
    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"

    if [ ! -f "$RESULT_FILE" ]; then
        continue
    fi

    # Extract Requests/sec for each endpoint section
    GET_RPS=$(awk '/^--- GET ---$/,/^--- POST ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    POST_RPS=$(awk '/^--- POST ---$/,/^--- PUT ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    PUT_RPS=$(awk '/^--- PUT ---$/,/^--- DELETE ---$/{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')
    DEL_RPS=$(awk '/^--- DELETE ---$/,0{print}' "$RESULT_FILE" | grep "Requests/sec" | awk '{print $2}')

    # Calculate average
    if [ -n "$GET_RPS" ] && [ -n "$POST_RPS" ] && [ -n "$PUT_RPS" ] && [ -n "$DEL_RPS" ]; then
        AVG_RPS=$(echo "$GET_RPS $POST_RPS $PUT_RPS $DEL_RPS" | awk '{printf "%.2f", ($1+$2+$3+$4)/4}')
    else
        AVG_RPS="N/A"
    fi

    ROWS="${ROWS}${AVG_RPS} $(printf "%-18s %5s %10s %10s %10s %10s %10s" "$fw" "$lang" "${GET_RPS:-N/A}" "${POST_RPS:-N/A}" "${PUT_RPS:-N/A}" "${DEL_RPS:-N/A}" "$AVG_RPS")\n"
done

# Sort by avg RPS descending and print
echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
    echo "$line" | awk '{$1=""; print substr($0,2)}'
done

echo "-------------------------------------------------------------------------------------"

# Append table to summary
{
    echo ""
    echo "Comparison Table (sorted by avg Req/sec):"
    echo "-------------------------------------------------------------------------------------"
    printf "%-18s %5s %10s %10s %10s %10s %10s\n" "Framework" "Lang" "GET r/s" "POST r/s" "PUT r/s" "DEL r/s" "AVG r/s"
    echo "-------------------------------------------------------------------------------------"
    echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
        echo "$line" | awk '{$1=""; print substr($0,2)}'
    done
    echo "-------------------------------------------------------------------------------------"
} >> "$SUMMARY_FILE"
