# Gamelan CLI - Agentic AI for Local Development

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.java.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.x-blue.svg)](https://quarkus.io/)

**Gamelan CLI** is a modern, Claude Code-inspired command-line interface for AI-driven development, powered by the **Aqli** local inference engine and conforming to the [Agent Skills specification](https://agentskills.io/specification).

## Features

### 🎯 Core Capabilities
- **Interactive REPL** - Chat with AI agent with persistent history
- **One-shot execution** - Run tasks non-interactively for scripts/CI
- **Skill system** - Extensible skills conforming to agentskills.io spec
- **Local inference** - Run models locally via Aqli (GGUF, ONNX, etc.)
- **Tool calling** - Native tool execution with approval modes
- **Session management** - Save, load, and resume conversations

### 🛡️ Safety & Control
- **Approval modes** - Choose autonomy vs safety tradeoff
  - `auto` - Full autonomy, no questions
  - `trusted-tools` - Ask only for untrusted tools (Claude Code-like)
  - `always` - Ask before every tool execution
- **Sandbox mode** - Restrict tool execution to safe operations
- **Permission system** - Fine-grained control over which tools are trusted
- **Context management** - Monitor token usage and context window

### 💻 Modern CLI UX
- **Rich formatting** - Colors, progress indicators, structured output
- **Command discovery** - Help system with examples for every command
- **JSON output** - Machine-readable output for automation
- **Multi-line input** - Support for complex prompts with triple backticks
- **History navigation** - Persistent command history across sessions

## Installation

### Prerequisites
- Java 21 or higher
- Maven 3.8+ (for building from source)

### Build from Source

```bash
# Build the CLI
mvn clean install -pl andalus-aqli/agent/agent-cli -am -DskipTests

# Run directly with Quarkus dev mode
./mvnw quarkus:dev -pl andalus-aqli/agent/agent-cli
```

### Native Image (Optional)

```bash
# Build native binary (requires GraalVM)
./mvnw package -Pnative -pl andalus-aqli/agent/agent-cli

# The binary will be at:
# target/gamelan-1.0.0-SNAPSHOT-runner
```

## Quick Start

### 1. Start Interactive REPL

```bash
# Start REPL (uses default model from config)
gamelan

# Start with specific model
gamelan --model hf:Qwen/Qwen2.5-Coder-7B-Instruct

# Verbose mode
gamelan -v
```

### 2. One-Shot Execution

```bash
# Run a single task
gamelan "refactor MyClass.java to use records instead of POJOs"

# Run with JSON output (for scripts/CI)
gamelan run --json "add unit tests to UserService.java"

# Run with specific model
gamelan run --model qwen2 "explain the architecture of this file"
```

### 3. Skill Management

```bash
# List all skills
gamelan skill list

# Show skill details
gamelan skill show read-file

# Install a new skill
gamelan skill install /path/to/my-skill

# Enable/disable skills
gamelan skill enable code-review
gamelan skill disable shell
```

### 4. Approval & Permissions

```bash
# Set approval mode
gamelan approve mode auto              # Full autonomy
gamelan approve mode trusted-tools     # Ask for untrusted tools (recommended)
gamelan approve mode always            # Ask for everything

# Trust specific tools
gamelan approve trust read_file
gamelan approve trust write_file
gamelan approve trust shell --all      # Trust all shell tools

# View permissions
gamelan approve list                   # List trusted tools
gamelan approve permissions            # Show full configuration
```

### 5. Model Management

```bash
# List available models
gamelan models list

# Download a model
gamelan models pull hf:Qwen/Qwen2.5-0.5B-Instruct

# Get model info
gamelan models info qwen2.5-0.5b

# Remove a model
gamelan models rm qwen2.5-0.5b
```

### 6. Configuration

```bash
# List current config
gamelan config list

# Set configuration values
gamelan config set model hf:Qwen/Qwen2.5-Coder-7B-Instruct
gamelan config set temperature 0.7
gamelan config set max_tokens 8192
gamelan config set skills_dir ~/.gamelan/skills
```

## REPL Commands

When in interactive mode, use these slash commands:

| Command | Description |
|---------|-------------|
| `/help` | Show help message |
| `/skills` | List available skills |
| `/models` | List local models |
| `/clear` | Clear conversation history |
| `/session` | Show session info (ID, turns, tokens) |
| `/model` | Show active model |
| `/tokens` | Show token usage statistics |
| `/context` | Show context window usage |
| `/trust <tool>` | Trust a tool for this session |
| `/permissions` | Show current permission settings |
| `/exit` or `/quit` | Exit Gamelan |

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+C` | Cancel current task (doesn't exit) |
| `Ctrl+D` | Exit Gamelan |
| `↑/↓` | Navigate history |
| `Tab` | Auto-complete (future) |

## Configuration

### Config File Location
`~/.gamelan/config.yml`

### Example Configuration

```yaml
# Default model to use
model: "hf:Qwen/Qwen2.5-Coder-7B-Instruct"

# Inference parameters
temperature: 0.7
max_tokens: 8192
top_p: 0.9

# Skills configuration
skills_dir: "~/.gamelan/skills"
auto_load_skills: true

# Approval mode (auto, trusted-tools, always)
approval_mode: "trusted-tools"

# Trusted tools
trusted_tools:
  - read_file
  - list_dir
  - search_files

# Sandbox mode
sandbox_enabled: false

# Session configuration
max_history_turns: 50
max_token_budget: 128000
session_persist: true
```

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────┐
│                   Gamelan CLI                           │
├─────────────────────────────────────────────────────────┤
│  REPL/Commands (Picocli + JLine)                        │
│         │                                                │
│         ▼                                                │
│  AgentLoop (Core Orchestration)                         │
│         │                                                │
│         ├──► SkillSelector                              │
│         ├──► PromptBuilder                               │
│         ├──► AqliSdk (Inference Engine)                │
│         ├──► ToolExecutor (with approval checks)         │
│         └──► ConversationSession (History/Persistence)   │
├─────────────────────────────────────────────────────────┤
│  Aqli SDK (tech.kayys.aqli)                         │
│         │                                                │
│         ├──► LocalAqliSdk (GGUF, ONNX, etc.)           │
│         ├──► Model Management                            │
│         └──✓ Native Tool Calling                         │
├─────────────────────────────────────────────────────────┤
│  Skills System (agentskills.io)                          │
│         │                                                │
│         ├──► SkillRegistry                               │
│         ├──► SkillLoader (YAML frontmatter + Markdown)   │
│         └──► Skill Discovery & Selection                 │
└─────────────────────────────────────────────────────────┘
```

### Tool Calling Protocol

Gamelan uses **Aqli's native tool calling** with structured `ToolDefinition` objects:

1. **Tools registered** → `List<ToolDefinition>` passed to LLM
2. **LLM responds** → `InferenceResponse` with `List<ToolCall>`
3. **Approval check** → Verify tool is trusted or ask user
4. **Execute tool** → Run via `ToolExecutor`
5. **Return results** → Feed back to LLM for next iteration

### Agent Loop Flow

```
User Input → Skill Selection → Build System Prompt
       ↓
AqliSdk.createCompletion(messages, tools)
       ↓
Parse Response:
  ├── Text only → Return to user (done)
  └── Tool calls → Execute tools → Continue loop
```

## Integration with Aqli Ecosystem

Gamelan CLI integrates seamlessly with the broader Aqli platform:

- **Local Inference**: Uses `LocalAqliSdk` for on-device model execution
- **Model Management**: Pull models from HuggingFace, manage local cache
- **Tool Ecosystem**: Access to 50+ Golok tools (file I/O, shell, git, etc.)
- **Skill System**: Compatible with agentskills.io specification

## Examples

### Code Refactoring

```bash
$ gamelan
🎶 Gamelan CLI v1.0.0 - Agentic AI for local development
Model: hf:Qwen/Qwen2.5-Coder-7B-Instruct
Skills: ~/.gamelan/skills | Type /help for commands

> Refactor UserService.java to use dependency injection

🤖 I'll help you refactor UserService.java to use dependency injection...

⚡ [read_file] Reading src/main/java/tech/kayys/aqli/UserService.java
⚡ [analyze_code] Analyzing class structure...
⚡ [write_file] Writing refactored version...
⚡ [compile_java] Verifying compilation...

✅ Refactoring complete! Changes:
   - Removed manual instantiation
   - Added @Inject annotations
   - Created constructor injection
   - All tests passing
```

### Automated Testing

```bash
$ gamelan run --json "add unit tests for payment processing"
{
  "text": "I've added comprehensive unit tests for payment processing...",
  "tool_results": [
    {"tool": "read_file", "status": "success"},
    {"tool": "write_file", "status": "success"},
    {"tool": "run_tests", "status": "success", "passed": 15}
  ],
  "tokens": {"input": 12500, "output": 3200},
  "error": null
}
```

### Skill Installation

```bash
$ gamelan skill install ~/my-custom-skill
Installing skill from: ~/my-custom-skill
✓ Installed skill: my-custom-skill
  Location: ~/.gamelan/skills/my-custom-skill

$ gamelan skill show my-custom-skill
📦 Skill: my-custom-skill

Description: Custom code review skill
Version:     1.2.0
Source:      user-global
Enabled:     Yes
Path:        ~/.gamelan/skills/my-custom-skill

Commands:
  • review-code
  • check-quality
```

## Development

### Project Structure

```
agent-cli/
├── pom.xml
├── src/main/java/tech/kayys/
│   ├── gamelan/                    # Improved agentic system
│   │   ├── agent/
│   │   │   ├── AgentLoop.java      # Core orchestration
│   │   │   ├── AgentResponse.java  # Response model
│   │   │   └── skill/              # Skill system
│   │   ├── config/                 # Configuration
│   │   ├── session/                # Session management
│   │   ├── tool/                   # Tool execution
│   │   └── util/                   # Utilities (AnsiPrinter)
│   └── andalus/agent/               # CLI commands
│       ├── GamelanApplication.java # Main entry
│       ├── ChatCommand.java        # REPL
│       ├── RunCommand.java         # One-shot
│       ├── SkillCommand.java       # Skill management
│       ├── ApprovalCommand.java    # Permissions
│       ├── ModelCommand.java       # Model management
│       └── ConfigCommand.java      # Configuration
└── src/main/resources/
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AgentLoopTest

# Skip tests
mvn install -DskipTests
```

### Debug Mode

```bash
# Enable debug logging
gamelan -v "your task"

# Or set in config
gamelan config set verbose true
```

## Comparison with Claude Code

| Feature | Claude Code | Gamelan CLI |
|---------|-------------|-------------|
| Local inference | ❌ Cloud-only | ✅ Aqli (GGUF/ONNX) |
| Approval modes | ✅ Yes | ✅ Yes (3 modes) |
| Tool permissions | ✅ Trusted tools | ✅ Granular control |
| Skill system | ❌ Proprietary | ✅ agentskills.io spec |
| Session persistence | ✅ Yes | ✅ JSON-based |
| Multi-model support | ✅ Anthropic only | ✅ Any Aqli model |
| Open source | ❌ | ✅ MIT License |
| Extensibility | Limited | Full skill system |

## Roadmap

### Short-term
- [ ] Tab completion for commands and file paths
- [ ] Multi-line input with syntax highlighting
- [ ] Real-time token usage display during streaming
- [ ] Confirmation prompts for tool approval
- [ ] Session search and replay

### Medium-term
- [ ] Parallel tool execution
- [ ] Sub-agent spawning for complex tasks
- [ ] Git integration (commit, PR creation)
- [ ] MCP server support
- [ ] Plugin marketplace

### Long-term
- [ ] Multi-agent collaboration
- [ ] Visual mode (ASCII diagrams)
- [ ] Voice input support
- [ ] Remote execution mode

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Aqli](https://github.com/kayys-tech/aqli) - Local inference engine
- [agentskills.io](https://agentskills.io) - Skill specification
- [Claude Code](https://anthropic.com/claude-code) - Inspiration for UX patterns
- [Picocli](https://picocli.info/) - CLI framework
- [JLine](https://github.com/jline/jline3) - REPL capabilities

---

Built with ❤️ by the Andalus Team
