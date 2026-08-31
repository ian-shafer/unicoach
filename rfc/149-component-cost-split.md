# RFC 149 — The component cost split

Status: proposed Slice: `money/02/component-split` (brief 0003, M2) Base:
`main@a995a8e8` · Branch: `pipeline/rfc-149`

## Summary

`colleges` stores one blended sticker figure (`cost_of_attendance_per_year_usd`,
`COSTT4_A`) and no cost components at all. At a public four-year, tuition and
fees is a median of only 37% of the on-campus total; the other 63% — housing and
food, books and supplies, travel and everyday spending — is the part a family
can actually influence, and today the product cannot see it.

This RFC ingests the six component figures the pinned Scorecard snapshot already
carries, and renders them as a per-college breakdown keyed by living
arrangement: `on_campus`, `off_campus`, `with_family`. It makes the brief's one
irreplaceable sentence sayable — _living at home instead would cost $7,368 less
— most of a year's tuition._

No new data source. Migration + loader + DAO + models + tool + prompt.

## Decisions

### D-A. Column names follow the 0059 unit convention, not D18's short names

Brief 0003 D18 decided the product vocabulary: the columns say **housing and
food**, never _room and board_. That decision stands. But D18 was written before
migration `0059.name-numeric-columns-by-unit`, which renamed every numeric
column on `colleges` to `measure_qualifier_unit` with dollars ending
`_per_year_usd`. Writing `housing_food_on` today would be the only numeric
column on the table without a unit.

Both rules are honoured by spelling the names out:

| Column                                     | Scorecard field    | Meaning                              |
| ------------------------------------------ | ------------------ | ------------------------------------ |
| `housing_and_food_on_campus_per_year_usd`  | `ROOMBOARD_ON`     | housing + food, living on campus     |
| `housing_and_food_off_campus_per_year_usd` | `ROOMBOARD_OFF`    | housing + food, renting off campus   |
| `books_and_supplies_per_year_usd`          | `BOOKSUPPLY`       | books and supplies (one figure, all) |
| `other_expenses_on_campus_per_year_usd`    | `OTHEREXPENSE_ON`  | travel + personal, on campus         |
| `other_expenses_off_campus_per_year_usd`   | `OTHEREXPENSE_OFF` | travel + personal, off campus        |
| `other_expenses_with_family_per_year_usd`  | `OTHEREXPENSE_FAM` | travel + personal, living at home    |

Six, not seven: there is no `ROOMBOARD_FAM`, which is why `with_family` renders
no housing and food line rather than a `$0` one.

The Scorecard field name lives in a `COMMENT ON COLUMN` (the `0059` pattern),
not in the column name. Longest constraint identifier is 62 characters, inside
Postgres's 63-character limit (counted, not assumed).

### D-B. The no-dorms case reads IPEDS `offers_housing`, it is not inferred

"This school has no residence halls" must be distinct from "not reported" (brief
0003, gate-2 D13). The Scorecard publishes no housing flag, and inferring one
from a null `ROOMBOARD_ON` would be a guess presented as a fact.

IPEDS does publish it, and we already ingest it: `college_ipeds.offers_housing`
(`IC.ROOM`, landed by RFC 144, renamed by `0059`). The cost read joins it by
`ipeds_unit_id`, in one batched query for the whole list, exactly as the merit
read does. Three states, all explicit: offers housing / does not offer housing /
no IPEDS row, which is "not reported".

Note this consciously spends brief 0003's D7 ("defer IPEDS IC"). D7's reason was
that ingesting IPEDS was work this brief did not need. That work has since
landed on its own account, so the reason has expired; the data is free now.

The no-dorms signal is its own key, **not** a `data_availability` entry.

**Published figures win over the flag.** The two sources can disagree: a school
whose IPEDS `ROOM` says it offers no housing may still publish `ROOMBOARD_ON`
and `OTHEREXPENSE_ON`. When that happens the on-campus arrangement is rendered
from the published figures and the flag is still reported beside it; the
contradiction is logged. Suppressing a number the school published would be the
worse failure of the two, and calling it "not reported" would be false about a
reported figure. The flag only suppresses the on-campus arrangement when there
is nothing published to show — which is the ordinary shape of a school with no
residence halls.

### D-C. The breakdown is computed in the service and rendered by the tool

`CollegeCost` gains two fields: `breakdown: CostBreakdown?` and
`offersOnCampusHousing: Boolean?`. The second is not part of the breakdown
because a school with no residence halls may report no component at all, and the
no-dorms answer must still be sayable when the breakdown is null. Arrangement
totals are summed in `CollegeCostService`, not in the tool: the tool is a
renderer and the arithmetic is domain truth that the service's own tests can
reach without a JSON round-trip.

Wire shape, per college, one new key `cost_by_living_arrangement`:

```json
"cost_by_living_arrangement": {
  "on_campus": {
    "tuition_and_fees_in_state_per_year_usd": 5507,
    "housing_and_food_on_campus_per_year_usd": 7368,
    "books_and_supplies_per_year_usd": 1500,
    "other_expenses_on_campus_per_year_usd": 4545,
    "total_per_year_usd": 18920
  },
  "off_campus": { ... },
  "with_family": {
    "tuition_and_fees_in_state_per_year_usd": 5507,
    "books_and_supplies_per_year_usd": 1500,
    "other_expenses_with_family_per_year_usd": 4545,
    "total_per_year_usd": 11552
  }
}
```

Rules the shape enforces:

- Component keys are `CostField` wire names, identical to the column names, so
  the JSON, `data_availability` and the schema all speak one vocabulary. The
  keys look redundant inside an arrangement object on purpose: the alternative
  is a second vocabulary of arrangement-local names that nothing else shares.
- The tuition line reuses the existing `tuition_and_fees_*` wire names and is
  selected by the existing `TuitionApplicable`. When residency is unanswered
  (`unknown`) the tuition line is **absent** and the arrangement carries no
  total — a total that silently picked one residency would be a lie.
- `with_family` carries **no** housing and food key. Explicit absence, never a
  zero.
- An arrangement whose parts are not all reported carries the parts it has and
  **no** `total_per_year_usd`. A partial sum is not a total.
- An arrangement with no reported parts at all is omitted entirely.
- `on_campus` is omitted when `offers_housing` is false **and the school
  publishes no on-campus figure**, and the college carries
  `"offers_on_campus_housing": false` instead. The flag is emitted **whenever it
  is known**, true or false; it is absent only when IPEDS does not tell us. A
  rendered `on_campus` arrangement is not a surrogate for "this school has
  dorms". That is the no-dorms answer. When the flag is false but the school
  does publish on-campus figures, D-B applies: the arrangement is rendered, the
  flag rides beside it, and the contradiction is logged.

### D-D. Books and other expenses stay separate on the wire; the copy merges them

The slice text speaks of four spoken lines (tuition, housing and food, books and
other, total). That is a copy decision, made in the prompt. The wire keeps
`books_and_supplies_*` and `other_expenses_*` separate, because they are two
published figures and collapsing them in the payload would make the tool's
output un-checkable against its source. Both are the same vintage, so the coach
may say them as one line.

### D-E. Per-figure academic-year vintages replace "data ingested YYYY"

`sourceAttribution(ingestYear)` currently emits
`"... College Scorecard (data ingested 2026)"`. `ingestYear` is derived from
`colleges.updated_at` — when we loaded the file, not the year of the figures.
Its own KDoc already says it "is _not_ the Scorecard release year".

This RFC introduces `ScorecardVintage` beside `CostField`: documented constants
for the pinned snapshot — components **AY2022-23**, `COSTT4_A` and the `NPT4*`
family **AY2021-22** — rendered through the existing `CdsCitation.cycleLabel`
style ("2022-23", never a bare year). The tool emits the vintage next to the
figures it governs, and `sourceAttribution` stops claiming an ingest year.
`ingestYear` stays on `CollegeCostProfile` (it is honest as a freshness fact)
but is no longer spoken as a vintage.

Each vintage is emitted as an **object naming the figures it dates**, not as a
bare year:

```json
"published_price_academic_year": {
  "academic_year": "2022-23",
  "figures": ["tuition_and_fees_in_state_per_year_usd", "..."]
}
```

A bare year states a fact the reader cannot use: the prompt tells the coach to
quote the year _beside a figure_, so the payload must say which figures the year
governs rather than leaving that membership in prose. The `figures` list is
derived from the same recorded emission set the labels are, so it cannot drift
from what was actually rendered.

**A figure this RFC has not dated carries no vintage at all.**
`ScorecardVintage` covers exactly two families — the published price (tuition
and the six components, AY2022-23) and the blended averages (`COSTT4_A` and
`NPT4*`, AY2021-22). Median debt at completion and median earnings ten years
after entry are on neither cohort basis, and this RFC establishes no year for
them, so their `CostField.vintage` is null and the tool prints no academic year
beside them. Printing a borrowed year would be exactly the false precision the
vintage work exists to remove.

### D-F. Three hard rules, asserted by tests

1. **`COSTT4_A` is not the component sum.** It is a weighted blend across living
   arrangements and a year older; measured, it equals the on-campus sum in 0% of
   publics. It keeps its own key and its own label; nothing compares the two.
2. **Never compute `net_price − tuition`**, in code or in prompt. Aid applies to
   the blend, not to a component. A test asserts no such subtraction exists.
3. **Never sum across vintages.** An arrangement total may mix only figures of
   one vintage — the tuition line and the components are all the published price
   of AY2022-23. `CostField` carries its own vintage and `ArrangementCost`
   refuses a mixed list, so this is enforced by construction rather than
   remembered.

### D-G. Coach prompt v9 appends; it does not replace

v9 is v8 byte-identical plus one appended paragraph (the additive shape of
`0047`, `0048`, `0058`), teaching the coach to lead with the split, name the
living arrangement it is quoting, mark the estimated lines as estimates, say the
at-home comparison when the school reports it, and never subtract a component
from a net price. v8 remains the rollback (`COACHING_SYSTEM_PROMPT_VERSION=v8`).

### D-H. The six columns do not enter `college_search_index`

Gate-2 D15 left this to be settled here. The answer follows from what the index
is for, not from timing: **the search index carries only what search needs** —
the college id, the name material, and the columns we want to filter or rank on.
A column earns a place there when someone can ask for it in a query ("colleges
whose books and supplies allowance is over $10,000"), not merely because it is a
cost figure.

Nobody searches on a component allowance today. These six exist to _explain_ one
already-chosen school's price, which is a read of `colleges` on the cost path,
not a filter. So they stay out. If a later slice wants a component filter, it
adds the column to the index then, with the query that justifies it.

(Independently: `college_search_index` does not exist yet on `main` or in any
worktree — `search/03b` is not started — so this is a rule stated for that
slice, not a change to it.)

## DDL (presented explicitly, per brief 0001 D10)

Migration `0062.add-college-cost-components.sql` — number claimed at commit
time; `0060` and `0061` are already claimed inside the live `pipeline/rfc-147`
worktree, so the current next free is **0062**.

```sql
ALTER TABLE colleges
    ADD COLUMN housing_and_food_on_campus_per_year_usd  INTEGER NULL,
    ADD COLUMN housing_and_food_off_campus_per_year_usd INTEGER NULL,
    ADD COLUMN books_and_supplies_per_year_usd          INTEGER NULL,
    ADD COLUMN other_expenses_on_campus_per_year_usd    INTEGER NULL,
    ADD COLUMN other_expenses_off_campus_per_year_usd   INTEGER NULL,
    ADD COLUMN other_expenses_with_family_per_year_usd  INTEGER NULL,
    ADD CONSTRAINT colleges_housing_and_food_on_campus_per_year_usd_nonneg_check
        CHECK (housing_and_food_on_campus_per_year_usd IS NULL
               OR housing_and_food_on_campus_per_year_usd >= 0),
    -- ... one nonneg CHECK per column, same shape (D19)
;

ALTER TABLE colleges_versions
    ADD COLUMN housing_and_food_on_campus_per_year_usd  INTEGER NULL,
    -- ... the same six columns, no constraints on the history table (the 0045 pattern)
;

CREATE OR REPLACE FUNCTION log_college_version() ...  -- full current body from 0059,
                                                      -- plus the six columns.
                                                      -- trigger_04_log_college_version
                                                      -- picks it up by name.

COMMENT ON COLUMN colleges.housing_and_food_on_campus_per_year_usd IS
    'ROOMBOARD_ON: the school''s published allowance for housing and food for a
     student living on campus, whole USD per academic year (AY2022-23). NULL is
     "not reported", never zero; a school with no residence halls reports no
     on-campus figure (see college_ipeds.offers_housing).';
-- ... one COMMENT per column.
```

All six carry a nonneg CHECK (D19): they are gross costs, so a negative is a
loader bug. This deliberately differs from `0045`'s net-price band columns,
where Scorecard publishes legitimate negatives.

A second migration seeds coach prompt v9 (next free after the above).

## Detailed Design

### Ingest (`:college`)

`CollegeScorecardLoader` gains six column-name constants (`COL_ROOMBOARD_ON` …),
each added to `REQUIRED_INSTITUTION_COLUMNS` so the RFC 139 header assertion
fatals on a snapshot missing them, and each read in `mapInstitution` with the
existing bounded form:

```kotlin
housingAndFoodOnCampusPerYearUsd = intInDomainOrNull(
    record, COL_ROOMBOARD_ON, 0, Int.MAX_VALUE,
    "housing_and_food_on_campus_per_year_usd", coercions,
)
```

`"NA"` / `"PrivacySuppressed"` become null by the existing sentinel handling; an
out-of-domain value is coerced to null and tallied, with the DB CHECK as
backstop.

The six DB column names are added to `NON_NULL_SUMMARY_COLUMNS` **and** to
`CollegesDao.NON_NULL_COUNTABLE_COLUMNS` (the closed allowlist `nonNullCounts`
requires), so the change summary proves the columns actually loaded — both in
`college_index_build.change_summary` and in the printed `non-null deltas:` line.

`bin/ingest-colleges` needs no change: it names no columns and execs the
prebuilt launcher.

### Persistence (`:db`)

`College` and `NewCollege` gain the six nullable `Int?` fields with the RFC 133
doc-comment pattern. `CollegesDao.upsert` carries each column in all four places
of its hand-rolled statement — INSERT list, `DO UPDATE SET`, the `colleges`
tuple and the `EXCLUDED` tuple — the last two being what makes an unchanged
re-ingest skip the version bump and a changed one record history. Both row
mappers gain the columns. The **search projection does not**: per D-H the search
path carries only what search filters or ranks on, and `CollegeMatch` renders
every field it holds to the model.

### Read path (`:service`)

- `CostField` gains six members whose wire names are the column names.
- New `CostBreakdown` / `LivingArrangement` types beside `CollegeCost`, with the
  arrangement totals computed in the domain layer (`CostBreakdown.of`, called
  from `CollegeCostService.costOf`) rather than in the tool.
- A batched `CollegeIpedsDao` read supplies `offersHousing` per college, joined
  by `ipeds_unit_id`; the existing statement-count test is extended so N
  colleges still cost a constant number of statements.
- `notReportedOf` lists a component `CostField` when the school reports no value
  for it, **except** the two on-campus components at a school known not to offer
  housing: an absent on-campus figure there is explained, not a silence. The
  no-dorms case is _not_ a `data_availability` entry; it is
  `offers_on_campus_housing: false`.
- `CollegeCostChatTool` renders `cost_by_living_arrangement`,
  `offers_on_campus_housing` (whenever the flag is known, true or false), and
  the vintage objects of D-E — each an `academic_year` plus the `figures` it
  dates — and gains description copy for all of it.

### Prompt

`db/schema/0063.seed-coach-system-prompt-v9.sql`: v8 verbatim plus one appended
paragraph. `service.conf` pins `v9`; `CoachingConfigTest`'s pinned literal moves
with it.

## Files Modified

| File                                                                    | Change                                            |
| ----------------------------------------------------------------------- | ------------------------------------------------- |
| `db/schema/0062.add-college-cost-components.sql`                        | new — the DDL above                               |
| `db/schema/0063.seed-coach-system-prompt-v9.sql`                        | new — v8 + one paragraph                          |
| `db/src/main/kotlin/ed/unicoach/db/models/College.kt`, `NewCollege.kt`  | six fields                                        |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`                  | upsert, row mapper, countable set (not search)    |
| `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` | constants, required columns, summary columns, map |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegeIpedsDao.kt`              | batched `offersHousing` read                      |
| `service/.../coaching/costs/CostField.kt`                               | six members; every member carries its vintage     |
| `service/.../coaching/costs/CostBreakdown.kt`                           | new — arrangement types                           |
| `service/.../coaching/costs/ScorecardVintage.kt`                        | new — documented AY constants                     |
| `service/.../coaching/costs/CollegeCostService.kt`                      | breakdown, housing join, notReported              |
| `service/.../coaching/costs/CollegeCostChatTool.kt`                     | new keys, vintage, `SOURCE_ATTRIBUTION`           |
| `service/src/main/resources/service.conf`                               | prompt pin v9                                     |
| tests (below)                                                           | —                                                 |

Not modified: `bin/ingest-colleges`, `.claude/**`.

## Implementation Plan

1. Migration + `College`/`NewCollege` + `CollegesDao` + DAO tests. Green.
2. Loader constants, required-column list, `mapInstitution`, summary columns +
   loader/ingest tests against the existing fixture. Green.
3. `CostField`, `ScorecardVintage`, `CostBreakdown`, service assembly, IPEDS
   housing join + service tests. Green.
4. Tool rendering, guard allowlist, description copy + wire tests. Green.
5. Prompt v9 migration, `service.conf`, catalog test. Green.
6. `nix develop -c bin/test`.

## Tests

- **DAO**: round-trip of all six columns; an unchanged re-ingest bumps no
  version; a changed component writes a `colleges_versions` row carrying it; the
  nonneg CHECK rejects a negative.
- **Loader**: the committed `scorecard-institutions-real-fixture.csv` already
  covers the three cases — Auburn Montgomery (all six present), Ventura (`NA`
  on-campus, off-campus present), Pensacola Christian (all six `NA`). Assert the
  mapped values, the null sentinel handling, and the six non-null deltas in the
  change summary. A snapshot missing a column fatals at the header assertion.
- **Service**: three arrangements assembled; `with_family` has no housing key;
  an incomplete arrangement carries no total; `offers_housing = false` drops
  `on_campus` and sets the flag; no IPEDS row is "not reported"; unanswered
  residency drops the tuition line and every total; component `CostField`s
  appear in `notReported`; the statement count stays constant across N colleges.
- **Tool**: the new keys and their exact vocabulary (`PRE_FEED_COLLEGE_KEYS`
  grows); the bare-source-code guard passes with every component populated, its
  key-coverage assertion extended to walk nested objects; the description states
  the contract.
- **The forbidden arithmetic**: a test asserts no source file in the cost
  package subtracts a tuition or component field from a net-price field, and
  that prompt v9's body contains no such instruction.
- **Prompt catalog**: v9 is v8 plus exactly one appended paragraph; the RFC 142
  source-jargon sentence and the RFC 141 glossary pairs survive
  byte-identically; `service.conf`'s pin exists in the catalog.
