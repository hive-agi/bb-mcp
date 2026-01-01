# bb-mcp

Lightweight MCP (Model Context Protocol) server implemented in Babashka.

## Why bb-mcp?

| Metric | JVM clojure-mcp | bb-mcp |
|--------|-----------------|--------|
| Startup | ~2-3s | **~5ms** |
| Memory | ~500MB | **~50MB** |
| Per-instance overhead | Heavy | Minimal |

## Architecture

```
┌─────────────┐     ┌─────────────┐
│  Claude 1   │────▶│   bb-mcp    │──┐
└─────────────┘     └─────────────┘  │
┌─────────────┐     ┌─────────────┐  │   ┌──────────────┐
│  Claude 2   │────▶│   bb-mcp    │──┼──▶│ Shared nREPL │
└─────────────┘     └─────────────┘  │   │  (1 JVM)     │
┌─────────────┐     ┌─────────────┐  │   └──────────────┘
│  Claude 3   │────▶│   bb-mcp    │──┘
└─────────────┘     └─────────────┘
   ~50MB each           ~500MB total
```

## Features

### Native Tools (no JVM needed)
- `bash` - Execute shell commands
- `read_file` - Read file contents
- `file_write` - Write files
- `glob_files` - Find files by pattern
- `grep` - Search content with ripgrep

### Delegated to Shared nREPL
- `clojure_eval` - Evaluate Clojure code

## Installation

```bash
# Clone the repo
git clone https://github.com/your-user/bb-mcp.git

# Add to Claude Code MCP config
claude mcp add bb-mcp bb -- -m bb-mcp.core
```

## Usage

### As MCP Server
```bash
bb -m bb-mcp.core
```

### With Shared nREPL
Start one JVM nREPL server:
```bash
clojure -M:nrepl -p 7888
```

All bb-mcp instances will connect to it for `clojure_eval`.

## Development

```bash
# Run tests
bb test

# Start REPL
bb nrepl
```

## Requirements

- [Babashka](https://babashka.org/) v1.3+
- [ripgrep](https://github.com/BurntSushi/ripgrep) for grep tool
- Shared nREPL for Clojure eval (optional)

## License

MIT
