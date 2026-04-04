---
name: shell-scripts
description:
  Core programming standards and safety constraints for writing Unix shell
  scripts.
---

# 🤖 Skill: Shell Script Standardization

This skill dictates the universal structure and safety guidelines for all shell
scripts across the repository to ensure functional robustness and consistent
usability.

## 📜 Mandatory Rules

1. **Source Common Context**:
   - Every script MUST explicitly source the `common` inclusion file at the top
     (e.g., `source "$(dirname "$0")/common"`).
2. **Fail Fast (`set -e`)**:
   - Every script MUST declare `set -e` immediately after the bash shebang to
     ensure the execution halts instantly upon any unexpected command failure.
   - **Never Toggle**: NEVER temporarily suspend this safety net using `set +e`.
   - **Safe Trapping**: To safely capture the integer exit code of an execution
     that is logically allowed to fail, prioritize an inline `||` assignment
     (e.g., `STATUS=0; my_command || STATUS=$?`). This natively captures the
     failure integer state without bypassing the global termination hooks
     protecting the surrounding logic.
3. **Centralized Help Function**:
   - Every script MUST define a `help()` function capable of accepting an
     optional string argument. If the argument is present, echo it. After
     echoing the string, print the standard usage instructions and execute
     `exit 1`.
4. **Standardized Help Flags**:
   - Every script MUST interpret inbound arguments and intercept `-h` or
     `--help` explicitly, invoking the `help()` function when triggered.
5. **Dual Option Signatures**:
   - Every option parsed by a script MUST uniformly support both a short
     definition and a long definition (e.g., `-p` or `--period`).
6. **Strict Argument Bounding**:
   - Define exactly what positional parameters are accepted and reject all else.
     Use exact evaluations (e.g., `if [ "$#" -ne 1 ]; then`) instead of loose
     minimums (`-lt`) to catch surplus trailing arguments.
7. **Semantic Output Streams**:
   - Standard execution logs, diagnostic errors, and invalid states MUST use the
     `log-info` function natively sourced via `/bin/common`. This effectively
     pipes informative strings to `stderr` (e.g., `log-info "Warning details"`).
   - The native `echo` command MUST ONLY be utilized when a script is
     semantically returning data strings down `stdout` (e.g. printing a variable
     evaluation meant to be caught by a pipe or var mapping externally).
8. **Specific Error Codes**:
   - Scripts MUST return specific, consistently mapped integer error codes
     (e.g., `exit 2`, `exit 3`) rather than throwing generic `exit 1` catch-alls
     when conveying distinct failure states or missing dependencies to other
     invoking bash scripts.
9. **Multiline Help Options**:
   - The `help()` function MUST use a standard `cat << 'EOF'` heredoc block to
     present its multiline usage string cleanly to standard output (`stdout`),
     rather than using `log-info` or sequential `echo` commands. This ensures
     direct user interface text is distinct from execution logging.
10. **Daemon Script Lexicon (`DOMAIN-ACTION`)**:

- All standard service wrappers and underlying engine logic scripts MUST
  strictly apply the format `${domain}-${action}` (e.g., `postgres-start`,
  `rest-server-stop`, `daemon-check`, `port-check`). Never prefix the action
  verb first (e.g., `start-postgres`). This guarantees that scripts natively
  group by service entity tightly in chronological directory listings and
  auto-complete uniformly.
