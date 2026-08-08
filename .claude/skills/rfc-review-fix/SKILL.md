---
name: rfc-review-fix
description: >-
  Review and repair loop for an RFC or other document target, in two modes.
  Auto (the default) runs the supplied reviewer skills once, applies every
  complete finding's recommended option, then walks the operator through each
  applied change one at a time — the diff hunk plus at most 60 words — applying
  the operator's edits to the target on the spot. Manual is the original
  operator-driven loop: every finding shown, the operator picks what to apply,
  one at a time, each reversible, with operator-elected further passes; no
  walkthrough. Closes by feeding rejected findings into a skill-update pass so
  a reviewer that produced a bad finding gets fixed. Use when a user asks to
  review-and-fix an RFC or document, or invokes /rfc-review-fix.
---

# RFC Review / Fix

A generic orchestrator. It moves messages between four roles and makes no
judgements of its own.

| Role             | Who                                                         |
| ---------------- | ----------------------------------------------------------- |
| **operator**     | the human; decides everything in manual mode, and shapes    |
|                  | the target in the auto walkthrough                          |
| **reviewer**     | a skill that inspects the target and returns findings       |
| **fixer**        | a skill that applies one finding to the target              |
| **orchestrator** | this skill; dispatches, presents, snapshots, and rolls back |

In **manual** mode the operator decides every outcome, as the phase sections
describe. In **auto** mode (the default) the run is unattended until the end:
one review pass, every complete finding's recommended option applied, then the
**walkthrough** (Phase 3b) — the operator's seat at the table, after the fact.

## Invocation Parameters

- **Mode**: `auto` (default) or `manual`.
- **Reviewers**: the reviewer skills to run (a list, or a glob such as
  `code-review-*`).
- **Fixer**: exactly one fixer skill.
- **Target**: what is under review — usually an RFC path; a commit SHA or file
  set works too.
- **Scratch Dir** _(optional)_: run-scoped working directory. Defaults to
  `.scratch/rfc-review-fix/<timestamp>/`.

If any of Reviewers, Fixer, or Target is missing, stop and ask the operator.

## Precondition — start from a clean tree

`HEAD` is the rollback point, so the working tree **MUST be clean before Phase
2**. If it is not, stop and ask the operator to commit or stash first. Never
commit or stash their pre-existing work to clear the way — those changes are not
this run's to move.

## Prime directive — never filter

The orchestrator **MUST present every finding every reviewer returned**, in the
reviewer's own words. It does not rank, merge, summarise, drop, or judge
relevance — not for scope, not for severity, not for apparent value. Deciding
what matters belongs to the operator in manual mode and to the stated policy in
auto — never to the orchestrator. A finding the orchestrator quietly discards is
a reviewer defect nobody can see. Auto mode does not filter either: every
finding is presented, then applied or recorded as an open item, and every
applied change is walked through — nothing is silently dropped.

Likewise the orchestrator **never loops on its own**. Every iteration is entered
because the operator chose to enter it. Auto mode runs exactly **one** review
pass; a further pass happens only if the operator elects one at the end of the
walkthrough.

## The finding contract

A reviewer returns zero or more findings. Each finding MUST carry:

- a **detailed description** of the issue — what is wrong and why it matters;
- **at least two options** to resolve it, each containing the **actual code or
  RFC text** to apply, not a description of it;
- exactly one option marked **recommended**, with the reason.

A finding missing options or a recommendation is incomplete: present it to the
operator marked `⚠ incomplete` rather than dropping or repairing it.

Each reviewer writes its findings to `<scratch>/pass-<p>/findings/<reviewer>.md`
— `<p>` is the review-pass counter, starting at 1 (see Phase 3) — write-once, as
markdown:

```markdown
---
reviewer: <skill name>
target: <target>
findings: <integer count>
---

## 1. <one-line title>

**Description:** <what is wrong, where, and why it matters>

**Option A:** <the literal replacement code or text>

**Option B:** <the literal replacement code or text>

**Recommended:** A — <why>
```

## Phase 1 — Review

1. Resolve the reviewer list. Report the resolved set to the operator before
   spawning, so a mistyped glob is caught before the fan-out.
2. Spawn **one background agent per reviewer**, each a fresh context window.
   - **description**: `[rfc-review-fix] <reviewer>`
   - **run_in_background**: `true`
   - **prompt**: name the reviewer skill to invoke, the Target, the finding
     contract above, and the exact output path
     `<scratch>/pass-<p>/findings/<reviewer>.md`. State that the agent writes
     that one file and changes nothing else.
3. Wait for all reviewers. Do not begin Phase 2 early.

   If a reviewer never returns, **record what happened** — which reviewer, how
   long, whether its findings file was written — and tell the operator. Do not
   silently retry or restructure the fan-out around it. A stall is the evidence
   for diagnosing the harness's task-reaping behaviour; suppressing it destroys
   the only signal.
4. Assign every finding a stable id `<reviewer-shortname>.<n>` and present the
   full set to the operator, grouped by reviewer, with each description intact.
   `<n>` is monotonic per reviewer **across the whole run, never reset by a new
   pass** — if pass 1's last finding was `<reviewer>.4`, pass 2's first is
   `<reviewer>.5` — so an id names a unique finding no matter which pass raised
   it.

## Phase 2 — Find / fix loop

**Auto mode replaces only the two operator choices in this loop** (the selection
in step 5 and keep-or-rollback in step 9); every mechanism between them — fresh
fixer context, verbatim hand-off, full diff and test report printed, one commit
per kept finding — runs unchanged:

- **Selection**: no prompt. Apply every **complete** finding's recommended
  option, in id order. A `⚠ incomplete` finding is presented, skipped, and
  recorded as an **open item** — the orchestrator never repairs or completes a
  finding itself.
- **Keep or rollback**: keep on green, or on the fixer's explicit "nothing to
  run" (the usual case for an RFC target); on red, one retry handing a fresh
  fixer the failure output, then roll back and record the outcome.
- **Stream everything.** Each finding, each diff, each fixer report is printed
  exactly as in manual mode, without waiting, and each decision is stated as it
  is taken (`auto: apply, recommended option`). The operator may interrupt any
  finding for full manual treatment, then resume auto.

In manual mode, repeat until the operator chooses none:

5. **Operator selects** one finding by id, or none to leave the loop.
6. **Dispatch the fixer.** `HEAD` is the last accepted state, so no snapshot is
   needed — the tree is clean and git already holds the rollback point.

   Spawn the fixer as a **background agent in a fresh context window**, one
   finding at a time — never a batch. Pass the finding **verbatim**, including
   all options and the recommendation, plus the Target. The prompt MUST state:
   - it **must not commit** — committing is the orchestrator's job, and only
     once the operator has said so;
   - it **owns verification** — after applying the fix it runs
     `nix develop -c bin/test` (the whole suite, unscoped, per CLAUDE.md) and
     reports the real executed counts. If the target has nothing to run — an
     RFC, a doc — it says so explicitly rather than staying silent.
7. **The fixer applies the finding** and reports what it changed and what it
   ran.
8. **Show the operator the diff and the test result together** — `git diff HEAD`
   plus any new untracked files, in full, followed by the fixer's verbatim test
   report. Both inform the same decision, so never present the diff alone. If
   the fixer reported no verification, say so plainly: an unverified fix is a
   thing the operator may still accept, but not by accident.
9. **Operator chooses keep or rollback.**
   - _keep_: commit it — one commit per accepted finding, so history reads as
     the operator's decisions:

     ```sh
     nix develop -c git commit --no-verify -am "<finding id>: <finding title>

     Reviewer: <reviewer skill>
     Applied: <the option that was applied>
     Verified: <what the fixer ran, and its executed counts>"
     ```

     These are intermediate commits on a work branch, so they skip the hook per
     CLAUDE.md's rule that the gate protects what reaches `main`. The fixer
     already ran the tests; the commit records what it ran. **The branch tip
     still has to pass the full gate before it lands** — run
     `nix develop -c bin/format -c` and `nix develop -c bin/test check` once,
     after the operator declines a further pass (Phase 3 in manual mode, the
     walkthrough's close in auto), and tell the operator the result.
   - _rollback_: `git reset --hard HEAD && git clean -fd`. Use `-fd`, never
     `-fdx`: the run's scratch is gitignored and must survive the rollback.
10. **On rollback, offer a retry.** The operator may re-run the same finding
    with additional notes; pass those notes verbatim to a fresh fixer context
    alongside the original finding. A retry re-enters step 6.
11. **Mark the outcome** — `kept`, `rolled-back`, or
    `rolled-back-reviewer-fault` — and present the remaining findings.
12. Return to step 5.

Record each outcome as a line in `<scratch>/ledger.jsonl`:
`{"id","reviewer","pass","outcome","decided_by","notes"}` — `decided_by`
(`operator` | `auto`) on every line, so the two kinds of evidence never blur.
This is the only durable record of what was decided and is what Phases 3b and 4
read.

## Phase 3 — Another pass?

When the operator leaves the find/fix loop, ask exactly one question: **run
another full review pass against the now-updated target?**

- **Yes**: increment the pass counter `<p>` (the initial fan-out was pass 1) and
  re-enter Phase 1 — fresh reviewer contexts, findings written to
  `<scratch>/pass-<p>/findings/`, finding numbers continuing monotonically. Then
  Phase 2 runs again over the new findings, and this question is asked again.
- **No**: proceed to Phase 4.

There is no cap and no autonomous exit condition — the loop runs exactly as many
passes as the operator asks for, and per the prime directive the orchestrator
never starts one on its own.

**In auto mode this question moves.** The single auto pass flows into the
walkthrough (Phase 3b) without asking anything; the question is asked once, at
the walkthrough's end, where the operator has just seen every change and knows
whether their edits warrant another look.

## Phase 3b — The walkthrough (auto mode only)

After the auto pass, walk the operator through **each applied change, one at a
time, in the order applied**. Manual mode skips this phase entirely — the
operator already saw and decided every change. This walkthrough is the auto
run's review gate: finishing it is the operator's sign-off on the target.

Each stop shows exactly two things:

1. The change's **diff** — that finding's commit, verbatim, in a fenced `diff`
   block. Two sites get two labelled hunks, as always.
2. An explanation of **at most 60 words**: what changed and why the reviewer
   recommended it. The cap is hard. The diff carries the detail; the words only
   orient. No preamble, no verdict, nothing after.

The operator replies **next**, or gives an **edit instruction**. An edit is
applied to the target immediately — by the orchestrator, directly; prose needs
no fixer fan-out — shown as a diff, and committed
(`nix develop -c git commit --no-verify -am "walkthrough: <summary>"`) before
moving on. The operator may equally edit the file themselves and say so; commit
that the same way.

An edit that reverses or substantially rewrites an applied finding is a
**retro-rejection**: record it in the ledger (`decided_by: operator`, outcome
`retro-rejected`, the operator's reason verbatim). It is exactly as much
evidence about the reviewer as a manual rejection, and Phase 4 spends it the
same way.

After the applied changes, walk the **open items** (`⚠ incomplete` findings and
anything else auto declined to decide) the same way — one at a time, the finding
verbatim, ≤60 words of orientation. The operator may act on one (it gets the
full manual Accept treatment) or wave it past.

When the walkthrough is done, ask the Phase 3 question once — another full pass
against the now-updated target? **Yes** re-enters Phase 1 in auto (pass
`<p>+1`), and the next walkthrough covers only that pass's changes. **No**
closes review: run the final gate — `nix develop -c bin/format -c`, plus
`nix develop -c bin/test check` only if the run touched anything beyond Markdown
(a Markdown-only target needs formatting, never a suite run, per CLAUDE.md) —
report the result, and proceed to Phase 4.

## Phase 4 — Skill update loop

A finding the operator rolled back, declined outright, or retro-rejected in the
walkthrough is evidence about the **reviewer**, not the target. This phase
spends it.

1. Present the findings again — every pass's — annotated with their find/fix
   outcomes and `decided_by`. An auto-applied finding nobody touched is weak
   evidence either way; a retro-rejection is as strong as a manual one.
2. **Operator selects** a finding whose reviewer should change, or none to
   finish.
3. The orchestrator **cannot run this itself** — `/skill-update` is interactive
   and needs a clean context. Print the exact prompt for the operator to paste
   into a **new conversation**:

   ```
   Invoke /skill-update on skill <reviewer skill>.

   Verbatim finding:
   <the finding, in full — description, options, recommendation>

   Subject (the code or text the finding was raised against):
   <the exact lines from the target, quoted>
   ```

   Include the **subject**. Without the text the reviewer was looking at, a rule
   that is simply wrong cannot be told apart from a rule misapplied to this one
   case — and those want opposite edits.

4. `/skill-update` discusses the change with the operator and edits the skill.
5. The operator returns here and asks for the remaining findings.
6. Return to step 2.

## What this skill never does

- Filter, rank, or summarise findings.
- Advance an iteration the operator did not ask for.
- Skip the walkthrough after an auto pass, or exceed 60 words at a walkthrough
  stop.
- Apply more than one finding per fixer context.
- Commit anything the operator has not accepted.
- Let the branch reach `main` without the full gate having run on its tip.
- Touch the operator's pre-existing uncommitted work.
- Edit a reviewer skill itself — that is `/skill-update`, with the operator
  present.
