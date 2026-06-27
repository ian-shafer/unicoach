---
name: rfc-impl-review-loop
description: >-
  A pre-configured macro skill that initiates an automated review and fix loop
  for a specific RFC implementation. It enforces the target RFC to be specified
  and automatically delegates execution to the skill-loop primitive.
---

# RFC Implementation Review Loop

This skill acts as a specific execution macro for the generic `skill-loop`. It
ensures that when a user asks to "review the implementation in a loop", "loop
the implementation review", or "run rfc-impl-review-loop", the underlying
generic state machine is invoked with the correct personas.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target RFC**: The path to the RFC markdown file whose implementation is
  under review (e.g., `rfc/24-stateful-auth-login.md`).

Invocation MAY optionally define:

- **Iterations**: Integer count of total loop executions (Defaults to 3 if not
  specified).

If the user does not provide a Target RFC in their prompt, you MUST pause and
ask them to provide it before continuing.

## Depth-1 Fan-out Invariant (normative)

This loop and **every** `rfc-impl-review` pass it drives MUST run **inline in
the top-level session** — never spawned as a background subagent (an
`Agent`-tool task). `rfc-impl-review` Phase 3 fans out one leaf reviewer per
micro-skill through the `design-review-chain` / `code-review-chain`; for those
leaves to be **depth-1** children (which the Claude Code harness reaps
reliably), no `Agent`-tool hop may sit above the fan-out. Running the loop from
inside a background subagent would make the leaves **grandchildren**, which the
harness task layer reaps unreliably (a finished leaf can stay `running`
indefinitely). Invoke this loop and its review passes with the `Skill` tool from
the top-level session; delegate only non-fan-out work (e.g. `rfc-impl-review`
Stage A) to background agents.

## Execution

Once the Target RFC is known, you MUST immediately delegate execution to
`skill-loop/SKILL.md` using the following parameters:

- **Target**: <The Target RFC provided by the user>
- **Iterations**: <The Iterations provided by the user, or 3 if unspecified>
- **Skills Chain**: `rfc-impl-review` -> `rfc-impl-fix`

Follow the initialization and execution state machine instructions defined in
`skill-loop/SKILL.md` exactly. If the `rfc-impl-review` outputs a status of
`APPROVED`, you MUST terminate the loop early.

Upon termination of the loop, you MUST output a final summary report that:

1. Lists all the changes and fixes made to the codebase across all iterations.
2. Recommends specific updates to the project's skills, `SPEC.md` files, or the
   RFC itself to prevent the discovered issues from occurring in future
   autonomous runs.
