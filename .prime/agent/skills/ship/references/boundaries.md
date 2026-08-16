# Boundaries: what ship shares, and what it must never touch

## Never modify

`.claude/**` is the Claude Code workflow. It stays exactly as it is. `ship` does
not edit, call, source, or import anything under `.claude/skills/*/scripts/`.

An earlier draft of this workflow proposed calling
`.claude/skills/rfc-pipeline/scripts/*` directly for the git plumbing. That was
rejected: it makes another workflow's internals into this one's API, so any
refactor there breaks this silently, and changes wanted here would perturb
there. `ship` has its own plumbing in `../scripts/`.

## Shared thing 1 — a git naming convention (lane A)

Coordination happens through **git**, not through shared code. `ship-claim`
independently re-implements the same claim protocol:

    n = 1 + max( committed rfc/NN-*.md
               , branches  pipeline/rfc-*
               , worktrees <repo>-rfc-NN )
    then: git worktree add -b pipeline/rfc-<n> <parent>/<repo>-rfc-<n>

`worktree add -b` is atomic, so if either workflow claimed the number since the
max was computed, the add fails and the loser bumps and retries. Two independent
implementations cannot collide.

**Note the scan is three sources, not one.** At the time of writing, `rfc/` tops
out at 111 but `pipeline/rfc-112` and `pipeline/rfc-113` already exist as live
branches and worktrees. Reading only the committed files would hand out a number
already in flight.

Degradation is graceful: if the conventions ever drift, the worst case is a
failed `worktree add` and a bumped number — never a corrupted claim.

Lane B uses `ship/<slug>`, disjoint from Claude Code's `quick/<slug>` and from
`pipeline/rfc-*`, so it never contends at all.

## Shared thing 2 — the review-rule corpus

Read-only, both directions. See [`review.md`](review.md). The rules are the
asset worth sharing: they carry accumulated, sharpened judgement about this
codebase, and duplicating them would guarantee drift. Their orchestration is not
shared.

The one remaining coupling is **path discovery** — `ship` must know the rules
live under `.claude/skills/`. That is declared in `review.md` and checked at
startup, so a move produces one clear error rather than a silent no-op review.

## Shared thing 3 — `bin/pre-commit` (additive only)

`ship-verified` needs the hook to record what it validated. The change to
`bin/pre-commit` is **one line on the success path** and is inert for Claude
Code: rfc-pipeline neither reads nor writes the marker file, and a run that
never calls `ship-verified assert` behaves exactly as before.
