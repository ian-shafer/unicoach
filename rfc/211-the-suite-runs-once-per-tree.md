# RFC 211: The suite runs once per tree

## Summary

A `/ship` run currently proves the same thing about the same tree up to four
times: an implementation child runs the suite, phase 5 runs it again (and, in
practice, `bin/test check` on top of it), and then `bin/pre-commit` runs
`bin/format -c` + `bin/test check` again at the `git commit`. This RFC makes the
split explicit:

- phase 5 **verifies** with plain `nix develop -c bin/test` — no module
  argument, no `-f`, exactly as CLAUDE.md prescribes ("let the cache do the
  scoping");
- phase 5 **does not** run `bin/test check`, and runs no shell harnesses at all
  — a deliberate, temporary gap with a named successor (D2);
- phase 5 **reuses** an implementation child's green suite when the git TREE has
  not moved in a way the suite could notice, instead of re-running it because a
  child reported it (D2b);
- the **pre-commit hook is the single authoritative `check` run**, and it is the
  run whose result is claimed.

Nothing changes about WHICH checks run on what reaches `main`. Only WHEN, and
how often.

## Motivation: measured, not estimated

From run rfc-208's own logs, 2026-09-14, in `.scratch/ship/rfc-208/impl/`
(archived with that run):

| clock       | what ran                                                                                                                        | result                                               |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| 13:10–13:22 | implementation-phase harness and Gradle runs (`scripts-tests.log`, `shell-tests.out`, `service.log`: `BUILD SUCCESSFUL in 55s`) | the tree was already being tested here               |
| 14:35       | waited out other checkouts' Gradle runs                                                                                         | the machine never went quiet                         |
| 14:36       | `nix develop -c bin/test`                                                                                                       | exit 0, `BUILD SUCCESSFUL in 4m 26s`, finished 14:41 |
| 14:41       | `nix develop -c bin/shell-tests` standalone                                                                                     | exit 1 at 14:49 — **not a test failure**, see D2     |
| 14:50       | `nix develop -c bin/test check`                                                                                                 | exit 0 at 15:05 — ~15 min under multi-checkout load  |
| then        | `bin/pre-commit` runs `bin/format -c` + `bin/test check` AGAIN on that same tree                                                | the hook does this by definition                     |

Four passes over one unchanged tree, and the first of them happened during
implementation, before the table above even starts.

The Gradle half of the 14:50 run reported `96 actionable tasks / 96 up-to-date`
— it re-executed nothing and still cost the wall clock, because `check` also
builds the two `installDist` artifacts and runs every shell harness. The hook
then answered the identical question a fourth time.

Roughly 30 minutes passed between "code written" and "landed", and the last two
suite runs were about the same bytes.

**This is now confirmed by observation, not inference.** On 2026-09-14 at 16:30
a slice landed deliberately in this RFC's shape: no phase-5 rehearsal, no
`bin/test`, no `bin/test check`, no `bin/shell-tests` before committing. The
hook fired by `git commit` was the single authoritative run. It landed in **11m
03s**, one gate run, under real load (load average 3.23 / 3.38 / 5.47, three
other checkouts live). Gradle's verdict inside that hook run, verbatim, both
occurrences:

    BUILD SUCCESSFUL in 7s    41 actionable tasks: 41 up-to-date
    BUILD SUCCESSFUL in 4s    96 actionable tasks: 96 up-to-date

Both `installDist` tasks up to date. **Zero test tasks re-executed. Gradle
contributed 11 seconds of those 11 minutes.** So the ~15-minute `bin/test check`
that rfc-208 ran in phase 5 changed no answer whatsoever — which is D1, observed
rather than argued.

The same run was sampled externally, every 10s, without touching the tree. The
gate's own shape:

| phase within the single hook run   | time    | share |
| ---------------------------------- | ------- | ----- |
| `bin/shell-tests` total            | 9m 05s  | 82%   |
| — `db-scripts-tests`               | 4m 03s  |       |
| — `scripts-tests`                  | 3m 52s  |       |
| — of which `tofu init`+`validate`  | 1m 21s  |       |
| — the other five harnesses         | ~1m 10s |       |
| Gradle phase, including `db-reset` | 1m 26s  |       |
| `ship-land` fast-forward           | 2s      |       |

That table is not this RFC's business to fix — a separate run owns the hook's
harness cost — but it is why D2 is deferred rather than landed here.

**A late measurement that reorders the whole motivation.** While this RFC was
waiting to land, its own two land attempts both died on one assertion —
`tofu -chdir=infra validate` in `bin/scripts-tests` — after 561s and 485s, each
with 1,019 other assertions passing. The cause was not test volume at all: that
step ran `tofu init` with no plugin cache, so every gate in this repo DOWNLOADED
the `hashicorp/aws` provider from the registry and deleted it, with both
commands sent to `/dev/null` so the failure printed nothing. It had been filed
as a flake. Fixed in `2cfe0be9`; the step is now 1-2s and offline, and prints
its output on failure.

Counting rfc-208's occurrence, that one silent download cost **three gate runs,
roughly 26 minutes, in a single day** — on diffs that were Markdown and
Markdown-adjacent. That is a larger measured waste than anything this RFC
removes. It does not change a single decision here, and it is recorded because
it corrects the frame: the expensive thing about the gate was never the number
of tests. It was a network call nobody could see, inside the one check that had
been taught to fail quietly.

The waste is structural, not a mistake by that run. Phase 5's job is "is this
tree good?" and the hook's job is "is this tree good?", and until now the skill
did not say which of them is the ANSWER.

## The claim about `main` does not weaken

This is the requirement the whole RFC is built around, so it is stated plainly
rather than implied, and stated PRECISELY rather than approximately.

**What reaches `main` is still hook-verified.** `bin/pre-commit` still runs
`bin/format -c` and the full `bin/test check` — Gradle, Postgres, the two
`installDist` builds, and every shell harness. `ship-land` still runs
`ship-verified assert` before the fast-forward, and still refuses a fast-forward
that is not that commit. The set of checks that must pass before code lands on
`main` is byte for byte what it was yesterday.

**One precision, because the loose version of this sentence is a real defect.**
The hook validates the **WORKING TREE**, not the index — CLAUDE.md says so ("the
hook validates the whole working TREE rather than the commit"), and RFC 194 says
so. The commit is built from the INDEX. What ties the two together is
`ship-verified record`'s own guard, quoted from the script:

> The hook validates the WORKING TREE; the commit is built from the INDEX.
> Normally identical. If not, we tested something other than what is being
> committed, so record nothing and say so.

So the honest claim is: the hook validates the working tree, and a commit whose
index differs from that tree gets no verified marker, so `ship-land` refuses it.
Writing "the staged tree" would delete exactly the hedge that makes the
guarantee true. D2c below is a case where that difference is not hypothetical.

What changes is only that the run stops paying for a rehearsal of that gate
minutes before the gate itself.

## What is given up, honestly

`bin/test` and `bin/test check` do not cover the same ground. `check` adds:

- the `rest-server` and `queue-worker` `installDist` builds, and
- `bin/shell-tests` (RFC 115), which runs **seven** harnesses, in this order:
  `ship-scripts-tests`, `prompt-seed-scripts-tests`, `docs-scripts-tests`,
  `test-calendar-seed-scripts-tests`, `q-scripts-tests`, `db-scripts-tests`,
  `scripts-tests`.

Seven, not four. CLAUDE.md still carries a stale four-name list, and the first
draft of this RFC copied it. Measured now in this worktree, `grep -c 'assert_'`
gives 19 / 27 / 33 / 27 / 32 / 121 / 622 = **881 assertion call sites**, of
which `scripts-tests` alone holds 622. The figure quoted at runtime is larger
(rfc-208's `bin/test check` printed a 1,616 total) because loops execute a call
site many times. Both numbers are real; they count different things, and this
RFC does not use "~280", which was the stale copy.

Getting the set right is not pedantry: a procedure keyed on four names has no
answer for a `docs-scripts-tests` failure, and `docs-scripts-tests` is the gate
on `bin/pre-commit`'s own root-checkout refusal — this skill's own land path.

So a failure **only `check` can catch** is now discovered **at commit time**
rather than before it, except where D2 applies.

The trade, stated as a cost and a saving:

- **Cost when it happens:** one wasted hook run (the full `check`, 2–4 min cold,
  10–16 min under load), plus the land lock held and released, plus a fix loop
  that re-enters phase 6 at the top.
- **Saving when it does not:** 10–20 minutes on **every** run, which is the
  overwhelming majority of runs, since most `/ship` diffs are Kotlin, SQL, or
  Markdown and cannot touch a shell harness at all.

Nothing in phase 5 softens that bet any more. D2 was to have done so; it is
deferred, and the successor is named in D2 below.

## Decisions

### D1 — phase 5 verifies with plain `bin/test`, and must not run `check`

**Decided: yes.** The skill already said `nix develop -c bin/test`; rfc-208 ran
`bin/test check` anyway, because the skill called its `bin/test` "the real gate"
while the hook — which the same skill elsewhere admits "re-runs the tests" — was
the thing actually being trusted. An instruction that is merely silent about the
stronger form invites it. The skill now says which run is the answer, and says
explicitly that `bin/test check` in phase 5 is not wanted.

`bin/test` with no module and no `-f` is the form: Gradle's dependency graph and
input hashes pick the tasks the diff actually invalidated. The executed count
from the JUnit XML `tests=` attribute is the evidence, as before — never
`grep -c "@Test"`, which counts commented-out annotations.

### D2 — DEFERRED. Phase 5 runs no shell harnesses, deliberately

**Decided: cut from this RFC, and named rather than dropped silently.**

Earlier drafts of this decision ran shell harnesses in phase 5 for a diff that
could break one — first all seven, then a targeted subset chosen from a
path-to-harness table. Both are removed. With D1 in force, **phase 5 runs no
shell harnesses at all**: `bin/test check` is the only thing that invokes
`bin/shell-tests`, and the hook is the only thing that invokes `bin/test check`.

**What that costs, stated plainly.** A diff touching `bin/`, `infra/`,
`.prime/agent/skills/`, `db/schema/`, `db/seed/`, `db/data/`, `prompts/` or
`.env*` now gets NO harness signal until the commit. When one of those breaks a
harness, the run discovers it at the gate and pays one wasted hook run. D3 is
the procedure for exactly that. This is accepted deliberately and temporarily.

**Why deferred, and it is sequencing rather than a verdict on the design.**

1. **Two tables, one fact, and the drift is asymmetric.** A separate RFC is
   attacking the hook's own shell-harness cost, and it must build the same
   path-to-harness mapping _in the hook_. A wrong map in phase 5 fails SILENTLY,
   because the hook catches what it missed; a wrong map in the hook does not. So
   the duplicated copy that gets exercised and trusted would be the one whose
   errors are invisible. This repo has already shown that failure mode:
   CLAUDE.md's stale four-harness list and stale "~280 assertions" figure, both
   of which an earlier draft of THIS RFC copied before review caught them.
2. **The insurance is about to get cheaper.** D2's value is roughly P(diff
   breaks a harness) x (cost of a wasted hook run). The hook-scope work cuts
   that second term substantially. Buying insurance at full price while the loss
   is being made four times smaller is bad sequencing.
3. **It carried nearly all the new machinery for the smallest saving.** The
   `BASE_SHA` footprint with one revision, `--no-renames`, a seven-row table,
   conditional `installDist`, conditional `postgres-up`, a fallback rule, daemon
   side effects, a dirty test DB. D1 is a DELETION and cannot be wrong; D2b is
   ~40s and keyed on a tree hash with an existing precedent. D2 was, by its own
   admission, "the third version of this decision, because the first two were
   wrong and the measurement proved it". That is the part to land last, not
   beside a deletion.

**The successor, named.** The mapping is to be built ONCE by the hook-scope RFC,
as a real artifact: one script, fail-closed on an unrecognised path AND on any
new `bin/*scripts-tests` it does not name — mirroring the anti-orphan guard
already in `bin/scripts-tests` — with its own tests. Phase 5 may then re-add
targeting by CALLING that script. It must never carry a second table.

**One correction to leave behind for that work.** `bin/shell-tests`'s help
publishes per-harness costs (~6s / ~3s / 4.6s / ~2s / ~12s / ~15s / ~63s, about
105s in total). Those are quiet-machine figures. Measured inside a real gate run
under multi-checkout load, `db-scripts-tests` took 4m 03s and `scripts-tests` 3m
52s, of which 1m 21s was `tofu init`+`validate`. Do not present the help's
figures as what a loaded machine will do.

### D2b — a green suite from a child is not re-run because it came from a child

**Decided: reuse it, keyed on the TREE, and make the record mechanical.**

The second duplicate run is not the hook at all: it is phase 5 re-running a
suite an implementation child has just run on the same bytes. The skill already
says "take the child's report at face value; re-verify only where **nothing**
reported", and then phase 5 re-runs the suite unconditionally anyway. That is
distrust dressed as diligence.

It would be overclaiming to call the suite deterministic — it is DB-backed and
daemon-touching, and CLAUDE.md itself names `-f` as the tool for "hunting a
flake". The honest argument is narrower and still sufficient: a second run of a
FLAKY suite over identical bytes is weak evidence, it is not free, and the
authoritative run — the hook — happens minutes later regardless.

**And the saving here is seconds, not minutes. Measured on this run, and it
corrects the obvious sales pitch for this decision.** The same
`nix develop -c bin/test`, in the same worktree, immediately after the first:

| run                              | wall clock | Gradle                                            |
| -------------------------------- | ---------- | ------------------------------------------------- |
| first in a fresh worktree        | 4m 29s     | `96 actionable tasks: 56 executed, 40 from cache` |
| second, Markdown-only diff since | **42s**    | `BUILD SUCCESSFUL in 5s`, `96/96 up-to-date`      |

So Gradle's own up-to-date checking already does most of what D2b does, and it
does it more finely: the second run executed ZERO tests and spent its 42 seconds
on JVM start, the dev shell, `db-reset` and migration. D2b's real saving is that
fixed overhead — roughly 40 seconds — not a suite.

That is worth having and it is not the headline. **The headline saving is D1**:
not running `bin/test check` in phase 5 saved rfc-208's ~15 minutes. D2b is
recorded at its true size deliberately, because a decision oversold once is
distrusted afterwards.

**The identity is the git TREE hash, not the commit SHA.** This repo already
made that call in `ship-verified`: "amending a message, re-committing identical
content, or rebasing all preserve the tree, so the marker survives exactly the
operations that do not change what was tested and fails exactly those that do."

#### The record must be produced BY the run, not narrated by it

Review found the first draft's fatal weakness: it asked the child to REPORT a
tree hash, while claiming "no part of this infers a green from a child's prose".
A hash retyped into a message is prose. A child that runs the suite green, fixes
a typo, checkpoints, and honestly reports the post-edit hash produces a record
for bytes that were never tested.

So the child does not report the triple; it EMITS it, from the same shell as the
suite, after the checkpoint:

    scripts/ship-checkpoint -s <rs> impl <n> -m "..."
    nix develop -c bin/test > <rs>/suite.log 2>&1 ; echo "exit=$?" >> <rs>/suite-green.txt
    { echo "cmd=nix develop -c bin/test"
      echo "tree=$(git rev-parse HEAD^{tree})"
      echo "xml=$(git rev-parse --show-toplevel)"
    } >> <rs>/suite-green.txt

The tree hash is then git's answer about the tree the command ran against, in
the same shell, with no human in the loop. The child still REPORTS the outcome
in prose, for Ian; phase 5 reads the file.

#### The predicate

At the point phase 5 would run the suite — after the tier fixes, which is where
the tree stops moving within phase 5 — the run checks FOUR things, not two:

    git status --porcelain            # empty
    git rev-parse HEAD^{tree}         # equals tree= in <rs>/suite-green.txt
    # exit=0 in that file
    # cmd= is exactly "nix develop -c bin/test"

All four: **do not re-run**; cite the record, naming the tree hash, and
re-derive the executed count from the JUnit XML rather than from the child's
sentence. Anything else — a different tree, a dirty file, a non-zero exit, a
module-scoped or `-f` command, a missing or unreadable record — runs the suite.
The default answer is always "run it".

The `cmd=` check exists because a child that ran `bin/test rest-server` or
`bin/test -f db` produces an equally valid-looking record, and reusing it would
be strictly weaker than today while contradicting CLAUDE.md's "do not scope by
module".

#### One narrow widening, for the paths that are provably not build inputs

Keyed on the raw tree hash alone, D2b fires only when literally nothing happened
between the child and the gate — and a one-word fix to an RFC Markdown file
would force a full suite re-run. RFC 194 already established why that is silly:
`product/**` and Markdown are not Gradle inputs, so `bin/test` cannot change its
answer for them.

So when the tree differs, the run asks what differs:

    git diff --name-only <recorded-tree> HEAD

If every path is under `product/`, or is `rfc/*.md`, or is a `*.md` under
`.prime/agent/skills/`, the Gradle suite's answer cannot have moved and the
record is still reused. One path outside that set re-runs.

This is the same reasoning as RFC 194, applied to a different decision, and it
is deliberately NOT factored into a shared predicate — RFC 194 says why: a
shared helper would imply the two questions are one question. Note also that a
Markdown file under `.prime/agent/skills/` is not a Gradle input but IS a
`ship-scripts-tests` subject, which is why it appears here and also in D2's
trigger set. The two lists answer different questions and are meant to disagree.

#### Two limits, stated rather than glossed

1. **This reuses a result, it does not lower a bar.** The reused run is the same
   `nix develop -c bin/test` over bytes Gradle cannot distinguish, reported with
   the same exit code and the same JUnit `tests=` count. The hook still runs the
   full `check` afterwards, unchanged.
2. **The tree can still move after the decision** — phase 6 runs
   `bin/prompt-seed`, `ship-order`, `bin/format` and a rebase, all inside the
   lock. That is fine, and is the reason phase 5's suite is not the authority:
   the hook runs after all of them, on the tree that lands.

### D2c — `bin/format` writes, and what it writes MUST be re-staged

**Decided: yes, it already writes — and the sequence had a real hole.** Checked
in the code rather than assumed: phase 6 runs `nix develop -c bin/format` in
WRITE mode before `git commit`, and `bin/format` without `-c` runs
`ktlint --format` and `deno fmt`, which rewrite files. So a stray unformatted
Markdown file does NOT cost a gate run. That part was correct.

The hole is the ORDER:

    scripts/ship-squash -s <rs>      # git reset --soft BASE_SHA — run is STAGED
    nix develop -c bin/format        # rewrites files in the WORKING TREE
    nix develop -c git commit        # commits the INDEX

`ship-squash` is `git reset --soft`, so the run's work sits in the INDEX.
`bin/format` then rewrites the WORKING TREE. Nothing re-stages the rewrite, so
the commit carries the UNFORMATTED bytes while the working tree is formatted.

Nothing in the gate catches it, and it fails in the most expensive way:

- The hook's `bin/format -c` checks the **working tree**, which is now correctly
  formatted. It passes. It cannot see the index.
- `bin/test check` passes too.
- `ship-verified record` hits its own guard —
  `working tree differs from index — not recording a verified tree` — and
  records NOTHING. One stderr warning inside an otherwise successful hook run.
- `ship-land` calls `ship-verified assert`, finds no marker, and REFUSES. The
  full gate has already been spent.
- And if a run ever did get past it, `main` would carry unformatted bytes, so
  the NEXT run's whole-tree `bin/format -c` would fail on a file it never
  touched.

The fix is one line, and the narrow instrument rather than the broad one:

    nix develop -c bin/format
    git add -u                       # re-stage what the formatter rewrote
    nix develop -c git commit

`git add -u` stages modifications to files git already tracks. A formatter
cannot create a file, so `-u` is exactly sufficient; `git add -A` would
additionally sweep up untracked files, which is not what this step is for.

This is not an exemption and not a new check. It closes a gap where the
committed content could differ from the content the gate examined — a stronger
guarantee than before, not a weaker one.

### D3 — what a run does when the hook fails on a shell harness

Written down here so the answer exists before it is needed. A procedure, not a
judgement call at the gate.

1. **Release the land lock first**, before diagnosing anything:
   `scripts/ship-lock -s <rs> release`. A run that stops to think while holding
   the lock blocks every other run for up to 15 minutes.
2. **Capture the failing assertion VERBATIM** to
   `<rs>/findings/hook-shell-failure.txt`, with the harness name and its section
   header. The worktree is deleted at land; the hook's output is the only
   record.
3. **Classify, in this order.**
   - _Environmental_ — a missing `installDist` artifact, a cluster that is down,
     a daemon another checkout stopped. Fix the environment, change no code,
     re-enter phase 6 at the top.
   - _This run's defect_ — fix, `ship-checkpoint`, and re-run ONLY the harness
     that failed, **by its own name**: `bin/ship-scripts-tests`,
     `bin/prompt-seed-scripts-tests`, `bin/docs-scripts-tests`,
     `bin/test-calendar-seed-scripts-tests`, `bin/q-scripts-tests`,
     `bin/db-scripts-tests`, `bin/scripts-tests`. Note the last one has no
     prefix; a `bin/<name>-scripts-tests` template cannot name it, and it is the
     largest and likeliest of the seven. A targeted re-run, not the full gate —
     the full gate is the hook, and it runs next anyway.
   - _Pre-existing and not this run's_ — confirm it fails at `BASE_SHA` too,
     from the original checkout, then **stop and report to Ian**. Repairing an
     unrelated harness inside a land is a new run with its own approval.
4. **Re-enter the phase 6 block at the top** — `ship-lock acquire`, then
   `ship-rebase` — never at the `git commit`. `main` may have moved while the
   lock was down.
5. **Never `--no-verify` past it.** The `--no-verify` commits this repo permits
   are enumerated, and this is not one of them.

### D4 — `bin/format -c` closes verify, but only for a Kotlin-bearing diff

**Decided: yes, narrowed.** The first draft justified this with "another
session's unformatted file in the same worktree fails your commit", which review
correctly called stale: RFC 201 gives every writer its own worktree.

The second draft kept it unconditional at ~7s. Questioned again, it does not
survive unconditionally either. Phase 6 already runs `bin/format` in WRITE mode
before the commit, and `deno fmt` is exhaustive for Markdown — the write fixes
everything the later check would report. A Markdown-only run learns nothing from
a check afterwards. It is ceremony.

Kotlin is the case that pays. `ktlint --format` auto-corrects what it can and
leaves what it cannot, so a non-correctable violation survives the write and
then fails `bin/format -c` INSIDE the hook — where it runs concurrently with
`bin/test check` and therefore discards a 10-20 minute suite run alongside it,
inside the land lock.

So the rule is: run it when the run's footprint contains `.kt` or `.kts`, and
not otherwise. Measured on this run: `bin/format -c` took **7.07s** and FAILED,
on two Markdown files this RFC introduced — which is also the evidence that
write mode, not check mode, is what prevents the waste.

### D5 — the two existing exemptions are untouched

Neither is re-litigated and neither is weakened:

- **RFC 193** — `ship-land` replays onto a base advance that is
  `product/**`-only instead of re-running the gate. A REPEAT exemption, about
  history.
- **RFC 194** — a commit whose staged paths are ALL under `product/` skips the
  test half. A FIRST-RUN exemption, about the dependency graph.

**This RFC is neither.** It adds no exemption: it removes a REHEARSAL of a gate
that still runs, in full, unchanged, on the tree that lands. RFC 193 and RFC 194
change what the hook does; this changes only what a run does before reaching the
hook. No `ship-*` script reads `suite-green.txt`, and nothing outside
`bin/pre-commit` calls `ship-verified record`.

## Detailed Design

The change is entirely in `.prime/agent/skills/ship/SKILL.md`. There is no code
change, no script change, and no change to `bin/pre-commit`, `bin/test`,
`ship-land`, `ship-lock` or `ship-verified`.

### Phase 4 (implement) — a child's suite run becomes citable

The existing sentence "take the child's report at face value; re-verify only
where **nothing** reported" gains the mechanism that makes it true of the suite
as well: a child that runs `bin/test` runs it after `ship-checkpoint` and
APPENDS the command line, exit code and `git rev-parse HEAD^{tree}` to
`<rs>/suite-green.txt` from the same shell.

### Phase 5 (verify) — the gate sentence is replaced

Today phase 5 ends with one sentence calling `nix develop -c bin/test` "the real
gate". It becomes a scoped instruction with four parts:

1. `nix develop -c bin/test` — plain, no module, no `-f`, dispatched to a child,
   reporting exit code and the executed count from the JUnit XML `tests=`
   attribute. SKIPPED when the D2b predicate holds; run in every other case.
2. An explicit prohibition on `bin/test check` and `-f` in phase 5, naming the
   reason: the hook is the authoritative `check` run and it is minutes away.
3. A plain statement that phase 5 runs NO shell harnesses, why that gap is
   accepted for now, and the successor that closes it.
4. `nix develop -c bin/format -c` as the last read-only step of verify.

### Phase 6 (land) — the hook is named as the authority

The existing parenthetical "`# code — through the FULL hook. The gate.`" stays.
`git add -u` is inserted between `bin/format` and `git commit`, with D2c's
reason. The prose around the lock, which today says the hook "re-runs the
tests", is corrected: it does not re-run them, it IS the run. A short paragraph
states the `main` claim at its true strength (working tree, plus
`ship-verified record`'s index guard) and points at the D3 block below it by
name.

## Files Modified

| file                                      | change                                                                                                                                                                                                                                               |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rfc/211-the-suite-runs-once-per-tree.md` | new — this file                                                                                                                                                                                                                                      |
| `.prime/agent/skills/ship/SKILL.md`       | phase 4 suite-record contract added; phase 5 verify block rewritten with the D2b predicate and an explicit statement that no shell harness runs there; phase 6 lock prose corrected, `git add -u` added, and a shell-harness failure procedure added |

Explicitly NOT modified: `.claude/**` (non-negotiable 1), `bin/pre-commit`,
`bin/test`, `bin/functions`, and every `scripts/ship-*`.

CLAUDE.md's stale four-harness list is noted above but deliberately left alone:
it is a documentation fix with a different blast radius, and folding it in here
would mix a skill-contract change with a repo-wide doc edit.

## Implementation Plan

1. Write `rfc/211-the-suite-runs-once-per-tree.md` (this file).
2. Edit `.prime/agent/skills/ship/SKILL.md`: the phase 4 record contract, the
   phase 5 verify block, the phase 6 `git add -u` and lock prose, and the D3
   procedure.
3. `nix develop -c bin/format` (write), then `bin/format -c`. MEASURED: the
   read-only check took **7.07s** and FAILED on two Markdown files this RFC
   introduced — D4 paying for itself on its first use.
4. `nix develop -c bin/test` — plain, per D1. MEASURED, twice, in the same
   worktree:

   | run                              | wall clock | Gradle                                            | tests                                           |
   | -------------------------------- | ---------- | ------------------------------------------------- | ----------------------------------------------- |
   | first, fresh worktree            | 4m 29s     | `96 actionable tasks: 56 executed, 40 from cache` | 3,286 executed, 0 failures, 0 errors, 0 skipped |
   | second, Markdown-only diff since | 42s        | `BUILD SUCCESSFUL in 5s`, `96/96 up-to-date`      | 0 executed                                      |

   The first number is a correction worth recording, because the obvious
   prediction was wrong. The diff is Markdown only and touches no Gradle input,
   so "let the cache do the scoping" predicts a no-op. It was not: a `/ship` run
   works in a FRESH WORKTREE with its own empty `build/`, so the first
   `bin/test` in it re-executes regardless of the diff. The cache supplies
   compilation, not test EXECUTION. Incrementality starts from the SECOND run in
   a worktree.

5. The shell harnesses were nonetheless RUN on this tree while D2 was still in
   the RFC, and the evidence is kept because it stands on its own:

   | what was run                                                 | wall clock | result                               |
   | ------------------------------------------------------------ | ---------- | ------------------------------------ |
   | builds + `postgres-up` + all seven harnesses                 | 674s       | exit 0, 1,453 assertions, 0 failures |
   | `bin/ship-scripts-tests` alone — the one this diff can break | 15s        | exit 0, 68 assertions, 0 failures    |

   So this tree is independently known green on all seven harnesses, and the 45x
   gap between the two rows is the measurement that argued for targeting. With
   D2 deferred, that gap is an input to the successor RFC rather than a rule
   here.

6. Commit through the full hook (`nix develop -c git commit`), which runs
   `bin/format -c` + `bin/test check`, then `ship-land`.

## Tests

There is no Kotlin or shell code in this change, so there is no new automated
test and inventing one would be dishonest. The change is verified four ways.

1. **The hook itself.** This run's `git commit` runs `bin/format -c` +
   `bin/test check`. That is the unchanged gate, and its exit code is the
   evidence that the claim about `main` still holds.
2. **The executed Kotlin count**, from the JUnit XML `tests=` attribute of the
   hook's run — never `grep -c "@Test"`. Reported in the run report.
3. **The harnesses run on this tree anyway**, while D2 was still in the RFC: all
   seven green, 1,453 assertions, 674s; and the single harness this diff can
   break green in 15s. D2 itself is deferred, so there is no new phase-5 trigger
   left to exercise.
4. **D2b's predicate exercised in BOTH directions, with real hashes.** Recorded
   tree `dc5e00eb`, exit 0, `cmd=nix develop -c bin/test`. After two further
   revisions the tree is `363ababd` and the worktree is clean, and
   `git diff --name-only dc5e00eb HEAD` returns exactly
   `.prime/agent/skills/ship/SKILL.md` and
   `rfc/211-the-suite-runs-once-per-tree.md` — both Markdown, neither a Gradle
   input — so the predicate says REUSE. Adding a single blank line to
   `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` produces tree
   `4d5bf022`, whose diff against the record lists that `.kt` file, and the
   predicate says RUN. The edit was reverted immediately; the worktree returned
   clean. A predicate whose two branches were never both observed is a predicate
   with one branch.

**A before/after wall-clock measurement** accompanies them, on one real slice,
with the contention conditions stated, because a suite time on this machine is
meaningless without them.

The honest limit of that measurement: rfc-208 and this run have different diffs,
so the comparison is of PROCESS cost, not of two runs of the same code. The part
that is apples-to-apples is the count of suite passes per landed tree — rfc-208
spent four over one tree, and this contract spends at most two (one `bin/test`,
reused wherever the tree has not moved in a way Gradle can see, and the hook's
`check`).
