#!/usr/bin/env bash
# Start bb-mcp with project configuration
#
# Usage: start-bb-mcp.sh /path/to/project [nrepl-port]
#
# Environment variables:
#   BB_MCP_PROJECT_DIR - Project directory (default: $1 or cwd)
#   BB_MCP_NREPL_PORT  - nREPL port (default: from .nrepl-port or $2)
#   HIVE_MCP_DIR      - Path to hive-mcp/hive-mcp (required for auto-spawn)
#   EMACS_SOCKET_NAME - Emacs daemon socket name for isolation (optional)

set -euo pipefail

# Get the directory where this script lives (bb-mcp root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Caller's invocation cwd (the Claude session's pwd) — captured BEFORE the
# explicit project-dir arg and the cd below erase it. Drives HCR scope
# resolution (kanban/memory default to this dir's .hive-project.edn).
export BB_MCP_CALLER_CWD="${BB_MCP_CALLER_CWD:-$PWD}"

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

# Start bb-mcp on the selected runtime.
#
#   BB_MCP_RUNTIME=bb    (default) babashka, using bb.edn for the classpath
#   BB_MCP_RUNTIME=cljw            ClojureWasm, classpath given explicitly
#   CLJW_BIN                       cljw binary to use (default: cljw on PATH)
#
# cljw has no process introspection, so the session id — which keeps per-session
# cursors stable across a bb-mcp restart — has to be handed to it. $PPID here is
# the process that launched this script, which is the same identity the babashka
# arm derives from ProcessHandle.
case "${BB_MCP_RUNTIME:-bb}" in
    cljw)
        export BB_MCP_SESSION_ID="${BB_MCP_SESSION_ID:-$PPID}"
        exec "${CLJW_BIN:-cljw}" -cp src -m bb-mcp.core
        ;;
    bb|"")
        exec bb -m bb-mcp.core
        ;;
    *)
        echo "start-bb-mcp.sh: unknown BB_MCP_RUNTIME '${BB_MCP_RUNTIME}' (want bb or cljw)" >&2
        exit 2
        ;;
esac
