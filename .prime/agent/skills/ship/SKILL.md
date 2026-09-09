---
name: ship
description: >-
  Takes a short instruction from Ian and carries it to landed code in the
  unicoach repo: claim a worktree, design (RFC for design-bearing work), one
  human approval gate, delegated implementation, tiered micro-skill review, the
  pre-commit gate, and a fast-forward land. Use when Ian asks to ship, build,
  implement, fix, or change something in unicoach, or invokes /skill:ship. Runs
  in Prime Agent and is fully independent of the Claude Code rfc-pipeline —
  it shares a git naming convention and the review-rule corpus, no code.
---

# ship

One instruction in, landed change out. Two human touchpoints: the instruction,
and one approval.

## Non-negotiables

1. **Never modify `.claude/**`.** The Claude Code workflow stays as-is. This
   skill neither calls nor sources `.claude/skills/rfc-pipeline/scripts/*`.
2. **Two authorities can say no**: Ian at approval, and `bin/pre-commit` at
   commit. There is no CI. Never weaken either.
3. **The approval gate is not an optimisation target.** Nothing downstream
   catches "we built the wrong thing" — no test, no lens. Make approval _cheap_
   (a decision list with defaults), never absent.
4. **All toolchain commands run `nix develop -c`** (CLAUDE.md). iOS scripts are
   the exception: they run under SYSTEM Xcode and refuse inside the dev shell.

## Lane choice (propose, do not ask)

| Lane          | When                                                                           | Branch             |
| ------------- | ------------------------------------------------------------------------------ | ------------------ |
| **A — rfc**   | New behaviour, a schema change, a new surface, anything with a design decision | `pipeline/rfc-<n>` |
| **B — quick** | Mechanical: a new script, a rename, a bug fix with an obvious shape            | `ship/<slug>`      |

State the lane and one-line reason in the same message as the design or plan.
Ian vetoes if wrong. Do not open with a question.

## Phases

```
claim → design → APPROVE → implement → verify → land → report
```

    scripts/ship-claim   -l rfc|quick [-s slug]     # worktree + branch + state
    scripts/ship-state   -s <rs> get|set|show       # run facts, on disk
    scripts/ship-checkpoint -s <rs> <phase> [i]     # WIP commit + ledger
    scripts/ship-status  [-p]                       # find live runs, no args
    scripts/ship-recover -s <rs> [-c sha|-n back]   # reset to a checkpoint
    scripts/ship-rebase  -s <rs> [-N]               # catch up with a moved base
    scripts/ship-verify-scope -s <rs> -f <sha> [-d deny]... [allow]...
    scripts/ship-order   -s <rs> [-n]               # land-time db/schema/ORDER
    scripts/ship-squash  -s <rs>                    # collapse WIP to staged
    scripts/ship-verified record|check|assert       # the CI stand-in
    scripts/ship-lock    -s <rs> acquire|release|status  # the land gate
    scripts/ship-land    -s <rs>                    # assert, ff-merge, teardown

Run every script with `-h` for its contract. **Read the run state from disk
after any compaction — never from memory.**

### The run is on disk, not in this session

Your context is the least durable thing in the run: compaction is silent, and it
takes the coordinator rather than a worker. So every phase boundary writes two
things before the next phase starts.

**Advance `PHASE` on entering each phase**, so a resumed session re-enters where
the run actually is:

    scripts/ship-state -s <rs> set PHASE designing|approved|implementing|verifying|landing|complete

**Checkpoint at every phase boundary**, not only during implementation, and name
the checkpoint for the phase it closes:

    scripts/ship-checkpoint -s <rs> before-verify
    scripts/ship-checkpoint -s <rs> before-land

A run that lands with `PHASE=implementing` had a working outcome and a lying
state file; the next resumed run believes it. Treat a stale `PHASE` as a defect,
not untidiness.

**Resuming a run.** A compacted session cannot pass `-s <rs>` — the run scratch
was the first thing it forgot. So start from the one script that asks for
nothing:

    scripts/ship-status                    # live runs: id, phase, branch, paths
    scripts/ship-state -s <rs> show        # re-read the facts
    # then re-enter at PHASE

`ship-status` discovers runs from git's own worktree list, so it works from
anywhere in the repo and needs no arguments precisely because a compacted
session has none. `ship-status -p` prints bare `RUN_SCRATCH` paths to paste into
the other scripts. Re-enter at the `PHASE` on disk, not at the phase you
remember.

### Parallel runs: the base WILL move under you

Other runs land while yours is open. The base branch advancing is NORMAL, not an
error and not a race you lost. Being merely _behind_ is harmless: your run still
forks at `BASE_SHA`, so its diff is honestly its own work, and `ship-land`'s
fast-forward check will ask for the rebase.

What is not harmless is being **rebased without recording it**. Once the branch
moves but `BASE_SHA` does not, `ship-squash` resets to a point that is no longer
the fork and absorbs the other run's landed commits into your diff, and
`ship-verify-scope` reports their files as your writes — a false deny, which
this skill treats as grounds for a reset. Note the trap: after a rebase the
stale `BASE_SHA` is still an _ancestor_ of HEAD, so an ancestor check sleeps
through exactly this case. Both scripts therefore test the **fork point**
(`git merge-base HEAD <base>`) and refuse when it has moved.

`scripts/ship-rebase -s <rs>` is the only supported way to catch up. It is
idempotent and costs one `rev-parse` when nothing moved, so **rebase early and
often** — call it at the top of verify and again before `ship-squash`, and any
time a sibling run reports it landed. A conflict caught at verify costs a small
fix; the same conflict caught at land costs the whole hook run again.

`ship-status` shows the drift (`BASE main@455f74d — 2 behind, REBASE NEEDED`),
so a resumed session sees it before it does anything else.

Since RFC 167 a land may also **wait**. Phase 6 is bracketed by one repo-wide
lock (`scripts/ship-lock`), so while another run is inside its gate this run's
`acquire` blocks rather than racing it — that wait replaces a duplicate hook
run, it does not add work. `ship-status` prints the current holder and its
remaining time, and `ship-land` refuses outright when the holder is another run:
its hook result was validated against a base that run is about to move.

A genuine conflict is a **design signal**, not a tooling failure: two runs
disagree about the same code. Read it, decide which shape is right, and say so
in the report. Do not paper over it by taking one side mechanically. On conflict
`ship-rebase` leaves the rebase in progress and `BASE_SHA` unchanged — resolve
and `git rebase --continue`, then re-run it, or `git rebase --abort`.

`ship-rebase` **rotates the checkpoint ledger** (`checkpoints.log` becomes
`checkpoints.log.pre-rebase-<n>`, and a fresh ledger starts at the post-rebase
HEAD). Checkpoints predating a rebase are gone by design: they name abandoned
history, so resetting to one would put the worktree back on the old base with
the other run's landed work missing. `ship-recover` only ever targets
post-rebase checkpoints.

### Long commands run in a subagent; landing reports itself

Ian's clock starts when a phase begins and stops when he is TOLD it ended. Two
ways this skill has wasted it, and they are different failures:

**1. A detached job nobody wakes from.** `nohup … &` and then the turn ends.
Nothing wakes a session when a shell job exits, so the run stalls at the one
moment it has news, and the next thing that moves it is Ian asking for a status
he should have been handed.

**2. A turn that ends on a tool result.** The hook returned `[main abc1234]` and
the turn ended with no text. A tool result Ian cannot see is not a report. Half
that wait was the gate; the other half was a finished answer sitting unread.

**Default: give a long command to a child.** Anything over roughly a minute —
`bin/test`, the hook, `installDist`, a concurrency demonstration — goes to a
subagent that runs it and replies. Four reasons, and the second is the important
one:

- The wake-up is free. A message resumes the session; a shell job cannot.
- **Ian can watch it without asking.** A child's session is in the harness, live,
  under the same view as everything else. A `nohup` log is a path he would have
  to know and `tail` himself. This is the difference between a wait he can see
  and a wait he has to interrupt.
- Context hygiene: a 1300-line Gradle log lands in the child's context, and four
  lines land in this one. Over a run that gap is large.
- A child can REACT — capture the failing assertion verbatim, check whether it
  reproduces — where a log file only sits there.

Four conditions, each from a real failure:

1. **State the report contract.** Exit code, executed test counts, and failing
   text VERBATIM. Not a summary — a gate result is exactly where the skill's
   two-hops-of-paraphrase warning bites.
2. **The child logs to disk as well as replying**, so a stall still leaves
   evidence and `agent_observe` shows where it stopped.
3. **The tree is frozen while a child runs a gate.** Editing a file a child is
   executing invalidates its run: bash reads a script incrementally, and that run
   has to be thrown away. Whoever runs the gate owns the tree until it reports.
4. **A green result is valid only for the commit it ran on.** The reply names the
   SHA; check it has not moved — the same reasoning as the land lock.

**Foreground** stays right for anything short (under about a minute), and for the
case where waiting is the only work left. **`nohup` is not banned** — it suits
fire-and-forget work whose result the run does not need — but a detached job owns
its poll, and a single poll that finds the job still running means a wake-up is
now required before the turn ends.

**Landing is the case that must report itself.** Announce the wait before it
starts, run the post-lock sequence — squash, format, `git commit`, `ship-land` —
as ONE unit rather than a turn per command, and make the report the next thing
generated after it returns. A `git commit` that succeeded is not the news; a
landed SHA is. The same holds for a failure inside the lock: release, then report
at once. "Landed", "failed" and "blocked" are terminal outcomes, and a terminal
outcome is announced the moment it is known, ahead of any other content.

### 1. claim

`scripts/ship-claim -l <lane> [-s <slug>]` from the original checkout. Capture
its stdout; `RUN_SCRATCH` and `CODEBASE_ROOT` parameterize everything after. All
work happens in `CODEBASE_ROOT`, never the original checkout.

### 2. design

Lane A only. Produce `rfc/<n>-<title>.md` with the four sections the Tier 0
reviewers check mechanically — `## Detailed Design`, `## Files Modified`,
`## Implementation Plan`, `## Tests`. Omitting one silently disables Tier 0.

Delegate the _research_ (bounded, known question, child returns findings). Keep
the _deciding_ inline: a child cannot talk to Ian, so a delegated design
conversation still routes every token through this session, at two hops of
paraphrase loss.

### 3. APPROVE — the one gate

Present: the lane, the design summary, and **the decisions where taste matters,
with defaults pre-chosen**. Approving should cost one word; disagreeing, one
line. Record it: `ship-state -s <rs> set APPROVED yes`. Do not ask again.

### 4. implement

Delegate to a child. State its write-scope as a **deny list**, not an allowlist:

    scripts/ship-verify-scope -s <rs> -f "$BASE_SHA" \
      -d '.claude/*' -d '.prime/agent/skills/ship/*'
    scripts/ship-checkpoint -s <rs> impl 1 -m "..."

**Do not hand an implementation dispatch a strict allowlist.** A small required
edit to an unlisted wiring, config, or fixture file is normal engineering, and
failing the run over it only forces a worse change that stays inside the lines.
Enforce absolutely where it is mechanical -- never touch the Claude Code
workflow, never edit this skill from inside a run -- and leave "is this
footprint consistent with the design?" to review, where the answer may be _widen
the design_ rather than _revert_. Reserve allow-globs for a genuinely narrow
dispatch (a docs-only fix, a single-file rename).

A deny hit is a reset to the last checkpoint — `scripts/ship-recover -s <rs>`.
Everything else is a review question. Take the child's report at face value;
re-verify only where **nothing** reported -- a stall, a kill, or a hand edit.

An opportunistic improvement the design never mandated is not blocked here. It
is surfaced at review as scope creep and consciously kept or reverted -- silent
blocking loses a real insight, silent acceptance compounds drift.

### 5. verify

`ship-state -s <rs> set PHASE verifying`, then
`ship-checkpoint -s <rs> before-verify`, then **rebase before anything else
looks at the tree**:

    scripts/ship-rebase -s <rs>

Rebase is the first thing verify does, so the tree that is reviewed and tested
is the tree that lands. Reviewing a stale tree reviews code that will never
exist.

See [`references/review.md`](references/review.md). Order matters and is not
negotiable: **Tier 0 → 1 → 2 → 3**, sequential, because each tier's fixes move
the tree the next tier reviews. Parallelise _within_ a tier only.

Persist as you go, one file per lens, so a stall costs one lens rather than the
tier: `<rs>/findings/lens-plan.json` (ran + skipped, with the skip reason) and
`<rs>/findings/tier<N>-<lens>.md`. Record the write-scope result too —
`ship-verify-scope … | tee <rs>/findings/write-scope.txt` — so the archive shows
the check ran, not merely that nobody complained.

Then the real gate — `nix develop -c bin/test`, dispatched to a child like any
other long command — and, for any UI change, screenshots as artifacts (see
[`references/visual-gate.md`](references/visual-gate.md)).

### 6. land

`ship-state -s <rs> set PHASE landing`, then
`ship-checkpoint -s <rs> before-land`. Write the report (phase 7) **first**:
`ship-land` deletes the worktree.

    scripts/ship-lock   -s <rs> acquire   # ← enter the critical section
    scripts/ship-rebase -s <rs>          # no-op if nothing moved since verify
    scripts/ship-order  -s <rs>          # re-place this run's ORDER lines
    scripts/ship-squash -s <rs>
    nix develop -c bin/format
    nix develop -c git commit            # code — through the FULL hook. The gate.
    nix develop -c git commit --no-verify  # RFC markdown only, lane A
    scripts/ship-land -s <rs>            # ff-merge, then releases  ← exit

`ship-order` is not optional: `ship-land` re-runs it as `ship-order -s <rs> -n`
(dry run — writes nothing, exit 3 when `ORDER` would change) before the
fast-forward and REFUSES, exiting with `ship-order`'s OWN status (3 stale, 1
corrupt) rather than a flat 1. Skipping it in the block above therefore does not
land; it stops at the very end, after the gate, and the fix is to run
`ship-order`, re-commit through the hook, and re-run `ship-land`. The check is
there because a skipped `ship-order` is otherwise invisible: the run's `ORDER`
line exists, only its POSITION is wrong.

Run that block as ONE foreground sequence, and report the fast-forward in the
same turn it returns — see "Long commands run in a subagent; landing reports
itself". A
`git commit` that succeeded is not the news; a landed SHA is.

`ship-order` sits between the rebase and the squash, and both edges matter. It
reads the base branch's `db/schema/ORDER` at `BASE_SHA`, so it must run
**after** the final rebase, when that copy is the order migrations actually
landed in rather than a prediction; it rewrites the file, so it must run
**before** the squash and the commit, so the line joins this run's staged diff
and lands **inside the hook-verified tree**. Appending it in `ship-land` instead
would need a `--no-verify` commit and a doc-glob exemption for `db/schema/ORDER`
— a hole in the one gate phase 6 exists to protect. It is derived and
idempotent: a run with no migration changes nothing, and a fix loop re-runs it
for free (RFC 180).

`db/schema/ORDER` is marked `merge=union` in `.gitattributes`. A rebase
therefore never stops on it: git keeps both sides' lines, with the newly landed
upstream lines first and this run's line last. Union never fails, so it cannot
catch two runs appending the same filename — `db-migrate` refuses a duplicate
line, and `ship-order` is what makes the final order deterministic rather than
merge-order-dependent. Do not resolve `ORDER` by hand and do not reorder landed
lines. `ship-order` refuses, without writing anything, a worktree copy that
deletes a line the base already has, a duplicated line, a line that is not a
legal migration filename or names no file on disk, or a `db/schema/*.sql` on
disk that no line names — that last one is an unordered migration, whose
position is the author's to declare and not this script's to guess. It reports
every kind it found in ONE pass, so a corpus with three faults costs one
land-time run and not three.

The lock is repo-wide and serialises the whole sequence, not the `git commit`
alone: the hook's result is only valid for the base it started on, so the rebase
has to be inside the lock too. `ship-lock acquire` waits up to 30m and holds for
at most 15m; `ship-land` releases it at the fast-forward. Phase 5's
`nix develop -c bin/test` stays **outside** it — the hook re-runs the tests, and
holding the lock through review would serialise the part of a run that has no
reason to be serial.

If the hook fails on tests, **stop and report**. That is the one place after
approval where this skill wakes Ian — and it happens inside the critical
section, so: **any exit from phase 6 that is not a land releases the lock
first.** Run `scripts/ship-lock -s <rs> release`, then report. A run that stops
to ask a human while holding the lock blocks every other run for up to 15
minutes, which is the cost the lock exists to remove. The fix loop then
re-enters this block **at the top** — `ship-lock acquire`, then `ship-rebase` —
never at the `git commit`: `main` may have moved while the lock was down, and
gating against a stale base is the wasted hook run the lock exists to remove.

A rebase conflict here is inside the lock too. Resolve it now, or release the
lock before you stop to ask.

**If `acquire` itself times out**, nothing has been spent yet. Run
`scripts/ship-lock status` — it needs no `-s` and works from anywhere — to see
the holder. A live run: wait and retry. A lock left behind by a killed session
expires on its own within 15m, or `scripts/ship-lock -s <rs> release -f` breaks
it now, naming what it displaced.

### 7. report

**Write `<rs>/report.md` BEFORE calling `ship-land`** — the worktree is deleted
at landing, and a report that exists only in this session dies with it. Then
give Ian the same content in chat.

RFC number/slug, landed SHAs with `--stat`, what was built, per-tier findings
applied/discarded, the test counts actually executed, and **open items** — every
finding the review declined to decide. Point at
`.scratch/ship-archive/<run-id>/`.

Then a **Learning candidates** section: every place review had to argue from
taste because **no principle in the corpus covered the case**. Not the findings
a lens produced — those are already the corpus working. These are the judgement
calls made unaided, the ones that would be re-litigated identically on the next
run.

State each in one line: what the call was, and what a rule would have had to say
to settle it. Write `None` when there were none; an empty section is a real
result, and a run that always finds candidates is nominating noise.

This is a **queue for Ian to read, not an action**. This skill never edits
`.claude/skills/**` — a run must not write the rules it is judged by, and only
Ian can tell a one-off taste call from a principle. Codification is
[`/principle`](../principle/SKILL.md), a separate human-gated session.

Finish with `ship-state -s <rs> set PHASE complete`.

## What this shares with Claude Code, and how

Two things, both declarative and read-only. See
[`references/boundaries.md`](references/boundaries.md).

- **A git naming convention** — lane A claims `pipeline/rfc-<n>` +
  `<repo>-rfc-<n>`, scanning the same three sources rfc-pipeline scans. Git is
  the arbiter: `worktree add -b` is atomic, so a concurrent claim from either
  workflow loses and bumps. No shared code, full mutual exclusion.
- **The review-rule corpus** — `.claude/skills/{code,design,impl}-review-*/` and
  `review-fix/tiers.md`, read-only. Their _chains_ are Claude Code orchestration
  and are NOT used; this skill dispatches the same rules itself. The corpus has
  exactly one writer, [`/principle`](../principle/SKILL.md), and this skill is
  not it — phase 7 nominates candidates and stops there.
