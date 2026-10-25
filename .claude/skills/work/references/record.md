# The run record

Every run writes `<rs>/record.md` as it goes; `ship-land` archives it to
`.scratch/ship-archive/<run-id>/record.md`. It exists so /work is judged on
numbers rather than impressions while it is being proven, and so a compacted or
restarted session can tell where the run stood. Write each section when its
phase ends, never reconstructed at the end.

Times come from `date +%s` taken at each phase boundary and appended to
`<rs>/timing.tsv` as `<phase>\t<start>\t<end>`; the record shows minutes.

```markdown
# /work <run-id>

- **Instruction:** <verbatim>
- **Outcome:** <one sentence a non-engineer could repeat>
- **Landed:** <sha> | not landed: <why>

## Dials

| Dial     | Value | Why |
| -------- | ----- | --- |
| spec     |       |     |
| scrutiny |       |     |
| approval |       |     |
| tests    |       |     |

Changed after implementation: <dial, from → to, why> | none

## Time

| Phase     | Minutes | Note                        |
| --------- | ------- | --------------------------- |
| spec      |         |                             |
| approval  |         | time waiting on Ian         |
| implement |         |                             |
| review    |         |                             |
| verify    |         |                             |
| land      |         | of which the hook commit: N |
| total     |         |                             |

## Review

| Tier | Dispatched | Skipped | BLOCKING | SHOULD-FIX | ADVISORY | Fixed | Declined | Held |
| ---- | ---------- | ------- | -------- | ---------- | -------- | ----- | -------- | ---- |

Per lens, one line each that produced a finding:
`<lens>: <grade> — <title> — <outcome>`.

## Stops for Ian

<each stop: why, the default offered, what he chose> | none

## Done by hand

<anything the coordinator did that this skill should have covered, or did
differently from what it says> | none

## Learning candidates

<the call, and what a rule would need to say> | None

## Open items

<what, who must act> | none
```

**Done by hand** is the section that improves the workflow: every entry is a
candidate edit to this skill, made between runs.
