---
name: rfc-pipeline
description: >-
  A master macro skill that orchestrates the entire lifecycle of an RFC from
  design to landing on the base branch. A per-run Mode knob (auto by default;
  manual on request) sets how the two review conversations behave: in auto they
  apply every reviewer's recommended option themselves, the Architect's
  interactive involvement is the design conversation plus a one-by-one RFC
  walkthrough, and everything after the design phase returns — implementation,
  the implementation review, the final commits, and the land — runs unattended.
  Optional per-run model knobs (IMPL_MODEL, REVIEW_MODEL) choose the backend
  model the implementation agent and the two review loops run on.
  Restartable at any point: on invocation it reads the on-disk run state
  (rfc-pipeline-status) and can resume any live run at the right phase without
  being told where it left off.
---

# RFC Pipeline Master Skill

This skill acts as the master orchestrator for the entire RFC-driven development
lifecycle. It manages the state machine, spawns background agents to run
autonomous phases, and delegates interactive work to separate conversations in
strict sequential order. The Architect's interactive involvement is front-loaded
into Phase 1 — the design conversation, then the design review whose auto mode
ends with a one-by-one RFC walkthrough; **returning from that review is the
approval**, and from that point the pipeline proceeds through implementation,
review, commits, and landing without asking again. All skill references in this
master orchestrator are prefixed with a slash (e.g., `/rfc-design`) to indicate
they are executable macro tools rather than plain phrases.

**Role — master.** This skill wears the **master** hat from `iterative-work`,
which defines the master / orchestrator / worker contracts and the universal
rules once: capture-on-completion, scratch ownership, checkpoint-at-gates,
verify-where-nothing-reported, bounded fan-out, write-scope enforcement. Below,
this skill states only the **pipeline-specific mechanics** (worktree, git
checkpoints, phase sequencing); where the two overlap, `iterative-work` is the
contract and this file is the instantiation.

## The Review Mode knob

One per-run setting, `auto` (default) or `manual`, chosen at kickoff — the
Architect says "manual mode" to flip it; otherwise assume auto — and recorded
immediately after the Phase 0 claim
(`rfc-pipeline-record -s <run-scratch> REVIEW_MODE <auto|manual>`) so a resumed
session recovers it. It is passed verbatim as `Mode:` in both review dispatch
prompts (Phase 1's `/rfc-review-fix` and Phase 2's `/review-fix`) and controls
nothing else: **the pipeline's own flow is identical in both modes** — same
phases, same checkpoints, same automatic commits and landing at the end. The
knob only decides what happens inside the two review conversations: auto applies
every recommended option itself (Phase 1 closing with the RFC walkthrough; Phase
2 hands-off apart from its two defined halts), while manual is the full
per-finding operator triage.

## The model knobs

Two more per-run settings, both optional, normally chosen at kickoff alongside
the Review Mode — but the Architect may set or change either one in any later
prompt, and it takes effect as long as it lands before its application point:
`IMPL_MODEL` before the `/rfc-impl` spawn, `REVIEW_MODEL` before the review
block it should shape is issued. Record a knob the moment it is stated
(`rfc-pipeline-record -s <run-scratch> IMPL_MODEL <model>` and/or
`REVIEW_MODEL <model>` — record only the ones the Architect set, re-record on
change) so a resumed session recovers it. Values are harness model aliases
(`sonnet`, `opus`, `haiku`); each value must be valid both as the `Agent` tool's
`model` parameter and as a `/model` argument, since both mechanisms carry it.
**Unset means inherit**: no `model` parameter on spawns, no `/model` line in the
copy-paste blocks — exactly the pre-knob behavior, and what a legacy run without
the keys resumes as.

- **`IMPL_MODEL`** steers the implementation: it is passed as the `Agent` tool's
  `model` parameter on the `/rfc-impl` spawn (Phase 2 step 1), including any
  re-spawn after a recovery.
- **`REVIEW_MODEL`** steers both review loops, through both mechanisms at once:
  - **Conversation model.** When set, each review block (Phase 1 step 2 and
    Phase 2 step 2) gains a `/model <review-model>` message right after the
    `/rename`, setting the session model of that operator-launched conversation.
    Everything **unpinned** spawned inside inherits it — `/rfc-review-fix`'s
    reviewer leaves and fixers, and `/review-fix`'s fixers.
  - **Pinned leaves.** `/review-fix`'s per-tier leaf reviewers are pinned to a
    mid tier by the `code-reviewer` / `design-reviewer` agent defs, and a pin
    beats session-model inheritance. So the Phase 2 dispatch prompt also carries
    `Model: <review-model>`, which `/review-fix` places as the explicit `model`
    parameter on every agent it spawns — the explicit parameter is what
    overrides the pins for that run. (Phase 1 needs no such parameter:
    `/rfc-review-fix` spawns nothing pinned, so the `/model` line alone covers
    it.)

Like the Review Mode, the knobs change nothing about the pipeline's own flow —
same phases, same checkpoints, same gates.

## How work is dispatched in Claude Code

Three dispatch mechanisms, chosen by the phase's shape:

- **Single-shot autonomous work → background agents.** Work that runs once with
  no human interaction — implementation (`/rfc-impl`) — is spawned with the
  **`Agent` tool** using `run_in_background: true`. The harness re-invokes this
  orchestrator with the agent's final report when it finishes, so you can spawn,
  then continue once notified — no polling required.
- **Interactive design → an operator-launched separate conversation.** Design
  needs live back-and-forth with the Architect, which a background agent cannot
  do. Same mechanism as the reviews below (copy-pasteable prompt, new
  conversation, pause and wait), but here the human genuinely participates.
- **The two reviews → operator-launched separate conversations.** Phase 1's
  design review is `/rfc-review-fix` (Reviewers: `rfc-design-review`; Fixer:
  `rfc-design`) and Phase 2's implementation review is `/review-fix`. Each is
  dispatched this way for **two independent** reasons, not one: each fans out
  one background leaf per reviewer skill (and one per fix), so the Depth-1
  Fan-out Invariant demands a top-level session — **in auto mode too**; and each
  is too context-heavy to run inline here (full findings, full colorized diffs,
  and in manual mode per-finding conversations across up to 39 reviewers and 4
  tiers). In auto mode the conversation runs itself once launched —
  paste-and-go, with the operator stepping in only for Phase 1's RFC walkthrough
  or a defined Phase 2 halt — but it is still a top-level session the operator
  opens, never a background agent. Same mechanism as the rest of this list:
  copy-pasteable prompt, new conversation, pause and wait for the operator's
  summary.

The stated reason for pushing design and review work into a separate
conversation is _"to keep my context window clean so I can stay focused on my
job."_

Every dispatched unit — background agent or operator-launched conversation —
MUST operate inside this run's dedicated **pipeline worktree**
(`<codebase-root>`, created in Phase 0), never the directory this orchestrator
conversation runs in. For background agents, pass it as the codebase root and do
**not** pass the Agent tool's `isolation: "worktree"` — that creates an
_ephemeral_ worktree the harness auto-deletes; this pipeline uses a _persistent_
worktree on the `pipeline/rfc-<n>` branch instead. For operator-launched
conversations, the copy-pasteable prompt names `<codebase-root>` explicitly.
Running each pipeline in its own worktree is exactly what lets multiple RFC
pipelines proceed concurrently without colliding on a shared working tree.

Use `subagent_type: general-purpose` for all background agents (it has full tool
access and can invoke the sibling skills). Continue an existing background agent
with `SendMessage` if you need to hand it a follow-up without losing its
context; otherwise spawn a fresh agent per phase.

**Review-agent model policy.** This pipeline is heavyweight and its reviews are
adversarial, so **run it on a capable session model.** The review
_orchestrators_ — Phase 1's `/rfc-review-fix` and Phase 2's `/review-fix` — are
**not** pinned; each runs in its own operator-launched conversation and inherits
whatever session model that conversation uses, so neither goes stale as models
change. That session model is where `REVIEW_MODEL`'s `/model` line lands when
the knob is set (**The model knobs** above); with the knob unset the operator's
own choice stands. The model pins in this pipeline are confined to
**non-adversarial** work where the mid tier is cheaper without costing
correctness reasoning: the **leaf** reviewers — `/review-fix`'s own per-tier
fan-out, same as the code-review and design-review chains it fans out to — held
to a mid tier via the `code-reviewer` and `design-reviewer` agent definitions in
`.claude/agents/`. Those `model:` pins are the **default, not a ceiling**: a run
with `REVIEW_MODEL` set overrides them for its own leaves via `/review-fix`'s
`Model:` parameter, and the pins remain the places to revisit if the model
lineup ever reshuffles.

Each review is owned end-to-end by its skill — reviewer fan-out, fixes, per-tier
integration (`/review-fix`), the walkthrough (`/rfc-review-fix` in auto). The
pipeline dispatches each as a single unit (see **Depth-1 Fan-out Invariant**
below) and does not reach into their internals.

## Critical Behaviours

- **Codebase Root Directory**: The orchestrator tracks the absolute path of this
  run's dedicated pipeline worktree as a required state parameter
  (`<codebase-root>`) and passes it explicitly to every interactive prompt and
  background-agent invocation. **`<codebase-root>` is the worktree created in
  Phase 0** (e.g. `../unicoach-rfc-<n>` resolved to an absolute path), NOT the
  directory this conversation runs in. The orchestrator's own shell stays in the
  original checkout, so every git command it runs MUST target the worktree —
  either `git -C "<codebase-root>" …` or by running from that directory.
- **Context Window Protection**: To prevent context bloat, the orchestrator MUST
  NOT run the heavyweight phases (the design review, implementation runs, the
  implementation review) inline in this conversation. Dispatch them per the
  mechanisms above — background agents for single-shot autonomous work, a
  copy-pasteable new-conversation prompt for interactive design and the two
  reviews.
- **Depth-1 Fan-out Invariant**: `/rfc-review-fix` (Phase 1) and `/review-fix`
  (Phase 2) each spawn one background leaf per reviewer skill (and per fix), so
  each MUST execute in a **top-level session** so every leaf is a **depth-1**
  child. Neither may be invoked from inside a background subagent (an
  `Agent`-tool task), because that makes the leaves **grandchildren**, which the
  Claude Code harness task layer reaps unreliably (a finished leaf can stay
  `running` indefinitely — the defect RFC 75 works around). Auto mode does not
  relax this — it changes who decides inside the conversation, not where the
  leaves are spawned from. The pipeline satisfies it for both reviews the same
  way: an **operator-launched new conversation** — a fresh top-level session —
  never inline in this orchestrator and never backgrounded.
- **Transparency before spawning**: Immediately before spawning any background
  agent, print one line in the chat stream naming the agent and its task, e.g.
  `Spawning agent "[rfc-impl] rfc/<n> <rfc-name>": <one-line task summary>`.
  When you instruct a background agent that it may itself spawn nested agents,
  require it to list any nested agents it launched (name + task) in its final
  report, so you can surface them to the Architect. (Claude Code does not
  deliver live mid-run notifications from a background agent, so capture this in
  the agent's returned summary rather than expecting an interrupt.)

## Session Naming

Every session this pipeline creates — both the background agents it spawns and
the separate conversations it asks the Architect to open — is named with one
uniform convention so a run's sessions are identifiable at a glance and grouped
by RFC:

```
[<skill-name>] rfc/<n> <rfc-name>
```

- `<skill-name>` is the skill that session **runs**, without the leading slash.
  - This orchestrator's **own** session is `rfc-pipeline`.
  - The operator-launched separate conversations are `rfc-design` (interactive
    design), `rfc-review-fix` (Phase 1 design review), and `review-fix` (Phase 2
    implementation review).
  - Each background agent uses the actual sub-skill it invokes (`rfc-impl`).
- `<n>` is this run's RFC number, claimed in Phase 0.
- `<rfc-name>` is a short, human-readable title for the RFC in Sentence case,
  derived from the RFC's H1 / brief description (e.g. `69-email-verification.md`
  → `Email verification`). **Record it in orchestrator state alongside `<n>`**
  and reuse the **same** string for every session in the run.

Examples: `[rfc-pipeline] rfc/69 Email verification`,
`[rfc-design] rfc/69 Email verification`,
`[rfc-impl] rfc/69 Email verification`.

How each session gets its name depends on who owns it:

- **This orchestrator session.** The harness auto-titles this conversation, and
  the model **cannot rename its own session** — only the `/rename` slash command
  can, and only the Architect can run it. So, as soon as both `<n>` and
  `<rfc-name>` are known (right after Phase 1 establishes `<rfc-name>`), **ask
  the Architect to run** `/rename [rfc-pipeline] rfc/<n> <rfc-name>` in this
  conversation. Treat it as best-effort cosmetics — if they skip it, continue
  the pipeline normally.
- **Background agents.** This string is the **`Agent` tool's `description`**
  field — the task/session name the harness surfaces. The orchestrator sets it
  directly; no human step.
- **Operator-launched child conversations** (the interactive design conversation
  and the `rfc-review-fix` / `review-fix` review conversations). These too are
  sessions whose model cannot rename itself, so auto-titling will be wrong.
  Instruct the Architect to run `/rename [<skill-name>] rfc/<n> <rfc-name>` **as
  the first message** in the new conversation (or launch it with `claude -n`),
  before running the skill.

## Change Tracking, Checkpoints & Agent Write-Scope

The pipeline runs many subagents against this run's **dedicated worktree**
across many steps. To make every step's delta inspectable, every agent's writes
verifiable, and a stalled or rogue agent recoverable, the orchestrator tracks
all state on a **pipeline branch checked out in its own git worktree**, with
**checkpoint commits**, and verifies each agent's footprint against a declared
**write-scope allowlist**. Because each run has its own worktree and branch,
concurrent pipelines never contend for the working tree.

### Pipeline scripts (the git plumbing)

The git mechanics are owned by this skill's bundled `scripts/` directory, run
via `nix develop -c`. Below they are named **bare** (e.g.
`rfc-pipeline-checkpoint`); resolve each to `scripts/<name>` relative to this
skill. They target the worktree internally and track all SHAs in the run's
state + checkpoint ledger, so the orchestrator never hand-runs git or tracks
SHAs in its context. Run each with `-h` for its full contract.

| Script (all take `-s <run-scratch>` except `claim` and `status`) | Owns                                                      |
| ---------------------------------------------------------------- | --------------------------------------------------------- |
| `rfc-pipeline-claim`                                             | Phase 0 claim; prints the run state on stdout             |
| `rfc-pipeline-status [-n n]`                                     | read-only: live runs + per-run resume facts (**Startup**) |
| `rfc-pipeline-record KEY VALUE`                                  | upsert one mid-run fact (`RFC_NAME`, `REVIEW_MODE`, …)    |
| `rfc-pipeline-checkpoint <step> [i]`                             | `--no-verify` WIP checkpoint + ledger row; prints the SHA |
| `rfc-pipeline-verify-scope [-d glob]… [allow-glob…]`             | write-scope assertion (subset / deny / clean)             |
| `rfc-pipeline-recover [step [i] \| sha]`                         | `reset --hard` to a checkpoint + `clean -fd`              |
| `rfc-pipeline-squash`                                            | `reset --soft <base>` to collapse WIP history             |
| `rfc-pipeline-adopt <branch>`                                    | ff-merge `<branch>` onto the pipeline branch, delete it   |
| `rfc-pipeline-land`                                              | ff-merge, remove worktree, archive scratch, delete branch |

`--no-verify` lives in `rfc-pipeline-checkpoint` (fast WIP commits, and the flag
stays out of the classifier-inspected Bash string) and in **one** of the two
final commits: the **code** commit runs the full pre-commit hook — its
`bin/test check` is the run's final independent gate, and the same hook run also
covers ktlint, `deno fmt --check` over the whole working tree (RFC markdown
included), and the staged-spec fuzz — so the **RFC-doc** commit is made with
`--no-verify` rather than paying for a second, identical `bin/test check` over a
tree the hook already validated (see Phase 3a). Any bare `git …` below means
`git -C "<codebase-root>" …`.

### Phase 0 — pipeline worktree (before Phase 1)

**Claim the next free RFC number `<n>` by creating the worktree**, by running
`rfc-pipeline-claim` from the original checkout.

**Capture its stdout** (the run state: `RFC_NUM`, `CODEBASE_ROOT`, `BASE_SHA`,
`RUN_SCRATCH`, …) — it parameterizes the rest of the run. Set `<codebase-root>`
to `CODEBASE_ROOT`; all pipeline work happens there, never touching the default
branch or original checkout until Phase 3. Then record the run's Review Mode
(`rfc-pipeline-record -s <run-scratch> REVIEW_MODE <auto|manual>` — auto unless
the Architect asked otherwise, per **The Review Mode knob** above) and any model
knobs the Architect set (`IMPL_MODEL` / `REVIEW_MODEL`, per **The model knobs**
above — an unset knob is simply not recorded).

Why a script: a concurrent pipeline claims its number by creating the
`pipeline/rfc-<n>` branch+worktree **before** committing any RFC file, so `<n>`
must clear the max of committed `rfc/NN`, existing `pipeline/rfc-NN` branches,
**and** `…-rfc-NN` worktrees — and the claim must be atomic (create-then-retry
on race). Hand-run git gets this wrong.

`claim` also records the base SHA in the **state file** and scaffolds
`<run-scratch>` (`<codebase-root>/.scratch/rfc-<n>/`) with the layout below and
an empty checkpoint ledger. Checkpoints append their SHA to the ledger;
`recover`/`squash` resolve targets by **step name** — so you never track SHAs by
hand. Hand `<run-scratch>` plus the exact sub-path to **every** agent you spawn
(the master-owned capture layer from `iterative-work`: write-once,
skip-if-present, gitignored, survives recovery resets). Layout:

```
<run-scratch>/
  phase1/design-review-<i>/…    # /rfc-review-fix scratch: pass-<p>/findings/, ledger.jsonl
  phase2/impl/…
```

`/review-fix`'s own evaluation output lives **outside** `<run-scratch>` entirely
— at `<codebase-root>/.scratch/review-fix/<run-id>/`, a sibling of
`.scratch/rfc-<n>/` under the same worktree, because it is that skill's durable
output, not this pipeline's WIP. `rfc-pipeline-land` archives it before the
worktree is removed (Phase 3b).

### Checkpoints

`rfc-pipeline-checkpoint -s <run-scratch> <step> [i]` snapshots the entire
worktree at a gate boundary. Policy:

- **Checkpoint at every gate boundary**: immediately before and after each
  dispatch (background spawn or operator-launched conversation). Never
  checkpoint while an agent is mid-write — the snapshot must be consistent.
- **Number every loopable step.** Any step the state machine can repeat —
  `design-review`, `review-fix` — carries a monotonic `[i]` counter. Counters
  are **monotonic per step-type across the whole run and never reused**: if the
  Architect loops the design back and the review re-runs, the next pass
  continues `[2] [3] …`, it does not reset. A number therefore identifies a
  unique moment. Non-loop steps omit the counter.

### Diffs from checkpoints

Resolve checkpoint SHAs from the ledger by step name (base SHA from state), then
plain git:

```sh
git -C "<codebase-root>" diff <prev-sha> <this-sha> -- rfc/<rfc-file>.md   # one step
git -C "<codebase-root>" diff <base-sha> HEAD                              # cumulative
```

Phase 1's write-scope verification and Phase 3's commit messages and completion
report are built from these. Anything written under `.scratch/` is gitignored
and never committed.

### Recovery

`rfc-pipeline-recover -s <run-scratch> [step [i]]` resets to a checkpoint
(default: last) and `clean -fd`s, keeping `<run-scratch>` so a re-spawn resumes.
On a normal return, take the agent's reported counts and check write-scope. On a
**stall or kill**, re-run the suite yourself — the agent reported nothing and
may have left a broken tree. Green + write-scope-clean ⇒ checkpoint and keep;
else recover and re-spawn against the same `<run-scratch>`, then escalate.

### Agent write-scope contract (enforced, not trusted)

Agent and review-conversation self-reports are not authoritative. The tree is at
a clean checkpoint before every dispatch, so on return its `git status` (or,
where the dispatched unit commits, its checkpoint diff) is the **exact**
footprint. The orchestrator asserts that footprint against the declared scope
with `rfc-pipeline-verify-scope` (exit 0 = within scope; exit 1 = violation with
the offending paths on stderr):

| Dispatched unit             | May write (tracked) | Checked with                                    |
| --------------------------- | ------------------- | ----------------------------------------------- |
| `/rfc-review-fix` (Phase 1) | `rfc/<rfc-file>.md` | not `verify-scope` — checkpoint diff; see below |
| `/rfc-impl`                 | code, tests, config | `verify-scope -d '*/SPEC.md'` (SPEC.md ban)     |
| `/review-fix` (Phase 2)     | code, tests, config | neither — its own per-tier gates; see below     |

`SPEC.md` files no longer exist in this codebase; the deny glob stops an agent
from reintroducing one.

**`/rfc-review-fix` (Phase 1) is not checked with `rfc-pipeline-verify-scope`**
— it commits each applied finding (and each walkthrough edit) on the pipeline
branch, so on return the tree is clean and the check would silently no-op.
Verify its footprint by diffing instead:
`git -C "<codebase-root>" diff --name-only <before-sha> HEAD` (resolve
`before design-review [i]` from the ledger) must list only `rfc/<rfc-file>.md`;
anything else ⇒ recover to that checkpoint and escalate.

**`/review-fix` (Phase 2) is never checked with `rfc-pipeline-verify-scope`.**
It works entirely in its own sibling worktrees and merges back with a
fast-forward (`rfc-pipeline-adopt`), so by the time the pipeline looks at
`<codebase-root>` again the tree is clean — the check would silently no-op
rather than actually verify anything. Its own per-finding diff (streamed in
auto, decided-on in manual) and per-tier integration gates are the enforcement
for that phase — finer-grained than a path allowlist ever was. Do not invoke it,
and do not try to make it watch `/review-fix`'s own sibling fix-worktrees or
`fix/*` / `integration/*` branches — it never sees them anyway (`git status` is
worktree-local), so nothing needs excluding by name.

On a violation from any of the rows above: surface it, `rfc-pipeline-recover` to
discard the rogue writes, then re-run or escalate — never silently keep
out-of-scope writes.

### Verification (run it where it counts, not everywhere)

Write-scope verifies _where_ an agent wrote, not whether its logic is correct.

**Trust an agent's reported test run.** When an agent says it ran
`nix develop -c bin/test` (the whole suite, unscoped, per CLAUDE.md) and reports
executed counts, take it — these are our own tools reporting their own output.
Re-running the suite to confirm a report it already gave costs minutes on every
step and almost never changes the answer, and the run is gated twice downstream
anyway: nothing reaches `main` without the code commit's pre-commit hook (Phase
3a) running `bin/test check` on the exact committed tree.

**The exception is a stall or kill, and it is not about doubt.** An agent that
died mid-write made no claim at all and may have left a half-applied tree.
Re-run there because there is nothing to trust, not because a report is suspect.

But run the suite **only** when code actually changed. Here **code** means the
compiled/executed sources — Kotlin, config, tests. Markdown (`*.md`: the RFC
doc, skills) is **documentation, not code**: a Markdown-only change needs
formatting (`bin/format`), never a suite run. So an `/rfc-impl` return that
touched Kotlin/config/tests re-runs the suite on a stall; the Phase 1 RFC
review, which touches only Markdown, never does.

**`/review-fix` (Phase 2) is exempt from this section's re-run policy
entirely.** It gates its own integration tip at the end of every tier
(`nix develop -c bin/format -c` + `bin/test check`, per its own Phase 3), so by
the time its merge-back reaches the pipeline the tree has already been tested
more times, and more granularly, than one suite run would add. The pipeline does
not re-run the suite around the merge-back. The **final** gate is still the code
commit's pre-commit hook (Phase 3a), which runs `bin/test check` on the exact
committed tree; that hook _is_ the last independent run, so no separate manual
run precedes it.

### Subagent rules (state these in every spawn prompt)

- **Never `git commit`** — the orchestrator owns all checkpoints.
- **Never `git stash`** — it mutates shared state and can strand the tree if the
  agent crashes mid-stash. Use `git diff HEAD` for any baseline.
- **Write durable output to your `<run-scratch>` sub-path** (write-once,
  skip-if-present) per `iterative-work` — the chat reply is a summary, the
  scratch file is the source of truth the orchestrator resumes you against.

### Phase 3 squash

`rfc-pipeline-squash -s <run-scratch>` resets `--soft` to the base SHA (worktree

- index preserved, WIP history dropped). The orchestrator then makes the two
  final commits itself (RFC doc via `--no-verify`; code through the full hook —
  see Phase 3a for why one hook run covers both).

## Startup: Fresh Run or Resume

A pipeline run's full state lives on disk — the worktree, the `pipeline/rfc-<n>`
branch, the run-scratch state file, and the checkpoint ledger — never only in
this conversation. Any `rfc-pipeline` session can therefore pick up any run at
any point, with no hand-fed recovery instructions. **On every invocation, before
anything else, run `rfc-pipeline-status`** (from the original checkout or any
worktree — it resolves the main checkout itself) and decide:

- **The Architect described a new feature** → fresh run: Phase 0 onward.
  Concurrent pipelines are supported, so a new brief starts a new run even while
  other runs are live.
- **The Architect asked to resume, or named an existing run/RFC number** →
  resume per below. Never re-run Phase 0 on a resume — the claim already
  happened, and `rfc-pipeline-claim` would claim a NEW number.
- **Live runs exist and the intent is ambiguous** → list them (`RFC_NUM`,
  `RFC_NAME`, last checkpoint) and ask which to resume, or whether to start
  fresh.

A listed run showing `STATE=missing` or `BASE_SHA_RESOLVES=false` is stale
debris (a hand-made worktree, or a claim whose base history was rewritten), not
a resumable run — surface it to the Architect as a teardown candidate
(`git worktree remove` + branch delete, their call), never auto-resume it.

### Resume procedure

Reconstruct everything from disk, not from any prior conversation:

1. **Load the run**: `rfc-pipeline-status -n <n>` prints the state file
   (including `RFC_NAME` / `RFC_FILE` / `REVIEW_MODE` / `IMPL_MODEL` /
   `REVIEW_MODEL` / `DESIGN_APPROVED`, persisted via `rfc-pipeline-record`) plus
   the derived facts the table below reads. The full ledger at
   `<run-scratch>/checkpoints.log` is the authoritative record of which gates
   were passed. If `REVIEW_MODE` was never recorded (a run predating it), it is
   `manual` — the old behavior — unless the Architect says otherwise. An absent
   `IMPL_MODEL` / `REVIEW_MODEL` means inherit — the pre-knob behavior, never a
   value to invent.
2. **Session name**: ask the Architect to run
   `/rename [rfc-pipeline] rfc/<n> <rfc-name>` (best-effort cosmetics, as
   always). If `RFC_NAME` was never recorded (a run predating it, or a crash
   before Phase 1 recorded it), re-derive it from the RFC file's H1 and record
   it now.
3. **Counters continue, never reset**: the next `[i]` for any loopable step is
   one past that step's highest `i` anywhere in the ledger.
4. **Dispatched work from the dead session**: every background agent died with
   it; an operator-launched conversation may still be live or may have finished.
   The table tells you which case you are in where git can tell; where it
   cannot, ask the operator — that is a status question, not a recovery
   instruction.
5. **Re-enter the state machine** at the point the facts imply, then proceed
   exactly as that phase's normal text prescribes — checkpoints, write-scope,
   verification and all. If `DESIGN_APPROVED` is recorded, everything from Phase
   2 on proceeds without re-asking the Architect anything.

| Observed facts                                                                                                  | Resume at                                                                                                                                                                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RFC_FILE` unset and `RFC_FILE_DETECTED=NONE`                                                                   | Phase 1 step 1 — re-issue the design-conversation block (ask first whether one is already open).                                                                                                                                                                                                     |
| Last checkpoint `before design-review [i]`; commits after it (`LAST_CHECKPOINT_SHA` ≠ `HEAD_SHA`, reachable)    | The review conversation ran (`/rfc-review-fix` commits applied findings and walkthrough edits). Ask the operator whether it finished — the walkthrough may still be open. Once confirmed done: verify by checkpoint diff, checkpoint `after design-review <i>`, record `DESIGN_APPROVED`, → Phase 2. |
| Last checkpoint `before design-review [i]`; HEAD still at it                                                    | Ask the operator whether the `/rfc-review-fix` conversation is open or done; if abandoned, re-issue the same block for pass `<i>`.                                                                                                                                                                   |
| Last checkpoint `after design-review [i]`                                                                       | The return already happened, and returning is approval — if `DESIGN_APPROVED` is somehow unset (a crash between checkpoint and record), record it now. → Phase 2 step 1.                                                                                                                             |
| Last checkpoint `before impl`                                                                                   | The impl agent is gone — a stall/kill by definition: verify write-scope and run the suite yourself; green ⇒ checkpoint `impl` and continue; broken ⇒ `rfc-pipeline-recover` to `before impl` and re-spawn against the same scratch.                                                                  |
| Last checkpoint `impl`                                                                                          | Phase 2 step 2 (dispatch `/review-fix`; `<i>` per rule 3).                                                                                                                                                                                                                                           |
| Last checkpoint `before review-fix [i]`; `INTEGRATION_BRANCHES` lists `integration/<run-id>` for that SHA's `E` | The review conversation completed: merge back (adopt + checkpoint), → Phase 3.                                                                                                                                                                                                                       |
| Last checkpoint `before review-fix [i]`; no matching integration branch                                         | Ask the operator whether that conversation is live; if abandoned, re-issue the same block (same `E` — that checkpoint's SHA).                                                                                                                                                                        |
| Last checkpoint `review-fix [i]`                                                                                | Phase 3.                                                                                                                                                                                                                                                                                             |
| `LAST_CHECKPOINT_REACHABLE=false`, or `HEAD_AT_BASE=true` with staged changes                                   | Phase 3 already began — the squash dropped the checkpoint history. `NON_CHECKPOINT_COMMITS=0` ⇒ redo 3a from the squashed state (format, regenerate messages, commit); `1` ⇒ one final commit exists — read `git log` to see which, make the other; `2` and clean ⇒ 3b (`rfc-pipeline-land`).        |
| The run's worktree no longer exists (`RUNS` lists no such run)                                                  | Nothing to resume — the run landed (or was torn down). Check `git log` on the base branch.                                                                                                                                                                                                           |

One caveat on `LAST_CHECKPOINT_REACHABLE=false`: a crash between
`rfc-pipeline-recover` and the re-checkpoint that normally follows it produces
the same signal without a squash. `NON_CHECKPOINT_COMMITS` disambiguates —
recovery lands on a checkpoint commit (`0`, with an unstaged/clean tree), while
Phase 3 trees carry staged changes or the final commits. If the facts still
conflict, show the Architect `git -C "<codebase-root>" log <base-sha>..HEAD` and
decide together.

Runs claimed before this version of the pipeline may carry legacy checkpoint
names — `before/after manual-review-fix [i]`, `architect-review [i]`, or the old
Phase 2 loop-back meaning of `review-fix [i]`. Do not force them into the table
above: surface the ledger to the Architect and decide the re-entry point
together (`rfc-pipeline-adopt` still merges a completed legacy
`integration/<run-id>` branch, and `rfc-pipeline-land` still archives a legacy
`.scratch/manual-review-fix/` dir).

## 🗺️ Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> Phase1_Design

    state Phase1_Design {
        [*] --> Design_Drafting : "Copy-paste prompt"
        Design_Drafting --> Design_Review : "Draft written; operator runs /rfc-review-fix"
        Design_Review --> Design_Drafting : "Operator requests substantial design rework"
        Design_Review --> [*] : "Operator returns (auto: walkthrough done) = approval"
    }

    Phase1_Design --> Phase2_Impl : "DESIGN_APPROVED recorded; no further prompts"

    state Phase2_Impl {
        [*] --> Autonomous_Impl : "Spawn /rfc-impl"
        Autonomous_Impl --> Review_Fix : "Checkpoint (= E); operator pastes /review-fix prompt"
        Review_Fix --> Merge_Back : "All tiers integrated + Phase 4 confirmed"
        Merge_Back --> [*] : "rfc-pipeline-adopt integration/<run-id>"
    }

    Phase2_Impl --> Phase3_Commit

    state Phase3_Commit {
        [*] --> Squash_Format
        Squash_Format --> Final_Commits : "Code commit through full hook; RFC doc --no-verify"
        Final_Commits --> Land : "rfc-pipeline-land"
        Land --> [*] : "Completion report to the Architect"
    }
```

## The Pipeline Lifecycle

Guide the Architect through Phase 1, then run Phases 2–3 without asking for
further decisions. **Before Phase 1, run Phase 0** (create the
`pipeline/rfc-<n>` branch, record the base SHA and `REVIEW_MODE`) per **Change
Tracking, Checkpoints & Agent Write-Scope** above — fresh runs only: a resume
re-enters mid-machine per **Startup: Fresh Run or Resume** and never re-claims.
Throughout, take a checkpoint at every gate boundary, number every loopable
step, verify each dispatched unit's write-scope on return, and re-run tests only
where nothing reported them — a stall or kill — per **Verification** above.

### Phase 1: Design

1. **Interactive Design (Separate Conversation)**: To protect this orchestrator
   conversation from context bloat, do NOT execute the interactive design phase
   here. Instead:

   - From the Architect's `<brief-description>`, derive `<rfc-name>` (a short
     Sentence-case title, e.g. `Email verification`) and record it in
     orchestrator state alongside `<n>`; it names every session in this run per
     **Session Naming** above. Persist it to the run state too —
     `rfc-pipeline-record -s <run-scratch> RFC_NAME '<rfc-name>'` — so a resumed
     session recovers it from disk (**Startup** above).
   - Now that both `<n>` and `<rfc-name>` are known, **ask the Architect to name
     this orchestrator session** by running
     `/rename [rfc-pipeline] rfc/<n>
     <rfc-name>` in this conversation (the
     model cannot rename its own session). This is best-effort cosmetics —
     proceed regardless of whether they do it.
   - Instruct the Architect to open a **new conversation** and, **as the very
     first thing in it, run** `/rename [rfc-design] rfc/<n> <rfc-name>` — the
     design session's model cannot rename itself, so this manual step is what
     gives that conversation the right name. Then run the `/rfc-design` skill to
     collaboratively draft the RFC.
   - Explain that this is required _"to keep my context window clean so I can
     stay focused on my job."_
   - Provide an explicit, copy-pasteable block they can use that bundles
     **both** the rename and the design prompt, substituting `<n>`,
     `<rfc-name>`, `<codebase-root>`, and `<brief-description>`:

     ```
     /rename [rfc-design] rfc/<n> <rfc-name>
     ```

     then, as the next message:

     ```
     Run /rfc-design to design a new feature in <codebase-root>: <brief-description>
     ```
   - Instruct them to return to this conversation and provide the target file
     path (e.g., `rfc/<rfc-file>.md`) once the draft is successfully written.
   - Pause and wait for the Architect's input. When they return with the path,
     persist it:
     `rfc-pipeline-record -s <run-scratch> RFC_FILE
     rfc/<rfc-file>.md`.

2. **Design Review & Fix (operator-launched separate conversation)**: Phase 1's
   review is `/rfc-review-fix` — in auto mode (the default) it applies every
   recommended option itself and then walks the Architect through the RFC
   changes one by one, diff hunk plus ≤60 words each, applying their edits on
   the spot; in manual mode the Architect triages every finding. Per the
   **Depth-1 Fan-out Invariant** it must run in a top-level session, so it is
   dispatched like `/rfc-design`: a copy-pasteable prompt for a **new
   conversation**, never inline, never backgrounded. This step is loopable (the
   Architect may send the design back for rework), so it carries an `[i]`
   counter — first entry is `<i> = 1`.

   Checkpoint first
   (`rfc-pipeline-checkpoint -s <run-scratch> before design-review <i>`). Then
   give the operator this copy-pasteable block, substituting `<n>`,
   `<rfc-name>`, `<codebase-root>`, `<rfc-file>`, `<i>`, and `<mode>` (the run's
   `REVIEW_MODE`):

   ```
   /rename [rfc-review-fix] rfc/<n> <rfc-name>
   ```

   then — only when the run has a `REVIEW_MODEL` — as its own message (this sets
   the conversation's session model, which its reviewer and fixer agents
   inherit):

   ```
   /model <review-model>
   ```

   then, as the next message:

   ```
   Invoke /rfc-review-fix in <codebase-root> with Mode: <mode>; Reviewers: rfc-design-review; Fixer: rfc-design; Target: rfc/<rfc-file>.md; Scratch Dir: <run-scratch>/phase1/design-review-<i>/. The target is Markdown only, so fixes have no test suite to run. When the run completes — in auto mode that includes the walkthrough — report back a summary of every change made to the RFC.
   ```

   Tell the Architect plainly: **returning here with that summary is the
   approval.** In auto mode the walkthrough inside that conversation is where
   they read and shape every RFC change; once they come back, this pipeline
   proceeds through implementation, review, commit, and landing on the base
   branch without asking anything further, and its next substantial message is
   the completion report. Pause and wait.

3. **On the operator's return**: `/rfc-review-fix` commits each applied finding
   and walkthrough edit, so on return the tree is clean and
   `rfc-pipeline-verify-scope` would no-op — **verify by checkpoint diff
   instead**, per the write-scope table above:
   `git -C "<codebase-root>" diff --name-only <before-sha> HEAD` must list only
   `rfc/<rfc-file>.md`; anything else ⇒
   `rfc-pipeline-recover -s <run-scratch> before design-review <i>` and
   escalate. Then checkpoint
   (`rfc-pipeline-checkpoint -s <run-scratch> after design-review <i>`) and
   record the approval —
   `rfc-pipeline-record -s <run-scratch> DESIGN_APPROVED <after-sha>` — so a
   resumed session never re-asks.

   If the Architect instead reports that the design needs substantial rework
   (not walkthrough-sized edits — those already happened in the conversation),
   route it: hand them the `/rfc-design` refine prompt
   (`/rename [rfc-design] rfc/<n> <rfc-name>`, then
   `Run /rfc-design to refine the design of the existing RFC:
   rfc/<rfc-file>.md. Discuss the following updates: <Architect-inputs>`),
   wait for their return, then loop back to **Step 2** with `<i> + 1`. Otherwise
   proceed **immediately** to Phase 2 — no further confirmation.

### Phase 2: Implementation

1. **Autonomous Implementation**: Do NOT ask the Architect to copy-paste
   prompts. Spawn a background agent with the **`Agent`** tool:

   - **subagent_type**: `general-purpose`
   - **description**: `[rfc-impl] rfc/<n> <rfc-name>`
   - **run_in_background**: `true`
   - **model**: the run's `IMPL_MODEL`, when recorded — omit the parameter
     entirely when unset (the agent then inherits this session's model). The
     same applies to any re-spawn after a recovery.
   - **prompt**:
     `"Invoke the /rfc-impl skill on RFC rfc/<rfc-file>.md to
        execute the implementation plan. The codebase root is <codebase-root>.
        Your run-scratch sub-path is <run-scratch>/phase2/impl/. If you spawn any
        nested agents, list them (name + task) in your final report."`

   Checkpoint before spawning
   (`rfc-pipeline-checkpoint -s <run-scratch> before impl`); the spawn prompt
   MUST state the **write-scope** (code, tests, config — and **no
   `*/SPEC.md`**), the `<run-scratch>` sub-path, and the subagent rules (never
   commit, never stash). Print the transparency line first, then spawn. Pause
   and wait. **On return _or stall/kill_, verify write-scope**
   (`rfc-pipeline-verify-scope -s <run-scratch> -d '*/SPEC.md'`) and read the
   agent's reported test counts — **re-run the suite yourself only on a stall or
   kill**, where no counts were reported at all. If green, checkpoint
   (`rfc-pipeline-checkpoint -s <run-scratch> impl`); if broken, recover to
   `before impl` (`rfc-pipeline-recover -s <run-scratch> before impl`) and
   re-spawn against the same scratch path (it resumes where it left off).

2. **Implementation Review & Fix (operator-launched separate conversation).**
   Phase 2's review is `/review-fix` — in auto mode (the default) it applies
   every recommended option itself, tier by tier, halting only on its two
   defined halt points (a within-tier conflict, or a tier gate that stays red
   after discarding the implicated fix); in manual mode the operator triages
   every finding. There is **no code walkthrough — by design**: the Architect
   reviews the RFC, not the implementation diffs. Per the **Depth-1 Fan-out
   Invariant** it must run in a top-level session, so it is dispatched exactly
   like Phase 1's review: a copy-pasteable prompt for a **new conversation**,
   never inline, never backgrounded. The step carries an `[i]` counter — first
   entry is `<i> = 1`.

   Checkpoint first
   (`rfc-pipeline-checkpoint -s <run-scratch> before review-fix <i>`) — this
   checkpoint's SHA **is** the run's `E` (Evaluated Commit): the commit
   `/review-fix` reviews, and every fix branches from.

   Give the operator this copy-pasteable block, substituting `<n>`,
   `<rfc-name>`, `<codebase-root>`, `<rfc-file>`, `<mode>`, `<E>` (the
   checkpoint SHA just taken), and — when the run has one — `<review-model>`:

   ```
   /rename [review-fix] rfc/<n> <rfc-name>
   ```

   then — only when the run has a `REVIEW_MODEL` — as its own message (this sets
   the conversation's session model, which its fixer agents inherit):

   ```
   /model <review-model>
   ```

   then, as the next message (drop the `Model:` clause when `REVIEW_MODEL` is
   unset — it is what overrides the pinned leaf reviewers, per **The model
   knobs**):

   ```
   Invoke /review-fix on Target rfc/<rfc-file>.md in <codebase-root> with Mode: <mode>, Model: <review-model>, Evaluated Commit E = <E>, Base Revision = <base-sha>. Use the default Fixer (/rfc-impl-fix) and the default Scratch Dir. When every tier is integrated and Phase 4 confirmed, report back the final ledger summary — open items included — and confirm the run completed.
   ```

   In auto mode, tell the operator this conversation runs itself once pasted;
   they need only return when it reports completion (or resolve a halt if one
   fires). Pause and wait for the summary.

   **Merge back, once the operator returns.** `/review-fix` leaves its result on
   `integration/<run-id>` — a branch, never a merge into the pipeline branch;
   merging it back is this orchestrator's job. Compute `<run-id>` yourself
   (`<source>-<Eshort>`: `<source>` is `<codebase-root>`'s basename, `<Eshort>`
   is `<E>`'s short SHA — both already known, no need to wait on the operator
   for it), then:

   1. `rfc-pipeline-adopt -s <run-scratch> integration/<run-id>` — fast-forwards
      the pipeline branch onto the integration tip and deletes the merged
      branch. It refuses, touching nothing, if the pipeline branch isn't a clean
      ancestor of the integration branch; resolve that with the operator before
      retrying.
   2. Checkpoint the result
      (`rfc-pipeline-checkpoint -s <run-scratch> review-fix <i>`).

   Carry the review's reported **open items** (incomplete findings, rfc-revision
   recommendations, Phase 4 re-fires) forward — they belong in the Phase 3
   completion report.

   **The scratch survives, but not inside the worktree.**
   `.scratch/review-fix/<run-id>/` — the ledger, every finding — is the run's
   durable evaluation output, and it lives inside `<codebase-root>`, which
   `rfc-pipeline-land` deletes in Phase 3b. `rfc-pipeline-land` archives it to
   the original checkout before removing the worktree (see Phase 3b) — nothing
   to do here, but do not `rm -rf` or otherwise clean up that directory
   yourself.

   Then proceed **directly to Phase 3** — no Architect review of the
   implementation, no walkthrough artifact, no confirmation prompt.

### Phase 3: Commit & Land

#### 3a. Squash, format, and make the two final commits

- **Compose the completion-report material first.** The worktree — including
  `<run-scratch>` and `.scratch/review-fix/<run-id>/` — is deleted at landing
  (the latter is archived, but read it now while the paths are fresh): the Phase
  1 findings/walkthrough ledger, the Phase 2 per-tier ledger with its
  `decided_by` split, the open items, and the reported test counts.
- **Squash the pipeline checkpoints**: collapse all WIP checkpoints into a clean
  staging state with `rfc-pipeline-squash -s <run-scratch>` (resets `--soft` to
  the recorded base SHA; working tree and index preserved, only the WIP history
  dropped). The two final commits are created from this state.
- **Format the tree**: run `nix develop -c bin/format` in `<codebase-root>`. It
  reformats Kotlin (`ktlint --format`) and Markdown (`deno fmt`) in place and is
  idempotent — running the formatter is always safe. Doing it now means the code
  commit's hook formatting checks (ktlint, `deno fmt --check`) pass on the first
  try instead of blocking the commit on a formatting nit.
- Ingest the final workspace diff (`git -C "<codebase-root>" diff <base-sha>` /
  `git -C "<codebase-root>" status`) and write two commit messages following the
  repository's commit guidelines: one for the new/updated RFC markdown document,
  and one for the actual code implementation.
- **Commit protocol — one hook run, not two.** The whole change is on disk in
  the working tree throughout both commits, so a single `bin/test check` (run by
  the hook) validates the suite against the working tree. The orchestrator makes
  both commits itself, in `<codebase-root>`:
  1. Commit the **code** **through the full hook** via
     `nix develop -c git commit`. This stages the code — including
     `api-specs/openapi.yaml` if it changed, so the staged-spec fuzz gate fires
     correctly — and its `bin/test check` **is** the run's final independent
     test gate. If the hook fails on a formatting nit, re-run
     `nix develop -c bin/format` and retry once. If it fails on tests, **stop**:
     the tree is intact — report the failure output and the run's state to the
     Architect and wait. This is the one place after Phase 1 where the pipeline
     stops for a human.
  2. Commit the **RFC markdown doc** with `--no-verify`
     (`nix develop -c git commit --no-verify`). It is Markdown-only
     (documentation, not code) and already formatted by `bin/format` above, so
     it needs no suite run — a second full `bin/test check` here would be pure
     waste. Order of the two commits does not matter; only the code commit needs
     the hook.

#### 3b. Land the branch, tear down the worktree, report

Once both commits exist on `pipeline/rfc-<n>` (inside the worktree), run
`rfc-pipeline-land -s <run-scratch>`.

It runs the teardown from the original checkout: fast-forward the base branch
onto `pipeline/rfc-<n>`, archive `.scratch/review-fix/` (and any legacy
`.scratch/manual-review-fix/`) to the original checkout's `.scratch/`,
`git worktree remove`, and delete the merged branch. It refuses if the worktree
still has uncommitted changes (a safety check, not an obstacle to force past)
and stops cleanly if the fast-forward is not possible because the base advanced
— open a PR or rebase the branch instead. After it succeeds, **verify the
land**: `git rev-parse <base-branch>` in the original checkout must equal the
pipeline tip that carried the two final commits.

Close the run with the **completion report** — the Architect's first substantial
message since Phase 1, so it must stand alone:

- RFC number, name, and the two landed commit SHAs with a `--stat` summary.
- Implementation summary: what `/rfc-impl` built, its reported test counts.
- Review summary: Phase 1 findings applied/skipped and walkthrough edits; Phase
  2 per tier — findings found / applied / discarded, with the `decided_by`
  split, and any halts and how they were resolved.
- **Open items**: everything the reviews declined to decide (incomplete
  findings, rfc-revision recommendations, Phase 4 re-fires) — the list the
  Architect may want to act on later.
- Where the full detail lives: the landed commits on the base branch, and the
  archived scratch at `.scratch/review-fix-archive/rfc-<n>/`.
