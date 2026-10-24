# Review: what lens and fix children read

The driver (`ship_driver/review.py`) runs each tier: it takes the dispatch set,
skips what cannot fire, assembles every dispatch from files on disk, parses the
grades, builds the fix plan, validates the outcome record and closes the tier.
This file is the text those children are bound by, and the one place the driver
reads its verbatim instructions from.

## Where the rules live (read-only)

    .claude/skills/impl-review-*/SKILL.md      Tier 0 — RFC conformance
    .claude/skills/code-review-*/SKILL.md      code lenses
    .claude/skills/design-review-*/SKILL.md    design lenses
    .claude/skills/review-fix/tiers.md         the ordering manifest
    .claude/skills/findings-output.template.md the output contract

`ship` reads these and never writes them; their one writer is
[`/principle`](../../principle/SKILL.md). The `*-chain` and `review-fix` skills
beside them are Claude Code orchestration and are not used.

**One rule, one reviewer.** The rules look overlapping and are not: their narrow
scoping is what makes a single-lens reviewer accurate, and a generalist applying
all of them at once under-finds (`code-review-chain`). Filter lenses that cannot
fire; never merge them.

## The dispatch set and the close

`scripts/ship-lens` owns discovery and the accounting invariant (RFC 234): run
before any ledger line, `ship-lens -s <rs> missing -t <N>` lists every lens the
tier assigns as `UNDISPATCHED`, and that list IS the dispatch set. The driver
removes what the applicability map rules out (see [`dials.md`](dials.md)) with
one shared `skip` reason per tier, appends `start` at dispatch and `done` on a
report, and the tier closes only when `missing` exits 0. `ship-lens -h` is the
contract for the ledger and the fault kinds.

Four names in `<rs>/findings/` are the run's own writing, never a lens report:
`tier<N>-fix-plan.md`, `tier<N>-fix-report.md`, `tier<N>-outcomes.jsonl`,
`tier<N>-missing.txt`. Any other `tier<N>-*.md` that no `start` names is
`UNDECLARED` and holds the tier open — a child never invents a report name.

## Tier order

Every rule's tier is the blast radius of the fix its findings induce
(`tiers.md`), so a structural finding is decided before the nits its fix would
delete.

| Tier | Shape                   | Note                                                         |
| ---- | ----------------------- | ------------------------------------------------------------ |
| 0    | RFC conformance         | The only tier whose triage may conclude **the RFC is wrong** |
| 1    | multi-file / structural |                                                              |
| 2    | single-file             |                                                              |
| 3    | 1–4 lines               | Renames, wording, comments                                   |

Tiers run sequentially; each sees the tree the previous tier's fixes left.
Lenses within a tier run in parallel. Tier 0 runs only when the run has an RFC;
when the code is right and the RFC is wrong, say so — per `rfc/README.md` the
code wins.

## Dispatch output contract

`findings-output.template.md` says a caller's own output instructions win. Every
lens dispatch adds one instruction on top of it (RFC 233): a `**Grade:**` line
under `**Verdict:**` and a tag on every finding heading. The report's grade is
the highest tag it carries.

| Grade        | Means                                                                                      | The run then                            |
| ------------ | ------------------------------------------------------------------------------------------ | --------------------------------------- |
| `CLEAN`      | no findings (`🟢 PASS`), or the lens does not apply (`⚪ N/A`)                             | nothing                                 |
| `ADVISORY`   | a preference — a name, a phrasing, a comment, a "consider". Leaving it is not a defect.    | reports, unapplied                      |
| `SHOULD-FIX` | a corpus rule is broken, or a defect whose fix is contained. Fix before land.              | fix batch at `full`, held at `targeted` |
| `BLOCKING`   | correctness, safety or data loss; or, Tier 0 only, the code is right and the RFC is wrong. | fix batch                               |

### Out of lens

A lens that sees a real defect outside its own rule reports it under a final
`## Out of lens` heading, tagged like any finding (RFC 234). It goes to the
tier's fix batch by its grade — **only within this run's own diff**. A defect in
code the run did not touch is an open item in the report, with its `file:line`:
fixing it would land something Ian did not approve. The lens never fixes
anything itself; the tree is frozen while a tier's lenses read it.

### The instruction, verbatim

The text every lens dispatch appends, and a re-dispatch quotes:

```text
Output per `.claude/skills/findings-output.template.md`, PLUS: directly under
`**Verdict:**` emit a line

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and end EVERY finding heading with its own tag in brackets, e.g.

    ### The plan step names a file that does not exist [SHOULD-FIX]

ADVISORY   = a preference; leaving it is not a defect (naming, wording, a "consider").
SHOULD-FIX = a corpus rule is broken, or a contained defect. Fix before land.
BLOCKING   = correctness, safety or data loss; or (Tier 0 only) the code is right
             and the RFC is wrong.

The report's Grade is the highest tag it carries.

AND: a real defect you see that your own rule does not cover goes under a final
heading

    ## Out of lens

one tagged `###` finding each, with its `file:line`, what it is, and its own
grade tag. Report it there; do not fix it, and do not judge whose rule it is.
```

**A `🔴 FAIL` with no tags is malformed.** The driver re-dispatches that lens
once with the instruction quoted; a second malformed report wakes the
coordinator (`malformed-twice`). Nobody infers the grade.

### The dispatch carries everything

A lens child is handed the rule and the diff in its `dispatch.md`, which a
one-line spawn prompt names (a large prompt can start a child before it lands),
not paths to them (RFC 235). Six parts, in order: the lens's rule text, the
output template, the instruction above, the diff, the report path to write, the
one-line reply wanted. Tier 0 also carries the RFC. The driver assembles it from
files; nothing is generated token by token. The child is told when it may still
open a file:

```text
Everything you need is in this dispatch. Open further files ONLY when your rule
genuinely requires context beyond the diff — `code-review-single-source-of-truth`
checking whether a fact already has a home elsewhere in the repo is the
canonical case — and say in your reply HOW MANY other files you opened.
```

`tiers.md` owns the lens named there; check it exists before changing that
clause. The reply is one tab-separated line: lens, grade, findings, files
opened. Target five model calls or fewer and report the number; nothing enforces
it, because a cap would truncate a report into a lens that silently found
nothing. A dispatch too large for the child's window is split by file, with the
reason recorded, or skipped through `ship-lens skip` with the real reason.

## What gets a fix child

`BLOCKING` always; `SHOULD-FIX` at scrutiny `full`; `ADVISORY` never — held
findings reach Ian in the report, one line each. A tier with nothing to fix
spawns no fix child. One fix child per tier: two writers in one worktree race on
`ship-checkpoint`. The driver writes `<rs>/findings/tier<N>-fix-plan.md` with
the sets RFC 234 names and hands the child its findings inline.

**A fix child runs its build, test and format commands through
[`scripts/ship-run`](../scripts/ship-run), never bare, passing its own name as
`-c`** (RFC 235, RFC 236; `-c` is required). It applies its whole batch, then
runs the suite ONCE, after `scripts/ship-checkpoint`, with `-g` so the record
verify reuses is written by the process that ran it:

    scripts/ship-run -s <rs> -n suite -c <your-name> -g -- nix develop -c bin/test

Per-item compiles are fine through the same script, reading back only their
error lines. Read the record and the failing excerpt, not the log; open the log
only when the excerpt is not enough. A long command is started in the background
and the turn ends; its completion wakes the child. A fix child spawns no
children. It follows the checkpoint protocol in its dispatch: `progress.md`
under `<rs>/driver/children/<name>/`, ending `## Done`.

### Tier 0 may not silently rewrite an approved decision

Amending the RFC to match the code is normal Tier 0 work. When the line to amend
is one Ian approved (`<rs>/approval.md`), the fix child does not amend it: it
records `DEFERRED` with `amends_approved` true and a reason quoting the approved
text and what the code does, and the driver wakes the coordinator.

## When two rules disagree

A **contradiction** is a finding that cannot be applied without breaking a
different rule the run is also bound by (RFC 234). Four counterparties:

| `contradicts_kind` | `contradicts_ref`             | what it is                                                                         |
| ------------------ | ----------------------------- | ---------------------------------------------------------------------------------- |
| `lens`             | the lens directory name       | another rule in the corpus                                                         |
| `house`            | the doc path                  | a convention in `CLAUDE.md` or another doc                                         |
| `rfc`              | the RFC number                | a decision in the approved RFC for this run                                        |
| `tier`             | the lens whose fix it applied | a fix an earlier tier already applied, with `contradicts_tier` carrying which tier |

In the prose line below the two are written together as `<kind>:<ref>`, and the
cross-tier one as `tier<N>:<lens>`.

**Synthesis first.** If one change satisfies both rules, make it: that is an
`ACCEPTED` outcome with the synthesis named in its reason.

**Otherwise halt THAT ITEM, not the batch.** Work every other finding. Write, in
the fix report:

```text
CONTRADICTION: <lens>#<id> (<file>:<line>) vs <counterparty>
```

then both rules **quoted verbatim, each with the path it was read from**, and
one line saying why no change satisfies both. The quoting is the guard: a
decline must be confirmed, not asserted. The fix child does not pick a side. A
later tier that would undo an earlier tier's applied fix records the `tier` kind
rather than reverting it. A contradicted `SHOULD-FIX` is recorded and left; a
contradicted `BLOCKING` wakes the coordinator, who takes it to Ian.

## The outcome record

The fix child appends one JSON object per line to
`<rs>/findings/tier<N>-outcomes.jsonl`, **one record per lens finding — never
per merged triage item**: one edit answering three lenses writes three records.

```text
{"lens":"code-review-error-bubbling","finding_id":1,"grade":"SHOULD-FIX",
 "file":"public-web/src/main/kotlin/Routing.kt","line":396,"outcome":"CONTRADICTED",
 "contradicts_kind":"lens","contradicts_ref":"code-review-no-leaks",
 "out_of_lens":false,
 "reason":"carry the cause vs do not leak it; no change satisfies both"}
```

One record is one line; the example is wrapped.

| field              | required                                      |
| ------------------ | --------------------------------------------- |
| `lens`             | always — the canonical directory name         |
| `finding_id`       | always                                        |
| `grade`            | always — echoed from the finding's tag        |
| `file`, `line`     | always                                        |
| `outcome`          | always, closed enum (below)                   |
| `decline_class`    | only when `DECLINED`                          |
| `contradicts_kind` | only when `CONTRADICTED`, closed enum (above) |
| `contradicts_ref`  | only when `CONTRADICTED`                      |
| `contradicts_tier` | only when `contradicts_kind` is `tier`        |
| `amends_approved`  | only when `DEFERRED`, `true` or `false`       |
| `out_of_lens`      | always, `true` or `false`                     |
| `reason`           | always, one line                              |

**Only** means only: a field that does not apply is **absent** — never `null`,
`""` or `"none"` — and a field that applies is never omitted. Build each record
with `json.dumps`, never as a typed literal: a reason quoting a rule breaks a
hand-written line. Read the file back before reporting. The driver parses every
line and checks each conditional field is on its own arm, and that every
dispatched finding has a record; a fault is sent back to the child once.

`finding_id` is **the finding's position in its own report**: the 1-based index
of tagged `###` headings in file order, `## Out of lens` ones included.
`finding_id` and `line` are numbers; every other value is a string.

`outcome` is closed:

| value          | means                                                    |
| -------------- | -------------------------------------------------------- |
| `ACCEPTED`     | applied                                                  |
| `PARTIAL`      | applied in part                                          |
| `DECLINED`     | not applied, and not agreed                              |
| `DEFERRED`     | agreed, not here — it becomes an open item in the report |
| `CONTRADICTED` | halted with both rules named, per the section above      |

`decline_class` is closed too, and the corpus owns it: take it from the
rejection-class table in `.claude/skills/review-fix/SKILL.md`, spelled as there.
`CONTRADICTED` is not the corpus's `discarded-conflict`: that records a side
that was chosen; this records that none was.

**The fix report** is `<rs>/findings/tier<N>-fix-report.md`, one per tier — the
name the ledger exempts. It says what the child did, and carries any
`CONTRADICTION:` lines; the per-finding verdicts live in the record.

## When the coordinator is woken

Triage is code (RFC 256 D5). The coordinator is woken, with the tier's reports
named in `<rs>/driver/ask.json`, for five review exceptions only:

- `approved-decision` — a Tier 0 outcome is `DEFERRED` with `amends_approved`
- `blocking-contradicted` — a `BLOCKING` finding's outcome is `CONTRADICTED`
- `malformed-twice` — a lens report has no grade after the re-dispatch
- `child-failed` — a fix child exited twice without `## Done`, or twice left an
  outcome file that does not validate
- `scope-deny` — `ship-verify-scope` hit a deny glob; the driver has already run
  `ship-recover`

Everything else — grade counts, held advisories, out-of-lens hand-offs, decline
classes — reaches Ian in the report.
