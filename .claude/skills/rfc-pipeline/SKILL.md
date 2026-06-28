---
name: rfc-pipeline
description: >-
  A master macro skill that orchestrates the entire lifecycle of an RFC from
  design to spec synchronization. It runs autonomous phases as background agents
  to isolate contexts and prevent context bloat, and delegates interactive
  phases to separate conversations.
---

# RFC Pipeline Master Skill

This skill acts as the master orchestrator for the entire Spec-Driven
Development (SDD) lifecycle of an RFC. It manages the state machine, spawns
background agents to run autonomous loops, and guides the human Architect
through interactive phases in strict sequential order. All skill references in
this master orchestrator are prefixed with a slash (e.g., `/rfc-design`) to
indicate they are executable macro tools rather than plain phrases.

**Role — master.** This skill wears the **master** hat from `iterative-work`,
which defines the master / orchestrator / worker contracts and the universal
rules once: capture-on-completion, scratch ownership, checkpoint-at-gates,
verify-on-return-_and_-stall, bounded fan-out, write-scope enforcement,
never-trust-green. Below, this skill states only the **pipeline-specific
mechanics** (worktree, git checkpoints, phase sequencing, the Spec/Invariants
gates); where the two overlap, `iterative-work` is the contract and this file is
the instantiation.

## How work is dispatched in Claude Code

Two execution mechanisms, chosen by whether the phase needs a human in the loop:

- **Autonomous phases → background agents.** Review loops, implementation, and
  fix passes run with no human interaction, so spawn them with the **`Agent`
  tool** using `run_in_background: true`. The harness re-invokes this
  orchestrator with the agent's final report when it finishes, so you can spawn,
  then continue once notified — no polling required. Every spawn MUST direct the
  agent at this run's dedicated **pipeline worktree** (`<codebase-root>`,
  created in Phase 0): pass it as the codebase root and have the agent operate
  inside it. Do **not** pass the Agent tool's `isolation: "worktree"` — that
  creates an _ephemeral_ worktree the harness auto-deletes; this pipeline uses a
  _persistent_ worktree on the `pipeline/rfc-<n>` branch instead. Running each
  pipeline in its own worktree is exactly what lets multiple RFC pipelines
  proceed concurrently without colliding on a shared working tree.
- **Interactive phases → a separate conversation.** Design needs live
  back-and-forth with the Architect, which a background agent cannot do. For it,
  give the Architect a copy-pasteable prompt to run in a **new conversation**,
  then pause and wait for them to return. The stated reason is _"to keep my
  context window clean so I can stay focused on my job."_ Note that **SPEC.md
  synchronization is no longer interactive** — it is LLM-managed and runs as a
  background agent (Phase 3a). The only Phase 3 human touchpoint is the
  **minimal, inline** review gate for `INVARIANTS.md` (Phase 3b), handled in
  this orchestrator conversation, not a separate one.

Use `subagent_type: general-purpose` for all background agents (it has full tool
access and can invoke the sibling skills). Continue an existing background agent
with `SendMessage` if you need to hand it a follow-up without losing its
context; otherwise spawn a fresh agent per phase.

**Review-agent model policy.** This pipeline is heavyweight and its reviews are
adversarial, so **run it on a capable session model.** The review
_orchestrators_ — the Phase 1 `/rfc-review-loop` (RFC design review) and
**every** `/rfc-impl-review` / `/rfc-impl-review-loop` pass in Phase 2 — are
**not** pinned; they inherit that session model and so never go stale as models
change. The model pins in this pipeline are confined to **non-adversarial** work
where the mid tier is cheaper without costing correctness reasoning: (1) the
**leaf** reviewers — the code-review and design-review chains fan out to many
small parallel agents, held to a mid tier via the `code-reviewer` and
`design-reviewer` agent definitions in `.claude/agents/`; and (2) the **Phase 3
spec/invariants agents** (`spec-sync-loop`, `invariants-writer`), pinned to
`sonnet` at their spawn because SPEC synthesis and invariant distillation are
descriptive/filtering work — and the invariants still pass an inline human gate.
These pins — `model:` in those two agent files, plus `model: sonnet` on the two
Phase 3 spawns — are the places to revisit if the model lineup ever reshuffles.
The adversarial review orchestrators above stay on the session model on purpose.

The shared review context — the base-to-HEAD diff plus each changed file's
contents — is materialized **once to `<scratch>/review-context.md`** (by the
`[rfc-impl-review-prep]` Stage A agent in Phase 2), and each leaf `Read`s that
file rather than receiving it inline. Use the per-lens fan-out as the
**default** for these reviews; the single-inline-agent mode (all lenses in one
pass) is an opt-in fast mode only, never the default for a real pipeline review.

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
  NOT run autonomous phases (review loops, implementation runs) inline in this
  conversation. Dispatch them per the two mechanisms above — background agents
  for autonomous work, a copy-pasteable new-conversation prompt for interactive
  work.
  - **Carve-out — the leaf review fan-out runs inline.** The one autonomous
    activity the orchestrator runs **inline** is the Phase 2
    implementation-review **leaf fan-out** (the `design-review-chain` /
    `code-review-chain`, Phase 2 step 2b below). This is required by the
    **Depth-1 Fan-out Invariant**: the leaves must be **depth-1** children of
    this top-level session to reap reliably, and a background-agent hop above
    the fan-out makes them grandchildren that the harness reaps unreliably. It
    is safe to own inline because its context cost is bounded to **lens names
    and scratch paths** — the per-leaf verdicts and the shared review context
    live in files, not this conversation. Everything context-heavy (scope
    reasoning, the changed-file reads, guard-branch service boots, the
    independent test run, and all fixes) stays delegated to background agents or
    is already accepted inline.

- **Depth-1 Fan-out Invariant**: A leaf-spawning review fan-out (the
  `design-review-chain` / `code-review-chain`, and `rfc-impl-review` Phase 3
  that drives them) MUST execute in the **top-level session** so each leaf is a
  **depth-1** child. It MUST NOT be invoked from inside a background subagent
  (an `Agent`-tool task), because that makes the leaves **grandchildren**, which
  the Claude Code harness task layer reaps unreliably (a finished leaf can stay
  `running` indefinitely — the defect RFC 75 works around). So the pipeline
  **never backgrounds a whole `rfc-impl-review`**: it delegates only the
  no-fan-out Stage A (`[rfc-impl-review-prep]`) and runs the leaf fan-out inline
  (Phase 2 step 2 below).
- **Transparency before spawning**: Immediately before spawning any background
  agent, print one line in the chat stream naming the agent and its task, e.g.
  `Spawning agent "[rfc-review-loop] rfc/<n> <rfc-name>": <one-line task summary>`.
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
  - The interactive design conversation is `rfc-design`.
  - Each background agent uses the actual sub-skill it invokes
    (`rfc-review-loop`, `rfc-impl`, `rfc-impl-review-loop`, `rfc-impl-fix`,
    `rfc-impl-review`, `spec-sync-loop`, `invariants-writer`). The Phase 2
    delegated Stage A prep agent — which runs `rfc-impl-review` Stage A only and
    spawns no leaves — is named `rfc-impl-review-prep`. The leaf fan-out (Phase
    2 step 2b) runs inline in **this** orchestrator session, so it spawns no
    separately-named session.
- `<n>` is this run's RFC number, claimed in Phase 0.
- `<rfc-name>` is a short, human-readable title for the RFC in Sentence case,
  derived from the RFC's H1 / brief description (e.g. `69-email-verification.md`
  → `Email verification`). **Record it in orchestrator state alongside `<n>`**
  and reuse the **same** string for every session in the run.

Examples: `[rfc-pipeline] rfc/69 Email verification`,
`[rfc-design] rfc/69 Email verification`,
`[rfc-impl] rfc/69 Email verification`,
`[spec-sync-loop] rfc/69 Email verification`.

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
- **Interactive child conversations.** These too are sessions whose model cannot
  rename itself, so auto-titling will be wrong. Instruct the Architect to run
  `/rename [<skill-name>] rfc/<n> <rfc-name>` **as the first message** in the
  new conversation (or launch it with `claude -n`), before running the skill.

## Change Tracking, Checkpoints & Agent Write-Scope

The pipeline runs many subagents against this run's **dedicated worktree**
across many steps. To make every step's delta inspectable, every agent's writes
verifiable, and a stalled or rogue agent recoverable, the orchestrator tracks
all state on a **pipeline branch checked out in its own git worktree**, with
**checkpoint commits**, and verifies each agent's footprint against a declared
**write-scope allowlist**. Because each run has its own worktree and branch,
concurrent pipelines never contend for the working tree.

> Convention: throughout this section the bare `git …` shown in command snippets
> is shorthand for `git -C "<codebase-root>" …`. The orchestrator's shell is not
> inside the worktree, so it must always target it explicitly.

### Phase 0 — pipeline worktree (before Phase 1)

**First, pick the next free RFC number `<n>` — and claim it by creating the
worktree.** The committed `rfc/NN-*.md` files are NOT the only claim on a
number: a concurrent pipeline reserves its number the moment it creates the
`pipeline/rfc-<n>` branch and worktree, **before** any RFC file is committed.
Choosing a number by looking only at committed files will collide with an
in-flight pipeline. So `<n>` must be greater than **both**:

- the highest committed `rfc/NN-*.md`, **and**
- the highest existing `pipeline/rfc-NN` branch or `../unicoach-rfc-NN`
  worktree.

```sh
ls rfc/ | grep -oE '^[0-9]+' | sort -n | tail -1          # highest committed RFC file
git branch --list 'pipeline/rfc-*'                         # branches already claiming a number
git worktree list                                          # worktrees already claiming a number
```

Take `<n>` = one past the max across all three. An existing `pipeline/rfc-<k>`
branch or worktree — even one that is empty, clean, and sitting at the base
commit — is an **active ownership claim by another run**; never reuse or
repurpose it, treat its number as taken and move past it. **Creating your own
worktree is what locks in your ownership**, so do it immediately and atomically
before any design work, so a parallel pipeline starting at the same moment sees
your branch and skips your number.

Then create a dedicated worktree on a new pipeline branch off the default
branch, and record the base commit. The worktree isolates this run so several
pipelines can proceed at once:

```sh
git rev-parse main                                          # base commit — record the literal SHA
git worktree add -b pipeline/rfc-<n> ../unicoach-rfc-<n> main
```

If `git worktree add` fails because the branch or path already exists, you
guessed a taken number — bump `<n>` and retry rather than forcing past it.

Set `<codebase-root>` to the absolute path of `../unicoach-rfc-<n>` and use it
as the target for every agent and every `git -C "<codebase-root>" …` command.
All pipeline work — RFC edits, implementation, spec sync — happens in this
worktree on the `pipeline/rfc-<n>` branch. The default branch and the original
checkout are never touched until the Architect commits at Phase 3.

**Persist SHAs in orchestrator state, not shell variables.** The Bash tool's
shell state does not survive between calls, so a `base=$(…)` variable is gone by
the next command. Record the **literal base SHA** in your own tracked state, and
capture each checkpoint's **literal SHA** as you create it (the commit prints
it, or `git rev-parse HEAD`). Diff and squash against those recorded SHAs — do
not rely on a shell variable or on diffing by commit message (git cannot diff by
message; resolve via the captured SHA or `git log --grep`).

**Establish the run scratch directory.** Set `<run-scratch>` to
`<codebase-root>/.scratch/rfc-<n>/`, record it in orchestrator state next to
`<codebase-root>` and the base SHA, and hand it — plus the exact sub-path — to
**every** agent you spawn. It is the master-owned capture layer from
`iterative-work` (write-once, skip-if-present, gitignored, survives recovery
resets); here it just needs its fixed layout:

```
<run-scratch>/
  phase1/review-loop/iter-<i>/…
  phase2/impl/…
  phase2/impl-review/iter-<i>/review-context.md    # Stage A ([rfc-impl-review-prep]) builds it; leaves Read it
  phase2/impl-review/iter-<i>/prep.json            # Stage A scope/test findings handoff
  phase2/impl-review/iter-<i>/design/leaves/<lens>.json  # one file per design leaf, write-once on completion
  phase2/impl-review/iter-<i>/code/leaves/<lens>.json    # one file per code leaf, write-once on completion
  phase2/impl-review/iter-<i>/report.md            # compiled — reconstructable from leaves/
  phase2/impl-fix/iter-<i>/ledger.jsonl            # one line per finding: applied | skipped | failed
  phase3/spec-sync/<dir-slug>.json
  phase3/invariants/<dir-slug>.json
```

### Checkpoints

A checkpoint is a WIP commit capturing the **entire** working tree (tracked and
untracked) at a gate boundary:

```sh
git add -A && git commit -m "pipeline(rfc-<n>): <step> [<i>]"
```

- **Checkpoint at every gate boundary**: immediately before and after each
  subagent spawn, and before each Architect review. Never checkpoint while an
  agent is mid-write — the snapshot must be consistent.
- **Number every loopable step.** Any step the state machine can repeat —
  `review-loop`, `impl-review`, `impl-fix`, `architect-review` — carries a
  monotonic `[i]` counter. Counters are **monotonic per step-type across the
  whole run and never reused**: if the Architect loops back and re-runs reviews,
  they continue `[4] [5] …`, they do not reset. A number therefore identifies a
  unique moment. Non-loop steps omit the counter.

### Diffs for the Architect walkthrough

Per-step delta and cumulative diff are plain git:

```sh
git diff <prev-checkpoint> <this-checkpoint> -- rfc/<rfc-file>.md   # what one step changed
git diff <base-sha> HEAD                                            # full cumulative diff
```

Build every walkthrough and the `.scratch/implementation_diff.md` artifact from
these. `.scratch/` is the home for transient artifacts (diff walkthroughs, agent
reports); it is gitignored and never committed.

### Recovery

Because each gate is a commit, a stalled, crashed, or rogue agent is undone
cleanly:

```sh
git reset --hard <last-good-checkpoint> && git clean -fd
```

The `-fd` (never `-x`) keeps `<run-scratch>` intact, so a re-spawn resumes from
where it stalled. Per `iterative-work`, **verify before you trust or reset** —
on every return _and_ every stall/kill, independently re-run the suite (a
stalled agent may have left a broken tree): green + write-scope-clean ⇒
checkpoint and keep; else reset above and re-spawn against the same
`<run-scratch>`. Then re-run or escalate to the Architect.

### Agent write-scope contract (enforced, not trusted)

Subagent self-reports are not authoritative. The tree is at a clean checkpoint
before every spawn, so after the agent returns `git status --porcelain` is its
**exact** footprint. Every spawn declares an allowlist; the orchestrator asserts
the footprint is a subset of it.

| Agent                              | May write (tracked)      | Post-run assertion                                                                                       |
| ---------------------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------- |
| `/rfc-review-loop`                 | `rfc/<rfc-file>.md` only | porcelain ⊆ {the RFC}; any code/test write ⇒ **FAIL**                                                    |
| `[rfc-impl-review-prep]` (Stage A) | nothing                  | porcelain **empty** ⇒ pass; else **FAIL**                                                                |
| `/rfc-impl`, `/rfc-impl-fix`       | code, tests, config      | porcelain contains **no `*/SPEC.md` and no `*/INVARIANTS.md`** (the Spec/Invariants Touch Ban, enforced) |

The orchestrator-owned leaf fan-out (Phase 2 step 2b, run inline) writes only to
`.scratch/` (gitignored, never in porcelain), so it needs no allowlist row —
identical to the review chains' existing scratch-only footprint.

- `.scratch/` is gitignored, so review/impl chains may write reports there
  freely — those writes never appear in `git status --porcelain` and need no
  allowlist exception.
- A net-zero edit (modify then revert) is invisible and acceptable.
- On any violation: surface it to the Architect,
  `git reset --hard
    <checkpoint> && git clean -fd` to discard the rogue
  writes, then re-run or escalate. Do NOT silently keep out-of-scope writes.

### Independent verification (never trust "green")

Write-scope verifies _where_ an agent wrote, not whether its logic is correct.
Separately, **before reporting any test result as passing, the orchestrator
re-runs the suite itself** via the project's test harness and reads the real
pass/fail counts. Never relay an agent's "all tests pass" claim without an
independent run — agent reports have been wrong, and a green claim has masked a
broken tree.

### Subagent rules (state these in every spawn prompt)

- **Never `git commit`** — the orchestrator owns all checkpoints.
- **Never `git stash`** — it mutates shared state and can strand the tree if the
  agent crashes mid-stash. Use `git diff HEAD` for any baseline.
- **Write durable output to your `<run-scratch>` sub-path** (write-once,
  skip-if-present) per `iterative-work` — the chat reply is a summary, the
  scratch file is the source of truth the orchestrator resumes you against.

### Phase 3 squash

At Phase 3, collapse all WIP checkpoints into the clean final commits, using the
**recorded base SHA**:

```sh
git reset --soft <base-sha>
```

The working tree and index are preserved; only the WIP history is dropped. Then
create the Architect's two commits (RFC doc; code + specs). The checkpoint
history never leaves the pipeline branch.

## 🗺️ Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> Phase1_Design

    state Phase1_Design {
        [*] --> Design_Drafting : "Copy-paste prompt"
        Design_Drafting --> Design_Review : "Draft written"
        Design_Review --> Architect_Spec_Gate : "Review PASS or 3-iter cap"
        Architect_Spec_Gate --> Design_Drafting : "Architect requests changes"
        Architect_Spec_Gate --> [*] : "Architect Approved"
    }

    Phase1_Design --> Phase2_Impl : "Architect Approved"

    state Phase2_Impl {
        [*] --> Autonomous_Impl : "Spawn /rfc-impl"
        Autonomous_Impl --> Autonomous_Review : "Impl finished"
        Autonomous_Review --> Architect_Review_Gate : "Checks pass or 3-iter cap"

        state Architect_Review_Gate {
            [*] --> Menu
            Menu --> Option_A_Design : "Option A (Design Gaps)"
            Menu --> Option_B_Fix : "Option B (Delegate Code)"
            Menu --> Option_C_Manual : "Option C (Manual Edits)"
            Menu --> Option_D_Done : "Option D (Lock In)"
        }

        Option_B_Fix --> Autonomous_Review : "Run /rfc-impl-review pass"

        Option_C_Manual --> Manual_Review_Gate : "Edits complete"
        state Manual_Review_Gate {
            [*] --> Ask_Review
            Ask_Review --> Autonomous_Review : "Yes"
            Ask_Review --> Menu : "No"
        }
    }

    Option_A_Design --> Design_Drafting : "Loop back (checkpoint, edit RFC)"
    Option_D_Done --> Phase3_Sync : "Ready for Specs"

    state Phase3_Sync {
        [*] --> Spec_Synchronization : "Autonomous spec-sync-loop (bg agent)"
        Spec_Synchronization --> Invariants_Gate : "SPEC.md synced"
        Invariants_Gate --> Commit_Generation : "INVARIANTS.md approved inline"
        Commit_Generation --> [*] : "Architect commits code"
    }
```

## The Pipeline Lifecycle

Guide the Architect through the following phases sequentially. **Before Phase 1,
run Phase 0** (create the `pipeline/rfc-<n>` branch and record the base SHA) per
**Change Tracking, Checkpoints & Agent Write-Scope** above. Throughout, take a
checkpoint at every gate boundary, number every loopable step, verify each
agent's write-scope on return, and independently re-run tests before reporting
any result as green.

### Phase 1: Design

1. **Interactive Design (Separate Conversation)**: To protect this orchestrator
   conversation from context bloat, do NOT execute the interactive design phase
   here. Instead:

   - From the Architect's `<brief-description>`, derive `<rfc-name>` (a short
     Sentence-case title, e.g. `Email verification`) and record it in
     orchestrator state alongside `<n>`; it names every session in this run per
     **Session Naming** above.
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
   - Pause and wait for the Architect's input.

2. **Autonomous Review Loop**: Do NOT ask the Architect to copy-paste prompts.
   Instead, spawn a background agent with the **`Agent`** tool:

   - **subagent_type**: `general-purpose`
   - **description**: `[rfc-review-loop] rfc/<n> <rfc-name>`
   - **run_in_background**: `true`
   - **prompt**:
     `"Invoke the /rfc-review-loop skill on target RFC
        rfc/<rfc-file>.md with an iteration cap of 3 — it exits early once a
        review pass returns a clean PASS verdict (zero Critical, zero Major), so
        it may run fewer than 3 passes. The codebase root is <codebase-root>.
        Return a final summary report of every change made to the RFC."`

   Checkpoint before spawning (`pipeline(rfc-<n>): before review-loop [i]`). The
   spawn prompt MUST state the **write-scope** (`rfc/<rfc-file>.md` only) and
   the subagent rules (never commit, never stash). Print the transparency line
   first, then spawn. Pause; when the harness notifies you the agent has
   completed, **verify its write-scope** (`git status --porcelain` ⊆ the RFC;
   any code/test write ⇒ reset and escalate), checkpoint the result
   (`pipeline(rfc-<n>): after review-loop [i]`), then present its final summary
   report to the Architect.

3. **Architect Review**:

   - To prevent automatic progression when reviews modify the RFC, the Master
     Orchestrator MUST NOT proceed directly to Phase 2.
   - **Verifying Diffs**: Diff the two review-loop checkpoints to capture
     exactly what the loop changed:
     `git diff "pipeline(rfc-<n>): before
        review-loop [i]" "pipeline(rfc-<n>): after review-loop [i]" --
        rfc/<rfc-file>.md`
     (resolve the messages to SHAs as needed). Because the RFC is committed in
     the checkpoint, a plain `git diff` against the working tree alone would
     miss it — diff the checkpoints.
   - **Walk-through**: Present a clear, structured line-by-line markdown diff of
     all additions and deletions made to the RFC to the Architect.
   - **Decisive Action**: Explicitly ask the Architect: _"Are you satisfied with
     these RFC design updates? Please reply 'yes' or 'approve' to authorize
     Phase 2: Implementation. If you would like to make further modifications to
     the design first, please specify them and I will give you a prompt to pass
     them to /rfc-design in a new conversation."_
   - **Loop Back**: If the Architect specifies changes or is unsatisfied, guide
     them to refine the RFC text and then loop back to **Step 2 (Autonomous
     Review Loop)** to re-verify the design. Do NOT spawn implementation until
     explicit approval is gained in this step.

### Phase 2: Implementation

1. **Autonomous Implementation**: Do NOT ask the Architect to copy-paste
   prompts. Spawn a background agent with the **`Agent`** tool:

   - **subagent_type**: `general-purpose`
   - **description**: `[rfc-impl] rfc/<n> <rfc-name>`
   - **run_in_background**: `true`
   - **prompt**:
     `"Invoke the /rfc-impl skill on RFC rfc/<rfc-file>.md to
        execute the implementation plan. The codebase root is <codebase-root>.
        Your run-scratch sub-path is <run-scratch>/phase2/impl/. If you spawn any
        nested agents, list them (name + task) in your final report."`

   Checkpoint before spawning (`pipeline(rfc-<n>): before impl`); the spawn
   prompt MUST state the **write-scope** (code, tests, config — but **no
   `*/SPEC.md` and no `*/INVARIANTS.md`**), the `<run-scratch>` sub-path, and
   the subagent rules (never commit, never stash). Print the transparency line
   first, then spawn. Pause and wait. **On return _or stall/kill_, verify
   write-scope** (`git status --porcelain` contains no `*/SPEC.md` and no
   `*/INVARIANTS.md`) and **independently re-run the test suite** (do not trust
   the agent's green claim, and a stalled agent may have left a broken tree). If
   green, checkpoint (`pipeline(rfc-<n>): impl`); if broken, reset to
   `before impl` and re-spawn against the same scratch path (it resumes where it
   left off).

2. **Autonomous Implementation Review — one iteration at a time.** Per
   `iterative-work`, the master owns the iteration boundary: do **not** hand the
   whole multi-iteration loop to one agent and wait blind. Drive the iterations
   yourself, checkpointing each pass. For `i = 1` to 3 (stop early once a pass
   is clean):

   Per the **Depth-1 Fan-out Invariant**, the pipeline **never backgrounds a
   whole `/rfc-impl-review`** — that would push the review's leaf fan-out a hop
   below the orchestrator, making the leaves grandchildren the harness reaps
   unreliably. Instead each iteration is a **three-part sequence**: a delegated
   no-fan-out Stage A prep agent, an orchestrator-owned **inline** leaf fan-out,
   and a light scratch aggregation.

   a. **Prep (delegated, depth-1, no fan-out — Stage A).** Checkpoint
   (`pipeline(rfc-<n>): before impl-review [i]`), then spawn a background
   `general-purpose` agent **`[rfc-impl-review-prep] rfc/<n> <rfc-name>`**
   running **`/rfc-impl-review` Stage A only** (Phases 1, 2, 2b scope/test
   checks **plus** building the shared review-context file) on
   `rfc/<rfc-file>.md`. The spawn prompt MUST state `<codebase-root>`, the
   scratch sub-path `<run-scratch>/phase2/impl-review/iter-<i>/`, that it
   **spawns no subagents**, the **write-scope** (writes nothing tracked; durable
   output to scratch only), and the subagent rules. It writes its scope/test
   findings to `…/prep.json` and the shared review context to
   `…/review-context.md` (write-once, skip-if-present) and returns a compact
   summary. On return _or stall/kill_, verify write-scope
   (`git status --porcelain` empty); on stall, re-spawn against the same
   sub-path — `review-context.md` and `prep.json` are skip-if-present, so it
   resumes. **The fan-out (b) does not start until `…/review-context.md`
   exists.**

   b. **Fan-out (orchestrator-owned, leaves depth-1 — Stage B).** **This
   orchestrator session itself** invokes `design-review-chain` then
   `code-review-chain` **inline** (via the `Skill` tool — NOT a background
   agent), so each leaf is a **depth-1** child of this session and reaps
   reliably. Pass each chain: **Target** = the Phase-1 changed-file set, **Base
   Revision** = `<base>`, **Scratch Dir** =
   `<run-scratch>/phase2/impl-review/iter-<i>/design/` and `…/code/`, and
   **Review Context File** =
   `<run-scratch>/phase2/impl-review/iter-<i>/review-context.md` (the prep
   agent's file) so the chains skip rebuilding the context and point their
   leaves at it. Each leaf `Read`s that file and writes **one verdict file per
   leaf** under its chain's `…/leaves/` the instant it finishes. The
   orchestrator drains the bounded (≤10) queues holding only **lens names and
   scratch paths** in context. **Let the fan-out run to completion; do not kill
   it mid-flight.** On any partial stall, recover only the missing lenses from
   the completed `…/leaves/` files (write-once); never re-run the whole fan-out.

   c. **Aggregate (orchestrator, light).** Reconstruct the master verdict from
   scratch — the prep agent's `…/prep.json` scope/test findings plus the
   per-leaf verdict files under `…/design/leaves/` and `…/code/leaves/` (read
   each chain's `report.md` if present, else merge the per-leaf files directly).
   Confirm the `Test Verification Completeness Check` is present and passing
   **and independently re-run the suite yourself**. Checkpoint
   (`pipeline(rfc-<n>): impl-review [i]`).

   d. **If clean** (no actionable findings, tests green): exit the loop.
   **Otherwise** spawn a background **`/rfc-impl-fix`** pass with scratch
   sub-path `<run-scratch>/phase2/impl-fix/iter-<i>/`, handing it the
   reconstructed findings; it records a per-finding ledger
   (`applied | skipped | failed`) write-once, so a stalled fix resumes at the
   first unapplied finding. On return _or stall/kill_: verify write-scope (no
   `*/SPEC.md`/`*/INVARIANTS.md`) and **independently re-run the suite** — if
   broken, reset to `pipeline(rfc-<n>): impl-review [i]` and re-spawn the fix
   (it resumes from its ledger); if green, checkpoint
   (`pipeline(rfc-<n>): impl-fix [i]`).

   Because every pass is checkpointed and every leaf and finding is captured
   under `<run-scratch>`, a stall anywhere forfeits at most the single in-flight
   unit — never a whole iteration. After the loop, present the final findings,
   changes, and uncommitted diff to the Architect. If the completeness check is
   missing/FAILED or your own run does not pass, halt and request revisions.

3. **Architect Implementation Review**: Once the autonomous reviews and fixes
   pass (or if the Architect requests intermediate iterations), present the
   final/current implementation state to the Architect.

   - **Immediate Checkpoint**: Take an `architect-review [i]` checkpoint at the
     start of this step every time it is entered
     (`git add -A && git
        commit -m "pipeline(rfc-<n>): architect-review [i]"`).
     This is the clean reference point preserved in case the Architect chooses
     Option A.
   - Generate a persistent artifact `.scratch/implementation_diff.md` from
     `git diff <base-sha> HEAD`. This artifact MUST contain a structured
     walkthrough AND the **actual code diff blocks** (in standard `git diff`
     format) showing every line that was added, modified, or deleted.
   - Clearly print and describe the three options below to the Architect in your
     response, helping them choose how to iterate:

   #### Option A: Refine the RFC Design (If design gaps/missing instructions are found)

   If the Architect determines that the design itself was incomplete or needs to
   change:

   1. Instruct the Architect to open a **new conversation** to refine the
      design, explaining that this is necessary _"to keep my context window
      clean so I can stay focused on my job."_ As the **very first thing in
      it**, they run `/rename [rfc-design] rfc/<n> <rfc-name>` (the design
      session cannot rename itself); reuse this run's existing `<n>` and
      `<rfc-name>`.
   2. Provide this exact copy-pasteable block — the rename first, then the
      design prompt as the next message:

      ```
      /rename [rfc-design] rfc/<n> <rfc-name>
      ```

      ```
      Run /rfc-design to refine the design of the existing RFC: rfc/<rfc-file>.md. Discuss the following updates: <Architect-inputs>
      ```
   3. Pause and wait for them to return once the RFC has been updated.
   4. Once they return:
      - **Verify RFC Diffs**: Diff the working-tree RFC against the
        `architect-review [i]` checkpoint taken at the start of this step
        (`git diff "pipeline(rfc-<n>): architect-review [i]" --
            rfc/<rfc-file>.md`)
        to capture exactly what design changes were made. Present this diff to
        the Architect for verification; the checkpoint is the reference.
      - **Surgical Patch Re-run**: Checkpoint the Architect's RFC edit
        (`pipeline(rfc-<n>): rfc-refine [i]`), then spawn a background agent
        running `/rfc-impl-fix` to apply only the RFC design changes
        incrementally to the existing implementation. The spawn prompt MUST
        state the **write-scope** (code, tests, config — **no `*/SPEC.md` and no
        `*/INVARIANTS.md`**), the `<run-scratch>` sub-path, and the subagent
        rules. You MUST pass the captured RFC design diff as the action items to
        implement:
        - **subagent_type**: `general-purpose`
        - **description**: `[rfc-impl-fix] rfc/<n> <rfc-name>`
        - **run_in_background**: `true`
        - **prompt**:
          `"Invoke the /rfc-impl-fix skill on target RFC
                rfc/<rfc-file>.md. Your run-scratch sub-path is
                <run-scratch>/phase2/impl-fix/refine-[i]/ — record a per-finding
                ledger there (write-once) and skip any already applied on
                re-entry. Treat the following captured RFC design diff as the
                targeted action items to implement in the existing codebase:
                <RFC-diff-text>"`

   #### Option B: Delegate Code Corrections (If implementation needs fixing but RFC is correct)

   If the Architect identifies implementation mistakes, bugs, or wants different
   coding approaches within the RFC's boundaries:

   1. Prompt the Architect to list out their desired changes, feedback, or
      requirements.
   2. Once the feedback is provided, spawn a background agent running
      `/rfc-impl-fix` to apply the corrections directly:
      - **subagent_type**: `general-purpose`
      - **description**: `[rfc-impl-fix] rfc/<n> <rfc-name>`
      - **run_in_background**: `true`
      - **prompt**:
        `"Invoke the /rfc-impl-fix skill on target RFC
            rfc/<rfc-file>.md. Your run-scratch sub-path is
            <run-scratch>/phase2/impl-fix/delegate-[i]/ — record a per-finding
            ledger there (write-once) and skip any already applied on re-entry.
            Treat the following Architect feedback as the review report/action
            items to implement: <Architect-feedback-text>"`
   3. Wait for the fix agent to complete.
   4. Once complete, run a verification review pass using the **same three-part
      sequence as Phase 2 step 2** (prep + inline fan-out + aggregate) — never a
      single backgrounded `/rfc-impl-review`, per the **Depth-1 Fan-out
      Invariant**. Use scratch sub-path
      `<run-scratch>/phase2/impl-review/delegate-[i]/`:
      - **Prep (delegated, no fan-out).** Spawn a background `general-purpose`
        agent **`[rfc-impl-review-prep] rfc/<n> <rfc-name>`** running
        `/rfc-impl-review` **Stage A only** (scope/test checks + build
        `…/review-context.md`). Write-scope: nothing tracked.
      - **Fan-out (inline, leaves depth-1).** This orchestrator session invokes
        `design-review-chain` then `code-review-chain` **inline** against the
        prep-built `…/review-context.md`, with Scratch Dirs `…/design/` and
        `…/code/`. Each leaf `Read`s the file and writes one verdict file per
        leaf under its `…/leaves/`.
      - **Aggregate.** Reconstruct the verdict from `…/prep.json` plus the
        per-leaf files, and independently re-run the suite.
   5. Present the updated code diffs and review results to the Architect,
      repeating this iteration loop.

   #### Option C: Manual Changes by the Architect

   If the Architect makes manual edits/changes directly in their workspace:

   1. Once they are done, explicitly ask the Architect: _"Would you like to run
      an automated /rfc-impl-review on your manual changes?"_
   2. If they say yes:
      - Run a review pass using the **same three-part sequence as Phase 2 step
        2** (prep + inline fan-out + aggregate) — never a single backgrounded
        `/rfc-impl-review`, per the **Depth-1 Fan-out Invariant**. Spawn the
        delegated `[rfc-impl-review-prep]` agent for **Stage A only**
        (scope/test checks + build `…/review-context.md`), then this
        orchestrator session invokes `design-review-chain` and
        `code-review-chain` **inline** against that context file (leaves
        depth-1), and aggregate the verdict from scratch.
      - Present the findings to the Architect and ask: _"Would you like the
        agent to automatically fix these findings via /rfc-impl-fix?"_
      - If they say yes, loop back to **Option B, Step 2** to apply the fixes.
   3. If they say no, ask if they are ready to proceed to Phase 3.

   Repeat this loop until the Architect explicitly states they are done and
   satisfied (e.g., "done", "looks good", "ready for specs").

### Phase 3: Specs, Invariants & Commit

Phase 3 maintains two sibling per-directory documents with strictly separated
mandates:

- **`SPEC.md`** — _descriptive_: what the code does, so an LLM gets context
  without reading every file. **Fully LLM-managed, no human gate.** Synced
  autonomously by a background agent (3a).
- **`INVARIANTS.md`** — _prescriptive_: the few durable guarantees that must
  remain true as the code evolves, each with its WHY. **Human-gated**, but the
  review is minimal because invariants are few. Drafted by a background agent,
  approved **inline in this orchestrator conversation** (3b).

First, determine the set of directories the implementation touched:
`git -C "<codebase-root>" diff --name-only <base-sha> HEAD`, then reduce to the
distinct source directories (exclude `rfc/` and the RFC file itself).

#### 3a. SPEC.md Synchronization (autonomous)

Do NOT ask the Architect to copy-paste prompts and do NOT use `/spec-editor`.
SPEC.md is LLM-managed. Spawn a background agent with the **`Agent`** tool:

- **subagent_type**: `general-purpose`
- **model**: `sonnet` — SPEC sync is descriptive synthesis, not adversarial
  correctness reasoning, so it runs on the mid tier (like the review-chain
  leaves) rather than the session model. A deliberate cost/latency pin; see
  **Review-agent model policy**.
- **description**: `[spec-sync-loop] rfc/<n> <rfc-name>`
- **run_in_background**: `true`
- **prompt**: instruct it to run `/spec-sync-loop` (3 iterations) on **each**
  touched directory in `<codebase-root>` (creating a new `SPEC.md` via
  `/spec-writer` where one is absent), and to **report any durable guarantee it
  notices that belongs in `INVARIANTS.md`** (it must NOT write `INVARIANTS.md`
  itself). Give it the scratch sub-path `<run-scratch>/phase3/spec-sync/` and
  tell it to record a per-directory completion artifact there
  (`<dir-slug>.json`) the instant each directory's sync finishes, and to **skip
  any directory whose artifact already exists** (so a re-spawn resumes at the
  first unsynced directory). The prompt MUST state the **write-scope**
  (`*/SPEC.md` files only — **no `*/INVARIANTS.md`**, no code/test/config) and
  the subagent rules (never commit, never stash).

Checkpoint before spawning (`pipeline(rfc-<n>): before spec-sync`). Print the
transparency line, then spawn. On return **_or stall/kill_, verify write-scope**
(`git status --porcelain` ⊆ `SPEC.md` files; any code/test/`INVARIANTS.md` write
⇒ reset and escalate). If it stalled, re-spawn against the same scratch sub-path
— directories already synced are skipped. Then checkpoint
(`pipeline(rfc-<n>): spec-sync`).

#### 3b. INVARIANTS.md (autonomous draft, minimal inline human gate)

Spawn a background agent with the **`Agent`** tool:

- **subagent_type**: `general-purpose`
- **model**: `sonnet` — invariants drafting is filtered distillation feeding a
  human gate, not correctness reasoning, so the mid tier suffices; the Architect
  still reviews every proposed invariant inline. See **Review-agent model
  policy**.
- **description**: `[invariants-writer] rfc/<n> <rfc-name>`
- **run_in_background**: `true`
- **prompt**: instruct it to run `/invariants-writer` on **each** touched
  directory in `<codebase-root>`, distilling the few true invariants from each
  directory's `SPEC.md` + code + in-scope RFCs through the five-gate filter, and
  to **return the proposed `INVARIANTS.md` drafts plus the filter rationale**
  (what it kept and, briefly, what it filtered out and why). Give it the scratch
  sub-path `<run-scratch>/phase3/invariants/` and tell it to write each
  directory's proposed draft + rationale there (`<dir-slug>.json`, recording
  "zero invariants" explicitly where that is the outcome) the instant that
  directory is assessed, and to **skip any directory whose artifact already
  exists**. It MUST NOT write any `INVARIANTS.md` file to the tree —
  `INVARIANTS.md` is human-gated; the orchestrator owns the write; scratch is
  not the tree. Many directories will legitimately yield **zero** invariants and
  therefore **no file** — that is expected. The prompt MUST state the
  **write-scope** (writes nothing tracked; reports + scratch only) and the
  subagent rules.

Checkpoint before spawning (`pipeline(rfc-<n>): before invariants`). On return
**_or stall/kill_, verify write-scope** (`git status --porcelain` empty), and
reconstruct the proposed drafts by reading `<run-scratch>/phase3/invariants/` if
the agent died mid-run (re-spawn to finish any unassessed directories — done
ones are skipped). Then run the **inline human gate**: present each proposed
`INVARIANTS.md` (they are short — a handful of lines each) to the Architect in
this conversation, note which directories yielded no invariants, and ask for
approval or edits. Apply the Architect-approved invariants by writing the
`INVARIANTS.md` files yourself, then checkpoint
(`pipeline(rfc-<n>): invariants`).

- **CRITICAL:** Do NOT print the final commit messages during this step.

#### 3c. Commit Message Generation & Code Approval

Once the SPEC.md sync is done and the Architect has approved the `INVARIANTS.md`
files:

- **Squash the pipeline checkpoints**: collapse all WIP checkpoints into a clean
  staging state with `git reset --soft <base-sha>` (the recorded base SHA;
  working tree and index preserved, only the WIP history dropped). The two final
  commits are created from this state.
- **Final independent test run**: before generating commit messages, re-run the
  suite yourself (project test harness) and confirm it is green — do not rely on
  any earlier agent report.
- Ingest the final workspace diff (`git diff <base-sha>` / `git status`).
- Generate and provide two formatted commit messages for the Architect to
  copy-paste (following the repository's commit guidelines): one for the
  new/updated RFC markdown document, and one for the actual code implementation
  (including the synchronized `SPEC.md` and the approved `INVARIANTS.md` files).
- Instruct them to verify everything locally and manually commit the changes
  once they approve. Wait for their confirmation that the code is committed.

3. **Land the branch & tear down the worktree**: Once the Architect confirms the
   commits exist on `pipeline/rfc-<n>` (inside the worktree), help them land the
   branch on the default branch and remove the now-redundant worktree. Run these
   from the original checkout (not from inside the worktree):

   ```sh
   git -C "<original-checkout>" merge --ff-only pipeline/rfc-<n>   # or open a PR from the branch
   git worktree remove ../unicoach-rfc-<n>                         # delete the worktree
   git branch -d pipeline/rfc-<n>                                  # delete the merged branch
   ```

   `git worktree remove` refuses if the worktree still has uncommitted changes —
   treat that as a safety check, not an obstacle to force past. After removal
   the run is fully wound down and another pipeline may take its place.
