#!/usr/bin/env bash
# Start bb-mcp with project configuration
#
# Usage: start-bb-mcp.sh [/path/to/project [nrepl-port]]
#
# MCP clients run it with NO args through the anchor
# ~/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh (see `bb-mcp setup`): the
# project dir defaults to the client's cwd and the nREPL port to
# <project>/.nrepl-port, else 7910.
#
# Environment variables:
#   BB_MCP_PROJECT_DIR - Project directory (default: $1 or cwd)
#   BB_MCP_NREPL_PORT  - nREPL port (default: from .nrepl-port or $2)
#   BB_MCP_RUNTIME    - cljw (default) or bb
#   BB_MCP_TOOL_SCHEMA - full (default) or compact: advertise only each tool's core
#                        parameters and keep the rest accepted (bb-mcp.tools.hive.compact)
#   CLJW_BIN          - cljw binary (default: config.edn :runtimes :cljw :binary, then every cljw on PATH)
#   EMACS_SOCKET_NAME - Emacs daemon socket name for isolation (optional)
#   BB_MCP_CHANNELS   - 1 to arm the sixth-sense channel receptor (opt-in; off by
#                       default, so the session never polls the hive JVM). Pair
#                       with `claude --dangerously-load-development-channels server:<name>`
#   BB_MCP_SENSE_PARENT   - receptor parent filter (default: CLAUDE_SWARM_SLAVE_ID, else
#                           this session's caller id coordinator:<instance>; * or all = no filter)
#   BB_MCP_SENSE_PROJECTS / BB_MCP_SENSE_CLASSES - comma lists narrowing the senses
#                           (classes e.g. ask,blocked,completed,truncated,error)
#   BB_MCP_SENSE_POLL_MS  - poll interval (default 1000); BB_MCP_SENSE_REPLAY=1 skips priming
#   BB_MCP_NO_UPDATE_CHECK - 1 turns off the one-line stderr notice printed when a
#                           newer bb-mcp release exists (checked at most once a day)

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
#   CLJW_BIN                       cljw binary; then config.edn :runtimes :cljw
#                                  :binary (optional), then every `cljw` on PATH.
#                                  The first that exists and has cljw.net wins.
HIVE_CONFIG="${HIVE_MCP_CONFIG:-$HOME/.config/hive-mcp/config.edn}"

config_cljw_bin() {
    [[ -r "$HIVE_CONFIG" ]] || return 0
    grep -o ':runtimes[[:space:]]*{[^}]*:cljw[[:space:]]*{[^}]*:binary[[:space:]]*"[^"]*"' "$HIVE_CONFIG" \
        | tail -1 | grep -o '"[^"]*"$' | tr -d '"' || true
}

# True when the cljw binary provides cljw.net/connect (the nREPL client's socket).
cljw_has_net() {
    "$1" -e '(println (some? (resolve (quote cljw.net/connect))))' </dev/null 2>/dev/null | grep -qx true
}

# Candidate cljw binaries, in precedence order: CLJW_BIN, config.edn
# :runtimes :cljw :binary, then every `cljw` on PATH. No path is assumed.
cljw_candidates() {
    [[ -n "${CLJW_BIN:-}" ]] && echo "$CLJW_BIN"
    config_cljw_bin
    type -ap cljw 2>/dev/null || true
}

# Prints the first candidate that exists and provides cljw.net/connect;
# reports each rejected candidate on stderr with the reason.
resolve_cljw() {
    local c path
    while IFS= read -r c; do
        [[ -n "$c" ]] || continue
        path="$(command -v "$c" 2>/dev/null || true)"
        if [[ -z "$path" || ! -x "$path" ]]; then
            echo "start-bb-mcp.sh: cljw candidate '$c' not found or not executable, skipping." >&2
        elif cljw_has_net "$path"; then
            echo "$path"
            return 0
        else
            echo "start-bb-mcp.sh: '$path' does not provide cljw.net/connect, skipping." >&2
        fi
    done < <(cljw_candidates | awk '!seen[$0]++')
    return 1
}

start_bb() {
    exec bb -m bb-mcp.core
}

# The release notice needs a subprocess (git ls-remote), which cljw lacks, so
# on cljw it runs as a detached `bb-mcp notice` beside the server: stdin and
# stdout closed (stdout is the MCP channel), stderr shared. It never delays the
# exec below, and BB_MCP_RELEASE_NOTICE tells the server it was already done.
start_release_notice() {
    case "${BB_MCP_NO_UPDATE_CHECK:-}" in ""|0|false|FALSE) ;; *) return 0 ;; esac
    command -v bb >/dev/null 2>&1 || return 0
    export BB_MCP_RELEASE_NOTICE=launcher
    (bb "$SCRIPT_DIR/bb-mcp" notice </dev/null >/dev/null &) || true
}

start_cljw() {
    export BB_MCP_SESSION_ID="${BB_MCP_SESSION_ID:-$PPID}"
    start_release_notice
    exec "$1" -cp src -m bb-mcp.core
}

RUNTIME="${BB_MCP_RUNTIME:-cljw}"
RUNTIME_EXPLICIT="${BB_MCP_RUNTIME:+yes}"

case "$RUNTIME" in
    cljw)
        if CLJW="$(resolve_cljw)"; then
            start_cljw "$CLJW"
        fi
        echo "start-bb-mcp.sh: no usable cljw (with cljw.net/connect); the nREPL client cannot run on cljw." >&2
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
