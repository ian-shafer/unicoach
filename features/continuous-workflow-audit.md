# Feature Brief — Continuous Workflow Audit

> **Status:** feature idea. Not an RFC, no implementation authority. The files
> under `.prime/agent/skills/ship/` and `.claude/skills/` are the source of
> truth for how the workflows actually behave.

## What this is

Every `ship` run already leaves a complete, machine-readable record of its own
execution under `.scratch/ship-archive/<run-id>/` — the run state, the
checkpoint ledger, the per-lens findings, the lens plan with its skip reasons,
and (since the phase-boundary change) the report and the write-scope result.

Today that record is read by a human, once, if someone thinks to look. This
feature makes it a **standing audit**: the workflow is continuously measured
against its own archives, so its defects surface from evidence rather than from
someone happening to notice.

The motivating case is real. The first `ship` run (RFC 114) landed a correct
change, and reading its archive afterwards found four workflow defects in one
pass — a stale `PHASE`, checkpoints confined to one phase, a report that died
with the session, and no evidence the write-scope check had run. None were
visible from the run's outcome, which was green. **A workflow can produce good
output and still be broken in ways only its own trace shows.**

## Why it is not just "read the archive"

The audit's value is in the comparison across runs, not in any single archive:

- **A defect that recurs is a workflow defect; a defect that happens once is a
  run.** Only the series distinguishes them.
- **Drift is invisible per-run.** A phase quietly stops being checkpointed, a
  lens quietly stops running, skip reasons get broader. Each run looks fine.
- **The workflow author is the worst auditor of it.** Writing the skill and then
  running it means executing from memory and papering over gaps without noticing
  — which is exactly why the RFC 114 defects needed a session that had only
  `SKILL.md`.

## What it would check

Mechanical, evidence-based questions answerable from the archives alone:

| Question                                                        | Evidence                                               |
| --------------------------------------------------------------- | ------------------------------------------------------ |
| Did the run leave a truthful `PHASE`?                           | `state` — a landed run reading `implementing` is a lie |
| Were checkpoints taken at every phase boundary?                 | `checkpoints.log` vs the phase list                    |
| Did every discovered lens get run or explicitly skipped?        | `lens-plan.json` — ran + skipped must equal the corpus |
| Is the skipped set growing over time?                           | skip counts across runs                                |
| Did a lens ever fire on a file type it is always skipped for?   | findings vs skip reasons                               |
| Did the write-scope check actually run?                         | `findings/write-scope.txt` present                     |
| Was a report written before teardown?                           | `report.md` present                                    |
| Did findings get applied, or silently dropped?                  | findings vs the landed diff                            |
| How long did each phase take, and where does the wall-clock go? | checkpoint timestamps                                  |
| Did the tip that landed pass the hook?                          | the verified-tree marker vs the landed tree            |

## Shape

Deliberately unspecified, but the constraints are clear:

- **Reads archives only.** It must never need a live run, and must never be able
  to affect one.
- **Covers both workflows where it can.** `rfc-pipeline` writes its own archive
  to `.scratch/review-fix-archive/rfc-<n>/`; the schemas differ, so shared
  checks need a small adapter rather than one format imposed on both. This
  feature must not require changing `.claude/`.
- **Cheap enough to run unattended.** A periodic job, or a step at the end of
  every run that audits the _previous_ runs, not the current one.
- **Its output is a finding list, not a dashboard.** The useful artifact is
  "these three things regressed since last week", not a chart.

## Open questions

- Does the audit run as a `ship` lane, a `cron` periodic job (RFC 97's
  infrastructure), or a plain script invoked on demand?
- Where does its own output live so that it is not itself unaudited?
- Should a failed audit block anything, or only report? (Probably only report:
  the two authorities that can say no are the Architect and the pre-commit hook,
  and adding a third that runs on stale evidence is how a gate becomes noise.)
- How many runs before drift is measurable? The archive count is currently 1.
