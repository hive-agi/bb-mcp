# bb-mcp

Lightweight MCP (Model Context Protocol) server in Babashka that bridges Claude Code to Emacs via nREPL.

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

| Metric | JVM emacs-mcp | bb-mcp |
|--------|---------------|--------|
| Startup | ~2-3s | **~5ms** |
| Memory | ~500MB | **~50MB** |
| Scales to | 1 instance | **Many instances** |

### Use Case: Claude Swarm

When running swarm agents (multiple Claudes working in parallel), each agent needs an MCP connection to Emacs. Without bb-mcp, you'd need separate JVM processes. With bb-mcp:

- **1 emacs-mcp** (JVM) handles Emacs integration
- **N bb-mcp** instances (Babashka) multiplex to it
- All agents share tools, memory, kanban, git via the single JVM

## Architecture

```
                    bb-mcp Instances              Shared JVM
                    ┌──────────────┐
  Claude 1 ───────▶ │   bb-mcp     │──┐
                    └──────────────┘  │
                    ┌──────────────┐  │     ┌─────────────────┐
  Claude 2 ───────▶ │   bb-mcp     │──┼────▶│   emacs-mcp     │
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
3. Emacs-related tools delegate to emacs-mcp via nREPL on port 7910
4. emacs-mcp executes elisp in Emacs via emacsclient

## Prerequisites

- [Babashka](https://babashka.org/) v1.3+
- [ripgrep](https://github.com/BurntSushi/ripgrep) (for grep tool)
- [emacs-mcp](https://github.com/your-user/emacs-mcp) running with nREPL on port 7910

## Installation

```bash
# Clone the repository
git clone https://github.com/your-user/bb-mcp.git
cd bb-mcp

# Add to Claude Code MCP config
claude mcp add bb-mcp bb -- -m bb-mcp.core
```

## Configuration

### nREPL Port Resolution

bb-mcp finds the nREPL port in this order:

1. **Explicit parameter** - `port` in tool call
2. **Environment variable** - `BB_MCP_NREPL_PORT`
3. **.nrepl-port file** - In `BB_MCP_PROJECT_DIR` or current directory
4. **Default** - Port 7910 (emacs-mcp)

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `BB_MCP_NREPL_PORT` | nREPL port to connect to | 7910 |
| `BB_MCP_PROJECT_DIR` | Directory for .nrepl-port lookup | Current dir |
| `EMACS_MCP_DIR` | emacs-mcp directory for auto-spawn | ~/dotfiles/gitthings/emacs-mcp |

## Tools

bb-mcp provides **114 tools** (6 native + 108 from emacs-mcp).

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

Tools loaded dynamically from emacs-mcp at startup:

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

At startup, bb-mcp queries emacs-mcp via nREPL for all available tools and creates forwarding handlers automatically. This ensures bb-mcp always has **automatic parity** with emacs-mcp - no manual synchronization needed.

When emacs-mcp adds new tools, bb-mcp picks them up automatically on next startup.

## Usage

### As MCP Server

```bash
# Via bb task
bb mcp

# Directly
bb -m bb-mcp.core
```

### Connection Management

bb-mcp uses **state-based detection** to manage the emacs-mcp connection:

| State | Condition | Action |
|-------|-----------|--------|
| `:ready` | Port listening | Connect immediately (0 latency) |
| `:starting` | Lock file or process exists | Wait with exponential backoff |
| `:not-running` | No process found | Spawn emacs-mcp and wait |

This eliminates race conditions and ensures reliable startup even when multiple bb-mcp instances start simultaneously.

Logs go to `~/.config/emacs-mcp/server.log`.

## Project Structure

```
bb-mcp/
├── bb.edn                    # Babashka deps and tasks
├── src/bb_mcp/
│   ├── core.clj              # Main entry, MCP message loop
│   ├── protocol.clj          # JSON-RPC protocol handling
│   ├── nrepl_spawn.clj       # Auto-spawn emacs-mcp nREPL
│   ├── test_runner.clj       # Test runner
│   └── tools/
│       ├── bash.clj          # Native: shell execution
│       ├── file.clj          # Native: file operations
│       ├── grep.clj          # Native: ripgrep wrapper
│       ├── nrepl.clj         # nREPL client (bencode)
│       ├── emacs.clj         # Emacs tools facade
│       └── emacs/
│           └── dynamic.clj   # Dynamic tool loading from emacs-mcp
└── test/                     # Tests
```

The `emacs/` directory is minimal - all Emacs tools are loaded dynamically from emacs-mcp at runtime, eliminating code duplication.

## Development

```bash
# Run tests
bb test

# Start REPL for development
bb nrepl
```

### Adding New Tools

**Native tools** (no JVM needed):
1. Add to appropriate file in `src/bb_mcp/tools/` (bash, file, grep)
2. Register in `src/bb_mcp/core.clj` native-tools vector

**Emacs tools**: Add to emacs-mcp instead - bb-mcp picks them up automatically via dynamic loading.

### nREPL Implementation

The nREPL client (`tools/nrepl.clj`) uses byte-based bencode for proper UTF-8 handling. Key functions:

- `eval-code` - Evaluate Clojure on remote nREPL
- `bencode-to-bytes` / `bdecode-from-stream` - Binary-safe bencode

## License

MIT
