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

**Role — orchestrator.** Wears the **orchestrator** hat from `iterative-work`
(spawns workers per iteration, captures each to scratch, resumes via
skip-if-present); the chained skills are **workers**. The general rules live
there; below is the loop-specific mechanics.

## Invocation Parameters

Invocation must define the following parameters:

- **Target**: The artifact, file, or component under operation.
- **Iterations**: Integer count of total loop executions.
- **Skills Chain**: Ordered list of skills to invoke per iteration.
- **Scratch Dir** _(optional)_: A run-scoped directory supplied by the caller
  (e.g. the `rfc-pipeline` orchestrator). When present, the loop captures each
  step's output there so it is **restartable without lost work**; when absent,
  the loop runs without the resume layer (standalone use).
- **Break Condition** _(optional)_: A caller-supplied predicate naming when to
  stop early — typically the verdict of the chain's review step (e.g. "the
  review step returns a PASS verdict: zero Critical, zero Major"). When omitted,
  the loop runs the full iteration budget.

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
     - **Execution**: Spawn a background subagent to execute `CurrentSkill` on
       `Target`. Use `invoke_subagent` with:
       - **TypeName**: `self`
       - **Role**: `[CurrentSkill] Executor`
       - **Prompt**:
         `"Run the [CurrentSkill] skill on target '[Target]'.
              Carry forward and apply this active context/feedback from previous
              steps: [Active Context / Findings]."`
         When a **Scratch Dir** was supplied, append:
         `" Your run-scratch sub-path is
              [Scratch Dir]/iter-[i]/[CurrentSkill]/. Write your result there the
              instant you finish (write-once), and on entry skip any unit whose
              artifact already exists."`
       - **Workspace**: `inherit`

     **Skip-if-present (resume).** When a Scratch Dir is supplied, before
     spawning check whether `[Scratch Dir]/iter-[i]/[CurrentSkill]/` already
     holds a completion artifact; if so, this step finished on a prior run —
     load its output as the active context and skip the spawn. This is what
     makes a re-invoked loop resume at the first incomplete step instead of
     redoing the iteration.

     Pause and wait for the subagent to complete. When it reports back, extract
     the findings, changes made, and the outcome. - **Context Pass-through**:
     Persist the subagent's output report as the active input context for the
     subsequent skill in the chain (the subagent has already written its durable
     artifact to the Scratch Dir; the chat report is a summary).

     - **Early Exit**: After extracting the step's output, evaluate the **Break
       Condition** against it — including any early-termination verdict the
       chained skill defines for itself (e.g. a review skill's `PASS` /
       `APPROVED`). If it is satisfied, announce early termination, skip any
       remaining skills in the chain for this iteration, and exit the loop.

   - **Iteration Summary**: At the end of each full iteration, provide the user
     with a concise, bulleted summary of all changes made to the Target during
     this loop before starting the next iteration.

3. **Termination**: When the Break Condition is met (early exit) or the
   iteration budget is exhausted, summarize terminal target state and halt for
   user input.

## Critical Constraints

- **Strict State Isolations**: Because each skill runs in a freshly spawned
  background subagent, state isolation is naturally enforced. Ensure that the
  prompt sent to each subagent contains only the required inputs and previous
  context outputs, preventing any bleeding of unneeded internal state.
- **File Artifacts**: Persist large document modifications directly via file
  editing tools. Do not output raw document source to the chat buffer.
- **Output Banners**: Prepend all chat outputs with tracking metadata indicating
  the operational phase:
  `### Iteration [i]/[Iterations] - Skill:
    [CurrentSkill]`.
