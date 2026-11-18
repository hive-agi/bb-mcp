# Sourced by start-bb-mcp.sh. Derives the session token bb-mcp stamps into
# its caller id (`coordinator:<token>`), kanban 20261111164432-2be7b1e6.
#
# The token used to be the parent pid: the Claude Code process. One Claude
# Code session can move between processes (a resume, a bg-spare handoff), so
# its pid, and with it the coordinator id, changed mid-session; kanban claims,
# ss receptors and ling parentage stayed with the dead id. Claude Code keeps
# one record per session process, <sessions-dir>/<pid>.json, holding the
# session's `sessionId`, which does not change when the process does. The
# token is the first 8 hex digits of that id (the shape hive-dsl's
# session-id-shape? accepts, and that holds no '-', which ends a session token
# in a coordinator id). With no readable record the token stays the pid.

# claude_session_token PID [SESSIONS_DIR [WAIT_MS]]
#   Prints the 8-hex session token of the Claude Code session PID hosts, or
#   PID itself when no record names one. Waits up to WAIT_MS (default 500)
#   for the record, since the client may write it just after it starts its
#   MCP servers; no wait at all when SESSIONS_DIR does not exist.
claude_session_token() {
    local pid="$1"
    local dir="${2:-${CLAUDE_SESSIONS_DIR:-$HOME/.claude/sessions}}"
    local wait_ms="${3:-${BB_MCP_SESSION_WAIT_MS:-500}}"
    local file="$dir/$pid.json" token="" waited=0
    if [[ -d "$dir" ]]; then
        while [[ ! -r "$file" ]] && (( waited < wait_ms )); do
            sleep 0.1
            waited=$(( waited + 100 ))
        done
        if [[ -r "$file" ]]; then
            token="$(grep -o '"sessionId"[[:space:]]*:[[:space:]]*"[0-9a-f]\{8\}' "$file" 2>/dev/null \
                     | head -1 | grep -o '[0-9a-f]\{8\}$' || true)"
        fi
    fi
    if [[ "$token" =~ ^[0-9a-f]{8}$ ]]; then
        echo "$token"
    else
        echo "$pid"
    fi
}
