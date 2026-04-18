---
name: skill-loop
description: >-
  Executes a specified sequence of skills in an iterative loop. Facilitates
  autonomous interaction between multiple persona skills without user
  intervention. Use when a user asks to "do N iterations", "put skills in a
  loop", or execute a "skill chain".
---

# Skill Loop

Orchestrates the sequential and iterative execution of multiple agent skills.

## Invocation Parameters

Invocation must define the following parameters:

- **Target**: The artifact, file, or component under operation.
- **Iterations**: Integer count of total loop executions.
- **Skills Chain**: Ordered list of skills to invoke per iteration.

_Example definition extracted from prompt:_

- Target: `draft-spec.md`
- Iterations: 3
- Skills Chain: `spec-design-review` -> `spec-design`

## Execution State Machine

1. **Initialization**: Output parsed parameters (Target, Iterations, Skills
   Chain) to the chat buffer for configuration confirmation. Read relevant
   `SKILL.md` definitions if unindexed.
2. **Execution Loop**: For iteration `i=1` to `Iterations`:
   - For `CurrentSkill` in `Skills Chain`:
     - **Status Announcement**: Explicitly announce to the user that the step is
       starting (e.g., "Kicking off iteration 1 of `spec-design-review`") to
       maintain visible orchestrator progress.
     - **Context Switch**: Load the persona, constraints, and operational
       directives defined in `CurrentSkill/SKILL.md`.
     - **Execution**: Perform actions required by `CurrentSkill`.
     - **Context Pass-through**: Persist outputs (e.g., critique, conceptual
       feedback, implementation plan) as the active input context for the
       subsequent skill in the chain.
3. **Termination**: Upon completion of all iterations, summarize terminal target
   state and halt for user input.

## Critical Constraints

- **Strict State Isolations**: Discard the persona of the previous skill upon
  context switch. Active skill constraints completely override previous context.
- **File Artifacts**: Persist large document modifications directly via file
  editing tools. Do not output raw document source to the chat buffer.
- **Output Banners**: Prepend all chat outputs with tracking metadata indicating
  the operational phase:
  `### Iteration [i]/[Iterations] - Skill: [CurrentSkill]`.
