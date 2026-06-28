# RFC 78: Harden College Scorecard Ingestion Against Real-Data Quirks

## Executive Summary

The `CollegeScorecardLoader` was built and tested against hand-authored
22-column synthetic CSV fixtures. Running it against the real federal Scorecard
files (`Most-Recent-Cohorts-Institution.csv`, 3,308 columns;
`Most-Recent-Cohorts-Field-of-Study.csv`, 178 columns) surfaces loader and
schema assumptions that reject valid data, drop whole institutions on a single
junk optional cell, and hide the loss behind generic per-row warnings. This RFC
lands the fixes and the test harness that would have caught them.

The work has two halves. First, a **real-data test harness**: verbatim,
machine-extracted real Scorecard rows plus the full real headers are committed
as fixtures (~100 KB, public-domain), and the real loader runs against them,
turning each observed quirk into an executable assertion. Second, the
**hardening** the harness pins:

- Re-land the interim savepoint (root-cause skip logging; migration 0021
  relaxing `cip_code` to the real 2/4/6-digit CIP grammar; migration 0022
  dropping the net-price non-negativity CHECK).
- **Per-field** best-effort for optional metrics: an out-of-domain optional
  value (e.g. `LOCALE=2`) is coerced to `NULL` and the institution is kept,
  instead of the DB CHECK dropping the whole row and cascading to its programs.
- Read the real `OPEID` column (the loader reads a nonexistent `OPEID8`, so
  `colleges.opeid` is always null).
- Recognize known source sentinels — `UNITID=NA` field-of-study rows (~7,020)
  and `CREDLEV=99` (~590) — and skip them with precise, counted reasons.
- Replace per-row warning spam with an end-of-load summary of skip counts by
  reason and constraint.

## Detailed Design

### Three disposition mechanisms

The loader currently has two outcomes per row: load, or skip-and-log. Real data
needs the skip path split by cause and a third outcome added. The design is
three mechanisms, applied in this precedence:

- **A — Optional metric out of domain → null the field, keep the row.** An
  optional enrichment column whose parsed value falls outside its NCES/IPEDS
  domain is set to `NULL`; the row still loads. This is the per-field
  granularity of the best-effort contract in
  [`college/.../SPEC.md`](../college/src/main/kotlin/ed/unicoach/college/SPEC.md)
  §II — a single junk optional cell must not drop an institution and cascade to
  its programs.
- **B — Required/key field is a known junk sentinel → skip the row, bucket it
  precisely.** `UNITID=NA` and `CREDLEV=99` are documented Scorecard sentinels
  in required/key columns that cannot be nulled; the row is skipped under a
  named reason and counted, never silently.
- **C — Any other DB rejection → skip the row, bucket by constraint.** A
  required field out of domain (e.g. `CONTROL=4`), or any unanticipated
  CHECK/unique violation, is rolled back to its savepoint and counted under the
  violated constraint name.

Mechanism A is the load-time distinction from valid-but-out-of-range values:
negative `net_price` is a _valid_ figure kept by dropping its CHECK (migration
0022); `LOCALE=2` is _junk_ nulled per-field while the
`colleges_locale_range_check` CHECK is retained as a backstop.

### Schema migrations (re-land of the interim savepoint)

Two new append-only migrations, applied by `bin/db-migrate` after `0020`:

- **`db/schema/0021.relax-college-programs-cip-format.sql`** — drops and re-adds
  `college_programs_cip_code_format_check` with pattern `^([0-9]{2}){1,3}$` (2,
  4, or 6 digits). The original `^[0-9]{6}$` in `0015.create-colleges.sql`
  assumed every `CIPCODE` was a 6-digit detail code; the field-of-study file
  encodes 2-digit series, 4-digit family (e.g. `0901`), and 6-digit detail
  codes, and the six-only CHECK rejected the 2- and 4-digit rows en masse.
  1/3/5/7-digit widths remain rejected (a 3-digit value is almost always a
  leading zero stripped from a 4-digit code — the corruption `cip_code` is
  `TEXT` to defend against). The constraint name is reused so assertions keyed
  on it keep matching.

- **`db/schema/0022.drop-college-net-price-nonneg-check.sql`** — drops
  `colleges_net_price_nonneg_check`. Net price is cost of attendance minus
  average aid; at heavily-subsidized institutions aid exceeds cost and the
  published figure is negative (Ventura College, `UNITID=125028`,
  `NPT4_PUB=-982`). The CHECK rejected those whole institutions, cascading into
  "no college" skips for every one of their programs. Dropped outright (net
  price has no semantic lower bound); the sibling cost/tuition/earnings
  non-negativity CHECKs are kept.

`0015.create-colleges.sql` is a committed migration and is **not edited**; its
inline `-- OPEID8`, `6-digit`, and `net_price >= 0` comments are stale but
immutable. The applied schema is the source of truth.

No migration is needed for mechanisms A, B, or the OPEID fix — they are loader
changes; the `colleges_locale_range_check` /
`college_programs_credential_level_range_check` CHECKs stay as DB-level
backstops.

### Loader changes — `CollegeScorecardLoader.kt`

`load`, `upsertWithSavepoint`, and the savepoint discipline are unchanged. The
changes are in row mapping, cell coercion, skip accounting, and the result type.

**1. Root-cause skip description (re-land).** `describe(error)` walks to the
root-cause throwable and, when it is a `java.sql.SQLException`, surfaces the
SQLSTATE and the trimmed message instead of the wrapping `DaoException`'s
generic "Database constraint violation". A private
`rootCause(Throwable): Throwable` helper is added.

**2. Read the real OPEID column.** `mapInstitution` reads `OPEID` (the 8-digit
OPE ID, column 2; e.g. Auburn `00831000`) instead of the nonexistent `OPEID8`.
The `colleges.opeid` column intent is unchanged; only the source header name is
corrected so the column stops loading null. (`OPEID6`, column 3, is the 6-digit
base and the natural join key for the deferred `UNITID=NA` linking below; the
stored 8-digit `OPEID` contains it as its leading 6 characters.)

**3. Per-field optional-metric coercion (mechanism A).** `mapInstitution`
coerces each bounded _optional_ metric to `NULL` when its parsed value is
outside the column's domain, recording the coercion by column name. Two private
helpers are added:

```
private fun intInDomainOrNull(record, column, min, max): Int?   // null if absent, non-int, or out of [min,max]
private fun doubleInDomainOrNull(record, column, min, max): Double?
```

The coerced columns and their domains (mirrored from the `0015` CHECKs; the DB
CHECK remains the backstop, so this duplication is intentional defense-in-depth)
are held as named constants:

| Column                 | Source            | Domain     |
| ---------------------- | ----------------- | ---------- |
| `region`               | `REGION`          | `0..9`     |
| `locale`               | `LOCALE`          | `11..43`   |
| `admission_rate`       | `ADM_RATE`        | `0.0..1.0` |
| `graduation_rate`      | `C150_4`          | `0.0..1.0` |
| `pct_pell`             | `PCTPELL`         | `0.0..1.0` |
| `undergrad_enrollment` | `UGDS`            | `0..MAX`   |
| `sat_avg`              | `SAT_AVG`         | `0..MAX`   |
| `cost_attendance`      | `COSTT4_A`        | `0..MAX`   |
| `tuition_in_state`     | `TUITIONFEE_IN`   | `0..MAX`   |
| `tuition_out_state`    | `TUITIONFEE_OUT`  | `0..MAX`   |
| `median_earnings`      | `MD_EARN_WNE_P10` | `0..MAX`   |

`net_price` is **excluded** (negatives are valid; CHECK dropped by 0022).
`latitude`/`longitude` are unbounded in the schema and uncoerced. Required
fields (`UNITID`, `INSTNM`, `CITY`, `STABBR`, `CONTROL`) are never coerced — a
missing or junk required field skips the whole row (mechanism B/C).

**4. Known-sentinel pre-filters (mechanism B).** `mapField` distinguishes the
two field-of-study sentinels before attempting an upsert:

- Raw `UNITID == "NA"` → skip with reason `UnitIdNa`. These 7,020 field-of-study
  rows belong to 822 non-IPEDS institutions (mostly for-profit) whose real id is
  `OPEID6` (column 2); 821 of the 822 are absent from the institution file
  entirely, so there is no `unit_id`-keyed `colleges` row to attach them to.
  **Decision:** out of scope for this RFC — detected and counted, but not
  linked. Linking would mean synthesizing ~821 metric-less `colleges` rows from
  field-of-study columns under an `OPEID6`-derived identity (the `colleges`
  natural key is `unit_id`, `NOT NULL` with `CHECK (unit_id > 0)`), a
  schema/identity change of low product value (these are the under-documented
  for-profits a coach is least likely to surface). The distinct `UnitIdNa`
  bucket honors "no silent skips" and hands a future linking RFC the real
  magnitude separated from genuine blank-`UNITID` rows.
- Parsed `CREDLEV` not in `1..8` → skip with reason
  `CredentialLevelOutOfDomain`. `99` ("Non-Credential Program") is a documented
  Scorecard sentinel appearing at scale (~590 rows; e.g. line 787, Auburn Univ
  at Montgomery, `CIPCODE=2601`, `CIPDESC="Biology, General."`).
  `credential_level` is `NOT NULL` and part of the upsert key so it cannot be
  nulled. **Decision:** pre-filter (skip with a precise named bucket) rather
  than let the DB CHECK reject it, so the ~590 rows surface as one
  intent-revealing count instead of a generic constraint bucket, and avoid a
  savepoint round-trip per row. `CONTROL` (the analogous required-domain column
  on institutions) is _not_ pre-filtered — out-of-domain `CONTROL` is unattested
  in the real data and stays on the generic constraint path (mechanism C).

**5. Skip taxonomy and per-file accounting (mechanism C + summary).** A
`SkipReason` type enumerates the buckets:

```
sealed interface SkipReason {
  data object MissingRequiredField : SkipReason
  data object NoCollegeForUnitId : SkipReason
  data object UnitIdNa : SkipReason
  data object CredentialLevelOutOfDomain : SkipReason
  data class  ConstraintViolation(val constraint: String?) : SkipReason
  data object Transient : SkipReason
}
```

The `LoadCount` accumulator gains a `MutableMap<SkipReason, Int>` skip tally and
a `MutableMap<String, Int>` field-coercion tally. **Every** skip — including the
pre-DB `mapInstitution`/`mapField` skips that today only log and `continue`
without counting — increments the tally. Per-row skip logging is demoted to
`DEBUG`; a `TransientError` skip stays at `WARN` (rare and retryable). The
per-row `DEBUG` line carries the row's natural-key identity and line number
(already in hand from the CSV row) plus the exception's `constraintName` and
`detail`, so a drill-down shows which value failed without dumping every row at
`WARN`. At the end of each file the loader emits one summary log line of the
non-empty skip and coercion tallies. A post-DB upsert failure is bucketed by
category: `TransientError` → `Transient`; `ConstraintViolationException` →
`ConstraintViolation(constraintName)`; any other `DaoException` →
`ConstraintViolation(null)`.

**6. `LoadResult` extension.** The result carries the structured breakdown; the
existing `transientSkips`/`permanentSkips` accessors are preserved as derived
values so `IngestApplication` and current tests keep compiling:

```
data class LoadResult(
  val collegesLoaded: Int,
  val programsLoaded: Int,
  val skipsByReason: Map<SkipReason, Int> = emptyMap(),
  val fieldsCoercedToNull: Map<String, Int> = emptyMap(),
) {
  val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
  val permanentSkips: Int get() = skipsByReason.filterKeys { it != SkipReason.Transient }.values.sum()
}
```

`load` merges the two per-file tallies (summing `ConstraintViolation` entries by
their constraint key) into the combined `LoadResult`.

### DB exception change — `ConstraintViolationException`

`ConstraintViolationException` gains two nullable diagnostic fields populated
from the PostgreSQL error, so the loader can bucket by constraint and surface
the failing key without parsing log text in the application layer.

```
class ConstraintViolationException(
  cause: Throwable,
  val constraintName: String? = null,
  val detail: String? = null,
) : DaoException("Database constraint violation", cause), PermanentError
```

`CollegesDao.mapCollegeError` populates both for `23514`/`23505` by reading
`org.postgresql.util.PSQLException.serverErrorMessage?.constraint` and `.detail`
(the `db` module already depends on `libs.postgresql`; the `college` module
never sees the pgjdbc type — it reads the plain `String?`s). `detail` is the
DETAIL line: for a unique/FK violation it is a clean `Key (col)=(val) …`
carrying the failing value; for a CHECK violation Postgres reports the whole
`Failing row
contains (…)` tuple, captured verbatim but not parsed. The defaults
keep the ~10 existing construction sites compiling unchanged; when the cause is
not a `PSQLException` both are null. (Surfacing the full structured set —
`column`, `table`, and the parsed CHECK failing value — is deferred to a future
RFC.)

### Reporting — `IngestApplication`

`main` logs the summary maps (`skipsByReason`, `fieldsCoercedToNull`) in
addition to the existing four counts, and keeps the transient-skip re-run
warning.

### Real-data test harness

Two new fixtures hold verbatim, machine-extracted real rows plus the full real
headers. They are never hand-edited; the implementation plan gives the exact
extraction commands.

- **`college/src/test/resources/scorecard-institutions-real-fixture.csv`** — the
  real 3,308-column header plus three verbatim institution rows:
  - `125028` Ventura College (`CONTROL=1`, `LOCALE=12`, `NPT4_PUB=-982`,
    `OPEID=00133400`) — loads with a negative `net_price` (guards 0022).
  - `136455` Pensacola Christian College (`CONTROL=2`, `LOCALE=2`,
    `OPEID=03018800`) — loads with `locale` coerced to null (guards mechanism
    A).
  - `100830` Auburn University at Montgomery (`CONTROL=1`, `LOCALE=12`,
    `OPEID=00831000`) — clean anchor for the program rows; `opeid` populated.

- **`college/src/test/resources/scorecard-fields-real-fixture.csv`** — the real
  178-column header plus verbatim field-of-study rows:
  - Auburn `100830` clean rows: `CIPCODE` `0301`/`0901` (4-digit, guard 0021)
    and `1101` (`CIPDESC="Computer and Information Sciences, General."`, quoted
    embedded comma — guards CSV parsing) at `CREDLEV` `3` and `5`.
  - Auburn `100830` line 787: `CIPCODE=2601`, `CREDLEV=99` — skipped under
    `CredentialLevelOutOfDomain`, exercises quoted-comma parsing on a skipped
    row.
  - `UNITID=NA` rows (`OPEID6=001023`, Judson College) — skipped under
    `UnitIdNa`.
  - Ventura `125028` `CIPCODE=0101` `CREDLEV=2` — links to the
    negative-net-price college.

The fixtures' larger size (institution rows ~15–20 KB each) is the deliberate
cost of testing against real column layout, quoting, and the blank-cell idiom.

### New invariant — `college/.../INVARIANTS.md`

Add one invariant capturing mechanism A, alongside the existing totality and
savepoint invariants. It is not redundant with the savepoint invariant: that
rule scopes one bad _row_'s blast radius; this one scopes one bad _optional
cell_'s blast radius, keeping a junk value from silently dropping the
institution and cascading to its programs.

> **An out-of-domain _optional_ metric is coerced to NULL, never allowed to drop
> the row.** The loader nulls an optional enrichment column whose value is
> outside its domain and keeps the institution; only a missing/invalid _required
> or key_ field skips the whole row. **Why:** an institution row anchors its
> field-of-study programs by `unit_id`; dropping it on one junk optional cell
> silently cascades to every program, the failure mode this granularity exists
> to prevent.

No `db/schema/INVARIANTS.md` change is required: the relaxed/dropped CHECKs are
not enshrined there.

### Error Handling / Edge Cases

- A row with multiple out-of-domain optional metrics has each nulled
  independently; the row still loads.
- A row failing both a sentinel pre-filter and a later CHECK is dispositioned by
  the first matching mechanism (precedence A→B→C), counted once.
- Idempotency is preserved: coercion and pre-filtering are pure functions of the
  row, so a re-run produces identical counts and rows.
- The real header carries 3,286 columns the loader ignores; `record.isMapped`
  already guards absent columns, so unread columns are inert.

### Dependencies

No new build dependencies. `commons-csv`, `:db`, and `libs.postgresql` (in
`:db`) are already present.

## Tests

DB-backed JUnit 5, run via `nix develop -c bin/test db college --force`.

### New: `CollegeScorecardRealDataTest` (`college` module)

Runs the real loader against the two real-data fixtures; `@BeforeEach` truncates
`colleges, college_programs`. Mirrors `CollegeScorecardLoaderTest`'s DB wiring.

- `negative net_price loads (guards 0022)` — `findByUnitId(125028)` non-null,
  `netPrice == -982`.
- `out-of-domain optional locale is nulled, institution kept (mechanism A)` —
  `findByUnitId(136455)` non-null, `locale == null`, and a valid field
  (`control == 2`) retained; `result.fieldsCoercedToNull["locale"] == 1`.
- `opeid loaded from real OPEID column (item 3)` —
  `findByUnitId(100830).opeid ==
  "00831000"`.
- `4-digit and 6-digit CIP programs load (guards 0021)` — Auburn programs for
  `cip_code` `0301` and `0901` are present.
- `quoted embedded comma in CIPDESC parses intact` — the `1101` program's
  `cip_title == "Computer and Information Sciences, General."`.
- `credlev 99 row is skipped and counted, neighbors survive (mechanism B)` — the
  `2601`/`99` program is absent;
  `result.skipsByReason[CredentialLevelOutOfDomain]
  > = 1`; the other Auburn programs still load.
- `UNITID=NA rows are skipped and counted, not silently lost (mechanism B)` —
  `result.skipsByReason[UnitIdNa] >= 1`; no college/program created for them.
- `Ventura program links to its negative-net-price college` — the `0101` program
  resolves to `collegeId` of `125028`.
- `summary has no transient skips against clean real data` —
  `result.skipsByReason[Transient]` is null/0.
- `re-running the real-data load is idempotent` — second load yields identical
  `collegesLoaded`/`programsLoaded` and identical row counts.

### Updated: `CollegeScorecardLoaderTest` (`college` module)

- Re-land program-count change `8 → 9` in `loads institutions and programs` and
  `re-running the loader is idempotent` (the appended `0901` field row).
- `a row violating a DB CHECK is skipped …`: the check-violation fixture's bad
  row changes from `ADM_RATE=1.5` (now coerced to null by mechanism A, so it
  would _load_) to `CONTROL=4` (an out-of-domain **required** field, still
  rejected). Assert the row is absent, neighbors survive, and
  `result.skipsByReason[ConstraintViolation("colleges_control_valid_check")] == 1`.
- `a row missing required fields is skipped, others load`: additionally assert
  `result.skipsByReason[MissingRequiredField] == 1` (the empty-`UNITID` row is
  now counted, not just logged).
- New `an out-of-domain optional field is coerced to null, not rejected`: a
  synthetic institution row with `ADM_RATE=1.5` loads with
  `admissionRate == null` and `fieldsCoercedToNull["admission_rate"] == 1`.

### Updated: `CollegesDaoTest` (`db` module)

- Re-land `negative net_price is accepted`,
  `cip_code at 2, 4 or 6 digits is
  accepted`, and
  `cip_code of an impossible width or non-digit is rejected`.
- New `ConstraintViolationException carries the constraint name and detail`: an
  upsert that violates a named CHECK (e.g. `colleges_control_valid_check`) fails
  with `constraintName == "colleges_control_valid_check"`; a duplicate-`unit_id`
  upsert fails with `constraintName` set and `detail` containing the failing
  `Key (unit_id)=(…)`.

## Implementation Plan

Each step is independently verifiable. Run all DB-backed checks with
`nix develop -c bin/test db college --force` (recreates and migrates the test
DB, so new migrations apply automatically). Do not stage unrelated files.

1. **Re-land migrations.** Create
   `db/schema/0021.relax-college-programs-cip-format.sql` and
   `db/schema/0022.drop-college-net-price-nonneg-check.sql` with the bodies from
   the interim savepoint (commit `0ca699a`). _Verify:_
   `nix develop -c bin/db-migrate` applies both without error;
   `nix develop -c psql "$DATABASE_URL" -c "\d+ college_programs"` shows the
   relaxed `cip_code` pattern and `\d+ colleges` shows no `net_price` CHECK.

2. **Re-land DAO tests.** Add the negative-net-price and CIP-width tests to
   `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`. _Verify:_
   `nix develop -c bin/test db --force` passes.

3. **Extend `ConstraintViolationException`.** Add `constraintName`/`detail`
   (both `String? = null`) in
   `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt`; populate both in
   `CollegesDao.mapCollegeError` from the `PSQLException` server error message
   (`.constraint`/`.detail`) for `23514`/`23505`. Add the constraint-name/detail
   DAO test (step-2 file). _Verify:_ `nix develop -c bin/test db --force` passes
   (all other DAO call sites compile unchanged).

4. **Loader: root-cause logging + OPEID.** In
   `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`,
   re-land `describe`/`rootCause`, and change the `OPEID8` read to `OPEID`.
   _Verify:_ `nix develop -c bin/test college --force` passes (counts updated in
   step 7).

5. **Loader: per-field coercion (mechanism A).** Add `intInDomainOrNull` /
   `doubleInDomainOrNull`, the domain constants, and the per-column coercion +
   `fieldsCoercedToNull` tally in `mapInstitution`. _Verify:_ compiles via
   `nix develop -c ./gradlew :college:compileKotlin`.

6. **Loader: skip taxonomy + sentinels + summary (mechanisms B/C).** Add
   `SkipReason`; extend `LoadCount` with the skip and coercion tallies and a
   `recordSkip(SkipReason)`; add the `UNITID=NA` and `CREDLEV ∉ 1..8`
   pre-filters in `mapField`; route all pre-DB and post-DB skips through the
   tally; demote per-row skip logs to `DEBUG` (transient stays `WARN`); emit one
   per-file summary line; extend `LoadResult` with derived
   `transientSkips`/`permanentSkips`. _Verify:_
   `nix develop -c ./gradlew :college:compileKotlin`.

7. **Update synthetic fixtures + loader tests.** Rename the `OPEID8` header to
   `OPEID` in `scorecard-institutions-fixture.csv` and
   `scorecard-institutions-check-violation-fixture.csv`; change the
   check-violation bad row from `ADM_RATE=1.5` to `CONTROL=4`; append the `0901`
   field row to `scorecard-fields-fixture.csv`. Update
   `CollegeScorecardLoaderTest` per the Tests section (`8→9`, the
   check-violation reason assertion, the missing-required count, and the new
   coercion test). _Verify:_ `nix develop -c bin/test college --force` passes.

8. **Extract real-data fixtures.** From `~/Downloads/c/`, write the real headers
   and the verbatim rows into the two real fixtures (machine-extracted, no
   manual editing):
   - Institutions: `head -1 Most-Recent-Cohorts-Institution.csv` then
     `awk -F',' '$1==125028 || $1==136455 || $1==100830'` →
     `scorecard-institutions-real-fixture.csv`.
   - Fields: `head -1 Most-Recent-Cohorts-Field-of-Study.csv`; the Auburn clean
     rows
     (`awk -F',' '$1==100830' | grep -E '^100830,008310,[^,]*,Public,1,(0301|0901|1101),'`),
     `sed -n '787p'` (the `CREDLEV=99` row), the first few `UNITID=NA` rows
     (`awk -F',' '$1=="NA"' | head -3`), and the Ventura `0101` row
     (`awk -F',' '$1==125028' | grep ',0101,'`) →
     `scorecard-fields-real-fixture.csv`. _Verify:_ `wc -l` shows header +
     expected row counts; total fixture size ~100 KB.

9. **Add `CollegeScorecardRealDataTest`.** Implement every test in the Tests
   section against the real fixtures. _Verify:_
   `nix develop -c bin/test college --force` passes.

10. **Reporting + invariant.** Update `IngestApplication.main` to log the
    summary maps. Add the proposed optional-metric invariant to
    `college/src/main/kotlin/ed/unicoach/college/INVARIANTS.md` (pending the
    human gate). _Verify:_ `nix develop -c bin/test check` (full Kotlin + ktlint
    gate) is green.

## Files Modified

- `db/schema/0021.relax-college-programs-cip-format.sql` (new) — relax
  `cip_code` CHECK to `^([0-9]{2}){1,3}$`.
- `db/schema/0022.drop-college-net-price-nonneg-check.sql` (new) — drop
  `colleges_net_price_nonneg_check`.
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt` — add
  `constraintName`/`detail` diagnostic fields to `ConstraintViolationException`.
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — populate
  `constraintName`/`detail` from `PSQLException` in `mapCollegeError`.
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt` — re-land net-price
  / CIP-width tests; add constraint-name/detail test.
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  root-cause logging, `OPEID` read, per-field coercion, sentinel pre-filters,
  `SkipReason` taxonomy, summary accounting, `LoadResult` extension.
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt` — log the
  skip/coercion summary.
- `college/src/main/kotlin/ed/unicoach/college/INVARIANTS.md` — add the
  optional-metric coercion invariant (human-gated).
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardLoaderTest.kt` —
  updated counts and assertions; new coercion test.
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardRealDataTest.kt`
  (new) — the real-data harness.
- `college/src/test/resources/scorecard-institutions-fixture.csv` — `OPEID8` →
  `OPEID` header.
- `college/src/test/resources/scorecard-institutions-check-violation-fixture.csv`
  — `OPEID8` → `OPEID` header; bad row `ADM_RATE=1.5` → `CONTROL=4`.
- `college/src/test/resources/scorecard-fields-fixture.csv` — append the `0901`
  field row.
- `college/src/test/resources/scorecard-institutions-real-fixture.csv` (new) —
  verbatim real institution header + three rows.
- `college/src/test/resources/scorecard-fields-real-fixture.csv` (new) —
  verbatim real field-of-study header + the quirk rows.

## History

- [ ] RFC 78: College Scorecard real-data hardening + real-data test harness.
