# RFC 144 — IPEDS: the attribute ingest

Brief 0004 (`product/0004-college-search-index/`), slice **S2**. Extends the RFC
139 ingest with a second source family: the IPEDS institutional-characteristics
survey. The attributes Ian named — religion, ROTC, study abroad, disability,
housing, athletics, application fee, test policy, closure — plus a 6-digit
program census become queryable source data. No search surface changes.

Gate-2 decisions D14–D22 are binding context. Three of them are load-bearing
here: **D19** (one `bin/ingest-colleges` grows optional IPEDS args,
all-or-nothing per run, no second CLI), **D15** (ordinary migrations, no
versioning triggers on reference/derived tables), and **D11**/the brief's
raw-codes rule (raw source codes in the schema, word enums only at the tool
boundary; NULL means unknown).

## Motivation

`colleges` today carries only what the College Scorecard institution file gives.
Every attribute in Ian's founding queries that Scorecard lacks — "religiously
affiliated", "has ROTC", "guaranteed housing", "test-blind", "NCAA D1" — lives
in IPEDS, and `college_programs` is 4-digit CIP only, so "schools with a marine
biology major" (CIP 260701) cannot be answered at all. S3 cannot build a useful
index over data that was never ingested.

## Ground truth (measured, not assumed)

Every number below was measured from the 2023 files by a research sub-agent
(`.scratch/ship/rfc-144/findings/research-ipeds-files.md`), which downloaded
`HD2023.zip`, `IC2023.zip`, `ADM2023.zip`, `C2023_A.zip` from
`https://nces.ed.gov/ipeds/datacenter/data/` (plain GET, no auth) and parsed
them. The findings that change the implementation:

- **Encoding is UTF-8 with a BOM** on HD/IC/C_A (`adm2023.csv` has none) — _not_
  latin-1 as the brief's research assumed. Read with a BOM-stripping reader or
  the first column name is `\ufeffUNITID` and the header assertion fails on
  three files.
- **CSV member names inside the zips are inconsistently cased** (`HD2023.csv`,
  `IC2023.csv`, but `adm2023.csv` and `C2023_a.csv`), and IC/C_A also ship `_RV`
  revised members. `IC2023_RV.csv` is a **reordered 141-column superset** —
  feeding it in would pass a naive column check and mis-map values. The operator
  passes CSV paths (as with Scorecard), so this is a documentation obligation,
  and the header assertion is the backstop.
- **`adm2023.csv`'s header line ends in trailing spaces** (last field is
  `ACTMT75`) → column names must be trimmed before assertion and lookup.
- **Three different missing-value conventions**: HD uses `-1/-2/-3` codes, IC
  uses `-1/-2` for coded columns and `.` for continuous ones, ADM has **no**
  coded sentinels and uses the empty string, C_A has none.
- **`-2` usually means a real "no"**, not a gap (`RELAFFIL` -2 = 5,164
  institutions that are explicitly not religious; `C21BASIC` -2 = not in the
  Carnegie universe). Only `-1` (not reported) and `-3` (not available) are
  unknown.
- **`HD.CLOSEDAT` is a 10-char space-padded alpha field** whose sentinel is
  `'-2        '`, and two rows carry `00/00/0000`, which is not a date.
- **`C_A.CIPCODE` is dotted and quoted** (`"11.0701"`), and `"99"` is a
  grand-total row equal to the sum of the 6-digit rows (verified: 0 mismatches
  over all 3,493 AWLEVEL=5 groups).
- **`(unitid, cipcode, awlevel)` is not unique** — 19,041 collisions at
  AWLEVEL=5 from the second-major rows. It _is_ unique once `MAJORNUM=1` is
  filtered.
- **749 of the 2,488 universe institutions have no ADM row at all** — the join
  must be a left join; `test_policy` is simply NULL for them.
- **Every UNITID in IC/ADM/C_A exists in HD** (0 orphans), so HD is the driving
  file.

The spec's five acceptance figures all reproduce **exactly** over the stated
universe (`ICLEVEL=1 ∧ UGOFFER=1 ∧ PSET4FLG=1 ∧ CYACTIVE=1`, n=2,488): 954
ROTC-yes, 1,080 test-optional, 567 test-blind, 741 with a denomination, 1,550
study-abroad. Over the **whole file** (which is what this ingest loads, filtered
only by "does a `colleges` row exist") the same counts are 1,005 / 1,100 / 772 /
885 / 1,876. The acceptance test applies the universe filter itself rather than
asserting the raw table counts.

## Detailed Design

### Shape

One command, one run (D19). `bin/ingest-colleges` keeps its three positional
Scorecard arguments and gains an **optional, all-or-nothing IPEDS group** of
long flags:

```
bin/ingest-colleges <institution.csv> <fields.csv> [aliases.json] \
    [--hd=HD.csv --ic=IC.csv --adm=adm.csv --completions=C_A.csv --survey-year=YYYY]
```

Given none of the five, the run behaves exactly as RFC 139 did. Given any of
them, **all five are required** — a partial group is a usage error, never a
silent partial load. `--survey-year` is explicit rather than derived from a
filename: a derived year is a silent coercion, and the year is stamped on every
row written.

Each IPEDS file is resolved by the existing `resolve_file_arg` (local path or
`s3://`), and its **original** argument is forwarded to the JVM as
`--hd-source=` / `--ic-source=` / `--adm-source=` / `--completions-source=`,
exactly as RFC 139 does for the Scorecard trio, so the provenance row records
what the caller actually named.

### Phases

The IPEDS phases run after the Scorecard phases and before provenance, inside
the same run and the same `phase()` tracker (so a post-commit failure still
reports `PARTIAL INGEST` with the committed phase list):

```
institutions → fields → aliases → [ipeds → programs-census] → provenance
```

Header assertion for **all four** IPEDS files happens before any IPEDS write,
with the same `assertRequiredColumns` used by S1 — and, because the Scorecard
headers are already asserted before the institutions phase, a bad IPEDS header
cannot corrupt a run that has already written Scorecard rows only if it is
asserted at the same point. It is: **all seven files are header-asserted up
front, before phase 1**.

### Row semantics

`college_ipeds` is driven by HD: one row per HD record whose `UNITID` matches an
existing `colleges.unit_id`, left-joined in memory to IC and ADM. Unmatched
`unit_id`s are **counted and skipped, never invented** (spec). The match set is
read once (`SELECT unit_id, id FROM colleges`, ~6k rows) before the phase.

`college_programs_census` takes C_A rows with `AWLEVEL=5` (bachelor's),
`MAJORNUM=1` and `CIPCODE <> '99'` — 81,409 rows before the college match
filter. The `MAJORNUM=1` filter is what makes the approved unique key
`(college_id, cip_code, award_level)` sound; second-major rows would collide and
double-count students. `CIPCODE` has its dot removed and must then match
`^[0-9]{6}$` (a row that does not is logged and skipped, per the S1 skip
taxonomy).

Three source-shape defects are **counted skips**, not silent losses and not a
dead phase: a row whose field count differs from its header's
(`row_arity_mismatch` — a short row would otherwise throw out of the first cell
read and abort the phase, a long row's surplus cells would be read by nobody), a
row repeating a natural key an earlier row of the same file already claimed
(`duplicate_key_in_file`, first row wins, in HD, C_A and the IC/ADM side files
alike), and a C_A row whose `AWLEVEL`/`MAJORNUM` cannot be read at all — that
row is not an exclusion the filter decided, it is a row that should have been
judged and could not be, so it lands in the skip taxonomy rather than in
`seen - selected`.

Both tables upsert on their natural key with the S1 `IS DISTINCT FROM` pattern,
so an unchanged re-ingest writes nothing. Neither is versioned (D15): they are
reference data whose history is `college_index_build`.

### Sentinel cleaning

The rule, stated once: **`-1`, `-3`, `.` (where the paired `X*` flag says "left
blank") and the empty string mean UNKNOWN → NULL. `-2` means "not applicable",
which for these columns is a real _no_ — kept as the raw code where the column
is a code column, mapped to `FALSE` where the column is a boolean.**

| Source column                           | Target                                    | Mapping                                                                                                                        |
| --------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `HD.CYACTIVE`                           | `cy_active BOOLEAN NOT NULL`              | `1 → TRUE`, `3 → FALSE` (only values present)                                                                                  |
| `HD.DEATHYR`                            | `death_year`                              | `-2 → NULL` (still alive), else the year                                                                                       |
| `HD.CLOSEDAT`                           | `closed_at`                               | trim; `-2 → NULL`; `00/00/0000 → NULL` + coercion log; else `MM/DD/YYYY`                                                       |
| `HD.NEWID`                              | `new_unit_id`                             | `-2 → NULL`, else the successor UNITID                                                                                         |
| `HD.ICLEVEL`                            | `inst_level`                              | `-3 → NULL`, else `1/2/3` raw                                                                                                  |
| `HD.UGOFFER`                            | `ug_offer`                                | `1 → TRUE`, `2 → FALSE`, `-3 → NULL`                                                                                           |
| `HD.C21BASIC`, `HD.C21SZSET`, `HD.CBSA` | `carnegie_basic`, `carnegie_size`, `cbsa` | raw code, **`-2` preserved** (a real exclusion, not a gap)                                                                     |
| `HD.SECTOR`                             | `sector` (new, D23)                       | raw code `0..9` **or `99`** (sector unknown, not active); `0` = administrative unit; anything else coerced to NULL and tallied |
| `IC.RELAFFIL`                           | `rel_affil`                               | raw code, **`-2` preserved** = explicitly not religious                                                                        |
| `IC.SLO5`, `IC.SLO6`                    | `has_rotc`, `has_study_abroad`            | `1 → TRUE`, `0 → FALSE` (implied no), `-2 → FALSE`, `-1 → NULL`                                                                |
| `IC.DISAB`                              | `disability_band` (D24)                   | raw code `1` (≤3%) / `2` (>3%), `-2 → NULL`, `-1 → NULL`                                                                       |
| `IC.DISABPCT`                           | `disability_pct`                          | `. → NULL` (populated only when `DISAB=2`)                                                                                     |
| `IC.ROOM`                               | `has_housing`                             | `1 → TRUE`, `2 → FALSE`, `-1/-2 → NULL`                                                                                        |
| `IC.ROOMCAP`                            | `housing_capacity`                        | `. → NULL` (populated iff `ROOM=1`)                                                                                            |
| `IC.APPLFEEU`                           | `application_fee`                         | `. → NULL`; **`0` is a real free application**, not a gap                                                                      |
| `IC.ASSOC1..6`                          | `athletic_assoc SMALLINT[]`               | the ordinals `i` where `ASSOCi = 1` (1 NCAA, 2 NAIA, 3 NJCAA, 4 NSCAA, 5 NCCAA, 6 other)                                       |
| `IC.CONFNO1`                            | `football_conf`                           | raw code, `-2` preserved (no football conference), `-1 → NULL`                                                                 |
| `ADM.ADMCON7`                           | `test_policy`                             | raw `1` required / `3` blind / `5` optional; **no ADM row → NULL**                                                             |
| `C_A.CTOTALT`                           | `awards_total`                            | always numeric                                                                                                                 |

Known tri-state gap, stated rather than hidden: `athletic_assoc` is
`NOT NULL DEFAULT '{}'` in the approved DDL, so the 6 institutions whose
`ASSOC*` are all `-1` (unreported) are indistinguishable from "belongs to
nothing". Six rows does not justify changing the approved shape; it is recorded
here and in the column comment.

### Two corrections to the approved DDL (new numbered decisions)

**D23 — add `sector SMALLINT NULL` to `college_ipeds`.** The spec's S2
acceptance says "UMaine System Central Office is loadable but flagged
not-degree-granting". Measured, that is false: UNITID 161280 has `ICLEVEL=1`,
`UGOFFER=1`, `CYACTIVE=1`, `PSET4FLG=1` — it sits _inside_ the 2,488 universe,
along with 51 other `SECTOR=0` administrative units. The only discriminator
IPEDS offers is `SECTOR=0` ("Administrative Unit"). Without `sector` the index
has no honest way to keep a system office out of student-facing results. One
nullable column; the universe counts are unaffected (S3 decides what to do with
it).

**D24 — replace `has_disability_svc BOOLEAN` with `disability_band SMALLINT`.**
`IC.DISAB` is not "offers disability services": it is a band indicator for the
_share of undergraduates formally registered as having a disability_ (`1` = 3%
or less, `2` = more than 3%), and `DISABPCT` carries the percent only when
`DISAB = 2`. Loading it as a boolean would encode a false claim in the schema
and then in the coach's mouth. The column keeps the raw code, `disability_pct`
is unchanged, and the tool boundary can say "more than 3% of undergraduates are
registered with disability services" — which is true — instead of "has
disability services".

### DDL (migration `0055`)

```sql
-- IPEDS institutional attributes, one row per matched college. RFC 144 (0004 S2).
-- Reference table, RFC 84 composition: unversioned, no soft delete, upsert on
-- unit_id. Raw IPEDS codes are stored as-is (brief 0004): -2 is preserved where it
-- means "not applicable / explicitly none"; NULL means unknown.
CREATE TABLE college_ipeds (
    id                 UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    unit_id            INTEGER     NOT NULL,             -- joins colleges.unit_id
    survey_year        SMALLINT    NOT NULL,             -- --survey-year, e.g. 2023
    cy_active          BOOLEAN     NOT NULL,             -- HD.CYACTIVE 1=TRUE, 3=FALSE
    death_year         SMALLINT    NULL,                 -- HD.DEATHYR
    closed_at          DATE        NULL,                 -- HD.CLOSEDAT
    new_unit_id        INTEGER     NULL,                 -- HD.NEWID (merger successor)
    inst_level         SMALLINT    NULL,                 -- HD.ICLEVEL 1/2/3
    ug_offer           BOOLEAN     NULL,                 -- HD.UGOFFER
    sector             SMALLINT    NULL,                 -- HD.SECTOR (D23; 0 = admin unit)
    carnegie_basic     SMALLINT    NULL,                 -- HD.C21BASIC (-2 = not classified)
    carnegie_size      SMALLINT    NULL,                 -- HD.C21SZSET (-2 = not classified)
    cbsa               INTEGER     NULL,                 -- HD.CBSA (-2 = not in a CBSA)
    rel_affil          SMALLINT    NULL,                 -- IC.RELAFFIL (-2 = explicitly none)
    has_rotc           BOOLEAN     NULL,                 -- IC.SLO5
    has_study_abroad   BOOLEAN     NULL,                 -- IC.SLO6
    disability_band    SMALLINT    NULL,                 -- IC.DISAB 1 = <=3%, 2 = >3% (D24)
    disability_pct     DOUBLE PRECISION NULL,            -- IC.DISABPCT (only when band = 2)
    has_housing        BOOLEAN     NULL,                 -- IC.ROOM
    housing_capacity   INTEGER     NULL,                 -- IC.ROOMCAP
    application_fee    INTEGER     NULL,                 -- IC.APPLFEEU (0 is a real free app)
    athletic_assoc     SMALLINT[]  NOT NULL DEFAULT '{}',-- IC.ASSOC1..6 ordinals that are 1
    football_conf      SMALLINT    NULL,                 -- IC.CONFNO1 (-2 = none)
    test_policy        SMALLINT    NULL,                 -- ADM.ADMCON7 1 req / 3 blind / 5 optional
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT college_ipeds_unit_id_positive_check    CHECK (unit_id > 0),
    CONSTRAINT college_ipeds_survey_year_range_check   CHECK (survey_year BETWEEN 1980 AND 2100),
    CONSTRAINT college_ipeds_death_year_range_check
        CHECK (death_year IS NULL OR death_year BETWEEN 1980 AND 2100),
    CONSTRAINT college_ipeds_new_unit_id_positive_check
        CHECK (new_unit_id IS NULL OR new_unit_id > 0),
    CONSTRAINT college_ipeds_inst_level_domain_check
        CHECK (inst_level IS NULL OR inst_level IN (1, 2, 3)),
    -- The PUBLISHED code set, not a range: 0..9 plus 99 ("sector unknown, not
    -- active"). 10..98 are values IPEDS does not emit.
    CONSTRAINT college_ipeds_sector_domain_check
        CHECK (sector IS NULL OR sector BETWEEN 0 AND 9 OR sector = 99),
    CONSTRAINT college_ipeds_disability_band_domain_check
        CHECK (disability_band IS NULL OR disability_band IN (1, 2)),
    CONSTRAINT college_ipeds_disability_pct_range_check
        CHECK (disability_pct IS NULL OR (disability_pct >= 0 AND disability_pct <= 100)),
    CONSTRAINT college_ipeds_housing_capacity_nonneg_check
        CHECK (housing_capacity IS NULL OR housing_capacity >= 0),
    CONSTRAINT college_ipeds_application_fee_nonneg_check
        CHECK (application_fee IS NULL OR application_fee >= 0),
    CONSTRAINT college_ipeds_test_policy_domain_check
        CHECK (test_policy IS NULL OR test_policy IN (1, 3, 5))
);
CREATE UNIQUE INDEX college_ipeds_unit_id_unique_idx ON college_ipeds (unit_id);

-- The house `_03` enforce-updated_at slot (0015, 0054), reusing
-- update_colleges_timestamp(): it touches updated_at only and is NOT a
-- versioning/history trigger, so D15 (reference tables carry no history) holds.
CREATE TRIGGER trigger_03_enforce_college_ipeds_updated_at
BEFORE UPDATE ON college_ipeds
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- 6-digit program census (IPEDS C_A), bachelor's first majors. RFC 144 (0004 S2).
CREATE TABLE college_programs_census (
    id           UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id   UUID        NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    cip_code     TEXT        NOT NULL,     -- 6-digit, dot stripped from C_A.CIPCODE
    award_level  SMALLINT    NOT NULL,     -- IPEDS AWLEVEL (5 = bachelor's)
    awards_total INTEGER     NOT NULL,     -- C_A.CTOTALT
    survey_year  SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT college_programs_census_cip_code_format_check
        CHECK (cip_code ~ '^[0-9]{6}$'),
    CONSTRAINT college_programs_census_award_level_positive_check
        CHECK (award_level > 0),
    CONSTRAINT college_programs_census_awards_total_nonneg_check
        CHECK (awards_total >= 0),
    CONSTRAINT college_programs_census_survey_year_range_check
        CHECK (survey_year BETWEEN 1980 AND 2100)
);
CREATE UNIQUE INDEX college_programs_census_unique_idx
    ON college_programs_census (college_id, cip_code, award_level);
CREATE INDEX college_programs_census_cip_idx ON college_programs_census (cip_code);

-- The same updated_at slot for the census table; likewise not a versioning trigger.
CREATE TRIGGER trigger_03_enforce_college_programs_census_updated_at
BEFORE UPDATE ON college_programs_census
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
```

No `CREATE EXTENSION`. S2 adds no extension of any kind (`pg_trgm`, landed by
S1, remains the repo's only one and is still unverified against live RDS).

### Provenance

The single `college_index_build` row grows, it does not multiply:

- `sources` — the existing array gains the four IPEDS `SourceDigest` entries
  (`file`, `sha256`, `bytes`, `source_arg`) when the group was supplied.
- `rows_ingested` — gains `ipeds` and `programs_census` objects
  (`inserted/changed/unchanged/skipped{by kind}`, and `unmatched_unit_ids`).
  **Omitted entirely when no IPEDS files were passed**, following S1's
  omit-vs-zero discipline: an absent key means "not supplied", a present key
  with zeros means "supplied and changed nothing".
- `change_summary` — gains `non_null.college_ipeds`, a before/after non-null
  count per IPEDS column, from a `CollegeIpedsDao.nonNullCounts` that mirrors
  `CollegesDao.nonNullCounts` with its own closed column allowlist over
  `college_ipeds`. Each DAO owns its table's counter, so no table name is ever
  interpolated into SQL — safer than widening the S1 primitive to take one.
- `method_version` — bumped `1 → 2`. The derivation logic changed; the existing
  test asserting `1` is updated deliberately, not silenced.

`survey_year` is also recorded in `rows_ingested.ipeds.survey_year` so a build
row says which vintage it loaded.

## Files Modified

**New**

- `db/schema/0055.create-college-ipeds-and-programs-census.sql` — the DDL above.
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeIpeds.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeProgramsCensus.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeIpedsDao.kt` — `upsert`,
  `upsertProgramsCensus`, `collegeIdsByUnitId`, `nonNullCounts` for both tables
  (closed column allowlists, mirroring `CollegesDao`).
- `college/src/main/kotlin/ed/unicoach/college/CsvIngestSupport.kt` — the
  generic machinery lifted verbatim out of `CollegeScorecardLoader` (see below).
- `college/src/main/kotlin/ed/unicoach/college/IpedsLoader.kt` — the four
  `REQUIRED_*_COLUMNS` lists, the mappers, the two load phases.
- `college/src/test/resources/ipeds-{hd,ic,adm,ca}-fixture.csv` — byte-verbatim
  excerpts of the real 2023 files (BOM, CRLF, padded sentinels, the
  duplicate-key trap, `00/00/0000`, UNITID 161280) already captured at
  `.scratch/ship/rfc-144/fixtures/`.
- `college/src/test/kotlin/ed/unicoach/college/IpedsLoaderTest.kt`,
  `IpedsIngestTest.kt`;
  `db/src/test/kotlin/ed/unicoach/db/dao/CollegeIpedsDaoTest.kt`.

**Changed**

- `bin/ingest-colleges` — the optional IPEDS flag group, all-or-nothing
  validation, `resolve_file_arg` per file, `--*-source=` forwarding; help text
  updated with the `_RV` / member-name warning.
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` — the
  generic parts move to `CsvIngestSupport` (`ROW_SAVEPOINT` renamed
  `scorecard_row` → `ingest_row`); `ingest(...)` takes an optional
  `IpedsSources?`; `IngestReport` gains ONE nullable `IpedsReport` group (survey
  year, both load results, both non-null snapshots) and `humanSummary()` gains
  its IPEDS lines; `rowsIngestedJson`/`changeSummaryJson` gain the new blocks;
  `METHOD_VERSION = 2`.
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt` — argv
  grammar: optional `--hd/--ic/--adm/--completions/--survey-year` plus their
  `-source` partners; all-or-nothing refusal; new typed failures in the catch
  list.
- `college/src/test/kotlin/ed/unicoach/college/{CollegeScorecardIngestTest,IngestApplicationArgvTest}.kt`
  — `method_version` 2, new argv cases.
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardTestBase.kt` —
  the `TRUNCATE` list gains the two new tables.
- `bin/scripts-tests` — argv/grammar assertions for the new flag group.
- `product/0004-college-search-index/brief.md`, `product/STATUS.md` — S2 ledger.

Deliberately **not** touched: `bin/remote`'s ops-tool allowlist and
`bin/deploy`'s `REPO_PATHS` — the command name is unchanged (D19's point), so
both already work.

## Implementation Plan

1. **Extract `CsvIngestSupport`** from `CollegeScorecardLoader` — move
   `SourceFile`, `SourceDigest`, `digest`, `assertRequiredColumns`,
   `MissingSourceColumnsException`, the cell coercers, `SkipReason`,
   `MapResult`, `LoadCount`, `classifyUpsertFailure`, `recordUpsertFailure`,
   `logUpsertSkip`, `upsertWithSavepoint`, `phase`, `PartialIngestException`.
   Pure motion plus the `ingest_row` savepoint rename; the S1 suite must still
   pass unchanged before anything else starts. Add BOM-stripping + header-name
   trimming to the module's ONE CSV primitive, `CsvFiles.parseCsv` (harmless for
   Scorecard and CDS, required for IPEDS), and route the header assertion and
   every row loop through it — a second `parse` in `CsvIngestSupport` would be a
   fork of the dialect, not an extraction of it.
2. **Migration 0055** + models + `CollegeIpedsDao` with the S1 upsert-if-changed
   shape, and its DAO tests.
3. **`IpedsLoader`** — required-column lists, mappers with the sentinel table
   above, the two phases, the unmatched-unit_id counter.
4. **Wire into `ingest()`** — optional `IpedsSources`, all seven header
   assertions up front, phases in order, digests appended to `sources`, the new
   `rows_ingested`/`change_summary` blocks, `METHOD_VERSION = 2`, summary lines.
5. **Argv + `bin/ingest-colleges`** — the flag group, all-or-nothing refusals,
   `scripts-tests` assertions.
6. **Acceptance run** — against the real 2023 files (see Tests).
7. Ledger updates.

## Tests

**Unit / integration (`nix develop -c bin/test`)**

- `IpedsLoaderTest` — one case per sentinel row in the fixtures: `-1 → NULL`,
  `-2 → FALSE` for `SLO5/SLO6`, `-2` preserved for `RELAFFIL/C21BASIC/CONFNO1`,
  `'.' → NULL` for `DISABPCT/ROOMCAP`, `APPLFEEU = 0` stays `0`, `CLOSEDAT`
  `'-2        '` and `00/00/0000 → NULL`, a real date parsed,
  `ROOM = 2 → FALSE`, `athletic_assoc` ordinals, an ADM-less college →
  `test_policy IS NULL`, `CIPCODE "05.0104"` with `MAJORNUM=2` skipped (no
  unique-violation), `"99"` excluded, `AWLEVEL <> 5` excluded.
- BOM/CRLF/trailing-space header handling: the fixtures are byte-verbatim, so a
  regression in the reader fails the header assertion loudly.
- A missing required column in each of the four files →
  `MissingSourceColumnsException`, **no writes, no build row** (mirrors S1's
  test).
- Unmatched `unit_id` counted and skipped; re-ingest of the same files reports
  `0 inserted, 0 changed, N unchanged` and writes no version churn.
- The three malformed-row skips above, end to end: a short and a long HD row
  counted as `row_arity_mismatch` with the well-formed rows still loading, a
  repeated `UNITID` in HD and in IC counted as `duplicate_key_in_file` with the
  first row kept, a repeated census key likewise, and a C_A row with a blank
  `AWLEVEL` counted rather than excluded.
- `CollegeIpedsDaoTest` — `sector` accepts `0`, `9` and `99` and its CHECK
  refuses `10`/`50`/`98`; a census FK violation's `NotFoundException` carries
  the constraint name, the server DETAIL and the driver `SQLException` as cause.
- `IngestApplicationArgvTest` — partial IPEDS group refused, repeated flag
  refused, blank value refused, `--survey-year` non-numeric refused, no-IPEDS
  run unchanged.
- `CollegeScorecardIngestTest` — `method_version = 2`; `rows_ingested` has
  **no** `ipeds` key when the group is absent, and both keys when present.
- `bin/scripts-tests` — `-h`, arity, the all-or-nothing group, an EMPTY flag
  value and a REPEATED flag both refused (presence is judged on the flag, so
  five empty flags can never degrade to a silent Scorecard-only run), and a
  stub-launcher assertion that the four `--*-source=` originals are forwarded
  verbatim.

**Acceptance (manual, reported with real numbers at verify)**

Download the four 2023 files, run the extended `bin/ingest-colleges` against a
database with `colleges` populated, then assert over the IPEDS universe
(`inst_level = 1 AND ug_offer AND cy_active`, PSET4FLG applied from HD at count
time): `has_rotc` yes = 954, `test_policy = 5` = 1,080, `test_policy = 3` = 567,
`rel_affil <> -2` = 741. UNITID 161280 (UMaine System Central Office) is
present, `ug_offer = TRUE`, and `sector = 0` — i.e. loadable and _flagged as an
administrative unit_, which is the correct form of the spec's acceptance clause
(D23). Unmatched `unit_id`s are reported as a count, in the low hundreds.
