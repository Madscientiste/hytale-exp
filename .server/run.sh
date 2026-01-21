#!/bin/bash
# Hytale Server Runner Script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/.server"
LIBS_DIR="$PROJECT_ROOT/libs"
MODS_DIR="$SERVER_DIR/mods"
UNIVERSE_DIR="$SERVER_DIR/universe"

# Check if HytaleServer.jar exists
if [ ! -f "$LIBS_DIR/HytaleServer.jar" ]; then
    echo "   Error: HytaleServer.jar not found at $LIBS_DIR/HytaleServer.jar"
    echo "   Please download it from the official portal and place it in libs/"
    exit 1
fi

# Ensure directories exist
mkdir -p "$MODS_DIR"
mkdir -p "$UNIVERSE_DIR"

# Default server options
AUTH_MODE="${AUTH_MODE:-authenticated}"
BIND_ADDRESS="${BIND_ADDRESS:-0.0.0.0:5520}"
TRANSPORT="${TRANSPORT:-QUIC}"
BARE_MODE="${BARE_MODE:-false}"

# Parse command line arguments
ARGS=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --auth-mode)
            AUTH_MODE="$2"
            shift 2
            ;;
        --bind|-b)
            BIND_ADDRESS="$2"
            shift 2
            ;;
        --transport|-t)
            TRANSPORT="$2"
            shift 2
            ;;
        --bare)
            BARE_MODE="true"
            shift
            ;;
        --help|-h)
            echo "Hytale Server Runner"
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --auth-mode <mode>     Authentication mode (authenticated|offline|insecure) [default: offline]"
            echo "  --bind, -b <address>   Bind address (default: 0.0.0.0:5520)"
            echo "  --transport, -t <type> Transport type (default: QUIC)"
            echo "  --bare                 Run in bare mode (no worlds/ports, for plugin testing)"
            echo "  --help, -h              Show this help message"
            echo ""
            echo "Environment variables:"
            echo "  AUTH_MODE              Set default auth mode"
            echo "  BIND_ADDRESS            Set default bind address"
            echo "  TRANSPORT               Set default transport type"
            echo ""
            echo "Examples:"
            echo "  $0                                    # Run with defaults"
            echo "  $0 --auth-mode authenticated          # Run with authentication"
            echo "  $0 -b 0.0.0.0:25565                  # Run on port 25565"
            exit 0
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

# Build server command
# Don't pass --mods if server auto-detects mods/ in current directory
# Change to server directory first so relative paths work
cd "$SERVER_DIR"

# Build command array
CMD=(
    java
    --add-opens java.base/java.lang=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED
    -jar "$LIBS_DIR/HytaleServer.jar"
)

# Add assets if Assets.zip exists
if [ -f "$LIBS_DIR/Assets.zip" ]; then
    CMD+=(--assets "$LIBS_DIR/Assets.zip")
fi

# Add bare mode if enabled (for plugin testing without full server setup)
if [ "$BARE_MODE" = "true" ]; then
    CMD+=(--bare)
else
    CMD+=(--universe "universe")
    CMD+=(--bind "$BIND_ADDRESS")
fi

CMD+=(
    --auth-mode "$AUTH_MODE"
    --transport "$TRANSPORT"
    "${ARGS[@]}"
)

echo "🚀 Starting Hytale Server..."
echo "   Mods: $MODS_DIR (auto-detected from current directory)"
if [ -f "$LIBS_DIR/Assets.zip" ]; then
    echo "   Assets: $LIBS_DIR/Assets.zip"
else
    echo "   Assets: Not found (server may fail without assets)"
fi
if [ "$BARE_MODE" = "true" ]; then
    echo "   Mode: BARE (plugin testing mode - no worlds/ports)"
else
    echo "   Universe: $UNIVERSE_DIR"
    echo "   Bind: $BIND_ADDRESS"
fi
echo "   Auth Mode: $AUTH_MODE"
echo "   Transport: $TRANSPORT"
echo ""

# Run from server directory (mods/ will be auto-detected)
exec "${CMD[@]}"

