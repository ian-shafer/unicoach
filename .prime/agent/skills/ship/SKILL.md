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
    scripts/ship-verify-scope -s <rs> -f <sha> GLOB...
    scripts/ship-squash  -s <rs>                    # collapse WIP to staged
    scripts/ship-verified record|check|assert       # the CI stand-in
    scripts/ship-land    -s <rs>                    # assert, ff-merge, teardown

Run every script with `-h` for its contract. **Read the run state from disk
after any compaction — never from memory.**

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

Delegate to a child with an explicit write-scope allowlist. On return:

    scripts/ship-verify-scope -s <rs> -f "$BASE_SHA" <allow-globs>...
    scripts/ship-checkpoint  -s <rs> impl 1 -m "..."

A violation is a reset to the last checkpoint, not a negotiation. Take the
child's report at face value; re-verify only where **nothing** reported — a
stall, a kill, or a hand edit.

### 5. verify

See [`references/review.md`](references/review.md). Order matters and is not
negotiable: **Tier 0 → 1 → 2 → 3**, sequential, because each tier's fixes move
the tree the next tier reviews. Parallelise _within_ a tier only.

Then the real gate — `nix develop -c bin/test` — and, for any UI change,
screenshots as artifacts (see
[`references/visual-gate.md`](references/visual-gate.md)).

### 6. land

    scripts/ship-squash -s <rs>
    nix develop -c bin/format
    nix develop -c git commit            # code — through the FULL hook. The gate.
    nix develop -c git commit --no-verify  # RFC markdown only, lane A
    scripts/ship-land -s <rs>

If the hook fails on tests, **stop and report**. That is the one place after
approval where this skill wakes Ian.

### 7. report

RFC number/slug, landed SHAs with `--stat`, what was built, per-tier findings
applied/discarded, the test counts actually executed, and **open items** — every
finding the review declined to decide. Point at
`.scratch/ship-archive/<run-id>/`.

## What this shares with Claude Code, and how

Two things, both declarative and read-only. See
[`references/boundaries.md`](references/boundaries.md).

- **A git naming convention** — lane A claims `pipeline/rfc-<n>` +
  `<repo>-rfc-<n>`, scanning the same three sources rfc-pipeline scans. Git is
  the arbiter: `worktree add -b` is atomic, so a concurrent claim from either
  workflow loses and bumps. No shared code, full mutual exclusion.
- **The review-rule corpus** — `.claude/skills/{code,design,impl}-review-*/` and
  `review-fix/tiers.md`, read-only. Their _chains_ are Claude Code orchestration
  and are NOT used; this skill dispatches the same rules itself.
