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
   - Any script that sources `bin/common` MUST NOT explicitly declare
     `set -euo pipefail` or `set -e`, as the loaded `common` context inherently
     provides it.
2. **Tab Spacing**:
   - Use exactly 2 spaces for all code indentation logic in scripts (no tabs or
     4-space blocks permitted).
3. **Fail Fast (`set -e`)**:
   - Every script (if NOT sourcing `common`) MUST declare `set -euo pipefail`
     immediately after the bash shebang to ensure the execution halts instantly
     upon any unexpected command failure.
   - **Never Toggle**: NEVER temporarily suspend this safety net using `set +e`.
   - **Safe Trapping**: To safely capture the integer exit code of an execution
     that is logically allowed to fail, prioritize an inline `||` assignment
     (e.g., `STATUS=0; my_command || STATUS=$?`). This natively captures the
     failure integer state without bypassing the global termination hooks
     protecting the surrounding logic.
4. **Centralized Help Function**:
   - Every script MUST define a polymorphically-handled `help()` function that
     exits with 0 if invoked successfully (e.g. from `-h`), and exits with an
     explicit or default error code if invoked as a command failure feedback.
   - Example architecture:

     ```shell
     help() {
       local exit_code=0
       if [ "$#" -gt 0 ]; then
         log-info "$1"
         exit_code=1
       fi
       cat << 'EOF'
     script-name [options] <arg-1-name> <arg-2-name> ...

     Arguments:
       arg-1-name: Descr 1
       arg-2-name: Descr 2

     Options:
       -h, --help: Help
     EOF
       exit "$exit_code"
     }
     ```

   - Omit the Arguments or Options tables entirely if the script does not
     contain any parameter functionality.

5. **Standardized Help Flags**:
   - Every script MUST intercept `-h` or `--help` and successfully trigger
     `help 0`. For all functional parameter failures, appropriately call
     `help "Error string..."`.
6. **Standard Option Parsing**:
   - Every script MUST use a standard `while` loop with a `case` statement to parse options, even if the script only flags `-h`/`--help`. This identical `getopt` style architecture across the repo makes adding future flags trivial and structurally consistent.
   - Example architecture:

     ```shell
     while [[ $# -gt 0 ]]; do
       case "$1" in
       -h|--help) help ;;
       -*) fatal "Unknown option: $1" ;;
       *) break ;;
       esac
     done
     ```
7. **Dual Option Signatures**:
   - Every option parsed by a script should support both a short and long names
     (e.g., `-p` or `--period`). This is not strictly required, but is
     recommended, especially for options that are used frequently.
8. **Strict Argument Bounding**:
   - Define exactly what positional parameters are accepted and reject all else.
     Use exact evaluations (e.g., `if [ "$#" -ne 1 ]; then`) instead of loose
     minimums (`-lt`) to catch surplus trailing arguments.
9. **Semantic Output Streams**:
   - Standard execution logs, diagnostic errors, and invalid states MUST use the
     `log-info` function natively sourced via `/bin/common`. This effectively
     pipes informative strings to `stderr` (e.g., `log-info "Message to user"`).
   - The native `echo` command MUST ONLY be utilized when a script is
     semantically returning data strings down `stdout` (e.g. printing a variable
     evaluation meant to be caught by a pipe or var mapping externally).
10. **Specific Error Codes**:
    - Scripts MUST return specific, consistently mapped integer error codes
      (e.g., `exit 2`, `exit 3`) rather than throwing generic `exit 1` catch-alls
      when conveying distinct failure states or missing dependencies to other
      invoking bash scripts.
11. **Multiline Help Options**:
    - The `help()` function MUST use a standard `cat << 'EOF'` heredoc block to
      present its multiline usage string cleanly to standard output (`stdout`),
      rather than using `log-info` or sequential `echo` commands. This ensures
      direct user interface text is distinct from execution logging.
12. **Daemon Script Lexicon (`DOMAIN-ACTION`)**:

- All standard service wrappers and underlying engine logic scripts MUST
  strictly apply the format `${domain}-${action}` (e.g., `postgres-start`,
  `rest-server-stop`, `daemon-check`, `port-check`). Never prefix the action
  verb first (e.g., `start-postgres`). This guarantees that scripts natively
  group by service entity tightly in chronological directory listings and
  auto-complete uniformly.
13. **Variable Naming Conventions**:
    - Transient or programmatic local variables (e.g., loop bounds, internal state) MUST use `lower_snake_case` (e.g., `postgres_container`).
    - Global constants, configuration flags, and exported environment variables MUST use `UPPER_SNAKE_CASE` (e.g., `POSTGRES_CONTAINER`).
14. **Subprocess Environment Isolation**:
    - Use `export` rarely.
    - In this system, `export` should only be used to set the initial `ENV_FILE` for system-wide, foundational configuration (e.g., `export ENV_FILE="..."`). Other exceptions may arise, but should be rare and well-documented.
15. **Explicit Data Flow (Anti-Magic)**:
    - Code readers should be able to physically track the flow of data passed to programs at every point in the stack. 
    - Actively disable implicit tool loading (e.g., passing `--env-file /dev/null`) when third-party tools attempt to bypass traceable injection natively.
