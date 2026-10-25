---
name: work
description: >-
  Takes a short instruction from Ian and carries it to landed, production-quality
  code in the unicoach repo, as fast as the change allows. Sets four dials (spec
  level 0–4, review scrutiny, and the derived approval and tests), claims a
  worktree, implements through a subagent, reviews one rule per reviewer in
  tier order, lands through the full hook by fast-forward, and leaves a per-run
  record. Use when Ian asks to build, implement, fix, change or ship something
  in unicoach, or invokes /work. The Claude Code successor to Prime Agent's
  /ship; replaces quick-change, rfc-pipeline and review-fix once proven.
---

# work

One instruction in, landed change out. This session is the coordinator: it makes
the judgement calls, dispatches subagents for the parallel work, and runs the
git plumbing. Speed comes from paying only for what the change needs; quality
comes from the hook, the approval stop and narrow reviewers, none of which a
dial can switch off.

## Non-negotiables

1. **Two authorities can say no**: Ian at approval, `bin/pre-commit` at commit.
   There is no CI. Never weaken either; never `--no-verify` the land commit.
2. **The approval stop is not an optimisation target.** Nothing downstream
   catches "we built the wrong thing". When it is required, make it cheap
   (decisions with defaults), never absent.
3. **Never edit this skill, its references, or
   `.prime/agent/skills/ship/scripts/**` from inside a run.** Fix the workflow
   between runs, so each run tests the version it started with. Learnings go in
   the run record.
4. **Toolchain commands run `nix develop -c`**; iOS scripts run under system
   Xcode and refuse inside the dev shell.
5. **The run is on disk.** After compaction or restart, find it with
   `$SHIP/ship-status` and read its state and `record.md`, never memory.

`$SHIP` below is `.prime/agent/skills/ship/scripts` **in the original checkout**
— /work uses Prime /ship's plumbing in place (each script has `-h`), so a run
that edits those scripts cannot break itself. They name branches `ship/<slug>`
and archive to `.scratch/ship-archive/`; that is expected until the scripts move
here.

## Talking to Ian

Every message follows
[`.prime/agent/skills/shared/operator-comms.md`](../../../.prime/agent/skills/shared/operator-comms.md):
a triage label on the first line (`FYI — NO ACTION NEEDED`,
`YOUR DECISION REQUIRED`, `DONE`), the outcome first, internal names expanded
the first time, every stop in a `YOUR DECISION REQUIRED` block with a default.

## The dials

Full rules: [`references/dials.md`](references/dials.md). You set **spec** and
**scrutiny**; **approval** and **tests** are derived. Ian never names them; you
choose and say why, he vetoes.

| Spec | What exists before code                    | Typical change                         |
| ---- | ------------------------------------------ | -------------------------------------- |
| 0    | the instruction                            | docs, a rename, a config value         |
| 1    | the instruction + a failing test           | a bug fix                              |
| 2    | a short plan in `<rs>/plan.md`             | a small feature with one obvious shape |
| 3    | a light RFC: the four Tier 0 headings only | a new surface, a table, a migration    |
| 4    | a full RFC (`/rfc-design` standard)        | several shapes, costly to get wrong    |

| Scrutiny   | Reviewers                       | Fixed before land        |
| ---------- | ------------------------------- | ------------------------ |
| `none`     | none; the suite and hook review | —                        |
| `targeted` | every applicable lens           | `BLOCKING`               |
| `full`     | every applicable lens           | `BLOCKING`, `SHOULD-FIX` |

Presets are shorthand, not a menu: **mechanical** = spec 0 / `none`; **bug fix**
= spec 1 / `targeted`; **feature** = spec 2–4 / `full`.

## The first message

Never open with a question. State the dials in words, each with its reason:

    FYI — NO ACTION NEEDED — <outcome in one line>
    Preset: bug fix.
    Spec: 1 — the failing test gives the shape.
    Review: targeted — two Kotlin files; only blocking findings get fixed.
    Approval: skipped — no schema, prompt, money, auth, deletion or public copy.
    Tests: chosen from the diff by bin/gate-scope; the hook decides.
    Plan: <two or three lines>

When approval is required the first message is the approval presentation
instead, labelled `YOUR DECISION REQUIRED` (sent once the spec is written).

## The phases

    claim → spec → [approve] → implement → review → verify → land → report

Record every phase boundary in the run record
([`references/record.md`](references/record.md)) as it happens, not afterwards.

1. **Claim.** From the original checkout:
   `nix develop -c $SHIP/ship-claim -l quick -s <slug>` for spec 0–2, `-l rfc`
   for spec 3–4 (claims the RFC number). Keep the printed `RUN_SCRATCH` (`<rs>`)
   and `CODEBASE_ROOT`. Record the dials with
   `$SHIP/ship-state -s <rs> set DIAL_SPEC <n>` (and `DIAL_SCRUTINY`,
   `DIAL_APPROVAL`). Create `<rs>/record.md`. An instruction whose first line is
   `Slice: <id>` was dispatched by /slice: also
   `$SHIP/ship-state -s <rs> set SLICE <id>`, so `slice-board` lists the run as
   IN FLIGHT for that slice instead of as unattributed.
2. **Spec.** Level 0–1: nothing to write. Level 2: `<rs>/plan.md` with the four
   headings Tier 0 reads (`## Detailed Design`, `## Files Modified`,
   `## Implementation Plan`, `## Tests`), a few lines each. Level 3–4:
   `rfc/<n>-<slug>.md` in `CODEBASE_ROOT`. Delegate bounded research to
   `Explore`; keep the deciding here.
3. **Approve** (when required). Present the dials, the design in brief, and each
   taste or irreversible decision as its own block with a default; a new table
   as its DDL with the cost of being wrong. Write what Ian approved to
   `<rs>/approval.md`. On changes, revise and re-present; never ask twice.
4. **Implement.** `$SHIP/ship-checkpoint -s <rs> before-impl`, then one
   implementer subagent (brief:
   [`references/implement.md`](references/implement.md)). Spec 0 changes small
   enough to type directly may be done inline instead. Then:
   - `$SHIP/ship-verify-scope` with the deny list in `implement.md`; a hit is
     reset with `$SHIP/ship-recover` to `before-impl` and re-briefed.
   - `$SHIP/ship-checkpoint -s <rs> impl`.
   - **Re-derive approval from the real diff.** A path the instruction did not
     predict tripping a rule in `dials.md` stops the run here for Ian. Raise
     scrutiny too if the diff now requires it.
5. **Review** (skip at scrutiny `none`). `$SHIP/ship-rebase -s <rs>` first, so
   the tree reviewed is the tree that lands. Then Tiers 0 → 3 from
   `.claude/skills/review-fix/tiers.md`, one tier at a time, one reviewer per
   rule: [`references/review.md`](references/review.md).
6. **Verify.** `nix develop -c bin/test` unscoped, once, after the last fix. It
   is not a second gate: it catches red before the land lock is taken, and
   Gradle's cache makes the hook's own run of it nearly free afterwards. Shell
   harnesses are left to the hook. Red: fix it from the failing lines,
   checkpoint, re-run.
7. **Land.** [`references/land.md`](references/land.md): lock, rebase, order,
   squash, one commit through the full hook, fast-forward.
8. **Report.** Finish `record.md` (it is archived by `ship-land`; finish the
   numbers before landing and append the landed SHA after), then send Ian the
   `DONE` message: the outcome in one sentence a non-engineer could repeat, the
   landed SHA, held findings one line each, open items with who must act, and
   the archive path `.scratch/ship-archive/<run-id>/`.

## When to stop for Ian

Only these, each one `YOUR DECISION REQUIRED` block with a default:

- **Approval** (above), and the diff tripping an irreversible rule the
  instruction did not predict.
- **An irreversible question from the implementer** — schema, stored data shape,
  prompt, public copy. Approval is required from then on.
- **Tier 0 would amend a line Ian approved.** Default: amend the spec to match
  the code.
- **A `BLOCKING` finding that contradicts another rule.** Quote both rules and
  the code. Default: leave the code, record the contradiction.
- **A failing test you did not cause** (red at `BASE_SHA` too). Default: stop;
  repairing it is a new run.
- **A rebase conflict whose right answer is not obvious** — two runs disagreeing
  about the same code is a design signal.

Everything else — a red suite caused by this run, a stalled subagent, a format
failure — you fix and note in the record.

## Learning candidates

At report time, list calls made from taste because no rule covered them — not
lens findings. One line each: the call, and what a rule would need to say.
`None` is a real result. Codifying is `/principle`; the review skills themselves
are edited only there.
