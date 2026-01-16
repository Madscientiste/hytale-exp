#!/bin/sh
# Common utilities and logging functions for Hytale server scripts

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Log levels
LOG_ERROR=1
LOG_WARN=2
LOG_INFO=3
LOG_DEBUG=4

# Default log level (can be overridden by LOG_LEVEL env var)
DEFAULT_LOG_LEVEL=${LOG_LEVEL:-$LOG_INFO}

# Get current log level
get_log_level() {
    case "${LOG_LEVEL:-INFO}" in
        ERROR|error) echo $LOG_ERROR ;;
        WARN|warn) echo $LOG_WARN ;;
        INFO|info) echo $LOG_INFO ;;
        DEBUG|debug) echo $LOG_DEBUG ;;
        *) echo $LOG_INFO ;;
    esac
}

# Check if we should log at this level
should_log() {
    local level=$1
    local current_level=$(get_log_level)
    [ $level -le $current_level ]
}

# Log functions
log_error() {
    if should_log $LOG_ERROR; then
        echo -e "${RED}[ERROR]${NC} $*" >&2
    fi
}

log_warn() {
    if should_log $LOG_WARN; then
        echo -e "${YELLOW}[WARN]${NC} $*" >&2
    fi
}

log_info() {
    if should_log $LOG_INFO; then
        echo -e "${CYAN}[INFO]${NC} $*"
    fi
}

log_debug() {
    if should_log $LOG_DEBUG; then
        echo -e "${BLUE}[DEBUG]${NC} $*"
    fi
}

log_success() {
    if should_log $LOG_INFO; then
        echo -e "${GREEN}[SUCCESS]${NC} $*"
    fi
}

# Section header
log_section() {
    if should_log $LOG_INFO; then
        echo ""
        echo -e "${BOLD}${MAGENTA}==========================================${NC}"
        echo -e "${BOLD}${MAGENTA}  $*${NC}"
        echo -e "${BOLD}${MAGENTA}==========================================${NC}"
        echo ""
    fi
}

# Step indicator
log_step() {
    if should_log $LOG_INFO; then
        echo -e "${CYAN}Step $1:${NC} $2"
    fi
}

# Error exit function
error_exit() {
    log_error "$1"
    exit 1
}

# Check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
check_prerequisites() {
    local missing=0
    
    for cmd in "$@"; do
        if ! command_exists "$cmd"; then
            log_error "$cmd is required but not installed"
            missing=$((missing + 1))
        fi
    done
    
    if [ $missing -gt 0 ]; then
        error_exit "Missing $missing required prerequisite(s)"
    fi
}
