---
name: coding
description:
  Global baseline philosophies for programming safety and data integrity.
---

# 🤖 Skill: Defensive Coding

This skill establishes the universal logic constraints that apply across all
programming languages, scripts, and implementations within the repository.

## 📜 Core Philosophy

1. **Accept Known, Reject All Else** (The Allowlist Principle)
   - You must define exactly what inputs, arguments, or data structures are
     permitted by a function or script.
   - Any input that does not match the defined boundary must be instantly
     rejected.
   - _Implementation Formulation_: Never check if an input is "missing" (e.g.,
     `if count < 1`). Instead, check if the input is "exactly what is expected"
     (e.g., `if count != 1`), rejecting any unexpected surplus data.

2. **Handle All Cases** (Exhaustive Evaluation)
   - The execution flow must be robust and explicitly address every possible
     input state, network error, or logic branch.
   - Never implement partial conditional evaluations or assume "default"
     closures cover unhandled edge cases.

3. **No Sentinel Nulls or Empty Strings** (Explicit Initialization)
   - NEVER use nulls or empty strings to represent an uninitialized state.
   - Prefer either initializing the variable with a concrete default value or
     using an explicit optional type (e.g., TypeScript's `string?` or Swift's
     `String?`).

4. **Immutable State Returns** (Functional Patterns)
   - When managing domain state or sessions, design models as immutable objects.
   - Interface methods must return copies of these models containing mutated
     state (e.g., `func setToken() -> Session`) preventing opaque side-effects
     (`func setToken() -> Void`).

5. **Avoid Metasyntactic Naming** (Concrete Identifiers)
   - Do not append ambiguous filler words like `State`, `Data`, or `Info` onto
     entity names.
   - Class and variable constructs must evaluate clear structural bounds
     directly (e.g., use `Session` instead of `SessionState`).

6. **Dynamic Variable Formatting** (Bracket Serialization)
   - ALWAYS wrap dynamic variables in brackets (`[]`) instead of single quotes
     (`''`) when printing strings in non-end-user communications (e.g., logs,
     CLIs, system outputs).
   - _Example_: `log-info "Processed node [$NODE_ID] successfully."`

7. **Extramarital Routing Defaults** (Explicit Interfaces Over Implicit Magic)
   - Do NOT rely on undocumented runtime inheritance or magic environment
     variables (e.g., `export COMPOSE_FILE="..."` bubbling up transparently to
     mask system behaviors).
   - Core interfaces and engines MUST be passed required context explicitly via
     visible command-line arguments or explicit mapping configuration files
     (e.g., passing `-f path` gracefully downward) so engineers can decipher
     command flow via pure static analysis without needing to map environment
     graphs.

8. **Semantic Output Streams** (Error and Fatal Routing)
   - NEVER pipe `echo` directly to `>&2` inside script execution logic for error
     messages.
   - You MUST utilize the globally integrated `log-error "My Message"` method
     defined in `bin/functions` explicitly for all error payloads. This
     guarantees that standard error is formatted universally with prefixes and
     standardized terminal outputs (e.g., `[ERROR] My Message`).
   - For errors that immediately terminal the script (e.g., followed immediately
     by `exit 1`), you MUST use the `fatal "My Message"` method. It behaves
     identically to `log-error` but formats as `[FATAL] My Message` and natively
     executes the `exit 1` block so that logic is DRY.
