---
name: spec-sync-loop
description: >-
  A pre-configured macro skill that loops to ensure a directory's SPEC.md is
  complete, accurate, and reflects the current codebase. Delegates execution
  to skill-loop chaining spec-writer-review and spec-editor.
---

# Spec Sync Loop

This skill acts as a specific execution macro for the generic `skill-loop`.
It ensures that a directory's `SPEC.md` stays strictly in sync with the codebase by adversarially reviewing it and then immediately passing findings to an interactive, human-in-the-loop editing session.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target Directory**: The path to the directory containing the `SPEC.md` to be reviewed (e.g., `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/`).

Invocation MAY optionally define:
- **Iterations**: Integer count of total loop executions (Defaults to 1 if not specified, as human-in-the-loop typically resolves issues quickly).

If the user does not provide a Target Directory in their prompt, you MUST pause and ask them to provide it before continuing.

## Execution

Once the Target Directory is known, you MUST immediately delegate execution to `skill-loop/SKILL.md` using the following parameters:

- **Target**: <The Target Directory provided by the user>
- **Iterations**: <The Iterations provided by the user, or 1 if unspecified>
- **Skills Chain**: `spec-writer-review` -> `spec-editor`

Follow the initialization and execution state machine instructions defined in `skill-loop/SKILL.md` exactly. The loop must first execute `spec-writer-review` to produce a findings report. Then, it MUST execute `spec-editor` to propose those findings interactively to the Architect for strict human approval before any edits are written to disk.
