# Repo money audit — brief 0006 ("Money in unicoach shape")

**Method.** Repo-only audit of `/Users/ian/Work/unicoach` (working tree,
2026-09-02). All schema claims read from `db/schema/*.sql`; all consumer claims
from `grep`/read of `db/`, `service/`, `college/`, `rest-server/`, `admin-web/`
main sources (test files cited only where they are themselves a risk). No web
research (not needed); no project commands were run (reading sufficed).
Citations are `file:line` against the current tree.

---

## 1. Every money-bearing column

### 1.1 `colleges` (mirrored on `colleges_versions`)

Created in publisher shape by `db/schema/0015.create-colleges.sql:34-81`,
versioned by `0023.version-colleges.sql`, quintiles added by `0045`, renamed
(not reshaped) by `0059.name-numeric-columns-by-unit.sql:52-84`, components
added by `0062`, residency COMMENTs added by `0075`.

| Column                                       | Source variable                                        | Added                                        | CHECK                                                     | NULL means                                                                                                               |
| -------------------------------------------- | ------------------------------------------------------ | -------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `cost_of_attendance_per_year_usd`            | COSTT4_A (in-state blend)                              | 0015:49 (`cost_attendance`), renamed 0059:55 | nonneg (0015:72, renamed 0059:124-126)                    | not reported / suppressed / never-loaded — indistinguishable. In-state basis stated only in COMMENT 0075:37-47           |
| `net_price_per_year_usd`                     | NPT4_PUB (control=1) else NPT4_PRIV, coalesced at load | 0015:50, renamed 0059:56                     | **none** — nonneg check dropped by 0022 (legit negatives) | same ambiguity; in-state basis COMMENT 0075:49-60                                                                        |
| `net_price_per_year_income_q1..q5_usd`       | NPT41..45_PUB/_PRIV                                    | 0045:24-28, renamed 0059:62-66               | none (deliberate, 0045:12-17)                             | same; per-band COMMENTs 0075:66-96                                                                                       |
| `tuition_and_fees_in_state_per_year_usd`     | TUITIONFEE_IN                                          | 0015:51, renamed 0059:57                     | nonneg 0015:74 → 0059:127-129                             | bare NULL                                                                                                                |
| `tuition_and_fees_out_of_state_per_year_usd` | TUITIONFEE_OUT                                         | 0015:52, renamed 0059:58                     | nonneg 0015:75 → 0059:135-137                             | bare NULL                                                                                                                |
| `median_debt_at_completion_usd`              | GRAD_DEBT_MDN                                          | 0045:29-31, renamed 0059:67                  | nonneg 0045:30 → 0059:146-148                             | bare NULL; federal-borrowers-only caveat in COMMENT 0059:262-266                                                         |
| `median_earnings_10y_after_entry_usd`        | MD_EARN_WNE_P10                                        | 0015:54, renamed 0059:60                     | nonneg 0015:76                                            | bare NULL                                                                                                                |
| `pell_share`                                 | PCTPELL                                                | 0015:55, renamed 0059:61                     | 0..1 range 0015:78 → 0059:144-145                         | bare NULL                                                                                                                |
| `housing_and_food_on_campus_per_year_usd`    | ROOMBOARD_ON                                           | 0062:34                                      | nonneg 0062:40-42                                         | COMMENT 0062:129-135: "NULL is not reported, never zero"; no-dorms ambiguity delegated to `college_ipeds.offers_housing` |
| `housing_and_food_off_campus_per_year_usd`   | ROOMBOARD_OFF                                          | 0062:35                                      | nonneg 0062:43-45                                         | ditto (0062:137-141)                                                                                                     |
| `books_and_supplies_per_year_usd`            | BOOKSUPPLY (one figure, all arrangements)              | 0062:36                                      | nonneg 0062:46-48                                         | ditto (0062:143-149)                                                                                                     |
| `other_expenses_on_campus_per_year_usd`      | OTHEREXPENSE_ON                                        | 0062:37                                      | nonneg 0062:49-51                                         | ditto                                                                                                                    |
| `other_expenses_off_campus_per_year_usd`     | OTHEREXPENSE_OFF                                       | 0062:38                                      | nonneg 0062:52-54                                         | ditto                                                                                                                    |
| `other_expenses_with_family_per_year_usd`    | OTHEREXPENSE_FAM (no ROOMBOARD_FAM exists)             | 0062:39                                      | nonneg 0062:55-57                                         | ditto; with-family housing line is structurally absent (0062:14-16, 165-172)                                             |

**Missingness story.** Three distinct absences collapse into one NULL:

1. _Institution/publisher silence_: Scorecard "NULL"/"PrivacySuppressed"
   sentinels are coerced by `toIntOrNull()` —
   `college/src/main/kotlin/ed/unicoach/college/CsvIngestSupport.kt:272-275`,
   acknowledged at `CollegeScorecardLoader.kt:1239` and `:1307`. Publisher
   **suppression is not distinguishable from non-reporting** anywhere in the
   store.
2. _Column postdates the ingest_: 0045:19-21 and 0062:30-31 both say "existing
   history rows show NULL — those ingests never saw the fields". Live rows carry
   the same NULL until the next ingest, so "load never ran for this column" is
   only recoverable from `college_index_build.change_summary` non-null counts
   (`CollegeScorecardLoader.kt:1544-1574`, `NON_NULL_SUMMARY_COLUMNS`), not from
   the row.
3. _Genuine no-data_. The repo's only in-schema tri-state precedents are noted
   at 0064:160-166 (array columns: NULL = unknown, `{}` = known-none) and
   0055:80-86 (the `athletic_assoc` gap stated in a COMMENT).

`colleges_versions` mirrors every column with **no CHECKs** (0062:59-60: "a
constraint there would reject history the live table accepted") and takes
meaning from the live table's COMMENTs (0075:31-35).

### 1.2 `college_ipeds` (money/money-adjacent)

`db/schema/0055.create-college-ipeds-and-programs-census.sql:18-67`, renamed
0059:90-93.

- `application_fee_usd` — IC.APPLFEEU, 0055:39; nonneg 0055:63-64 →
  0059:156-158. Comment at 0055:39: "0 is a real free app" — the one money
  column where 0 is meaningful.
- `offers_housing` (IC.ROOM) / `housing_capacity_headcount` (IC.ROOMCAP) —
  0055:37-38; money-adjacent: gate the on-campus arrangement (0062:133-135).
- Missingness: 0055:9-13 — IPEDS `-1`/`-3` sentinels and IC `.` land as NULL
  ("unknown"); `-2` "not applicable" is preserved as data. Best
  sentinel-handling precedent in the repo.

### 1.3 `college_merit_aid` (CDS H2A)

`db/schema/0054.create-cds-admissions-tables.sql:54-82`, renamed 0059:102-104.

- `first_time_full_time_freshmen_headcount` (H.201 FRSH_FT_N),
  `no_need_merit_recipients_headcount` (H.2A01), `no_need_merit_average_usd`
  (H.2A02). CHECKs: joint nonneg 0054:75-78, `recipients <= freshmen` 0054:79-81
  (renamed 0059:160-162). NULL = not reported in that school's CDS. Per-row
  provenance: `source_year` domain (0054:41-43), `source_url`, `archive_url`
  (0054:70-71) — the only money table with per-row source citation.
- Derived merit share is computed at read time, never stored (0054:49-51).

### 1.4 `college_search_index` (derived)

`db/schema/0064.create-search-index-and-subjects.sql:82-201`.

- `net_price_per_year_usd` (0064:138) — copied from `colleges` by the rebuild
  (`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:2346`); btree index
  0064:267-268.
- `net_price_percentile_share` (0064:191) — `percent_rank()` over the default
  universe, in-state figure for every family (`CollegesDao.kt:2425-2427`); range
  CHECK 0064:192-200.

### 1.5 `money_profiles` (the family side)

`db/schema/0046.create-money-profiles.sql:18-56` +
`0070.add-money-profile-living-plan.sql:20-32`.

- `income_band` — **literally Scorecard quintile-shaped**: CHECK IN
  `('under_30k','30k_to_48k','48k_to_75k','75k_to_110k','over_110k')`
  (0046:41-43); Kotlin `IncomeBand` owns band→`net_price_per_year_income_qN_usd`
  (`db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt:12-90`).
- `residency_state` (0046:38, `^[A-Z]{2}$` CHECK :46-47), `living_plan`
  (0070:21-27, values = `LivingArrangement` wire names).
- All three are **tri-state**:
  `*_status IN ('unanswered','answered','declined')` with a value-IFF-answered
  CHECK (0046:52-55, 0070:28-32) — the repo's only stored missingness taxonomy,
  and the model gate-1 will consider for the college side.
- Fully versioned entity (history table + triggers 0046:65-134).

### 1.6 Other money-adjacent tables not named in the task

- `college_list_entries.living_plan` — per-college override, NULL = "use global
  default" (`db/schema/0071.add-college-list-entry-living-plan.sql:8-11,17-20`);
  versioned-table ALTER shape, so `log_college_list_entry_version()` was
  restated (0071:13-15).
- `cost_report_shares` (`db/schema/0073.create-cost-report-shares.sql:16-25`) —
  no money figures, but the share link that exposes the whole cost surface to a
  parent (RFC 155).
- `college_index_build` (`db/schema/0052.create-college-index-build.sql:14-28`,
  extended 0064:279-293) — ingest provenance incl. per-money-column non-null
  before/after counts.
- `codebook_sources`
  (`db/schema/0060.create-codebook-reference-tables.sql:351-364`) — per-domain
  artifact digest + vintage + declared NULL sentinels.

---

## 2. Consumers per publisher-shaped column (cutover blast radius)

All paths `.../kotlin/ed/unicoach/...` abbreviated. `CollegesDao` =
`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`.

| Column                                                                               | Readers (file:line)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `net_price_per_year_usd`                                                             | CollegesDao row-mappers :175, :238, :1607; upsert :288-309; **search filter** :686 (`maxNetPricePerYearUsd`) + UnknownAxis accounting; cheapest-first sort :1906; **similar-colleges** cheaper-than filter :1208 and price axis :1287; **index rebuild** :2309, :2344, :2426-2427; percentile input list :2537. Service: `costs/CollegeCostService.kt:1127` (`NetPrice.OverallAverage`); `fitlens/FitLensService.kt:715` (LLM digest line, labelled in-state per RFC 157 D-G at :698-706). Wire: `college/CollegeMatchRow.kt:73` (search/fit-lens/similar match object) with residency caveat in tool description :17-33. Admin: `admin-web/.../CollegesResource.kt:76,158,221` (list, detail, versions panel). |
| `net_price_per_year_income_q1..q5_usd`                                               | CollegesDao mappers :176-180, :239-243; upsert :289-320; similar/search payload :812-813. The one band→column mapping: `db/models/IncomeBand.kt:44-90` (`netPriceFor(College)`, `netPriceFor(CollegeMatch)`). Band-aware rendering: `CollegeCostService.kt:248,716` (`BandSpecific`), `CollegeMatchRow.kt:74-88` (`net_price_by_income_band` array).                                                                                                                                                                                                                                                                                                                                                            |
| `cost_of_attendance_per_year_usd`                                                    | CollegesDao :174, :287-308, :2536. Service: `CollegeCostService.kt:951`; `CostBreakdown.kt:381` (`ReportedAmount.Column`); `CostField.kt:46` (STICKER_..., `ResidencyAxis.IN_STATE_ONLY`, withheld at public schools for non-in-state families per RFC 157). Admin: `CollegesResource.kt:69,157`.                                                                                                                                                                                                                                                                                                                                                                                                               |
| `tuition_and_fees_{in,out_of}_state_per_year_usd`                                    | CollegesDao (11 refs, mappers/upsert); `CostField.kt:51-59` (residency-typed pair); `CostBreakdown.kt` tuition slot (RFC 149 D-C, `CostField.kt` companion ~:120); `CollegeCostService.kt` (5 refs; `TuitionApplicable` selection :131-205); `CollegeCostChatTool.kt` (2 refs); admin `CollegesResource.kt` (4 refs).                                                                                                                                                                                                                                                                                                                                                                                           |
| six cost components (0062)                                                           | CollegesDao mappers/upsert; `CostBreakdown.kt:31-100` (`ARRANGEMENT_COMPONENTS` per `LivingArrangement`); `CostField.kt:92-110`; `College.kt`/`NewCollege.kt` models.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `median_debt_at_completion_usd`, `median_earnings_10y_after_entry_usd`, `pell_share` | CollegesDao; `CostField.kt:81-82` (undated, residency-free); `CollegeCostService.kt`/`CollegeCostChatTool.kt`/`CostBreakdown.kt`; `FitLensService.kt`; admin `CollegesResource.kt`; models `College.kt`, `CollegeMatch.kt`, `NewCollege.kt`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `college_ipeds.offers_housing`                                                       | search index rebuild `CollegesDao.kt:2350`; `ArrangementGap.of` (`costs/ComparisonBasis.kt:611-631` via cost service); search filter (0064:151).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `college_ipeds.application_fee_usd`                                                  | admissions surfaces (`NewCollegeIpeds.kt`; not a cost-tool input today).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `college_merit_aid.*`                                                                | `db/dao/CdsAdmissionsDao.kt`; models `CollegeMeritAid.kt`, `NewCollegeMeritAid.kt`, `CdsCoverage.kt`; `admissions/CollegeAdmissionsService.kt`, `admissions/CollegeAdmissionsChatTool.kt`, `admissions/MeritAidWire.kt`; **also** `costs/CollegeCostService.kt` and `costs/CollegeCostChatTool.kt` (merit-aid line in the cost answer).                                                                                                                                                                                                                                                                                                                                                                         |
| `college_search_index.net_price_per_year_usd` / `net_price_percentile_share`         | search (`CollegesDao.kt:686,810-822,1906`), similar-colleges (:1130,:1208,:1287,:1386-1398), percentile update (:2408-2427); consumed by `college/CollegeSearchTool.kt`, `college/SimilarCollegesTool.kt`, `college/FindCollegeTool.kt` via `CollegeSearchService.kt`; fit-lens sweeps the same query path (`FitLensService.kt:88-95` shares `CollegeQuery`/`CollegeQueryVocabulary`).                                                                                                                                                                                                                                                                                                                          |
| `money_profiles.*`                                                                   | `CollegeCostService.kt` (band/residency/plan resolution :865-908, :1006); `MoneyProfileChatTool.kt`; `rest-server/.../routing/MoneyProfileRoutes.kt`; fit-lens (residency label decision `FitLensService.kt:698-706`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

**Coupling that magnifies the radius:** `CostField.wireName`s "ARE the column
names" (`costs/CostField.kt:92-96`), so JSON keys on the chat wire
(`cost_by_living_arrangement`, `data_availability`) are literally
publisher-column names. A canonical rename is a tool-contract change, not just a
schema change.

---

## 3. The read-time canonical embryo (candidates to become stored shape)

- **Arrangements**:
  `db/src/main/kotlin/ed/unicoach/db/models/LivingArrangement.kt:25`
  (on_campus/off_campus/with_family — already the stored vocabulary of
  0070/0071). Component-per-arrangement map: `costs/CostBreakdown.kt:31-100`
  (`ARRANGEMENT_COMPONENTS`, `LivingArrangement.components`); arrangement totals
  `ArrangementCost` (:127-241) refuse partial sums and mixed vintages
  (`MixedVintageArrangementException` :102).
- **Silence taxonomy (RFC 152)**: `ArrangementGap` — the _school's_ silence
  (`costs/ComparisonBasis.kt:578-631`: `NO_ON_CAMPUS_HOUSING` only on a known
  IPEDS `false`; `NOT_REPORTED` otherwise) vs `NoTotalReason` — _our_ silence
  (`costs/CostBreakdown.kt:469-503`: `AWAITING_RESIDENCY_ANSWER`,
  `TUITION_APPLICABILITY_UNKNOWN`, `PART_NOT_PUBLISHED`). Carried by
  `PlanSilence` (ComparisonBasis.kt:645-660). This is the missingness enum a
  canonical schema would store.
- **Comparison basis (RFC 151/157)**: `costs/ComparisonBasis.kt:43-50` — six
  facts (population, residency, blended-figure basis, arrangement, academic
  years, aid basis), each a code + computed statement; `allStatements` forbids
  subsetting (:53-60). `BlendedFigureBasis.kt` and `SingleSchoolBasis.kt` are
  the per-figure/per-school halves.
- **Residency axis + withholding (RFC 157)**: `costs/ResidencyAxis.kt:29-48`
  (`IN_STATE_ONLY` vs `IN_STATE` vs `OUT_OF_STATE`); `CostField` requires every
  figure to declare vintage AND residency at compile time
  (`CostField.kt:30-37`); withholding is derived in `CollegeCostService.kt`
  (`NetPrice.Withheld` :117-124, `TuitionApplicable` :131-205,
  `CollegeCost.withheld/blendedFiguresApply` :318-410). _A stored price row with
  a mandatory residency-basis column makes this whole layer a projection._
- **Vintage-per-figure (RFC 149 D-E)**: `costs/ScorecardVintage.kt:39-64` —
  exactly two vintages (PUBLISHED_PRICE AY2022-23, BLENDED_AVERAGE AY2021-22),
  closed enum, undated figures carry null; only same-vintage figures may be
  summed (enforced in `CostBreakdown`). Snapshot-bump procedure documented at
  ScorecardVintage.kt:33-38 — today a _code edit_; canonically a per-figure
  `vintage` column filled by ingest.
- **Data they compute from**: entirely the `colleges` columns of §1.1 plus
  `college_ipeds.offers_housing` and the three `money_profiles` facts — nothing
  else.

---

## 4. Ingest/offline architecture

- **`bin/ingest-colleges`** (launcher; :1-100 documents the contract) drives the
  JVM `college/IngestApplication.kt:406-540`. Phase order, each phase its own
  transaction (`CollegeScorecardLoader.kt:600-720`): `codebooks` (:626-629) →
  `subjects` (:641-647) → `institutions`+`programs` (loadScorecard :648) →
  `aliases` (:649) → `ipeds` + `programs-census` (:650-659) → `cds` (:666-671) →
  derived phase 2: `name-words` (:676) → `search-index` (:680) → read-only
  unknown-code report (:681-700) → `provenance` (one `college_index_build` row,
  success only). Partial failure raises `PartialIngestException` naming
  committed phases (`CsvIngestSupport.kt:31-51`); re-running completes
  idempotently.
- **`bin/fetch-codebooks`** (bin/fetch-codebooks:1-31): operator-run python;
  downloads IPEDS Stata syntax archives → `db/seed/codebooks/*.zip` +
  `PROVENANCE.json` → generated `db/data/codebooks.json`; ALL interpretation
  (slugs, sentinels, label parses) lives in the generator; unparseable label =
  FATAL, never guessed.
- **`bin/fetch-cds-seed`** (bin/fetch-cds-seed:3-25): same pattern over the
  collegedata.fyi PostgREST corpus → three committed CSVs + PROVENANCE.json with
  drop counts and samples.
- **Provenance**: `college_index_build` records sources (sha256), per-table row
  counts, and per-column non-null before/after (`change_summary`) — the closest
  thing to a per-column "the load carried this" fact (0052:7-11;
  `CollegeScorecardLoader.kt:1544-1574`). Caveat: keys are column names,
  append-only across renames (0059:37-42).
- **Where a "map external → canonical" phase slots in**: after the row phases
  (`institutions`/`ipeds`/`cds`) and before/alongside the derived phase 2 —
  exactly where `search-index` sits today (`CollegeScorecardLoader.kt:675-680`:
  "rows first, derived state second, never per-row triggers"). A canonical money
  table is _derived state rebuilt wholesale from staged rows_, i.e. the
  `college_search_index` composition (0064:13-17), with its row count added to
  `college_index_build` the way 0064:279-293 added `search_index_rows`.
- **Where staging (external-shaped) tables could live**: the repo already has
  the pattern twice — `college_ipeds`/`college_merit_aid` are effectively
  external-shaped staging with reference-table composition (0055:2-6:
  "unversioned... their history is college_index_build"; 0054:6-13). New
  Scorecard/IPEDS-IC staging tables would take that same shape (unversioned,
  upsert on natural key + vintage, updated_at trigger only), freeing `colleges`
  to shrink toward identity/location.

---

## 5. RFC 147 codebook tables as precedent for unicoach-authored vocabulary

- Shape today (`db/schema/0060`): `ipeds_regions` (slug PK, `code` = OBEREG,
  `name` parsed, `label_raw` verbatim; :65-72), `us_states` (usps_code PK,
  `name`, `ipeds_region` FK, plus **one authored column** `jurisdiction_kind`
  :92-107 — IPEDS publishes no such column). `codebook_sources` (:351-364)
  records artifact/digest/ vintage/null-sentinels per domain; loads report drift
  loudly.
- The pattern proved: unicoach-keyed rows (slugs), offline generation
  (`bin/fetch-codebooks`), committed diffable JSON, provenance, loud
  unknown-code reports (D46, `CollegeScorecardLoader.kt:681-700`), and even one
  FK step further — 0067 made `colleges.state`/`locale` real FKs onto the
  codebook tables (0067:49-56).
- But these tables **mirror the published codebook**: 0060:1-10 ("the PUBLISHED
  meaning of the federal codes... nothing here is authored by hand"), and slugs
  derive from published labels. `label_raw` is kept "byte for byte" as evidence
  (0060:58-62). `us_states.jurisdiction_kind` (0060:86-90,105-107) and
  `subjects` (0064:33-76, authored `db/data/subjects.json`, loader-validated
  against `cip_codes`) are the only two unicoach-authored vocabularies in the
  store today.
- Ian's directive changes the direction of authority: today external code →
  published label; the target is _unicoach authors the vocabulary and maps
  external codes into it_. `subjects` is the working precedent for exactly that
  (authored taxonomy + `cip_prefixes` mapping + fatal validation at load,
  0064:37-45), and `college_search_index`'s D61 rule ("every coded column holds
  OUR vocabulary, not the publisher's number", 0064:24-30) is the same principle
  applied to a derived table. What changes vs today: the mapping file becomes
  the authored artifact (like subjects.json), the codebook mirrors become inputs
  to it rather than the vocabulary itself, and the D46 drift report inverts —
  "published code with no unicoach mapping" becomes the loud finding.

---

## 6. Migration risk register

1. **`log_college_version()` names every column literally.** The plpgsql body is
   TEXT; renames don't reach inside it (0059:178-182), so _every_ add/rename on
   `colleges` must restate the full INSERT (pattern:
   0023→0045:44-66→0059:183-212→0062:74-110) or the next college write fails.
   Same trap on `money_profiles` (`log_money_profile_version`, 0046:83-95) and
   `college_list_entries` (0071:13-15). `colleges_versions` must also mirror
   each new column, constraint-free (0062:59-67).
2. **The search-index rebuild is a second copy of the column list.** DELETE +
   INSERT-SELECT
   - percentile UPDATE all name `net_price_per_year_usd`
     (`CollegesDao.kt:2305-2440`); the table, its indexes (0064:267-268) and
     `DefaultUniverse` interact. A cutover that changes the index's price source
     must change the rebuild SQL, the 0064 DDL, and
     `CollegeSearchIndexRebuildTest`
     (`db/src/test/.../CollegeSearchIndexRebuildTest.kt:63-438`) together.
3. **A source-scanning test polices money arithmetic.**
   `ForbiddenCostArithmeticTest`
   (`service/src/test/.../costs/ForbiddenCostArithmeticTest.kt:29-121`)
   regex-scans every `.kt` in `service/.../costs/` for money-operand
   subtraction, _including string-template bodies_ (RFC 157 tier-2 fix, :46-54).
   New canonical-layer code placed in that package inherits the scan; code
   placed elsewhere silently escapes it — either way a decision. Prompt-side
   twins live in `SystemPromptCatalogTest.kt:311,461,593,1309`.
4. **Wire keys == column names.** `CostField.wireName` (CostField.kt:44-110) and
   the 0062 comment "the wire names ARE the column names" mean column renames
   are visible to the model/tools; RFC 149's JSON contract and
   `CollegeMatchRow.kt:33,73-88` (the in-state caveat sentence names
   `net_price_per_year_usd` verbatim) pin them further.
5. **Ingest-loaded FKs make phase order a write precondition.** 0067 (:20-37)
   made the `codebooks` phase a hard precondition of any `colleges` write; tests
   truncate reference tables (`CodebooksDaoTest.kt:61`), so canonical tables
   with FKs onto codebook/vocabulary tables re-import this whole class of
   test-fixture and empty-database failure. The 0064:296-320 note (why D57 was
   first refused) is the fullest statement of the trade.
6. **`change_summary` keys are frozen column names.** Append-only provenance
   keyed by `NON_NULL_SUMMARY_COLUMNS` (`CollegeScorecardLoader.kt:1544-1574`);
   a rename seams the history (0059:37-42 — known, tolerated, but each rename
   widens it).
7. **Admin-web hardcodes the column set.**
   `CollegesResource.kt:69-80,157-158,221` (list fields, detail map, versions
   panel) must track any live-table change.
8. **Delete guards force TRUNCATE-based test churn.**
   `trigger_00_prevent_colleges_delete` (0023) means table swaps/backfills in
   tests go through TRUNCATE CASCADE
   (`CollegeSearchIndexRebuildTest.kt:434-438`) — fine, but every
   canonical-table test fixture must join that dance.
9. **CHECK-constraint name length.** Postgres truncates identifiers at 63 chars
   silently (0059:130-134, 0062:22-24) — long canonical column names will hit
   this again.
10. **The safe path exists in-repo.** 0045/0062 (add columns + backfill on next
    ingest, NULL history is honest), 0059 (pure-rename mechanics), 0064 (new
    derived table + wholesale rebuild + provenance column) together are the
    staged playbook the brief's success criterion 5 asks for: land canonical
    tables beside `colleges`, fill them in a new ingest phase, cut consumers
    over one at a time (§2 is the checklist), drop publisher columns last.

**Tool availability note.** All findings are from direct file reads/greps; no
build or DB commands were needed, and none were run.
