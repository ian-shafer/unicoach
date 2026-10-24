# The driver: phases, files, land

`../driver/ship_driver/` runs one background `asyncio` task per run (RFC 256),
calling the `../scripts/` tools (each has `-h`) from the original checkout. The
code is the authority; this file is its map.

## The phase machine

    claim → [design] → [approve] → implement → verify → land → report → complete

`PHASE` in `ship-state` follows the phases; `DRIVER_STEP` names the sub-step
(e.g. `verify/tier2`). A phase returns the next phase. A judgement inside it is
`run.ask(kind, …)`: write `ask.json`, wake the model, poll `answers/` every 15
s. The driver schedules a compaction after approval, before verify and before
land.

- **claim** — `ship-claim -l rfc` for `design=rfc`, `-l quick -s <slug>` for
  `design=none`. `LANE` stays in state as the branch-naming fact.
- **approve** — runs for `design=rfc`, or when approval was derived `required`
  at `start()`; writes `<rs>/approval.md` and `APPROVED=yes`.
- **implement** — one implementer child. Its dispatch carries the brief or RFC,
  the `ship-run -g` suite recipe, the deny list, the prompt-seed and visual-gate
  rules, and the checkpoint protocol. An irreversible question it writes to its
  `decision.md` becomes `Ask('decision')`, and the answer is sent to it. Then
  `ship-verify-scope` (to `findings/write-scope.txt`), the `impl` checkpoint,
  and approval re-derived from the diff.
- **verify** — `before-verify` checkpoint; rebase first, so the tree reviewed is
  the tree that lands; `bin/prompt-seed -b BASE_SHA` when `prompts/` changed,
  again after Tier 3; the tests dial from `bin/gate-scope`; Tiers 0 → 3
  ([`review.md`](review.md)); then the suite if needed, the shell harnesses the
  dial names, and `bin/format -c` only when the diff has `.kt`/`.kts`. A red
  gate is `Ask('suite-red')`; after the answer the driver checkpoints and
  re-runs it.

**Write scope is a deny list**:
`-d '.claude/*' -d '.prime/agent/skills/ship/*'`. An unlisted wiring edit is
normal; whether the footprint fits is review's question. A deny hit resets to
`before-impl` and asks `scope-deny`.

## Files under `<rs>/driver/`

    journal.jsonl       one event per line: enter/exit, spawn, settle, ask,
                        answer, subprocess, error
    ask.json            the open judgement, or absent
    answers/<n>.json    every model answer, numbered
    children/<name>/    dispatch.md, progress.md, decision.md, result.json
    wakes.jsonl         every wake issued, with its reason
    session.json        driver_session, heartbeat_id, coord_log, agent_dir
    commit-subject.txt  the land commit's subject
    error.txt           the traceback when the task crashed

State keys added: `DIAL_DESIGN`, `DIAL_SCRUTINY`, `DIAL_NARROW`,
`DIAL_APPROVAL`, `DIAL_TESTS` (one word, `scope=…,gradle=…,harnesses=a+b`,
rewritten after every rebase), `DRIVER_STEP`. Session handles live in
`session.json`. Values are one shell word: the scripts `source` the state file.
A `PHASE` the journal's open phase disagrees with is a defect; `resume()`
refuses and says so.

## Gates and the suite rule

Every gate runs through `scripts/ship-run` (`-g` on the suite); the log is
`<rs>/runs/<name>.log` and the driver reads the record. `suite_needed` (RFC 211,
RFC 235) reuses the LAST record in `<rs>/suite-green.txt` only when the tree is
clean, `exit=0`, `cmd=` is exactly `nix develop -c bin/test`, and `HEAD^{tree}`
equals its `tree=` or `bin/gate-scope` says the move since needs no Gradle. The
reason is journaled. Plain `bin/test`, not `bin/check`: the hook is the
authoritative run.

## The land sequence

`before-land` checkpoint, the report's numbers written and the model's prose
asked for — `ship-land` archives `<rs>` to `.scratch/ship-archive/<run-id>/` and
deletes the worktree. Then:

    scripts/ship-lock   -s <rs> acquire     # re-waits while another run holds it
    scripts/ship-rebase -s <rs>
    nix develop -c bin/prompt-seed -b "$BASE_SHA"
    scripts/ship-order  -s <rs>
    scripts/ship-squash -s <rs>
    nix develop -c bin/format
    git add -u
    git commit -q -m "<subject>"            # ONE commit, RFC markdown included,
                                            # through the full hook (ship-run -n hook)
    scripts/ship-land   -s <rs>             # ff-merge; releases the lock

Why this order:

- The lock brackets rebase through land: the hook's result is valid only for the
  base it ran on (RFC 167).
- `prompt-seed` and `ship-order` run after the final rebase, so they read the
  base as landed, and before the squash, so what they write lands inside the
  hook-verified tree (RFC 180, RFC 181). `ship-land` re-checks both dry.
- `git add -u` re-stages what `bin/format` rewrote; without it the commit gets
  no `ship-verified` marker and `ship-land` refuses after the gate (RFC 211).
- A base that advanced only by `product/**` commits is replayed, not re-gated
  (RFC 193); `ship-land` records `LAND_FAST_PATH`.

**Every in-lock failure releases the lock first**, then asks: `rebase-conflict`,
or `hook-failed` with `cls` = `tests` | `format` | `shell-harness` |
`prompt-seed` | `ship-order` | `ship-land`. After the answer, land restarts at
`ship-lock acquire`, never at `git commit`. A lock left by a killed session
expires by itself, or `scripts/ship-lock -s <rs> release -f` breaks it.

After `ship-land` the driver records the landed SHA, appends the Spend section
to the archived report, asks `announce`, sets `PHASE complete`, deletes the
watchdog and exits.

## When the hook fails on a shell harness

The harness set is `nix develop -c bin/shell-tests -h`'s; it is not copied. The
driver has released the lock and saved the failing excerpt to
`<rs>/findings/hook-shell-failure.txt`. Then:

1. Classify. _Environmental_ (missing `installDist`, a stopped daemon, a
   Postgres hiccup): fix the environment, change no code. _This run's defect_:
   fix, checkpoint, re-run only that harness by the name the hook printed
   (`bin/shell-tests -n <name>`). _Pre-existing_: confirm it fails at `BASE_SHA`
   from the original checkout, then stop and tell Ian — repairing it is a new
   run.
2. Answer `hook-failed`; land restarts at the top.
3. Never `--no-verify` past it.

## Parallel runs

A moving base is normal. Being rebased without recording it is not, so
`ship-rebase` is the only way to catch up: it moves `BASE_SHA`, is a no-op when
nothing moved, and rotates `checkpoints.log` (`ship-recover` targets only
post-rebase checkpoints). `ship-squash` and `ship-verify-scope` refuse when the
fork point moved. `db/schema/ORDER` is `merge=union`; never resolve it by hand.

## Stalls, crashes, the watchdog

- Each child is spawned with one line naming its `children/<name>/dispatch.md`:
  a large prompt can start a child before it arrives. Finished = its marker (a
  lens report's `**Grade:**` line, `## Done` in `progress.md`) and not
  streaming; `settled` is not read. Stopped without it (not streaming, no
  `ship-run` of its own open): one wake message. Then a lens, or one past its
  30-minute ceiling, is re-dispatched once, then skipped `child stalled twice`;
  a writer is `Ask('child-failed')`.
- A crashed task writes `error.txt` and wakes `ship-wake <id> driver-error`.
- `start()` creates the watchdog heartbeat (`*/30 * * * *`, `follow_up`), whose
  cell is `await ship_driver.watchdog()`: no live task for an incomplete run →
  `resume()` of this session's run. Paused while parked on approval, deleted at
  `complete`.

## The report

`report.py` writes every number from files, including **Spend**: coordinator
turns (judgement / absorbed), child count and tokens by role (`unmeasured`,
never zero, when a session file has no usage), wall-clock per phase. The model
writes the opening sentence and the learning candidates.
