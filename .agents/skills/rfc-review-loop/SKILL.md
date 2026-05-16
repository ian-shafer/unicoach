---
name: rfc-review-loop
description: >-
  A pre-configured macro skill that initiates an adversarial 3-round review loop 
  for a specific RFC. It enforces the target RFC to be specified and automatically 
  delegates execution to the `skill-loop` primitive.
---

# RFC Review Loop

This skill acts as a specific execution macro for the generic `skill-loop`. 
It ensures that when a user asks to "review an RFC in a loop" or "run rfc-review-loop",
the underlying generic state machine is invoked with the correct personas.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target RFC**: The path to the RFC markdown file under review (e.g., `rfc/24-stateful-auth-login.md`).

Invocation MAY optionally define:
- **Iterations**: Integer count of total loop executions (Defaults to 3 if not specified).

If the user does not provide a Target RFC in their prompt, you MUST pause and ask them to provide it before continuing.

## Execution

Once the Target RFC is known, you MUST immediately delegate execution to `skill-loop/SKILL.md` using the following parameters:

- **Target**: <The Target RFC provided by the user>
- **Iterations**: <The Iterations provided by the user, or 3 if unspecified>
- **Skills Chain**: `rfc-design-review` -> `rfc-design`

Follow the initialization and execution state machine instructions defined in `skill-loop/SKILL.md` exactly.
