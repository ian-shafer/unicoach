---
name: code-review-chain
description: >-
  A pre-configured macro skill that runs a comprehensive, adversarial code
  review of a target. It builds the shared review context once (the diff plus
  each changed file) and injects it into a bounded fan-out of background
  `code-review-*` leaf agents — one per micro-skill — then compiles their
  verdicts into a single report, asserting every discovered lens is accounted
  for and marking any that did not run. This context-injected fan-out is the
  default for real reviews; a single inline agent applying all lenses is an
  opt-in fast mode that under-finds.
---

# Code Review Chain

This skill acts as a specific execution macro. It ensures that when a user asks
to "do a full code review" or "run code-review-chain", the orchestrator
concurrently spawns background subagents to evaluate all code-review rules in
parallel.

**Role — orchestrator.** This chain wears the **orchestrator** hat from
`iterative-work` (bounded fan-out, per-leaf capture, reconstruct-from-artifacts,
resumable via skip-if-present); the `code-review-*` leaves are **workers**.
Below is only the chain-specific wiring.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target**: The list of files to review.
- **Base Revision** _(optional)_: The revision the change is measured against,
  used to build the shared diff (step 2). Defaults to `main`. The caller (e.g.
  `rfc-impl-review`) passes the same base it used to establish the changed-file
  set, so the diff the leaves see is exactly the implementation's delta.
- **Scratch Dir** _(optional)_: A run-scoped directory supplied by the caller
  (e.g. `rfc-impl-review` under the `rfc-pipeline` orchestrator). When present,
  each leaf reviewer persists its verdict there so a stalled aggregation never
  forfeits completed leaf work. When absent, default to a local scratch path.

If the user does not provide a Target in their prompt, you MUST pause and ask
them to provide it before continuing.

## Execution

1. **Discover Review Skills**: Scan the list of all active skills available in
   your execution context (defined in your system prompt or `<skills>` block)
   that match the pattern `code-review-*` (excluding `code-review-chain`
   itself).
2. **Build the shared review context — once.** Assemble a `<review-context>`
   text block and hold it for injection in step 3:
   - The full diff of the change: `git diff <base>...HEAD -- <Target files>`
     (the `...` merge-base form), where `<base>` is the **Base Revision**
     (default `main`). If that diff is empty (the implementation is uncommitted
     in the working tree), use `git diff <base> -- <Target files>`.
   - The full contents of every changed file (the whole Target set), inlined
     **whole** and labelled by path — do not digest or excerpt. For a changed
     file too large to inline, name it in the block and leave it for leaves to
     `Read` directly; inline every other file whole.
3. **Spawn Subagents in Bounded Batches**: Launch a background subagent for each
   discovered code-review skill using the **`Agent`** tool, but keep **at most
   10 in flight at once**. Emit up to 10 spawns in one message; then, as each
   leaf finishes (and writes its verdict file), dispatch the next not-yet-run
   skill, holding concurrency at 10 until all are dispatched (bounded fan-out,
   per `iterative-work`). Specify the following for each:
   - **subagent_type**: `code-reviewer` — this agent definition
     (`.claude/agents/code-reviewer.md`) pins the reviewer model and a read-only
     tool set. Do **not** use `general-purpose`; the leaf reviewers are
     deliberately scoped and must not edit the tree.
   - **description**: `[Skill Name] Reviewer` (e.g.
     `code-review-allowlist Reviewer`)
   - **run_in_background**: `true`
   - **prompt**:
     `"Apply the [Skill Name] skill to the change described in the
        review context below and return a detailed verdict. ANALYSE THE PROVIDED
        CONTEXT for your one lens — do not re-derive the diff or reopen every
        file from the repo; use Read/Grep only to confirm a specific detail or
        widen to a referenced definition. Your response must clearly state the
        Verdict (PASS, FAIL, or N/A) and the detailed Reasoning.<SCRATCH>\n\n===
        REVIEW CONTEXT (target '[Target]', base '[Base Revision]') ===\n<review-context>"`
     — where `<SCRATCH>`, when a **Scratch Dir** was supplied, is:
     `" The
        instant you finish, write your verdict (Verdict + Reasoning) to
        [Scratch Dir]/leaves/[Skill Name].json — write-once. Do this BEFORE your
        chat reply so your work survives even if the chain is interrupted."`
   - **Skip-if-present**: when a Scratch Dir is supplied, do **not** spawn a
     leaf whose `[Scratch Dir]/leaves/[Skill Name].json` already exists **with a
     real verdict** (PASS / FAIL / N/A) — it completed on a prior run. A
     `NOT RUN` file (a prior run's bounded give-up, see step 4) is not a
     completed verdict; a resume MAY re-attempt that lens. This lets an
     interrupted chain resume cheaply while still retrying lenses that never
     produced a real verdict.
4. **Drain the Batch Queue**: Keep refilling per step 3 — dispatching the next
   skill each time a slot frees — until every discovered skill has run, then
   wait for the final in-flight leaves to finish. Each leaf has written its own
   verdict file, so you need not hold verdicts in context — track only which
   leaf files have appeared and how many of the 10 slots are free. **Run the
   fan-out to completion; do not kill it mid-flight.** If a leaf exceeds a
   generous per-leaf budget, mark it failed and dispatch **one** replacement. If
   that replacement also produces no verdict file, **give up on that lens and
   write its verdict file yourself** to `[Scratch Dir]/leaves/[Skill Name].json`
   with verdict `NOT RUN` and a reason (e.g. `timed out after one retry`); do
   not retry further. Every discovered lens therefore ends with a verdict file —
   real or `NOT RUN` — so the loop always terminates and no lens vanishes
   silently. On any partial failure, re-run only the missing lenses; keep the
   completed verdict files already in `[Scratch Dir]/leaves/` (write-once) and
   never re-run the whole fan-out.
5. **Compile Report — assert completeness first.** Before compiling, diff the
   set of `code-review-*` skills discovered in step 1 against the verdict files
   present in `[Scratch Dir]/leaves/`. **Every discovered lens MUST have a
   file.** For any lens still missing a file at compile time, add it to the
   report as a `⚠️ NOT RUN` entry — never omit it, because an omitted lens reads
   as "not flagged" when it was in fact never run. Then compile the unified
   report **by reading `[Scratch Dir]/leaves/`** (not from in-context
   accumulation), using the markdown format below; the leaves are the source of
   truth, so an interrupted compile is simply re-run against the same directory.
   **Any lens that is `NOT RUN` — a give-up file from step 4 or a still-missing
   file — forces the Final Verdict to 🔴 REVISION REQUIRED. Never report 🟢
   APPROVED while a lens is unaccounted for.**

> **Modes.** Use the context-injected per-lens fan-out above as the **default
> for real and final reviews.** A single inline agent applying all lenses at
> once is an **opt-in fast mode for quick checks only**; it under-finds, so do
> **not** use it as the default for real reviews.

## Output Format

Accumulate all subagent verdicts in your context and compile a unified
evaluation report.

> **CRITICAL CONSTRAINT:** Do NOT output the raw report to the chat buffer.
> Write the compiled report to `[Scratch Dir]/report.md` (or, absent a supplied
> Scratch Dir, a local scratch file). The per-leaf files in
> `[Scratch Dir]/leaves/` remain the source of truth — `report.md` is a
> convenience rollup, reconstructable from them at any time. Output only a
> summary and the file path to the chat buffer so the master orchestrator can
> read it later.

```markdown
# 🔍 Comprehensive Code Review: [Target]

## 🛡️ 1. Core Philosophy Check

### [Insert Skill Name (e.g., code-review-allowlist)]

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A / ⚠️ NOT RUN) **Reasoning:** [Provide
detailed explanation. MUST include code examples (max 20% of total LOC changed).
For a ⚠️ NOT RUN lens, state that it did not complete and why (timed out after
retry / never produced a verdict).]

## General Review and Feedback

[This section is for general feedback and observations that do not fit into the
above categories. It is not required to provide feedback in this section.]

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

_(Every discovered `code-review-*` lens MUST appear above with a verdict. If any
lens is ⚠️ NOT RUN, coverage is incomplete and the status is **🔴 REVISION
REQUIRED** — a missing lens can never be reported as approved.)_

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable
code changes they must make. For each action item, you MUST provide at least 2
distinct options for how to resolve it, and explicitly recommend one of the
options. For each ⚠️ NOT RUN lens, the action item is to re-run that lens.)_
```
