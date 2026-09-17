# RFC 218 — Field-of-study money: what this major returns

**Slice:** `beyond/03/what-this-major-returns` (brief
`product/0011-beyond-money`). **Status:** proposed. **Lane:** A — new table, new
ingest phase, new pinned vintages.

## Summary

The College Scorecard publishes earnings and student-debt figures **per
program** — per (college, 4-digit CIP field, credential level) — and we read
four of the 178 columns of that file today, none of them a figure. This RFC
loads three:

| Publisher variable              | What it is                                            |
| ------------------------------- | ----------------------------------------------------- |
| `EARN_MDN_4YR`                  | median earnings 4 years after entry, Treasury         |
| `DEBT_ALL_STGP_EVAL_MDN`        | median federal loan debt of completers, NSLDS         |
| `DEBT_ALL_STGP_EVAL_MDN10YRPAY` | the publisher's own monthly payment on a 10-year plan |

They land in a new table, `program_money_stats`, beside the existing canonical
money store rather than inside it.

**Nothing a family can see changes in this run.** This slice fills a store; the
sentences that read it are `beyond/05/the-major-answer`'s work, and the spec
says so. There is no new tool, no new route, no prompt edit and no page change.
The door is the operator's: `nix develop -c bin/ingest-colleges`.

**We never compute a debt-to-earnings ratio** (brief 0011 D27). The earnings
column and the debt columns are different cohorts, different publishers and
different dollar bases; a ratio over them is a figure nobody published. We store
the publisher's own 10-year monthly payment instead, and store it because the
PUBLISHER computed it — a payment we derived from a debt figure would be a
stored derived figure, which CLAUDE.md forbids.

## Why a sibling table and not `cohort_money_stats`

`cohort_money_stats` is keyed institution-level
`(college_id, measure, population, residency_scope, aid_scope, income_band, vintage)`
with closed `CHECK IN (...)` lists on four of those columns, and a unique index
over that key with `NULLS NOT DISTINCT`. A nullable `cip_code` bolted onto it
would silently widen every existing reader's `WHERE`: today "the median debt at
this college" is one row, and after such a change it is one row plus 391. Brief
0011 D26 settles it — a sibling table — and D37 backs it: model each thing as
what it is, never deform an existing model to absorb something new.

What the sibling REUSES is the canonical store's furniture, not its key: the
`figure_statuses` vocabulary, the `money_source` domain with `source_variable`,
and the value-iff-status CHECK that makes a value without a value-bearing status
impossible to insert.

It does NOT carry a `publisher_flag`. `cohort_money_stats` has one because ITS
publisher files one — the raw IPEDS imputation code — and this table does not
because ITS publisher does not file one beside these three columns. A column
with no writer is a question every future reader has to ask and nobody can
answer.

## Three silence arms, not two

At bachelor's level (`CREDLEV=3`, 71,664 of 227,980 rows), measured on the
committed snapshot (`release 06102026`) rather than reconstructed:

| Column                          | numeric | `PS`   | `NA`  | blank |
| ------------------------------- | ------- | ------ | ----- | ----- |
| `EARN_MDN_4YR`                  | 27,617  | 44,047 | 0     | 0     |
| `DEBT_ALL_STGP_EVAL_MDN`        | 26,005  | 40,870 | 4,789 | 0     |
| `DEBT_ALL_STGP_EVAL_MDN10YRPAY` | 26,005  | 40,870 | 4,789 | 0     |

The spec called the third arm "blank". **The file contains no blank cells in
these columns at all**; the third arm is the literal token `NA`, which
`CsvIngestSupport.NULL_SENTINELS` already recognises as school silence. The arm
is real and the count is right; only the word was wrong. Recorded here because
the code wins over the spec, and reported back to /chart.

So the three arms map to three stored statuses, and a reader can tell them
apart:

| Cell    | Status                        | What it means                           |
| ------- | ----------------------------- | --------------------------------------- |
| numeric | `reported`                    | the publisher published a figure        |
| `PS`    | `suppressed_by_publisher`     | withheld for privacy — too few students |
| `NA`    | `not_reported_by_institution` | no figure for this program              |

RFC 206 built that recognition at the seam and this RFC builds nothing of it
again: the new loader calls the same `statusfulCell`.

## The table (brief 0001 D10 — presented at the approval gate)

One migration, `db/schema/create-program-money-stats.sql`, plus its line in
`db/schema/ORDER` (RFC 180: a kebab slug, no number).

```sql
CREATE TABLE program_money_stats (
    id                 UUID         NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The figure is a fact about a PROGRAM, so it points at the program row
    -- rather than restating its three-column grain. college_programs already
    -- holds (college_id, cip_code, credential_level) under a unique index, and
    -- a second copy here would be a second place for them to disagree.
    college_program_id UUID         NOT NULL REFERENCES college_programs(id) ON DELETE CASCADE,

    measure            TEXT         NOT NULL,

    -- The FIRST and LAST academic year the publisher's cohort pools, each as
    -- its START year: earnings pool AY2017-18 and AY2018-19, so 2017 and 2018.
    -- EQUAL when a figure pools nothing. The academic_year DOMAIN (0087) makes
    -- the range rule one rule and makes "2017-19" unspellable -- its own
    -- comment is the argument: "a regex cannot say 'the second half is the
    -- first plus one'."
    cohort_first_year academic_year NOT NULL,
    cohort_last_year  academic_year NOT NULL,

    -- Whole US dollars, nominal. INTEGER and unit-suffixed, on
    -- price_figures.amount_usd's precedent (0083:191) and the naming rule 0059
    -- states: every numeric column says its UNIT, and the unit comes last.
    amount_usd         INTEGER      NULL,
    status             TEXT         NOT NULL REFERENCES figure_statuses(slug),

    source             money_source NOT NULL,
    source_variable    TEXT         NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT program_money_stats_measure_check
        CHECK (measure IN (
            'median_earnings_4yr_after_entry',
            'median_debt_at_completion',
            'median_debt_10yr_monthly_payment'
        )),

    -- An amount exists exactly when the status is one that bears a value. The
    -- canonical store's own rule, copied without deviation: a suppressed or
    -- unreported figure with a number in it is not representable.
    CONSTRAINT program_money_stats_amount_iff_status_check
        CHECK ((amount_usd IS NOT NULL) = (status IN ('reported', 'imputed_by_publisher'))),

    -- Dollars, never negative. The publisher states none of these can be.
    CONSTRAINT program_money_stats_amount_nonnegative_check
        CHECK (amount_usd IS NULL OR amount_usd >= 0),

    -- A cohort cannot end before it starts.
    CONSTRAINT program_money_stats_cohort_years_ordered_check
        CHECK (cohort_last_year >= cohort_first_year),

    CONSTRAINT program_money_stats_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

-- The natural key. One figure per program, per measure, per cohort: a snapshot
-- that re-dates a column adds rows beside the old ones rather than overwriting
-- a cohort nobody re-published.
CREATE UNIQUE INDEX program_money_stats_unique_idx
    ON program_money_stats (
        college_program_id, measure,
        cohort_first_year, cohort_last_year
    );

CREATE TRIGGER trigger_03_enforce_program_money_stats_updated_at
BEFORE UPDATE ON program_money_stats
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
```

Plus `COMMENT ON TABLE` / `COMMENT ON COLUMN` in the house style, and no further
index: the only read this slice creates is the rebuild's own delete, and the
reader `beyond/05` will query by program — the leading column of the unique
index. An index describing a query nobody has written yet is worse than none.

Row count at full ingest: three rows per loadable field-of-study row, about
**684,000** rows, of which roughly two thirds are absence rows carrying a status
and no amount. Absence rows are stored on purpose — "the publisher withheld
this" is a fact a family is owed, and RFC 206 exists because we once served it
as silence.

## Dollars, not decimals — and cents are discarded

Every figure in this table is money: median earnings, median debt, and a monthly
payment. So the column is `amount_usd INTEGER` — whole US dollars, nominal — and
not a `NUMERIC` called `value`. Two repo rules force it, and both point the same
way:

- **Every numeric column says its UNIT, and the unit comes last** (migration
  `0059`): money is `_usd`, whole dollars, nominal, `INTEGER`.
- `price_figures.amount_usd INTEGER` (`0083:191`) is the table that already got
  this right, with the same two CHECKs this table carries.

**Cents are discarded at ingest, by rule and not by accident.** Ian, 2026-09-16:
_"ignore the cents. they are not material."_ The publisher's 10-year monthly
payment is the only one of the three that can arrive with a fraction. The loader
rounds it **half up to whole dollars**, in ONE place (`ProgramMoneyLoader`), and
a test asserts that rounding by name — `123.49` to `123`, `123.50` to `124`. It
never truncates silently, and it never stores a fraction. A reader looking for
what happened to the cents finds this paragraph, that function, and that test.

### Why this table is unit-suffixed and `cohort_money_stats` is not

`cohort_money_stats.value` is `NUMERIC` and carries no unit in its name for a
real reason: it mixes kinds. `pell_share` — a share between 0 and 1 — sits in
the same column as `avg_net_price`, so no unit suffix could be true of it. This
table has no such mixture: all three measures are dollars, so the column can
state its unit and does. **Do not "fix" this column back to match the older
table.**

One note for the product layer, not work for this run:
`cohort_money_stats.value` is now the last unitless money column in the
canonical store, and it has a real reason. Whether it ever gains a per-unit
split is a later decision, and not this slice's.

### What the three measures mean

| `measure`                          | `source_variable`               | Publisher                  |
| ---------------------------------- | ------------------------------- | -------------------------- |
| `median_earnings_4yr_after_entry`  | `EARN_MDN_4YR`                  | Treasury                   |
| `median_debt_at_completion`        | `DEBT_ALL_STGP_EVAL_MDN`        | NSLDS (federal loan files) |
| `median_debt_10yr_monthly_payment` | `DEBT_ALL_STGP_EVAL_MDN10YRPAY` | NSLDS, publisher-computed  |

`source` is `scorecard` for all three — the existing `money_source` DOMAIN, not
a new per-table CHECK, because the list of publishers is one list wherever the
question is asked.

## The loader

A new `ProgramMoneyLoader` in `college/`, run as its own phase in
`CollegeScorecardLoader.loadScorecard`, after `canonical-money` and before
`search-index`. It is NOT a branch of `CanonicalMoneyLoader.fill`: that function
is a source-precedence loop over `ORDERED_SOURCES`, resolving competing
publishers for one institution-level key. These figures have exactly one
publisher and a different grain, so an arm inside that loop would deform it
(D37).

What it does, per run:

1. `DELETE FROM program_money_stats` for the rows of this source — the canonical
   store's rebuild shape (`deleteFactsOfSources`), not an upsert. The Scorecard
   file is a snapshot and the table is a projection of it.
2. Stream the field-of-study CSV a second time (the `fields` phase already
   streams it once, and holding 684,000 staged rows in memory to save one pass
   is the wrong trade).
3. Per row: reuse `mapField`'s existing skip rules (a missing key field, an `NA`
   UNITID, a `CREDLEV` outside 1..8), resolve the program id, then read the
   three cells through `CsvIngestSupport.statusfulCell` — the same seam RFC 206
   taught to recognise `PS`.
4. Batch insert, on `CanonicalMoneyDao.batchInsert`'s shape.
5. Report: rows seen, rows written, skips by named reason, and the absence
   counters below.

A row whose program is not in `college_programs` cannot happen in a normal run —
the `fields` phase wrote it minutes earlier in the same ingest — but it is
counted, named and skipped rather than assumed away.

## The third absence counter (brief 0011 D45)

RFC 206 left `CellAbsenceTally` with two counters — values coerced out of range,
values the publisher suppressed — and a `logger.warn` for the third arm: a
PRESENT token that is neither a known blank nor a known sentinel and does not
parse. Its review asked for a counter; the slice correctly declined a product
decision its gate had not covered. D45 decided it: **this run builds the
counter.**

    A log line is invisible until somebody already suspects a problem. A number
    in the operator summary is what makes a sudden change visible.

So `CellAbsenceTally` gains `recordUnrecognisedToken(column)` /
`unrecognisedTokens`, keyed by SOURCE COLUMN exactly like its two siblings —
`mapToNotReported` takes the tally and records there, and its KDoc paragraph
claiming "this one has no tally by design" is rewritten rather than left to
contradict the code. The `warn` line STAYS: the counter says how many and in
which column, the log line says which line and which token, and neither replaces
the other.

The count reaches the operator in both places the other two do —
`CanonicalMoneyLoader.logFillTally` and `IngestApplication.logCanonicalMoneyRun`
— so the institution fill gains the third number too, and the new program-money
phase prints its own.

## Vintage: pinned per column, in its own scope

`ScorecardInstitutionColumns` is the money seam's registry for the INSTITUTION
file, and `ScorecardDictionaryPinTest` asserts that the committed transcription
`db/seed/scorecard/dictionary-variable-sources.csv` has a row for EXACTLY the
variables in it — a set EQUALITY, both ways. Three consequences, all of which
say the same thing: a field-of-study variable does not belong in that registry.

1. Equality, not containment: adding a row fails the pin test, correctly.
2. Every `VintageGroup` member in it is an institution cohort, so joining one
   would stamp `EARN_MDN_4YR` with a tuition year.
3. Its `Cohort` type parses `AcadYr <label>` or empty, and these cohorts are
   pooled two-year spans — the one thing they must state is the one thing that
   column cannot hold.

This is not new ground: RFC 195's six score-band columns hit the same wall and
were deliberately kept OUT of that file, with their date in one named constant
whose KDoc quotes the publisher's cell, a release pin, and a test asserting the
two agree. This RFC follows that shipped precedent rather than reshaping a
sealed type: a new `ScorecardFieldOfStudyColumns` object carries, per variable,
the publisher, the `source_variable`, the two cohort years, the publisher's cell
VERBATIM and the inflation base year where one is published, with the document
and read date quoted in its KDoc; `db/seed/scorecard/FETCH-NOTES.md` gains the
citation section; and a new `ScorecardFieldOfStudyPinTest` asserts that the
loader writes cohort years for exactly those variables and that the pinned
snapshot release still equals `PROVENANCE.json`'s.

The guard therefore does not live inside the artifact under rewrite: the labels
are declared once in Kotlin, and the test checks them against the release the
fetcher wrote and against the columns the loader reads.

### The cohorts, in the publisher's own words

Read from `CollegeScorecardDataDictionary.xlsx` (727,278 bytes,
<https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
read 2026-09-15), sheet `FieldOfStudy_Cohort_Map`, column
`FieldOfStudyMostRecent datafile` — the column for the file we ingest. The
workbook's `ChangeLog` dates its latest field-of-study cohort refresh to **June
10, 2026**, which is the release in `PROVENANCE.json` (`06102026`). That is a
date match, not a stamped filename, and this RFC says so rather than claiming
more.

| Variable                        | Publisher's cell, verbatim                                                                                                | Stored years |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ------------ |
| `EARN_MDN_4YR`                  | `Treasury AY2017-2018, AY2018-2019 pooled earnings cohort measured in CY2022, CY2023, inflation adjusted to 2024 dollars` | 2017, 2018   |
| `DEBT_ALL_STGP_EVAL_MDN`        | `NSLDS pooled AY2018-19, AY2019-20 cohort`                                                                                | 2018, 2019   |
| `DEBT_ALL_STGP_EVAL_MDN10YRPAY` | `NSLDS pooled AY2018-19, AY2019-20 cohort`                                                                                | 2018, 2019   |

**The row stores the years; the column stores the sentence.** The two academic
years are a fact about the ROW — they are what this figure is about — so they
are typed columns on it. Everything else in that cell — which calendar years the
earnings were measured in, and that they are adjusted to 2024 dollars — is a
fact about the VARIABLE, identical on every one of the ~684,000 rows. Repeating
it per row would be storing one fact 684,000 times and inviting 684,000 versions
of it.

So it lives once per variable, in `ScorecardFieldOfStudyColumns`, which holds
for each of the three: the publisher, the `source_variable`, the two cohort
years, the publisher's cell quoted verbatim, and — for earnings — the
**inflation base year, 2024**, as a named field and not as prose inside a
string.

**Where a reader of the figure finds the inflation base.** An earnings figure is
in 2024 dollars and nothing on its row says so, which is exactly the kind of
silence RFC 183 exists to prevent. Three places answer it, and the first is the
one that matters:

1. `ScorecardFieldOfStudyColumns.EARN_MDN_4YR.inflationBaseYear = 2024`, reached
   from any row through its `source_variable` — the single lookup any reader,
   including `beyond/05`, must use to say the figure out loud.
2. The `COMMENT ON COLUMN program_money_stats.source_variable`, which names that
   object, so somebody reading the schema alone is sent to it.
3. `db/seed/scorecard/FETCH-NOTES.md`, with the workbook, sheet, byte size and
   read date above.

A sentence that speaks an earnings figure without its dollar base is a defect in
`beyond/05`, and that slice is told so here.

Three things the publisher does NOT state, recorded so no later slice invents
them:

- **no dollar year for either debt column.** The earnings cell names its
  adjustment; the debt cells name none. We say nothing about debt in today's
  dollars.
- **no interest rate tied to this release.** The 10-year payment's method is
  described only in `FieldOfStudyDataDocumentation.pdf`, whose own version line
  reads "Version: June 2024" — two years older than our snapshot. So copy may
  say "the publisher's own estimate of the monthly payment on a standard 10-year
  plan"; it may not name a rate.
- **the two debt columns and the earnings column are different cohorts and
  different publishers** — 2018-2019 against 2017-2018, NSLDS against Treasury.
  That is D27 confirmed from the source rather than from a brief, and it is why
  the two are never averaged, combined or ratio'd.

## Approved

Ian approved the table on 2026-09-16, verbatim: _"Remove publisher_flag because
it's not needed. Other than that, I approve the table"_. That column is removed
above. Two earlier amendments in the same gate, both applied: `value NUMERIC`
became `amount_usd INTEGER` with cents discarded ("ignore the cents. they are
not material"), and the publisher's cohort sentence became two typed
`academic_year` columns with the prose moved once per variable.

The third absence counter is keyed by SOURCE COLUMN. That was the stated default
of an unanswered gate question, so it is recorded as **taken by default, not
decided** — the alternative was keying it by column and by the exact
unrecognised token.

## Detailed Design

### Types

- `db/src/main/kotlin/ed/unicoach/db/models/ProgramMoneyMeasure.kt` — the one
  Kotlin enum for the three measures, `value` + `fromValue`, pinned to the SQL
  CHECK list by set equality in both directions (the house rule for an own
  enumeration: TEXT + CHECK in the schema, exactly one enum in Kotlin).
- `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyRows.kt` —
  `NewProgramMoneyStat`, carrying a `FigureReading<Int>` so an amount and a
  status cannot be mispaired in Kotlin before the CHECK ever sees them.
- `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyDao.kt` —
  `deleteFactsOfSource`, `batchInsert`, `countsByStatus`. Stateless object, raw
  JDBC over `SqlSession`, `Result<T>` — the `CanonicalMoneyDao` shape, no ORM.
- `college/src/main/kotlin/ed/unicoach/college/ScorecardFieldOfStudyColumns.kt`
  — the per-variable registry: publisher, cohort years, the publisher's cell
  verbatim, the inflation base year where published, and the measure.
- `college/src/main/kotlin/ed/unicoach/college/ProgramMoneyLoader.kt` — the
  phase.

### Reading a cell

```
raw = stringOrNull(record, column)      // blank -> null -> NotReported
raw in SUPPRESSION_SENTINELS            // "PS" / "PrivacySuppressed"
                                        //   -> SuppressedByPublisher, counted
parse(raw) == null                      // "NA"/"NULL" -> NotReported, quiet
                                        //   anything else -> NotReported,
                                        //   counted AND logged
                                        //   non-finite parses (NaN, Infinity)
                                        //   are rejected here too, not fatal
value !in 0..Int.MAX_VALUE              // the loader-side twin of the
                                        //   nonnegative CHECK and of the
                                        //   column's INTEGER width
                                        //   -> NotReported, counted by column
else                                    // -> Reported(value)
```

`StatusfulCell` maps to `(amount_usd, status)` through the one shared
`StatusfulCell.reading()` mapping beside `StatusfulCell`, which is the only
place that mapping is written; `ProgramMoneyLoader` then applies its own
whole-dollars conversion to the `Reported` arm.

### Column list

`REQUIRED_FIELDS_COLUMNS` gains the three variable names. That list is asserted
by `CollegeScorecardLoaderTest`'s "the REQUIRED column lists cover every column
the mappers read", and `assertRequiredColumns` is FATAL, so the two committed
minimal fixtures (`scorecard-fields-fixture.csv`,
`scorecard-fields-empty-fixture.csv`) gain the three headers in the same commit
or roughly fourteen suites fail at once. The 178-column
`scorecard-fields-real-fixture.csv` needs no header change and is where a real
`PS` and a real `NA` are fixtured.

## Files Modified

| File                                                                                      | Change                                                                                                                            |
| ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/create-program-money-stats.sql`                                                | NEW — the table above                                                                                                             |
| `db/schema/ORDER`                                                                         | one appended line                                                                                                                 |
| `db/src/main/kotlin/ed/unicoach/db/models/ProgramMoneyMeasure.kt`                         | NEW                                                                                                                               |
| `db/src/main/kotlin/ed/unicoach/db/models/FactTable.kt`                                   | one member: the vocabulary-retirement walk must know this table                                                                   |
| `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyRows.kt`                               | NEW                                                                                                                               |
| `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyDao.kt`                                | NEW                                                                                                                               |
| `db/src/main/kotlin/ed/unicoach/db/dao/CodedColumnCounts.kt`                              | NEW — the shared `countsBy` primitive, lifted out of `CanonicalMoneyDao`                                                          |
| `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyDao.kt`                              | `countsBy`/`countsByStatus` moved out; callers now use the shared one                                                             |
| `college/.../ScorecardFieldOfStudyColumns.kt`                                             | NEW — per-column vintage registry                                                                                                 |
| `college/.../ProgramMoneyLoader.kt`                                                       | NEW — the ingest phase                                                                                                            |
| `college/.../CollegeScorecardLoader.kt`                                                   | `REQUIRED_FIELDS_COLUMNS` + the new phase + summary line                                                                          |
| `college/.../CsvIngestSupport.kt`                                                         | third counter on `CellAbsenceTally`; `mapToNotReported` records it; shared `StatusfulCell.reading`; non-finite double guard; KDoc |
| `college/.../CanonicalMoneyLoader.kt`                                                     | third counter into `FillResult` and `logFillTally`; drop its private `reading()`                                                  |
| `college/.../IngestApplication.kt`                                                        | print the third counter                                                                                                           |
| `college/src/test/resources/scorecard-fields-fixture.csv`                                 | three headers                                                                                                                     |
| `college/src/test/resources/scorecard-fields-empty-fixture.csv`                           | three headers                                                                                                                     |
| `college/src/test/resources/scorecard-fields-real-fixture.csv`                            | unchanged headers; assert the publisher's real `PS` reads as suppressed                                                           |
| `db/seed/scorecard/FETCH-NOTES.md`                                                        | the field-of-study citation section                                                                                               |
| `db/src/testFixtures/kotlin/ed/unicoach/db/dao/LogWarnCapture.kt`                         | NEW — the shared root-logger WARN capture, lifted from three private copies                                                       |
| `db/build.gradle.kts`, `college/build.gradle.kts`                                         | test-fixture dependency for the shared WARN capture                                                                               |
| `service/.../LlmCallLogTest.kt`, `service/.../admissions/CollegeAdmissionsServiceTest.kt` | use the shared WARN capture                                                                                                       |
| tests                                                                                     | below                                                                                                                             |

No change to any tool, route, prompt, page or payload. No `bin/` script changes:
`bin/ingest-colleges` already passes the field-of-study file.

## Implementation Plan

1. Migration + `ORDER` line; `bin/db-reset` locally to prove it applies.
2. `ProgramMoneyMeasure`, `ProgramMoneyRows`, `ProgramMoneyDao` + DAO tests.
3. `CellAbsenceTally`'s third counter, its wiring and its print sites.
4. `ScorecardFieldOfStudyColumns` + its pin test + `FETCH-NOTES.md`.
5. `ProgramMoneyLoader` + the phase + the summary line; fixtures.
6. Full suite.

## Tests

- **DAO** (`ProgramMoneyDaoTest`, on `CanonicalMoneyDaoTest`'s shape): insert,
  batch insert, rebuild-by-delete, and each constraint asserted BY NAME — an
  amount with a non-value-bearing status refused, a negative amount refused, a
  cohort that ends before it starts refused, a duplicate natural key refused, an
  unknown measure refused, a program-less row refused.
- **Enum/CHECK pin**: `ProgramMoneyMeasure` equals the SQL CHECK list, both
  ways, read with `pg_get_constraintdef` — and the new
  `program_money_stats_amount_iff_status_check` is ADDED to the pinned list in
  `MoneyVocabularyLoaderTest`, which otherwise silently would not cover it.
- **Cents**: the 10-year payment rounds half up to whole dollars — `123.49`
  stores `123`, `123.50` stores `124` — asserted by name.
- **Three arms** (`ProgramMoneyLoaderTest`): a numeric cell stores `reported`
  with the amount; `PS` stores `suppressed_by_publisher` with NULL; `NA` stores
  `not_reported_by_institution` with NULL; the three are distinguishable in one
  query.
- **The third counter**: a fixture row with a token that is neither blank, nor a
  null sentinel, nor a suppression sentinel, nor a number increments
  `unrecognisedTokens` for that column AND still warns. There is no test of that
  arm today at all, so this adds the first.
- **Vintage**: every stored row carries two cohort years and a `source_variable`
  in the registry; the years equal the ones pinned for that variable;
  `last >= first` is refused by name at the database; the pinned release equals
  `PROVENANCE.json`'s; and the earnings variable carries an inflation base year
  while the two debt variables carry none.
- **No ratio**: a source sweep asserting that no production Kotlin divides an
  earnings figure by a debt figure — counted by the attribute, not by one call
  spelling, so it cannot be bypassed by renaming a helper.
- **Idempotency**: two ingests of the same fixture leave identical rows
  (`CollegeScorecardRealDataTest`'s existing shape).
- **Summary**: `CollegeScorecardIngestTest` asserts the new human-summary line,
  with the omit-vs-zero rule the other optional blocks follow.

## Rollback

Full. Revert the code commit and drop the table; nothing reads it yet, and no
family-visible sentence exists to lose. The `ORDER` line and the migration are
removed together.

## Open question for /chart

The spec's C8 calls the third silence arm "blank". The file has no blank cells
in these columns; it has 4,789 `NA` tokens on the two debt columns, which the
seam already treats as school silence. The arm survives, its count is right, and
`beyond/05`'s sentence must say "no figure for this program" rather than
anything about an empty cell.
