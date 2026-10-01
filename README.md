# bb-mcp

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/bb-mcp.svg)](https://clojars.org/io.github.hive-agi/bb-mcp)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/bb-mcp)](https://cljdoc.org/d/io.github.hive-agi/bb-mcp/CURRENT)
[![release](https://github.com/hive-agi/bb-mcp/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/bb-mcp/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

Lightweight MCP (Model Context Protocol) server in Babashka that bridges Claude Code to Emacs via nREPL.

## OpenClaw ACP

`bb-mcp.acp` is a portable `.cljc` JSON-RPC client for OpenClaw's ACP
methods: `initialize`, `session/new`, `session/load`, `session/prompt`, and
`session/cancel`. It accepts an injected `exchange!` function, so the same
message layer works over bb-mcp stdio, the cljw TCP adapter, JVM sockets, or a
ClojureScript transport. `hive-claw` should consume this seam instead of
inventing a second ACP wire format.

## Why bb-mcp?

**The Problem:** Running multiple Claude Code instances (e.g., swarm agents) each with their own JVM-based MCP server consumes massive resources.

**The Solution:** bb-mcp is a lightweight **multiplexer** - many Babashka instances share ONE JVM.

| Scenario | Without bb-mcp | With bb-mcp |
|----------|----------------|-------------|
| 1 Claude | ~500MB | ~550MB (50MB bb + 500MB JVM) |
| 3 Claudes | ~1.5GB (3 JVMs) | **~650MB** (3 bb + 1 JVM) |
| 5 Claudes | ~2.5GB (5 JVMs) | **~750MB** (5 bb + 1 JVM) |
| 10 Claudes | ~5GB (10 JVMs) | **~1GB** (10 bb + 1 JVM) |

### Key Metrics

| Metric | JVM hive-mcp | bb-mcp |
|--------|---------------|--------|
| Startup | ~2-3s | **~5ms** |
| Memory | ~500MB | **~50MB** |
| Scales to | 1 instance | **Many instances** |

### Use Case: Claude Swarm

When running swarm agents (multiple Claudes working in parallel), each agent needs an MCP connection to Emacs. Without bb-mcp, you'd need separate JVM processes. With bb-mcp:

- **1 hive-mcp** (JVM) handles Emacs integration
- **N bb-mcp** instances (Babashka) multiplex to it
- All agents share tools, memory, kanban, git via the single JVM

## Architecture

```
                    bb-mcp Instances              Shared JVM
                    ┌──────────────┐
  Claude 1 ───────▶ │   bb-mcp     │──┐
                    └──────────────┘  │
                    ┌──────────────┐  │     ┌─────────────────┐
  Claude 2 ───────▶ │   bb-mcp     │──┼────▶│   hive-mcp     │
                    └──────────────┘  │     │   (nREPL:7910)  │
                    ┌──────────────┐  │     └────────┬────────┘
  Claude 3 ───────▶ │   bb-mcp     │──┘              │
                    └──────────────┘                 ▼
                       ~50MB each              ┌─────────────┐
                                               │    Emacs    │
                                               └─────────────┘
```

**Data Flow:**
1. Claude Code connects to bb-mcp via MCP protocol (stdio)
2. bb-mcp handles native tools directly (bash, grep, file ops)
3. Emacs-related tools delegate to hive-mcp via nREPL on port 7910
4. hive-mcp executes elisp in Emacs via emacsclient

## Prerequisites

- [Babashka](https://babashka.org/) v1.3+ (required: `bb-mcp setup` and the `bb` runtime)
- [ripgrep](https://github.com/BurntSushi/ripgrep) (for the grep tool)
- [hive-mcp](https://github.com/hive-agi/hive-mcp), the JVM backend bb-mcp talks to, serving nREPL (default port 7910)

## Installation

Install with `curl -fsSL https://hive-mcp.com/install.sh | sh` then `hive setup`.
`hive setup` installs bb-mcp, publishes it at `~/.local/share/hive-mcp/bb-mcp`,
and registers `hive` with your MCP clients (Claude Code, Codex) through bb-mcp.
Already have bb-mcp checked out somewhere? Run `<that checkout>/bb-mcp setup`:
wherever bb-mcp lives becomes the reference. Verify with `bb-mcp setup --check`.

### The reference: one anchor for every client

Wherever bb-mcp is checked out is the reference. `bb-mcp setup` publishes it at
one well-known anchor, a symlink:

```
~/.local/share/hive-mcp/bb-mcp  ->  <your bb-mcp checkout>
```

Every MCP client launches hive through the anchor, never through a
user-specific checkout path, so moving or switching the checkout is one rerun
of `bb-mcp setup` and no client config changes.

### `bb-mcp setup`

```bash
<checkout>/bb-mcp setup [--client claude|codex|all] [--scope user|project] \
                        [--port N] [--dry-run] [--check] [--json]
# or, from the checkout:
bb setup [same options]
```

Every step is idempotent and reported as a row (`ok`, `changed`, `planned`,
`skipped`, `refused`, `failed`):

| Step | What it does |
|------|--------------|
| `anchor` | Creates `~/.local/share/hive-mcp/` and the symlink to this checkout. A symlink pointing elsewhere is replaced and its previous target reported. A real directory or file at the anchor is refused, never deleted. |
| `claude:user` | Default when the `claude` CLI is on PATH: `claude mcp remove --scope user hive`, then `claude mcp add --scope user hive -- ~/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh` (with `-e BB_MCP_NREPL_PORT=N` under `--port`). |
| `claude:project` | With `--scope project`, run from a project dir: writes or merges `.mcp.json` in the cwd with the literal `${HOME}/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh` command, keeping every other server. |
| `codex` | When `~/.codex` exists (or `--client codex`): upserts `[mcp_servers.hive]` in `~/.codex/config.toml` with the expanded anchor path, keeping every other table and comment, after backing the file up once as `config.toml.bak-<timestamp>`. |

Your own env vars on an existing hive entry (for example `BB_MCP_RUNTIME`)
are kept. `HIVE_MCP_DIR` is removed (bb-mcp never reads it), and
`BB_MCP_NREPL_PORT` is set only when you pass `--port`. `--dry-run` prints the
plan, ops included, without writing anything.

`bb-mcp setup --check` verifies that the anchor resolves to a directory with
an executable `start-bb-mcp.sh`, that babashka is on PATH, and that each
detected client's hive entry points at the anchor (flagging stale paths such as
the real checkout, another user's home, leftover `HIVE_MCP_DIR` or pinned
`args`). It also reports whether an nREPL answers on the resolved port (a
warning only). It exits non-zero when a check fails.

### Manual configuration

For a client that expands env vars in its JSON config (Claude Code's
`.mcp.json` does):

```json
{
  "mcpServers": {
    "hive": {
      "command": "${HOME}/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh"
    }
  }
}
```

For a client without expansion, write the expanded absolute path of the anchor
(`/home/<you>/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh`), never the path of
the real checkout. No args are needed, and env only when non-default. See
[`examples/mcp.json.example`](examples/mcp.json.example).

### The backend

bb-mcp is a **client**: it connects to an nREPL the hive-mcp JVM is already
serving, and it does not spawn or respawn one. Start the hive-mcp backend
(nREPL on 7910 by default) yourself; `hive setup` documents how. When it is
down, every tool call fails with `could not connect to 'localhost:7910'` until
the backend is started. That message means the backend, not the arguments of
the call that reported it.

## Configuration

### Project and nREPL port resolution

`start-bb-mcp.sh` takes no arguments. The project directory defaults to the
client's cwd, and the nREPL port is resolved in this order:

1. **Explicit parameter**: `port` in a tool call
2. **Environment variable**: `BB_MCP_NREPL_PORT`
3. **.nrepl-port file**: in `BB_MCP_PROJECT_DIR` (the client's cwd by default)
4. **Default**: port 7910 (hive-mcp)

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `BB_MCP_RUNTIME` | `cljw` (ClojureWasm) or `bb` (babashka). cljw falls back to bb when no usable cljw is found. | `cljw` |
| `BB_MCP_NREPL_PORT` | nREPL port of the hive-mcp backend | `<project>/.nrepl-port`, else 7910 |
| `BB_MCP_PROJECT_DIR` | Project directory (scopes tools; holds `.nrepl-port`) | the client's cwd |
| `BB_MCP_TOOL_SCHEMA` | `compact` advertises each tool's core parameters and still accepts the rest (per-command params on demand via `command='carto describe' of='<cmd>'`); `full` advertises every tool parameter | `compact` |
| `CLJW_BIN` | cljw binary for the cljw runtime | config.edn `:runtimes :cljw :binary`, then `cljw` on PATH |

Set these in the client's server entry only when you need a non-default
value. Never put `HIVE_MCP_DIR` in an MCP client config: bb-mcp does not read it.

### Sixth-sense channel receptor

With channels on, bb-mcp polls hive-agent's sixth sense over the same nREPL
link and pushes each sense (a ling's ask, block, completion, truncation,
error...) to Claude Code as a `notifications/claude/channel` event. The
event's meta carries `agent`, `sense_class`, `project`, `parent`, `sense_id`
and, when the sense has them, `termination`, `error_type` and `reason`; the
content ends with the same detail compactly (`[termination=max-turns]`).

| Variable | Description | Default |
|----------|-------------|---------|
| `BB_MCP_CHANNELS` | `1` arms the receptor (opt-in) | off |
| `BB_MCP_SENSE_PARENT` | Parent whose lings this session hears; `*` or `all` hears every swarm on the host | This session's caller id, the parent hive-mcp records verbatim for lings it spawns (`coordinator:<instance>` for a coordinator, `<slave-id>:<instance>` for a ling); `CLAUDE_SWARM_SLAVE_ID` only when no caller id is known |
| `BB_MCP_SENSE_PROJECTS` | Comma list of project ids | all |
| `BB_MCP_SENSE_CLASSES` | Comma list of classes, e.g. `ask,blocked,completed,truncated,error` | all |
| `BB_MCP_SENSE_POLL_MS` | Poll interval | 1000 |
| `BB_MCP_SENSE_REPLAY` | `1` delivers the backlog from before the session | off |

When the hive has hive-agent's per-consumer port (`sense-port`), the drain
cursor is kept in the hive under its own consumer id, `<caller-id>#channel`,
apart from the session's manual `ss drain` calls (which default to the bare
caller id and would otherwise share one consume-once cursor), so a restarted
bb-mcp resumes where it left off; a consumer the hive has never seen starts
at the log head. On an older hive-agent it falls back to the stateless
`api/drain` with a cursor held in bb-mcp, dropping the first backlog and
re-reading from 0 whenever the hive's sense log restarts (epoch change or a
cursor that went backwards).

## Tools

bb-mcp provides **114 tools** (6 native + 108 from hive-mcp).

### Native Tools (6)

Fast tools that run directly in Babashka without JVM:

| Tool | Description |
|------|-------------|
| `bash` | Execute shell commands |
| `read_file` | Read file contents |
| `file_write` | Write files to disk |
| `glob_files` | Find files by glob pattern |
| `grep` | Search content with ripgrep |
| `clojure_eval` | Evaluate Clojure via nREPL |

### Emacs Tools (108 dynamic)

Tools loaded dynamically from hive-mcp at startup:

| Domain | Tools | Description |
|--------|-------|-------------|
| **Buffer** | 19 | Buffer ops, elisp eval, file navigation |
| **Memory** | 17 | Project memory CRUD with TTL, semantic search |
| **Swarm** | 14 | Claude swarm orchestration, hivemind |
| **CIDER** | 12 | Clojure REPL, docs, completions |
| **Magit** | 10 | Git operations via Magit |
| **Kanban** | 8 | Task/kanban management |
| **Projectile** | 6 | Project navigation |
| **Org** | 6 | Org-mode operations, native kanban |
| **Prompt** | 5 | Prompt capture, search, analysis |
| **Channel** | 4 | Emacs event channels |
| **Hivemind** | 4 | Multi-agent communication |
| **Context** | 1 | Full context aggregation |

### Dynamic Tool Loading

At startup, bb-mcp queries hive-mcp via nREPL for all available tools and creates forwarding handlers automatically. This ensures bb-mcp always has **automatic parity** with hive-mcp - no manual synchronization needed.

When hive-mcp adds new tools, bb-mcp picks them up automatically on next startup.

## Usage

### As MCP Server

MCP clients run `~/.local/share/hive-mcp/bb-mcp/start-bb-mcp.sh` (see
Installation). By hand, from the checkout:

```bash
# Via bb task
bb mcp

# Directly
bb -m bb-mcp.core
```

## Project Structure

```
bb-mcp/
├── bb-mcp                    # Entry: MCP server, or `bb-mcp setup`
├── start-bb-mcp.sh           # Launcher every MCP client runs (via the anchor)
├── bb.edn                    # Tasks: mcp, setup, test
├── src/bb_mcp/
│   ├── core.clj              # Main entry, MCP message loop
│   ├── protocol.clj          # JSON-RPC over stdio
│   ├── setup.clj             # `bb-mcp setup` pipeline (CLI)
│   ├── setup/                # model (pure plan), clients (registry), toml, io (boundary)
│   ├── host/                 # Runtime ports (bb, cljw)
│   ├── sense/                # Sixth-sense channel receptor
│   ├── wire/                 # bencode
│   └── tools/                # bash, nrepl client, hive (dynamic tools from hive-mcp)
└── test/                     # Tests (bb test)
```

All hive tools are loaded dynamically from hive-mcp at runtime, so there is
no static copy of them here.

## Development

```bash
# Run tests
bb test

# Start an nREPL server for development
bb nrepl-server
```

### Adding New Tools

**Native tools** (no JVM needed):
1. Add to appropriate file in `src/bb_mcp/tools/` (bash, file, grep)
2. Register in `src/bb_mcp/core.clj` native-tools vector

**Emacs tools**: Add to hive-mcp instead - bb-mcp picks them up automatically via dynamic loading.

### nREPL Implementation

The nREPL client (`tools/nrepl.clj`) uses byte-based bencode for proper UTF-8 handling. Key functions:

- `eval-code` - Evaluate Clojure on remote nREPL
- `bencode-to-bytes` / `bdecode-from-stream` - Binary-safe bencode

## License

MIT
