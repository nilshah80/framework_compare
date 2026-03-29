FRAMEWORKS = aarv aarv-segmentio gin fiber echo mach
PORTS = 8081 8086 8082 8083 8084 8085

# Build all frameworks
.PHONY: build
build:
	@mkdir -p bin
	@for fw in $(FRAMEWORKS); do \
		echo "Building $$fw..."; \
		(cd $$fw && go mod tidy && go build -o ../bin/$$fw .); \
	done
	@echo "All frameworks built successfully!"

# Build a single framework
.PHONY: build-%
build-%:
	@mkdir -p bin
	@echo "Building $*..."
	@(cd $* && go mod tidy && go build -o ../bin/$* .)

# Run individual frameworks
.PHONY: run-aarv run-gin run-fiber run-echo run-mach
run-aarv:
	@cd aarv && go run . 2>/dev/null
run-gin:
	@cd gin && go run . 2>/dev/null
run-fiber:
	@cd fiber && go run . 2>/dev/null
run-echo:
	@cd echo && go run . 2>/dev/null
run-mach:
	@cd mach && go run . 2>/dev/null

# Start all frameworks in background
.PHONY: start-all
start-all: build
	@mkdir -p logs
	@echo "Starting all frameworks..."
	@./bin/aarv           > logs/aarv.log           2>&1 & echo "Aarv           started on :8081 (PID: $$!)"
	@./bin/aarv-segmentio > logs/aarv-segmentio.log 2>&1 & echo "Aarv-Segmentio started on :8086 (PID: $$!)"
	@./bin/gin            > logs/gin.log            2>&1 & echo "Gin            started on :8082 (PID: $$!)"
	@./bin/fiber          > logs/fiber.log          2>&1 & echo "Fiber          started on :8083 (PID: $$!)"
	@./bin/echo           > logs/echo.log           2>&1 & echo "Echo           started on :8084 (PID: $$!)"
	@./bin/mach           > logs/mach.log           2>&1 & echo "Mach           started on :8085 (PID: $$!)"
	@sleep 2
	@echo "\nAll frameworks started. Use 'make stop-all' to stop them."

# Stop all frameworks
.PHONY: stop-all
stop-all:
	@echo "Stopping all frameworks..."
	@-pkill -f "bin/aarv-segmentio" 2>/dev/null || true
	@-pkill -f "bin/aarv"  2>/dev/null || true
	@-pkill -f "bin/gin"   2>/dev/null || true
	@-pkill -f "bin/fiber" 2>/dev/null || true
	@-pkill -f "bin/echo"  2>/dev/null || true
	@-pkill -f "bin/mach"  2>/dev/null || true
	@echo "All frameworks stopped."

# Run benchmark
.PHONY: benchmark
benchmark:
	@./benchmark.sh

# Run quick smoke test
.PHONY: test
test:
	@echo "Testing all endpoints..."
	@echo "\n--- Aarv (8081) ---"
	@curl -s -H "X-Api-Key: test-token" "http://localhost:8081/users/user123/orders/42?fields=id,status" | head -1
	@echo "\n--- Gin (8082) ---"
	@curl -s -H "X-Api-Key: test-token" "http://localhost:8082/users/user123/orders/42?fields=id,status" | head -1
	@echo "\n--- Fiber (8083) ---"
	@curl -s -H "X-Api-Key: test-token" "http://localhost:8083/users/user123/orders/42?fields=id,status" | head -1
	@echo "\n--- Echo (8084) ---"
	@curl -s -H "X-Api-Key: test-token" "http://localhost:8084/users/user123/orders/42?fields=id,status" | head -1
	@echo "\n--- Mach (8085) ---"
	@curl -s -H "X-Api-Key: test-token" "http://localhost:8085/users/user123/orders/42?fields=id,status" | head -1
	@echo ""

# Clean build artifacts
.PHONY: clean
clean:
	@rm -rf bin/ logs/ results/
	@echo "Cleaned."

# Tidy all go modules
.PHONY: tidy
tidy:
	@for fw in $(FRAMEWORKS); do \
		echo "Tidying $$fw..."; \
		cd $$fw && go mod tidy && cd ..; \
	done
