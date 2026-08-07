---
name: design-review-chain
description: >-
  A pre-configured macro skill that runs a comprehensive, adversarial design
  review of a target. It materializes the shared review context once (the diff
  plus each changed file) to a scratch file and points a bounded fan-out of
  background `design-review-*` leaf agents — one per micro-skill — at it (each
  leaf reads the file), then compiles their verdicts into a single report,
  asserting every discovered lens is accounted for and marking any that did not
  run. The leaf fan-out runs in the top-level session (depth-1 leaves), never
  from a background subagent. This per-lens fan-out is the default for real
  reviews; a single inline agent applying all lenses is an opt-in fast mode that
  under-finds.
---

# Design Review Chain

This skill acts as a specific execution macro. It ensures that when a user asks
to "do a full design review" or "run design-review-chain", the orchestrator
concurrently spawns background subagents to evaluate all design-review rules in
parallel.

**Role — orchestrator.** This chain wears the **orchestrator** hat from
`iterative-work` (bounded fan-out, per-leaf capture, reconstruct-from-artifacts,
resumable via skip-if-present); the `design-review-*` leaves are **workers**.
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
- **Review Context File** _(optional)_: Path to a pre-built
  `<scratch>/review-context.md` (the `<base>...HEAD` diff plus each changed
  file's contents). When supplied, the chain **skips** building the context
  (skip-if-present) and points the leaves at this file. When absent, the chain
  builds the context itself (step 2) and **writes it to
  `<scratch>/review-context.md`** before fan-out. Either way the leaves `Read`
  the file rather than receiving the context inline.

If the user does not provide a Target in their prompt, you MUST pause and ask
them to provide it before continuing.

## Depth-1 Fan-out Invariant (normative)

This leaf-spawning fan-out (steps 3–4) **MUST execute in the top-level
session**, so each leaf is a **depth-1** child of that session. It **MUST NOT**
be invoked from inside a background subagent (an `Agent`-tool task): doing so
makes the leaves **grandchildren** of the top-level session, which the Claude
Code harness task layer reaps unreliably (a finished leaf can stay `running`
indefinitely). Run this chain **inline** (via the `Skill` tool) from the session
that is to be the leaves' direct parent; delegate only non-fan-out work (e.g.
building the review context) to background agents. This is a workaround for a
specific harness defect and stays next to the fan-out machinery it constrains.

## Execution

1. **Discover Review Skills**: Scan the list of all active skills available in
   your execution context (defined in your system prompt or `<skills>` block)
   that match the pattern `design-review-*` (excluding `design-review-chain`
   itself).
2. **Materialize the shared review context — once, to a file.** The shared
   context lives in `<scratch>/review-context.md`, which the leaves `Read`; it
   is never injected into leaf prompts.
   - **If a Review Context File was supplied** (skip-if-present): do **not**
     rebuild it. Verify the named file exists; if it does, use it as
     `<scratch>/review-context.md` and skip to step 3.
   - **Otherwise build it and write it to `<scratch>/review-context.md`.**
     Assemble a `<review-context>` block containing:
     - The full diff of the change: `git diff <base>...HEAD -- <Target files>`
       (the `...` merge-base form), where `<base>` is the **Base Revision**
       (default `main`). If that diff is empty (the implementation is
       uncommitted in the working tree), use
       `git diff <base> -- <Target files>`.
     - The full contents of every changed **non-test** file, inlined **whole**
       and labelled by path — do not digest or excerpt. **Test files are the
       exception: do NOT inline their bodies.** A test file's changes are
       already in the diff above, and its untouched body is usually the bulk of
       the context while rarely needed by a single lens — so instead **name**
       each changed test file in a "named — `Read` on demand" list, and a leaf
       that needs more than the diff `Read`s it from the repo. (A test file is
       one under a `test/` / `tests/` directory or whose name matches `*Test` /
       `*Tests` / `*Spec` / `*_test` / `*.test.*` — e.g. `src/test/**` or
       `*Test.kt`.) Likewise, for any **non-test** file too large to inline,
       name it for leaves to `Read` directly; inline every other non-test file
       whole.

     Write that block to `<scratch>/review-context.md` (write-once,
     skip-if-present), then proceed. Do **not** hold the block in context for
     injection — the leaves read the file.
   - **Edge case — context file must exist before fan-out.** A leaf pointed at a
     missing `review-context.md` cannot review its lens. Confirm
     `<scratch>/review-context.md` exists (supplied-and-verified, or just
     written) **before** spawning any leaf; if a supplied file is absent, fall
     back to building it yourself rather than fanning out against a missing
     file.
3. **Spawn Subagents in Bounded Batches**: Launch a background subagent for each
   discovered design-review skill using the **`Agent`** tool, but keep **at most
   10 in flight at once**. Emit up to 10 spawns in one message; then, as each
   leaf finishes (and writes its verdict file), dispatch the next not-yet-run
   skill, holding concurrency at 10 until all are dispatched (bounded fan-out,
   per `iterative-work`). Specify the following for each:
   - **subagent_type**: `design-reviewer` — this agent definition
     (`.claude/agents/design-reviewer.md`) pins the reviewer model and a
     read-only tool set. Do **not** use `general-purpose`; the leaf reviewers
     are deliberately scoped and must not edit the tree.
   - **description**: `[Skill Name] Reviewer` (e.g.
     `design-review-srp Reviewer`)
   - **run_in_background**: `true`
   - **prompt**:
     `"SKILL: [Skill Name]\n\nInvoke the Skill tool with the skill named in the
        SKILL: directive above, then apply that one lens to the
        shared review context and return a detailed verdict. READ the shared
        review context from <scratch>/review-context.md (the base-to-HEAD diff
        plus each changed file's contents, target '[Target]', base
        '[Base Revision]') and analyse THAT for your one lens — do not re-derive
        the diff or reopen every file from the repo; use Read/Grep only to
        confirm a specific detail or widen to a referenced definition. Your
        response must clearly state the Status (PASS or FAIL) and the detailed
        Evaluation.<SCRATCH>"`
     — where `<scratch>/review-context.md` is the materialized context file from
     step 2 (the supplied **Review Context File** when one was passed), and
     `<SCRATCH>`, when a **Scratch Dir** was supplied, is:
     `" The
        instant you finish, write your verdict (Status + Evaluation) to
        [Scratch Dir]/leaves/[Skill Name].json — write-once. Do this BEFORE your
        chat reply so your work survives even if the chain is interrupted."`
   - **Skip-if-present**: when a Scratch Dir is supplied, do **not** spawn a
     leaf whose `[Scratch Dir]/leaves/[Skill Name].json` already exists **with a
     real verdict** (PASS / FAIL) — it completed on a prior run. A `NOT RUN`
     file (a prior run's bounded give-up, see step 4) is not a completed
     verdict; a resume MAY re-attempt that lens. This lets an interrupted chain
     resume cheaply while still retrying lenses that never produced a real
     verdict.
4. **Drain the Batch Queue**: Keep refilling per step 3 — dispatching the next
   skill each time a slot frees — until every discovered skill has run, then
   wait for the final in-flight leaves to finish. Each leaf has written its own
   verdict file, so you need not hold verdicts in context — track only which
   leaf files have appeared and how many of the 10 slots are free. **Run the
   fan-out to completion; do not kill it mid-flight.** If a leaf exceeds a
   generous per-leaf budget, mark it failed and dispatch **one** replacement. If
   that replacement also produces no verdict file, **give up on that lens and
   write its verdict file yourself** to `[Scratch Dir]/leaves/[Skill Name].json`
   with status `NOT RUN` and a reason (e.g. `timed out after one retry`); do not
   retry further. Every discovered lens therefore ends with a verdict file —
   real or `NOT RUN` — so the loop always terminates and no lens vanishes
   silently. On any partial failure, re-run only the missing lenses; keep the
   completed verdict files already in `[Scratch Dir]/leaves/` (write-once) and
   never re-run the whole fan-out.
5. **Compile Report — assert completeness first.** Before compiling, diff the
   set of `design-review-*` skills discovered in step 1 against the verdict
   files present in `[Scratch Dir]/leaves/`. **Every discovered lens MUST have a
   file.** For any lens still missing a file at compile time, add it to the
   report as a `⚠️ NOT RUN` entry — never omit it, because an omitted lens reads
   as "not flagged" when it was in fact never run. Then compile the unified
   report **by reading `[Scratch Dir]/leaves/`** (not from in-context
   accumulation), using the markdown format below; the leaves are the source of
   truth, so an interrupted compile is simply re-run against the same directory.
   **Any lens that is `NOT RUN` — a give-up file from step 4 or a still-missing
   file — forces the Final Verdict to 🔴 REVISION REQUIRED. Never report 🟢
   APPROVED while a lens is unaccounted for.**

> **Modes.** Use the per-lens fan-out above (leaves `Read` the materialized
> context file) as the **default for real and final reviews.** A single inline
> agent applying all lenses at once is an **opt-in fast mode for quick checks
> only**; it under-finds, so do **not** use it as the default for real reviews.

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
# 🔍 Comprehensive Design Review: [Target]

## 🏗️ 1. Design Review Check

### [Insert Skill Name (e.g., design-review-srp)]

**Evaluation:** [Provide observation. For a ⚠️ NOT RUN lens, state that it did
not complete and why (timed out after retry / never produced a verdict).]
**Status:** ✅ PASS / ❌ FAIL / ⚠️ NOT RUN

## General Review and Feedback

[This section is for general feedback and observations that do not fit into the
above categories. It is not required to provide feedback in this section.]

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

_(Every discovered `design-review-*` lens MUST appear above with a status. If
any lens is ⚠️ NOT RUN, coverage is incomplete and the status is **🔴 REVISION
REQUIRED** — a missing lens can never be reported as approved.)_

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable
code changes they must make. For each action item, you MUST provide at least 2
distinct options for how to resolve it, and explicitly recommend one of the
options. For each ⚠️ NOT RUN lens, the action item is to re-run that lens.)_
```
