# RFC 235: Dispatch full, run lean

## Summary

Two changes to how `/ship` spends child context during review, one per child
kind. Neither changes which lenses run, any rule text, or any model a lens or
fix child uses.

**W7 — a lens dispatch carries everything it needs.** The dispatch message holds
the lens rule text, the output template, the graded-output contract and the diff
under review, inline. The coordinator assembles that message **in the Python
kernel from files on disk**, so the bytes never pass through its own generation.
The child is told that everything it needs is in the message, and that it opens
further files only when its rule genuinely requires context beyond the diff.
Today a lens child is handed paths and spends its first turns fetching what the
coordinator could have handed it; the archive's median lens child takes 15 model
calls, and every one of them re-reads the whole context at cacheRead prices.

**W8 — no command output enters a fix child's context.** A fix child still runs
its own builds, tests and formatting, but only through a new script,
`scripts/ship-run`, which pipes the whole output to a file under `<rs>/` and
**emits** the record from the same shell: exit code, executed test counts, and a
byte-exact excerpt of the failing output. The record is what the child reads;
the log stays on disk. The suite runs once per fix BATCH, at the end, not once
per finding; per-item compiles are allowed, to file, with only the errors read
back.

The two halves land as two commits so either can be reverted alone.

## Motivation: measured, not estimated

`.scratch/review-cost-measurements.md` section 2 counts 1,415 review children
across 34 runs at **$2,644.74**. The spend is not concentrated in a tier — Tier
1 and Tier 2 together are 69.6% — and it is not concentrated in output tokens
either. Across the archive, lens children burned **1,335M cacheRead tokens
against 17M output tokens**, at a median of **15 model calls** each; fix
children burned **1,513M cacheRead** at a median of **65 calls**. Output is ~1%
of the token volume. What a review costs is the number of times a child re-reads
what it already has.

That reframes the problem. Cutting what a child READS once is worth little; the
same bytes are re-read on every subsequent turn. **Cutting TURNS is the lever**,
and the two child kinds waste turns in two different ways.

### A lens child spends its opening turns fetching

Today a lens dispatch names paths: the rule file, the diff, the template. The
child then reads them, one tool call each, and every read lands in a context
that is re-sent on every later call. The bytes were always going to be paid for
— the child has to see the rule and the diff to do its job — but paying for them
through three tool-call round trips instead of one message multiplies the
re-reads by the turns those round trips add.

That arithmetic predicts 15 calls to 5, a 3x saving. **Measured, the saving is
about a third** — the experiment below puts a front-loaded lens child at a
median of 11 calls and $1.43, against 15 calls and $2.04–2.32 today. The reason
is worth more than the number: the turns that remain are rule-driven file reads,
not the opening fetch.

This does not grow the coordinator. The rule and diff bytes go from disk to the
child's first message through a Python variable; the coordinator's own context
grows by the dispatch cell and a one-line reply. Assembling the message by
GENERATING it would be the opposite trade — the content would pass through the
coordinator's context once to be written and then again on every later turn of
the coordinator's own session, and composing a prompt token by token is the
pattern that produces degenerate repetition loops. The assembly is
`Path(...).read_text()`, always.

### A fix child reads build logs it does not need

`rfc-155`'s archive carries `bin-test-1.log` through `bin-test-5.log` INSIDE its
fix passes: one fix child ran the suite five times. Section 3 counts the shape
that produces: 33% of (run, tier) pairs needed two or more fix children, and one
tier needed **18**. A fix child that runs the suite after every item pays the
full log into its context each time, and then re-reads it on every turn until it
finishes.

Two separable wastes sit in that: WHAT enters the child's context, and HOW OFTEN
the command runs. This RFC fixes both, and keeps them apart deliberately —
piping a log to a file and reading the whole file back into context costs
exactly what streaming it cost. The rule is about what ENTERS context, not about
the transport.

### The runner grandchild was designed, probed, and then dropped

The plan row for W8 asked for a runner grandchild: the fix child would spawn a
child of its own on `deepseek/deepseek-v4-pro` (RFC 233's runner rule, one level
down), which would run the command and report back. That was probed first,
because the whole of it rested on an unproven capability — a depth-1 child on
`claude-opus-5` spawned a depth-2 grandchild on `deepseek/deepseek-v4-pro`, the
grandchild ran `nix develop -c ./gradlew :college:compileKotlin` with output
piped to a file under `<rs>/`, and replied up one level with the exit code and
the tail of the log. Spawn admitted, reply delivered, log on disk, three turns
in the middle child. **Depth-2 dispatch works.**

It is not used, and the reason is worth writing down because the design looked
obviously right until the pieces were listed:

| the guarantee W8 needs                       | what provides it                                                                                                    |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| the log never enters the fix child's context | the script — it pipes to a file and prints only the record                                                          |
| the failing text is byte-exact               | the script — it writes the excerpt from the same shell                                                              |
| exit code and executed counts                | the script                                                                                                          |
| the wait does not block the fix child        | the harness — a background command returns a handle, the child ends its turn, and the completion follow-up wakes it |
| a cheap model rather than an expensive one   | the grandchild — for running one command it was handed                                                              |

With the script in place the grandchild reads a file and retypes three facts. It
costs a spawn round trip of two to three turns **in the fix child**, at
fix-child prices — about $0.27 against roughly $0.05 for the deepseek child
itself, so the spawn costs more than the runner — and it adds one hop of
paraphrase to the one guarantee this half exists to protect. It removes nothing.

So a fix child runs its own commands, through `ship-run`. **The coordinator's
runner children are unchanged**: RFC 233's reasons there are different ones —
Ian can watch a coordinator's runner live in the harness, and it reports to him.
A fix child is already invisible to him, so a child of it buys him nothing. The
same argument does partly apply to the coordinator's rule; re-opening it is not
this RFC's business, and it is named here as a question for a later run rather
than left as an inconsistency nobody noticed.

## The recall question, measured rather than assumed

W7's plan row claimed "zero recall risk". That is an assumption, not a
measurement: telling a child to stop exploring is a behavioural restriction, and
a model told to minimise exploration may under-explore. So it was tested before
the design was written, on the instrument already on disk from the W2 model
ladder.

**Substrate.** `.scratch/ab/ladder/rfc-226-control.diff` —
`git diff 67af2f56
0abe261b`, the 1,400-line tree that RFC 226's real review
read — plus `.scratch/ab/ladder/control-findings.json`, the 14 control findings
opus-5 produced on it under today's path-based dispatch, in the 14-lens subset.

**Method.** Eight of those lenses were re-dispatched on the SAME model as the
control (`anthropic/claude-opus-5`), on the SAME frozen diff, under front-loaded
dispatch: rule text, template, graded contract and diff inline, assembled in the
kernel, with the exploration clause this RFC proposes. Six of the eight carry 11
of the 14 control findings; two are control-PASS lenses, carried to measure
inventions rather than recall. Reports: `.scratch/ab/frontload/<lens>.md`.
Scoring uses the ladder's own legend — EXACT, ADVISORY, X-LENS, ADJACENT, MISS.

**Turn and cost result.** Same model, same diff, same contract; the comparison
baseline is the archive-wide measured rate, not a same-diff control, and that
caveat is stated again below.

| lens                  | model calls | files opened | cost | cacheRead | grade / findings |
| --------------------- | ----------- | ------------ | ---- | --------- | ---------------- |
| `no-remote-breakage`  | 12          | 4            | 1.55 | 731,871   | SHOULD-FIX / 9f  |
| `generalization`      | 24          | 8            | 2.20 | 1,759,363 | SHOULD-FIX / 5f  |
| `impossible-misuse`   | 10          | 6            | 1.23 | 574,865   | BLOCKING / 7f    |
| `exhaustive-eval`     | 16          | 9            | 1.67 | 1,067,143 | SHOULD-FIX / 6f  |
| `solve-completely`    | 18          | 6            | 1.85 | 1,110,302 | BLOCKING / 9f    |
| `srp`                 | 6           | 0            | 1.04 | 289,470   | SHOULD-FIX / 5f  |
| `no-sentinels`        | 7           | 1            | 1.07 | 370,289   | SHOULD-FIX / 4f  |
| `structured-payloads` | 5           | 0            | 0.84 | 173,313   | SHOULD-FIX / 6f  |

- **Model calls: median 11**, against the archive's median of **15** for a lens
  child. Mean 12.3. Three of eight met the ≤5-turn target; the other five did
  not.
- **Cost: mean $1.43 per lens child** (median $1.39, eight lenses $11.45),
  against opus-5's measured Tier 1/2 rate of **$2.04–2.32** per lens child. That
  is roughly a **third** off, not the half the plan row estimated, and nothing
  like the 3x the turn arithmetic suggested.

**Why the saving is a third and not a half — the most useful thing measured
here.** The turn count tracks the number of files the child opened, and almost
nothing else:

    files opened   0    0    1    4    6    6    8    9
    model calls    5    6    7   12   10   18   24   16

The two lenses that opened nothing ran in 5 and 6 calls — the target, met. The
five that went to disk did so because their rule is about the rest of the
repository, which is exactly the exploration D3 permits. **Front-loading removes
the opening fetch, not the rule-driven reads, and the rule-driven reads are the
bulk of the turns.** The lever is real and its ceiling is now known.

The obvious next step — front-load the full current text of every file the diff
touches, so the rule-driven reads land in message one too — is refused by its
own number: those 20 files are **660KB, ~165k tokens**, eight times the diff,
and every one of them would be re-read at cacheRead prices on every turn of
every lens child. The reads a lens makes are a few files each; front-loading all
of them would cost more than it saves.

**Recall result — the assumption the plan called "zero risk".** Scored against
the control by a separate child reading the eight reports, the control findings
and the diff (`.scratch/ab/frontload/results.md`):

| judgement                                                | count (of 11 control findings) |
| -------------------------------------------------------- | ------------------------------ |
| `EXACT` — same lens, same site, actionable grade         | 9                              |
| `ADVISORY` — found, but graded ADVISORY, so no fix child | 1 (`im-1`)                     |
| `ADJACENT` — same defect area, neighbouring site         | 1 (`gen-1`)                    |
| `X-LENS` — real, filed under another lens                | 0                              |
| `MISS` — not found                                       | **0**                          |

**Nothing was lost.** One finding was demoted to ADVISORY, which operationally
means it would not be fixed, and one was hit at a neighbouring site. On this
substrate the exploration clause did not cause a lens to under-find.

**Precision — the question that replaced it.** The eight reports carried 36
findings against the control's 11. Classified strictly, reading the diff:

| class                                                      | count |
| ---------------------------------------------------------- | ----- |
| `REAL-IN-LENS` — a genuine defect, this lens's business    | 16    |
| `REAL-OFF-LENS` — genuine, but another lens's rule owns it | 15    |
| `INVENTION` — not a real defect                            | 5     |

31 of 36 are real, and the 36 collapse to roughly 17 distinct claims — the
duplication is cross-lens re-raising, which is what RFC 234's `## Out of lens`
handling and the corpus's lens-boundary problem (W4) already address. The two
control-PASS lenses produced 13 findings of which **12 are real**: the control's
PASS on `no-sentinels` and `structured-payloads` was under-finding, not
cleanliness. One new defect neither the control nor the W2 ladder found — the
completion-rate sentence drops the publisher's "differs by institutional
calendar" hedge — came from this dispatch.

**So the honest summary is not the one the plan expected.** Front-loading is
cheaper per lens child by about a third, loses no recall on this substrate, and
finds MORE real defects — which means it also produces more triage and more fix
work. The saving is in the lens half; part of it will be spent in the fix half.
W8 is what makes the fix half cheaper, which is why the two ship together.

**Caveats, stated because they are load-bearing.** One diff, one RFC, eight
lenses, Kotlin-heavy — the same single-substrate limit the W2 ladder carries.
The cost baseline is the archive-wide measured opus-5 Tier 1/2 rate, NOT a
same-diff control: the rfc-226 control children's own costs are not recoverable
from the archive, so a same-diff cost comparison does not exist. The precision
judgements are one child's, made by reading the diff and opening six files to
settle specific claims. The experiment cost $11.45 in lens children plus the
scorer.

## Decisions

### D1 — the lens dispatch message carries rule, template, contract and diff

Inline, in that order, ending with the report path and the one-line reply
contract. No lens dispatch names a path and asks the child to fetch it.

The alternative was a partial front-load — rule inline, diff by path — on the
theory that the diff is the big part. It is rejected because the diff is the
part every lens must read: leaving it out keeps the tool-call round trip that
the change exists to remove, and saves the coordinator nothing, since the diff
never touches the coordinator's context either way.

### D2 — the message is assembled in the Python kernel, never generated

`Path(...).read_text()`, concatenated into a variable, passed to `rlm(...)`. The
coordinator must never print the assembled message, and must never write the
rule or diff text into its own reply. This is a correctness rule, not a style
one: generating the content costs it twice in the coordinator's own session, and
token-by-token composition of a large mechanical string is the shape that
produces degenerate loops.

### D3 — the child may leave the message, but only when its rule requires it

The exploration clause is explicit and narrow: everything you need is in this
message; open further files ONLY when your rule genuinely requires context
beyond the diff — `code-review-single-source-of-truth` checking whether a fact
already has a home elsewhere in the repo is the canonical case — and say in your
reply HOW MANY files you opened. A blanket "do not read anything" would break
exactly the lenses whose rule is about the rest of the repository.

The count is the clause's last clause and the reply line's last field, written
once: the obligation lives in the text the child reads, and the reply shape
points at it rather than restating it in weaker words.

### D4 — target ≤5 turns, and the number is reported, not enforced

There is no mechanism that stops a child at five calls, and none is proposed: a
cap would truncate a report mid-write and produce a lens that silently found
nothing, which is the failure mode this corpus fears most. The target is stated
in the dispatch and the actual median is reported in phase 7 for the next five
lane A runs, beside the RFC 233 D5 figures already being collected.

### D5 — a fix child runs commands only through `scripts/ship-run`

Never a bare `nix develop -c bin/test`, never a bare `./gradlew`, never a bare
`bin/format`. The rule is about what ENTERS context: `ship-run` pipes the whole
output to a file and prints only the record, so the tool result the child reads
is bounded whatever the command printed. Piping a log to a file and then reading
the file back would cost exactly what streaming it cost; the file is not the
point, the record is.

A long command is started in the background and the child ends its turn; the
completion follow-up wakes it. It does not sit blocking, and it does not need a
child to wait for it.

The child may open the log when it decides it needs more than the excerpt. What
it may not do is read it by default.

### D6 — `ship-run` emits the record, because VERBATIM has to be testable

The load-bearing word in W8 is VERBATIM. Once a fix child stops reading the
output, something else has to be exactly right: a summarised failing assertion
is a corrupted signal the child cannot detect, and it then confidently repairs
the wrong thing.

Asking a model nicely for verbatim text is not a guarantee and cannot be tested.
So the excerpt is produced by a SCRIPT, from the same shell that ran the
command, exactly as RFC 211 made the suite record an emitted artifact rather
than a reported one:

    scripts/ship-run -s <rs> -n <name> [-d <dir>] -- <command>...

writes `<rs>/runs/<name>.log` (the whole output), `<rs>/runs/<name>.failing`
(the byte-exact excerpt), and `<rs>/runs/<name>.record` (`cmd=`, `exit=`,
`tree=`, `log=`, `failing=`, and the executed test counts re-derived from the
JUnit XML the run produced). stdout is the record; the log is on disk and named
in it.

`bin/ship-scripts-tests` gets the assertion Ian asked for, against a fixture
whose failing line carries quotes, a backslash and a tab: every line of
`.failing` appears byte-identical in `.log`, and mutating the extraction fails
the assertion. This is the only form of the verbatim guarantee a harness can
check, which is why it is the form the contract takes.

### D7 — the failing excerpt is bounded, and never empty on failure

`.failing` is the log lines matching a fixed failure-pattern set plus three
lines of following context each, in file order, de-duplicated, capped at 200
lines. **When the command failed and no pattern matched, it is the last 50 lines
of the log**, verbatim. A fix child must never be told "it failed" with nothing
to look at, and a pattern list is a claim about failure formats that will be
incomplete the day a new tool is added. When the cap truncates, the record says
so and names the log.

### D8 — the suite runs once per fix BATCH, at the end

Not once per finding. A tier's fix child applies its whole batch, then runs the
suite ONCE. Per-item compiles are allowed and encouraged — they are the fast
signal — but they go through `ship-run` like everything else, and only the error
lines come back. This is what `rfc-155`'s five in-pass `bin-test` logs were, and
what the tier that needed 18 fix children paid for.

RFC 211's `suite-green.txt` record is unchanged in FORMAT and is still what
phase 5 reads. `ship-run` writes its own record; a suite run that phase 5 will
reuse still appends the RFC 211 three-line record, projected out of that record
by `scripts/ship-suite-green` rather than retyped.

### D9 — two commits, W8 first

One branch, implemented sequentially, landed as two commits so either half can
be reverted alone. W8 first, because it is the half that depended on an unproven
capability — depth-2 dispatch, which turned out to work and then turned out not
to be needed; W7 was landable on its own had it been blocked. The W8 commit is
an intermediate branch commit and the W7 commit is the branch TIP that goes
through the full `bin/pre-commit` gate — CLAUDE.md's rule is that what REACHES
`main` is verified, and the tip's tree contains both halves.

## What is given up, honestly

**A lens child that would have found something by wandering will not wander.**
The exploration clause permits rule-driven lookups and nothing else, and a
finding that needed an unmotivated browse of the repository is now less likely.
That is the trade the recall measurement above exists to price; it is reported
as a number, not assumed away.

**A fix child no longer sees the build output.** It sees an exit code, counts,
and a bounded excerpt. A defect whose signal is somewhere else in a 30k-line log
— a warning, a timing line, an unrelated stack trace — is now invisible to it.
The mitigation is that the whole log is on disk under `<rs>/`, named in the
record, and the fix child may ask for more; what it may not do is read it all by
default.

**The excerpt is a guess about what mattered.** D7's pattern set plus its
last-50-lines fallback is a claim about what failure output looks like, and a
tool whose failure format nobody anticipated gets the fallback rather than the
right lines. The harness assertion protects the excerpt's FIDELITY — the bytes
are the log's bytes — not its RELEVANCE. The log is one `ship-run` record away
when the excerpt is not enough.

**Front-loading is worse for a lens that would have read almost nothing.** A
lens that is N/A on the diff pays the whole diff in its first message to decide
that. The archive's no-op lenses (section 4: `execution-context` 77%) are
exactly this case. It is accepted: the alternative is a pre-filter that guesses
applicability, and guessing which lens can fire is what the applicability filter
already does, visibly, before dispatch.

**One number cannot be attributed.** W7 and W8 land together, but they are
measured on different child kinds — W7 in lens-child turn counts and cacheRead,
W8 in fix-child cacheRead — so the existing per-child-type, per-tier measurement
separates them without a split run.

## Detailed Design

### `references/review.md` — a new section, "The dispatch message carries everything"

Placed after "Dispatch output contract", before "What gets a fix child". It
states D1, D2 and D3: the six parts of a lens dispatch in order; that assembly
is `Path(...).read_text()` in the Python kernel and never the coordinator's
generation, with the two reasons; the exploration clause verbatim, as a quotable
block beside the graded-output instruction; the reply shape
(`lens<TAB>grade<TAB>
findings<TAB>files opened`); and the ≤5-turn target with
D4's reason for not enforcing it.

The Tier 0 case is named: an `impl-review-*` lens is measured against the RFC,
so its dispatch front-loads the RFC text as well as the diff.

The one limit is stated as a limit: when an assembled message would not fit the
child's input window, the dispatch cannot proceed as one message; the review
splits that lens by file and records why, through `ship-lens skip` if it cannot
run at all.

### `references/review.md` — "What gets a fix child" gains the command rule

Four sentences: a fix child runs build, test and format commands only through
`scripts/ship-run`; what it reads is the record and the excerpt, not the log;
the suite runs once per batch at the end, and per-item compiles read back only
their errors; a long command is backgrounded and the turn ends, rather than
waited on. The script's own contract is not restated — `SKILL.md`'s script index
and `ship-run -h` own it.

### `SKILL.md` — "Long commands run in a subagent" gains the fix-child case

The section defines the runner role, its model, its four conditions and the "run
and copy versus look and judge" line. All of that stays, for the COORDINATOR. It
gains one short paragraph saying a fix child does NOT spawn a runner: it runs
the command itself through `ship-run`, because the script already bounds what
enters its context and a child of a child adds a paraphrase hop to the one fact
that must survive. The measured depth-2 probe is cited once, as the reason this
is a decision rather than a limitation.

### `SKILL.md` — phase 5

Three touches. The lens-dispatch paragraph points at the new `review.md` section
for how a dispatch is assembled. The fix-batch paragraph states the once-per-
batch suite rule and that a fix child runs its commands only through `ship-run`.
And RFC 211's suite-reuse check reads the LAST record in `<rs>/suite-green.txt`,
not the first: this RFC makes several records per run normal — the
implementation child appends one and every fix pass that runs the suite appends
another — so the first match is the oldest tree in the run, which is the one the
tier fixes moved away from.

### `SKILL.md` — the script index and phase 7

`scripts/ship-run` and `scripts/ship-suite-green` join the index with their
one-line contracts, and the four artifacts `ship-run` writes are named there
once. Phase 7's "for the next five lane A runs" list gains two figures: the
median lens-child turn count, and the number of `ship-run` invocations recorded
in `<rs>/runs/ledger.tsv`, which is why that file exists. The ledger carries no
child identity and one `<rs>` is shared by the coordinator and every fix child
of every tier, so the figure is the RUN's and not each child's — the claim is
what that file can answer.

### `scripts/ship-run`

New, in `.prime/agent/skills/ship/scripts/`. `getopts`, short options only,
reserved usage-error exit codes from `bin/functions`, all logging on stderr.

    ship-run -s <rs> -n <name> [-d <dir>] -- <command>...

`-s` the run scratch (required; must contain a readable `state`), `-n` the
record name (required; `[a-z0-9][a-z0-9-]*`), `-d` the directory to run in
(defaults to `CODEBASE_ROOT` from the run state). Everything after `--` is the
command, executed with no shell re-interpretation.

Writes, under `<rs>/runs/`:

- `<name>.log` — stdout and stderr of the command, interleaved, nothing else.
- `<name>.failing` — D7's excerpt, byte-exact lines from the log.
- `<name>.record` — `key=value` lines: `cmd=`, `dir=`, `exit=`, `tree=`,
  `started=`, `log=`, `log_lines=`, `failing=`, `failing_lines=`,
  `failing_truncated=`, `junit_result_files=`, and — when that count is above
  zero and every one of those files could be read — `tests=`, `failures=`,
  `errors=`, `skipped=` summed across them.
- `ledger.tsv` — one APPENDED line per invocation, whatever its `-n`, each field
  named in the line itself: `started=`, `name=`, `exit=`, `cmd=`, tab-separated.
  The three files above are keyed by `<name>` and a second run of that name
  replaces them, so this is where the NUMBER of `ship-run` invocations — the
  figure phase 7 reports — survives.

A field that does not apply is ABSENT, never a placeholder: `tree=` is omitted
when the directory holds no commit, exactly as the four JUnit counts are omitted
when no XML was produced. `tree=` is the field this matters most for, because
phase 5 keys its suite-record reuse on it and two treeless runs carrying one
sentinel would compare equal. WHY a field is absent is a key of its OWN, never a
value in the field itself: `tree_absent=` names which of `no_commit`,
`not_a_repository` or `no_git` was measured, `junit_result_files=` is stated
whatever the count is, and `junit_omitted=` names which of `no_results` or
`unreadable` left the four counts out. An absent key must not mean two things.

The three `<name>` files are emptied BEFORE the command runs, so a run killed
part-way leaves an empty record rather than the previous run's standing beside
this run's log.

`cmd=` carries each word shell-quoted, so the field states what ran rather than
labelling it: joined with plain spaces, one argument containing a space reads
exactly like two. A command word containing a NEWLINE is refused with
`EXIT_INVALID_ARG_VALUE`, and so is a `-s` or `-d` path carrying one — one key
per line is the record's only structural promise, and `dir=`, `log=` and
`failing=` interpolate those paths raw. Every path the record names is absolute,
so the record is readable from any directory.

The run state is READ, never SOURCED: `awk` takes the one key `ship-run` needs
(`CODEBASE_ROOT`, and only when `-d` was not given) and nothing in that file can
assign a variable the option grammar has already validated. `-s` is therefore
authoritative over the `RUN_SCRATCH=` the state carries, and so are `-n`, `-d`
and the command.

stdout carries the record and nothing else — it is what the caller asked for.
Progress and the closing summary go to stderr. `ship-run` exits with the
COMMAND's status; a usage fault exits with a reserved code and writes no files
at all, and so does a fault of `ship-run`'s own that stopped the command running
— a directory that exists but cannot be entered — because a fault of the wrapper
is not a result of the command and must not be recorded as one. The command's
stdin is `/dev/null`: a fix child backgrounds a long command and ENDS ITS TURN,
so a command that prompts on an inherited stdin never returns and the completion
follow-up never comes. The git environment a hook exports (`GIT_DIR`,
`GIT_WORK_TREE`, `GIT_INDEX_FILE`) is stripped for `ship-run`'s OWN `git` call
only, because `git -C` loses to an exported `GIT_DIR` and `tree=` would
otherwise name the hook's repository; the wrapped command's environment is
handed to it exactly as the caller gave it.

The JUnit sum is taken only from XML files newer than a marker file written when
the run starts, so a stale `build/test-results` tree from an earlier run cannot
be reported as this run's count. It lives in ONE named function: Gradle's
results layout and JUnit's four attributes are one toolchain's business, and the
rest of the script wraps any command at all.

### `scripts/ship-suite-green`

New, beside `ship-run`. `ship-suite-green -s <rs> -n <name> -a <started-at>`
appends RFC 211's three lines — `exit=`, `cmd=`, `tree=` — to
`<rs>/suite-green.txt`, copied verbatim out of the `ship-run` record `<name>`
wrote. It writes three lines or it halts naming the key it could not find: the
projection used to be three `grep`s a fix child retyped, and a `grep` that
matches nothing appends nothing and still succeeds, so a record missing a key
appended a TWO-line record that phase 5 read as a whole one, in an append-only
file where nothing afterwards could tell the two apart.

It runs whatever the suite answered — a red record is still a record, and phase
5 needs `exit=` to tell a suite that FAILED from a suite nobody ran — so it
exits 0 for a red suite and warns that phase 5 will re-run rather than reuse.
The judging stays in phase 5. It is a separate script rather than a flag on
`ship-run` because `ship-run` is transparent to its command's status and so has
no way to fail loudly for a fault of its own.

The record it projects must be the record the invocation just made, and that is
what `-a` asks. A refused `ship-run` writes no file, so the previous run's green
record is left standing and nothing downstream can tell; `SKILL.md`'s recipe
therefore takes `date -u +%Y-%m-%dT%H:%M:%SZ` BEFORE the invocation and passes
it as `-a`, and the projection refuses a record whose own `started=` is earlier
than that instant. `-a` is required, because a projection made without the
freshness fact is exactly the stale-green projection it exists to refuse.

The caller's exit status will not do instead. `ship-run` is transparent to its
command, so a wrapped command refusing its OWN arguments exits `bin/functions`'
reserved band 10-29 exactly as a `ship-run` refusal does — `bin/test` uses that
band — and a recipe keyed on the band names the wrong cause for the first and
discards a record this invocation did write. `started=` is a fact about the
record rather than an inference from a number, at the one-second resolution
`started=` carries. `bin/ship-scripts-tests` asserts that behaviour by RUNNING
`ship-suite-green` against a green record of an earlier run — refused by name,
nothing appended — and not by reading the recipe out of `SKILL.md`.

An earlier version of this harness did read it: it cut the recipe out of the
document and executed that, so the document and the assertion could not drift.
It was removed. A test that reads a file and judges its contents is static
analysis, and this repo does not do static analysis — the recipe's BEHAVIOUR is
`ship-run` and `ship-suite-green`, both of which the harness executes directly.
It also made the document a harness input, which falsified the evidence
`bin/gate-scope`'s `markdown-docs` row is built on, and
`bin/gate-scope-scripts-tests` caught it at the gate. No test is better than a
test that reads code.

A record must also be one this script can USE, not merely one with three keys in
it: a key carried twice is corrupt rather than corrected, and a key with an
empty value is a line rather than a fact. Both are refused by name, because
`suite-green.txt` is append-only and phase 5 compares those fields.

### `bin/ship-scripts-tests`

A new banner-separated section for `ship-run`, in the harness's existing style:
a throwaway `git init` repo, a fabricated run-scratch state file, no Gradle and
no Postgres. The command under test is a small shell script the section writes
itself, so the failure output is fixed and the assertions are exact.

## Files Modified

| file                                                | change                                                                                                     |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `rfc/235-dispatch-full-run-lean.md`                 | new — this RFC                                                                                             |
| `.prime/agent/skills/ship/references/review.md`     | new dispatch section (W7); runner rule in "What gets a fix child" (W8)                                     |
| `.prime/agent/skills/ship/SKILL.md`                 | runner section gains the fix-child case; phase 4's suite recipe; phase 5 and phase 7 touches; script index |
| `.prime/agent/skills/ship/scripts/ship-run`         | new — the runner's command wrapper and record emitter                                                      |
| `.prime/agent/skills/ship/scripts/ship-suite-green` | new — RFC 211's three-line record, projected out of a ship-run record                                      |
| `bin/ship-scripts-tests`                            | new `ship-run` and `ship-suite-green` sections, including the VERBATIM assertion                           |

Nothing under `.claude/**` is touched: no lens rule, no `tiers.md`, no model
assignment.

## Implementation Plan

Sequential, one branch, in this order — W8 first (D9).

1. `scripts/ship-run`: option parsing and the reserved usage exits; the run, the
   log, the record; then D7's excerpt with its pattern set, context lines, cap
   and empty-on-failure fallback; then the JUnit sum with the `started=`
   freshness filter.
2. `bin/ship-scripts-tests`: the new section. Usage faults, the record's fields,
   the exit-code propagation, the JUnit sum including a stale-XML case, and the
   verbatim assertions — a failing fixture whose text carries a double quote, a
   backslash and a tab, asserted byte-identical in `.failing`; the no-pattern
   fallback; the cap and its `failing_truncated=` flag.
3. `SKILL.md` and `references/review.md`: the W8 prose (commands only through
   `ship-run`, once-per-batch suite, the fix-child paragraph in the runner
   section, `ship-run` in the script index).
4. Commit W8 — an intermediate branch commit, RECONSTRUCTED from the WIP
   checkpoints rather than split out of a squash. `scripts/ship-squash` is a
   flat `git reset --soft` to `BASE_SHA`, so running it first destroys the
   split, and the split cannot be recreated by path afterwards because both
   halves edit the same two files (`SKILL.md`, `references/review.md`). From the
   clean run worktree: `git reset --hard <the last W8 checkpoint>`, then
   `git reset --soft <BASE_SHA>`, then commit W8 with `--no-verify` — it is an
   intermediate commit and the tip carries the gate.
5. `references/review.md` and `SKILL.md`: the W7 prose (the dispatch section,
   the exploration clause, the reply shape, phase 5 and phase 7 touches).
6. Commit W7 through the full `bin/pre-commit` gate — the branch tip. Re-apply
   the W7 prose on top of the W8 commit — `git cherry-pick --no-commit` of the
   W7 checkpoint, or the same patch by hand — and commit it with
   `nix develop -c git commit`; that run is the branch's gate, and its tree
   carries both halves.
7. The RFC doc commit, `--no-verify`, against the tree the gate just validated.

## Tests

Shell assertions in `bin/ship-scripts-tests`, each verified by mutation — break
the line the assertion is about, watch it fail, restore:

1. **Usage.** Missing `-s`, missing `-n`, a `-n` that is not
   `[a-z0-9][a-z0-9-]*`, no `--`, an empty command, a `-d` that is not a
   directory, **a command word containing a newline**, an empty `-d`, a state
   with no `CODEBASE_ROOT`, and a `CODEBASE_ROOT` that is not a directory: each
   exits with its reserved code from `bin/functions` and writes no file under
   `<rs>/runs/`. Every refusal the script makes is reached by one of these — a
   guard nothing triggers is worth what no guard is worth.
2. **The record.** A succeeding command writes `exit=0`, the `cmd=` it was given
   — re-parsed by a shell it yields that argv word for word, so one argument
   containing a space is not two — a `tree=` equal to
   `git rev-parse HEAD^{tree}` in `-d`, and a `log=` that exists with
   `log_lines=` equal to its line count. An explicit `-d` moves the command
   itself, `dir=` and `tree=` together; a state file naming a different
   `RUN_SCRATCH` does not move the three files off the `-s` that was given.
3. **Exit propagation.** A command exiting 7 makes `ship-run` exit 7 and the
   record say `exit=7`.
4. **VERBATIM — the assertion this RFC exists to make testable.** A fixture
   command prints a failing assertion containing `"`, `\` and a tab, then exits
   1. Every line of `<name>.failing` is byte-identical to a line of
      `<name>.log`, and the fixture's assertion line is present with its
      quoting, backslash and tab unaltered — compared with `cmp`, not with a
      pattern.
5. **Never empty on failure.** A command that fails while printing nothing a
   pattern matches still produces a non-empty `.failing` — the log's last lines
   — and a zero-output failure produces an empty file and a record that says so
   rather than a missing file.
6. **The cap.** A failure printing 5,000 matching lines yields at most 200 in
   `.failing` and `failing_truncated=yes` naming the log; a small failure yields
   `failing_truncated=no`.
7. **A clean run has nothing to excerpt.** `exit=0` yields `failing_lines=0`.
8. **JUnit counts.** A fabricated `build/test-results/test/*.xml` tree is summed
   into `tests=`/`failures=`/`errors=`/`skipped=`; an XML file older than
   `started=` is NOT counted, and a run with no XML at all omits the four fields
   entirely rather than writing zeros.
9. **stdout discipline.** stdout is exactly the record; every diagnostic is on
   stderr; `-h` prints help on stdout and exits 0.

No test asserts anything about an agent's prose. The verbatim guarantee is a
property of a file a script wrote, which is why it was moved into a script.

## What this does not do

It does not change which lenses run, any lens rule text, the model used for lens
children, or the model used for fix children. Those are W2 and W5. It does not
touch `.claude/skills/review-fix/tiers.md`. It does not change the RFC 211 suite
record, the RFC 233 graded contract, or the RFC 234 ledger and outcome record.
