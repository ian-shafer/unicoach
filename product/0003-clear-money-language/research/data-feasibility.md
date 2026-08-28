# Data feasibility: splitting tuition from room & board

Research for product brief 0003 ("clear money language"). Question: can unicoach
show a stable-cost / variable-cost split per school, and if so from what data?

**Headline: yes, today, with no new data source.** The component columns are
already in the pinned College Scorecard institution CSV we ingest — we simply
never mapped them. The work is a migration + loader/DAO/tool change, not an
ingest project.

## Method

- Read the repo:
  `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`,
  `db/schema/0015.create-colleges.sql`,
  `db/schema/0045.add-college-income-band-net-price.sql`,
  `service/src/main/kotlin/ed/unicoach/coaching/costs/`, and the verbatim
  real-data fixture
  `college/src/test/resources/scorecard-institutions-real-fixture.csv`.
- Primary docs downloaded and parsed directly (2026-08-27):
  [College Scorecard Data Dictionary (xlsx)](https://collegescorecard.ed.gov/assets/CollegeScorecardDataDictionary.xlsx),
  [Institution Data Documentation (PDF, "Version: June 2024")](https://collegescorecard.ed.gov/assets/InstitutionDataDocumentation.pdf),
  [IPEDS IC2023_AY data dictionary](https://nces.ed.gov/ipeds/datacenter/data/IC2023_AY_Dict.zip)
  (and IC2019/2021/2022 for the label history), via
  [nces.ed.gov/ipeds/use-the-data](https://nces.ed.gov/ipeds/use-the-data/).
- Coverage and arithmetic computed on the **actual current release**,
  [Most-Recent-Cohorts-Institution_06102026.zip](https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Institution_06102026.zip)
  (n = 6,273 institutions), linked from
  [collegescorecard.ed.gov/data](https://collegescorecard.ed.gov/data/).
- **Unavailable:** web search (no Serper key) and commondataset.org (HTTP 403 to
  automated requests). The Common Data Set section below is therefore weaker
  evidence than the rest.

## What the repo ingests today

`CollegeScorecardLoader.mapInstitution` reads exactly these cost columns:
`COSTT4_A` (-> `cost_attendance`), `TUITIONFEE_IN`, `TUITIONFEE_OUT`,
`NPT4_{PUB|PRIV}` (-> `net_price`, keyed on `CONTROL`), `NPT41..45_{PUB|PRIV}`
(-> `net_price_q1..q5`), `GRAD_DEBT_MDN`, `MD_EARN_WNE_P10`. **No component
column is read.** RFC 67 says only curated typed columns are retained and "if a
later RFC needs a Scorecard column not curated here, it adds the typed column
and re-ingests" — that is exactly this case.

Adding one column is a well-worn five-file change, precedent `db/schema/0045`:
migration `ALTER TABLE colleges` + `ALTER TABLE colleges_versions` +
`CREATE OR REPLACE FUNCTION
log_college_version()` (picked up by the existing
trigger by name); `College`/`NewCollege` models; `CollegesDao` select/upsert
column lists; one `intInDomainOrNull(...)` line per column in the loader; then
re-run the loader over the same pinned CSV (idempotent upsert on `unit_id`).
Plus `CostField` + `CollegeCostChatTool` to surface it.

**Crucially, the pinned snapshot already contains the data.** The committed
real-data fixture is a verbatim machine-extract of it, and its header is
byte-identical in membership and order to the current 3,308-column release.
`ROOMBOARD_ON`, `ROOMBOARD_OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM`,
`COSTT4_P` and `TUITFTE` are all present with real values (e.g. Auburn
Montgomery: `TUITIONFEE_IN=9700`, `ROOMBOARD_ON=7368`, `BOOKSUPPLY=1500`,
`OTHEREXPENSE_ON=4545`). **No new download is required.**

## The component fields (primary definitions)

From the Institution Data Dictionary, `SOURCE = IPEDS` for all of them:

| Field                               | Data-dictionary name                                                     | Vintage (Most_Recent_Inst_Cohort_Map)           |
| ----------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------- |
| `ROOMBOARD_ON`                      | Cost of attendance: on-campus room and board                             | AY2022-23 (IPEDS DCY2022-23)                    |
| `ROOMBOARD_OFF`                     | Cost of attendance: off-campus room and board                            | AY2022-23                                       |
| `BOOKSUPPLY`                        | Cost of attendance: estimated books and supplies                         | AY2022-23                                       |
| `OTHEREXPENSE_ON` / `_OFF` / `_FAM` | Cost of attendance: other expenses, on-campus / off-campus / with-family | AY2022-23                                       |
| `TUITIONFEE_IN` / `_OUT`            | In-state / out-of-state tuition **and fees**                             | AY2022-23                                       |
| `COSTT4_A` / `COSTT4_P`             | Average cost of attendance, academic-year / program-year institutions    | **AY2021-22**                                   |
| `TUITFTE`                           | Net tuition revenue per FTE (incl. grad students)                        | institutional finance — **not a student price** |

Caveats that matter for copy:

1. **There is no `ROOMBOARD_FAM`.** Students living with family get an "other
   expenses" figure only; housing/food is assumed zero. A three-way
   living-arrangement picker is therefore asymmetric by construction.
2. **`TUITIONFEE_IN/_OUT` is tuition _plus required fees_** — it is not
   "tuition". Label it "tuition & fees" or it is wrong.
3. **`TUITFTE` is a revenue metric including graduate students** — never show it
   as a price.
4. The docs warn the living-cost estimates themselves are unreliable: "research
   by Kelchen, Hosch, and Goldrick-Rab (2014) find considerable variability in
   the accuracy of reported living expenses… public institutions tend to
   underestimate actual living costs"
   ([Institution Data Documentation, p. 46](https://collegescorecard.ed.gov/assets/InstitutionDataDocumentation.pdf)).

**Coverage** (measured on the current release; "present" = non-null, non-`NA`,
non-`PrivacySuppressed`):

|                                      | all 6,273 | `PREDDEG=3` & operating (n=1,944) |
| ------------------------------------ | --------- | --------------------------------- |
| `TUITIONFEE_IN/OUT`                  | 57.6%     | 94.8%                             |
| `BOOKSUPPLY`                         | 52.1%     | 92.5%                             |
| `ROOMBOARD_OFF` / `OTHEREXPENSE_OFF` | 52.3%     | 92.1%                             |
| `ROOMBOARD_ON` / `OTHEREXPENSE_ON`   | 31.1%     | **79.5%**                         |
| `COSTT4_A`                           | 51.0%     | 90.6%                             |

A full on-campus split (tuition+fees, room & board, books, other) renders for
**78.7%** of bachelor-predominant operating institutions; the off-campus split
renders for 89.9%. The on-campus gap is mostly _legitimate_: 296 of those
schools report off-campus figures and no on-campus ones because they have no
residence halls (Amridge, Art Center College of Design, …). "No on-campus
housing" is a real answer, not missing data — but the coach must be able to say
which it is.

## COSTT4_A is **not** the sum of the components — and net price does not subtract this basket

Two independent reasons, both from primary docs:

- **Living arrangements are blended.** "expenses by living arrangement
  (on-campus, off-campus independent, or off-campus with family) are combined
  via a **weighted average** according to the distribution of full-time,
  first-time students" (Institution Data Documentation, p. 12).
- **Different vintage.** In the most-recent file `COSTT4_A` is AY2021-22 while
  every component is AY2022-23 (cohort map above). They are a year apart.

Measured: over 1,504 bachelor-predominant schools reporting all four on-campus
components, `COSTT4_A` matched
`TUITIONFEE_IN + ROOMBOARD_ON + BOOKSUPPLY + OTHEREXPENSE_ON` exactly in **0%**
of public and 1.1% of private cases; median gap −$2,637 (−10.1%) for publics and
−$3,354 (−5.9%) for privates. It does sit inside the
on-campus/off-campus/with-family envelope 97.6% of the time — consistent with it
being the blended figure it claims to be.

Net price uses the **same blended basket**: `NPT4_*` is "the average annual
total cost of attendance (CostT4_A, CostT4_P) … minus the average
grant/scholarship aid" for Title IV recipients (in-state payers only, at
publics). So the split and the net price are on _different_ bases.
**`net_price − tuition_in_state` is not "living costs after aid" and must never
be computed.** Aid is applied to the blend, not to a nameable component.

## IPEDS and the Common Data Set

**IPEDS IC (academic-year file)** is the upstream source and is strictly richer.
`IC2023_AY` carries, for four consecutive academic years each: `CHG1AT*/CHG1AF*`
in-district tuition and fees _separately_, `CHG2*` in-state, `CHG3*`
out-of-state, `CHG4AY*` books and supplies, `CHG5AY*` on-campus food and
housing, `CHG6AY*` on-campus other, `CHG7AY*`/`CHG8AY*` off-campus (not with
family) food+housing / other, `CHG9AY*` off-campus with-family other
([IC2023_AY dictionary](https://nces.ed.gov/ipeds/datacenter/data/IC2023_AY_Dict.zip)).
It is public-domain federal data, one ~300KB zip per year, keyed on the same
`UNITID` — a genuinely cheap second ingest if we ever want the in-district tier
or multi-year trend. **Language note:** NCES itself renamed these fields between
the IC2022 and IC2023 collections, from "On campus, room and board" to "**On
campus, food and housing**", aligning with FAFSA-simplification
cost-of-attendance terminology; the Scorecard dictionary still says "room and
board".

**Common Data Set section G** ("Annual Expenses") gives per-school published
tuition, fees, food and housing, books, transportation and personal expenses,
self-reported by each institution. It has no central machine-readable
distribution — each college publishes its own PDF/XLSX — so ingest means
per-school scraping and per-school licence questions. _Caveat: I could not fetch
commondataset.org (HTTP 403 to automated requests), so this paragraph is uncited
recollection and should be re-verified before it is relied on._ It is clearly
the most expensive option and the only one whose licence is unclear.

## What this means for brief 0003

1. **Ship the split from the pinned Scorecard snapshot.** Add six nullable
   INTEGER columns — `room_board_on`, `room_board_off`, `books_supplies`,
   `other_expense_on`, `other_expense_off`, `other_expense_family` — via the
   `db/schema/0045` pattern and re-run the loader. No new data source, no
   licence question, ~one RFC.
2. **Make the stable/variable line the on-campus AY2022-23 components, not
   `COSTT4_A`.** Stable = `tuition_in_state`/`tuition_out_state` (labelled
   "**tuition & fees**"). Variable = room & board + books & supplies +
   other/personal. For public 4-years the split is the whole story: tuition &
   fees is a median of only **37%** of the on-campus sticker total, with a
   median **$18,220/yr** on the variable side (private nonprofit: 68% /
   $17,814).
3. **Do not add up mixed vintages or mix bases.** Show the component sum as the
   sticker total rather than `COSTT4_A` (they differ by ~10% at publics), keep
   `COSTT4_A`/`NPT4_*` labelled as the blended-living-arrangement figures they
   are, and forbid any tuition-vs-net-price subtraction in prompt and code.
4. **Model "no on-campus housing" as a first-class case,** distinct from "not
   reported" — 15% of bachelor-predominant schools are in it. Extend
   `CostField`/`data_availability` so the coach says "this school has no
   residence halls" rather than "not reported".
5. **Consider adopting "housing & food" over "room & board"** for the variable
   bucket: it is the term the federal collection itself moved to in 2023 and
   reads more plainly to both parents and students. Worth a line in the copy
   research.
6. **Defer IPEDS IC.** It is cheap and public-domain, and it is where
   in-district tuition and multi-year trend would come from — but it buys
   nothing brief 0003 needs today. CDS should stay out of scope until someone
   proves the licence.
