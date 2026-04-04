#!/usr/bin/env bash

# ============================================================
# Web Framework Benchmark Script
# ============================================================
# Benchmarks Go, .NET, Node.js, Java, and Rust frameworks
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
#
# Environment variables:
#   ENDPOINTS   — which endpoints to benchmark (default: all)
#                 all:  GET, POST, PUT, DELETE, BULK, LIST, PROFILE-PUT, PROFILE-GET
#                 crud: GET, POST, PUT, DELETE
#                 large: BULK, LIST, PROFILE-PUT, PROFILE-GET
#                 comma-separated: get,post,bulk (specific endpoints)
#   CONSTRAIN   — 0 = direct execution (default), 1 = Docker with resource limits
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

ENDPOINTS="${ENDPOINTS:-all}"
CONSTRAIN="${CONSTRAIN:-1}"

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR" "$LOGS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
SUMMARY_FILE="$RESULTS_DIR/benchmark_${TIMESTAMP}.txt"

POST_BODY='{"items":[{"product_id":"prod_001","name":"Widget","quantity":2,"price":29.99},{"product_id":"prod_002","name":"Gadget","quantity":1,"price":49.99}],"currency":"USD"}'

PROFILE_BODY='{"name":"John Doe","email":"john@example.com","phone":"+1-555-0100","address":{"street":"123 Main St","city":"Springfield","state":"IL","zip":"62701","country":"US"},"preferences":{"language":"en","currency":"USD","timezone":"America/Chicago","notifications":{"email":true,"sms":false,"push":true},"theme":"dark"},"payment_methods":[{"type":"visa","last4":"4242","expiry_month":12,"expiry_year":2027,"is_default":true},{"type":"mastercard","last4":"5555","expiry_month":6,"expiry_year":2026,"is_default":false}],"tags":["premium","early_adopter","beta_tester"],"metadata":{"source":"web","campaign":"summer2024","referrer":"google","signup_version":"2.1"}}'

# ── Endpoint filtering ────────────────────────────────────────
should_run() {
    local endpoint=$1

    # In constrained mode (Docker 512MB), auto-skip endpoints that OOM:
    # - delete: 15-30s POST seeding fills memory with millions of orders
    # - bulk: creates 50 orders per request, floods store rapidly
    if [ "$CONSTRAIN" = "1" ]; then
        case "$endpoint" in
            delete|bulk)
                # Allow if explicitly requested by name
                if echo ",$ENDPOINTS," | grep -q ",$endpoint,"; then
                    return 0
                fi
                # Otherwise skip for 'all' and 'crud'/'large' groups
                return 1
                ;;
        esac
    fi

    case "$ENDPOINTS" in
        all) return 0 ;;
        crud)
            case "$endpoint" in
                get|post|put|delete) return 0 ;;
                *) return 1 ;;
            esac
            ;;
        large)
            case "$endpoint" in
                bulk|list|profile-put|profile-get) return 0 ;;
                *) return 1 ;;
            esac
            ;;
        *)
            echo ",$ENDPOINTS," | grep -q ",$endpoint," && return 0 || return 1
            ;;
    esac
}

# Build list of active endpoint names for display
ACTIVE_ENDPOINTS=""
for ep in get post put delete bulk list profile-put profile-get; do
    if should_run "$ep"; then
        [ -n "$ACTIVE_ENDPOINTS" ] && ACTIVE_ENDPOINTS="$ACTIVE_ENDPOINTS, "
        ACTIVE_ENDPOINTS="$ACTIVE_ENDPOINTS$(echo "$ep" | tr '[:lower:]' '[:upper:]')"
    fi
done

# ── Docker helper: map framework to language for Dockerfile ───
fw_docker_lang() {
    local lang=$1
    case "$lang" in
        Go)   echo "go" ;;
        .NET) echo "dotnet" ;;
        Java) echo "java" ;;
        Node) echo "node" ;;
        Rust) echo "rust" ;;
    esac
}

# ── Docker helper: get JAR_PATH build arg for Java frameworks ─
fw_jar_path() {
    local fw=$1
    case "$fw" in
        quarkus)        echo "target/quarkus-app/quarkus-run.jar" ;;
        spring-webmvc)  echo "target/spring-webmvc-1.0.0.jar" ;;
        spring-webflux) echo "target/spring-webflux-1.0.0.jar" ;;
        micronaut)      echo "target/micronaut-benchmark-1.0.0.jar" ;;
        vertx)          echo "target/vertx-benchmark-1.0.0-fat.jar" ;;
        helidon)        echo "target/helidon-benchmark.jar" ;;
        javalin)        echo "target/javalin-1.0.0.jar" ;;
        *)              echo "target/*.jar" ;;
    esac
}

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
add_fw "chi"              8087 "Go"   "$SCRIPT_DIR/go/chi"              "./chi"

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
        echo "Groups: go, dotnet, node, java, rust"
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

if [ "$CONSTRAIN" = "1" ]; then
    if ! command -v docker &> /dev/null; then
        echo "ERROR: docker is not installed (required when CONSTRAIN=1)."
        exit 1
    fi
    echo "Docker mode enabled (CONSTRAIN=1) — building images..."
    echo ""
else
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
fi

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
echo "  Endpoints:   $ACTIVE_ENDPOINTS"
echo "  Constrain:   $([ "$CONSTRAIN" = "1" ] && echo "Docker (1 CPU, 512MB)" || echo "None (native)")"
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
    echo "  Endpoints:   $ACTIVE_ENDPOINTS"
    echo "  Constrain:   $([ "$CONSTRAIN" = "1" ] && echo "Docker (1 CPU, 512MB)" || echo "None (native)")"
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
# ── Helper: get resource stats ────────────────────────────────
# Returns: threads|memory|cpu
# Uses docker stats in Docker mode, ps/top in native mode.
get_process_stats() {
    local pid_or_name=$1

    # Docker mode: use docker stats (real-time snapshot)
    if [ "$CONSTRAIN" = "1" ]; then
        local container="benchmark-${pid_or_name}"
        local stats_line
        stats_line=$(docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.PIDs}}" "$container" 2>/dev/null)
        if [ -z "$stats_line" ]; then
            echo "N/A|N/A|N/A"
            return
        fi
        local cpu mem pids
        cpu=$(echo "$stats_line" | cut -d'|' -f1)
        mem=$(echo "$stats_line" | cut -d'|' -f2 | awk '{print $1}')  # e.g., "45.2MiB" from "45.2MiB / 512MiB"
        pids=$(echo "$stats_line" | cut -d'|' -f3)
        echo "${pids}|${mem}|${cpu}"
        return
    fi

    # Native mode: use ps + top
    local pid=$pid_or_name
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "N/A|N/A|N/A"
        return
    fi

    local cpu rss_kb
    cpu=$(ps -o %cpu= -p "$pid" 2>/dev/null | tr -d ' ')
    rss_kb=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')

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

    local threads="N/A"
    local top_line
    top_line=$(top -l 1 -pid "$pid" -stats th 2>/dev/null | tail -1)
    if [ -n "$top_line" ] && ! echo "$top_line" | grep -q "^#TH"; then
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

# ── Docker: build and start framework in container ────────────
docker_build_and_start() {
    local fw=$1 lang=$2 port=$3
    local docker_lang
    docker_lang=$(fw_docker_lang "$lang")
    local image_name="benchmark-${fw}"
    local dockerfile="$SCRIPT_DIR/docker/${docker_lang}.Dockerfile"

    if [ ! -f "$dockerfile" ]; then
        echo "  ERROR: Dockerfile not found: $dockerfile"
        return 1
    fi

    echo "  Building Docker image $image_name..."
    local build_args="--build-arg FRAMEWORK=$fw"
    if [ "$lang" = "Java" ]; then
        local jar_path
        jar_path=$(fw_jar_path "$fw")
        build_args="$build_args --build-arg JAR_PATH=$jar_path"
    fi
    docker build -f "$dockerfile" $build_args -t "$image_name" "$SCRIPT_DIR" -q || {
        echo "  ERROR: Docker build failed for $fw"
        return 1
    }

    # Per-language env vars to limit thread pools to match 1-CPU constraint
    local env_args=""
    case "$lang" in
        Go)   env_args="-e GOMAXPROCS=1" ;;
        Java) env_args="-e JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx384m'" ;;
        Rust) env_args="-e TOKIO_WORKER_THREADS=1" ;;
        .NET) env_args="-e DOTNET_PROCESSOR_COUNT=1" ;;
        Node) env_args="-e UV_THREADPOOL_SIZE=1" ;;
    esac

    echo "  Starting Docker container $image_name..."
    docker run --cpus=1 --memory=512m $env_args -p ${port}:${port} --rm -d --name "$image_name" "$image_name" || {
        echo "  ERROR: Docker run failed for $fw"
        return 1
    }
    return 0
}

# ── Docker: stop framework container ──────────────────────────
docker_stop() {
    local fw=$1
    local container_name="benchmark-${fw}"
    docker stop "$container_name" 2>/dev/null || true
    # Wait for container to fully stop
    local max_wait=10 elapsed=0
    while docker ps -q -f "name=$container_name" 2>/dev/null | grep -q .; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge $max_wait ]; then
            docker kill "$container_name" 2>/dev/null || true
            break
        fi
    done
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

    if [ "$CONSTRAIN" = "1" ]; then
        # Docker mode: build and start container
        if ! docker_build_and_start "$fw" "$lang" "$port"; then
            echo "  SKIPPING $fw — Docker build/start failed"
            echo ""
            continue
        fi
        SUBSHELL_PID=""
    else
        # Native mode: ensure port is free and start directly
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
    fi

    # Wait for server to respond
    if ! wait_for_server "$port"; then
        echo "  SKIPPING $fw — server did not start"
        if [ "$CONSTRAIN" = "1" ]; then
            docker_stop "$fw"
        else
            kill_port "$port"
        fi
        echo ""
        continue
    fi

    # Find the ACTUAL server PID (the process listening on the port)
    SERVER_PID=$(find_server_pid "$port")
    echo "  Server ready (PID $SERVER_PID)"

    # ── Resource stats: IDLE ──────────────────────────────────
    IDLE_STATS=$(get_process_stats "$([ "$CONSTRAIN" = "1" ] && echo "$fw" || echo "$SERVER_PID")")
    IFS='|' read -r IDLE_TH IDLE_MEM IDLE_CPU <<< "$IDLE_STATS"
    echo "  Resources (idle):  Threads=$IDLE_TH  Memory=$IDLE_MEM  CPU=$IDLE_CPU"

    # ── Seed orders and capture a valid order ID ──────────────
    echo "  Seeding 100 orders..."
    ORDER_ID=$(seed_and_get_id "$port" 100)
    if [ -z "$ORDER_ID" ]; then
        echo "  SKIPPING $fw — could not seed orders"
        if [ "$CONSTRAIN" = "1" ]; then
            docker_stop "$fw"
        else
            kill_port "$port"
        fi
        echo ""
        continue
    fi
    echo "  Using order_id=$ORDER_ID for GET/PUT/DELETE targets"

    # ── Validate all endpoints return expected status ─────────
    echo "  Validating endpoints..."
    VALID=true
    if should_run "get"; then
        assert_status "GET"    "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=id,status" "200" "" || VALID=false
    fi
    if should_run "put"; then
        assert_status "PUT"    "http://localhost:$port/users/user123/orders/$ORDER_ID" "200" "$POST_BODY" || VALID=false
    fi
    if should_run "post"; then
        assert_status "POST"   "http://localhost:$port/users/user123/orders" "201" "$POST_BODY" || VALID=false
    fi
    if should_run "bulk"; then
        assert_status "POST"   "http://localhost:$port/users/user123/orders/bulk" "201" '{"orders":[{"items":[{"product_id":"v","name":"V","quantity":1,"price":1}],"currency":"USD"}]}' || VALID=false
    fi
    if should_run "profile-put"; then
        assert_status "PUT"    "http://localhost:$port/users/user123/profile" "200" "$PROFILE_BODY" || VALID=false
    fi
    if should_run "profile-get"; then
        # Seed profile first if only running profile-get
        if ! should_run "profile-put"; then
            curl -s -o /dev/null -X PUT "http://localhost:$port/users/user123/profile" \
                -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
                -d "$PROFILE_BODY" 2>/dev/null || true
        fi
        assert_status "GET"    "http://localhost:$port/users/user123/profile" "200" "" || VALID=false
    fi
    # Don't validate DELETE here — it would consume the order
    echo "    All endpoints OK"

    if [ "$VALID" = false ]; then
        echo "  SKIPPING $fw — endpoint validation failed"
        if [ "$CONSTRAIN" = "1" ]; then
            docker_stop "$fw"
        else
            kill_port "$port"
        fi
        echo ""
        continue
    fi

    # Global warmup: exercise all code paths to trigger JIT/compilation
    # This ensures the first benchmarked endpoint doesn't pay cold-start cost
    echo "  Global warmup (5s mixed requests)..."
    wrk -t2 -c10 -d5s "http://localhost:$port/users/user123/orders/$ORDER_ID?fields=id" \
        -H "X-Api-Key: bench-token" > /dev/null 2>&1 || true
    wrk -t2 -c10 -d2s -s "$LUA_DIR/wrk_post.lua" \
        "http://localhost:$port/users/user123/orders" > /dev/null 2>&1 || true
    wrk -t2 -c10 -d2s -s "$LUA_DIR/wrk_put.lua" \
        "http://localhost:$port/users/user123/orders/$ORDER_ID" > /dev/null 2>&1 || true
    sleep 1

    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"
    {
        echo "Framework: $fw ($lang)"
        echo "Port: $port"
        echo "Order ID: $ORDER_ID"
        echo "Date: $(date)"
        echo ""
    } > "$RESULT_FILE"

    # Track which endpoints were actually benchmarked for this framework
    DID_GET=false; DID_POST=false; DID_PUT=false; DID_DELETE=false
    DID_BULK=false; DID_LIST=false; DID_PROFILE_PUT=false; DID_PROFILE_GET=false

    # Start background peak memory/CPU sampler (samples every 2s, keeps max)
    PEAK_MEM_FILE="/tmp/_peak_stats_$$.txt"
    echo "0|0MiB|0%" > "$PEAK_MEM_FILE"
    (
        while true; do
            stats=$(get_process_stats "$([ "$CONSTRAIN" = "1" ] && echo "$fw" || echo "$SERVER_PID")")
            IFS='|' read -r s_th s_mem s_cpu <<< "$stats"
            # Extract numeric memory for comparison (strip MiB/GiB suffix)
            s_mem_num=$(echo "$s_mem" | sed 's/[^0-9.]//g')
            prev=$(cat "$PEAK_MEM_FILE" 2>/dev/null)
            prev_mem_num=$(echo "$prev" | cut -d'|' -f2 | sed 's/[^0-9.]//g')
            # Keep the sample with higher memory (peak)
            if awk "BEGIN{exit !($s_mem_num > $prev_mem_num)}" 2>/dev/null; then
                echo "${s_th}|${s_mem}|${s_cpu}" > "$PEAK_MEM_FILE"
            fi
            sleep 2
        done
    ) &
    SAMPLER_PID=$!

    # ══════════════════════════════════════════════════════════
    # GET benchmark — target: /users/user123/orders/{ORDER_ID}
    # The order exists and will keep existing (GET doesn't mutate)
    # ══════════════════════════════════════════════════════════
    if should_run "get"; then
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
        LOAD_STATS=$(get_process_stats "$([ "$CONSTRAIN" = "1" ] && echo "$fw" || echo "$SERVER_PID")")
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
        DID_GET=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # POST benchmark — creates new orders (always 201)
    # ══════════════════════════════════════════════════════════
    if should_run "post"; then
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
        DID_POST=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # PUT benchmark — updates the same order (always 200)
    # Re-verify the order still exists first
    # ══════════════════════════════════════════════════════════
    if should_run "put"; then
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
        DID_PUT=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # DELETE benchmark — successful deletes (200) using sequential unique IDs
    # Pre-seed a large pool of orders, then each wrk thread deletes
    # unique IDs from non-overlapping ranges via wrk_delete.lua.
    # ══════════════════════════════════════════════════════════
    if should_run "delete"; then
        # Seed a pool of orders for DELETE.
        # Use dedicated "deluser" to avoid store bloat affecting other benchmarks.
        # Constrained mode needs less seeding (lower throughput = fewer deletes needed).
        local del_seed_duration=30
        [ "$CONSTRAIN" = "1" ] && del_seed_duration=15
        echo "  Seeding DELETE pool (${del_seed_duration}s POST burst)..."
        wrk -t${THREADS} -c${CONNECTIONS} -d${del_seed_duration}s -s "$LUA_DIR/wrk_post.lua" \
            "http://localhost:$port/users/deluser/orders" > /dev/null 2>&1 || true
        # Get the current order counter — this is the ceiling of what exists
        DEL_POOL_END=$(curl -s -X POST "http://localhost:$port/users/deluser/orders" \
            -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
            -d "$POST_BODY" | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4)
        echo "  DELETE pool: ~$DEL_POOL_END orders available"

        # Verify deletes work
        assert_status "DELETE" "http://localhost:$port/users/deluser/orders/5000" "200" "" || {
            echo "  WARNING: DELETE validation failed at ID 5000"
        }

        # No warmup for DELETE — warmup would consume pool IDs.
        # The POST seeding burst already warmed up the server.

        # Benchmark: start from 10000 (past validation delete at 5000)
        echo "  Benchmarking DELETE..."
        DEL_RESULT=$(DELETE_START_ID=10000 DELETE_USER=deluser \
            wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
            -s "$LUA_DIR/wrk_delete.lua" \
            "http://localhost:$port" 2>&1) || true
        echo "$DEL_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
        { echo "--- DELETE ---"; echo "$DEL_RESULT"; echo ""; } >> "$RESULT_FILE"

        DEL_NON2XX=$(echo "$DEL_RESULT" | grep "Non-2xx" | awk '{print $NF}')
        if [ -n "$DEL_NON2XX" ] && [ "$DEL_NON2XX" != "0" ]; then
            echo "  WARNING: DELETE had $DEL_NON2XX non-2xx responses (pool may have been too small)"
        fi
        DID_DELETE=true
    fi

    # ══════════════════════════════════════════════════════════
    # PROFILE PUT benchmark — update user profile (nested JSON ~650B)
    # Runs BEFORE bulk to keep store small
    # ══════════════════════════════════════════════════════════
    if should_run "profile-put"; then
        echo "  Warming up PROFILE PUT (3s)..."
        wrk -t2 -c10 -d3s -s "$LUA_DIR/wrk_profile_put.lua" \
            "http://localhost:$port/users/profuser/profile" > /dev/null 2>&1 || true
        sleep 1

        echo "  Benchmarking PROFILE PUT..."
        PROF_PUT_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
            -s "$LUA_DIR/wrk_profile_put.lua" \
            "http://localhost:$port/users/profuser/profile" 2>&1) || true
        echo "$PROF_PUT_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
        { echo "--- PROFILE-PUT ---"; echo "$PROF_PUT_RESULT"; echo ""; } >> "$RESULT_FILE"
        DID_PROFILE_PUT=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # PROFILE GET benchmark — read user profile (~700B response)
    # ══════════════════════════════════════════════════════════
    if should_run "profile-get"; then
        # Ensure profile exists
        curl -s -o /dev/null -X PUT "http://localhost:$port/users/profuser/profile" \
            -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
            -d "$PROFILE_BODY" 2>/dev/null || true

        echo "  Warming up PROFILE GET (3s)..."
        wrk -t2 -c10 -d3s "http://localhost:$port/users/profuser/profile" \
            -H "X-Api-Key: bench-token" > /dev/null 2>&1 || true
        sleep 1

        echo "  Benchmarking PROFILE GET..."
        PROF_GET_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
            -H "X-Api-Key: bench-token" \
            "http://localhost:$port/users/profuser/profile" 2>&1) || true
        echo "$PROF_GET_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
        { echo "--- PROFILE-GET ---"; echo "$PROF_GET_RESULT"; echo ""; } >> "$RESULT_FILE"
        DID_PROFILE_GET=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # LIST GET benchmark — large JSON response (~20KB for 100 orders)
    # Uses dedicated "listuser" with exactly 100 orders
    # Runs BEFORE bulk to keep store small for scan
    # ══════════════════════════════════════════════════════════
    if should_run "list"; then
        echo "  Seeding listuser with 100 orders..."
        for s in $(seq 1 100); do
            curl -s -o /dev/null -X POST "http://localhost:$port/users/listuser/orders" \
                -H "Content-Type: application/json" -H "X-Api-Key: bench-token" \
                -d "$POST_BODY"
        done

        echo "  Warming up LIST GET (3s)..."
        wrk -t2 -c10 -d3s "http://localhost:$port/users/listuser/orders" \
            -H "X-Api-Key: bench-token" > /dev/null 2>&1 || true
        sleep 1

        echo "  Benchmarking LIST GET..."
        LIST_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
            -H "X-Api-Key: bench-token" \
            "http://localhost:$port/users/listuser/orders" 2>&1) || true
        echo "$LIST_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
        { echo "--- LIST ---"; echo "$LIST_RESULT"; echo ""; } >> "$RESULT_FILE"
        DID_LIST=true
        sleep 2
    fi

    # ══════════════════════════════════════════════════════════
    # BULK POST benchmark — large JSON (~9KB request, ~15KB response)
    # Runs LAST — creates millions of orders that flood the store.
    # Uses dedicated "bulkuser" to isolate from other users.
    # ══════════════════════════════════════════════════════════
    if should_run "bulk"; then
        echo "  Warming up BULK POST (3s)..."
        wrk -t2 -c10 -d3s -s "$LUA_DIR/wrk_bulk_post.lua" \
            "http://localhost:$port/users/bulkuser/orders/bulk" > /dev/null 2>&1 || true
        sleep 1

        echo "  Benchmarking BULK POST..."
        BULK_RESULT=$(wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
            -s "$LUA_DIR/wrk_bulk_post.lua" \
            "http://localhost:$port/users/bulkuser/orders/bulk" 2>&1) || true
        echo "$BULK_RESULT" | grep -E "Latency|Req/Sec|Requests/sec|Transfer/sec|Non-2xx"
        { echo "--- BULK ---"; echo "$BULK_RESULT"; echo ""; } >> "$RESULT_FILE"
        DID_BULK=true
        sleep 2
    fi

    # ── Resource stats: PEAK + FINAL ─────────────────────────
    # Stop the background sampler
    kill $SAMPLER_PID 2>/dev/null; wait $SAMPLER_PID 2>/dev/null || true

    # Read peak stats (highest memory seen during all benchmarks)
    PEAK_STATS=$(cat "$PEAK_MEM_FILE" 2>/dev/null || echo "N/A|N/A|N/A")
    IFS='|' read -r PEAK_TH PEAK_MEM PEAK_CPU <<< "$PEAK_STATS"
    rm -f "$PEAK_MEM_FILE"

    # Also capture final snapshot
    AFTER_STATS=$(get_process_stats "$([ "$CONSTRAIN" = "1" ] && echo "$fw" || echo "$SERVER_PID")")
    IFS='|' read -r AFTER_TH AFTER_MEM AFTER_CPU <<< "$AFTER_STATS"

    echo "  Resources (peak):  Threads=$PEAK_TH  Memory=$PEAK_MEM  CPU=$PEAK_CPU"
    echo "  Resources (final): Threads=$AFTER_TH  Memory=$AFTER_MEM  CPU=$AFTER_CPU"

    # Use peak stats for the comparison table (most representative)
    if [ -z "${LOAD_TH:-}" ] || [ "$LOAD_TH" = "" ] || [ "$LOAD_TH" = "N/A" ]; then
        LOAD_TH="$PEAK_TH"; LOAD_MEM="$PEAK_MEM"; LOAD_CPU="$PEAK_CPU"
    fi
    # Override with peak if peak memory is higher
    LOAD_MEM="$PEAK_MEM"
    LOAD_CPU="$PEAK_CPU"

    # Write resource stats to result file
    {
        echo "--- RESOURCES ---"
        echo "Idle:  Threads=$IDLE_TH  Memory=$IDLE_MEM  CPU=$IDLE_CPU"
        echo "Peak:  Threads=$PEAK_TH  Memory=$PEAK_MEM  CPU=$PEAK_CPU"
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
    if [ "$CONSTRAIN" = "1" ]; then
        docker_stop "$fw"
    else
        kill_port "$port"
        if [ -n "$SUBSHELL_PID" ]; then
            kill $SUBSHELL_PID 2>/dev/null || true
            wait $SUBSHELL_PID 2>/dev/null || true
        fi

        if ! ensure_port_free "$port"; then
            echo "  WARNING: force-killing remaining processes on port $port"
            lsof -ti:"$port" 2>/dev/null | xargs kill -9 2>/dev/null || true
            sleep 2
        fi
    fi

    LOG_HUMAN="N/A"
    if [ -f "$LOG_FILE" ]; then
        LOG_BYTES=$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ')
        LOG_HUMAN=$(awk "BEGIN{
            b=${LOG_BYTES:-0};
            if(b>=1073741824) printf \"%.1fGB\",b/1073741824;
            else if(b>=1048576) printf \"%.1fMB\",b/1048576;
            else if(b>=1024) printf \"%.1fKB\",b/1024;
            else printf \"%dB\",b
        }" 2>/dev/null || echo "${LOG_BYTES}B")
    fi
    echo "  Done. (log: $LOG_HUMAN)"
    echo ""

    # Reset LOAD stats for next framework
    unset LOAD_TH LOAD_MEM LOAD_CPU

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

# ── Build dynamic column list based on which endpoints ran ────
# Each column: label, result-section-marker, next-section-marker
declare -a COL_LABELS COL_MARKERS COL_NEXT_MARKERS COL_KEYS
add_col() { COL_LABELS+=("$1"); COL_MARKERS+=("$2"); COL_NEXT_MARKERS+=("$3"); COL_KEYS+=("$4"); }

if should_run "get";         then add_col "GET r/s"     "--- GET ---"         "get"; fi
if should_run "post";        then add_col "POST r/s"    "--- POST ---"        "post"; fi
if should_run "put";         then add_col "PUT r/s"     "--- PUT ---"         "put"; fi
if should_run "delete";      then add_col "DEL r/s"     "--- DELETE ---"      "delete"; fi
if should_run "bulk";        then add_col "BULK r/s"    "--- BULK ---"        "bulk"; fi
if should_run "list";        then add_col "LIST r/s"    "--- LIST ---"        "list"; fi
if should_run "profile-put"; then add_col "PPUT r/s"    "--- PROFILE-PUT ---" "profile-put"; fi
if should_run "profile-get"; then add_col "PGET r/s"    "--- PROFILE-GET ---" "profile-get"; fi

NUM_COLS=${#COL_LABELS[@]}

# ── Helper: extract RPS from a result file for a given section ─
extract_rps() {
    local file=$1 start_marker=$2
    # Extract from start_marker to next section marker (any "--- ... ---" line)
    awk -v start="$start_marker" '
        $0 == start { found=1; next }
        found && /^--- .* ---$/ { exit }
        found && /Requests\/sec/ { print $2 }
    ' "$file" 2>/dev/null
}

# Build header format dynamically
HEADER_FMT="%-18s %5s"
for ((c=0; c<NUM_COLS; c++)); do
    HEADER_FMT="$HEADER_FMT %10s"
done
HEADER_FMT="$HEADER_FMT %10s  %8s %7s\n"

DIVIDER_LEN=$((18 + 6 + NUM_COLS * 11 + 11 + 9 + 8))
DIVIDER=$(printf '%*s' "$DIVIDER_LEN" '' | tr ' ' '-')

echo "Throughput (sorted by average of benchmarked endpoints):"
echo "  Threads/Memory: single sample during load — indicative, not precise."
echo ""

# Print header
HEADER_ARGS=("Framework" "Lang")
for ((c=0; c<NUM_COLS; c++)); do
    HEADER_ARGS+=("${COL_LABELS[$c]}")
done
HEADER_ARGS+=("AVG r/s" "Threads" "Peak Mem")
printf "$HEADER_FMT" "${HEADER_ARGS[@]}"
echo "$DIVIDER"

ROWS=""
for idx in "${ACTIVE_INDICES[@]}"; do
    fw="${FW_NAMES[$idx]}"
    lang="${FW_LANGS[$idx]}"
    RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"

    if [ ! -f "$RESULT_FILE" ]; then
        continue
    fi

    # Extract RPS for each active column
    declare -a RPS_VALUES=()
    RPS_SUM=0
    RPS_COUNT=0
    for ((c=0; c<NUM_COLS; c++)); do
        local_rps=$(extract_rps "$RESULT_FILE" "${COL_MARKERS[$c]}" "${COL_NEXT_MARKERS[$c]}")
        if [ -n "$local_rps" ]; then
            RPS_VALUES+=("$local_rps")
            RPS_SUM=$(echo "$RPS_SUM + $local_rps" | bc 2>/dev/null || echo "$RPS_SUM")
            RPS_COUNT=$((RPS_COUNT + 1))
        else
            RPS_VALUES+=("—")
        fi
    done

    PEAK_LINE=$(grep "^Peak:" "$RESULT_FILE" 2>/dev/null || grep "^Load:" "$RESULT_FILE" 2>/dev/null || echo "")
    RES_TH=$(echo "$PEAK_LINE" | sed 's/.*Threads=\([^ ]*\).*/\1/')
    RES_MEM=$(echo "$PEAK_LINE" | sed 's/.*Memory=\([^ ]*\).*/\1/')

    if [ "$RPS_COUNT" -gt 0 ]; then
        AVG_RPS=$(echo "scale=2; $RPS_SUM / $RPS_COUNT" | bc 2>/dev/null || echo "N/A")
    else
        AVG_RPS="N/A"
    fi

    # Build printf args
    ROW_ARGS=("$fw" "$lang")
    for ((c=0; c<NUM_COLS; c++)); do
        ROW_ARGS+=("${RPS_VALUES[$c]}")
    done
    ROW_ARGS+=("$AVG_RPS" "${RES_TH:-N/A}" "${RES_MEM:-N/A}")

    ROW_LINE=$(printf "$HEADER_FMT" "${ROW_ARGS[@]}")
    ROWS="${ROWS}${AVG_RPS} ${ROW_LINE}\n"
    unset RPS_VALUES
done

echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
    echo "$line" | awk '{$1=""; print substr($0,2)}'
done

echo "$DIVIDER"

# Check for DELETE non-2xx warnings
if should_run "delete"; then
    echo ""
    DEL_ISSUES=false
    for idx in "${ACTIVE_INDICES[@]}"; do
        fw="${FW_NAMES[$idx]}"
        RESULT_FILE="$RESULTS_DIR/${fw}_${TIMESTAMP}.txt"
        [ ! -f "$RESULT_FILE" ] && continue
        NON2XX=$(awk '/^--- DELETE ---$/,/^--- (BULK|LIST|PROFILE-PUT|PROFILE-GET|RESOURCES) ---$/' "$RESULT_FILE" | grep "Non-2xx" | awk '{print $NF}')
        if [ -n "$NON2XX" ] && [ "$NON2XX" != "0" ]; then
            echo "  WARNING: $fw DELETE had $NON2XX non-2xx responses (pool exhausted)"
            DEL_ISSUES=true
        fi
    done
    if [ "$DEL_ISSUES" = false ]; then
        echo "  All DELETE benchmarks returned 200 (verified)"
    fi
fi

# Append comparison table to summary file
{
    echo ""
    echo "Throughput (sorted by average of benchmarked endpoints):"
    echo "  Threads/Memory: single sample during load — indicative, not precise."
    echo ""
    printf "$HEADER_FMT" "${HEADER_ARGS[@]}"
    echo "$DIVIDER"
    echo -e "$ROWS" | sort -t' ' -k1 -rn | while IFS= read -r line; do
        echo "$line" | awk '{$1=""; print substr($0,2)}'
    done
    echo "$DIVIDER"
} >> "$SUMMARY_FILE"
