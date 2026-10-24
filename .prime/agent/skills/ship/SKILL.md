---
name: ship
description: >-
  Takes a short instruction from Ian and carries it to landed code in the
  unicoach repo. The model sets the design and review-scrutiny dials (approval
  and test depth are derived), names a preset (mechanical, bug fix, feature),
  and hands the run to the ship_driver package, which claims a worktree,
  dispatches children, runs the gates and lands by fast-forward, waking the
  model only for judgement calls. Use when Ian asks to ship, build, implement,
  fix, or change something in unicoach, or invokes /skill:ship. Independent of
  the Claude Code rfc-pipeline: it shares a git naming convention and the
  review-rule corpus, no code.
---

# ship

One instruction in, landed change out. The driver does the bookkeeping; this
session makes the judgement calls it asks for, and nothing else.

## Talking to Ian

Every message to Ian follows
[`../shared/operator-comms.md`](../shared/operator-comms.md): a triage label on
the first line, the outcome first, an internal name expanded the first time it
reaches him, every stop in a `YOUR DECISION REQUIRED` block.

## Non-negotiables

1. **Never modify `.claude/**`**, nor call anything under
   `.claude/skills/*/scripts/`. Never edit this skill from inside a run.
2. **Two authorities can say no**: Ian at approval, `bin/pre-commit` at commit.
   There is no CI. Never weaken either; never `--no-verify` past the hook.
3. **The approval stop is not an optimisation target.** Nothing downstream
   catches "we built the wrong thing". When it is required, make it cheap
   (decisions with defaults), never absent.
4. **Toolchain commands run `nix develop -c`**; iOS scripts run under system
   Xcode and refuse inside the dev shell.
5. **The run is on disk.** After compaction or restart read `drv.status()` and
   `scripts/ship-status`, never memory.

## Dials and presets

| Preset     | design | scrutiny | approval  | typical change                                   |
| ---------- | ------ | -------- | --------- | ------------------------------------------------ |
| mechanical | none   | none     | skipped   | docs, a rename, a new `bin/` script + harness    |
| bug fix    | none   | targeted | skipped\* | a failing test first, then the fix               |
| feature    | rfc    | full     | required  | new behaviour or surface, a schema change, taste |

You set **design** (`none`|`rfc`) and **scrutiny** (`none`|`targeted`|`full`).
**approval** and **tests** are derived by the driver (\*a bug fix that touches
an irreversible path needs approval); tests is always "from the diff". Approval
is re-derived from the real diff after implementation; a diff that trips a rule
the instruction did not predict stops the run before verify. Rules:
[`references/dials.md`](references/dials.md). Ian never names a preset; you
choose, he vetoes.

## The first message

Never open with a question. State all four dials **in words**, each with its
reason, and the preset:

    FYI — NO ACTION NEEDED — <outcome in one line>
    Preset: bug fix.
    Design document: none — the failing test gives the shape.
    Review: targeted — two Kotlin files; only blocking findings get fixed.
    Approval: skipped — no schema, prompt, money, auth, deletion or public copy.
    Tests: chosen from the diff after implementation, the way the hook does.
    Plan: <two or three lines>

`FYI — NO ACTION NEEDED` when approval is skipped; the run proceeds. When it is
required, the first message is the approval presentation, labelled
`YOUR DECISION REQUIRED` (for `design=rfc`, sent once the RFC is written).

## The driver: five calls

Import from `ORIGINAL`, the original checkout (never a worktree), so a run that
edits the driver cannot break itself:

```python
import sys; sys.path.insert(0, f"{ORIGINAL}/.prime/agent/skills/ship/driver")
import ship_driver
from ship_driver.dials import Dials
drv = await ship_driver.start(instruction, Dials(design="none", scrutiny="targeted"),
                              slug="fix-x", brief=plan)  # claim + start the task
drv = await ship_driver.resume()      # after restart or compaction
await drv.answer(kind, payload)       # your judgement, by kind
drv.status()                          # phase, step, children, waiting-on
await ship_driver.watchdog()          # the heartbeat's one cell
```

`start` refuses a second live run in this session. `brief` is your plan; with no
RFC the implementer reads the instruction, then it. After any call, end the
turn: the driver runs in the background and wakes you.

## The judgement points

A wake is a `[bash-done]` notice whose command begins
`ship-wake <run-id> <kind>`. Read `<rs>/driver/ask.json` (its `context` names
the files), do the work, then `await drv.answer(kind, payload)`. The payload is
`{}` unless shown; `ask.json` lists the shapes.

- **`design`** — write `rfc/<n>-<slug>.md` in `CODEBASE_ROOT` with
  `## Detailed Design`, `## Files Modified`, `## Implementation Plan`,
  `## Tests` (Tier 0 reads those four). Delegate bounded research; keep the
  deciding inline. `{"path": ...}`.
- **`approval`** — present the dials, the design in brief and the taste
  decisions with defaults, one `YOUR DECISION REQUIRED` block each; a new table
  as its DDL with the cost of being wrong. Also raised when the diff tripped an
  irreversible path: say what. On Ian's yes,
  `{"approved": True, "decisions": [...]}`; on changes, revise and re-present.
  Never ask twice.
- **`decision`** — the implementer wrote an irreversible question (schema,
  stored data shape, prompt, public copy) to its `decision.md`. One block to
  Ian; `{"answer": ...}` is sent to the implementer. Approval is required from
  then on.
- **`approved-decision`** — a Tier 0 fix would amend an RFC line Ian approved.
  Block quoting the approved text and what the code does; default: amend the RFC
  to match the code. Apply his answer, then answer.
- **`blocking-contradicted`** — a BLOCKING finding cannot be applied without
  breaking another rule. Block quoting both rules and the code; default: leave
  the code, record the contradiction. Apply his answer, then answer.
- **`malformed-twice`** — a lens report has no grade after one re-dispatch.
  Never grade it yourself: `{"skip": "<real reason>"}` or
  `{"redispatch": "<why>"}`.
- **`child-failed`** — an implementer or fix child exited twice without
  `## Done`. Read its `progress.md` and last record; finish the work,
  `{"finished": "<what>"}` (re-checked), or `{"respawn": "<why>"}`.
- **`scope-deny`** — the implementer wrote a denied path; the driver has reset
  with `ship-recover`. Read `findings/write-scope.txt`; `{"brief": ...}`
  dispatches a new implementer, `{}` means the tree is now right.
- **`rebase-conflict`** — two runs disagree about the same code: a design
  signal. Decide which shape is right, resolve, `git rebase --continue`, answer;
  or `--abort` and ask Ian. Report it.
- **`suite-red`** — a verify command (suite, shell harnesses, `bin/format -c`,
  `bin/prompt-seed`) is red. Fix it from the record's failing lines, then
  answer; the driver checkpoints and re-runs it.
- **`hook-failed`** — a land step failed; the lock is released. `cls` says
  which: `tests`, `format`, `prompt-seed`, `ship-order`, `ship-land`, or
  `shell-harness` (follow [`references/driver.md`](references/driver.md)).
  Failing tests go to Ian, lines verbatim plus the log path. Fix, checkpoint,
  answer; land restarts at the top.
- **`report-prose`** — `{"opening": ..., "learning_candidates": ...}` (Markdown;
  optional `"subject"`, the commit subject). The opening is one sentence a
  non-engineer could repeat. The driver writes every number.
- **`announce`** — send Ian the `DONE` message: outcome, landed SHA, open items
  with who must act, `.scratch/ship-archive/<run-id>/`. Then answer.

`ship-wake <id> driver-error` is not a judgement: the task crashed. Read
`<rs>/driver/error.txt`, fix the cause, `drv = await ship_driver.resume()`.

**Learning candidates** are calls review made from taste because no principle
covered the case — not lens findings. One line each: the call, and what a rule
would need to say. `None` is a real result. Codifying is
[`/principle`](../principle/SKILL.md). For an `ios-app/**` change, look at
`<rs>/artifacts/` with `attach_image` first.

## Notices, compaction, resume

For the whole run, a `[child-exited]` or `[bash-done]` notice that does not
begin `ship-wake` gets **no tool call and no text**; the driver saw it too. A
watchdog beat runs its one cell. The driver schedules compaction itself after
approval, before verify and before land; after one, read `<rs>/driver/ask.json`
on the next wake.

**Resume** is `drv = await ship_driver.resume()`: it finds this session's run,
re-reads state and journal, re-enters the open phase. If it reports `PHASE`
disagreeing with the journal, tell Ian; do not guess. `scripts/ship-status`
lists live runs and their drift.

## The coach system prompt

A coach-prompt change is an edit to `prompts/coach-system-prompt.md` and nothing
else, one sentence per line (`prompts/` stays out of `deno fmt`). Never
hand-write the seed migration, its `db/schema/ORDER` line or the
`systemPromptVersion` pin: `bin/prompt-seed -b BASE_SHA` writes them, run by the
implementer before its suite and by the driver after the verify rebase, after
review and at land. The comment log above the pin is still the author's. A red
`SystemPromptCatalogTest` before the generator has run is the generator owed,
never a hand fix.

## References

- [`references/driver.md`](references/driver.md) — phases, `<rs>/driver/`, land,
  the suite rule, failure procedures.
- [`references/dials.md`](references/dials.md) — dial rules, lens applicability,
  retired lenses.
- [`references/review.md`](references/review.md) — what lens and fix children
  read.
- [`references/boundaries.md`](references/boundaries.md) — what Claude Code
  shares. Every script under `scripts/` has `-h`.
