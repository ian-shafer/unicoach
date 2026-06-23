---
name: spec-sync-loop
description: >-
  A pre-configured macro skill that autonomously loops to ensure a directory's
  SPEC.md is complete, accurate, and reflects the current codebase. Delegates
  execution to skill-loop chaining spec-writer-review and spec-writer. SPEC.md
  is LLM-managed with no human gate; invariants are handled separately by
  invariants-writer.
---

# Spec Sync Loop

This skill acts as a specific execution macro for the generic `skill-loop`. It
keeps a directory's `SPEC.md` strictly in sync with the codebase by
adversarially reviewing it and then immediately applying the findings — fully
autonomously, with **no human-in-the-loop step**.

**Role — orchestrator.** A thin **orchestrator** (per `iterative-work`) that
delegates to `skill-loop`, forwarding any caller-supplied `Scratch Dir`;
`spec-writer-review` / `spec-writer` are the **workers**.

`SPEC.md` is **descriptive** (what the code does) and is fully LLM-managed. This
loop never touches `INVARIANTS.md` — the directory's prescriptive durable
guarantees are authored and human-gated separately by the `invariants-writer`
skill. If, while syncing, the loop notices a durable guarantee that belongs in
`INVARIANTS.md`, it records the observation in its report for the orchestrator
to route to `invariants-writer`; it does **not** write `INVARIANTS.md` itself.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target Directory**: The path to the directory containing the `SPEC.md` to be
  synced (e.g., `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/`).

Invocation MAY optionally define:

- **Iterations**: Integer count of total loop executions (Defaults to 3).
- **Scratch Dir**: A run-scoped directory supplied by the caller for
  capture/resume; forwarded to `skill-loop` so a stalled sync resumes at the
  first incomplete iteration. Omit for standalone use.

If the user does not provide a Target Directory in their prompt, you MUST pause
and ask them to provide it before continuing.

If the target directory has no `SPEC.md` yet, the first `spec-writer` pass
generates it from the codebase and in-scope RFCs.

## Execution

Once the Target Directory is known, you MUST immediately delegate execution to
`skill-loop/SKILL.md` using the following parameters:

- **Target**: <The Target Directory provided by the user>
- **Iterations**: <The Iterations provided by the user, or 3 if unspecified>
- **Skills Chain**: `spec-writer-review` -> `spec-writer`
- **Scratch Dir**: <The Scratch Dir provided by the caller, if any>

Follow the initialization and execution state machine instructions defined in
`skill-loop/SKILL.md` exactly. The loop first executes `spec-writer-review` to
produce a findings report, then executes `spec-writer` to apply the accepted
findings and regenerate the `SPEC.md`. This repeats until the review returns a
PASS verdict (zero Critical, zero Major) or the iteration budget is exhausted.
No interactive approval is solicited — `SPEC.md` is LLM-managed.
