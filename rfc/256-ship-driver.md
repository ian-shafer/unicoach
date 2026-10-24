# RFC 256 — The ship driver: code between the judgement calls

**Status:** proposed. **Base:** `main@24a1bf7c`.

`/ship` today is a model reading a 50 KB skill and doing bookkeeping by hand:
dispatching reviewers, waiting, writing ledgers, deciding whether to re-run a
suite, running the land sequence, assembling the report. Measured over 74 landed
runs (2026-08-13 to 2026-10-21), the coordinator spends 70–140 model turns on
that bookkeeping in the review step alone, each turn re-reading a 200k–500k
token context; coordinators used 4.2 billion tokens, children 10.5 billion; a
landed run takes a median 15 hours wall-clock of which about 4 are machine time,
and 60% of the waiting ends with Ian nudging a run that had stopped with work
left.

This RFC moves everything between the judgement calls into a Python module that
runs in the coordinator's kernel. The model keeps a turn only where judgement is
needed: setting the dials, designing, presenting the approval, resolving a
review exception, writing the report's prose. Dispatching, waiting, the ledgers,
the re-run decision, the land sequence and the report's numbers are code. The
fourteen scripts under `scripts/` are unchanged: they encode landed decisions
and are tested, and the driver calls them exactly as the skill prose told the
model to.

## Summary

- **D1** — a `ship_driver` Python package, imported into the kernel, runs the
  phase machine as ONE background `asyncio` task per run; phases are coroutines,
  state is on disk, and a restart re-enters at the recorded phase.
- **D2** — waiting is a poll loop inside that task; the task wakes the model
  with a `bash()` notification only at a judgement point; a watchdog heartbeat
  resumes a driver the harness killed.
- **D3** — the two lanes become four dials: design document, review scrutiny,
  approval stop (derived), test depth (derived through `bin/gate-scope`). Three
  presets — mechanical, bug fix, feature — are names for Ian, not code paths.
- **D4** — reviewers are selected by a fixed map from changed-path classes to
  the lenses that can fire on them; every skip is a `ship-lens skip` with the
  shared reason; on the archived outcomes, four lenses stop being dispatched.
- **D5** — per-tier triage is code: grades dispatch the fix batch; the model is
  woken for five exception kinds only.
- **D6** — the coordinator's long commands run as driver subprocesses through
  `ship-run`; the cheap runner child of RFC 233 is retired.
- **D7** — implementers and fix children write a progress file and compact at
  every checkpoint and at a 300k-token ceiling.
- **D8** — the driver's journal is the budget instrument: the report states
  coordinator model turns (judgement and absorbed), child count, child tokens,
  and wall-clock per phase, computed from files.

## What the harness can and cannot do (measured 2026-10-24)

Each claim below was probed in this session before the design was written. The
design rests on the first three; the rest bound it.

| Primitive                                | Verified behaviour                                                                                                                                                                                                                                                                                                                                                                  | Survives kernel restart?            |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| kernel `asyncio` task                    | keeps running after the model's turn ends (ticked for 150 s across three idle turns)                                                                                                                                                                                                                                                                                                | no — dies with the kernel           |
| `bash()` launched from that task         | its completion delivers `[bash-done …] Command: <text>` to the idle session and starts a turn — the command text is a free wake-up message                                                                                                                                                                                                                                          | n/a (one-shot)                      |
| a child's exit                           | ALWAYS delivers `[child-exited: no-reply <name>]` to the parent and starts a turn, whether or not the child sent a message; a notice that lands mid-turn is queued and delivered after the turn, not lost (all five probes arrived)                                                                                                                                                 | children keep running (supervisor)  |
| `rlm.collect(targets, timeout_ms=N)`     | returns immediately with `settled=False` for running children (0.4 s with N=60000) — it does NOT block; and its `settled`/`done` can be reported while the child is still streaming (rfc-256 Tier 0), so it is not a completion signal — `agent_observe.get_agent(name)`'s `isStreaming` is the liveness read                                                                       | yes (reads the supervisor)          |
| `rlm.spawn`                              | returns a handle at once; the handle is not awaitable; `rlm.list_subagents()` re-finds children by name; a LARGE prompt (51 KB, ~300 KB) can start the child before the prompt is delivered — its first turn sees no task (rfc-256: the implementer and 4 of 5 Tier 0 lenses) — so the driver writes the prompt to `children/<name>/dispatch.md` and spawns with one line naming it | yes (children are supervisor-owned) |
| `agent_message`                          | a child's reply wakes the parent, but the parent's inbox caps at 20 pending and refuses the rest (rfc-244 archive) — files are the fan-in, messages are a hint                                                                                                                                                                                                                      | yes (daemon)                        |
| `rlm_heartbeat`                          | a recurring prompt to this session, `steer` or `follow_up`; the one primitive that fires with nothing else alive in the kernel                                                                                                                                                                                                                                                      | assumed yes — not provable here     |
| `compact.run()` / `compact.status()`     | schedules a compaction for the end of the turn; the kernel persists; auto-compaction is off in this installation, so every compaction is explicit                                                                                                                                                                                                                                   | n/a                                 |
| `rlm.factory`                            | a state machine whose every state is a model child, answers capped at 160 characters, run registry in kernel memory, disabled by default (`/factory on`) — cannot host a pure-code step                                                                                                                                                                                             | no                                  |
| `goal`                                   | one persistent objective re-prompted across turns; created only at Ian's request; no API to park or resume on a condition                                                                                                                                                                                                                                                           | yes                                 |
| `bin/gate-scope` over a run's whole diff | `GIT_INDEX_FILE=<tmp> git read-tree <BASE_SHA>` then `gate-scope -r <root>` classifies the base-to-HEAD name set without touching the real index (verified on this checkout: `scope=full`)                                                                                                                                                                                          | n/a                                 |

Two consequences shape everything below. First, the ONLY things that run with no
model turn are a kernel task and a subprocess, so the driver is a kernel task
and its waits are polls. Second, a child's exit cannot be made silent, so a
fan-out of N children costs the coordinator N wake-ups whatever the design does;
D2 makes each one cost nothing but a context read, and D8 counts them.

## Decisions

### D1 — one kernel task per run; phases are coroutines; the run is on disk

`ship_driver` lives at `.prime/agent/skills/ship/driver/ship_driver/` and is
imported from the ORIGINAL checkout — the landed skill drives the run, so a run
that edits the driver (this one) cannot break itself mid-flight. The scripts are
invoked from the same checkout for the same reason. Stdlib only, Python 3.11
(the kernel) and 3.13 (the dev shell) both run it.

The public surface is five calls, and SKILL.md teaches nothing else:

```python
drv = await ship_driver.start(instruction, dials, slug=None, brief=None)  # claims, starts the task
drv = await ship_driver.resume()                               # after restart/compaction: ship-status -p → re-enter
await drv.answer(kind, payload)                                # the model's judgement, by kind
drv.status()                                                   # one line: phase, step, children, waiting-on
await ship_driver.watchdog()                                   # the heartbeat's one cell
```

The phase machine:

```text
claim → [design → approve] → implement → verify(rebase, tier 0..3, suite, format) → land → report → complete
```

Each phase is `async def run_<phase>(run, dials) -> Next`; `Next` names the
phase to enter. A judgement is `payload = await run.ask(kind, line, **context)`
INSIDE the phase, because a phase uses the answer and then continues: `ask`
writes `<rs>/driver/ask.json`, journals it, wakes the model (`run.wake`, a
`bash("echo ship-wake …")`), polls `<rs>/driver/answers/<n>.json`, and returns
the payload. An `ask.json` of the same kind already open is re-used, so a
re-entered phase takes an answer written while the task was dead. The loop:

```python
while phase != "complete":
    run.enter(phase)                 # journal + DRIVER_STEP
    outcome = await PHASES[phase](run, dials)
    run.exit(phase)
    phase = outcome.phase
```

**State.** `ship-state` keeps the facts it keeps today, plus `DIAL_DESIGN`,
`DIAL_SCRUTINY`, `DIAL_APPROVAL`, `DIAL_TESTS` (the `gate-scope` line, rewritten
after every rebase) and `DRIVER_STEP` (the sub-step inside a phase, e.g.
`verify/tier2/fix`). Values that only the agent session means — the driver's
session id, the heartbeat id, the coordinator's log and Prime Agent's directory
— are never in the shared state file that every script sources; `start()` binds
them once, from the harness, in `<rs>/driver/session.json`. The driver's own
files live under `<rs>/driver/`:

```text
journal.jsonl     one event per line: phase/step enter+exit, spawn, settle, wake, answer, subprocess
ask.json          the open judgement, or absent
answers/<n>.json  every model answer, numbered
children/<name>/  dispatch.md (the assembled prompt), progress.md (the child's), result.json
wakes.jsonl       every bash() wake issued, with its reason
session.json      the agent-session bindings: driver_session, heartbeat_id, coord_log, agent_dir
```

**Resume.** `resume()` runs `ship-status -p`, keeps the runs whose `PHASE` is
not `complete` and whose `session.json` names THIS session (another session's
run is never adopted; two of its own it refuses and names), re-reads `state`,
finds the last `enter` event in the journal without an `exit`, re-finds live
children by name through `rlm.list_subagents()`, and re-enters that step. Every
step is written to be re-entered: a lens that has a `start` line and a report
file is `done`; one with a `start` and no file is re-dispatched; a fix child
still running is waited on, not re-spawned (the archive's RFC 116 lesson). A
`PHASE` that is stale is a defect the journal exposes, because the journal's
last `enter` disagrees with it, and `resume()` says so rather than guessing.

The kernel variable `drv` is a convenience; after compaction it is still there,
after a restart `resume()` rebuilds it. Nothing the run needs is held only in
the kernel.

### D2 — a wait never ends a turn without a wake-up

**The wait.** Inside the task, waiting on children is a 15-second poll of two
reads per child: its **marker file** — a lens report's `**Grade:**` line,
`## Done` in a writer's `progress.md`, a narrowing child's `result.json` — and
whether it is still streaming (`agent_observe.get_agent(name)`). A child is
finished when its marker says so and it has stopped streaming; `rlm.collect`'s
`settled` is not read, because it reports `done` while a child is still
streaming. A child that is not streaming for four polls in a row, with no
`ship-run` of its own open in `<rs>/runs/ledger.tsv`, has stopped.

and waiting on a subprocess is `await proc.wait()`. Neither ends a model turn,
because neither is a model turn: the coordinator's turn ended the moment it
called `start()` or `answer()`. Ian's clock still runs — but the run is moving,
and `drv.status()` or `ship-status` shows where.

**The wake-up.** When a phase calls `run.ask`, the task calls

```python
bash(f"echo 'ship-wake {run_id} {kind}: {one_line}; read {ask_path}'")
```

whose completion the harness delivers as a `[bash-done]` notice to the idle
session, starting a turn. The text names the kind and the file, so the woken
model reads one JSON file and nothing else. Every wake is appended to
`wakes.jsonl`, so the report can say how many turns the driver asked for.

**The notices the driver did not ask for.** Every child exit also starts a turn.
SKILL.md gives the coordinator one standing rule for the duration of a run: a
`[child-exited]` or `[bash-done]` notice that does not begin `ship-wake` is
answered with no tool call and no text — the driver saw the same event through
`collect`. Such a turn is one context read with no output; D8 counts them
separately from judgement turns. To keep that read small, the coordinator
compacts at three boundaries — after approval, before verify, before land — with
`compact.run("ship run <id>: state is on disk at <rs>; on wake read
<rs>/driver/ask.json")`.

**Stalls.** Every child is spawned with one line naming its `dispatch.md`, so
the task arrives with the spawn whatever its size. A child that stopped without
its marker is sent ONE wake message
(`agent_message.send(..., receiver_role='child')`: "re-read your dispatch and
progress file and finish") — the archive shows that resumes it with its context
intact, and an errored child ("Retry failed after 3 attempts") recovers on a
message too. A lens that stops again, or runs past its 30-minute ceiling, is
re-dispatched once, then skipped with `ship-lens skip -r "child stalled twice"`;
an implementer or fix child that stops again is an `Ask` of kind `child-failed`.

**If the harness dies mid-wait.** The task dies; children keep running under the
supervisor and keep writing to `<rs>/`; subprocesses the driver started keep
running too (the suite finishes and `ship-run` writes its record). Recovery has
two legs. The first is the **watchdog heartbeat**, created by `start()`:
`rlm_heartbeat.create("ship watchdog: await ship_driver.watchdog()",
interval="*/30 * * * *", delivery_mode="follow_up")`.
The interval is a five-field cron string: `rlm_heartbeat.create` rejects
`"30m"`, and its one-shot `"in <N>m"` form does not recur. Its cell imports the
module (a fresh kernel has not) and asks: is there a live driver task for a run
whose `PHASE` is not `complete`? If yes it prints one line and ends; if no it
calls `resume()`. It is paused while the driver is parked on an approval —
nothing to resume — and deleted at `complete`. Whether a heartbeat itself
survives a daemon restart could not be proved in this session; the second leg
does not depend on it: SKILL.md's resume rule is one call,
`await ship_driver.resume()`, and `ship-status` shows a run whose state file has
not been touched in an hour. A heartbeat that does survive turns the second leg
into Ian never having to make that call.

The watchdog costs one cheap turn per 30 minutes while a run is live. A run
under this driver is expected to take 4–6 hours of machine time, so 8–12 such
turns; the report counts them with the other absorbed turns.

### D3 — four dials replace two lanes

Ian runs `/ship` with a description and never names a lane. The skill reads the
instruction, looks at the code it names, sets the dials, and states all four in
words in its first message with the reason for each. Presets are vocabulary:

| Preset         | design | scrutiny | approval  | tests   | examples                                                  |
| -------------- | ------ | -------- | --------- | ------- | --------------------------------------------------------- |
| **mechanical** | none   | none     | skipped   | derived | docs, a rename, a new `bin/` script with its own harness  |
| **bug fix**    | none   | targeted | skipped\* | derived | a failing test first, then the fix; obvious shape         |
| **feature**    | rfc    | full     | required  | derived | new behaviour, a new surface, a schema change, a decision |

\*unless the derivation below requires it.

**design** — `none` or `rfc`. `rfc` when the change has more than one reasonable
shape and the wrong one is costly to reverse, or creates new behaviour, a new
surface, a table, a migration, a prompt change. `none` when the shape is given
by the instruction or by a failing test. `rfc` claims `ship-claim -l rfc`;
`none` claims `-l quick -s <slug>` — the scripts' lane vocabulary is unchanged
and `LANE` stays in the state file as the branch naming's fact, not the run's.

**scrutiny** — `none`, `targeted`, `full`. `none`: no lens runs; the suite and
the hook are the review. `targeted`: the lenses the applicability map (D4) says
can fire on the changed path classes, narrowed optionally by one cheap call,
repairing BLOCKING findings only — SHOULD-FIX is held and reported. `full`:
every lens the map allows, no narrowing, repairing BLOCKING and SHOULD-FIX. Tier
0 runs only when `design=rfc` (it measures code against an RFC); with
`design=none` the five `impl-review-*` lenses are skipped with the reason
`no RFC in this run`. Rule of thumb the skill applies: `none` for docs-only and
for a one-file fix with a failing test; `targeted` for a fix under four code
files; `full` whenever `design=rfc`, or the diff touches `db/schema/`,
`prompts/`, money or auth, or more than three code files.

**approval** — derived, never chosen. Required when `design=rfc`, or when the
instruction or the changed paths touch anything irreversible: `db/schema/**`,
`db/seed/**`, `prompts/**`, the money paths (`service/**/budget/**`,
`service/**/subscriptions/**`, `appstore/**`, StoreKit, the paywall), auth,
account or data deletion, public copy (`PRODUCT.md`, `HomePage`). Each path glob
must match a tracked path (a test checks); a subject with no code yet is caught
by the instruction's words. Otherwise skipped — the first message is
`FYI — NO ACTION NEEDED` with the dials and the plan, and the run proceeds. The
derivation runs twice: at `start()` from the instruction and the paths it names,
and again after implementation from the actual diff. A diff that trips a rule
the instruction did not predict turns the dial up: the run stops before verify
with `YOUR DECISION REQUIRED`. That is the one way a dial moves mid-run, and it
replaces switching lanes.

**tests** — derived through `bin/gate-scope`, the same script and table the hook
uses, over the run's base-to-HEAD name set (the temp-index technique above).
Verify runs `nix develop -c bin/test` when `gradle=yes`; runs
`bin/shell-tests -n <name>` for each harness `harnesses=` names, or
`bin/shell-tests` when it says `ALL` — gate-scope's `ALL` means "whatever
`bin/shell-tests` runs", and the driver keeps no path table of its own (the set
RFC 211 left ungated in phase 5 is closed by CALLING the map, never copying it);
runs `bin/format -c` when the diff has `.kt`/`.kts`. The skill never sets this
dial and SKILL.md does not carry the table. The hook is still the authority;
verify is incremental reassurance that now matches the hook's shape.

**A fix that uncovers a design choice.** The implementer or the model states the
choice. Reversible — no schema, no stored data shape, no prompt, no public copy
— state the default, proceed, report `FYI`. Irreversible — `Ask` of kind
`decision`, `YOUR DECISION REQUIRED`, and the approval dial is now `required`
for the rest of the run. A decision is not an approval: the post-implementation
re-derivation still runs unless `APPROVED=yes`, so a diff that then touches an
irreversible path is still presented.

### D4 — reviewer selection by evidence and by change

**The map.** `applicability.py` classifies each changed path into one class —
`kotlin`, `swift`, `ts`, `python`, `sql`, `shell`, `markdown`, `config`,
`prompt` — by extension and, for extensionless files, by shebang. Each lens
declares the classes it can fire on. The table is the one source; the archive's
82 skip reasons were used to write it and every row is one of three shapes:

| Lens group                                                                                                                                                                                                                                                                                                                                       | Fires on                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------- |
| typed-language rules: `constructor-di`, `execution-context`, `immutable-returns`, `structured-payloads`, `unit-encapsulation`, `lossless-domain-mapping`, `no-sentinels`, `impossible-misuse`, `domain-outcomes`, `feature-isolation`, `no-leaks`                                                                                                | kotlin, swift, ts, python                                     |
| shell-only: `semantic-output` (its rule is `log-error`/`fatal` in scripts sourcing `bin/functions`)                                                                                                                                                                                                                                              | shell                                                         |
| every other code lens (`dry-abstractions`, `exhaustive-eval`, `single-source-of-truth`, `allowlist`, `error-bubbling`, `parameterize-bounds`, `single-level-abstraction`, `explicit-routing`, `reuse-hardened-primitives`, `bracket-serialization`, `handle-return-values`, naming and comment lenses, every `design-review-*` not listed above) | any code class: kotlin, swift, ts, python, sql, shell, config |
| `impl-review-*`                                                                                                                                                                                                                                                                                                                                  | any code class, and only when `design=rfc`                    |

`markdown` and `prompt` fire nothing — the archive's single most common skip (41
lines) is "markdown-only, no lens can fire", and the real risk there, factual
accuracy, is the coordinator's. A run whose every path is `markdown` or `prompt`
runs no lens at any scrutiny; the dial statement says so.

The tier's dispatch set is still `ship-lens missing -t N` with no ledger line
written (every lens returns `UNDISPATCHED`), filtered by the map; every lens the
map removes gets `ship-lens skip -r "<shared reason>"` with ONE reason text per
tier naming the classes present. The accounting invariant and the close
condition (`missing` exits 0) are unchanged.

**Optional narrowing** (`targeted` only, off until measured). One call to a
cheap model (`deepseek/deepseek-v4-pro`, the RFC 233 runner's model) with the
diff and the remaining lens descriptions, returning the subset that can fire. It
may only REMOVE lenses, each removal is a `skip` whose reason is the model's
sentence prefixed `narrowed:`, and the report counts them. It ships behind
`Dials(narrow=False)` because its cost is one child and its benefit is unknown;
the first three targeted runs turn it on and the report compares.

**Ranking by evidence.** 1,971 outcome records across 20 archived runs
(`tier*-outcomes.jsonl`, the RFC 234 format) joined to 969 `start` lines in 25
`lens-ledger.tsv` files. The yield that matters is **accepted fix-grade findings
per dispatch** — ADVISORY findings are never applied, so a lens that produces
only those is a lens the run pays for and acts on never. Top and bottom of the
table:

| Lens                                   | dispatched | fix-grade findings | accepted | declined |    accepted / dispatch |
| -------------------------------------- | ---------: | -----------------: | -------: | -------: | ---------------------: |
| `code-review-dry-abstractions`         |         24 |                 67 |       61 |        1 |                   2.54 |
| `design-review-no-remote-breakage`     |         24 |                 65 |       60 |        2 |                   2.50 |
| `design-review-actionable-errors`      |         23 |                 51 |       49 |        1 |                   2.13 |
| `code-review-single-level-abstraction` |         23 |                 52 |       49 |        2 |                   2.13 |
| …                                      |            |                    |          |          |                        |
| `code-review-constructor-di`           |         21 |                 14 |        5 |        2 | 0.24 (+4 contradicted) |
| `code-review-bracket-serialization`    |         23 |                  4 |        4 |        0 |                   0.17 |
| `code-review-standard-verbs`           |         23 |                  2 |        2 |        0 |                   0.09 |
| `code-review-execution-context`        |         21 |                  5 |        1 |        0 |                   0.05 |
| `code-review-concrete-names`           |         24 |                  0 |        0 |        0 |                   0.00 |

The full table is `<rs>/research/lens-ranking.tsv` in this run's archive and is
regenerated by `ship_driver.rank_lenses(archive_dir)` so the next decision reads
fresh figures.

**What stops being dispatched — Ian's decision at approval.** Four lenses:
`code-review-concrete-names` (24 dispatches, no fix-grade finding ever),
`code-review-standard-verbs` (2 accepted in 23),
`code-review-bracket-serialization` (4 accepted in 23) and
`code-review-constructor-di` (5 accepted in 21, and the corpus's most
contradicted rule: 4 of the archive's 16 contradictions).
`code-review-execution-context` is KEPT despite 1 accepted in 21. The driver
carries them in `RETIRED: frozenset[str]` and skips each with
`-r "retired by RFC 256: <figure>"`, so the tier's accounting still closes. The
rule files themselves live under `.claude/skills/`, which this skill never edits
and whose one writer is `/principle`; deleting them from the corpus — and their
`tiers.md` rows, which Claude Code's `/review-fix` also reads — is a separate
`/principle` change, named as an open item in this run's report.

**Size.** The skill's Markdown is 92 KB today (SKILL.md 51.7, review.md 32.4,
visual-gate.md 5.4, boundaries.md 2.6) and the coordinator reads SKILL.md at the
start of every run. Targets: SKILL.md under 10 KB; review.md under 15 KB (its
record formats, ledger semantics and encoder snippets become code the driver
runs, keeping only the text a child must read); driver.md and dials.md under 12
KB together; about 40 KB of Markdown in all. Prose that only restates an RFC's
reasoning is deleted, not moved: the RFCs are immutable and already carry it.
The driver adds Python; the report states both the Markdown and the code sizes,
before and after.

### D5 — per-tier triage is code; the model is woken for exceptions

Grades are machine-readable by RFC 233's contract: `**Grade:**` under the
verdict, a `[TAG]` on every finding heading. The driver parses each report,
re-dispatches a `🔴 FAIL` with no tag once with the instruction quoted, and
builds the fix plan from the tags — `BLOCKING` always, `SHOULD-FIX` at `full`,
`ADVISORY` held — writing `tier<N>-fix-plan.md` with the four sets RFC 234
names. One fix child per tier (two writers in one worktree race on
`ship-checkpoint`, RFC 140 archive), dispatched with the findings inline, the
`ship-run` contract, the outcome-record contract, and for Tier 0 the approval
record. After it settles the driver validates `tier<N>-outcomes.jsonl` (every
line parses; every conditional field on its own arm), appends `done` lines, runs
`missing -t N`, and reads `CONTRADICTION:` lines from the fix report.

The coordinator's "may overrule a tag" is dropped from the default path: in 20
archived runs the promoted/demoted set is 1 record. What it bought is kept where
it was earned — the model is woken, with the tier's reports named, for exactly
these kinds:

| `Ask` kind              | when                                                                                                                      |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `approved-decision`     | a Tier 0 fix would amend an RFC line in `<rs>/approval.md` (the fix child records `DEFERRED` with `amends_approved` true) |
| `blocking-contradicted` | a `BLOCKING` finding's outcome is `CONTRADICTED`                                                                          |
| `malformed-twice`       | a lens report has no grade after the re-dispatch                                                                          |
| `child-failed`          | a fix child or implementer exited twice without its completion marker, or reported a red suite it could not fix           |
| `scope-deny`            | `ship-verify-scope` hit a deny glob (the driver has already run `ship-recover`)                                           |

Everything else — grade counts, held advisories, out-of-lens hand-offs, decline
classes — reaches Ian in the report, where it reached him before.

Those five are the review exceptions. Outside review, one more kind exists
because something must happen when a verify gate the driver itself runs — the
suite, the shell harnesses, `bin/format -c`, `bin/prompt-seed` — is red:
`suite-red`, with the record and its failing excerpt named. The model fixes it
and answers; the driver checkpoints `verify-fix` and re-runs that gate. A red
gate inside the land lock is `hook-failed` (Detailed Design, `run_land`).

### D6 — the coordinator's long commands are driver subprocesses

RFC 233 spawned a cheap "runner" child to run the suite, the hook or
`installDist` because the coordinator model could not wait without spending a
turn. The driver can. Every coordinator-side gate now runs as
`asyncio.create_subprocess_exec("…/ship-run", "-s", rs, "-n", name, "-c",
"driver", …)`
— output to `<rs>/runs/<name>.log`, record to `<name>.record`, `-g` on the suite
— and the driver reads the record. The three facts the runner copied are read
from the file the shell wrote, with no model and no paraphrase hop; Ian watches
`<rs>/runs/<name>.log` or `drv.status()` instead of a child's session.
`ship-run`'s `caller=driver` keeps phase 7's per-caller figure honest. Fix
children still run their own commands through `ship-run` (RFC 235) and spawn
nothing.

The suite re-run rule (RFC 211, four conditions on the LAST `suite-green.txt`
record) is `phases.suite_needed(run) -> (needed, reason)`, and the reason is
journaled. Its fourth condition — whether the tree's move since the green run
could change the suite's answer — is answered by CALLING `bin/gate-scope` over
the recorded-tree-to-HEAD name set (the same temp-index technique as the tests
dial): `gradle=no` reuses. This supersedes RFC 211's docs-only path list for the
driver: `bin/gate-scope` did not exist when RFC 211 was written, and it now owns
"which paths Gradle reads" (its header forbids a second copy). The `cmd=` it
compares is `Toolchain.suite_cmd`, the same words the driver's own suite gate
runs and both writer prompts print, rendered as `ship-run` renders them.

### D7 — children compact, and write first

Implementers and fix children get this in their dispatch:

```text
Checkpoint protocol. After every `scripts/ship-checkpoint`, and whenever
`await compact.status()` reports more than 300000 tokens, (1) write
<rs>/driver/children/<your-name>/progress.md — what is done, what remains as a
numbered list, the exact next command, and the path of the last ship-run record
you read; (2) then `await compact.run("<your-name>: continue from progress.md")`.
Finish by appending `## Done` and one line to that file. The parent reads the
file, not your reply.
```

Lens children are short-lived and do not compact. The 300k ceiling is a third of
the window with headroom for one more build log; the first runs report each
child's compaction count and peak so the figure can be moved on evidence.
`progress.md` is also D2's completion marker and the thing a re-dispatched or
woken child reads first, so compaction and recovery share one file.

### D8 — the coordinator's budget, and how the report proves it

Targets, in **judgement turns** (turns that follow a `ship-wake`, Ian's message,
or the opening instruction):

| Preset     | judgement turns | what they are                                                                                            |
| ---------- | --------------: | -------------------------------------------------------------------------------------------------------- |
| mechanical |             ≤ 3 | dials + brief; report prose; the DONE announcement                                                       |
| bug fix    |             ≤ 5 | dials + brief; 0–2 exceptions; report prose; DONE                                                        |
| feature    |            ≤ 12 | dials; design (1–3, research children included); approval present + record; 0–3 exceptions; report; DONE |

Absorbed turns — child exits, watchdog beats — are counted but not budgeted:
their number is the child count plus the run's half-hours, and their cost is a
context read each.

The report's **Spend** section is written by `report.py` from files alone:

- coordinator model turns, split judgement / absorbed — judgement from
  `answers/` (one per Ask answered; the `wakes.jsonl` count is reported beside
  it, since a re-entered Ask wakes again), absorbed as the remainder of
  assistant messages in this session's log (`coord_log`, bound by `start()` from
  the harness) between the journal's first and last timestamps, with their
  summed `usage.totalTokens`;
- children: count by role (lens, fix, implementer, research, narrowing), and
  token totals summed from each child's session file, found through
  `<agent_dir>/rlm-ledger/*.jsonl` (`agent_dir` bound by `start()`; `op=spawn`
  rows map a parent to a child file) — a child whose file carries no usage is
  listed as `unmeasured`, never zero;
- wall-clock per phase from the journal's enter/exit pairs, and machine time as
  the sum of `ship-run` start/end spans in `<rs>/runs/ledger.tsv`;
- the `ship-run` invocation count per caller, as today.

Phase 7's existing figures (grade counts, the re-dispatch line, contradictions,
`missing` verdicts, learning candidates) are unchanged in content and now
assembled by code; the model writes the opening sentence, the learning
candidates, and nothing else.

## Detailed Design

### `ship_driver` package

```text
.prime/agent/skills/ship/driver/
  ship_driver/
    __init__.py       start, resume, watchdog, rank_lenses; the Driver class (answer, status); open_phase;
                      the claim runs inside start() (a Run needs the claimed scratch), journaled as `claim`
                      only once setup succeeded, so a failed start() leaves no run to resume
    harness.py        LiveHarness: spawn, busy (the agent_observe roster; absent = not busy, an unreadable
                      roster raises), list_subagents, delete, send, wake (a bash echo), session_id,
                      session_log, agent_dir, heartbeat_create/update/delete, compact — read from the
                      kernel's globals; tests inject a FakeHarness
    run.py            Toolchain (original checkout, scripts, tool prefix, poll interval: passed to start()
                      and resume(), held by Run); Run: state (read in-process, written via ship-state),
                      session.json, journal, ask/answer files, phase + step, wake(), ship_run(); sh, the
                      one async process runner; parse_assignments, read_record, ship_run_ledger
    answers.py        the answer type each Ask kind accepts; a wrong shape is refused at answer()
    dials.py          Dials dataclass; derive_approval(design, paths, instruction="") -> (verdict, reason);
                      test_depth(root, base_sha) via gate-scope; dial_line / parse_dial_line
    phases.py         Next; run_design/approve/implement/verify; suite_needed(run) -> (needed, reason)
    land.py           run_land, run_report: the phase-6 sequence as one coroutine returning Landed or
                      NotLanded; lock release on every non-land exit; a run already archived is not re-landed
    review.py         tier runner: dispatch set, applicability skips, lens dispatch assembly, grade parsing,
                      fix plan, fix child dispatch, outcomes validation, close
    applicability.py  LENS_GROUP (lens -> typed | shell | code | impl), RETIRED, classify(paths, root),
                      allowed(lens, classes, design), skip_reason(lens, classes, design)
    children.py       spawn(run, role, name, prompt, marker=) via dispatch.md, refind, wait, nudge,
                      finish (the stall policy), the checkpoint-protocol text
    report.py         Spend + the phase-7 sections from files; session/rlm-ledger readers
    ranking.py        rank_lenses(archive_dir) -> {"rows": [...], "unparsed": [...]}; the figures in D4
  tests/              stdlib unittest; a FakeHarness; fixtures are throwaway git repos and run-scratch dirs
```

`Dials` is `dataclass(frozen=True)`: `design: Literal['none','rfc']`,
`scrutiny: Literal['none','targeted','full']`, `narrow: bool = False`;
`approval` and `tests` are `Run` properties read from the state file, never
`Dials` fields.

`start(instruction, dials, slug=None, brief=None, harness=None, tools=None)`:
refuses if `ship-status` shows a live run from this session (one driver per
session); runs `ship-claim` with the lane the design dial implies; writes
`DIAL_*`; writes `<rs>/instruction.md`; creates the watchdog; binds
`session.json`; journals the claim; starts the task; returns the Driver. With
`design=rfc` the first `Ask` is `design` (the model writes the RFC in
`CODEBASE_ROOT/rfc/<n>-<slug>.md` and answers with its path); the driver checks
the four Tier 0 sections exist and asks `approval` next; the model presents and
answers `{'approved': True, 'decisions': [...]}` which becomes
`<rs>/approval.md` and `APPROVED=yes`. With `design=none` and approval not
required, the first `Ask` is none: the implementer brief is the instruction plus
the model's plan, supplied in `start(..., brief=...)`, written together to
`<rs>/brief.md` (the instruction first, then `## Plan`).

`run_implement`: `ship-checkpoint before-impl` (the point a denied write is
reset to with `ship-recover -c`: the implementer's own checkpoints may carry
it); assembles the implementer dispatch (brief or RFC, the `ship-run -g` recipe
with `-c <name>`, the deny list, the checkpoint protocol), spawns, waits, runs
`ship-verify-scope` (tee to `findings/write-scope.txt`), `ship-checkpoint impl`,
re-derives approval from the diff (D3), then `Next(verify)`.

`run_verify`: `set PHASE verifying`; `ship-checkpoint before-verify`;
`ship-rebase` (conflict → `Ask('rebase-conflict')`); `prompt-seed -b BASE_SHA`
when `prompts/` changed; `DIAL_TESTS` from gate-scope; for tier in 0..3:
`review.run_tier`; then `suite_needed` →
`ship-run -g -- nix develop -c bin/test`; named harnesses; `bin/format -c` if
Kotlin — each through `gate()`, where a red gate is `Ask('suite-red')`;
`Next(land)`.

`run_land`: `set PHASE landing`; `ship-checkpoint before-land`; `report.write`
(the numbers) and `Ask('report-prose')` for the model's two paragraphs; then the
sequence — `ship-lock acquire`, `ship-rebase` (then `DIAL_TESTS` rewritten for
the base as landed), `prompt-seed`, `ship-order`, `ship-squash`, `bin/format`,
`git add -u`, then ONE `git commit` through the hook (a subprocess; the hook's
output to `<rs>/runs/hook.log` via `ship-run -n hook`) — the RFC markdown is
inside it, so the landed tip is itself the hook-verified tree and no
`--no-verify` commit exists — then `ship-land`. Any non-land exit releases the
lock FIRST, classifies (shell harness → the written procedure; tests →
`Ask('hook-failed')`), and journals. After `ship-land` the driver appends the
landed SHA and the Spend section to the archived report and asks `announce` —
the model's DONE message is the last judgement turn; the driver then sets
`PHASE complete`, deletes the watchdog, exits the task.

### Lens and fix dispatch assembly

Unchanged in content from `references/review.md` — six parts, in order, from
files on disk, concatenated in Python — and now assembled by `review.py` rather
than by the model, which is what "assemble in the kernel, never generate" always
meant. The graded-output instruction and the file-opening clause stay in
`review.md` and are READ from it, so the text the child sees and the text the
reference documents cannot drift.

### SKILL.md

Rewritten to under 10 KB: the standing rules (non-negotiables, talking to Ian,
the notice rule), the five driver calls, the dials and presets with the first
message's required shape, the judgement kinds and what each asks of the model,
the resume rule, and pointers. Phase mechanics, the suite rule's prose, the land
sequence's reasons, the prompt-seed invariant's history and the runner rationale
move to `references/driver.md`; the dial derivations and the applicability
table's rationale to `references/dials.md`. `references/review.md` loses "the
coordinator may overrule a tag" and the runner-model paragraph, and gains the
five `Ask` kinds. `../shared/operator-comms.md`'s table row for "lane A / lane
B" becomes the presets. `../slice/SKILL.md:254` stops naming a lane and names
the preset it expects.

### What is unchanged

`bin/pre-commit`, `scripts/ship-lock`, `scripts/ship-land`,
`scripts/ship-order`, `bin/prompt-seed`, `bin/gate-scope`, the review corpus
under `.claude/`, every other `scripts/ship-*`, and `bin/ship-scripts-tests`'
existing sections.

## Files Modified

- `.prime/agent/skills/ship/driver/ship_driver/*.py` — new, listed above
- `.prime/agent/skills/ship/driver/tests/*.py` — new
- `.prime/agent/skills/ship/SKILL.md` — rewritten, < 10 KB
- `.prime/agent/skills/ship/references/driver.md` — new
- `.prime/agent/skills/ship/references/dials.md` — new
- `.prime/agent/skills/ship/references/review.md` — triage-by-code, Ask kinds,
  runner paragraph removed
- `.prime/agent/skills/ship/references/boundaries.md` — "lane A/B" wording
- `.prime/agent/skills/shared/operator-comms.md` — one table row
- `.prime/agent/skills/slice/SKILL.md` — one sentence at line 254
- `bin/ship-scripts-tests` — one new section that runs
  `python3 -m unittest discover -s .prime/agent/skills/ship/driver/tests`
- `rfc/256-ship-driver.md` — this file

## Implementation Plan

1. `applicability.py`, `ranking.py`, `dials.py` with tests — pure functions over
   paths, archive files and a throwaway repo; `rank_lenses` reproduces D4's
   table from `.scratch/ship-archive`. Verify: unit tests green.
2. `run.py`, `harness.py`, `children.py` — state, journal, ask/answer, wake,
   wait, stalls, against a `FakeHarness` whose children settle on a script.
   Verify: a fake run parks, wakes, answers, resumes from a journal with a dead
   task.
3. `review.py` — dispatch set from `ship-lens missing`, skips, assembly from the
   real corpus, grade parsing over archived reports (every archived `tier*-*.md`
   parses or is `malformed`), fix plan, outcomes validation. Verify: re-triage
   the archive. Measured: of the 72 archived tier triages with graded reports
   and outcome records, 57 give the same fix-grade (lens, finding id) set as the
   hand triage's outcome records. The other 15 differ for reasons code cannot
   reproduce: older finding-id schemes (rfc-244 string ids, rfc-251 short lens
   names), promotions and second-pass reports (rfc-230, rfc-231, rfc-232,
   rfc-254), and findings the fix child left without a record (rfc-234, rfc-238,
   rfc-240). A test pins the floor of 57.
4. `phases.py`, `land.py`, `report.py` — the machine end to end against a
   throwaway repo with a fake hook (`git commit` with a stub `bin/pre-commit`
   that calls `ship-verified record`). Verify: a fake mechanical run lands and
   its report's Spend section is populated from files.
5. `bin/ship-scripts-tests` section;
   `nix develop -c bin/shell-tests -n
   ship-scripts-tests` green.
6. SKILL.md and references rewritten; `bin/format` clean; SKILL.md measured
   under 10 KB.
7. Land this RFC's run through the OLD skill (this session). Then the DONE
   condition: one landed run per preset under the new driver — a docs/mechanical
   change, a bug fix with a failing test, and a feature with an RFC — each
   report showing coordinator turns, child count, child tokens and wall-clock
   per phase. Those three are separate `/ship` invocations after this lands;
   this RFC's own report records the baseline it was measured against.

## Tests

Stdlib `unittest`, run by `bin/ship-scripts-tests`, no network, no Gradle, no
Postgres; the harness is a `FakeHarness`; git fixtures are `git init` temp
repos; the lens corpus is the real one under `.claude/skills/` (read-only).

- **applicability**: every lens in `tiers.md` has a row; a Kotlin-only diff
  allows the typed-language group; a shell-only diff allows `semantic-output`
  and disallows `constructor-di`; a markdown-only diff allows nothing; a lens in
  `RETIRED` is never allowed; the shared skip reason names the classes present.
- **ranking**: `rank_lenses` over a fixture archive of three runs produces the
  dispatched / fix-grade / accepted / declined / per-dispatch columns; a
  non-JSON line is named (path:line) as unparsed, not skipped silently; over a
  fixture archive carrying both outcome spellings, `to_tsv` equals a checked
  table exactly.
- **dials**: `derive_approval` is `required` for each irreversible path class
  (every glob in `IRREVERSIBLE_GLOBS`) and for `design=rfc`, `skipped` for a
  `service/` test-only diff; `test_depth` over a throwaway repo returns
  gate-scope's three lines and leaves the real index untouched
  (`git diff --cached` empty before and after).
- **run / journal**: `ask` writes `ask.json` and a journal line, wakes, and
  returns once `answers/<n>.json` appears; an answer written while the task was
  dead is taken on re-entry; a `PHASE` disagreeing with the journal is reported,
  not acted on.
- **resume**: a run whose task died parked on approval (`design=none`, approval
  derived `required`) is resumed by `watchdog()` through `resume()` and lands; a
  second `start()` from one session is refused; `resume()` and `watchdog()` take
  this session's run and never another session's; a `start()` that fails before
  its journal leaves no run to resume and can be retried; a driver that dies
  after `ship-land` resumes without landing again.
- **children**: every spawn prompt is one line naming `dispatch.md`, which holds
  the assembled prompt; `wait` returns `done` only once the marker is written
  and the child is quiet; a lens still streaming is waited on, not
  re-dispatched; a child with its own `ship-run` open is not idle; a stopped
  lens gets one wake message and is not re-dispatched when it then reports; a
  lens with no report, or over its ceiling, is re-dispatched once then skipped
  with `child stalled twice`; a fix child without `## Done` gets one wake
  message then `Ask(child-failed)`, whose answer is acted on — `finished` is
  re-checked and asked again while the work is undone, `respawn` deletes the
  child and waits on a fresh one; a stalled lens child is deleted on every
  stall.
- **phases**: an RFC missing a Tier 0 section is asked for again; a
  `design=none` diff that adds an irreversible path asks `approval` before
  `verify` is entered, and so does one after a `decision`; a denied write is
  reset to `before-impl`, asks `scope-deny`, and a re-dispatched implementer
  lands; the implementer's dispatch carries the instruction and the plan.
- **review**: dispatch set equals `missing`'s `UNDISPATCHED` list minus the
  map's removals; every removal has a `skip` line; grade parsing over every
  archived report under `.scratch/ship-archive/*/findings/tier*-*.md` yields a
  grade or `malformed`; a `FAIL` with no tags is re-dispatched once; the fix
  plan holds ADVISORY, dispatches BLOCKING at `targeted`, adds SHOULD-FIX at
  `full`; outcomes validation rejects a conditional field on the wrong arm; a
  fix record `DEFERRED` with `amends_approved` true asks `approved-decision` and
  a `CONTRADICTED` BLOCKING asks `blocking-contradicted`; the archive's graded
  triages re-triage to the hand triage's set for at least 57 of 72.
- **suite rule**: `suite_needed` is false for an identical tree with a green
  `bin/test` record, including one the real `ship-run -g` wrote; true for a
  non-zero exit, a scoped `cmd=`, a dirty tree, an unreadable recorded tree and
  a `.kt` move; false for a move the real `bin/gate-scope` calls `gradle=no`
  (`product/`, `markdown-docs`). `harnesses=ALL` runs `bin/shell-tests` whatever
  the diff touches.
- **contracts**: the report parser and the lens's finished marker accept what
  review.md's graded instruction tells a lens to write; every approval glob
  matches a tracked path; a wrong-shaped answer is refused while the ask stays
  open.
- **land**: on a failed `git commit` the lock is released before the `Ask`; the
  lock is acquired no earlier than another run's fixture lock expires, before
  `ship-squash`; `DIAL_TESTS` is rewritten after the land-time rebase; the
  archived report carries the Spend section.
- **report**: coordinator turns are counted from a fixture session log between
  two timestamps and split by the answers, with the wakes reported beside them;
  a child file with no usage is `unmeasured`.
- **budget**: the fake mechanical run spends ≤ 3 judgement asks; the fake
  feature run ≤ 12 — the test is the budget table's enforcement.
