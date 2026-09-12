# RFC 193: Skip the land gate for a docs-only base advance

Instruction: Ian, 2026-09-11 — after `/chart tests` (brief 0010) measured four
consecutive lost land races, all four lost to commits that never contended for
the land lock: "a small, standalone /ship session" to fix it.

## Executive Summary

RFC 167 serialised the `/ship` land gate with a repo-wide lock, so two runs
cannot both spend the 2–4 minute `bin/pre-commit` gate on the same base. It
works — for **code** lands. It has one hole, and the hole is now the dominant
cost of landing:

**a plain `docs:` / `product/**` commit never takes the land lock.** It is an
ordinary `git commit` on `main` from a session that is not in phase 6 at all. So
it can move `main` while a run is inside its gate, and `ship-land` then refuses
the fast-forward: `[main] advanced past this run's base`. The run rebases and
pays the whole gate again.

Measured, 2026-09-11: run `rfc-187` lost this race **four times**, beaten each
time by a docs-only commit — `33c7abf8`, `50e44f8d`, `0403f784` — costing
roughly 16 minutes of gate time. Both sessions were following their skills
exactly: `slice`'s non-negotiable 5 makes the `product/**` commit **mandatory**
after every landed slice, so this traffic is not noise that discipline removes.
It scales with the number of parallel slices, which is the thing we are trying
to do more of. Four losses is not bad luck, it is the protocol.

Stated at its sharpest: **a docs-only commit pays for a test run it cannot
change, and skips the lock that would have made that run mean something.** Both
halves are wrong at once. `git commit` fires `bin/pre-commit`, so the docs
commit runs `bin/format -c` + `bin/test check` in full — the one commit type
that cannot change a test result pays the same price as code — and because it
never takes the lock, that expensive run buys no serialisation for anyone. We
run the expensive check on both sides and serialise neither.

This RFC closes the hole from the other end. `ship-land` **skips re-running the
gate when every commit the base advanced by touches only `product/**`.**

### Relation to RFC 167 — extends, does not supersede

RFC 167's decision stands in full: the lock, its durations, `ship-land`'s
self-healing backstop, and the refusal of a foreign holder are unchanged. This
RFC adds one exemption at the point where RFC 167 §6 said the gap was:

> the Claude Code `rfc-pipeline` and any hand-run `git commit` on `main` still
> bypass the lock

A `docs:` commit on `main` is exactly that bypass. RFC 167 could only refuse and
make the run pay; this RFC says the payment is unnecessary for one provable
class of advance. Nothing here weakens the lock, and a code land racing another
code land is still serialised by RFC 167 exactly as before.

### Why this is not a weakening of the gate

1. `product/**` is pure Markdown. It is not a Gradle input and cannot change a
   test result.
2. The hook also runs `bin/format -c`, which covers the **whole working tree** —
   so in principle a moved doc could fail formatting. But any doc commit that
   reached `main` **passed that same whole-tree format gate on its own way in**.
   A `main` that moved by doc commits only cannot be unformatted.
3. So the cheap path is not skipping a check. It is **declining to re-run a
   check whose result is already proven for that tree.**

### What this narrows, stated exactly

CLAUDE.md's rule is that the full hook is required on **whatever lands on
`main`**. After this fast path that is no longer literally true, and the RFC
says so rather than glossing it: the replay gives this run's commits new shas,
and the landed tree is _our hook-verified code plus their docs_, which as a
whole was never hook-verified.

The narrower claim that makes it sound, and the only one anybody should quote:

> The landed tree differs from the hook-verified tree ONLY by paths under
> `product/`, which are not Gradle inputs and cannot change a test result; and
> `bin/format -c` — the only Markdown gate — has already passed independently on
> both halves: on the verified tree here, and on those doc commits' own way in
> to `main`.

Do **not** write "the tree that lands passed the hook". It did not. CLAUDE.md is
amended in this run to carry the exemption, because a documented rule the code
no longer follows is how the next agent is misled.

And the shape is not new. `ship-land` **already** tolerates one doc commit on
top of the hook-verified tree — its own `rfc:` commit, through
`ship-verified assert`'s doc-glob exemption, which exists because the RFC
markdown lands with `--no-verify` after the gate. This RFC **generalises an
existing, already-trusted exemption** rather than inventing one: the same claim
(Markdown outside the build cannot invalidate a hook result) applied to the
commits `main` gained instead of the commits this branch added.

## Detailed Design

### The predicate

`BASE_BRANCH` advanced past this run's tip. Let the advance be the commits in
`HEAD..<base tip>`. The advance is **docs-only** when, for **every** commit in
that range:

- it has exactly one parent (a merge or a root commit is not classified), and
- it changes at least one path, and
- every path it changes begins with the literal prefix `product/` and has at
  least one character after it.

**The contract is a PREFIX on the path string, not a glob**, and that is a
requirement rather than an implementation detail. Real doc traffic is nested
several levels deep — `product/0009-say-it-once/research/_parts/rfc-history.md`
— and a single-level `product/*` glob is exactly the shape that matches
`product/STATUS.md` and silently misses those. A predicate that fails closed is
safe, but one that fails closed for the commonest case is useless, and the
matching mode of a glob depends on the tool reading it. So the code tests
`case "$path" in "product/"?*)`, and the tests drive a three-level-deep path and
a `productX/` lookalike, one for each direction the test can be wrong.

Anything else — a code path, a merge, an empty commit, a failed `git` call — is
**not recognised**, and an advance containing one unrecognised commit is not
docs-only. The fallback is today's exact behaviour: refuse, and the run rebases
and re-runs the gate. **When in doubt, run the gate.**

**EVERY commit in the range, not the tip.** A `docs -> code -> docs` advance has
a docs-only tip and is **not** docs-only. This is the same shape as the
stale-`BASE_SHA` trap the ship skill already documents: a check on one end
sleeps through exactly the case it exists to catch. §Tests drives that ordering
specifically.

### `rfc/**` is deliberately NOT in the predicate

An earlier draft included it. Dropped, because the ship skill's own final-commit
ordering puts the `rfc:` doc commit immediately after its `feat:` code commit,
from the same run — as `main` itself shows:

    4a4bf350 16:33:30  feat: cite once per block (RFC 190)
    7de43df6 16:43:39  rfc: 190 — cite once per block
    0403f784 16:46:12  docs: product status after RFC 190

So a range containing an `rfc/**` commit almost always contains that run's code
commit too, making it a **mixed** advance that falls back anyway. Including
`rfc/**` buys almost nothing while carrying real rot risk: the day a test reads
an RFC as a fixture, the predicate is silently wrong. `product/**` alone covers
all four observed losses, is the traffic `slice` makes mandatory and unbounded,
and contains no build input by construction. **A narrower predicate that is
provably safe beats a wider one that needs a guard test to stay safe.**

### Where it lives: `ship-land`, one definition, one consumer

The predicate is a function inside `ship-land` with exactly one call site. It is
**not** factored into a helper `bin/pre-commit` can also call. The hook's job is
to **validate a tree**; this predicate is about **what invalidates an
already-validated result**. Those are different questions, and a shared helper
invites the hook to start skipping work — a far riskier change wearing the same
clothes.

### The sequence inside `ship-land`

Step 3 (`ship-verified assert`) must run **before** the rebase: the verified
tree is the pre-rebase tip's tree, and it does not survive a replay. The new
step 3a sits between the assert and the two generated-artifact land gates, so
those still run against the tree that actually lands:

    1.  clean worktree
    2.  the land lock (RFC 167)
    3.  ship-verified assert          ← the hook-verified tip, pre-rebase
    3a. docs-only fast path (NEW)     ← classify, then rebase without the gate
    3b. ship-order -n                 ← on the tree that lands
    3c. bin/prompt-seed -n            ← on the tree that lands
    4.  fast-forward the base branch   ← UNCHANGED

Step 3a, in full:

- If the base tip is already an ancestor of `HEAD`, nothing advanced: record
  `LAND_FAST_PATH=none` and continue. This is the common case and costs one
  `rev-parse` plus one `merge-base`.
- Otherwise classify every commit in the advance and print the classification.
- **Mixed or unrecognised** — record `LAND_FAST_PATH=none`, log why, and
  continue. Step 4's ancestry check then produces today's refusal, with today's
  message, unchanged.
- **Docs-only** — replay this run onto the tip **that was classified**, with a
  plain `git rebase "$BASE_TIP"`, then update `BASE_SHA` and rotate the
  checkpoint ledger exactly as a phase-6 rebase does. Record
  `LAND_FAST_PATH=docs-only`. No gate is re-run.

  Deliberately **not** `ship-rebase -s <rs>`, though it is the supported rebase
  everywhere else: `ship-rebase` re-resolves the base tip itself, and this
  traffic takes no lock, so between the classification and the replay the branch
  can gain a commit nobody looked at. Landing on an unclassified commit is
  precisely the hole this step closes.

- If a rebase or merge is **already in progress** in the worktree, the fast path
  is not taken at all and nothing is touched. The fallback below aborts, and
  aborting an operator's paused rebase — while reporting it as a conflict of
  this run's own — would destroy work the old code left alone while refusing
  cleanly at step 4.
- If that replay **fails** — a conflict, or anything else — `git rebase --abort`
  restores the worktree byte-for-byte and the run falls back to the refusal.
  Only a rebase this step actually started is aborted, and git's own stderr is
  printed, so a failure that was never a conflict is not reported as one. A
  conflict between this run and a `product/**` commit is a real disagreement and
  belongs in front of the operator, resolved by hand through the normal phase-6
  loop — not resolved mechanically inside the one script that reaches `main`.

### The fast path still confirms the fast-forward

Step 4 is untouched: `git merge-base --is-ancestor "$BASE_BRANCH" "$BRANCH"`
still runs from the original checkout, and the merge is still `--ff-only`, and
the post-land verify still checks the landed sha. Stated explicitly because
"re-validated" is the kind of word a later reader turns into "trusted". The fast
path skips **the gate**, not the ancestry check. Note in particular that the
docs traffic this RFC is about does not take the lock, so `main` can move again
between step 3a and step 4 — and when it does, step 4 refuses exactly as it does
today.

### Recording why the cheap path was taken

A land that skipped the gate and a land that ran it must be distinguishable
afterwards — the same argument RFC 183 makes for its withholding counters: a
rule that fired on 3 commits must not look like one that fired on 300. Every
`ship-land` invocation that reaches step 3a records:

- **Run state**: `LAND_FAST_PATH=docs-only|none` and, on the fast path,
  `LAND_FAST_PATH_COMMITS=<short-sha>,<short-sha>,...`. Both values are single
  tokens with no spaces, because `<run-scratch>/state` is **sourced** by every
  ship script and a value containing a space would execute its second word.
- **`<run-scratch>/land-fast-path.md`**: the full classification — every commit
  in the range, its verdict, its subject, and the paths that decided it.
- **`<run-scratch>/report.md`**: the same block, appended when the file exists.
  Phase 7 writes the report before `ship-land` runs, so the script appends to it
  rather than expecting the agent to have predicted the outcome.
- **stderr**, in both directions: the fast path says what it skipped and why,
  and the fallback says which commit made the advance mixed — which is the line
  that tells an operator their re-run was genuinely necessary.

The run scratch is archived to `.scratch/ship-archive/<run-id>/`, so all three
survive the worktree teardown.

## Open items — NOT built here

**The pre-commit hook could skip `bin/test check` for a staged tree that is all
`product/**`.** Same path glob, other end of the problem, and it would remove
the other half of the waste named in the summary. It is deliberately **not**
done here, and the two are **not symmetric**:

- `ship-land` skipping the gate is a **repeat** exemption: the check it declines
  to re-run has already PASSED on that tree. It declines a redundant repeat.
- the hook skipping `bin/test check` is a **first-run** exemption: the claim
  that the check need never run at all for that commit. Probably true for
  `product/**`, but it is a different claim, and it deserves its own RFC and its
  own argument.

A concurrent run holds `pipeline/rfc-194` for exactly that change at the time of
writing, so the two halves are being designed side by side and neither is a
rediscovery of the other.

This is also why the "one definition, one consumer" decision above is not
short-sighted. The follow-on is not a second caller of this predicate; it is a
different question that happens to share a path glob. If the two ever converge
on one definition, that must be the deliberate outcome of the second RFC, not an
accident of this one.

## Files Modified

- `.prime/agent/skills/ship/scripts/ship-land` — new step 3a, the classifier
  function, the recording, and the fallback.
- `bin/ship-scripts-tests` — a new section of assertions over the fast path,
  driven by a real throwaway repo and worktree.
- `.prime/agent/skills/ship/SKILL.md` — one paragraph in phase 6 stating that a
  docs-only advance is landed without a second gate run, and that anything else
  still costs a rebase and a re-run.
- `rfc/193-docs-only-land-fast-path.md` — this file.

Not modified: `bin/pre-commit`, `ship-lock`, `ship-verified`, `ship-rebase`, and
anything under `.claude/**`.

## Implementation Plan

1. Add `land_fast_path` to `ship-land`: classify the advance, print the table,
   return 0 only when every commit qualifies.
2. Wire it as step 3a between the assert and the `ship-order` gate, with the
   `ship-rebase` call, the `--abort` fallback, and the recording.
3. Extend `bin/ship-scripts-tests` with the fixture and the assertions below.
4. Run `nix develop -c bin/shell-tests` until green, then the full
   `nix develop -c bin/test`.

The change is **additive and fail-safe by construction**: every path that is not
positively recognised as docs-only falls through to the code that runs today.
This is the script every other run lands through, and `pipeline/rfc-192` and
`pipeline/rfc-178` are live while it changes.

## Tests

New assertions in `bin/ship-scripts-tests`, against a throwaway `git init` repo
with a real worktree, a fabricated run scratch, and a hand-written
`ship-verified-trees` marker — no Gradle, no database, no hook:

1. **A docs-only advance lands with no second gate run.** `main` gains two
   `product/**` commits — one of them **three levels deep**
   (`product/0009-brief/research/_parts/history.md`), which is what pins the
   prefix contract against a single-level glob — and `ship-land` fast-forwards a
   tip that contains both those commits and this run's work. A `productX/a.md`
   commit, the same test in the other direction, refuses.
2. **`docs -> code -> docs` REFUSES.** The trap case: a docs-only tip over a
   code commit. The run does not land, and the state records
   `LAND_FAST_PATH=none`.
3. **An `rfc/**`-only advance refuses.** `rfc/**` is not in the predicate, by
   design, and this is the assertion that says so.
4. **An empty commit is not docs-only.** A commit that changes no path matches
   "every path is `product/**`" vacuously; the predicate must require at least
   one path. Fail-safe, not vacuous truth.
5. **A merge commit is not docs-only**, even when its diff is all `product/**` —
   one parent, or it is not classified.
6. **The recording exists**: after a fast-path land, the archived run state has
   `LAND_FAST_PATH=docs-only` with the short shas, and `land-fast-path.md` names
   every commit and its verdict.
7. **A conflicting replay aborts and falls back**: the run's HEAD is unmoved, no
   rebase is left in progress, the worktree is clean, and the recorded verdict
   is `none`. Two runs editing one `product/**` file is a real disagreement, and
   a wedged worktree would be the operator's problem the moment they went to fix
   it.
8. **A base that never moved still lands**, recorded as no fast path. The
   cheapest way to break every land in the repo is a new step that fires when
   nothing advanced.
9. **The generated-artifact land gates run AFTER the replay.** A
   `bin/prompt-seed` stub records the `-b` it was handed, and the recorded base
   is the POST-replay tip. Without this, step 3a could be moved below those
   gates and every other assertion here would still pass.
10. **A leading-space `" product/x.txt"` path refuses.** `read` without `IFS=`
    strips the space, and a directory literally named `" product"` then reads as
    `product/` and skips the gate. Found in review, demonstrated, and fixed with
    `while IFS= read -r path`.
11. **A worktree already mid-rebase is left untouched**, and reported as busy
    rather than aborted.
12. **The existing foreign-holder and land-gate assertions still pass**, which
    is the evidence the change is additive.

Measured: `nix develop -c bin/ship-scripts-tests` goes from 56 to **68**
assertions, all green, and needs no database, no Gradle and no hook.
