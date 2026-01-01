#!/usr/bin/env bash
# Start bb-mcp with project configuration
#
# Usage: start-bb-mcp.sh /path/to/project [nrepl-port]
#
# Environment variables:
#   BB_MCP_PROJECT_DIR - Project directory (default: $1 or cwd)
#   BB_MCP_NREPL_PORT  - nREPL port (default: from .nrepl-port or $2)

set -euo pipefail

# Get the directory where this script lives (bb-mcp root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PROJECT_DIR="${1:-${BB_MCP_PROJECT_DIR:-$(pwd)}}"
NREPL_PORT="${2:-${BB_MCP_NREPL_PORT:-}}"

# Auto-detect nREPL port if not provided
if [[ -z "$NREPL_PORT" ]] && [[ -f "$PROJECT_DIR/.nrepl-port" ]]; then
    NREPL_PORT=$(cat "$PROJECT_DIR/.nrepl-port")
fi

export BB_MCP_PROJECT_DIR="$PROJECT_DIR"
export BB_MCP_NREPL_PORT="${NREPL_PORT:-}"

# Change to bb-mcp directory (where bb.edn is)
cd "$SCRIPT_DIR"

# Start bb-mcp
exec bb -m bb-mcp.core
