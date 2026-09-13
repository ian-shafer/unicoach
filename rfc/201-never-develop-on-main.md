# RFC 201 — Never develop on `main`

**Status:** implemented

## Summary

`/chart` and `/slice` wrote `product/**` directly in the root checkout and
committed on `main`. That is development on `main`, it is the only writer in
this repo that still worked that way, and it is the root cause of the two
parallel-work hazards left open after RFCs 193 and 194.

This RFC gives documentation the shape code has had all along — its own
worktree, its own branch, fast-forward into `main` — and enforces it in
`bin/pre-commit`, which now refuses a commit made in the root checkout or on
`main`.

## The problem, measured

Both failures were observed on 2026-09-11, an hour apart, by two sessions
sharing the one checkout. Neither session did anything its skill did not tell it
to do.

**Hazard 3 — the hook validates the TREE, not the commit.** A session's commit
died on `bin/format -c` because of an unstaged Markdown file owned by the OTHER
session and not present in the commit. `bin/test check` had already passed
(BUILD SUCCESSFUL, 4m 17s, 96 tasks), so four minutes of gate were spent and
then discarded by a file that was not being committed.

**Hazard 4 — `git add` will take any path you hand it.** A `docs:` commit
closing brief 0009 included 63 lines of the other session's in-progress spec,
under the wrong commit message and with no attribution. It passed every gate,
because a docs-only commit is exactly what it looked like.

One cause: **two writers in one tree.** `/ship` had already solved it for code
by giving every run a worktree; the documentation path was never given one.

## Detailed Design

### 1. The guard: `bin/pre-commit` refuses the root checkout and `main`

`bin/functions` gains `protected_commit_target <repo>`, which prints one line
naming why a commit must be refused and returns 0, or returns 1 silently. Two
clauses, both wanted:

- **The root checkout is read-only.** Detected by git-dir == git-common-dir,
  which is true in the primary worktree and false in every linked one — /ship's
  `unicoach-rfc-*`, and Claude Code's under `.claude/worktrees/`. The root
  checkout is the one tree every session shares, so it is the one tree nobody
  commits in.
- **HEAD is `main`.** Redundant TODAY: git refuses to check one branch out in
  two worktrees, so `main` lives only in the root checkout and the first clause
  already caught it. Kept because it encodes the RULE — _we never edit on
  `main`; we only fast-forward into it_ — rather than today's arrangement, and a
  guard that encodes the rule outlives one that encodes the arrangement.

A detached HEAD is not refused on the second clause. The Claude Code
rfc-pipeline worktrees run detached, and they are linked worktrees already doing
what this rule asks for. `.claude/**` is not modified by this RFC.

The guard runs FIRST, ahead of the dev-shell check, because it needs no
toolchain to answer and refusing twice in a row would spend a second round trip
on news we already had. `ship-land` is unaffected: it reaches `main` with
`git merge --ff-only`, which creates no commit and fires no hook.

**The refusal message is the migration.** An in-flight session adopts this by
being refused mid-close-out, so the text carries the whole recipe: what was
refused and why, the exact `docs-claim -s <slug> -a <path>...` with that
session's own staged `product/` paths filled in, and a plain statement that
nothing is lost. An error that makes the reader go and find an RFC is a worse
error. The recipe is built from the STAGED set and from nothing else: other
dirty paths under `product/` may belong to another live session, and a recipe
that swept them in would print hazard 4 from inside the fix for it.

### 2. The path that now exists: `docs-claim` and `docs-land`

Two new scripts under `.prime/agent/skills/chart/scripts/`, used by both /chart
and /slice:

    docs-claim -s <slug> [-b <base>] [-a <path>]...   # worktree + branch + state
    docs-land  -s <run-scratch>                        # assert, ff-merge, tear down

    branch      docs/<slug>
    worktree    <repo>-docs-<slug>
    run scratch <worktree>/.scratch/docs/<slug>

**Why a third prefix.** A docs change has no RFC number, so `pipeline/rfc-<n>`
is not merely unnecessary, it is harmful: that prefix is what `ship-claim` AND
Claude Code's rfc-pipeline scan to compute the next free RFC number, so a docs
branch parked there would burn a number for a change that has no design.
`ship/<slug>` is /ship's lane B and is what `ship-status` lists as live
engineering. A third prefix keeps the three populations distinguishable by name
alone. `git worktree add -b` is atomic, so two sessions choosing one slug is a
clean refusal rather than a collision.

**`-a` adopts only the paths NAMED.** Never a glob. The migration has to move
files out of a tree three sessions share, and doing that by hand means
`git checkout -- product/` on somebody else's work — which is the instrument
that caused hazard 4. `-a` copies each named path into the new worktree,
verifies the copy, and only then restores it in the root checkout (a tracked
file is reverted to the base branch; an untracked one is removed).
Copy-verify-then-restore is not stylistic: the restore deletes a file whose only
other copy is the one just made.

**`docs-land`'s gate is what the branch LANDS.** It asserts that the branch's
net diff against the base touches nothing outside `product/`. That is the whole
safety argument, and it is one sentence:

> A branch that can only land `product/` paths cannot land an untested build
> input, because `product/` is not a build input.

It deliberately does NOT ask `ship-verified` for a hook marker, and could not:
RFC 194 makes `bin/pre-commit` skip `bin/test check` for a product-docs-only
commit and record no marker for it, because a marker that can lie is worse than
no marker.

`docs-land` replays onto a base that moved, aborting and refusing if the replay
conflicts — two sessions disagreeing about one file is a human's to read, not a
script's to resolve inside the one path that reaches `main`. It leaves an
operation it did not start alone.

**No land lock.** RFC 193 considered making doc commits take the repo-wide lock
and rejected it: it taxes the common cheap case to protect the rare one, and doc
traffic scales with the number of parallel slices. That stands. Doc traffic
still moves `main` without the lock, which is exactly what RFC 193's docs-only
fast path in `ship-land` absorbs.

### 3. Where the path predicate lives

`branch_lands_product_docs_only` goes in `bin/functions`, beside RFC 194's
`staged_changes_are_product_docs_only`, and NOT into a new file of its own.

Three questions are now asked about `product/` paths, and they stay three
functions:

| Question                                    | Who asks                   | Where           |
| ------------------------------------------- | -------------------------- | --------------- |
| What is STAGED?                             | `bin/pre-commit` (RFC 194) | `bin/functions` |
| What did EVERY COMMIT in this range change? | `ship-land` (RFC 193)      | `ship-land`     |
| What does this branch LAND?                 | `docs-land` (this RFC)     | `bin/functions` |

Co-location, not sharing. Each of the three carries the same hard-won details —
`-z` with `IFS= read -r -d ''`, `--no-renames`, an empty set answering _no_
rather than passing a universal test vacuously — and each of those is a
REPRODUCED defect, not a precaution: a directory named `" product"` with a
leading space was read as `product`, classified docs-only, and skipped the gate.
Three copies in three files is how two of them rot while the third is fixed. Two
of the three now sit in one file with their difference stated in their
docstrings; `ship-land`'s stays where it is, with its one caller, as RFC 193
decided.

Per-commit is the wrong shape for the third question. A docs run may commit
twice, amend, or rebase; what it publishes is the tree, not its route to it. The
safety claim is about the LANDED TREE, so the predicate reads the landed tree,
and the caller asserts the fast-forward separately — which is what makes "net
diff against the base" and "what lands" the same set.

### 4. Sourcing `bin/functions` from a skill script

The `docs-*` scripts source `bin/functions`, and nothing else from `bin/`. The
`ship-*` scripts source nothing from `bin/` at all, and that rule is kept for
them, but its stated reason is about **`bin/common`**: it layers the dotenvs and
derives a per-checkout port block for a checkout those scripts merely point AT,
so an env fault in a repo they do not run in would fail them. `bin/functions`
assigns defaults and defines functions and does nothing else, and it was
measured to parse and source cleanly under macOS system bash 3.2 — the shell a
skill script runs in outside the Nix dev shell. Sourcing it is free, and it is
what keeps the `product/` predicates in one file to read.

## Cost

**Measured 2026-09-11 on `main@f3aebc71`: `nix develop -c bin/format -c` takes
8.485s over 1175 files.** RFC 194 is what makes this RFC affordable. Every
ledger line and every `STATUS.md` edit is now a branch that must pass the hook
on its way in; without RFC 194 each of those would pay the full gate, which the
run that started all of this measured at 4m17s. RFC 194 turns _"every docs edit
now pays a gate"_ from a real objection into eight and a half seconds. This RFC
makes RFC 194's exemption MORE load-bearing, not redundant — it is now on the
path of every documentation change rather than an optimisation for some of them.

The other cost is real and is accepted: **committing by hand from the root
checkout no longer works.** It needs a worktree, or `--no-verify`.

## What this closes, and what it does not

**Hazard 4 is CLOSED by construction.** Another session's in-progress file is
uncommitted in THEIR worktree, so the path does not exist in yours and `git add`
cannot be handed it.

**Hazard 3 is closed FOR THE OBSERVED FAILURE, and not in general.** The hook is
still whole-tree; what changed is that the tree now holds one session's work.
Two sessions sharing ONE worktree would still collide, and nothing here prevents
that. Do not read this as "the hook now validates the commit" — it does not, and
whether it should is still the open question RFC 193's write-up declined to
decide.

Three residual holes, all real:

1. `git commit --no-verify` bypasses the guard, as it bypasses every other check
   in this hook. This makes the old path impossible BY DEFAULT, which is the
   difference between a rule in the instrument and a rule in a session's memory
   — the latter is what failed twice in one afternoon. It is not impossible.
2. The guard blocks COMMITTING in the root checkout, not WRITING there. A stray
   file left in it now blocks `ship-land`'s `git merge --ff-only` instead of
   being committed by mistake: a better failure, still a failure somebody must
   clear. `docs-claim` names any dirty `product/` path it finds there and
   touches none of them, because they may not be its.
3. A docs commit made with `--no-verify` lands unformatted Markdown, and
   `docs-land` cannot tell — RFC 194 records no verified marker for such a
   commit, so there is nothing to assert. The format gate is the hook's, and
   this RFC does not duplicate it at land time.

## Files Modified

| File                                           | Change                                                                                                               |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `bin/functions`                                | `branch_lands_product_docs_only`, `protected_commit_target`; a note on why the `docs-*` scripts may source this file |
| `bin/pre-commit`                               | the guard, first, with the migration recipe in its refusal                                                           |
| `.prime/agent/skills/chart/scripts/docs-claim` | NEW                                                                                                                  |
| `.prime/agent/skills/chart/scripts/docs-land`  | NEW                                                                                                                  |
| `bin/docs-scripts-tests`                       | NEW — 46 assertions                                                                                                  |
| `bin/shell-tests`                              | runs the new harness                                                                                                 |
| `.prime/agent/skills/chart/SKILL.md`           | non-negotiable 8; "Landing product docs"; the ship relationship                                                      |
| `.prime/agent/skills/slice/SKILL.md`           | non-negotiables 6-7; CLOSE OUT claims a docs worktree; checklist                                                     |
| `CLAUDE.md`                                    | the rule, the two hazards, the two honest limits                                                                     |

`.claude/**` is not modified. `ship-land` is not modified.

## Implementation Plan

1. `bin/functions`: the two predicates, beside RFC 194's.
2. `bin/pre-commit`: the guard and its refusal text.
3. `docs-claim`, then `docs-land`.
4. `bin/docs-scripts-tests`, wired into `bin/shell-tests` (the anti-orphan sweep
   in `bin/scripts-tests` asserts the wiring line exists).
5. The skill files and `CLAUDE.md`. These are proposed to Ian, not merely
   edited: `chart/SKILL.md` says of itself that a lesson changing how the skill
   works is a skill edit, gated like code.

## Tests

`bin/docs-scripts-tests` — 46 assertions, 4.6s, no cluster, no Gradle, no
worktree of the real repo. Every case runs against a throwaway `git init` repo
under `mktemp -d`: a case that reached the real checkout would add branches to a
machine running live sessions, and `docs-land` ends in `git merge --ff-only`.

The cases that exist because something really failed:

- **`-a` leaves a path it was not given alone, in BOTH directions.** A second
  session's file sits in the same directory and is not named; it must be in
  neither the worktree nor changed in the root checkout. This is hazard 4,
  re-run against the tool built to close it.
- **A directory named `" product"` (leading space) is NOT docs-only.** RFC 193's
  sibling implementation read its paths without `IFS=`, so bash stripped the
  space and a code path was smuggled past the gate by a filename. Drop the
  `IFS=` and this fails.
- **A rename out of `product/` into `bin/` is refused.** With rename detection
  on, `--name-only` prints only the destination; `--no-renames` decomposes it.
- **A branch that lands NOTHING is refused, and says so in its own words.**
  "Lands nothing" and "lands code" are different mistakes with different fixes.
- **A conflicted replay is aborted, the worktree kept, `main` untouched** — and
  an operation the script did NOT start is left alone, because aborting an
  operator's paused rebase while reporting it as our own conflict destroys work
  that refusing cleanly would have left.
- **`GIT_DIR` from a hook cannot move `docs-land` off the run's own repo.** This
  harness runs inside `bin/pre-commit`, where `GIT_DIR` is exported and outranks
  both `git -C` and `cd`; a `docs-land` that trusted the environment over its
  run state would fast-forward the REAL repository from a fixture branch.
- **stdout discipline, on both scripts.** `docs-claim` writes only its KEY=value
  state; `docs-land` writes only the landed SHA. Both assertions failed on the
  first run and found real leaks — `git worktree add` writes "HEAD is now at"
  and `git merge` writes "Updating a..b", both to stdout.

### The refusal that looked right and failed on paste

Worth its own note, because it is the defect this RFC most nearly shipped.

The recipe derives its slug from the first staged path with a `sed`
backreference. That line was written once with the backreference mangled to a
control character, so the refusal printed:

    .prime/agent/skills/chart/scripts/docs-claim -s  -a product/0011-demo/spec.md

A command that LOOKS right and fails on paste is worse than no recipe at all:
the reader trusts it, it does not work, and they then distrust themselves rather
than the message. It was found by CLONING the branch and RUNNING the real hook —
not by reading the line, where the mangled character is invisible.

The test lifts the `sed` expression OUT of `bin/pre-commit` and runs it on a
sample path. A `grep` for the line's shape would have passed. Negative control
confirmed: remove the backreference and the assertion fails.

### The restore that called the right command and achieved nothing

Found in review, before landing, and it is the defect this RFC would most have
regretted.

`docs-claim -a` restored a tracked file in the root checkout with
`git checkout -- <path>`. Without `HEAD`, git restores from the INDEX — so a
change the session had already STAGED survived untouched, while the script
logged that the root checkout had been reverted.

**The one case that must work was the one that did not, and it was the likeliest
case by construction.** The refusal message sends an agent to `docs-claim -a`
precisely BECAUSE they ran `git add` and were refused; the file is staged,
always. A restore that reads the index therefore no-ops on every real migration
and succeeds only in the case that never happens.

The fix is `git checkout HEAD -- <path>`, which resets the index AND the working
tree, so the root checkout is genuinely clean rather than clean-looking. The
test STAGES before adopting, because that is the case and not an edge of it.

The log line is treated as part of the defect, not as commentary on it. "root
checkout reverted" printed over a file that was not reverted is worse than
silence: an operator who reads it stops checking. It now says what it does —
restored from HEAD.

One thing worked: `docs-claim`'s leftover warning DID name the still-dirty path,
so the operator was not left blind. That is a partial mitigation of a false
claim, and not an excuse for one.

The general rule, nominated to `/principle`:

> **A log line that claims an action must be tested against the action's EFFECT,
> not its invocation.** The script did call a restore command; it simply
> restored nothing. A test asserting "the command ran" passes. A test asserting
> "the file is back at HEAD" fails.

### Why the guard's cases are not beside RFC 194's

The guard's own cases — the root checkout refused and named, a linked worktree
allowed, a DETACHED linked worktree allowed (the rfc-pipeline case), `HEAD` on
`main` refused even in a linked worktree — live in `bin/docs-scripts-tests` and
NOT in `bin/scripts-tests` beside RFC 194's classification cases, which is where
subject-matter adjacency would have put them.

`bin/scripts-tests` requires a live Postgres cluster and built
`rest-server`/`queue-worker` `installDist` artifacts, and costs roughly 63
seconds. Not one of these 47 cases needs any of it: they run against throwaway
`git init` repos in 4.6 seconds total. Sixty-three seconds a run, forever, is
the kind of cost that gets a harness quietly skipped — and these cases guard the
one check that stops development on `main`. **This is a deliberate placement,
not an oversight: do not "fix" it by moving them.**

## Credit

The four hazards were diagnosed on 2026-09-11 by two `/chart` sessions running
slices in parallel, and written up in `.scratch/land-lock-writeup.md`. The rule
all four converge on is theirs, and it is the rule `-a` implements:

> **Enumerate what you are about to act on, then act — never act on the set you
> think you own.**
