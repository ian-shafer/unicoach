---
name: code-review-chain
description: >-
  A pre-configured macro skill that initiates a comprehensive, adversarial 
  code review chain for a specific target. It concurrently invokes background 
  subagents to execute all individual `code-review-*` micro-skills, compiling 
  their results into a single evaluation report.
---

# Code Review Chain

This skill acts as a specific execution macro. It ensures that when a user asks to "do a full code review" or "run code-review-chain", the orchestrator concurrently spawns background subagents to evaluate all code-review rules in parallel.

**Role — orchestrator.** This chain wears the **orchestrator** hat from `iterative-work` (bounded fan-out, per-leaf capture, reconstruct-from-artifacts, resumable via skip-if-present); the `code-review-*` leaves are **workers**. Below is only the chain-specific wiring.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target**: The file, artifact, or component under review.
- **Scratch Dir** _(optional)_: A run-scoped directory supplied by the caller
  (e.g. `rfc-impl-review` under the `rfc-pipeline` orchestrator). When present,
  each leaf reviewer persists its verdict there so a stalled aggregation never
  forfeits completed leaf work. When absent, default to a local scratch path.

If the user does not provide a Target in their prompt, you MUST pause and ask
them to provide it before continuing.

## Execution

1. **Discover Review Skills**: Scan the list of all active skills available in your execution context (defined in your system prompt or `<skills>` block) that match the pattern `code-review-*` (excluding `code-review-chain` itself).
2.  **Spawn Subagents in Bounded Batches**: Launch a background subagent for each
    discovered code-review skill using the **`Agent`** tool, but keep **at most
    10 in flight at once**. Emit up to 10 spawns in one message; then, as each
    leaf finishes (and writes its verdict file), dispatch the next not-yet-run
    skill, holding concurrency at 10 until all are dispatched (bounded fan-out,
    per `iterative-work`). Specify the following for each:
    - **subagent_type**: `code-reviewer` — this agent definition
      (`.claude/agents/code-reviewer.md`) pins the reviewer model and a
      read-only tool set. Do **not** use `general-purpose`; the leaf reviewers
      are deliberately scoped and must not edit the tree.
    - **description**: `[Skill Name] Reviewer` (e.g. `code-review-allowlist Reviewer`)
    - **run_in_background**: `true`
    - **prompt**: `"Run the [Skill Name] skill on target '[Target]' and return
        a detailed verdict. Your response must clearly state the Verdict (PASS,
        FAIL, or N/A) and the detailed Reasoning.<SCRATCH>"` — where `<SCRATCH>`,
        when a **Scratch Dir** was supplied, is: `" The instant you finish,
        write your verdict (Verdict + Reasoning) to
        [Scratch Dir]/leaves/[Skill Name].json — write-once. Do this BEFORE your
        chat reply so your work survives even if the chain is interrupted."`
    - **Skip-if-present**: when a Scratch Dir is supplied, do **not** spawn a
      leaf whose `[Scratch Dir]/leaves/[Skill Name].json` already exists — it
      completed on a prior run. This lets an interrupted chain resume cheaply.
3. **Drain the Batch Queue**: Keep refilling per step 2 — dispatching the next
   skill each time a slot frees — until every discovered skill has run, then wait
   for the final in-flight leaves to finish. Each leaf has written its own
   verdict file, so you need not hold verdicts in context — track only which leaf
   files have appeared and how many of the 10 slots are free.
4. **Compile Report**: Once every leaf's verdict file is present, compile the
   unified report **by reading `[Scratch Dir]/leaves/`** (not from in-context
   accumulation), using the markdown format below. Because the leaves are the
   source of truth, a compile that is interrupted is simply re-run against the
   same directory — no leaf review is ever lost or repeated.

## Output Format

Accumulate all subagent verdicts in your context and compile a unified
evaluation report.

> **CRITICAL CONSTRAINT:** Do NOT output the raw report to the chat buffer. Write
> the compiled report to `[Scratch Dir]/report.md` (or, absent a supplied Scratch
> Dir, a local scratch file). The per-leaf files in `[Scratch Dir]/leaves/`
> remain the source of truth — `report.md` is a convenience rollup,
> reconstructable from them at any time. Output only a summary and the file path
> to the chat buffer so the master orchestrator can read it later.

```markdown
# 🔍 Comprehensive Code Review: [Target]

## 🛡️ 1. Core Philosophy Check

### [Insert Skill Name (e.g., code-review-allowlist)]
**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed explanation. MUST include code examples (max 20% of total LOC changed)]

## General Review and Feedback

[This section is for general feedback and observations that do not fit into the above categories. It is not required to provide feedback in this section.]

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable code changes they must make. For each action item, you MUST provide at least 2 distinct options for how to resolve it, and explicitly recommend one of the options.)_
```
