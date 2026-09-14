# RFC 206 — The suppression sentinel: the Scorecard writes `PS`

Status: proposed Slice: `beyond/02/the-suppression-sentinel` (brief
`product/0011-beyond-money`, decision D25)

## Summary

`CsvIngestSupport` recognises the College Scorecard's privacy-suppression
sentinel as the literal `"PrivacySuppressed"`. The Scorecard writes **`PS`**.
Every suppressed cell therefore falls through to `not_reported_by_institution`,
and a family is told **"This school did not report this figure"** about a figure
the publisher withheld.

Measured for this RFC against the committed institution file itself, rather than
taken from the brief (`db/seed/scorecard/Most-Recent-Cohorts-Institution.csv`,
6,273 rows): **1,228 `GRAD_DEBT_MDN` cells are `PS`**, and `"PrivacySuppressed"`
appears **zero** times in the whole file. Across the sixteen institution columns
the status-preserving readers consume, `PS` is the only non-numeric token and
`GRAD_DEBT_MDN` is the only column carrying it.

This RFC teaches the one ingest seam both spellings, counts what it recognised,
and changes nothing else. There is no migration, no new table and no copy
change: the two family-visible sentences already exist and are already correct.

## Motivation

`figure_statuses` exists (RFC 158) precisely so that two silences are not one
silence. RFC 177 D3 then split the copy on who is responsible:

- `suppressed_by_publisher` — "The College Scorecard withholds this figure to
  protect students' privacy."
- `not_reported_by_institution` — "This school did not report this figure."

The second sentence blames the school. Today we say it about 1,228 schools that
did report, and whose figure the publisher suppressed for privacy. That is the
error class brief 0008 spent four slices removing from the money figures, still
live in the ingest.

It also gets worse on a schedule. `beyond/03/what-this-major-returns` loads the
field-of-study file through this same seam. Measured on
`db/seed/scorecard/Most-Recent-Cohorts-Field-of-Study.csv` (227,980 rows, 178
columns), with the scope of each count stated because an unscoped count is the
kind of figure this brief exists to stop us printing:

| Scope                                                                            | `PS` cells |
| -------------------------------------------------------------------------------- | ---------- |
| The two columns `beyond/03` loads, at bachelor's only (`CREDLEV=3`, 71,664 rows) | **84,917** |
| — of which `EARN_MDN_4YR`                                                        | 44,047     |
| — of which `DEBT_ALL_STGP_EVAL_MDN`                                              | 40,870     |
| The same two columns, every credential level                                     | 327,297    |
| Every column, every row                                                          | 28,881,284 |

The spec's 44,047 and 40,870 are confirmed exactly, and 84,917 is their sum.
`beyond/03` BLOCKS on this slice for exactly that reason (D25).

Why it was never caught: the three CSV fixtures under
`college/src/test/resources/` spell the sentinel `PrivacySuppressed` — a string
the source never emits. The tests passed, on data the publisher does not
publish. That is fixed here too, and it is the durable half of the fix.

## Detailed Design

### 1. One sentinel set, at the one seam

`CsvIngestSupport.PRIVACY_SUPPRESSED` (a single `String`) becomes
`SUPPRESSION_SENTINELS` (a `Set<String>`), holding both spellings:

- **`PS`** — what the Scorecard writes today, in both the institution file and
  the field-of-study file.
- **`PrivacySuppressed`** — the spelling the publisher used in earlier cohort
  files. It is kept, not replaced, because dropping it would silently reclassify
  an archived file's suppressions as school silence: this defect, in reverse.

`statusfulCell` is the only reader of the constant and stays one line:

```kotlin
val raw = stringOrNull(record, column) ?: return StatusfulCell.NotReported
if (raw in SUPPRESSION_SENTINELS) return StatusfulCell.SuppressedByPublisher
```

Membership only. No trimming beyond `stringOrNull`'s existing trim, no
case-folding, no prefix match: a sentinel set that guesses is how a legitimate
value becomes a suppression. The set is exact, and a test asserts that `NA`,
`NULL`, `ps`, `Ps`, `""` and an arbitrary token all read as `NotReported` — the
three silences stay three.

The seam is safe by construction: `statusfulCell` is reached only for cells the
caller parses as `Int` or `Double`, so no column where `PS` could be a real
value passes through it. `statusfulCell` is used by `CanonicalMoneyLoader`
alone, so this is one edit for every consumer, present and future — not a patch
per column (D25).

The non-status-preserving readers (`intOrNull`, `doubleOrNull`) are deliberately
untouched, as RFC 139 and the existing KDoc say. No column they read carries
`PS`: `GRAD_DEBT_MDN` and `MD_EARN_WNE_P10` stopped being read into `colleges`
at RFC 176.

### 2. Counting what the seam recognised

The acceptance asks the ingest summary to state how many rows changed status.
The canonical fill is a wholesale rebuild (delete-then-insert, P12), so there is
no before/after row inside one run. Two things carry the change instead:

**(a) The durable record already moves, with no new key.** The provenance JSON
and the fill log already carry per-status row counts per canonical table
(`canonicalMoneySummaryJson`, RFC 158 P11). `cohort_money_stats` today reports
no `suppressed_by_publisher` rows from the Scorecard's debt measure; after a
re-ingest it reports 1,228. The reclassification is recorded by the counter that
already exists, and risk 6.6's frozen-key seam does not widen.

**(b) A read-side count, by column, new.** What the status totals cannot say is
_which column_ the suppressions came from, and a column that stops carrying them
is exactly the regression that produced this RFC. `CsvIngestSupport` gains:

```kotlin
/** Per-column counts of WHY a status-preserving read produced no value. */
internal class CellAbsenceTally {
  fun recordCoercedToNull(column: String)
  fun recordSuppressedByPublisher(column: String)
  val coercedToNull: Map<String, Int>
  val suppressedByPublisher: Map<String, Int>
}
```

It **replaces** the bare `coercions: MutableMap<String, Int>` parameter already
threaded through `CanonicalMoneyLoader`'s mapping functions — same parameter
count, one object that models one thing (brief 0011 D37: model each thing as
what it is; two parallel maps of "why this cell had no value" are one fact).
`FillResult.fieldsCoercedToNull` keeps its exact type and content and is derived
from `tally.coercedToNull`; `FillResult` gains
`cellsSuppressedByPublisher: Map<String, Int>`, printed in the fill log line and
in `IngestApplication`'s operator summary, both on stderr per `bin/`
conventions. Nothing is written to stdout.

The suppression is tallied where it is **recognised** — inside `statusfulCell`,
which is why the two bare readers `statusfulIntCell`/`statusfulDoubleCell` take
the tally as well — and it is keyed by the **source column**. Tallying it one
level down, in the domain reader, would count only the columns that happen to
carry a domain: the net-price cells are read with no domain at all (a negative
net price is valid), so every suppression they ever carry — the archived
institution files spell one, and the committed fixture keeps it — would be
missing from a figure that reads as a complete per-column count. That is the
error class this RFC exists to remove, not one to add.

Both arms are keyed by the **source column**, and the coercion arm's second
`columnName` parameter goes away with the dual keying: every live call site
passed the same string twice, so the signature carried two adjacent `String`
parameters with opposite meanings that a transposed call would swap silently.

A token in NEITHER set is now said out loud. `NULL_SENTINELS` (`NULL`, `NA`)
names the publisher's spellings of school silence beside the suppression set,
and a present cell that is in neither set and does not parse is logged by
column, line and token before it reads as not-reported. The status is unchanged
— this only refuses to be silent. It is the cheap half of the defect this RFC
repairs: `PS` took the unnamed path for years, and the next spelling the
publisher invents will take it too, but it will say so on the first re-ingest
instead of reaching families as school silence. Counting those tokens for the
operator summary is deliberately NOT done here; that is a third figure and a
product decision, and it is recorded as an open item.

The figure counts **reads**, and both log lines say so. A mapping guarded above
this seam — a control-keyed cell on a row with no `CONTROL`, an in-state tuition
cell with no residency evidence — never reaches the reader, so it is in no
column's count. Today that excludes nothing (`GRAD_DEBT_MDN` is the only
institution column carrying `PS`), but a count printed as complete when it is
not is the same defect one level up.

### 3. The fixtures say what the publisher says

The three institution fixtures under `college/src/test/resources/` currently
spell every suppressed cell `PrivacySuppressed`. Each fixture's `GRAD_DEBT_MDN`
suppression becomes **`PS`**; the `NPT43_PUB` suppression stays
**`PrivacySuppressed`**, so one fixture exercises each spelling and the legacy
arm is covered by the same integration test that covers the live one.
`college/build/resources/**` is build output and is not edited.

### 4. Reaching a database

This is a loader change. The stored statuses move only when the ingest is re-run
(`bin/ingest-colleges`) against the same source file. Until then the database
holds the old, wrong classification and families keep reading the wrong
sentence. The re-ingest is an operator action, named in the report as an open
item.

### 5. Rollback

Revert the code commit. No migration, no seed, no prompt version, no data
written by this change. A revert followed by a re-ingest restores the previous
(wrong) classification exactly.

## Files Modified

| File                                                                           | Change                                                                                                                                                                                                                                                                       |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `college/src/main/kotlin/ed/unicoach/college/CsvIngestSupport.kt`              | `PRIVACY_SUPPRESSED` -> `SUPPRESSION_SENTINELS`; membership test in `statusfulCell`; `CellAbsenceTally`; the statusful helpers take the tally; the per-cell coercion log line split into a private `logCoercedCell`, so the tally-keeping and the logging are said once each |
| `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`          | thread the tally in place of `coercions`; `FillResult.cellsSuppressedByPublisher`; fill log line                                                                                                                                                                             |
| `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt`             | the operator summary names the per-column suppression count, extracted out of `main` into `logCanonicalMoneyRun` beside the `logCds*` helpers                                                                                                                                |
| `db/src/main/kotlin/ed/unicoach/db/models/FigureStatus.kt`                     | the `SUPPRESSED_BY_PUBLISHER` KDoc named `PrivacySuppressed` as THE publisher token — a second, unreachable home for the fact this RFC moves                                                                                                                                 |
| `college/src/test/resources/scorecard-institutions-fixture.csv`                | `GRAD_DEBT_MDN` suppression -> `PS`                                                                                                                                                                                                                                          |
| `college/src/test/resources/scorecard-institutions-changed-fixture.csv`        | same                                                                                                                                                                                                                                                                         |
| `college/src/test/resources/scorecard-institutions-missing-column-fixture.csv` | same                                                                                                                                                                                                                                                                         |
| `college/src/test/kotlin/ed/unicoach/college/CsvIngestStatusfulCellTest.kt`    | both spellings suppress; the near-misses do not; tally assertions                                                                                                                                                                                                            |
| `college/src/test/kotlin/ed/unicoach/college/CanonicalMoneyLoaderTest.kt`      | the `PS` cell lands `suppressed_by_publisher`; the count reaches `FillResult`                                                                                                                                                                                                |
| `college/src/test/kotlin/ed/unicoach/college/CanonicalMoneyIngestTest.kt`      | comment + assertion follow the fixture spelling                                                                                                                                                                                                                              |
| `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardLoaderTest.kt`    | RFC 195's pair-reader tests used the retired `PRIVACY_SUPPRESSED` constant as a literal; they take the literal string instead                                                                                                                                                |
| `rfc/206-the-suppression-sentinel.md`                                          | this document                                                                                                                                                                                                                                                                |

No `db/schema/**` change, so no `db/schema/ORDER` line. No `prompts/**` change,
so no prompt version.

## Implementation Plan

1. `CsvIngestSupport`: introduce `SUPPRESSION_SENTINELS` and switch
   `statusfulCell` to membership. Run the unit test — it fails before, passes
   after.
2. `CellAbsenceTally`: add it; change the statusful `*InDomain` helpers to take
   it instead of the raw map, and the bare `statusfulIntCell`/
   `statusfulDoubleCell` to take it too, so the suppression is counted at the
   seam that recognises it rather than only on the domain-checked columns.
3. `CanonicalMoneyLoader`: thread the tally, derive `fieldsCoercedToNull` from
   it unchanged, add `cellsSuppressedByPublisher` to `FillResult` and to
   `logFillTally`.
4. `IngestApplication`: add the count to the operator summary log line.
5. Fixtures: one cell per file, `PrivacySuppressed` -> `PS`, on the
   `GRAD_DEBT_MDN` column only.
6. Tests below.

## Tests

All in `college/src/test/kotlin/ed/unicoach/college/`.

1. **`CsvIngestStatusfulCellTest`** — `PS` reads as
   `StatusfulCell.SuppressedByPublisher` for both the int and double readers.
   **Fails on today's code**: today it reads `NotReported`.
2. **`CsvIngestStatusfulCellTest`** — `PrivacySuppressed` still reads as
   `SuppressedByPublisher` (the legacy arm; passes today, must keep passing).
3. **`CsvIngestStatusfulCellTest`** — the three silences stay three: `NA`,
   `NULL`, a blank cell, `ps`, `Ps` and an arbitrary token all read as
   `NotReported`, never as a suppression.
4. **`CsvIngestStatusfulCellTest`** — a suppressed read tallies under its source
   column in `CellAbsenceTally.suppressedByPublisher` and does **not** appear in
   `coercedToNull`; an out-of-domain value tallies the other way. A suppressed
   cell is never coerced, and a suppression on a column with **no domain** is
   counted just the same — the case the fill's net-price cells take.
5. **`CanonicalMoneyLoaderTest`** — the fixture's `PS`-spelled `GRAD_DEBT_MDN`
   lands as a `cohort_money_stats` row with status `suppressed_by_publisher` and
   a NULL value, and the `PrivacySuppressed`-spelled `NPT43_PUB` still does too.
   **Fails on today's code** for the first half.
6. **`CanonicalMoneyLoaderTest`** — `FillResult.cellsSuppressedByPublisher` is
   `{NPT43_PUB=1, GRAD_DEBT_MDN=1}` for the fixture — BOTH columns, the
   domain-free net price as well as the domain-checked debt figure — and
   `fieldsCoercedToNull` is byte-for-byte what it is today.
7. **`CanonicalMoneyIngestTest`** — the end-to-end assertion that suppression
   survives the fill now runs on the publisher's real spelling.

The full suite is the gate: `nix develop -c bin/test`.
