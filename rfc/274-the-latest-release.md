# RFC 274 — The latest release

Slice: `cost/01/the-latest-release` (brief 0017, gate 2 approved 2026-11-05).
Lane A, spec 4.

## Executive Summary

Every College Scorecard figure unicoach serves carries an academic year or an
entering cohort, and today every one of those periods is typed into Kotlin:
`CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR` and `BLENDED_AVERAGE_VINTAGE` date
the money; seven private values in `ScorecardOutcomeCitations` date the search
figures; `ScoreBandCitation.COHORT_TERM` dates the score bands; three
`FieldOfStudyVariable` constants date the field-of-study money. Each is pinned
by a test against a hand transcription (`dictionary-variable-sources.csv`,
RFC 183) or against the release stamp in `PROVENANCE.json` (RFC 195, RFC 218),
so a newer release fails a test instead of silently re-dating a price — and then
costs a human a re-read of the publisher's workbook and four edits. That is a
fragile design, not a missing check: a fact about stored data lives in code,
where the publisher can change its meaning without touching it.

The publisher dates every variable itself, in the `Most_Recent_Inst_Cohort_Map`
and `FieldOfStudy_Cohort_Map` sheets of `CollegeScorecardDataDictionary.xlsx`.
This RFC makes that workbook the source of the period on every Scorecard figure:
`bin/fetch-scorecard` fetches the workbook with the data, writes the three
sheets it reads as CSV beside the data, and records both in `PROVENANCE.json`.
The ingest reads those CSVs through one reader, `ScorecardDictionary`, and
stamps every money and field-of-study row with the year the publisher states for
it. Those rows already carry their year (`price_figures.academic_year`,
`cohort_money_stats.vintage`, `program_money_stats.cohort_*_year`), so the year
a family hears is the year stored beside the dollar figure.
`program_money_stats` gains one column, `amount_usd_year`, the calendar year of
the dollars in `amount_usd`, so "in 2024 dollars" is read off the row as well.
The money and field-of-study constants go: `ScorecardFieldOfStudyColumns` keeps
only what is true of a variable on every row. A dictionary that drops, renames
or back-dates a served variable fails the ingest naming it, and so does one
whose field-of-study cell states a dollar year where the measure carries none,
or none where it does.

The search figures and score bands are `colleges` columns with no year beside
them, so there is no row to stamp. Giving them one is a data-model change —
moving the fourteen figures into a `college_figures` table whose every row
carries its academic year — and Ian split it into its own slice on 2026-11-05.
Until it lands, their period values stay in Kotlin, and the tests that pin them
read the fetched dictionary through the same reader instead of the hand
transcription. A release that moves a cohort therefore fails the suite naming
the variable and the publisher's cell, and the land updates the value under
test. The slice's criterion "no year literal for a Scorecard figure lives in
`college/` or `service/` Kotlin" is met for money and field of study here and
for the rest by that slice.

The land is also the first move: the fetch on the day this lands takes the
publisher's current release (institution file `11022026`, field-of-study file
`10152026`, workbook "Released October 15, 2026"), and the served tuition year
moves from 2024-25 to 2025-26 with no Kotlin year edited. Brief 0017 D1 read
under D15: the newest release, every time.

## What the publisher ships, measured 2026-11-05

- `/data/` names `Most-Recent-Cohorts-Institution_11022026.zip` and
  `Most-Recent-Cohorts-Field-of-Study_10152026.zip`: **two stamps**. The
  changelog says November 2 is "a more recent version of the IPEDS Winter
  2025-26 data" for the institution file; the field-of-study file is unchanged
  since October 15. `bin/fetch-scorecard` refuses two stamps today
  (`require_one_release`), so the fetcher cannot take the current release at all
  until this RFC.
- `/data/data-documentation/` links `/files/CollegeScorecardDataDictionary.xlsx`
  (769,715 bytes, sha256 `8d258487f252…5cc4c`, `Last-Modified` 2026-11-02). Its
  `README` sheet's second row is "Released October 15, 2026". The URL is
  unversioned: the publisher overwrites it.
- `Most_Recent_Inst_Cohort_Map` (3,379 variables; header `variable`,
  `Cohort/Measure in "Most Recent" Datafile`,
  `Merged file Drawn From
  (MergedXXXX_YY_PP.csv)`) dates, verbatim:
  `TUITIONFEE_IN` "AcadYr 2025-26, reported in IPEDS DCY2025-26"; `COSTT4_A`,
  `NPT4_*`, `PCTPELL` "AcadYr 2024-25, reported in IPEDS DCY2025-26";
  `ADM_RATE`, `UGDS`, `SAT_AVG`, `SATVR25` "Fall 2024, reported in IPEDS
  DCY2024-25"; `C150_4`, `TRANS_4` "Fall 2019 or AcadYr 2019-20 cohort (differs
  by institutional calendar), reported in IPEDS DCY2025-26"; `OMAWDP8_*` "AY
  2017-18 cohort, reported in IPEDS DCY2025-26"; `GRAD_DEBT_MDN` "NSLDS FY2024,
  FY2025 pooled cohorts"; `MD_EARN_WNE_P10` "Treasury AY2011-12, AY2012-13
  pooled cohort measured in CY2022, CY2023, inflation adjusted to 2024 dollars".
- `FieldOfStudy_Cohort_Map` (1,797 variables; one column per datafile, the last
  `FieldOfStudyMostRecent datafile`) dates `EARN_MDN_4YR` "Treasury AY2017-2018,
  AY2018-2019 pooled earnings cohort measured in CY2022, CY2023, inflation
  adjusted to 2024 dollars" and both `DEBT_ALL_STGP_EVAL_MDN*` "NSLDS pooled
  AY2022-23, AY2023-24 cohort" — the Kotlin constants say AY2018-19, AY2019-20.
- `Institution_Data_Dictionary` (3,746 rows; `VARIABLE NAME`, `SOURCE`, …; a row
  per enumeration value with `VARIABLE NAME` empty) is where
  `AssuranceTier.SCORECARD_TIERS` reads its `SOURCE` evidence (RFC 179 D4).

So four homes move with this release, not two, and three of the four would move
to the wrong value by hand. The mechanism below covers all four because the
fault class is one.

## Decisions

**D1. The workbook is read at FETCH time, in Python, with the standard library.
The JVM keeps reading CSV.** `.xlsx` is a zip of XML; `bin/fetch-scorecard`
reads `xl/workbook.xml`, `xl/_rels/workbook.xml.rels`, `xl/sharedStrings.xml`
and one `xl/worksheets/sheetN.xml` per sheet it needs with `zipfile` and
`xml.etree` (~70 lines, proven on the current file), and writes each sheet it
reads as a CSV, cells verbatim. RFC 173's "no xlsx reader" is reversed in letter
— the fetcher does read the workbook — and kept in substance: no reader joins
the JVM classpath, nothing parses a spreadsheet at test or run time, and the
committed artefact is a CSV a reviewer can diff. The alternative (an xlsx
library on the JVM, read at ingest) puts 770 KB of binary parsing on the ingest
path for three columns and makes the re-dating visible only in a served answer;
the slice asks that it show in the diff of a fetched artefact.

**D2. A year lives on the row it dates, stamped by the load that wrote the
row.** Money and field-of-study rows have year columns; the ingest fills them
from the fetched dictionary instead of a constant. The search figures and score
bands have no row of their own to carry a year. A side table of periods keyed by
figure name was considered at the approval stop and rejected by Ian: it links
data to its year only by a name Kotlin and SQL must both spell the same, so
either side can change and break the other with no error. The fix is rows that
carry their own year (`college_figures`, one table, five typed value columns
with exactly one set per row), which is its own slice. In this RFC those two
homes keep their constants, pinned against the fetched dictionary (§4, §5).

**D3. Two release stamps are recorded, not refused.** `PROVENANCE.json` records
each artifact's own `release` (the stamp in its URL) and drops the top-level
`release` that claimed one stamp for the pair. Disagreeing stamps are logged as
a warning naming both; the download proceeds. The refusal's reason — a pair
"cross-read by UNITID" — is weaker than it was written: the field-of-study phase
already counts and skips a row whose `UNITID` names no college, and the
publisher's own "most recent" pair is this pair. Refusing it means never
fetching again until the publisher happens to re-stamp both. The two Kotlin
release pins that compared a constant to the top-level stamp
(`ScoreBandCitation.COHORT_SNAPSHOT_RELEASE`,
`ScorecardFieldOfStudyColumns.COHORT_SNAPSHOT_RELEASE`) are deleted with their
tests: the dictionary is the pin now.

**D4. The workbook URL is discovered, never constructed.** The fetcher reads
`/data/data-documentation/` and takes the one href ending in
`CollegeScorecardDataDictionary.xlsx`; zero or several is a refusal, like the
data page. It is recorded as a third artifact in `PROVENANCE.json`: `url`,
`fetched_at`, `bytes`, `sha256`, `released` (the `README` sheet's "Released …"
cell, verbatim), and per written sheet its CSV name, data-row count and sha256.

**D5. The workbook itself is committed beside the CSVs.** Its URL is unversioned
and overwritten, so a reader of the committed CSVs could otherwise never check
the extraction against its source. 770 KB per release, a few times a year. The
two data CSVs stay gitignored (100 MB and 153 MB).

**D6. Money and field of study move; outcome citations and score bands are
pinned.** The money and field-of-study years are read from the dictionary and
their constants deleted. The outcome-citation periods and the score-band term
stay in Kotlin until `college_figures` lands, and their pins move from the hand
transcription and the release stamp to the fetched dictionary, so the new
release forces them to the publisher's values on this land. `PCTPELL` stays
stamped undated (RFC 183 D3, unchanged). IPEDS `SURVEY_YEAR` is not a Scorecard
pin and is brief 0017's D15 backlog.

**D7. The guards.** The ingest refuses, naming the variable, before any write:
(a) a registered variable with no row in the fetched dictionary (dropped or
renamed); (b) a cell the reader cannot read; (c) a money column the dictionary
dates EARLIER than the newest year already stored for it in `price_figures` /
`cohort_money_stats` (a back-date); (d) the same for a field-of-study cohort
against `program_money_stats`; (e) a field-of-study cell that states the
calendar year of its dollars for a measure that carries none, or states none for
one that does (`UnexpectedCohortShapeException`, naming the variable and the
cell, listed by `DictionaryRefusesServedVariablesException`). A re-dated outcome
cohort or score-band term fails the suite at its pin (§4, §5), naming the
variable and the cell. The fetcher reports, per sheet, how many cells changed
against the file it overwrites and names the changed variables, bounded.

**D8. The reader's grammar lives once, in main.** `ScorecardDictionary` reads
the LEADING clause of a cell — before `, reported in` — and recognises:
`AcadYr YYYY-YY` (a collection academic year), `AY YYYY-YY cohort` (an entering
academic year), `Fall YYYY[ …]` (an entering term, as today's `FALL_TERM`),
`<publisher> pooled AYyyyy-yy, AYyyyy-yy cohort` and
`Treasury AYyyyy-yyyy,
AYyyyy-yyyy pooled earnings cohort measured in CYyyyy, CYyyyy, inflation
adjusted to yyyy dollars`
(pooled academic years, with an optional dollar base). Parsing is per variable,
on demand: a column the fill stamps undated is never asked, so the pooled
administrative cells need no shape until something serves their year. Anything
asked for and unrecognised is `UnreadableCohortCellException` with the variable,
the cell and a cause — the type that exists today, moved.

**D9. A field-of-study figure's meaning comes from its table.** A
`program_money_stats` row carries the three periods a family is told: the two
academic years its cohort pools and the calendar year of its dollars. The
serving path reads them from the row and nowhere else.
`ScorecardFieldOfStudyColumns` keeps the facts true of a variable on every row:
measure, publisher, assurance tier.

## Detailed Design

### 1. `bin/fetch-scorecard`

**Workbook discovery.** A second page read,
`DOCUMENTATION_PAGE_URL =
https://collegescorecard.ed.gov/data/data-documentation/`,
matched by
`WORKBOOK_HREF_PATTERN = r'href="(/files/CollegeScorecardDataDictionary\.xlsx)"'`
and resolved against the page origin — the href the page served, like the data
pattern. `discover_workbook_url` refuses zero or several hrefs. The offline
route reads `scorecard-documentation-page-fixture.html` beside the data-page
fixture.

**Reading the workbook.**
`read_workbook(body, origin) -> {sheet_name: [[cell]]}` for exactly
`WORKBOOK_SHEETS`: `Institution_Data_Dictionary`, `Most_Recent_Inst_Cohort_Map`,
`FieldOfStudy_Cohort_Map`, `README`. Shared strings and inline strings both
resolved; a numeric cell is its text; an absent sheet is a refusal naming it and
listing the sheets present; a `workbook.xml` with no `<sheets>` element is a
refusal naming that. Rows are padded to the header's width; trailing empty
columns beyond the header are dropped.

**The written sheets.** Three CSVs, cells verbatim, UTF-8, `csv.writer` with
`QUOTE_MINIMAL`:

| File                                    | Sheet                         | Columns (as the sheet's header, verbatim)                                                            |
| --------------------------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------- |
| `dictionary-institution-cohorts.csv`    | `Most_Recent_Inst_Cohort_Map` | every header column; `variable` and `Cohort/Measure in "Most Recent" Datafile` required              |
| `dictionary-institution-sources.csv`    | `Institution_Data_Dictionary` | `VARIABLE NAME`, `SOURCE`, `NAME OF DATA ELEMENT`, `NOTES` — rows whose `VARIABLE NAME` is non-empty |
| `dictionary-field-of-study-cohorts.csv` | `FieldOfStudy_Cohort_Map`     | `Variable Name`, `FieldOfStudyMostRecent datafile`                                                   |

Column selection is by header NAME; a header that lacks a named column is a
refusal naming the sheet and the column. A header carrying a required column
twice, and a sheet with a header and no rows, are refusals naming the sheet. The
sources sheet drops the enumeration-value rows (empty `VARIABLE NAME`) because
they describe values, not variables; nothing else is filtered. The workbook is
also written as `CollegeScorecardDataDictionary.xlsx` (D5).

**`PROVENANCE.json`** (D3, D4):

```json
{
  "fetched_at": "...", "source": "network", "fixture_derived": false,
  "artifacts": {
    "Most-Recent-Cohorts-Institution":    { "url", "release": "11022026", "fetched_at", "bytes", "sha256", "member", "member_bytes", "member_sha256", "rows", "ingest_position": 1 },
    "Most-Recent-Cohorts-Field-of-Study": { "...", "release": "10152026", "ingest_position": 2 },
    "CollegeScorecardDataDictionary":     { "url", "fetched_at", "bytes", "sha256", "released": "Released October 15, 2026",
                                            "sheets": { "dictionary-institution-cohorts.csv": { "sheet", "rows", "sha256" }, "...": {} } }
  }
}
```

`release` is null per artifact on an offline replay, as `fetched_at` is.
`report_release_change` compares per artifact against the previous manifest and
logs each move. `require_one_release` becomes `report_release_stamps`: a warning
when the stamps differ, naming both. The `README` "Released" cell is found by
prefix `Released` in column A; absent, `released` is null and a warning says so
(the row is prose, not a contract).

**The cell-change report.** For each written CSV with a predecessor in `outdir`,
the count of variables whose cell changed, the count added and removed, and up
to `CHANGED_VARIABLES_PREVIEW = 20` names of each, on stderr. Information only:
a new release is the point.

**Write order.** The two members, the three CSVs, the workbook, then the
manifest last — `write_atomically` unchanged.

### 2. `ScorecardDictionary` (`:college` main)

```kotlin
class ScorecardDictionary private constructor(
  private val institutionCells: Map<String, String>,   // variable -> cohort cell, verbatim
  private val institutionSources: Map<String, String>, // variable -> SOURCE
  private val fieldOfStudyCells: Map<String, String>,  // variable -> most-recent cohort cell
  val digests: List<SourceDigest>,                     // the three CSVs, for the ingest's provenance row
) {
  fun institutionCellOf(variable: String): String      // throws VariableNotInDictionaryException
  fun sourceOf(variable: String): String               // same
  fun fieldOfStudyCellOf(variable: String): String     // same
  fun institutionCohortOf(variable: String): Cohort    // parsed on demand; throws UnreadableCohortCellException
  fun academicYearOf(variable: String): AcademicYear   // Cohort.StatedAcademicYear or throws UnexpectedCohortShapeException
  fun enteringPeriodOf(variable: String): Cohort       // StatedTerm | StatedEnteringAcademicYear, else throws
  fun fieldOfStudyCohortOf(variable: String): Cohort.PooledAcademicYears
  fun readInstitutionCohort(variable: String): Dating<Cohort>                       // the same questions, answered not thrown
  fun readAcademicYear(variable: String): Dating<AcademicYear>
  fun readFieldOfStudyCohort(variable: String): Dating<Cohort.PooledAcademicYears>
  sealed interface Dating<out T> {
    data class Dated<T>(val value: T) : Dating<T>
    data class Undatable(val fault: Refusal) : Dating<Nothing>
  }
  companion object {
    suspend fun fromFiles(
      sources: ScorecardDictionarySources,
      ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ScorecardDictionary
  }
}
```

The publisher's cell grammar is `ScorecardCohortGrammar` (`:college` main), pure
and needing no file. `ScorecardYearGuard` collects every offender over the
`read…` forms without catching and throws
`DictionaryRefusesServedVariablesException` once; the `…Of` forms throw the
refusal, for a caller that wants one answer.

`Cohort` is today's sealed interface (`StatedAcademicYear`, `StatedTerm`,
`StatedEnteringAcademicYear`, `NoAcademicYear`) plus
`PooledAcademicYears(first: AcademicYear, last: AcademicYear, amountUsdYear:
CalendarYear?, cell: String)`.
`NoAcademicYear` is now the EMPTY cell only; a pooled administrative cell is
read as `PooledAcademicYears` when asked. The leading clause is
`cell.substringBefore(", reported in")`. A variable stated twice in a CSV is
refused at construction (today's rule). Every exception about a variable carries
it and its cell as properties; `UnreadableCohortCellException` carries the typed
`Unreadable(cause, clause, spellings, rejection)` and chains the year type's
rejection as its cause, and `UnexpectedCohortShapeException.expected` is the
closed `ExpectedPeriod`. A pooled label's first half is exactly the year it
parses to, so `+2022-23` and `02022-23` are refused. A CSV lacking the column
the reader selects, or a row naming no variable, is
`MalformedDictionaryFileException(file, variable: String?, detail)`, its
`variable` null where the fault names none.

`ScorecardDictionarySources(institutionCohorts: SourceFile, institutionSources:
SourceFile, fieldOfStudyCohorts: SourceFile)`
is the ingest's input group.

**The ingest CLI.** Three REQUIRED options on `bin/ingest-colleges`, one per
CSV: `-D <dictionary-institution-cohorts.csv>`,
`-O <dictionary-institution-sources.csv>` and
`-F <dictionary-field-of-study-cohorts.csv>`. Each goes through
`resolve_file_arg` like the positionals (a local path or an `s3://` URI) and is
forwarded to the JVM as its own flag -- `--dictionary-cohorts=`,
`--dictionary-sources=`, `--dictionary-fields=` -- with a `--…-source=` partner
carrying the caller's original argument; `IngestApplication.parseArgv` refuses
each one's absence by name. Three inputs and not one file with siblings read
from beside it, because the production ingest runs through `bin/remote`, which
uploads every `-f` file under its own prefix: files that must sit together never
arrive together on the host. `bin/load-external-data` defaults all three to
`db/seed/scorecard/` and checks each exists before the JVM starts; its help's
`bin/remote prod` example uploads the three with `-f` and passes them as `@n`.
Tests build the sources over the committed seed directory, found by walking up
from the working directory (the `MoneyVocabularyFixture` precedent), so no test
re-types a cell.

### 3. Money (`CanonicalMoneyLoader`, `ScorecardInstitutionColumns`)

`VintageGroup` becomes two members: `DATED` (the eight charges, `COSTT4_A`, the
net-price family) and `UNDATED` (`PCTPELL`, `GRAD_DEBT_MDN`, `MD_EARN_WNE_P10`).
The registry still says WHETHER a column is dated; the dictionary says WHEN.
`PUBLISHED_CHARGE_VARIABLES` and `BLENDED_AVERAGE_VARIABLES` are deleted;
readers that needed "the charges" use the concept, not a year group. The union
of the loaded and outcome variables is named `CITED_VARIABLES`, because `DATED`
now names the narrower `VintageGroup`.

```kotlin
fun stampedYearOf(column: String, dictionary: ScorecardDictionary): AcademicYear? =
  when (groupOf(column)) { DATED -> dictionary.academicYearOf(column); UNDATED -> VINTAGE_UNDATED }
fun datedYearOf(column: String, dictionary: ScorecardDictionary): AcademicYear
```

`CanonicalMoneyLoader.fill(institutionCsv, sfa, dictionary)` — the dictionary is
an argument of the fill, like the staged charges, because it is an input the
fill reads. `fill()` first runs `ScorecardYearGuard`:

1. every `LOADED_VARIABLES` member has a dictionary row and every `DATED` one
   reads as an academic year — a failure is
   `DictionaryRefusesServedVariablesException`, whose `faults` list every
   offender (`VariableNotInDictionaryException` /
   `UnreadableCohortCellException` / `UnexpectedCohortShapeException`), before
   any read of the CSV;
2. for each `DATED` column, the newest `academic_year` (price_figures) or
   `vintage` (cohort_money_stats) stored with `source = 'scorecard'` and that
   `source_variable`, read in the fill's own transaction before the DELETE; a
   dictionary year earlier than it is
   `DictionaryBackdatesServedVariableException(backdates: List<Backdate>)`,
   listing every offender, each `Backdate(variable, stored, dictionary)` holding
   the stored and dictionary periods as a typed `Backdate.Period` (`Year` or
   `Span`), rendered as labels in the message only. An empty table is no
   evidence and passes.

`PUBLISHED_PRICE_YEAR`, `BLENDED_AVERAGE_VINTAGE` are deleted. `VINTAGE_UNDATED`
stays. The in-state collapse rule (RFC 183 D1) and precedence are untouched;
with charges at 2025-26 and IC_AY staged through 2023-24 the two publishers are
two years apart, and an arrangement the Scorecard prices incompletely still
falls back to the newest complete IPEDS year through `publishedPriceYearOf` —
asserted by test, not assumed. Comments at `CollegeCostService.kt:523`,
`CollegeCostChatTool.kt`, `ComparisonBasis.kt`, `CostField.kt`,
`CanonicalCohortFixture.kt` that name the constants are reworded.

### 4. Outcome citations (`ScorecardOutcomeCitations`)

The seven period values stay until `college_figures` lands. Their pin,
`ScorecardDictionaryPinTest`, is re-pointed: it builds `ScorecardDictionary`
over the committed seed directory and asserts, per citation, that the value's
period equals `dictionary.enteringPeriodOf(sourceVariable)` — kind and year. The
eight-year trio must read as an entering academic year and the other four as a
term; a mismatch fails naming the variable, the Kotlin value and the publisher's
cell. The land updates whichever values the newest release moves. Readers
(`CollegeSearchTool`, `SimilarCollegesTool`, `CollegeMatchRow`) are unchanged.

### 5. Score bands (`ScoreBandCitation`)

`COHORT_TERM` stays until `college_figures` lands. `COHORT_SNAPSHOT_RELEASE` and
its test are deleted: they compared a constant with the top-level release stamp
that D3 removes. `ScoreBandCopyTest`'s pin is re-pointed to the dictionary: all
seven band variables (`SATVR25/75`, `SATMT25/75`, `ACTCM25/75`, `SAT_AVG`) must
read as one term, and `COHORT_TERM` must equal it.

### 6. Field of study (`ScorecardFieldOfStudyColumns`, `ProgramMoneyLoader`, `ProgramReturn`)

**Migration.** `db/schema/add-amount-usd-year-to-program-money-stats.sql`, last
in `db/schema/ORDER`. It empties the table first: `program_money_stats` is
derived data rebuilt wholesale by each ingest, and the production re-ingest that
follows the land refills it.

```sql
DELETE FROM program_money_stats;

ALTER TABLE program_money_stats
    ADD COLUMN amount_usd_year SMALLINT NULL,
    ADD CONSTRAINT program_money_stats_amount_usd_year_earnings_check
        CHECK ((amount_usd_year IS NOT NULL) = (measure = 'median_earnings_4yr_after_entry'));
```

The migration also comments `amount_usd_year` (the calendar year of the dollars
in `amount_usd`, as the publisher states it; empty where it states none) and
re-issues the comments on `amount_usd` and `source_variable`, so neither points
a reader at a dollar base anywhere but this row.

**Registry.** `FieldOfStudyVariable` is `sourceVariable`, `measure`,
`publishedBy` and `assuranceTier`. `COHORT_SNAPSHOT_RELEASE` is deleted.

**Measure.** `ProgramMoneyDollars` (`:db` models) closes a measure and the
calendar year of its dollars into one value, one arm per measure, so a debt
measure with a year or earnings with none does not compile:

```kotlin
sealed interface ProgramMoneyDollars {
  val measure: ProgramMoneyMeasure
  val amountUsdYear: CalendarYear?
  data class MedianEarnings4YrAfterEntry(override val amountUsdYear: CalendarYear) : ProgramMoneyDollars
  data object MedianDebtAtCompletion : ProgramMoneyDollars
  data object MedianDebt10YrMonthlyPayment : ProgramMoneyDollars
  companion object {
    fun ofOrNull(measure: ProgramMoneyMeasure, amountUsdYear: CalendarYear?): ProgramMoneyDollars?
  }
}
```

`ofOrNull` is the door for the two boundaries that hold the pair apart, the
dictionary cell and the stored row. `ProgramMoneyMeasure.statesAmountUsdYear` is
read off it, and the tool description lists the measures from there. A stored
row no arm accepts is a located `CorruptPersistedValueException` saying which it
was (earnings with no year, a debt measure with a year), and a stored year
outside `CalendarYear`'s range is the same located refusal.

**Ingest.** `ScorecardYearGuard.fieldOfStudyCohorts` reads each variable's
cohort with `dictionary.readFieldOfStudyCohort`, builds its
`ProgramMoneyDollars` and refuses one whose stated dollar year its measure
cannot carry (D7e). The refusal lands in `requireDictionaryServes`, before the
ingest's first phase commits, and again in the fill's own transaction before its
DELETE, so the database CHECK is never what says so.
`ProgramMoneyLoader.fill(fieldsCsv, dictionary)` stamps `cohort_first_year`,
`cohort_last_year` and `dollars` from what the guard returned, and the back-date
guard compares to the newest stored pair per `source_variable`.

**Serving.** `NewProgramMoneyStat` and `ProgramReturnStat` carry
`dollars: ProgramMoneyDollars` in place of a measure and a year side by side,
and `ProgramMoneyDao` writes and reads it; a stored pair the CHECK forbids is
the read's corrupt-row refusal.

```kotlin
@ConsistentCopyVisibility
data class ProgramFigureProvenance private constructor(
  val variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable,
  val cohortFirstYear: AcademicYear,
  val cohortLastYear: AcademicYear,
  val dollars: ProgramMoneyDollars,
) {
  val amountUsdYear: CalendarYear? get() = dollars.amountUsdYear
  val publisher: String get() = variable.publisher
  val assuranceTier: AssuranceTier get() = variable.assuranceTier
  companion object {
    fun ofOrNull(variable, cohortFirstYear, cohortLastYear, dollars): ProgramFigureProvenance? // null where dollars names a measure variable does not
  }
}
```

`provenanceOfOrNull` builds it from the row once `source` and `source_variable`
are attributed; a measure the variable does not publish is its no-figure arm.
The family report's words are unchanged: "in 2024 dollars" is spoken from
`provenance.amountUsdYear`.

**The coach tool's fields change; the coach prompt does not.** In
`college_cost_profile`'s `program_return` figures, `cohort_sentence` is removed
and `inflation_base_year` is renamed `amount_usd_year`, carried on the earnings
measure only; the tool description says both, listing the measures from
`ProgramMoneyMeasure`.

### 7. `AssuranceTier` evidence and the ingest's provenance row

`AssuranceTierClosureTest` reads `SOURCE` through `dictionary.sourceOf`; the
sha256 pin against `FETCH-NOTES.md` is replaced by equality with the digest
`PROVENANCE.json` records for each written sheet. The ingest's `provenance`
phase appends `dictionary.digests` to `sources`, so
`college_index_build.sources` names the dictionary the run stamped years from.

### 8. The seed directory

`db/seed/scorecard/`: `PROVENANCE.json`, `FETCH-NOTES.md`,
`CollegeScorecardDataDictionary.xlsx`, `dictionary-institution-cohorts.csv`,
`dictionary-institution-sources.csv`, `dictionary-field-of-study-cohorts.csv`
committed; the two data CSVs gitignored. `dictionary-variable-sources.csv` is
deleted. `FETCH-NOTES.md`'s transcription section is replaced by one that says
the three CSVs are fetched, which sheet each is, and that the digests live in
`PROVENANCE.json`. The `.gitignore` stanza names the new exemptions.

### Error handling, edge cases

- A workbook with a renamed sheet or column: fetch refusal naming it; nothing
  written.
- A sheet whose header carries a required column twice, or that holds a header
  and no rows: fetch refusal naming the sheet; nothing written.
- A dictionary dropping a served variable: ingest refusal before any write; the
  previous fill stands.
- A dictionary back-dating a money or field-of-study column: ingest refusal
  naming variable, stored year, dictionary year.
- A field-of-study cell whose dollar year disagrees with its measure: ingest
  refusal naming the variable and the cell, before any write.
- A pooled or unrecognised cell on a DATED column: a fault of
  `DictionaryRefusesServedVariablesException` at the guard
  (`UnexpectedCohortShapeException` / `UnreadableCohortCellException`), naming
  cell and variable.
- A release that moves an outcome cohort or the score-band term: the suite fails
  at the pin, naming the variable, the Kotlin value and the cell.
- The two data stamps differ: warning, both recorded, fetch proceeds.
- The `README` lacks a "Released" row: `released: null`, a warning.
- Offline replay: as today, with the workbook fixture; per-artifact `release`
  and the workbook `url` null; `released` is the fixture's README line.
- An ingest run without `-D`, `-O` or `-F`: usage refusal naming the missing
  option, before the JVM starts.

### Dependencies

None new. Python standard library; commons-csv already in `:college`.

## Tests

Kotlin, `:college` unless noted. Years in tests are READ from the committed
artefacts through `ScorecardDictionary.fromFiles` over the seed directory, or
derived from them (`+1`); no test states a Scorecard year.

1. `ScorecardDictionaryTest`:
   - every member of `ScorecardInstitutionColumns.CITED_VARIABLES`, the seven
     outcome variables, the seven band variables and
     `ScorecardFieldOfStudyColumns.VARIABLES` has a cell;
   - every `DATED` money column reads as `StatedAcademicYear`; the eight charges
     agree on one year and `COSTT4_A` with the net-price family on one year one
     earlier (the publisher's shape, checked not assumed);
   - the three field-of-study variables read as `PooledAcademicYears`, last ≥
     first, earnings with a dollar base and debt without;
   - the committed CSVs' sha256 equal `PROVENANCE.json`'s recorded sheet
     digests;
   - the Scorecard charge year is later than `IpedsChargeVocabulary.SURVEY_YEAR`
     (RFC 183 D2's check, kept);
   - refusals: a missing variable names it; a duplicated variable is refused;
     each unreadable shape ported from today's test reader keeps its cause; a
     pooled cell asked for as an academic year is refused naming it.
2. `CanonicalMoneyDictionaryTest` (DB):
   - a dictionary whose eight charge cells are re-dated to the committed year
     plus one: after `fill`, every scorecard tuition row carries that year and
     the canonical read serves it; `ScorecardReleaseYearTest` (`:service`, DB)
     runs the same fill and asserts the year `CollegeCostService` states for a
     Scorecard-only college moves to it — the acceptance's test;
   - a dictionary missing `TUITIONFEE_IN`: `fill` throws naming it, and the
     previous fill's rows are untouched;
   - a dictionary dating `TUITIONFEE_IN` one year EARLIER than a stored row:
     throws naming variable, stored and dictionary years; untouched;
   - an institution row priced incompletely by the Scorecard (no housing) with a
     complete IC_AY arrangement: the IC_AY year is served for that arrangement.
3. `ProgramMoneyDictionaryTest` (DB): the field-of-study `+1` and back-date
   cases, as 2; the fill stamps `amount_usd_year` on earnings rows and leaves it
   empty on debt and payment rows; a later dollar year in the dictionary moves
   every earnings row; an earnings cell stating no dollar year, and a debt or
   payment cell stating one, are each refused naming the variable and the cell,
   the previous fill standing.
4. Re-pointed: `ScorecardDictionaryPinTest` (§4); `ScoreBandCopyTest`
   (`:service`, §5); `ScorecardResidencyCollapseTest`,
   `CanonicalMoneyIpedsTest`, `CanonicalMoneySfaTest`,
   `SearchCanonicalMoneyTest`, `CanonicalMoneyLoaderTest` (years from the seed
   dictionary); `IngestApplicationArgvTest` (each of the three `--dictionary-*`
   flags required and refused by name, each with its `-source` partner);
   `AssuranceTierClosureTest` (§7); `ScorecardFieldOfStudyPinTest` (the
   registry's own facts, against the seed dictionary); `ProgramMoneyLoaderTest`
   (cohort years from the seed dictionary); `CostsTestDb`,
   `ProgramReturnCopyTest` and `FakeCostReportSource` (`:service`,
   `:public-web`: rows and provenance from the seed dictionary).
5. Deleted: `ScorecardDictionaryTranscription` (test) and the two release-pin
   tests.
6. `ProgramMoneyDaoTest` (`:db`, DB): the CHECK refuses an earnings row with no
   `amount_usd_year` and a debt row with one, by constraint name; the
   major-answer read carries each row's own year.
7. `CollegeCostServiceTest` (`:service`, DB): a stored row is spoken with its
   own cohort years and "in X dollars", X taken from the row and differing from
   the dictionary's. `CollegeCostChatToolTest`: the wire carries
   `amount_usd_year` on earnings only and `cohort_sentence` on no figure, and
   the description names no `cohort_sentence`.
8. `bin/scripts-tests`, fetch-scorecard section: the fixture writer synthesises
   a workbook (both shared and inline strings, the four sheets, a "Released"
   row); the written listing is the seven files; `PROVENANCE` records the
   workbook artifact with `released`, its sha256 and each sheet's rows and
   digest; per-artifact `release`, no top-level one; two disagreeing stamps are
   WARNED and both recorded (the inverted `stamp-mismatch` case); a workbook
   missing a sheet, a sheet missing a column, and a documentation page with no
   workbook href are each refused naming the thing, writing nothing; the
   cell-change report names a changed variable on a second replay against an
   edited predecessor; the unversioned-URL sweep still passes;
   `load-external-data` names the missing dictionary CSVs before the JVM starts.

## Implementation Plan

1. **Fetcher.** D1, D3, D4, D5 in `bin/fetch-scorecard`; fixture writer and the
   fetch-scorecard section of `bin/scripts-tests`; the documentation-page
   fixture in `college/src/test/resources`. Verify:
   `nix develop -c bin/shell-tests -n scripts-tests`.
2. **The real fetch.** `nix develop -c bin/fetch-scorecard` into
   `db/seed/scorecard`; `.gitignore` exemptions; delete
   `dictionary-variable-sources.csv`; rewrite `FETCH-NOTES.md`'s dictionary
   section. Verify: `git status` shows the four new committed artefacts and the
   manifest; `jq .artifacts.CollegeScorecardDataDictionary.released`.
3. **Reader and ingest input.** `ScorecardDictionary` in `:college` main with
   the grammar (D8), moved from the test reader; the `-D` / `-O` / `-F` inputs
   through `bin/ingest-colleges`, `IngestApplication` and
   `bin/load-external-data`; `ScorecardDictionaryTest`. Verify:
   `nix develop -c bin/test college -t "*ScorecardDictionary*"`.
4. **Money.** §3; `CanonicalMoneyDictionaryTest`; re-point the money tests;
   delete the constants; reword comments. Verify: `nix develop -c bin/test`.
5. **Field of study.** §6: the migration, `ProgramMoneyMeasure`, the row and
   DAO, the guard and loader, the registry, `ProgramFigureProvenance`, the
   tool's two fields; `ProgramMoneyDictionaryTest`, `ProgramMoneyDaoTest`, the
   serving and wire tests; delete the release pin. Verify:
   `nix develop -c bin/test`.
6. **Pins.** §4, §5: re-point both pins to the dictionary and update whichever
   values the new release moves. Verify: `nix develop -c bin/test`.
7. **Provenance row.** §7. Verify: `nix develop -c bin/test`.
8. **Full suite, unscoped.** `nix develop -c bin/test`; report executed counts.

## Files Modified

Expected scope, non-exhaustive.

- `bin/fetch-scorecard`; `bin/scripts-tests` (fetch-scorecard section);
  `college/src/test/resources/scorecard-documentation-page-fixture.html`
- `db/seed/scorecard/{PROVENANCE.json, FETCH-NOTES.md,
  CollegeScorecardDataDictionary.xlsx, dictionary-institution-cohorts.csv,
  dictionary-institution-sources.csv, dictionary-field-of-study-cohorts.csv}`;
  `dictionary-variable-sources.csv` deleted; `.gitignore`
- `bin/ingest-colleges`, `bin/load-external-data`, `bin/fetch-ipeds` (the `-D` /
  `-O` / `-F` inputs)
- `db/schema/add-amount-usd-year-to-program-money-stats.sql` (new),
  `db/schema/ORDER`
- `db/src/main/kotlin/ed/unicoach/db/models/{ProgramMoneyMeasure.kt,
  ProgramMoneyDollars.kt (new)}`;
  `db/src/main/kotlin/ed/unicoach/db/dao/{ProgramMoneyRows.kt, ProgramMoneyDao.kt}`;
  `common/src/main/kotlin/ed/unicoach/common/util/CalendarYear.kt` (comment)
- `college/src/main/kotlin/ed/unicoach/college/{ScorecardDictionary.kt (new),
  ScorecardCohortGrammar.kt (new), ScorecardScoreBandColumns.kt (new),
  ScorecardYearGuard.kt (new), ScorecardInstitutionColumns.kt, CanonicalMoneyLoader.kt,
  ScorecardOutcomeCitations.kt, ScorecardFieldOfStudyColumns.kt,
  ProgramMoneyLoader.kt, CollegeScorecardLoader.kt (provenance row),
  IngestApplication.kt (`--dictionary`)}`
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/SchoolScoreBands.kt`;
  `service/src/main/kotlin/ed/unicoach/coaching/costs/{ProgramReturn.kt,
  ProgramReturnCopy.kt, CollegeCostService.kt, CollegeCostChatTool.kt,
  ComparisonBasis.kt}`
- `college/src/test/kotlin/ed/unicoach/college/{ScorecardDictionaryTest.kt (new),
  CanonicalMoneyDictionaryTest.kt (new), ProgramMoneyDictionaryTest.kt (new),
  ScorecardDictionaryPinTest.kt, ScorecardDictionaryTranscription.kt (deleted),
  AssuranceTierClosureTest.kt, ScorecardFieldOfStudyPinTest.kt,
  ProgramMoneyLoaderTest.kt, CanonicalMoney*Test.kt,
  ScorecardResidencyCollapseTest.kt, SearchCanonicalMoneyTest.kt,
  IngestApplicationArgvTest.kt}`;
  `service/src/test/kotlin/ed/unicoach/coaching/admissions/ScoreBandCopyTest.kt`;
  `service/src/test/kotlin/ed/unicoach/coaching/costs/{CostsTestDb.kt,
  CollegeCostServiceTest.kt, CollegeCostChatToolTest.kt, ProgramReturnCopyTest.kt,
  ScorecardReleaseYearTest.kt (new)}`;
  `db/src/test/kotlin/ed/unicoach/db/dao/ProgramMoneyDaoTest.kt`;
  `db/src/test/kotlin/ed/unicoach/db/models/ProgramMoneyDollarsTest.kt` (new);
  `public-web/src/test/kotlin/ed/unicoach/web/{FakeCostReportSource.kt,
  CostReportProgramReturnTest.kt}`;
  `db/src/testFixtures/kotlin/ed/unicoach/db/dao/CanonicalCohortFixture.kt`
  (comment)

The schema changes by the one migration above. The coach tool's fields change
(one removed, one renamed) and the coach prompts are unchanged; the land
regenerates the prompt seed only if `bin/prompt-seed` requires it (RFC 181), as
every land does.
