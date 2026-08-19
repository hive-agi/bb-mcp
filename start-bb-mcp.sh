#!/usr/bin/env bash
# Start bb-mcp with project configuration
#
# Usage: start-bb-mcp.sh /path/to/project [nrepl-port]
#
# Environment variables:
#   BB_MCP_PROJECT_DIR - Project directory (default: $1 or cwd)
#   BB_MCP_NREPL_PORT  - nREPL port (default: from .nrepl-port or $2)
#   HIVE_MCP_DIR      - Path to hive-mcp/hive-mcp (required for auto-spawn)
#   BB_MCP_RUNTIME    - cljw (default) or bb
#   CLJW_BIN          - cljw binary (default: config.edn :runtimes :cljw :binary, then PATH)
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
#   BB_MCP_RUNTIME=cljw  (default) ClojureWasm, classpath given explicitly
#   BB_MCP_RUNTIME=bb              babashka, using bb.edn for the classpath
#   CLJW_BIN                       cljw binary; falls back to config.edn
#                                  :runtimes :cljw :binary, then `cljw` on PATH
HIVE_CONFIG="${HIVE_MCP_CONFIG:-$HOME/.config/hive-mcp/config.edn}"

config_cljw_bin() {
    [[ -r "$HIVE_CONFIG" ]] || return 0
    grep -o ':runtimes[[:space:]]*{[^}]*:cljw[[:space:]]*{[^}]*:binary[[:space:]]*"[^"]*"' "$HIVE_CONFIG" \
        | tail -1 | grep -o '"[^"]*"$' | tr -d '"' || true
}

# True when the cljw binary provides cljw.net/connect (the nREPL client's socket).
cljw_has_net() {
    [[ -x "$1" || -x "$(command -v "$1" 2>/dev/null || echo /nonexistent)" ]] || return 1
    "$1" -e '(println (some? (resolve (quote cljw.net/connect))))' </dev/null 2>/dev/null | grep -qx true
}

start_bb() {
    exec bb -m bb-mcp.core
}

start_cljw() {
    export BB_MCP_SESSION_ID="${BB_MCP_SESSION_ID:-$PPID}"
    exec "$1" -cp src -m bb-mcp.core
}

RUNTIME="${BB_MCP_RUNTIME:-cljw}"
RUNTIME_EXPLICIT="${BB_MCP_RUNTIME:+yes}"

case "$RUNTIME" in
    cljw)
        CLJW="${CLJW_BIN:-$(config_cljw_bin)}"
        CLJW="${CLJW:-cljw}"
        if cljw_has_net "$CLJW"; then
            start_cljw "$CLJW"
        fi
        echo "start-bb-mcp.sh: '$CLJW' does not provide cljw.net/connect — the nREPL client cannot run." >&2
        if [[ -n "${CLJW_BIN:-}" ]] || ! command -v bb >/dev/null 2>&1; then
            echo "start-bb-mcp.sh: build ClojureWasm main (zig build -Dwasm -Doptimize=ReleaseFast), or set CLJW_BIN, or run with BB_MCP_RUNTIME=bb." >&2
            exit 3
        fi
        echo "start-bb-mcp.sh: falling back to babashka." >&2
        start_bb
        ;;
    bb)
        start_bb
        ;;
    *)
        echo "start-bb-mcp.sh: unknown BB_MCP_RUNTIME '${BB_MCP_RUNTIME}' (want bb or cljw)" >&2
        exit 2
        ;;
esac
