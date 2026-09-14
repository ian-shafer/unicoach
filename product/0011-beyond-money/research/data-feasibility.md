# Data feasibility: what non-money data unicoach could actually serve

Research report for brief **0011 — beyond money**. Scope: what non-money facts
unicoach already holds, what it already downloads but never loads, and what
adjacent public sources would supply.

## Method

**What I did.**

- Read the repo directly: `db/schema/`,
  `college/src/main/kotlin/ed/unicoach/college/`, the chat tools under
  `service/.../coaching/`, the routes under `rest-server/.../routing/`, and
  `bin/fetch-ipeds`, `bin/fetch-scorecard`, `bin/fetch-cds-seed`. Plus
  `product/STATUS.md` lines 1462 and 1588.
- Measured coverage **on the committed source files themselves**:
  `Most-Recent-Cohorts-Institution.csv` (6,273 rows × 3,308 cols),
  `Most-Recent-Cohorts-Field-of-Study.csv` (227,980 × 178),
  `db/seed/ipeds/adm2023.csv`, `db/seed/ipeds/IC2023.csv`. Every "measured"
  percentage is my own count over those files.
- Hit live endpoints: the IPEDS Data Center (HEAD + download of `EF2023D` and
  `OM2023`) and the collegedata.fyi PostgREST corpus that `bin/fetch-cds-seed`
  already uses.
- Delegated a second pass to a subagent, which fetched and parsed the IPEDS
  release schedule and dictionaries, the Scorecard dictionary, the CDS 2025-2026
  form, the Clery bulk zip, the EADA bulk zip and the NSC pages. Raw notes:
  `.scratch/0010-partb-notes.md` (gitignored), with a full URL log.

**What failed / was not available.**

- The `websearch` skill was **unusable in the subagent** (Serper key not
  configured). Everything below therefore comes from direct HTTP fetches of
  primary sources or from the repo — no blog, no secondary summary.
- **My own** first probes of `ope.ed.gov/campussafety` and
  `ope.ed.gov/athletics` found only a JS shell and 404s on guessed API paths.
  The subagent found the real (undocumented) API and downloaded both bulk files,
  so the Clery and EADA sections below **are** verified — by it, not by me.
- No database was running, so "populated" claims about the live DB are inferred
  from the loaders' code and from the committed seed files, not from a `SELECT`.
- The Scorecard's field-of-study **suppression rule is not public**: the
  official `FieldOfStudyDataDocumentation.pdf` says "information about the
  specific suppression rules is not available to the public". The common "n <
  30" claim is therefore **UNVERIFIED**.
- No CDS **2024-2025** form exists on `commondataset.org`; only the live
  2025-2026 cycle is published there. Prior-year forms are UNVERIFIED.
- NSC's fee schedule is UNVERIFIED (the StudentTracker pages returned a stub and
  a 404).

---

## PART A — what the repo already has

### A1. Non-money columns that exist today

`colleges` (`db/schema/0015.create-colleges.sql:34-81`, renamed by
`0059.name-numeric-columns-by-unit.sql:52-67`, money columns **dropped** by
`0094.drop-publisher-money-columns.sql:112-129`). After 0094 what is left is
identity and place (`ipeds_unit_id`, `opeid`, `name`, `city`, `state`, `region`,
`locale`, `latitude`, `longitude`, `control`, `website`) plus exactly four
facts: `undergrad_enrollment_headcount` (Scorecard `UGDS`),
`admission_rate_share` (`ADM_RATE`), `sat_average_equivalent_score` (`SAT_AVG`,
one blended number with no range) and `completion_rate_150pct_4yr_share`
(`C150_4`).

**One outcome measure, one selectivity measure, one test number. That is the
whole non-money outcome surface on `colleges`.**

`college_ipeds`
(`db/schema/0055.create-college-ipeds-and-programs-census.sql:18-67`) is the
richest non-money table: `carnegie_basic` (HD.C21BASIC), `carnegie_size`
(HD.C21SZSET), `cbsa`, `rel_affil` (IC.RELAFFIL), `has_rotc` (IC.SLO5),
`has_study_abroad` (IC.SLO6), `disability_band` /
`registered_disability_percent` (IC.DISAB/DISABPCT), `offers_housing` (IC.ROOM),
`housing_capacity_headcount` (IC.ROOMCAP), `application_fee_usd` (IC.APPLFEEU),
`athletic_assoc` (IC.ASSOC1..6), `football_conf` (IC.CONFNO1), `test_policy`
(ADM.ADMCON7).

`college_programs_census` (same file, lines 92-113): 6-digit CIP, `award_level`,
`awards_count` — **bachelor's first majors only** (`MAJORNUM = 1`,
`AWLEVEL = 5`). `college_programs` (`0015...sql:105-118`) holds CIP offerings
from the Scorecard field-of-study file: code, title, credential level — **and
nothing else from that file**.

`college_admission_factors` (`0054.create-cds-admissions-tables.sql:107-134`):
the full CDS **C7** grid, 18 factors, four ratings. `college_deadlines` (lines
154-184): six rounds, offered flag, month/day. `college_merit_aid` (lines
54-82).

`college_search_index` (`0064.create-search-index-and-subjects.sql:82-201`) is
the derived filter table: 30 columns. Its non-money filter axes are `state`,
`region`, `locale`, `control`, `sector`, `is_active`, `is_four_year`,
`undergrad_enrollment_headcount`, `admission_rate_share`,
`completion_rate_150pct_4yr_share`, `test_policy`, `religious_affiliation`,
`carnegie_class`, `carnegie_size`, `has_rotc`, `has_study_abroad`,
`offers_housing`, `athletic_associations`, `cip_codes`, `subject_slugs`, plus
four percentile columns.

### A2. What is exposed to a user

Of the nine chat tools under `service/.../coaching/`, **only two serve non-money
facts**: `search_colleges`, whose filter list is enumerated at
`college/.../CollegeQueryVocabulary.kt:351-382` (subject, CIP prefix, states,
region, locale, control, enrollment, admission rate, price, completion rate,
test policy, religious affiliation, Carnegie class/size, athletic association,
ROTC, study abroad, housing); and `college_admissions_profile`
(`service/.../admissions/CollegeAdmissionsChatTool.kt:20-40`) — merit aid, the
C7 factor grid, deadlines, each section cited.

REST serves none: `/api/v1/colleges` is name search only
(`rest-server/.../CollegeRoutes.kt:36`), and the other routes are the college
list, the money profile and the vocabulary registry. **No REST route serves a
college attribute payload**, and the shared Family Cost Report page is
money-only.

### A3. What we already download and do NOT load — the headline

**1. `db/seed/ipeds/adm2023.csv` is in the repo and we read exactly ONE
column.** `IpedsLoader.REQUIRED_ADM_COLUMNS` is
`listOf(COL_UNITID, COL_ADMCON7)` (`college/.../IpedsLoader.kt:1031-1032`). The
file has 1,972 rows. Measured against the 2,997 currently-operating four-year
Scorecard institutions, of which 1,727 have an ADM row:

| variable                            | what it is                                         | coverage of those 1,727 |
| ----------------------------------- | -------------------------------------------------- | ----------------------- |
| `SATVR25/50/75`, `SATMT25/50/75`    | SAT section percentiles of enrolled freshmen       | 981 = **56.8%**         |
| `ACTCM25/50/75`, `ACTEN*`, `ACTMT*` | ACT percentiles                                    | 963 = **55.8%**         |
| `SATPCT` / `ACTPCT`                 | share of enrolled freshmen submitting each test    | 66.0% / 65.7%           |
| `APPLCN` / `ADMSSN` / `ENRLT`       | applied / admitted / enrolled — yield is derivable | 100% / 99.4% / 99.2%    |
| `ADMCON1..ADMCON12`                 | what the school **requires**                       | 100%                    |

The `ADMCON` codes are worth naming because two of them are product facts on
their own: 1 HS GPA, 2 class rank, 3 HS record, 4 college-prep programme, 5
recommendations, 6 demonstrated competencies, **7 admission test scores** (the
only one we load), 8 English proficiency, 9 other test, 10 work experience, **11
essay**, **12 legacy status**.

`sat_average_equivalent_score` (one blended number) is what we serve today. The
25th/75th range is the number a family actually reasons with, and it is already
on disk. Note the Scorecard's own mirror is **worse**: `SATVR25` is non-null for
only 31.4% of the four-year universe (measured) against 56.8% of ADM rows.

**2. `Most-Recent-Cohorts-Field-of-Study.csv` is 153 MB on disk, 178 columns,
and we read 4 of them.**
`CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS =
listOf(UNITID, CIPCODE, CIPDESC, CREDLEV)`
(`college/.../CollegeScorecardLoader.kt:1838`). Unread:

`EARN_MDN_4YR`, `EARN_MDN_1YR`, `EARN_MDN_HI_1YR`, `EARN_MDN_HI_2YR` (median
earnings after completion; `HI` = highest-credential filter);
`DEBT_ALL_STGP_EVAL_MDN` (median federal Stafford + Grad PLUS debt at
completion, by field) and `DEBT_ALL_STGP_EVAL_MDN10YRPAY` — **the monthly
payment on a 10-year plan, already computed by the publisher**; `DEBT_ALL_PP_*`
(Parent PLUS); Pell / no-Pell / male / not-male splits of both;
`BBRR2_*`/`BBRR3_*` repayment-status shares by field.

Measured over the **71,664 bachelor's rows** (`CREDLEV = 3`, 2,537
institutions):

| variable                 | numeric            | rest                               |
| ------------------------ | ------------------ | ---------------------------------- |
| `EARN_MDN_4YR`           | 27,617 = **38.5%** | `PS` (privacy-suppressed) or blank |
| `EARN_MDN_HI_2YR`        | 28,092 = **39.2%** | 40,136 rows read literally `PS`    |
| `DEBT_ALL_STGP_EVAL_MDN` | 26,005 = **36.3%** |                                    |

Over **all** credential levels the usable share is much lower: `EARN_MDN_1YR`
52,226 / 227,980 = 22.9%, `DEBT_ALL_STGP_EVAL_MDN` 46,673 = 20.5%.

**2,009 of 2,537 institutions (79%) have at least one bachelor's programme with
a median-earnings figure**, median 8 programmes with a figure out of a median 25
bachelor's programmes offered. 311 distinct 4-digit CIP families are covered. So
this is "some of your majors, at most schools" — not a universal fact — and the
absence is `PS`, a stated act by the publisher, which is exactly the shape RFC
177/179's honesty seam already handles.

**Vintage matters and is ugly** (from the dictionary's own
`FieldOfStudy_Cohort_Map`): `EARN_MDN_4YR` pools AY2017-18 and AY2018-19
completers measured in CY2022 and CY2023, in 2024 dollars — the freshest.
`EARN_MDN_HI_2YR` pools AY2014-15 and AY2015-16 measured in CY2017 and CY2018,
in 2019 dollars — **8-9 years stale**. `CIPCODE`/`CREDLEV` describe AY2022-23
completions under the CIP2020 edition. RFC 183 landed because two Scorecard year
stamps were wrong by two years; this file has six different vintages in one row
and would need the same per-column pinning.

**3. `db/seed/ipeds/IC2023.csv` — 138 columns, we read 8.** Unused and **100%
coded** across the 2,723 four-year institutions with an IC row:

`CALSYS` (semester / quarter / trimester / 4-1-4 / continuous) · `STUSRV2`
academic or career counselling · `STUSRV3` employment services · `STUSRV4`
placement services for completers · `VET1..VET5` veteran support (Yellow Ribbon,
Post-9/11 GI Bill, credit for military training) · `CREDITS2/3/4`
life-experience, dual and AP credit · `DISTCRS`/`DISTPGS` distance courses and
fully-distance programmes · `SLO7` weekend-evening college · `SLOA`
undergraduate research · `SLO8` teacher certification · `SLOB` transition
programme for students with intellectual disabilities · `TUITPL` payment plans ·
`LIBRES1..6` library services. (`SLO*` is IPEDS's **special learning
opportunities** block, not student services.)

Partly coded: `ALLONCAM` 86.9% (all students required to live on campus),
`OPENADMP` 87.3% (open admission), `ROOMCAP` 66.1%, `MEALSWK` 34.2%.

**4. `db/seed/ipeds/C2023_a.csv` — completions.** Loaded with the filter
`MAJORNUM = 1` and `AWLEVEL = 5` only (`0055...sql:88-91`). The same file also
carries other award levels (associate 3, master's 7, doctorate 17) and the full
race/sex breakdown. A "does this school actually graduate people in my major,
and does it feed a master's" answer needs no new download.

**5. The Scorecard institution file already carries `STUFACR`** — the IPEDS
student-to-faculty ratio, re-published. Measured on our own committed file:
**2,476 of 2,997 four-year operating institutions = 82.6%**. We do not read it.

**6. `bin/fetch-cds-seed` reads a corpus of WHOLE CDS documents and normalises
four fact groups.** Verified live against the corpus's own PostgREST API
(`https://api.collegedata.fyi/rest/v1/cds_fields`, anon key scraped from
`https://www.collegedata.fyi/api` exactly as `bin/fetch-cds-seed:668-675` does):

- **4,810** extracted, non-removed documents covering **706 distinct IPEDS
  institutions**, cycles 2016-17 through 2025-26 (2024-25: 432 docs; 2025-26:
  242 and rising).
- Every CDS section is present as rows, not just the ones we take. Row counts by
  `field_id` prefix: A 12,904 · B 82,599 · C 76,989 · D 12,148 · E 10,789 · F
  20,840 · G 10,634 · H 47,783 · I 22,791 · J 17,950.
- A companion table `cds_field_definitions` gives `field_label`, `section` and
  `subsection` per `schema_version` — 1,083 definitions for `2024-25`. **We do
  not use it**; `bin/fetch-cds-seed` hardcodes its field IDs.

Coverage measured per field (distinct IPEDS ids with
`value_status = 'reported'`, 2024-25 and 2025-26 schema versions):

| field             | label                                                                    | distinct institutions |
| ----------------- | ------------------------------------------------------------------------ | --------------------- |
| `I.201`           | Fall 2024 student-to-faculty ratio                                       | **278**               |
| `I.101`           | total instructional faculty                                              | 371                   |
| `I.301..I.308`    | undergraduate **class-size distribution** (2-9, 10-19, … 100+, total)    | 337                   |
| `F.104`           | % who live in college-owned / operated / affiliated housing              | 327                   |
| `F.102` / `F.103` | % of men in fraternities / women in sororities                           | 269                   |
| `F.201..F.221`    | activities offered (21 named: marching band, Model UN, radio station, …) | present               |
| `F.401..F.413`    | housing options (theme housing, living-learning communities, …)          | present               |
| `B.2201`          | retention rate                                                           | 74                    |

Our committed seed today: `db/seed/cds/admission-factors.csv` 369 rows,
`merit-aid.csv` 352, `deadlines.csv` 831, `aid_policy.csv` 3,496.

**7. Our IPEDS pin is one collection year behind.** `bin/fetch-ipeds:201`
declares `SURVEY_YEAR = 2023`. The 2024-25 Winter release (ADM, GR, SFA, OM)
went out **2025-12-09** and the Spring release (EF, S, AL) **2026-01-06**;
`ADM2024` exists (1,956 rows) and `EF2024D` exists (5,578 rows). Note the URL
path also moved: `https://nces.ed.gov/ipeds/complete-data-files/<FILE>.zip` is
the live pattern, and `…/ipeds/datacenter/data/<FILE>.zip` now 404s for most
files (HD/IC/EFFY/C_A still resolve there, which is why our fetcher still
works).

---

## PART B — public sources, by axis

### Licences (checked first, because two of these are not open)

| source                             | status                                                   | evidence                                                                                                                                                                                                                                                                                                         |
| ---------------------------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **IPEDS / NCES**                   | US federal, **public domain**                            | "all information on the U.S. Department of Education's IES website … is in the public domain and may be reproduced, published, linked to, or otherwise used without IES' permission" — https://nces.ed.gov/help/webmaster.asp . There is no separate IPEDS licence page (`/ipeds/about-ipeds/data-policy` 404s). |
| **College Scorecard**              | public; data.gov metadata declares **CC-BY**             | https://catalog.data.gov/dataset/college-scorecard → `license: http://www.opendefinition.org/licenses/cc-by`. Attribute "U.S. Department of Education, College Scorecard, \<release date\>".                                                                                                                     |
| **Clery / Campus Safety**          | US Dept of Education (OPE) → public domain, free, no key | https://ope.ed.gov/campussafety/ . No dedicated terms page — relies on the ED default.                                                                                                                                                                                                                           |
| **EADA (athletics)**               | same                                                     | https://ope.ed.gov/athletics/ . No dedicated terms page.                                                                                                                                                                                                                                                         |
| **Common Data Set**                | **NOT public domain**                                    | https://commondataset.org/ — a private collaborative (College Board, Peterson's, U.S. News). No open licence, no bulk data, no API; each institution self-publishes its own filled-in form.                                                                                                                      |
| **National Student Clearinghouse** | **proprietary**                                          | "©2026 National Student Clearinghouse. All Rights Reserved." Aggregate reports are free to read; institution-level data is contract-only.                                                                                                                                                                        |

The CDS row is the one that should be read twice: **unicoach already ships CDS
data** through `bin/fetch-cds-seed`, and the licence position is "third-party
content with no reuse grant", not "public federal data". That is a standing
exposure, not something this brief creates — but any slice that widens the CDS
take widens it.

### Programs / majors offered

- **IPEDS `C_A`** (`C2023_a.csv`, **already in the repo**): `CIPCODE`,
  `AWLEVEL`, `MAJORNUM`, `CTOTALT` + race/sex counts. Latest `C2024_A` = 307,707
  rows, 5,869 institutions. Plus **Scorecard `CIP01BACHL..CIP54BACHL`** (38
  columns, one per 2-digit CIP family, offered/degree/distance-only) — already
  on disk, unread. Verdict: **fully covered by files we hold.**

### Earnings and debt BY FIELD OF STUDY

- **Scorecard field-of-study file** — see A3.2. Grain (UNITID, CIPCODE 4-digit,
  CREDLEV). Source is IRS/Treasury earnings matched to NSLDS debt. Dictionary:
  https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx ;
  documentation `…/files/FieldOfStudyDataDocumentation.pdf`.
- **Already on disk.** Coverage 36-39% of bachelor's rows; suppression code
  `PS`, rule undisclosed; six different cohort vintages in one row.
- Alternative worth naming, not recommended now: Census LEHD **PSEO** (earnings
  by field from state wage records) — better wage data, but coverage is only the
  states that partnered.

### Completion / retention / transfer-out

- **Retention:** IPEDS `EF2023D.RET_PCF` (full-time) and `RET_PCP` (part-time).
  I downloaded and measured it: 5,646 rows, `RET_PCF` non-null 88.6% overall and
  **84.4%** of the 2,468 four-year institutions present. Latest is `EF2024D`
  (5,578 rows, `RET_PCF` 4,947, `RET_PCP` 2,972). Scorecard mirrors it as
  `RET_FT4` (**68.6%** measured on our file; cohort Fall 2023).
- **Completion:** already loaded (`C150_4`, **75.3%** measured; cohort Fall
  2018). `C150_4_POOLED` + `POOLYRS` widen it for small schools.
- **Transfer-out:** Scorecard `TRANS_4` — **75.3%** measured (cohort Fall 2018,
  so ~7 years old); `TRANS_4_POOLED_SUPP` 71.6%. **Already on disk, unread.** In
  raw IPEDS it is `GR2024` `GRTYPE = 4` and the derived `DRVGR2024.TRRTTOT`.
- **The 8-year, everybody-counted view:** IPEDS **Outcome Measures**. I
  downloaded `OM2023`: 47,342 rows, 54 columns; the 2024 file is 47,029 rows
  over 3,590 institutions. `OMAWDP8` = % awarded anything within 8 years;
  **`OMENRAI`/`OMENRAP` = enrolled subsequently at ANOTHER institution**.
  `OMCHRT` splits full-time/part-time × first-time/non-first-time × Pell — **the
  only IPEDS component that covers part-time and transfer-in students at all.**
  Scorecard mirrors it: `OMAWDP8_ALL` **80.1%** measured,
  `OMAWDP8_FTFT_POOLED_SUPP` 73.5%.
- **NSC:** no free institution-level data. Only national/state/sector aggregate
  reports, plus free supporting workbooks (enrolment coverage by state/sector
  2017-2024, an NSC-code↔UNITID crosswalk, FERPA block rates). StudentTracker
  and the Postsecondary Data Partnership are fee/contract. **Do not plan on
  it.**

### Admissions selectivity and admitted-student profile

- **IPEDS ADM** — see A3.1. Already on disk (2023); `ADM2024` available.
- **GPA:** no federal source publishes an admitted-student GPA distribution. CDS
  **C12** does — corpus only, a few hundred schools. Everything else on the
  market is a vendor estimate. Do not fabricate one.

### Class size, faculty

- **There is NO federal class-size source.** The subagent checked the IC, EF, S
  and AL dictionaries: IPEDS collects no class-section or class-size data. Every
  "% of classes under 20" figure in the market comes from **CDS I-3** or a
  publisher survey. CDS I-3 gives two tables — class **sections** and
  **subsections** — counted in bands 2-9 / 10-19 / 20-29 / 30-39 / 40-49 / 50-99
  / 100+ / total, excluding distance learning, non-credit and one-to-one
  instruction. Corpus coverage: **337 institutions**.
- **Student-faculty ratio:** IPEDS `EF2023D.STUFACR` — **100.0%** of the 2,468
  four-year institutions in that file (median 13, quartiles 10 / 17, max 132),
  and the Scorecard re-publishes it at **82.6%** of our four-year universe on a
  file already in the repo.
- **Faculty composition:** CDS `I.101..I.130` (371 institutions), or IPEDS HR
  (`S2024_OC`, 178,024 rows / 5,837 institutions; salaries in `SAL2024_IS`).

### Campus setting / size / housing

- Setting and size: `HD.LOCALE`, `HD.C21SZSET` — **already loaded**. The 2024
  file adds the **Carnegie 2025** classification family (`CARNEGIEIC`,
  `CARNEGIESIZE`, `CARNEGIERSCH`, …), which our `carnegie_2021_*` codebooks do
  not know about.
- Housing offered + capacity: `IC.ROOM` / `ROOMCAP` — **already loaded**. What
  is missing is the **share who live on campus**: `IC.ALLONCAM` (86.9%) is a
  rule, not a share; the share is CDS `F.104` (327 institutions).

### Religious affiliation, athletics

- Both **already loaded** from IPEDS IC and already search filters. The
  Scorecard mirror of `RELAFFIL` is only 27.9% measured — IPEDS IC is the right
  source and is the one we read.
- **EADA** (https://ope.ed.gov/athletics/, same undocumented API as Clery):
  `EADA_2024-2025.zip`, 12 MB. `instLevel.xlsx` = **2,037 rows, one per
  institution**; `schools.xlsx` = **18,045 rows, one per sport per
  institution**, 37 sport codes — division/classification, participation by sex,
  coach counts and salaries, athletic aid, expenses, revenue. Lag ~10-12 months.
  Public domain.

### Campus safety (Clery)

- Host is **`ope.ed.gov/campussafety/`** (`ops.ed.gov` does not resolve). There
  **is** a working undocumented JSON API: `api/dataFiles/fileList` lists all 61
  bulk files; `api/dataFiles/file?fileName=Crime2025EXCEL.zip` returns the zip
  (38.2 MB, verified download).
- Latest **"Crime2025" covers calendar years 2022-2024**, prepared 2026-04-27 →
  **lag ≈ 15-16 months**. 5 geographies (on-campus, residence hall, non-campus,
  public property, total) × 5 measure families (offences, arrests, disciplinary
  referrals, hate crimes, VAWA), plus fire safety and unfounded reports.
- **Grain is one row per CAMPUS, not per institution**: `UNITID_P` + `BRANCH`.
  `Oncampuscrime222324.xls` is 10,111 rows × 49 columns; needs aggregation.
- The interpretation trap: reported-crime counts rise with **reporting
  quality**, so a school that reports honestly looks worse than one that does
  not.

### Student services, veteran support, calendar

- IPEDS `IC` `STUSRV2/3/4/9`, `VET1..VET5`, `CALSYS`, `CREDITS2/3/4`,
  `DISTCRS`/`DISTPGS`, `TUITPL`, `LIBRES1..6` — **all ~100% coded, all already
  on disk, none loaded.**

### Student-body composition

- Scorecard `UGDS_WHITE/BLACK/HISP/ASIAN/…` measured **82.8%**; `PPTUG_EF`
  (part-time share) 82.1%; `AGE_ENTRY` 86.5%. IPEDS `EF2024A` is the primary
  source, and `DRVEF2024` publishes the shares ready-made, including **`EFUGTRN`
  (transfer-in)** and in-state share of first-time students.
- Judgement call, not a data question: race shares are easy to serve and easy to
  serve badly. Flag for Ian; do not assume.

---

## Implications for unicoach

Ranked by (value × feasibility). "New DDL" is called out because it is the
expensive half.

1. **Read `STUFACR` out of the Scorecard institution file we already ingest —
   82.6% of the four-year universe, zero new download, zero new fetcher.**
   Highest confidence on the page: one column added to the loader's read list,
   one column on `colleges` or `college_ipeds`, one search filter, one payload
   field. It answers "how big are the classes" _approximately_ for 2,476 schools
   where the CDS answers it _exactly_ for 337. If a slice also wants `RET_PCF`,
   take both from IPEDS `EF2024D` instead (one 94 KB file, `STUFACR` non-null
   for 5,577 of 5,578 rows). **New DDL: one or two nullable columns.**

2. **Read the SAT/ACT 25th-75th percentiles, `APPLCN/ADMSSN/ENRLT` and
   `ADMCON1..12` out of `adm2023.csv`, which is already in the repo.** We serve
   one blended `sat_average_equivalent_score`; families reason in ranges ("am I
   in the middle 50%?"). Coverage 56.8% for the ranges, ~100% for the counts and
   the requirement flags. Two of the requirement flags are conversation-changing
   on their own — `ADMCON11` essay and **`ADMCON12` legacy status**. The file is
   already fetched, pinned and provenance-stamped. **New DDL: a
   `college_admission_profile` table, or ~10 columns on `college_ipeds`.** High
   confidence.

3. **Load retention and the 8-year outcome rate; stop serving `C150_4` alone.**
   `RET_PCF` (84.4% of four-year institutions measured) answers "do freshmen
   come back". `OMAWDP8_ALL` (**80.1%**, already on disk) counts part-time and
   transfer-in students, whom `C150_4` silently excludes — and those are exactly
   the students a coaching app reaches. Add `TRANS_4` (75.3%, already on disk):
   "about a quarter of students here transfer out" is a decision-grade fact we
   cannot currently say. Caveat to carry: `C150_4` and `TRANS_4` describe a
   **Fall 2018** cohort while `RET_FT4` describes **Fall 2023** — four different
   vintages beside each other, which is precisely the RFC 183 failure mode.
   **New DDL: 3-4 nullable columns, each needing its own year stamp.** High
   confidence.

4. **Load the Scorecard field-of-study earnings and debt.** 153 MB already on
   disk, 178 columns, 4 read. `EARN_MDN_4YR` + `DEBT_ALL_STGP_EVAL_MDN` +
   `DEBT_ALL_STGP_EVAL_MDN10YRPAY` turn "what does college cost" into "what does
   _this major at this school_ cost and return" — the biggest product step
   available with no new download. **Three cautions, all load-bearing:** it is
   money-adjacent rather than non-money; 61.5% of bachelor's rows are `PS` and
   the suppression rule is _not published_, so we cannot even explain the gap
   precisely; and the six vintages in one row (best `EARN_MDN_4YR` = 2022-23
   earnings; worst `EARN_MDN_HI_2YR` = 2017-18 earnings) demand the same
   per-column year pinning RFC 183 built. The right shape is the existing one: a
   fact row with `FigureStatus` + assurance tier, not raw columns. **New DDL: a
   programme-level fact table.** High confidence in the value, medium in the
   effort.

5. **Widen `bin/fetch-cds-seed` to take sections F (student life) and I (faculty
   and class size) from the corpus it already reads.** Class-size distribution,
   % living on campus, % in Greek life, activities offered, housing options,
   student-faculty ratio as the school itself states it. No new source, no new
   credential, no new fetcher — the corpus is already paged and
   `cds_field_definitions` already exists. **Two catches.** Coverage is 270-370
   institutions of 6,273, so it belongs in `college_admissions_profile`-style
   _answers_, never as a `search_colleges` filter. And the CDS is **not** open
   data (see the licence table) — widening the take widens a standing exposure,
   which is an Ian call, not an engineering one. **New DDL: one or two CDS fact
   tables.** High confidence technically; flagged commercially.

6. **Load the IPEDS `IC` support block — `STUSRV2/3/4`, `VET1..VET5`, `CALSYS`,
   `CREDITS2/3/4`, `DISTCRS`/`DISTPGS`, `ALLONCAM`, `TUITPL` — all already on
   disk, all ~100% coded.** Individually small facts; collectively they answer
   "will this school actually support me" for first-generation, veteran,
   transfer and commuter students, an underserved segment for a coach product.
   **New DDL: ~12 boolean / small-int columns on `college_ipeds`.** Medium-high
   confidence; the risk is a long attribute list nobody reads.

7. **Widen `college_programs_census` past bachelor's first majors.** The
   `MAJORNUM = 1 / AWLEVEL = 5` filter is a deliberate soundness choice
   (`0055...sql:88-91`), but it means we cannot say "142 students graduated in
   this major last year" for anything but a bachelor's, cannot see double
   majors, and cannot answer "does this school have a master's in it". The file
   is already in the repo and the unique key already includes `award_level`, so
   **no new DDL** — but the `MAJORNUM` filter must stay and second majors need
   their own axis. Medium confidence.

8. **Bump the IPEDS pin from 2023 to 2024 as part of whichever slice lands
   first.** `ADM2024` and `EF2024D` are published; the URL path moved to
   `/ipeds/complete-data-files/`; and `HD2024` carries the new **Carnegie 2025**
   classification columns our `carnegie_2021_*` codebooks cannot represent.
   Doing this inside another slice is cheap; discovering it later is a
   re-ingest. **No new DDL for ADM/EF; Carnegie 2025 would need new codebook
   tables.** Medium-high confidence.

9. **Do NOT build campus safety (Clery) yet — but the "we cannot get the data"
   objection is dead.** The API works, the bulk file is 38 MB, coverage is every
   Title IV campus, lag is ~16 months. The reasons to decline are product
   reasons: the grain is per-campus and needs aggregation, and the metric is
   structurally misleading — better reporting looks like more crime. A product
   whose brand is figure honesty (briefs 0003, 0008) should not ship the one
   axis where the honest presentation is a caveat longer than the number.
   Revisit only on an explicit Ian decision. High confidence.

10. **Do NOT plan on NSC, and do not serve an admitted-student GPA outside the
    CDS.** NSC institution-level data is contract-only and copyrighted. No
    federal source publishes admitted GPA. Any GPA we showed for the other
    ~5,900 schools would be a vendor estimate or a guess, and this codebase has
    twice landed corrections for figures whose provenance was wrong (RFC 177,
    RFC 183). High confidence.

11. **EADA is real, cheap and probably not worth a slice.** 2,037 institutions,
    18,045 sport rows, public domain, ~11-month lag, one 12 MB download. It
    would let a recruited athlete filter by division and sport, which today they
    cannot (`athletic_associations` is association-level only). But that is a
    narrow segment. Rank it below everything above it. Medium confidence.

12. **Structural note for the spec, not a build item.** Seven of the eleven
    items above are "read a column from a file we already fetched". The
    bottleneck for non-money data is not acquisition — it is that `colleges` /
    `college_ipeds` grew as _whatever the loader happened to read_, and there is
    no non-money analogue of `price_figures`: a fact row carrying its source,
    its year, its status and its population. Items 3 and 4 each carry three or
    more different publisher vintages in one answer, which is the exact
    condition RFC 183 was written to survive. If more than two of these land,
    build that table first. Otherwise the next brief is "drop the publisher
    shape" again.
