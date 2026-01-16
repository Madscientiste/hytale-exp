.PHONY: help build build-only test deploy run clean rcon-build release password

# Default target
help:
	@echo "Hytale Mod Development - Available commands:"
	@echo ""
	@echo "  make build       - Build all projects and deploy to .server/mods/"
	@echo "  make build-only  - Build all projects (no deployment)"
	@echo "  make test        - Run all tests"
	@echo "  make deploy      - Deploy built JARs to .server/mods/"
	@echo "  make run         - Run full Hytale server (requires assets)"
	@echo "  make clean       - Clean all build artifacts"
	@echo "  make rcon-build  - Build rcon project and deploy to .server/mods/"
	@echo "  make password    - Generate password hash for RCON authentication"
	@echo "                     Usage: make password PASSWORD=your_password_here"
	@echo "  make release     - Create a new release"
	@echo ""
	@echo "                     Usage: make release TYPE=patch|minor|major PROJECT=project1[,project2]"
	@echo "                     Examples:"
	@echo "                       make release TYPE=patch PROJECT=rcon"
	@echo "                       make release TYPE=minor PROJECT=playground"
	@echo "                       make release TYPE=patch PROJECT=rcon,playground"
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

# Generate password hash
password:
	@if [ -z "$(PASSWORD)" ]; then \
		echo "❌ Error: PASSWORD is required"; \
		echo "Usage: make password PASSWORD=your_password_here"; \
		exit 1; \
	fi
	@echo "🔐 Generating password hash..."
	./gradlew :rcon:hashPassword -Ppassword="$(PASSWORD)" --no-daemon
	@echo "✓ Password hash generated"

# Release
release:
	@if [ -z "$(TYPE)" ]; then \
		echo "❌ Error: TYPE is required"; \
		echo "Usage: make release TYPE=patch|minor|major PROJECT=project1[,project2]"; \
		exit 1; \
	fi
	@if [ -z "$(PROJECT)" ]; then \
		echo "❌ Error: PROJECT is required"; \
		echo "Usage: make release TYPE=patch|minor|major PROJECT=project1[,project2]"; \
		exit 1; \
	fi
	@./tools/release.sh $(TYPE) $(PROJECT)

