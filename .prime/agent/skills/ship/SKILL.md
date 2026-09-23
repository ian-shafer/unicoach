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

## Talking to Ian

Every message this skill sends Ian follows
[`../shared/operator-comms.md`](../shared/operator-comms.md): expand an internal
name the first time it reaches him (`D33`, `S4`, tier 2, lane A, `BASE_SHA`),
then use the short form; put anything that stops the run in the
`YOUR DECISION REQUIRED` block; open every message with one of the five triage
labels and lead with the outcome in plain words.

That file is the contract. This skill's own prose is agent-facing and keeps its
vocabulary — the rule governs what is SAID to Ian, not what is written here or
in an RFC.

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
    scripts/ship-lens    -s <rs> start|done|skip|missing # the per-lens ledger
    scripts/ship-run     -s <rs> -n <name> -c <caller> [-g] -- <command>...
                         # run it, record it; -g writes phase 5's suite record
    scripts/ship-verified record|check|assert       # the CI stand-in
    scripts/ship-lock    -s <rs> acquire|release|status  # the land gate
    scripts/ship-land    -s <rs>                    # assert, ff-merge, teardown

`ship-run`'s artifacts under `<rs>/runs/` are its own contract — `ship-run -h`,
which owns it and is interpolated from the constants it describes. One of them
is this file's business: phase 7 reports the number of `ship-run` invocations
**per caller**, and `<rs>/runs/ledger.tsv` is what that is counted from. It
records EVENTS — a `start` line before the command runs, an `end` line after —
so a run killed mid-command is still counted, and counting invocations is
counting `start` lines (RFC 236). `ship-run -h` gives the two lines' fields,
including what an invocation that named no caller writes; they are not restated
here, because a second copy of a wire format is one nothing checks.

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
- **Ian can watch it without asking.** A child's session is in the harness,
  live, under the same view as everything else. A `nohup` log is a path he would
  have to know and `tail` himself. This is the difference between a wait he can
  see and a wait he has to interrupt.
- Context hygiene: a 1300-line Gradle log lands in the child's context, and four
  lines land in this one. Over a run that gap is large.
- A child can REACT — capture the failing assertion verbatim, check whether it
  reproduces — where a log file only sits there.

**A runner is spawned cheap** (RFC 233). A child is a _runner_ when its whole
job is to execute a command this session names and report the outcome under the
contract below — the suite, `bin/test check`, the hook, the land sequence,
`installDist`, `bin/format`, `bin/shell-tests -n <name>`, a compile. Spawn it
with `model='deepseek/deepseek-v4-pro'`, the model with a measured record of
doing this job correctly — RFC 233 carries the figures, which are not repeated
here. Safe because the run never trusted the runner's prose — phase 5 reads
`suite-green.txt`, which the shell wrote, and re-derives the count from the
JUnit XML. If the model is unavailable the spawn fails: retry once with no
`model` argument and say so in the report. **A runner is never skipped because
the cheap model was down.** Anything that must DECIDE — which test to re-run,
whether a failure reproduces, what a trace means — is not a runner and inherits
this session's model. The line is _run and copy_ versus _look and judge_.

Four conditions, each from a real failure:

1. **State the report contract.** Exit code, executed test counts, and failing
   text VERBATIM. Not a summary — a gate result is exactly where the skill's
   two-hops-of-paraphrase warning bites.
2. **The child logs to disk as well as replying**, so a stall still leaves
   evidence and `agent_observe` shows where it stopped. Log **under `<rs>/`**:
   `ship-land` copies the whole run scratch to
   `$ORIGINAL_CHECKOUT/.scratch/ship-archive/<run-id>/` BEFORE it removes the
   worktree, so a land log written there survives the teardown and is read from
   the archive afterwards. A run that reports its gate numbers were lost with
   the worktree has not looked.
3. **The tree is frozen while a child runs a gate.** Editing a file a child is
   executing invalidates its run: bash reads a script incrementally, and that
   run has to be thrown away. Whoever runs the gate owns the tree until it
   reports.
4. **A green result is valid only for the commit it ran on.** The reply names
   the SHA; check it has not moved — the same reasoning as the land lock.

**All of the above is the COORDINATOR's.** A fix child spawns no runner: it runs
the command itself, through [`scripts/ship-run`](scripts/ship-run) (RFC 235).
That script already bounds what enters its context — the output goes to a file
and the child reads a record — so a grandchild would read that file and retype
three facts, at the cost of a spawn round trip charged at fix-child prices and
one more hop of paraphrase through the one fact that must survive verbatim. The
capability was probed before the design was written, and depth-2 dispatch works;
that is what makes this a decision rather than a limitation.

**Foreground** stays right for anything short (under about a minute), and for
the case where waiting is the only work left. **`nohup` is not banned** — it
suits fire-and-forget work whose result the run does not need — but a detached
job owns its poll, and a single poll that finds the job still running means a
wake-up is now required before the turn ends.

**Landing is the case that must report itself.** Announce the wait before it
starts, run the post-lock sequence — squash, format, `git commit`, `ship-land` —
as ONE unit rather than a turn per command, and make the report the next thing
generated after it returns. A `git commit` that succeeded is not the news; a
landed SHA is. The same holds for a failure inside the lock: release, then
report at once. "Landed", "failed" and "blocked" are terminal outcomes, and a
terminal outcome is announced the moment it is known, ahead of any other
content.

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

This gate STOPS the run, so every decision in it takes the
`YOUR DECISION REQUIRED` block from
[`../shared/operator-comms.md`](../shared/operator-comms.md) — one decision per
block, two to four options stated as outcomes rather than mechanisms, a
recommendation with one reason, and the default that is taken in silence. Say
the lane in words ("mechanical change, no design doc"), not as a letter alone. A
new database table is presented as its DDL, in the block, with the cost of being
wrong stated (0001 D10).

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

**That includes the SUITE** (RFC 211). A child that runs the suite runs it AFTER
`scripts/ship-checkpoint` and through [`scripts/ship-run`](scripts/ship-run),
never bare (RFC 235) — with **`-g`**, which writes phase 5's own file from the
same process:

    scripts/ship-run -s <rs> -n suite -c <caller> -g -- nix develop -c bin/test

One command. No status arithmetic, no second command, no timestamp. `-g` appends
RFC 211's record to `<rs>/suite-green.txt` from the values the process that RAN
the suite already holds, byte-identical to the record it just wrote —
`ship-run -h` gives the keys, interpolated from the one array that defines them,
and they are not restated here. It re-reads nothing, so nothing has to ask
whether the record it found belongs to this invocation (RFC 236).

That question is why this recipe was three lines and a timestamp until RFC 236,
and it is worth knowing why it could not be answered. The projection was a
SECOND process reading `<rs>/runs/suite.record`, and a `ship-run` that REFUSED
its invocation — a usage fault, a `CODEBASE_ROOT` that is no longer a directory
— writes no record at all, so the PREVIOUS run's stood there: complete,
well-shaped and green, for bytes this invocation never tested. The exit status
cannot tell the two apart, because `ship-run` is transparent to its command and
`bin/test` refusing its OWN arguments exits `bin/functions`' reserved band
exactly as a refusal does. Nor could a one-second `started=` window, which is a
window and not a fact. The process that ran the command does not have the
question to answer.

A RED run is projected too, and says so on stderr. Phase 5 needs `exit=` to tell
a suite that FAILED from a suite nobody ran, and it re-runs the suite for both —
that is phase 5's rule, and `-g` does not anticipate it. What `-g` will NOT do
is write a PART of the record: when a key cannot be measured — `tree=`, when the
directory is not a repository — it writes NONE of the lines and names the
reason, because the file is append-only and phase 5 keys on all of them, so a
fragment is one nothing downstream can tell from a whole record.

`-c <caller>` is the invoking child's own name, and it is REQUIRED (RFC 236 D5,
amended). `ship-run` cannot discover it, and phase 7's invocation figure is per
caller: an unnamed invocation and a named one must not look alike in a file
whose only job is counting.

Emitted, not REPORTED — and copied, never retyped. A hash retyped into a message
is prose, and a child that runs the suite green, fixes a typo, checkpoints, and
honestly reports the post-edit hash has produced a record for bytes nothing ever
tested. `ship-run` answers `exit=` and `tree=` in the same process that ran the
suite, and writes the record itself, so the file is measurement. The child still
reports the outcome in words, for Ian; phase 5 reads the file.

Phase 5 then reuses it instead of re-running the same suite over bytes Gradle
cannot distinguish. Re-running a gate a child reported green, with no edit in
between, is distrust dressed as diligence, and it costs minutes. It is NOT a
"second sample of a deterministic function" — the suite is DB-backed and
CLAUDE.md itself names `-f` for flake hunting — but a second sample of a flaky
suite over identical bytes is weak evidence, it is not free, and the
authoritative run is the hook minutes later.

An opportunistic improvement the design never mandated is not blocked here. It
is surfaced at review as scope creep and consciously kept or reverted -- silent
blocking loses a real insight, silent acceptance compounds drift.

**A change to the coach system prompt is an edit to
`prompts/coach-system-prompt.md`, and to nothing else** (RFC 181, RFC 185). The
seed migration under `db/schema/` is a GENERATED artifact that phase 6 rewrites
from that file: a hand-written seed is overwritten, and a hand-edited one is
refused. The body IS that file, verbatim -- no trim, no join, no re-wrap -- so
Markdown works and a line break is body text. Author it **one sentence per
line**: that line granularity is the whole reason two runs editing different
paragraphs now merge without a conflict. `prompts/` is excluded from `deno fmt`
in `deno.json`, because a reflow would silently rewrite the prompt; keep it
excluded. A tab and an interior double space are ordinary text now. Do not touch
the `systemPromptVersion` pin -- phase 6 owns it, because the version label is
only knowable at land.

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

Every lens is dispatched with the graded-output instruction in
[`references/review.md`](references/review.md) — a `**Grade:**` line and a tag
per finding — and **only `SHOULD-FIX` and `BLOCKING` findings get a fix child**
(RFC 233). The dispatch carries the rule text, the template, that instruction
and the diff **inline**, assembled in the Python kernel from files on disk and
never generated; "The dispatch message carries everything" in the same file is
the contract, down to the clause that tells a child when it may still open a
file of its own (RFC 235). A fix child that cannot apply a finding without
breaking another rule **halts that item**, names both sides, and never picks a
side; `review.md` gives the four counterparty kinds, the halt line it quotes,
and the record it writes (RFC 234). A defect a lens reports under
`## Out of lens` — real, but outside its own rule — is dispatched to that tier's
fix batch by its grade like any other finding, **but only within this run's own
diff**: a defect in code the run did not touch is an open item in the report,
because fixing it would make the landed change something Ian did not approve.

A fix child applies its **whole batch**, runs no command bare, and runs the
suite ONCE at the end — the rule, with its reasons, is **A fix child runs its
build, test and format commands through `scripts/ship-run`** in
[`references/review.md`](references/review.md) (RFC 235). It is stated there and
not restated here: two copies of one instruction drift, and the fix-child
dispatch is built from that file.

Resolving a Tier 0 finding is the run's call as it always was, with one
exception: when the amendment would change a decision **Ian approved at the
gate**, it is his, in the `YOUR DECISION REQUIRED` block. A contradicted
`BLOCKING` finding goes to him the same way and in the same block, quoting both
rules and the code (RFC 234) — same rule, same gate.

Persist as you go, one file per lens, so a stall costs one lens rather than the
tier: `<rs>/findings/tier<N>-<lens>.md`, declared in
`<rs>/findings/lens-ledger.tsv` through `scripts/ship-lens` (RFC 234) — `start`
at dispatch, before the child does any work; `done` when it replies; `skip` with
a reason for a lens this diff cannot fire; and
`ship-lens -s <rs> missing -t <N>` before the tier closes, which must exit 0.
Empty output is not the test: the script answers "I could not read the corpus"
with its own status, and a caller that reads stdout cannot tell that from a
clean tier. It leaves a VERDICT in `<rs>/findings/tier<N>-missing.txt` — the
faults, or nothing when the tier is clean, or `INCOMPLETE` and the reason when
the check could not finish — and that file, not memory, is what phase 7 reports
each tier close from. The ledger replaces `lens-plan.json`, which was written
from memory afterwards and so could claim a lens ran with no report on disk. The
tier's fix plan records what was dispatched, what was held as advisory, any tag
the coordinator promoted or demoted with its one-line reason, and what was
contradicted. Record the write-scope result too —
`ship-verify-scope … | tee <rs>/findings/write-scope.txt` — so the archive shows
the check ran, not merely that nobody complained.

Then the suite — `nix develop -c bin/test`, dispatched to a child like any other
long command — and, for any UI change, screenshots as artifacts (see
[`references/visual-gate.md`](references/visual-gate.md)).

**Run it only if the tree has MOVED in a way the suite could notice** (RFC 211).
At the point the suite would run — after the tier fixes, which is where the tree
stops moving inside phase 5 — check FOUR things, not two:

    git status --porcelain            # empty
    git rev-parse HEAD^{tree}         # equals tree= in the LAST record in <rs>/suite-green.txt
    # exit=0 in that record
    # cmd= is exactly "nix develop -c bin/test"

**The LAST record, not the first** (RFC 235). The file is append-only and a run
now writes several: the implementation child appends one, and every fix pass
that runs the suite appends another. Reading the first match reads the oldest
tree in the run, which is exactly the tree the tier fixes moved away from.

All four: do **not** re-run. Cite the record as phase 5's suite evidence, name
the tree hash in the report, and re-derive the executed count from the JUnit XML
rather than from the child's sentence. Anything else — a different tree, a dirty
file, a non-zero exit, a module-scoped or `-f` command, a missing or unreadable
record — runs the suite. The default answer is always "run it".

`cmd=` is not ceremony: a child that ran `bin/test rest-server` or
`bin/test -f
db` produces an equally valid-looking record, and reusing that
would be strictly weaker than today while contradicting CLAUDE.md's "do not
scope by module".

**When the tree differs, ask WHAT differs.** Keyed on the raw hash alone this
rule would fire only when literally nothing happened, and a one-word fix to an
RFC Markdown file would buy a full suite re-run:

    git diff --name-only <recorded-tree> HEAD

Every path under `product/`, or `rfc/*.md`, or a `*.md` under
`.prime/agent/skills/`: the Gradle suite's answer cannot have moved — none is a
build input — so reuse the record. One path outside that set re-runs. This is
RFC 194's reasoning applied to a different decision, and it is deliberately not
shared code with it.

The TREE hash is the identity, not the commit SHA, for the reason
`ship-verified` already gives: amending, re-committing identical content, and a
no-op rebase all preserve the tree, so the marker survives exactly the
operations that did not change what was tested. Note that the tree can still
move AFTER this decision — phase 6 runs `bin/prompt-seed`, `ship-order`,
`bin/format` and a rebase — and that is fine. Phase 5's suite is not the
authority; the hook runs after all of them, on the tree that lands.

**Plain `bin/test`, and NOT `bin/test check`** (RFC 211). No module argument, no
`-f`: Gradle's own dependency graph picks the tasks the diff invalidated, which
is what CLAUDE.md means by "let the cache do the scoping". Phase 5 is not the
authoritative run and must not pretend to be — **the pre-commit hook is the
single authoritative `check` run**, and it is minutes away. Running `check` here
proves the same thing about the same tree twice: run rfc-208 spent 4m26s on
`bin/test`, then ~15 min on `bin/test check` whose Gradle half was
`96/96 up-to-date`, and then the hook ran `check` a third time. Report the exit
code and the EXECUTED count from the JUnit XML `tests=` attribute — never
`grep -c "@Test"`, which counts commented-out annotations.

**Phase 5 runs NO shell harnesses at all.** That is a deliberate, temporary gap,
not an oversight (RFC 211). `bin/test check` is the only thing that runs
`bin/shell-tests`, and the hook is the only thing that runs `bin/test check`. So
a diff that touches `bin/`, `infra/`, `.prime/agent/skills/`, `db/schema/`,
`db/seed/`, `db/data/`, `prompts/` or `.env*` gets NO harness signal until the
commit. When it fails there, follow the shell-harness procedure in phase 6.

Targeting — running only the harness a given path can break — needs a
path-to-harness mapping, and that mapping is being built ONCE, as a real script
with tests, by the RFC that attacks the hook's own harness cost. Phase 5 will
re-add targeting by CALLING that script. It must never carry a second copy of
the table: a wrong map here fails SILENTLY, because the hook catches what it
missed, while a wrong map in the hook does not — so the copy that gets exercised
and trusted would be the one whose errors are invisible.

Close verify with `nix develop -c bin/format -c` — read-only, measured at 7.07s
— **but only when the diff contains `.kt` or `.kts`.** Otherwise skip it.

The reason is narrow and it is the only one that survives. Phase 6 already runs
`bin/format` in WRITE mode before the commit, and for Markdown `deno fmt` is
exhaustive: the write fixes everything the later check would report, so a
Markdown-only run learns nothing here. Kotlin is different. `ktlint --format`
auto-corrects what it can and LEAVES what it cannot, so a non-correctable
violation survives the write and fails `bin/format -c` at the hook — where it
runs CONCURRENTLY with `bin/test check`, so it discards a 10-20 minute suite run
with it, inside the land lock. Seven seconds here against that.

Not because another session's file might be in your tree: RFC 201 gave every
writer its own worktree, so there is none.

### 6. land

`ship-state -s <rs> set PHASE landing`, then
`ship-checkpoint -s <rs> before-land`. Write the report (phase 7) **first**:
`ship-land` deletes the worktree.

    scripts/ship-lock   -s <rs> acquire   # ← enter the critical section
    scripts/ship-rebase -s <rs>          # no-op if nothing moved since verify
    nix develop -c bin/prompt-seed -b "$(scripts/ship-state -s <rs> get BASE_SHA)"
    scripts/ship-order  -s <rs>          # re-place this run's ORDER lines
    scripts/ship-squash -s <rs>
    nix develop -c bin/format
    git add -u                           # re-stage what the formatter rewrote
    nix develop -c git commit            # code — through the FULL hook. The gate.
    nix develop -c git commit --no-verify  # RFC markdown only, lane A
    scripts/ship-land -s <rs>            # ff-merge, then releases  ← exit

**`git add -u` is not decoration** (RFC 211). `ship-squash` is
`git reset --soft`, so the run's work lives in the INDEX; `bin/format` (write
mode — it runs `ktlint --format` and `deno fmt`, and it is what stops a stray
Markdown file from costing a gate run) rewrites the WORKING TREE. Without the
re-stage the commit carries the UNFORMATTED bytes, and nothing in the gate says
so: the hook's `bin/format -c` checks the working tree and passes,
`bin/test check` passes, and then `ship-verified record` declines with
`working tree differs from index` — so `ship-land`'s `ship-verified assert`
refuses AFTER the full gate has been spent. `-u` and not `-A`: a formatter
cannot create a file, so staging tracked modifications is exactly enough.

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
itself". A `git commit` that succeeded is not the news; a landed SHA is.

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

`bin/prompt-seed` sits at the same seam and for the same two reasons (RFC 181).
The coach system prompt is authored as `prompts/coach-system-prompt.md` and its
seed migration is a GENERATED file: the script deletes this run's own seed,
recomputes the version label as one past the highest coach seed in `BASE_SHA`,
and rewrites it from the source. So it must run **after** the final rebase, or
the label is a prediction of a tip that has since moved, and **before** the
squash and the commit, so the regenerated seed lands inside the hook-verified
tree. A run that touched no prompt writes NOTHING -- no seed, no `ORDER` line,
no repin -- because a version is cut only when the authored body differs from
the catalog tip's (RFC 185 D6). That is what makes it safe to run on every land,
exactly like `ship-order`; it used to mint a new immutable row at every land
instead, whether or not a word was authored. **Pass `-b` explicitly.** The
default base is `main`, and a run claimed against any other base would then
classify the PARENT branch's landed coach seed as its own generated file and
delete it — the one file this script must never touch. `ship-land` re-runs it as
`bin/prompt-seed -b "$BASE_SHA" -d "$CODEBASE_ROOT/db/schema" -n` before the
fast-forward and refuses with that script's own status (3 = regenerate, anything
else = the corpus or the source file is refused). The fix is
`nix develop -c bin/prompt-seed -b "$(scripts/ship-state -s <rs> get BASE_SHA)"`,
re-commit through the hook, re-run `ship-land`.

It also owns the **pin**: it rewrites `systemPromptVersion = "vNN"` in
`service/src/main/resources/service.conf` to the row this land leaves the
runtime serving — the label it just generated, or the catalog TIP when it
generated none, which moves the pin BACKWARD off a version an earlier run cut
and a later edit reverted. The label is computed at land, so a literal nobody
moves drifts from it the moment another run lands first — the seed says `v25`
while the pin still says `v24`, and the runtime is served a row this run did not
generate. The append-only comment log above that pin is still the **author's**
to write; the script rewrites the one assignment line and passes every other
byte of the file through.

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
`nix develop -c bin/test` stays **outside** it — that run is incremental
reassurance, not the gate, and holding the lock through review would serialise
the part of a run that has no reason to be serial. The hook does not RE-run the
suite; it IS the run (RFC 211). What reaches `main` is hook-verified exactly as
strongly as before: `bin/pre-commit` runs `bin/format -c` and the full
`bin/test check` — Gradle, Postgres, both `installDist` builds, and all SEVEN
shell harnesses. Say it precisely: the hook validates the WORKING TREE, not the
index (CLAUDE.md: "the hook validates the whole working TREE rather than the
commit"). What ties that to the commit is `ship-verified record`'s own guard —
it records nothing when the working tree differs from the index — so such a
commit gets no marker and `ship-land`'s assert refuses it. RFC 211 removed a
rehearsal, not a check. When the hook fails on a harness rather than on Gradle,
follow the shell-harness procedure below.

**A base that advanced by `product/**` commits only does NOT cost a second gate
run** (RFC 193). Those commits never take the lock — a `docs:` commit is an
ordinary commit from a session that is not in phase 6 at all — so they move the
base while a run is inside its gate, and they did it four times to one run on
2026-09-11. `ship-land` therefore classifies the advance itself: when every
commit in it touches nothing but `product/`, it replays this run onto that tip
and lands, recording `LAND_FAST_PATH=docs-only` and the commits it classified in
the run state, in `<rs>/land-fast-path.md`, and appended to `<rs>/report.md`.
Anything else — one code path, a merge, an empty commit, a replay that conflicts
— falls back to today's refusal, and the run rebases and re-runs the gate as
before. The fast path skips the **gate**, not the ancestry check: the
fast-forward is still confirmed, and `main` can move again while step 3a runs.
Say in the report which of the two happened; the run state says so too.

**If the hook fails on a SHELL HARNESS** — any of the seven:
`ship-scripts-tests`, `prompt-seed-scripts-tests`, `docs-scripts-tests`,
`test-calendar-seed-scripts-tests`, `q-scripts-tests`, `db-scripts-tests`,
`scripts-tests` — the answer is written down, so it is not improvised at the
gate (RFC 211). In this order:

1. **Release the lock first**, before diagnosing anything:
   `scripts/ship-lock -s <rs> release`.
2. **Capture the failing assertion VERBATIM** to
   `<rs>/findings/hook-shell-failure.txt`, with the harness name and its section
   header. The worktree is deleted at land; the hook's output is the only
   record.
3. **Classify, in this order.** _Environmental_ (missing `installDist` artifact,
   a daemon another checkout stopped, a Postgres hiccup) — fix the environment,
   change no code, re-enter phase 6 at the top. _This run's defect_ — fix,
   `ship-checkpoint`, and re-run ONLY the harness that failed, BY ITS OWN NAME
   from the seven above — note `bin/scripts-tests` has no prefix, so a
   `bin/<name>-scripts-tests` template cannot name the largest and likeliest of
   them; the full gate is the hook and it runs next anyway. _Pre-existing and
   not this run's_ — confirm it fails at `BASE_SHA` too, from the original
   checkout, then **stop and report to Ian**. Repairing an unrelated harness
   inside a land is a new run with its own approval.
4. **Re-enter the phase 6 block at the top** — `ship-lock acquire`, then
   `ship-rebase` — never at the `git commit`.
5. **Never `--no-verify` past it.** The `--no-verify` commits this repo permits
   are enumerated, and this is not one of them.

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

Open it with `DONE` and one sentence a non-engineer could repeat — what a user
can now do, or what the run established. The detail follows. An open item names
who must act.

RFC number/slug, landed SHAs with `--stat`, what was built, per-tier findings
applied/discarded, the test counts actually executed, and **open items** — every
finding the review declined to decide. Point at
`.scratch/ship-archive/<run-id>/`.

Then **Advisory findings (not applied)**: every `ADVISORY` finding the tiers
held, one line each — lens, `file:line`, title (RFC 233). It is a queue for Ian
like the learning candidates below, and `None` is a real result. For the next
five lane A runs, also state the grade counts per tier — which must account for
every lens that ran, since a lens returning no grade is the one case D1 cannot
tolerate — whether the malformed-report re-dispatch fired and on which lens, the
number of fix children spawned, each runner child's model and cost, the
contradiction count by `contradicts_kind`, the outcome counts by value and by
lens, the decline classes, the `Out of lens` count, and what `ship-lens missing`
printed at each tier close, including "nothing" (RFC 234).

Two more figures, one per half of RFC 235. State the number of `ship-run`
invocations recorded in `<rs>/runs/ledger.tsv` — the count of `start` lines —
**per caller**: each line carries a `caller=` field holding the `-c` that
invocation passed, so the figure is per fix child rather than per run. `-c` is
required, so every line in that file is attributed and the figure has no
unattributed remainder (RFC 236 D5, amended in tier 2). A run that was killed
still counts, because its `start` line was written before the command ran and
stands there with no `end` beside it. And state the median model-call count
across the run's lens children, which is the figure front-loaded dispatch is
measured in. A guard with no trace is indistinguishable from an absent one,
which is why the re-dispatch line is stated even when the answer is "it did not
fire", and why a tier close that found nothing missing still says so.

Then **Contradictions**: every finding recorded `CONTRADICTED`, one line each —
lens, `file:line`, the counterparty, and why no change satisfied both (RFC 234).
This is **not** the learning-candidate queue below, and keeping them apart is
what decides where `/principle` looks: a learning candidate is _no rule covered
this_, so the corpus is missing something; a contradiction is _two rules covered
it and disagreed_, so the corpus has something too wide. `None` is a real result
for both.

Then **Seen but not owned**: every defect a lens reported under `## Out of lens`
— the lens that saw it, `file:line`, its grade, and whether it was fixed here or
left as an open item because it sat outside this run's diff (RFC 234).

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
