---
name: manual-review-fix
description: >-
  Human-in-the-loop review and repair of an implementation, built to evaluate the
  reviewer skills themselves. Fans out every reviewer skill against one fixed
  commit, presents findings to the operator one at a time in tier order, builds
  each accepted fix in its own worktree off that same commit, and integrates them
  at the end. A rejected finding is evidence against its reviewer and routes to
  /skill-update. Use when a user asks to manually review and fix an
  implementation, or invokes /manual-review-fix.
---

# Manual Review / Fix

An orchestrator. It moves messages between four roles and makes no judgements of
its own.

| Role             | Who                                                        |
| ---------------- | ---------------------------------------------------------- |
| **operator**     | the human; the only party that decides anything            |
| **reviewer**     | a skill that inspects the target and returns findings      |
| **fixer**        | a skill or prompt that applies one finding                 |
| **orchestrator** | this skill; dispatches, presents, isolates, and integrates |

**Purpose.** This process exists to **evaluate the reviewer skills**. Repairing
the code is the by-product. Every design choice below — one fixed commit, one
finding at a time, one worktree per fix, verbatim hand-off — is there so that a
bad outcome is attributable to exactly one reviewer.

## Invocation Parameters

- **Target**: the RFC under review (e.g. `rfc/107-e2e-tests.md`). Tier 0 needs
  it; the code lenses do not.
- **Evaluated Commit** (`E`): the SHA whose tree is reviewed and off which every
  fix is built. Defaults to `HEAD`.
- **Base Revision**: what the change is measured against. Defaults to `main`.
- **Fixer**: the skill or prompt that applies an accepted finding. Defaults to
  `/rfc-impl-fix`.
- **Scratch Dir** _(optional)_: defaults to `.scratch/manual-review-fix/<E>/`.

If Target or `E` cannot be resolved, stop and ask.

## Preconditions

`E` must be a real commit — every fix branches from it, so it cannot be a dirty
working tree. If the tree has uncommitted changes, stop and ask the operator to
commit them; that commit becomes `E`. Never commit or stash their work yourself.

## Prime directives

**Never filter.** Present every finding every reviewer returned, in the
reviewer's own words. Do not rank, merge, summarise, drop, or judge relevance.
Deciding what matters is the operator's job and the only reason this skill
exists. A finding the orchestrator quietly discards is a reviewer defect nobody
can see — and this run's entire output is the record of which reviewers produce
defects.

**Never loop unasked.** Every iteration is entered because the operator chose to
enter it. There is no pass/fail gate that re-runs anything on its own.

**Never let a lens vanish.** Every discovered skill ends the run with a verdict
file — real or `NOT RUN` — and every `NOT RUN` is reported as such. An omitted
lens reads as "found nothing" when it in fact never ran.

## The finding contract

Each finding MUST carry:

- a **detailed description** — what is wrong, where, and why it matters;
- **at least two options**, each containing the **actual code** to apply, not a
  description of it, presented in **descending preference order**;
- **Option 1 is the recommendation**, labelled `(RECOMMENDED)` and carrying the
  reason it beats the rest;
- the **subject** — the exact lines the reviewer was looking at.

**The ordering is the recommendation.** Every reviewer must rank, not just list.
A separate "Recommendation: B" line is not acceptable — it can name an option
that does not exist, and it lets a reviewer dodge the ranking for options 2..n,
which is exactly the judgement the operator is reading the finding for. Option 1
is what the fixer applies by default, so a reviewer that will not commit to a
first choice has not finished its job.

The subject is not optional either. Without it, at `/skill-update` time a rule
that is simply wrong cannot be told apart from a rule misapplied to one case,
and those want opposite edits.

A finding with fewer than two options, with options that are not ranked, or with
no subject is incomplete: present it marked `⚠ incomplete` rather than dropping
or repairing it. **Never supply the missing ranking yourself** — an
orchestrator-invented recommendation is indistinguishable from the reviewer's
own at triage time, and it would silently launder a reviewer defect into a clean
finding. Incompleteness is a first-class result worth recording.

## Depth-1 Fan-out Invariant (normative)

This skill spawns one leaf per reviewer, so it **MUST run inline in the
top-level session** — invoked with the `Skill` tool from the conversation
itself, never from inside a background subagent (an `Agent`-tool task). One
`Agent` hop above the fan-out makes every leaf a **grandchild**, which the
Claude Code harness reaps unreliably: a finished leaf can sit at `running`
indefinitely.

It is also interactive by construction — the operator decides every outcome — so
there is nothing to gain by backgrounding it and a stalled fan-out to lose.

## Phase 1 — Fan-out

**One fan-out per tier, and only when that tier's turn arrives.** Fan out Tier
0, triage it to completion (Phase 2), then fan out Tier 1, and so on. Never
spawn a later tier's leaves before the current tier's triage is done.

Every tier is still reviewed against **`E`**, unchanged — accepted fixes live on
their own branches and integrate only in Phase 3, so nothing has landed and the
input is identical whenever a leaf runs. Spawning late costs no comparability.

What it buys: a Tier 0 finding can send the operator back to `/rfc-design`, and
the resulting implementation change invalidates every code-lens verdict taken
against the old tree. Fanning out all 39 up front spends 34 leaf reviews on a
tree that is about to be replaced. Tier 0 gates the rest for the same reason it
is triaged first — it asks whether this is even the right implementation to be
reviewing.

1. **Build the shared review context, once**, to `<scratch>/review-context.md`:
   the `<base>...E` diff, plus the full contents of every changed **non-test**
   file inlined whole and labelled by path. Name changed test files and any
   oversized file in a "named — `Read` on demand" list instead of inlining them.
   Write-once, skip-if-present.

   This file is the **evidence**, not an optimisation. It is what lets a silent
   lens be told apart from a lens that never looked, and it is the subject you
   hand to `/skill-update` later. Every leaf reads it.

2. **Resolve the skill set.** Discover live skills by glob — `impl-review-*`,
   `design-review-*`, `code-review-*`, excluding `*-chain` — and check each
   against [`tiers.md`](tiers.md). A discovered skill missing from the manifest
   **halts the run**; report it and ask the operator which tier it belongs in.
   Do this **once**, up front, so a manifest gap fails before any leaf is spent.
   Report the resolved set and its per-tier counts.

3. **Spawn one leaf per skill in the current tier only** — never a later tier —
   at most 10 in flight, refilling as each finishes. Name the tier and its skill
   count in chat before spawning, so an over-wide fan-out is visible immediately
   rather than after 39 agents are running.
   - **subagent_type**: `code-reviewer` (or `design-reviewer` for the design
     lenses) — read-only, model-pinned. Tier 0 skills also use `code-reviewer`;
     they read the RFC and the tree and write nothing.
   - **description**: `[manual-review-fix] <skill>`
   - **run_in_background**: `true`
   - **prompt**: names the one skill to invoke, `<scratch>/review-context.md` to
     read, the Target RFC (Tier 0 only), the finding contract above, and the
     write path `<scratch>/findings/<skill>.md`. State that it writes that one
     file, writes it **before** replying in chat, and changes nothing else.

4. **Drain to completion. Do not kill the fan-out mid-flight.** A leaf that
   exceeds a generous budget gets one replacement; if that also produces no
   file, write its file yourself with `NOT RUN` and the reason. Never retry
   further and never re-run the whole fan-out — completed leaf files are
   write-once and are kept.

5. **Assert completeness**, then assign every finding a stable id
   `<skill-shortname>.<n>`.

If a leaf stalls, **record what happened** — which skill, how long, whether its
file appeared — and tell the operator. Do not silently retry or restructure
around it; a stall is evidence about the harness and suppressing it destroys the
only signal.

## Phase 2 — Triage

Walk the tiers in manifest order: **0, then 1, 2, 3**. Phases 1 and 2 interleave
— fan out a tier, triage it to completion, then return to Phase 1 for the next
tier. Within a tier, order is yours. Present findings **one at a time**, never a
batch, never a summary table of several.

If Tier 0 triage sends the operator back to `/rfc-design`, **stop here**. Do not
fan out Tier 1 against an implementation that is about to change.

Before presenting a finding, check whether any already-accepted fix branch
touched its subject's file and line range. If so, mark it
`⚠ subject modified by <finding-id>` and show what changed. Do not drop it — a
stale finding the operator can see is data; one you suppressed is a reviewer
scored down for something it never did.

### Presenting a finding

Three blocks and nothing else. Tier 0:

```
**<id>** <one-line title>

- **RFC says** — <verbatim excerpt>
- **Code does** — `<file>:<line>`, the offending lines, one sentence on what is
  wrong
- **Options** — ranked; 1 is **(RECOMMENDED)** with a one-line reason
```

Tiers 1–3 drop **RFC says**; **Code does** carries the subject.

No preamble, no restating the rule the lens enforces, no summary of what the
reviewer was checking, no assessment of whether it is a good catch. The
reviewer's words and the operator's decision, with nothing in between.

The per-skill ledgers (step, declaration, traceability, test) stay in the
findings file. They are coverage accounting, not findings — surface a ledger
only when it has a row that is not a pass, since only then does it change
anything.

### Orchestrator commentary

**Default silent.** This is not "be brief" — it is a gate. Speak only when what
you would say changes the decision or its scope. Four triggers:

- **The recommended option will not build, or is wrong.** Say what breaks.
- **Two findings conflict.** Name both ids and the lines they contend for.
- **The pattern recurs outside the subject.** Grep, and report the count and
  where. Fixing one site and fixing all of them are different decisions, and the
  operator cannot make the second one without the number.
- **Something egregious sits in the diff but outside every lens's scope.**
  Report it once, then drop it.

Everything else — context, rationale, agreement, restating the finding in your
own words, noting that you checked something — is noise, and it dilutes the four
signals above until they stop being read. Stay silent.

The operator chooses one of:

### Accept

1. `git worktree add ../<repo>-fix-<id> -b fix/<id> <E>`.
2. Spawn the **Fixer** there, in a fresh context, one finding at a time — never
   a batch. Pass the finding **verbatim**: description, all options, the
   recommendation, the subject. Do not paraphrase, re-order, or "clarify" it.
   Handing the fixer your restatement rather than the reviewer's own words
   destroys the attribution this run exists to produce.

   The prompt MUST state that the fixer:
   - works only in its own worktree, and **must not commit**;
   - **owns verification** — after applying, it runs the tests covering what it
     changed (`nix develop -c bin/test <module> -f`) and reports the real
     executed counts. The per-worktree test DB and free-port claiming make
     concurrent runs safe. If there is nothing to run, it says so explicitly
     rather than staying silent.

3. Show the operator the **diff and the test result together** — `git diff <E>`
   in that worktree plus any untracked files, in full, followed by the fixer's
   verbatim test report. Never the diff alone. If the fixer reported no
   verification, say so plainly: an unverified fix may still be accepted, but
   not by accident.

4. The operator **keeps or discards** the fix.
   - _keep_: commit it in the worktree —
     `nix develop -c git commit --no-verify -am "<id>: <title>"` with trailers
     naming the reviewer, the option applied, and what was verified. The branch
     waits for Phase 3. (Intermediate commits on a work branch skip the hook per
     CLAUDE.md; the branch tip is gated in Phase 3.)
   - _discard_: `git worktree remove --force` and delete the branch. Nothing to
     roll back — the fix never touched the main tree, which is the point of
     building off `E`.
   - On discard, offer a **retry**: the operator may re-run the same finding
     with extra notes, passed verbatim to a fresh fixer context alongside the
     original finding.

Record the outcome distinctly: `kept`, `discarded-fix-fault` (the finding was
sound, the fix was not), or `discarded-finding-fault` (implementing it revealed
the finding was wrong). These are different evidence and must not be collapsed.

### Reject

The finding is wrong. Nothing is built.

This is the primary output of the run, so spend it immediately, while the
operator's reasoning is fresh. Print this block for them to paste into a **new
conversation** — do not run `/skill-update` inline; it is a full interactive
editing session and would swamp the triage queue:

```
Invoke /skill-update on skill <reviewer skill>.

Verbatim finding:
<the finding, in full — description, options, recommendation>

Subject (the code the finding was raised against):
<the exact lines, quoted>

Why it was rejected:
<the operator's reason, verbatim>
```

Then **continue triaging**. Do not wait for them to finish; the queue keeps
moving and they return when they return.

### Revise the RFC — Tier 0 only

A Tier 0 finding often means the RFC is wrong, not the code: a "missing feature"
may be a spec the implementation correctly declined to invent. When the operator
says so, hand them the `/rfc-design` prompt for a new conversation, and mark the
finding `rfc-revision`. The RFC change re-enters through the normal pipeline,
not through this loop.

Record every outcome as a line in `<scratch>/ledger.jsonl`:
`{"id","skill","tier","outcome","notes"}`. This is the only durable record of
what the operator decided, and the evaluation dataset this run produced.

## Phase 3 — Integration

Once triage is done:

1. Branch `integration` at `E`.
2. **Cherry-pick each kept fix in acceptance order** — which is tier order, so
   structural fixes land before the nits that rebase onto them. A 3-line rename
   conflicting with an extraction that already landed is trivial; the reverse is
   not.
3. **A conflict is a finding, not a chore.** Two accepted fixes contending for
   the same lines means two reviewers disagree about that code. Present both
   hunks to the operator, let them resolve it, and record it in the ledger as a
   conflict between the two skills. That is a result this run was built to
   produce.
4. **Gate the tip**: `nix develop -c bin/format -c` and
   `nix develop -c bin/test check`. Report the real counts.

   Per-branch greens do not imply a green integration — two fixes can merge
   cleanly and still be jointly wrong (one extracts a helper, another edits code
   that no longer exists there). This run is what catches that.

5. Remove the fix worktrees and delete the merged branches.

## Phase 4 — Confirmation

Re-run the **Tier 0** skills once against the integration tip. Five read-only
skills; it catches a fix that did not actually satisfy the check it was raised
against.

If something re-fires, return to Phase 2 triage for those findings. The operator
decides whether to act. **This is a check, not a loop** — it does not re-run
itself, and it never re-runs Tiers 1–3.

## What this skill never does

- Filter, rank, or summarise findings.
- Paraphrase a finding when handing it to a fixer.
- Advance an iteration the operator did not ask for.
- Apply more than one finding per fixer context.
- Build a fix anywhere but its own worktree off `E`.
- Report a verdict for a lens that did not run.
- Let the integration tip reach `main` without the full gate having run on it.
- Edit a reviewer skill itself — that is `/skill-update`, with the operator
  present.
