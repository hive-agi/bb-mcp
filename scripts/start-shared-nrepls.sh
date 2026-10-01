#!/usr/bin/env bash
# Start shared nREPLs for all projects
# Run this ONCE at startup, then bb-mcp instances connect to them

set -euo pipefail

# Projects as name -> "dir:port:command". Checkout locations come from env:
#   HIVE_MCP_SRC       hive-mcp checkout       (default: $HOME/hive-mcp)
#   CLOJURE_ELISP_SRC  clojure-elisp checkout  (default: $HOME/clojure-elisp)
# A project whose directory does not exist is skipped.
declare -A PROJECTS=(
    ["clojure-elisp"]="${CLOJURE_ELISP_SRC:-$HOME/clojure-elisp}:7920:clojure -M:dev"
    ["hive-mcp"]="${HIVE_MCP_SRC:-$HOME/hive-mcp}:7910:clojure -M:dev"
    # Add more projects as needed
)

start_nrepl() {
    local name="$1"
    local config="$2"

    IFS=':' read -r dir port cmd <<< "$config"

    if [[ ! -d "$dir" ]]; then
        echo "- $name: $dir not found, skipping"
        return 0
    fi

    # Check if already running
    if [[ -f "$dir/.nrepl-port" ]]; then
        existing_port=$(cat "$dir/.nrepl-port")
        if nc -z localhost "$existing_port" 2>/dev/null; then
            echo "✓ $name already running on port $existing_port"
            return 0
        fi
    fi

    echo "Starting nREPL for $name on port $port..."
    cd "$dir"

    # Start nREPL in background
    nohup $cmd -p "$port" > "/tmp/nrepl-$name.log" 2>&1 &

    # Wait for .nrepl-port
    for i in {1..30}; do
        if [[ -f "$dir/.nrepl-port" ]]; then
            echo "✓ $name started on port $(cat "$dir/.nrepl-port")"
            return 0
        fi
        sleep 1
    done

    echo "✗ $name failed to start"
    return 1
}

echo "=== Starting Shared nREPLs ==="
echo ""

for name in "${!PROJECTS[@]}"; do
    start_nrepl "$name" "${PROJECTS[$name]}" || true
done

echo ""
echo "=== Summary ==="
echo "Memory usage: ~500MB per running nREPL (shared by all swarm agents)"
echo "bb-mcp instances: ~50MB each (connect to shared nREPL)"
