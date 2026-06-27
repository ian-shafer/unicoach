# RFC 75: Depth-1 Review Fan-out

## Executive Summary

The RFC pipeline's review fan-out intermittently stalls forever. The pipeline
session spawns a background review agent (`/rfc-impl-review`) with the `Agent`
tool, that agent invokes the review chains inline, and the chains spawn one leaf
reviewer per micro-skill — also with the `Agent` tool. The leaves are therefore
**grandchildren** of the pipeline session (session → background agent → leaf).
The Claude Code harness task layer reaps grandchild subagents unreliably: a leaf
finishes, writes its scratch verdict, emits its final message, yet its task
stays `running` indefinitely (observed 16h+, ~7/10 leaves stuck across
controlled batches). Subagents spawned **directly** by the top-level session
reap reliably (10/10). The defect is in the harness, not this repo; the only
lever here is the **depth** at which leaves are spawned.

This RFC collapses leaf depth from 2 to 1. The leaf fan-out — which is
context-cheap because each leaf writes its verdict to a scratch file and the
orchestrator tracks only filenames — is run **directly by the top-level
session** instead of by a backgrounded review agent. The context-heavy,
no-fan-out work (file-isolation, scope, test-completeness and guard-branch
checks, and building the shared review context) stays delegated to a depth-1
background agent that spawns **no** subagents, preserving the orchestrator's
context budget. The shared review context is materialized once to a scratch file
that leaves `Read`, so the orchestrator never holds the changed files while
owning the fan-out. No harness behaviour is assumed to change; the fix removes
the grandchild relationship that the harness mishandles.

## Detailed Design

### Root cause and the lever

A new subagent layer (a new task) is created **only** by an `Agent`-tool call. A
skill invoked with the `Skill` tool runs **inline** in the caller's context and
adds no depth. The grandchild leak therefore comes from exactly one structural
fact: there is an `Agent`-tool hop (the background `/rfc-impl-review` agent)
**above** the chain's leaf-spawning `Agent`-tool hop. Remove that intermediate
hop above the fan-out and the leaves become depth-1 children of the top-level
session — the configuration that reaps reliably.

The fix does not touch the harness, the Agent SDK, or the leaf reviewers'
analysis. It changes **which session owns the leaf fan-out** and how the shared
context is delivered.

### The constraint that forces a split

Two requirements pull in opposite directions:

- **Depth-1 leaves** require that no `Agent`-tool hop sit above the fan-out —
  the fan-out must run in the top-level session.
- **Bounded orchestrator context** (the original reason reviews were
  backgrounded) requires that context-heavy work — reading every changed file to
  build the review context, scope/scope-creep reasoning, and the guard-branch
  exercise that boots services — be delegated off the orchestrator.

These cannot both live in one place, so the review is split by context-weight
into two stages with fixed ownership:

- **Stage A — Scope & Context Prep (delegatable).** The existing
  `rfc-impl-review` Phases 1, 2, 2b (files-modified isolation,
  scope/feature-creep check, test-completeness + guard-branch exercise) **plus**
  construction of the shared review-context file. Spawns **no** subagents. Runs
  as a depth-1 background agent under the pipeline, keeping its heavy reads off
  the orchestrator.
- **Stage B — Lens Fan-out & Aggregation (top-level-owned).** The existing Phase
  3 (the `design-review-chain` and `code-review-chain` leaf fan-outs) plus Phase
  4 (master verdict). Spawns leaves and therefore **must run in the top-level
  session** so leaves are depth-1. Context-light: leaves `Read` the pre-built
  context file and write verdicts to scratch; the session holds only lens names
  and scratch paths.

Run standalone (a developer invokes `/rfc-impl-review` inline at the top level),
one session runs both stages back-to-back and leaves are already depth-1 — no
regression. The split matters only when an orchestrator wants Stage A's context
isolation **and** depth-1 leaves; the pipeline composes the two stages to get
both.

### Depth-1 fan-out invariant (normative)

A new hard rule added to the two chains, `rfc-impl-review`,
`rfc-impl-review-loop`, and `rfc-pipeline` (deliberately not lifted into the
`iterative-work` fan-out doctrine, which is kept harness-agnostic — this rule is
a workaround for a specific harness defect and stays next to the fan-out
machinery it constrains):

> A leaf-spawning fan-out (the `design-review-chain` / `code-review-chain`, and
> `rfc-impl-review` Phase 3 which drives them) MUST execute in the top-level
> session, so each leaf is a depth-1 child. It MUST NOT be invoked from inside a
> background subagent (an `Agent`-tool task), because that makes the leaves
> grandchildren, which the harness task layer reaps unreliably. Run the chain
> **inline** (`Skill` tool) from the session that is to be the leaves' direct
> parent; delegate only non-fan-out work to background agents.

### Materialized review-context file (shared-context contract)

The shared review context moves from prompt-injection to a scratch file. The
chains today assemble a `<review-context>` block (the `<base>...HEAD` diff plus
each changed file inlined whole) and inject the entire block into **every** leaf
spawn prompt. This RFC materializes that block once to
`<scratch>/review-context.md` and has each leaf `Read` it.

The block carries both the diff and the full files because each answers a
distinct question and neither suffices alone: the diff anchors the review to
**this** implementation's delta, so a leaf does not flag pre-existing code as a
finding, while the full files supply the surrounding whole that structural
lenses (e.g. `single-level-abstraction`, `srp`) need but a diff hunk omits. To
keep the file tight, only **non-test** files are inlined whole; **test-file
bodies are dropped** — a test's change is already in the diff, and its untouched
body is usually the bulk of the context yet rarely needed by a single lens, so
each changed test file (and any non-test file too large to inline) is **named**
in the block and `Read` on demand instead. Materializing once to disk pays the
remaining redundancy a single time rather than once per leaf spawn.

- **Producer:** whoever builds the context writes it to
  `<scratch>/review-context.md`, write-once (skip-if-present). Under the
  pipeline, Stage A's prep agent builds it (it already reads every changed file
  for scope checks, so no second read). Standalone, the chain builds it in its
  own `Discover/Build` step as today, but writes it to the file instead of
  holding it for injection.
- **Consumer:** each leaf spawn prompt names the file path and instructs the
  leaf to `Read` it for the shared context, rather than receiving the block
  inline. If the file exceeds the `Read` tool's token limit, the leaf reads it
  **in full** via sequential `offset`/`limit` chunks until the entire file is
  consumed — never stopping early and never substituting a partial `Grep` —
  since the diff floor alone can exceed the limit on a large change and a
  half-read context yields an unsound verdict.
- **Why:** an orchestrator that owns the fan-out must not also hold the changed
  files in its context, or the context-bound guarantee is lost. A file the
  leaves read keeps the orchestrator's per-spawn message to a path plus one lens
  name. This also removes the duplicated multi-kilobyte block from ~34 spawn
  prompts per iteration.

### Chain invocation contract (changed)

`design-review-chain` and `code-review-chain` gain one optional input and one
delivery change:

- **Review Context File** _(optional)_ — path to a pre-built
  `<scratch>/review-context.md`. When present, the chain **skips** building the
  context (skip-if-present) and points leaves at it. When absent, the chain
  builds the context as today but **writes it to `<scratch>/review-context.md`**
  and points leaves at the file.
- **Leaf prompt** — the injected `<review-context>` block is replaced by an
  instruction to `Read <scratch>/review-context.md`. Everything else about the
  leaf prompt (the one-lens instruction, the write-once verdict-file path, the
  PASS/FAIL/N-A contract) is unchanged.
- **Bounded fan-out, skip-if-present, completeness assertion, NOT-RUN give-up**
  semantics are unchanged. The chains remain `subagent_type: code-reviewer` /
  `design-reviewer` with `run_in_background: true`; only the **caller depth**
  and the **context delivery** change.

### Pipeline Phase 2 restructure

`rfc-pipeline` Phase 2 step 2 (the per-iteration implementation review) changes
from "spawn one `[rfc-impl-review]` background agent that does everything" to a
three-part sequence the orchestrator composes per iteration `i`:

1. **Prep (delegated, depth-1, no fan-out).** Spawn a background
   `general-purpose` agent `[rfc-impl-review-prep] rfc/<n> <rfc-name>` running
   `rfc-impl-review` **Stage A** on `rfc/<rfc-file>.md`. Scratch sub-path
   `<run-scratch>/phase2/impl-review/iter-<i>/`. It writes scope/test findings
   and `<scratch>/review-context.md` there and returns a compact summary.
   Write-scope: nothing tracked (`git status --porcelain` empty).
2. **Fan-out (orchestrator-owned, leaves depth-1).** The pipeline session itself
   invokes `design-review-chain` then `code-review-chain` **inline** (`Skill`
   tool), passing Target = the Phase-1 changed-file set, Base Revision =
   `<base>`, Scratch Dir = `<scratch>/design/` and `<scratch>/code/`, and Review
   Context File = the prep agent's `review-context.md`. Each leaf is a depth-1
   child of the pipeline session. The orchestrator drains the bounded (≤10)
   queues, tracking only which `leaves/<lens>.json` files appear.
3. **Aggregate (orchestrator, light).** The pipeline reconstructs the master
   verdict from scratch — the prep agent's scope/test findings plus the per-leaf
   verdict files — exactly as Phase 2 step 2b already does ("read `report.md` if
   present, else merge the per-leaf files"). If clean, exit the iteration loop;
   else spawn `/rfc-impl-fix` as today.

The **Context Window Protection** rule ("the orchestrator MUST NOT run
autonomous phases inline") gains a carve-out: the **leaf fan-out** is the one
autonomous activity the orchestrator runs inline, justified because its context
cost is bounded to lens names and scratch paths — the leaves' verdicts and the
review context live in files. Everything context-heavy (scope reasoning, file
reads, guard-branch service boots, the independent test run already done inline,
and all fixes) remains delegated or is already accepted inline.

The **write-scope table** entry for `/rfc-impl-review` (porcelain empty) now
applies to the `[rfc-impl-review-prep]` agent; the orchestrator-owned fan-out
writes only to `.scratch/` (gitignored, never in porcelain) and so needs no
allowlist entry — identical to the chains' existing row.

### Leaf agent definitions

`code-reviewer.md` and `design-reviewer.md` change one bullet: the shared review
context is delivered as a **scratch file named in the prompt, which the leaf
`Read`s**, not as injected prompt text. The read-only, write-once-verdict,
no-test-run, single-lens contract is otherwise unchanged.

### Error handling / edge cases

- **Missing context file.** If a leaf's prompt names a `review-context.md` that
  does not exist, the leaf cannot review its lens. The chain MUST verify the
  file exists before fan-out and, if absent, build it itself (the standalone
  path) rather than spawning leaves against a missing file.
- **Prep agent stall.** Stage A is a normal delegated agent under the existing
  verify-on-return-and-stall rule; on stall the orchestrator re-spawns it
  against the same scratch sub-path (skip-if-present on `review-context.md` and
  the findings artifact). The fan-out does not start until the context file
  exists.
- **Leaf stall (the original defect).** Still possible in principle, but depth-1
  leaves reap reliably in evidence (10/10). The chains' existing bounded
  one-retry-then-`NOT RUN` give-up still applies, so a genuinely stuck leaf
  yields a `NOT RUN` verdict file and forces `🔴 REVISION REQUIRED` rather than
  hanging the loop forever.
- **Standalone `/rfc-impl-review`.** Runs both stages inline at depth-0; leaves
  depth-1. No behavioural change beyond context-file delivery.
- **Orphaned grandchildren on kill.** The failure mode where killing a stalled
  orchestrator orphaned in-flight grandchildren into permanent zombies no longer
  arises: there are no grandchildren to orphan.

### Dependencies

None. No code, schema, or build change — the edits are to skill and agent
instruction Markdown under `.claude/`. The fix relies on the observed harness
property that depth-1 subagents reap reliably; it does not depend on a harness
fix.

## Tests

There is no executable unit under test — the change is to agent/skill
instructions. Verification is therefore (a) objective structural assertions on
the edited Markdown, runnable in CI-style greps, and (b) one controlled
empirical reap-rate run that is the real proof the leak is gone. Each is
enumerated below.

### Structural assertions (objective, scriptable)

1. **Chains materialize the context file.** `grep` finds `review-context.md` in
   both `code-review-chain/SKILL.md` and `design-review-chain/SKILL.md`, and the
   leaf-prompt spec instructs `Read`ing it (no longer injects the full
   `<review-context>` block into the leaf prompt).
2. **Chains accept the Review Context File input.** Both chain SKILLs list a
   `Review Context File` invocation parameter with skip-if-present semantics.
3. **Depth-1 invariant present.** The normative depth-1 fan-out rule (mentioning
   grandchild / top-level session) appears in `code-review-chain/SKILL.md`,
   `design-review-chain/SKILL.md`, `rfc-impl-review/SKILL.md`,
   `rfc-impl-review-loop/SKILL.md`, and `rfc-pipeline/SKILL.md`.
4. **rfc-impl-review split is documented.** `rfc-impl-review/SKILL.md` names a
   delegatable Stage A (Phases 1/2/2b + build context file) and a
   top-level-owned Stage B (Phase 3 fan-out + Phase 4), and assigns context-file
   construction to Stage A.
5. **Pipeline no longer backgrounds the whole review.** `rfc-pipeline/SKILL.md`
   Phase 2 step 2 names a `[rfc-impl-review-prep]` delegated prep agent and an
   orchestrator-owned inline fan-out, and no longer instructs spawning a single
   `[rfc-impl-review]` background agent that performs the fan-out.
6. **Context Window Protection carve-out.** `rfc-pipeline/SKILL.md` states the
   leaf fan-out as the bounded-context exception to "MUST NOT run autonomous
   phases inline."
7. **Leaf agent defs read the file.** `code-reviewer.md` and
   `design-reviewer.md` each instruct reading the shared context from a named
   scratch file rather than from injected prompt text.
8. **No residual grandchild dispatch.** No in-scope file instructs invoking a
   chain (or `rfc-impl-review` Phase 3) from inside a background subagent.

### Empirical reap-rate verification (the real proof)

9. **Depth-1 fan-out completes with zero stuck tasks.** Drive one
   `rfc-impl-review` pass through the restructured pipeline path against a
   sample changed-file set. After every leaf has emitted its final message and
   written its verdict file, assert via the harness task list that **zero** leaf
   tasks remain `running` — compared against the ~7/10-stuck grandchild
   baseline. The pass is repeated for both chains (≈34 leaves total) so the
   assertion covers a batch comparable to the controlled batches that exhibited
   the leak.
10. **Completeness gate still holds.** After the run, every discovered
    `code-review-*` and `design-review-*` lens has a verdict file under its
    `leaves/` dir (real verdict or `NOT RUN`); a deliberately-killed leaf
    produces a `NOT RUN` file and forces `🔴 REVISION REQUIRED`, proving the
    loop still terminates without hanging.
11. **Orchestrator context stays bounded.** Confirm the orchestrator's per-leaf
    spawn message carries only a lens name and scratch paths (not the inlined
    diff/files), demonstrating the fan-out is context-cheap to own at the top
    level.

## Implementation Plan

Each step edits instruction Markdown only; verification is `grep`/`ls` on the
worktree. Steps are ordered so the leaf contract changes before the callers that
rely on it. Declarations to be implemented are the **named** parameters, file
paths, session names, and the normative invariant text — not verbatim prose;
wording may vary so long as every named element is present.

1. **Leaf agent definitions read the context file.** In
   `.claude/agents/code-reviewer.md` and `.claude/agents/design-reviewer.md`,
   change the "Analyse the review context you are handed" bullet so the shared
   context is delivered as a scratch file (named in the prompt) the leaf
   `Read`s, not as injected prompt text. Add the **paging backstop**: when the
   file exceeds the `Read` tool's token limit, the leaf reads it in full via
   sequential `offset`/`limit` chunks until the whole file is consumed, never
   stopping early. Keep every other operating rule.
   - Verify:
     `grep -ni "review-context" .claude/agents/code-reviewer.md
     .claude/agents/design-reviewer.md`
     returns a hit in each; `grep -ni "inject" .claude/agents/code-reviewer.md`
     shows the injection wording is gone;
     `grep -ni "offset\|token limit" .claude/agents/code-reviewer.md
     .claude/agents/design-reviewer.md`
     hits both (the paging backstop).

2. **Chains materialize the context file and accept a pre-built one.** In
   `.claude/skills/code-review-chain/SKILL.md` and
   `.claude/skills/design-review-chain/SKILL.md`: add the optional **Review
   Context File** invocation parameter (skip-if-present); change the build step
   to write the context to `<scratch>/review-context.md` (and skip building when
   a pre-built file is supplied), inlining **non-test** files whole while
   **naming test-file bodies** for on-demand `Read` (the test-file trim); change
   the leaf-prompt spec to instruct `Read`ing that file instead of injecting the
   `<review-context>` block; add the **edge-case** check that the file must
   exist (or be built) before fan-out.
   - Verify:
     `grep -n "review-context.md\|Review Context File" .claude/skills/code-review-chain/SKILL.md .claude/skills/design-review-chain/SKILL.md`
     hits both;
     `grep -ni "non-test\|test file" .claude/skills/code-review-chain/SKILL.md .claude/skills/design-review-chain/SKILL.md`
     hits both (the test-trim); the leaf-prompt section no longer contains the
     literal `\n<review-context>` injection tail.

3. **Add the depth-1 fan-out invariant to both chains.** Insert the normative
   rule (leaf fan-out runs in the top-level session; never from inside a
   background subagent; harness grandchild-reap rationale) into both chain
   SKILLs.
   - Verify:
     `grep -ni "depth-1\|grandchild\|top-level session" .claude/skills/code-review-chain/SKILL.md .claude/skills/design-review-chain/SKILL.md`
     hits both.

4. **Refactor `rfc-impl-review` into Stage A / Stage B.** In
   `.claude/skills/rfc-impl-review/SKILL.md`: document Stage A (Phases 1, 2,
   2b + build `<scratch>/review-context.md`, spawns no subagents, delegatable)
   and Stage B (Phase 3 chains + Phase 4 verdict, top-level-owned, depth-1
   leaves); add the depth-1 invariant; state that Phase 3 passes the prep-built
   Review Context File to each chain.
   - Verify:
     `grep -ni "Stage A\|Stage B\|review-context.md\|depth-1" .claude/skills/rfc-impl-review/SKILL.md`
     hits each term.

5. **Note the invariant in `rfc-impl-review-loop`.** In
   `.claude/skills/rfc-impl-review-loop/SKILL.md`, state that the loop and its
   `rfc-impl-review` passes must run inline in the top-level session (never
   spawned as a background subagent), citing the depth-1 invariant.
   - Verify:
     `grep -ni "depth-1\|top-level session\|inline" .claude/skills/rfc-impl-review-loop/SKILL.md`
     hits.

6. **Restructure `rfc-pipeline` Phase 2 step 2.** In
   `.claude/skills/rfc-pipeline/SKILL.md`: replace the single
   `[rfc-impl-review]` background-agent spawn with the three-part per-iteration
   sequence — (a) spawn `[rfc-impl-review-prep]` running Stage A; (b)
   orchestrator runs `design-review-chain` then `code-review-chain` inline
   against the prep-built context file, leaves depth-1; (c) aggregate from
   scratch. Add the **Context Window Protection** carve-out for the inline
   fan-out, the depth-1 invariant, and update the **Session Naming** and
   **write-scope** notes to reference `[rfc-impl-review-prep]`.
   - Verify:
     `grep -n "rfc-impl-review-prep" .claude/skills/rfc-pipeline/SKILL.md` hits;
     the old text directing a single background agent to run the whole
     review/fan-out is gone;
     `grep -ni "carve-out\|fan-out.*inline\|depth-1" .claude/skills/rfc-pipeline/SKILL.md`
     hits.

7. **Cross-file consistency sweep.** Confirm no in-scope file still instructs
   invoking a chain or `rfc-impl-review` Phase 3 from inside a background
   subagent, and that every file referencing the fan-out names the depth-1
   requirement.
   - Verify:
     `grep -rn "depth-1\|grandchild" .claude/skills/code-review-chain .claude/skills/design-review-chain .claude/skills/rfc-impl-review .claude/skills/rfc-impl-review-loop .claude/skills/rfc-pipeline`
     shows coverage in all five.

8. **Controlled reap-rate run.** Execute one review pass through the new
   pipeline path on a sample changed-file set; after all leaves report, confirm
   via the harness task list that zero leaf tasks remain `running`, every
   discovered lens has a verdict file, and the orchestrator's spawn messages
   carried only names/paths. Record the result (stuck count vs the ~7/10
   baseline) as the acceptance evidence.
   - Verify: zero `running` leaf tasks post-completion; one verdict file per
     discovered `code-review-*` and `design-review-*` lens under the run's
     `leaves/` dirs.

## Files Modified

- `rfc/75-depth-1-review-fan-out.md` — _(new)_ this RFC.
- `.claude/skills/code-review-chain/SKILL.md` — materialize context to
  `review-context.md`; add Review Context File input; leaves `Read` the file;
  depth-1 fan-out invariant; pre-fan-out file-exists edge case.
- `.claude/skills/design-review-chain/SKILL.md` — same changes as the
  code-review chain.
- `.claude/skills/rfc-impl-review/SKILL.md` — Stage A (delegatable, builds
  context file, no subagents) / Stage B (top-level-owned fan-out + verdict)
  split; depth-1 invariant; Phase 3 passes the prebuilt context file to the
  chains.
- `.claude/skills/rfc-impl-review-loop/SKILL.md` — depth-1 invariant note: run
  inline at the top level, never as a background subagent.
- `.claude/skills/rfc-pipeline/SKILL.md` — Phase 2 step 2 restructure
  (`[rfc-impl-review-prep]` delegated Stage A + orchestrator-owned inline
  fan-out + scratch aggregation); Context Window Protection carve-out; depth-1
  invariant; Session-Naming and write-scope updates for
  `[rfc-impl-review-prep]`.
- `.claude/agents/code-reviewer.md` — shared context delivered as a scratch file
  the leaf `Read`s, not injected prompt text.
- `.claude/agents/design-reviewer.md` — same change as `code-reviewer.md`.
