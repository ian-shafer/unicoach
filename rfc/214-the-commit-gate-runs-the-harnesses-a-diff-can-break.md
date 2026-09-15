# RFC 214: The commit gate runs the harnesses a diff can break

## Summary

`bin/pre-commit` is the single authoritative suite run in a `/ship` (RFC 211).
Measured over three lands on 2026-09-14 it costs 11-14 minutes, and ~82% of that
is `bin/shell-tests` — every harness, on every commit, whatever the diff.

Gradle already solves this for the Kotlin half by hashing its declared inputs.
The shell half has no equivalent: there is no input graph, so `bin/shell-tests`
runs everything.

This RFC builds the missing map, as the successor RFC 211's deferred D2 names by
hand: **ONE script, `bin/gate-scope`, fail-closed, with its own harness**, which
answers one question — _given this staged set, what must run?_ — and is called
by `bin/pre-commit`. `/ship` phase 5 may later call the same script; it must
never carry a second copy of the table.

It also takes the OpenTofu checks out of the gate entirely, on an operator
decision, and says plainly what that costs.

## Motivation

The gate's own shape, sampled externally every 10s during the `rfc-208` land,
load average 3.23 / 3.38 / 5.47:

| phase within the single hook run   | time    | share |
| ---------------------------------- | ------- | ----- |
| `bin/shell-tests` total            | 9m 05s  | 82%   |
| — `db-scripts-tests`               | 4m 03s  |       |
| — `scripts-tests`                  | 3m 52s  |       |
| — the other harnesses              | ~1m 10s |       |
| Gradle phase, including `db-reset` | 1m 26s  |       |
| `ship-land` fast-forward           | 2s      |       |

Gradle's own contribution inside that 11m 03s was **11 seconds**, verbatim:

    BUILD SUCCESSFUL in 7s    41 actionable tasks: 41 up-to-date
    BUILD SUCCESSFUL in 4s    96 actionable tasks: 96 up-to-date

**RFC 211's own land is the case this RFC exists for.** Its diff was one skill
file and one Markdown file — `.prime/agent/skills/ship/SKILL.md` and
`rfc/211-*.md`. It paid a full Gradle-plus-every-harness gate for that diff
THREE times: 561s + 485s + 781s, about 30 minutes, for 761 lines of prose.
Nothing in that diff is a Gradle input, and nothing in it is read by any
harness.

### The baseline this RFC is measured against

Measured for this RFC, in the `rfc-214` worktree, each harness timed
individually and serially, load average 3.2-3.4 throughout with a peak of 8.05
during `db-scripts-tests`. Assertion counts are each harness's own printed
total, cross-checked against its `✅` lines.

| harness                                   | wall | assertions |
| ----------------------------------------- | ---- | ---------- |
| `ship-scripts-tests`                      | 16s  | 68         |
| `prompt-seed-scripts-tests`               | 12s  | 76         |
| `docs-scripts-tests`                      | 5s   | 48         |
| `test-calendar-seed-scripts-tests`        | 5s   | 45         |
| `test-state-programme-seed-scripts-tests` | 7s   | 75         |
| `q-scripts-tests`                         | 41s  | 32         |
| `db-scripts-tests`                        | 284s | 112        |
| `scripts-tests`                           | 269s | 1085       |
| **total**                                 | 639s | 1541       |

`installDist`, warm, is 7s. **Two harnesses are 553s of the 639s — 87%.** Any
scoping work that does not move `db-scripts-tests` and `scripts-tests` has not
moved anything.

**A warning about published costs, carried forward from RFC 211 and now
quantified.** `bin/shell-tests`'s help published ~6s / ~3s / 4.6s / ~2s / ~4s /
~12s / ~15s / ~63s, about 105s in total. Those were quiet-machine figures.
`db-scripts-tests` measured ~19x its published number and is the SLOWEST harness
of the set while the help's cheapest-first ordering implies it is nearly the
cheapest. This RFC deletes the per-harness figures from that help rather than
correcting them, because a number in a help string has no date, no load average
and no way to go stale loudly.

**There were EIGHT harnesses, not seven.** RFC 211 says seven, and CLAUDE.md
still named four and "~280 assertions". Both were stale:
`bin/test-state-programme-seed-scripts-tests` landed with RFC 208 after those
sentences were written. This RFC adds a ninth and corrects CLAUDE.md, and
`bin/gate-scope` derives the set from the filesystem so the next such addition
fails loudly instead of quietly.

### What the rows are worth, on this repository's real history

The last 400 commits on `main`, classified through the rules below
(`git log --no-renames --name-only`, module list derived from directories
containing `src/`):

| row             | commits | share |
| --------------- | ------- | ----- |
| `markdown-docs` | 245     | 61.3% |
| `full`          | 129     | 32.2% |
| `ios-app`       | 16      | 4.0%  |
| `module-source` | 19      | 4.8%  |
| `prime-scripts` | 6       | 1.5%  |

Of the 245 Markdown commits, 99 are `product/`-only and already fast under RFC
194; the remaining 146 — 37% of ALL commits — go from 11-13 minutes to about 8
seconds. They are `rfc/` 101, `.claude/` 21, `.prime/` 15, `features/` 5.

Applying the measured per-harness costs above to those 400 commits: **~61 hours
today, ~29 hours with this table, ~32 hours saved, averaging ~288s per commit**.
And that counts ONE gate run per commit, where today's evidence says a land
often costs three.

One finding from the same classification that removed a decision rather than
adding one: **zero of the 129 `full` commits are "module `src/` plus
Markdown"**. Every Kotlin commit in 400 also touches `db/schema`, `bin/`,
resources or `api-specs`. So `module-source` is deliberately NOT loosened to
allow Markdown alongside code: it would gain literally nothing and would cost a
correctness argument.

## What a path-to-harness map is, and what it must therefore do

It is a **correctness claim**, not a configuration table. If it is wrong, a
defect reaches `main` and the gate reports green — the worst failure this repo
can have, because it is silent. So `bin/gate-scope` is fail-closed in five ways,
each from a way a table like this goes wrong:

1. **Unrecognised path ⇒ everything.** The map lists what is KNOWN to be narrow.
   Anything else falls to the full gate.
2. **Judged on the diff AS A WHOLE, never per-file.** A mixed set is not the
   union of its files' scopes; it is `full`. One unrecognised path in a
   thousand-file diff makes the whole diff `full`.
3. **An EMPTY staged set is `full`**, not a vacuous pass — the same answer
   `staged_changes_are_product_docs_only` already gives, for the same reason.
4. **A rename crossing a scope boundary is classified on BOTH sides.**
   `--no-renames` is passed to `git diff --cached`, so a rename appears as a
   delete and an add and both paths are judged. `git mv .prime/x/SKILL.md bin/x`
   is `full`.
5. **A new `bin/*scripts-tests` the map does not name FAILS the gate**, by name,
   whatever the diff. It mirrors the anti-orphan sweep in `bin/scripts-tests`,
   and it fails on a `full` diff too: failing only on the narrow path would hide
   it exactly when it matters least and let it surprise someone later.

And `-z` with `IFS= read -r -d ''` throughout, because a pathname containing a
newline, or a directory named `" .prime"` with a leading space, must not be able
to dodge a glob. That is a demonstrated defect in this repo (RFC 193), not a
hypothesis.

## The standing risk decision this RFC was written under

Stated because it changed the table, and a reader who does not know it will
misread the rows as carelessness.

The operator's ruling, verbatim: _"If we have failures because we miss a test,
that's okay. The cost the other way is too high and we're experiencing it now:
it takes hours just to commit a change."_

That inverts the usual default. Concretely, as applied here:

- A missed test that lets a defect through is **recoverable** — found later,
  fixed in a follow-up. An 11-to-13-minute gate on every commit is **not**; it
  is paid every time, by every run, forever.
- Where it was unclear whether a harness observes a path, the harness is
  **excluded**, not included "to be safe". Safety in that direction is the cost
  being complained about.
- **No belt-and-braces rows.** The bar for excluding a harness is evidence that
  it does not actually observe the path — not the absence of an imaginable
  failure.
- The fail-closed guard STAYS. It is about the map being COMPLETE, which is a
  different thing from running extra tests, and no ruling about test volume
  touches it.

This licenses a narrower map than instinct wants. It does not license guessing:
every row below cites what was read.

## Decisions

### D1 — one script, `bin/gate-scope`, and it is the only table

`bin/pre-commit` calls it. `/ship` phase 5 may later call it. There is never a
second copy.

RFC 211's reasoning for insisting on this is not repeated except for its sharp
end: a wrong map in phase 5 fails SILENTLY, because the hook catches what it
missed; a wrong map in the hook does not. The duplicated copy that drifted
invisibly is the one that would be trusted.

`gate-scope` prints its answer on **stdout** as exactly three `key=value` lines,
because stdout carries only what the caller asked for:

    scope=markdown-docs
    gradle=no
    harnesses=

Every diagnostic, including the human-readable reason, goes to **stderr**
(`bin/` convention 2). Options are `getopts`, short only (`bin/` convention 1).

`harnesses=ALL` is a distinct token from a literal list, deliberately: it means
"whatever `bin/shell-tests` runs", so a newly added harness is covered by the
full scope with no edit at all. Only the NARROW scopes enumerate.
`bin/pre-commit` refuses the combination `gradle=no` + `harnesses=ALL` rather
than guessing, since no row produces it.

### D2 — `product/**` keeps its rule, and `gate-scope` becomes its one caller

RFC 194's product-docs-only exemption is an existing, tested claim, and its
predicate `staged_changes_are_product_docs_only` lives in `bin/functions`.

This RFC does not reimplement it and does not move it. `gate-scope` **calls**
it. So `bin/pre-commit` asks exactly one question of exactly one script, which
is D1's requirement, while RFC 194's claim is still made by the code written and
tested for it. Reimplementing it inside `gate-scope` would have been the second
table this RFC exists to prevent.

### D3 — the rows, and the evidence each one rests on

The sweep behind every row was done by **reading every hit, not counting them**.
A mention in a comment is not observation. A file created inside a throwaway
fixture repo is not observation. Executing the repository's real file is.

#### `markdown-docs` — every staged path ends `.md`, none inside a module `src/` tree

⇒ `bin/format -c` only. No Gradle, no harness.

Evidence: **no harness reads any real `.md` in this repository.** The only
mentions are three comments (`bin/INVARIANTS.md`, `CLAUDE.md`) and one fixture —
`bin/ship-scripts-tests` writes `rfc/999-x.md` inside a throwaway repo to assert
that the `product/` predicate rejects `rfc/`. Markdown's only executable
consequence here is `deno fmt`, and `bin/format -c` runs on EVERY path through
`bin/pre-commit` including this one; `bin/common`'s `MARKDOWN_FMT_PATHS` already
covers `**/*.md`, `.claude` and `.prime`.

**The `src/` exclusion is the one place this RFC refuses to guess.** About ten
`INVARIANTS.md` files live inside `src/main/kotlin` trees. Whether Gradle hashes
them as source-set inputs is answerable only by experiment, and the failure
direction of guessing wrong is a silently skipped Gradle half. They are excluded
instead. It costs nothing: CLAUDE.md retired those files in 2026-08 and nobody
edits them.

Where such a file DOES land is worth stating, because the first draft of this
section predicted the wrong row and the harness caught it: the `src/` exclusion
only clears the Markdown flag, so the path still satisfies `module-source` and
classifies there — `gradle=yes`, `scripts-tests`. That is the outcome the
exclusion exists to produce. Gradle is left to answer the question this script
declined to answer, which is the whole point; it is not the `full` fallback the
draft claimed.

This row subsumes what an earlier draft called `prime-docs`, and covers every
`rfc/*.md`, every `SKILL.md`, `CLAUDE.md`, `PRODUCT.md`, and every brief and
spec. RFC 211's own 30 minutes across three lands was entirely this row.

The residue, stated rather than glossed: a harness that composed a Markdown path
from a VARIABLE would escape the sweep. None does; the harness fails the build
on any literal one; a variable one would have to be written deliberately.

**One consequence worth recording rather than discovering later: for the commits
`/chart` actually writes, the `product-docs` row is now DEAD.** Everything it
writes is Markdown, and `markdown-docs` is checked first. The row is kept anyway
— it is RFC 194's tested claim, it is the only row a non-Markdown `product/`
file can reach, and deleting a correct claim to tidy up is how a later
`product/**/*.csv` commit would quietly become `full`.

#### `prime-scripts` — every staged path under `.prime/**`

⇒ `bin/format -c` + `ship-scripts-tests` + `docs-scripts-tests` +
`db-scripts-tests`. No Gradle, no `installDist`, no shared cluster.

**The third name is the one an instruction to write this script got wrong, and
the omission is exactly the trap this RFC is about.** `bin/db-scripts-tests`
line 1770 EXECUTES the real `.prime/agent/skills/ship/scripts/ship-order`
against a throwaway git fixture, to make `bin/common`'s ORDER reader and
`ship-order`'s ORDER writer meet. A map naming only the two obvious harnesses
would have landed a `ship-order` change green with its one contract test never
run.

The sweep is by literal `.prime/` MENTION, not by resolved path, and that is the
point: `bin/ship-scripts-tests` builds its targets as
`SHIP_SCRIPTS="$PROJECT_ROOT/.prime/agent/skills/ship/scripts"` and then
`"$SHIP_SCRIPTS/ship-lock"`, and `bin/docs-scripts-tests` does the same through
`CHART_SCRIPTS`. An exact-path map would not see those targets at all. Directory
mention survives variable composition; exact matching does not. So the claim is
made at the coarsest granularity that is mechanically checkable, and no finer.

**This is a WEAKER claim than RFC 194's, and is written as one.** RFC 194 is
safe because `product/**` is not a build input AT ALL. `.prime/` is not in that
class — it holds executable scripts that harnesses run — so this row is safe not
because nothing observes `.prime/` but because **the things that observe it are
named and are run**. That is a claim about a list, and a list can go stale,
which is what D4's guard is for.

#### `module-source` — every staged path under a Gradle module's `src/` tree

⇒ Gradle in full, plus `bin/scripts-tests`, and nothing else. Saves 370s of
639s.

Evidence for what is KEPT: `bin/scripts-tests` boots the REAL built
`rest-server` and `queue-worker` (`test_daemon_wrapper "rest-server"` /
`"queue-worker"`, 53s of its 269s) and asserts they come up and answer. That is
genuine observation of compiled Kotlin, so it runs. **This is where the cheap
answer was available and not taken**: excluding it would have made a Kotlin
commit ~90s, and the evidence says keep it.

Evidence for what is EXCLUDED: none of the other harnesses references a built
artifact, a daemon wrapper, or a module `src/` path. The three apparent
exceptions were read — `prompt-seed-scripts-tests` CREATES
`service/src/main/resources/service.conf` inside its own fixture repo to test
`bin/prompt-seed`'s repin and never reads the real one; `docs-scripts-tests`'s
only `.kt` hit is a fixture file literally named `" product/x.kt"` with a
leading space; `q-scripts-tests` drives `bin/q-*` through `bin/db-run` and psql
and never starts the JVM.

The module list is read from `settings.gradle.kts` at runtime — the source of
truth — rather than copied. A module added to the build and not to that file
could only make this script fall back to `full`, which is the safe direction.

The risk, concretely rather than as a category: a Kotlin change that alters
something a BASH script parses, where the bash-side assertion is in a skipped
harness. `scripts-tests` covers the daemon and health cases. The uncovered shape
is a Kotlin change reaching `db-scripts-tests` or `q-scripts-tests` — but those
drive psql and `bin/db-*` and never compile or run Kotlin, so it would have to
arrive through a migration or a seed, and a diff containing one of those is not
a `src`-only diff and does not take this row.

#### `ios-app` — every staged path under `ios-app/`

⇒ `bin/format -c` only. No Gradle, no harness. 16 of the last 400 commits on
`main` (4.0%).

Evidence: of the harnesses `bin/shell-tests` actually runs, **none mentions
`ios-app` at all** — `bin/scripts-tests`' only two `ios` hits are the
`HARNESS_EXCLUSIONS` name, not a path. `ios-app` is not a Gradle module
(`settings.gradle.kts` does not include it), no build file references it, and
the only Kotlin mentions anywhere in the tree are two COMMENTS in `rest-server`
tests pointing at Swift files. It is Swift, an Xcode project and some `.env`
files.

`bin/ios-scripts-tests` DOES read the real `ios-app/env/prod*.env` files — and
it is carved out of `bin/shell-tests` BY NAME (RFC 114: it drives system Xcode
and refuses inside the Nix dev shell, which is where the gate runs). So it never
ran at the gate before this row and does not now. Stated honestly, what changes
for an iOS commit is that it stops paying for a Kotlin-and-Postgres gate that
cannot observe it; its own coverage — a human running `bin/ios-scripts-tests`
outside the dev shell — is exactly what it was.

#### What is deliberately NOT a row, and why

- **`db/schema/**`, `db/seed/**`, `db/data/**`** — observed by six of the eight
  harnesses (`scripts-tests` alone carries 141 `db/seed` and 35 `db/data`
  references, and `q-scripts-tests` depends on the `jobs` table through
  `db-reset`). There is no narrow answer here and one is not invented.
- **`bin/**`** — the harnesses ARE the tests for `bin/`. A `bin/`-only diff is
  `full`, correctly.
- **`infra/**`** — and this one was WRONG in this RFC's first draft, so it is
  recorded rather than quietly fixed. A draft row claimed that once D9 removed
  the tofu assertions nothing observed `infra/`. `bin/scripts-tests` observes it
  in five other places: it greps `infra/files/deploy-on-instance.sh` (1942),
  loops over `infra/files/unicoach-*.service` (1950), `sed`s `infra/rds.tf` to
  assert the production `POSTGRES_DB` matches `db_name` (6400, RFC 156), runs
  `infra/files/render-env.sh` (6443), and `bash -n`s `infra/files/*.sh` (6496).
  An `infra/`-only diff is `full`. It is 1 commit in the last 400, so the row
  would have bought almost nothing even had it been right.
- **`prompts/**`, `.env*`** — small and entangled; no measured win worth a
  correctness claim.
- **A finer scope INSIDE `scripts-tests`.** Its 53s daemon block is the only
  part that observes Kotlin. Splitting it is a harness change, not a map row.

### D4 — the guard: the harness list is swept, not trusted

`gate-scope` discovers `bin/*scripts-tests` from the filesystem, excludes
`ios-scripts-tests` BY NAME (RFC 114: it drives system Xcode and refuses inside
the dev shell — carved out by name, never by pattern, so a future omission
cannot be swallowed), and asserts every survivor appears in its own
`KNOWN_HARNESSES`. An unnamed harness is a **fatal**.

The narrow rows are then checked the other way round by
`bin/gate-scope-scripts-tests`, over the REAL harness files: every harness that
mentions `.prime/` must be in `prime-scripts`'s list; no harness may name a
literal `.prime/` path ending `.md`; every name in a row must exist and be
executable. Both sides of those sweeps are derived mechanically — a test that
hardcoded the three names on both sides would assert nothing.

The glob is `*scripts-tests`, not `*-scripts-tests`, for the reason
`bin/scripts-tests` already documents: the hyphenated form silently misses
`bin/scripts-tests` itself, the most expensive harness of the set.

### D5 — `bin/shell-tests -n`, and `bin/test -n`, pass a subset through

`bin/shell-tests` gains a repeatable `-n <harness>`: run only the named ones, in
the aggregator's own cheapest-first order, never in the order given. No `-n`
means all, so every existing caller is unchanged. An unknown name is
`EXIT_INVALID_ARG_VALUE`, checked BEFORE anything runs — a typo that silently
ran a shorter set would be a gate reporting green over tests nobody executed.

The straight-line invocations become an ordered `HARNESSES` array whose entries
are `bin/<name>` and **not** bare names. That is load-bearing: the anti-orphan
sweep in `bin/scripts-tests` greps this file for the literal `bin/<harness>`,
and an array of bare names would have turned every one of those assertions green
while asserting nothing.

`bin/test` gains the same repeatable `-n`, meaningful only with the `check`
keyword; without it, `EXIT_MISSING_REQUIRED_ARG` rather than a silent no-op.

### D6 — what is NOT recorded

`ship-verified record` marks "the full pre-commit gate passed on this tree". RFC
194 already declines to record it on the product-docs path, because a marker
that can lie is worse than none.

**The same applies to every narrow scope.** The condition is `scope=full`, NOT
"did the test half run at all": a `module-source` commit runs Gradle in full and
one harness of nine, which is a real gate and a useful one, and it is still not
THE gate. The consequence is the one RFC 194 already accepted and is in the safe
direction: a `/ship` run whose branch TIP is a narrow-scope commit is refused at
land, and one re-commit fixes it.

### D7 — the scope is LOGGED, by name, on every run

`bin/pre-commit` logs the scope, the reason in words, and the exact test-half
command it is about to run. Two commits that ran different gates must be
distinguishable from the hook output alone.

### D8 — concurrency is NOT in this RFC, and the reason is not caution

The harnesses run serially and are largely independent, so overlapping them
looks like free money. It is deferred, for three reasons:

1. **It cannot be justified by reading.** It needs an experiment against real
   Postgres and this checkout's daemons. `scripts-tests`' EXIT trap calls
   `bin/rest-server-down` and `bin/queue-worker-down`, and `q-scripts-tests` and
   `scripts-tests` share this worktree's test DB while `db-scripts-tests`
   provisions a private cluster. Those are collisions to be mapped, not read
   off.
2. **After the rows above, the win shrinks.** Concurrency only helps when
   several harnesses run, and the point of the map is that they now mostly do
   not.
3. `db-scripts-tests` alone took this machine's load average to 8.05. Running it
   beside `scripts-tests` is not obviously a win on a machine carrying ~10
   worktrees.

Also deferred, and named: whether `bin/scripts-tests`' repeated
`db-create`/`db-drop`/`db-adopt`/`db-migrate` cycles (~50s of its 269s, measured
from its own section headers) are each load-bearing or habit.

### D9 — the tofu checks leave the gate, and `infra/` becomes ungated

**Decided by the operator, against the recommendation in this RFC's first
draft.** That draft argued the checks should stay because `infra/` is committed
here and there is no CI, so the commit gate is the only gate it passes through.
That argument was heard and rejected.

`tofu -chdir=infra fmt -check` and the `init -backend=false` + `validate` pair
move out of `bin/scripts-tests` into **`bin/infra-check`**, which nothing calls:

    nix develop -c bin/infra-check

**So `infra/` is ungated. Nothing checks it at commit time.** Whoever changes
`infra/` runs that command, and there is no CI to catch it afterwards. That is
the cost, stated plainly, and it is accepted.

**No speed win is claimed for this.** Since `2cfe0be9` the pair costs 1-2s warm;
removing it saves essentially nothing. The reason is that **a commit gate should
not reach the public internet**, and a check that cannot run offline is worse
than an absent one. This is the step that failed two consecutive land attempts,
after 561s and 485s of otherwise-passing assertions, while downloading a
provider with both commands sent to `/dev/null`.

It is NOT smuggled back in behind a path rule "for safety" — that would be the
same check in the same gate with extra steps.

**And this removal does NOT make `infra/` unobserved, so no future row may lean
on it.** `bin/scripts-tests` still reads five other real `infra/` files, listed
in D3's not-a-row section. An `infra/`-only diff is `full`, and a later RFC that
wants a narrow `infra` row must sweep those five, not cite this decision.

The executed assertion count of `bin/scripts-tests` falls by exactly 2, from
1085 to 1083, and that is the whole of the change in what the gate executes for
a `full` diff.

### D10 — the two existing exemptions are untouched

RFC 193's docs-only land fast path and the enumerated `--no-verify` commits are
unchanged. This RFC changes what the hook RUNS, not who may skip it.

## The executed assertion count, and every delta in it

A faster gate that quietly runs fewer assertions on a diff that needed them is
the failure mode this RFC could have. So the count is stated with every change
accounted for, for a `full` diff — the only scope that runs everything:

| change                                                                                                                                                         | delta   |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `bin/scripts-tests`: two tofu assertions removed (D9)                                                                                                          | -2      |
| `bin/scripts-tests`: the pre-commit scope-logging test split into two, because the logging moved into `bin/gate-scope` and both halves are now worth asserting | +1      |
| `bin/gate-scope-scripts-tests`: new harness                                                                                                                    | +46     |
| **net, `full` diff**                                                                                                                                           | **+45** |

`bin/scripts-tests` goes 1085 → 1084; the suite total goes 1541 → 1586. **A
`full` commit therefore executes MORE assertions after this RFC than before
it**, not fewer. What falls is what a NARROW diff runs, and each narrow row
states above which harnesses it drops and on what evidence.

## Detailed Design

### `bin/gate-scope`

New script.

    gate-scope [-r <repo>] [-h]

stdout, exactly three lines: `scope=`, `gradle=`, `harnesses=`. stderr: one
`log-info` line explaining the classification in words. Exit 0 on any successful
classification including `full`; `bin/functions`' reserved codes for a usage
error; a plain fatal for the anti-orphan failure, which is a broken repository
rather than a bad invocation.

The anti-orphan sweep runs BEFORE classification. Then, over the whole staged
set:

1. Empty ⇒ `full` / yes / `ALL`.
2. All `.md` and none under a module `src/` tree ⇒ `markdown-docs` / no / none.
3. `staged_changes_are_product_docs_only` ⇒ `product-docs` / no / none.
4. All under `.prime/` ⇒ `prime-scripts` / no / three harnesses.
5. All under `infra/` ⇒ `infra` / no / none.
6. All under a module `src/` tree ⇒ `module-source` / yes / `scripts-tests`.
7. Otherwise ⇒ `full` / yes / `ALL`.

Rule 2 precedes 3 and 4 deliberately: where two rows could both match, the row
making the WEAKER demand on the gate wins, because the Markdown claim is about
the file's readers rather than its directory.

`list_contains` is NOT sourced from `bin/tests-common` — that file is a test
helper, it defines `pass_test`/`fail_test` and a counter, and a production
script in the gate path has no business sourcing it. `gate-scope` carries a
four-line `name_in`. Every array expansion uses `${a[@]+"${a[@]}"}` for the
bash-3.2 reason `bin/common` already documents.

### `bin/pre-commit`

Replaces the two-branch `DOCS_ONLY` flag with `gate-scope`'s three lines. An
unrecognised `key=` from `gate-scope` is a fatal, not an ignored line: it is a
contract change between two files that must agree, and ignoring it would run the
old gate shape against a new answer and say nothing. Three gate shapes result:

- `gradle=yes` ⇒ `bin/test check`, narrowed with `-n` when the harness set is
  not `ALL`, concurrently with `bin/format -c`. Unchanged for `full`.
- `gradle=no` with harnesses ⇒ `bin/shell-tests -n ...` concurrently with
  `bin/format -c`.
- `gradle=no` with none ⇒ `bin/format -c` alone.

## Files Modified

- `bin/gate-scope` — new. The single path-to-harness map.
- `bin/gate-scope-scripts-tests` — new. Its harness. Named `*scripts-tests` so
  the existing anti-orphan sweep in `bin/scripts-tests` derives it and asserts
  it is wired into `bin/shell-tests`.
- `bin/infra-check` — new. The tofu checks, outside the gate (D9).
- `bin/shell-tests` — `HARNESSES` array, repeatable `-n`,
  `gate-scope-scripts-tests` wired in, per-harness cost figures deleted from the
  help.
- `bin/test` — repeatable `-n` passed through; stale harness list and cold-cost
  figure removed from the help.
- `bin/pre-commit` — calls `gate-scope`; three gate shapes; scope logged;
  `ship-verified record` only for `full`.
- `bin/scripts-tests` — the two tofu assertions removed, replaced by a comment
  naming `bin/infra-check` and saying why it is not to be re-added here.
- `CLAUDE.md` — stale harness list and assertion count corrected, the new scopes
  documented beside RFC 194's, and `infra/`'s ungated status stated.
- `rfc/214-the-commit-gate-runs-the-harnesses-a-diff-can-break.md` — this file.

## Implementation Plan

1. `bin/gate-scope` with the anti-orphan sweep and the seven classification
   rules.
2. `bin/infra-check`; remove the two assertions from `bin/scripts-tests`.
3. `bin/shell-tests` `HARNESSES` + `-n`; `bin/test` `-n` pass-through.
4. `bin/pre-commit` consumes `gate-scope`.
5. `bin/gate-scope-scripts-tests`, covering every rule, every fail-closed
   direction, and the cross-sweeps over the real harness files.
6. `CLAUDE.md` and help-text corrections.
7. Measure after: per-harness and full-gate wall clock with a stated load
   average, executed assertion counts before and after with every delta
   explained, and one worked case each for a `.prime`-only, Markdown-only,
   Kotlin-only, `bin/`-only and mixed diff.

## Tests

`bin/gate-scope-scripts-tests`, against throwaway `git init` fixtures — no
cluster, no daemon, no worktree.

**Classification**: empty index ⇒ `full`; a `product/` path ⇒ `product-docs`; a
`.prime/**/SKILL.md`, an `rfc/*.md`, `CLAUDE.md`, two Markdown files in
different directories, and a delete-only Markdown diff ⇒ `markdown-docs`; an
`INVARIANTS.md` inside a module `src/` tree ⇒ `module-source`, NOT
`markdown-docs`, and in particular `gradle=yes`; a `.prime` script ⇒
`prime-scripts` with exactly the three names; `.prime` Markdown plus a `.prime`
script ⇒ `prime-scripts`, not `markdown-docs`; one `.kt`, two files in one
module, two files in two modules ⇒ `module-source`; a `src/` path under a
directory absent from `settings.gradle.kts` ⇒ `full`; a `bin/` path, a
`db/schema/` path, `infra/` plus a `.kt`, and a `.kt` plus Markdown ⇒ `full`;
`infra/` alone ⇒ `infra`.

**Fail-closed**: a rename `.prime/x/SKILL.md` → `bin/x` ⇒ `full`; a rename
inside `.prime/` from Markdown to a script ⇒ `prime-scripts`; a path containing
a newline is classified rather than split; a top-level directory named
`" .prime"` with a leading space ⇒ `full`.

**The guard**: a tree containing a `bin/zzz-scripts-tests` the table does not
name ⇒ fatal, naming it, for a `full` diff as well as a narrow one;
`bin/ios-scripts-tests` present ⇒ no fatal.

**Cross-sweeps over the REAL harnesses**, with both sides derived mechanically:
every `bin/*scripts-tests` mentioning `.prime/` is in `prime-scripts`'s list —
this is the assertion that catches `db-scripts-tests`; no harness names a
literal `.prime/` path ending `.md`; every name in a row exists and is
executable; every harness but `ios-scripts-tests` is in `KNOWN_HARNESSES`.

**Usage**: unknown option ⇒ 10; `-r` without a value ⇒ 11; a positional argument
⇒ 20; `-r ""`, `-r /nonexistent` and `-r <non-git-dir>` ⇒ 22; `-h` writes help
to STDOUT and exits 0; a normal run writes NOTHING to stdout but the three
`key=value` lines and its reason to stderr — that emptiness is asserted, because
it is exactly what regresses when someone moves a log line back.

The two MENTION sweeps carve out `gate-scope-scripts-tests` itself BY NAME, in
the same style as `ios-scripts-tests` and for a stated reason: that harness
writes `.prime/...` and `.prime/.../SKILL.md` as FIXTURE paths under `mktemp`
and never reads, runs or stages anything in the real `.prime/` tree. The
alternative was to compose those fixture paths from a variable so they dodge the
grep, which is precisely the residue the `markdown-docs` row warns about. Both
sides of the sweep are still derived mechanically, and the sweep FAILS if it
finds nothing to check, so the carve-out cannot silently empty it.

`bin/shell-tests -n` with an unknown name ⇒ 22, repeated `-n` runs both in
aggregator order rather than argument order; `bin/test -n` without `check` ⇒ 21.
