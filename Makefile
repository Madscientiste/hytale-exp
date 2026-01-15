.PHONY: help build build-only test deploy run clean rcon-build

# FIX; currently i don't really like what i've done
# sure it works but since this is a monorpo i don't want to 
# manually add modules; maybe i'll take inspiration from AOSP
# as they have a similar structure

# Default target
help:
	@echo "Hytale Mod Development - Available commands:"
	@echo ""
	@echo "  make build       - Build all projects and deploy to .server/mods/"
	@echo "  make build-only  - Build all projects (no deployment)"
	@echo "  make test        - Run all tests"
	@echo "  make deploy      - Deploy built JARs to .server/mods/"
	@echo "  make run         - Run full Hytale server (requires assets)"
	@echo "  make run-bare    - Run server in bare mode (plugin testing, no worlds)"
	@echo "  make clean       - Clean all build artifacts"
	@echo "  make rcon-build  - Build rcon project and deploy to .server/mods/"
	@echo ""

# Build and deploy
build:
	@echo "🔨 Building and deploying..."
	./gradlew buildAndDeploy

# Build only (no deployment)
build-only:
	@echo "🔨 Building..."
	./gradlew build

# Run tests
test:
	@echo "🧪 Running tests..."
	./gradlew test

# Deploy only
deploy:
	@echo "📦 Deploying plugins..."
	./gradlew deploy

# Run server (full mode, requires assets)
run:
	@echo "🚀 Starting Hytale server (requires assets)..."
	.server/run.sh

# Run server in bare mode (for plugin testing without full server setup)
run-bare:
	@echo "🚀 Starting Hytale server in bare mode (plugin testing)..."
	.server/run.sh --bare

# Clean build artifacts
clean:
	@echo "🧹 Cleaning build artifacts..."
	./gradlew clean
	@echo "✓ Clean complete"

# Build rcon project and deploy
rcon-build:
	@echo "🔨 Building rcon project and deploying..."
	./gradlew :rcon:build
	@mkdir -p .server/mods
	@for jar in projects/rcon/build/libs/*.jar; do \
		if [ -f "$$jar" ]; then \
			cp "$$jar" .server/mods/; \
			echo "✓ Deployed: $$(basename $$jar) -> .server/mods/"; \
		fi; \
	done
	@echo "✓ Rcon build and deploy complete"

