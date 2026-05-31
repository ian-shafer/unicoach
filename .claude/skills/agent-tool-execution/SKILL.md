---
name: agent-tool-execution
description:
  Defines the mandatory wrapper constraints when the AI agent needs to run local
  shell tools (like Docker or Gradle).
---

# 🤖 Skill: Agent Tool Execution Context

This skill dictates how the LLM Agent inherently interacts with system shells to
execute commands that depend on user-profile binaries.

## 📜 Core Philosophy

By default, standard AI terminal executions run non-interactively and bypass
typical login user files (`~/.zprofile`, `~/.zshrc`). As a result, critical
system tools scoped to typical Mac setups (such as Homebrew binaries in
`/opt/homebrew/bin` or locally-installed Node, Docker, etc. residing in
`/usr/local/bin`) will NOT be found in `$PATH`, throwing `command not found`.

To solve this:

1. **Interactive Shell Wrappers**:
   - ANY TIME we must run an executable or script bridging with external daemon
     environments (such as compiling code, running tests with `./bin/test`,
     checking `docker ps`, or making HTTP calls with specialized tools), we MUST
     wrap the execution in a login shell.
   - We must execute an exact bash block wrapper logic natively:
     `zsh -l -c '<COMMAND STRING>'`.
2. **Implementation Example**:
   - Incorrect: `run_command(command: "./bin/test")`
   - Correct: `run_command(command: "zsh -l -c './bin/test'")`
3. **No Environment Hardcoding**:
   - NEVER try to manually rebuild the profile context (e.g. `export PATH=...`)
     per command. The user's dotfiles serve as the single source of truth.
     Always rely on the `-l` login flag to load everything natively.
