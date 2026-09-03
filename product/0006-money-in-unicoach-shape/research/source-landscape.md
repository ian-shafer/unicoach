# Source landscape — what can fill a canonical money schema, and with what exact semantics

Brief 0006 research report. Question: for each realistic external source, what
money facts does it carry, at what granularity/vintage, under what missing-value
semantics, and what does that imply for FILLS / PARTIAL / CANNOT against
unicoach's canonical money concepts?

## Method

- Read `product/0006-money-in-unicoach-shape/brief.md`, `bin/ingest-colleges`,
  `bin/fetch-cds-seed`, `db/seed/*/PROVENANCE.json`, and the Scorecard loader to
  ground what is pinned today.
- Downloaded and read primary documentation directly: the College Scorecard
  Institution Data Documentation PDF (June 2024,
  <https://collegescorecard.ed.gov/assets/InstitutionDataDocumentation.pdf>),
  the IPEDS Stata label files for IC2023 (already pinned in-repo at
  `db/seed/codebooks/IC2023_Stata.zip`), IC2023_AY and SFA2223 (fetched from
  `nces.ed.gov/ipeds/datacenter/data/`), and the live collegedata.fyi PostgREST
  API (the corpus `bin/fetch-cds-seed` already uses) for the CDS section-H field
  inventory and its `value_status` enum.
- **Tool gap, stated honestly:** the `websearch` skill is not configured (no
  Serper key), and `commondataset.org` returns HTTP 403 to scripted fetches. CDS
  section H is therefore cited from the collegedata.fyi `cds_field_definitions`
  table (schema_version 2024-25), which mirrors the official template
  field-for-field, not from the CDS PDF itself. All other claims are from
  primary docs fetched during this session or from repo files (file:line).

---

## 1. College Scorecard, institution-level

**What the repo pins today.** The Scorecard CSV pair (`institution.csv` +
`fields.csv`) is the primary `colleges` loader (`bin/ingest-colleges:9-16`). The
loader reads `COSTT4_A`, `TUITIONFEE_IN`, `TUITIONFEE_OUT`, `NPT4`/`NPT41..45`
(`_PUB`/`_PRIV` per control), `ROOMBOARD_ON/OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_*`
(`college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt:1281-1307,1469-1493`).

**Money variables** (all per Institution Data Documentation, June 2024, "Costs"
and "Financial Aid" sections):

- `COSTT4_A` / `COSTT4_P` — average annual cost of attendance (tuition+fees,
  books+supplies, living expenses) for **full-time, first-time, Title IV-aided**
  undergrads; `_A` academic-year institutions, `_P` program-year (annualized).
  Living-arrangement variants are **blended away**: "expenses by living
  arrangement … are combined via a weighted average according to the
  distribution of full-time, first-time students utilizing those options". Not
  reported before 2009.
- `TUITIONFEE_IN` / `TUITIONFEE_OUT` / `TUITIONFEE_PROG` — published tuition +
  required fees. The doc says `TUITIONFEE_IN` is provided "for **in-district**
  students"; "Some institutions have different tuition and fees for in-state
  students that are not reflected in this metric." So the Scorecard's "IN"
  figure collapses in-district and in-state into one number — the residency tier
  the canonical schema needs is already lost at this source. Not reported
  before 2000.
- `BOOKSUPPLY`, `ROOMBOARD_ON`, `OTHEREXPENSE_ON`, `ROOMBOARD_OFF`,
  `OTHEREXPENSE_OFF`, `OTHEREXPENSE_FAM` — component estimates for FTFT students
  at academic-year institutions. (Note: no `ROOMBOARD_FAM` — with-family
  housing/food is structurally absent.)
- `NPT4_PUB` / `NPT4_PRIV` — average net price (COA minus
  federal+state+institutional grants/scholarships) for FTFT Title IV-aided
  undergrads; **for publics, limited to students paying in-state tuition** (the
  RFC 157 defect's origin). `NPT41..NPT45_{PUB,PRIV,PROG,OTHER}` — the same by
  income quintile: $0–30k / 30,001–48k / 48,001–75k / 75,001–110k / 110k+.
  `NUM[1-5]_{PUB,PRIV}` give the underlying counts. Not before 2009.
- `PCTPELL_DCS` (all degree/cert-seeking UGs) and `FTFTPCTPELL` (FTFT subset) —
  Pell share. `PCTFLOAN_DCS` / `FTFTPCTFLOAN` — federal-loan borrowing share.
- Debt: `DEBT_MDN` (median cumulative federal-loan debt at separation), split
  `GRAD_DEBT_MDN`/`WDRAW_DEBT_MDN`, by FAFSA income (`LO/MD/HI_INC_DEBT_MDN`),
  dependency, Pell status, gender; PLUS loans excluded, Perkins excluded since
  the 2017-18 file. Debt and earnings figures are pooled across two years to
  reduce volatility.
- Earnings: `MD_EARN_WNE_*` families (Treasury), differentially-private noise
  added.

**Granularity/keys.** One row per IPEDS **UNITID**; NSLDS/Treasury elements are
rolled up to the 6-digit OPEID and copied to every UNITID that shares it (doc,
"Accuracy and Privacy"). Population varies per element (FTFT Title IV-aided for
cost/net price; all-UG for `_DCS` shares; federal borrowers for debt) — the
"comparison basis" the brief names is per-variable, not per-file.

**Vintage/cadence.** Annual data files 1996-97 onward plus a "Most Recent
Cohorts" featured file; cost elements are IPEDS pass-throughs one collection
behind (SFA covers the year prior to the IPEDS collection year). Doc version in
hand: June 2024. Cohort maps per year are published with the data.

**Missing-value semantics — two markers only, and they mean different things:**

- `PrivacySuppressed` — only for NSLDS/Treasury elements: "those data that do
  not meet reporting standards are shown as PrivacySuppressed" (doc, Accuracy
  and Privacy). So for debt/earnings/ repayment, suppression IS distinguishable
  from absence.
- `NULL` — everything else: not reported, not applicable (e.g. `NPT4_PUB` at a
  private), and not-collected-that-year all collapse to NULL. For the
  IPEDS-derived cost/net-price variables the Scorecard **cannot** distinguish
  "institution did not report" from "not applicable".
- **The repo currently erases even this distinction**: "Scorecard
  PrivacySuppressed/NULL sentinels fall out as null"
  (`CollegeScorecardLoader.kt:1239`, again at `:1307`). A canonical layer should
  stop doing that — `PrivacySuppressed` is a genuine third state we already
  receive and discard.

**Licence/access.** US federal government work — public domain (17 U.S.C. §105);
listed on data.gov (<https://catalog.data.gov/dataset/college-scorecard>). CSV
download + a documented REST API (api.data.gov key). Institution-level annual
CSVs are on the order of 150–200 MB each (not re-measured this session; the repo
ingests a pinned snapshot rather than the live API).

---

## 2. IPEDS IC — institutional characteristics and charges

IPEDS IC is **two files per year**. The repo pins only the first.

**(a) `IC<yyyy>.csv` — pinned today** (`db/seed/ipeds/IC2023.csv`, sha256 in
`db/seed/ipeds/PROVENANCE.json`; loaded into `college_ipeds` by
`bin/ingest-colleges` RFC 144). Money-adjacent variables (labels from the pinned
`db/seed/codebooks/IC2023_Stata.zip` member `IC2023.do`): `TUITVARY` ("Tuition
charge varies for in-district, in-state, out-of-state students"), `ALLONCAM`
(FTFT required to live on campus), `ROOM`/`ROOMCAP` (institution-controlled
housing + capacity), `BOARD`/`MEALSWK`, `ROOMAMT`/`BOARDAMT`/`RMBRDAMT` (typical
academic-year housing / food / combined charges), `APPLFEEU` (undergrad
application fee), `TUITPL1-4` (tuition guarantee / prepaid / payment-plan
offerings).

**(b) `IC<yyyy>_AY.csv` — the charges file, NOT pinned yet** (brief 0003 D7's
deferred source). From `IC2023_ay.do` (fetched
<https://nces.ed.gov/ipeds/datacenter/data/IC2023_AY_Stata.zip>):

- `TUITION1/2/3` + `FEE1/2/3` + `HRCHG1/2/3` — **average** tuition, required
  fees, and per-credit- hour charge for full/part-time undergrads at the three
  residency tiers: **1 = in-district, 2 = in-state, 3 = out-of-state** (5/6/7 =
  the same for graduates). This is the only bulk source where the three
  residency tiers are separate first-class variables.
- `CHG1/2/3 AT/AF/AY 0-3` — **published** in-district / in-state / out-of-state
  tuition (`*AT*`), fees (`*AF*`), and combined tuition-and-fees (`*AY*`), each
  carried for **four academic years in one file** (suffix 0-3 = 2020-21 …
  2023-24 in the 2023 file), plus guaranteed-increase percents (`CHG1TGTD`
  etc.).
- `CHG4AY0-3` books and supplies; `CHG5/6AY0-3` on-campus food-and-housing /
  other; `CHG7/8AY0-3` off-campus (not with family) food-and-housing / other;
  `CHG9AY0-3` off-campus with-family other expenses. These are exactly
  unicoach's three arrangements — and note IPEDS itself now says "food and
  housing" (brief 0003's vocabulary), while the Scorecard still says ROOMBOARD.

**Granularity/keys.** One row per UNITID per collection year; charges are
institution-level published/average prices (no population cohort — these are
sticker facts, not aided-student averages). ~6,000 institutions
(`PROVENANCE.json`: IC2023 = 6,049 rows).

**Vintage/cadence.** IC is collected each fall **for the current academic year**
— the freshest sticker prices in any federal source (SFA and Scorecard trail it
by 1–2 years). Provisional data first, then revised `_RV` members inside later
archives — the repo already refuses `_RV` (`bin/ingest-colleges:9-19` help
text). Complete data files go back to the 1980s; the `chg*` 4-year window means
one file already carries short history.

**Missing-value semantics — the best of any source; three-way distinguishable.**
Every money variable has a paired imputation column (`X` + name: `XTUIT2`,
`XCHG3AY3`, `XROOMAMT` …). The code list, verbatim from the pinned `IC2023.do`
(comment block "possible values for the item imputation field variables"): **A**
Not applicable; **B** Institution left item blank; **C** Analyst corrected
reported value; **D** Do not know; **G** Data generated from other data values;
**H** Value not derived – data not usable; **J** Logical imputation; **K** Ratio
adjustment; **L** Group Median imputation; **N** Nearest Neighbor imputation;
**P** Carry Forward imputation; **R** Reported; **Y** (specific
professional-practice program); **Z** Implied zero. Categorical variables
additionally use reserved values `-1` "Not reported" / `-2` "Not applicable"
(e.g. `label_disab` in `IC2023.do`). So IPEDS distinguishes **reported (R/C) vs
institution-did-not- report (B/D/H) vs not-applicable (A) vs NCES-imputed
(G/J/K/L/N/P/Z)** — and the imputed codes matter: an imputed value prints like a
real dollar figure. The canonical layer should carry the flag, and arguably
treat carried-forward/imputed prices as its own provenance state rather than
"reported".

**Licence/access.** NCES/federal — public domain; zip per survey per year from
`nces.ed.gov/ipeds/datacenter/data/` (IC2023_AY.zip = 310 KB measured this
session). The repo's `bin/fetch-ipeds` + PROVENANCE pattern extends to it
directly.

---

## 3. IPEDS SFA — student financial aid

**Not pinned today.** One zip per aid year (`SFA2223.zip` = 1.9 MB, measured;
covers aid year 2022-23, released in the 2023-24 collection). Variables from
`sfa2223.do` (fetched
<https://nces.ed.gov/ipeds/datacenter/data/SFA2223_Stata.zip>); every one has an
`X` imputation twin with the same code list as IC.

- **Aid by source, FTFT cohort** — `AGRNT_N/P/T/A` (any fed/state/local/inst
  grant), `FGRNT_*` (federal grants), `SGRNT_*` (state/local), `IGRNT_*`
  (institutional), `PGRNT_*` (Pell, FTFT), `LOAN_N/P/T/A` (student loans, FTFT),
  `ANYAIDN/P`.
- **All-undergrad and degree-seeking cohorts** — `UAGRNT*`/`UDGAGRNT*` (any
  grant), `UPGRNT*`/`UDGPGRNT*` (Pell: number, percent, total, average),
  `UFLOAN*`/`UDGFLOAN*` (federal loans). This is where Scorecard `PCTPELL_DCS`
  comes from.
- **Net price** — `NPIST0-2` (publics: students awarded grant/scholarship aid,
  limited to those paying in-district/in-state rates) and `NPGRN0-2` (privates),
  each carrying **three years** in one file; `NPIS41-45{0,1,2}` — net price by
  the five income bands for Title IV-aided students (the direct source of
  Scorecard NPT41-45).
- **Income-band grant detail with arrangement counts** — `GIS4*` family: numbers
  living on-campus / off-campus-with-family / off-campus-not-with-family /
  unknown (`GIS4ON/WF/OF/UN`), and per-income-band counts/totals/averages of
  grant aid. The arrangement-population weights the Scorecard blends into COSTT4
  are published here, unblended.
- **Residency-split populations** — `SCFA11N` etc.: number of fall-cohort
  students paying in-district / in-state / out-of-state rates. This lets a
  canonical layer say what share of a public's students each residency price
  actually applies to.

**Granularity/keys.** UNITID × aid year; multiple explicit cohorts (FTFT
financial-aid cohort `SCUGFFN`, all-UG `SCUGRAD`, fall cohort `SCFA*`) — the
population is named per variable family.

**Vintage/cadence.** Annual; aid year trails the collection by one (~18-month
lag vs IC sticker prices). Provisional → revised (`_RV`). History to 1999-2000
in the complete data files.

**Missing-value semantics.** Same three-way IPEDS imputation system as IC
(A/B/…/R/Z). This is the only **aid** source that distinguishes not-reported
from not-applicable.

**Licence/access.** Public domain, same fetch pattern.

---

## 4. Common Data Set — section H (via the collegedata.fyi corpus the repo already uses)

**What the repo pins today.** `bin/fetch-cds-seed` pulls the open
collegedata.fyi corpus (PostgREST API, `https://api.collegedata.fyi/rest/v1`,
attribution header required — `bin/fetch-cds-seed:1-60`) and commits three
derived CSVs under `db/seed/cds/`; only **H2A merit aid** (plus C7 factors,
C21/22 deadlines) is extracted → `college_merit_aid`
(`bin/ingest-colleges:52-60`). The pinned seed selected 440 documents from a
4,799-document manifest (`db/seed/cds/PROVENANCE.json`) — coverage is hundreds
of (mostly selective private) schools, not the ~6,000 IPEDS universe.

**What section H carries** (field inventory pulled live from
`cds_field_definitions`, schema_version 2024-25; matches the official template):

- **H1** need-based vs non-need-based aid _dollars awarded_ by source: federal,
  state, institutional, external scholarships; total scholarships/grants;
  student loans; work-study; total self-help; parent loans; tuition waivers;
  athletic awards (H.106-H.128).
- **H2** the need-aid ladder for three cohorts (FTFT / all degree-seeking /
  part-time — H.201-239): applied for need aid → determined to have need →
  awarded aid → awarded need-based grant → **need fully met** count → **average
  % of need met** → average package → average need-based grant → average
  self-help → average need-based loan. **This is the only bulk source for "% of
  need met" and "need fully met" — the need-generosity facts no federal file
  has.**
- **H2A** institutional non-need ("merit") aid: number with no need awarded
  merit grants, average merit award, athletic counterparts (H.2A01-12) — already
  ingested.
- **H4/H5** graduating-cohort borrowing: % who borrowed and average per-borrower
  cumulative principal by loan program (any / federal / institutional / state /
  private — H.501-515). The only source that covers **private** loans in debt
  figures.
- **H6** aid to nonresidents (international) — availability + averages
  (H.601-606).
- **H7-H13** process facts: required forms (**FAFSA, CSS PROFILE**, noncustodial
  PROFILE — H.801-807), deadlines (H.901-903), notification dates,
  loan/scholarship programs available (H.1201-1308), and the H14/H15-style
  institutional-aid criteria grid (merit categories: academics, athletics,
  music, ROTC … H.1401-1419).

**Granularity/keys.** Per school (corpus carries `ipeds_id` → UNITID join; docs
without one are dropped — 148 such in the pinned run, `PROVENANCE.json`) × CDS
year; cohorts are named in the form (FTFT vs all degree-seeking vs part-time).

**Vintage/cadence.** Annual per-school self-publication (PDF/xlsx); the corpus
re-scrapes continuously; template field numbering shifts across years
(`fetch-cds-seed` refuses untaught schema_versions rather than guessing).

**Missing-value semantics.** The corpus marks each extracted field with
`value_status`; observed enum values by probing the live API this session:
`reported`, `not_applicable`, `parse_error` (other candidate values returned
empty). A field the school left blank simply has **no row** for that document —
so "not reported by the institution" is representable but only as row-absence
against the template, and "corpus failed to extract" (`parse_error`) is a
distinct, honest fourth state. `bin/fetch-cds-seed` already maps drops to
"unreported, never guessed" (`fetch-cds-seed:20-24`; drop reasons in
`db/seed/cds/PROVENANCE.json`). Underlying CDS itself has no suppression concept
— a school reports, skips, or writes N/A.

**Licence/access.** CDS the template is free to use; each school's filing is its
own publication. The corpus's terms: open with attribution etiquette
(`X-CollegeData-Client` header; the repo cites its
`docs/api-usage-attribution.md`). Not a federal source — flag for a licence
re-check before any figure is shown to users at scale.

---

## 5. Net price calculators (NPCs)

Every Title IV institution must host one (HEOA 2008 mandate; the Scorecard links
to them). **There is no dataset.** An NPC is an interactive per-student
estimator (many run on College Board's or the federal template engine); output
depends on family inputs and is not published in bulk. Scraping thousands of
heterogeneous calculators is not a realistic ingest, and a cached NPC output
would be a _guess about a family_, which the honesty rules forbid presenting as
a fact. **Verdict: CANNOT fill the canonical layer.** Its role is a per-college
_link_ (the Scorecard/IPEDS HD carries NPC URLs) — a pointer the coach can hand
to a family, not a figure to store.

## 6. State tuition-exchange programs

Four regional compacts, none publishing machine-readable price files:

- **WICHE WUE** (<https://www.wiche.edu/tuition-savings/wue/>): students from
  the 16-member Western region "pay no more than **150% of the institution's
  resident tuition rate**" at 170+ participating publics; downloadable school
  list + a web "Tuition Savings Finder". WRGP is the graduate analogue.
- **MSEP** (<https://msep.mhec.org/>): Midwest compact, discounted rate (publics
  cap at ~150% of resident tuition; avg. savings ~$7,000/yr per its site).
- **NEBHE Tuition Break / New England RSP** (<https://nebhe.org/tuitionbreak/>):
  reduced rates for New England residents **in approved programs not offered by
  their home state** — program-scoped, not institution-scoped.
- **SREB Academic Common Market**
  (<https://www.sreb.org/academic-common-market>): in-state rates for specific
  out-of-state programs in the South — also program-scoped.

**Semantics.** These are _rules and rosters_, not reported dollars: eligibility
(residency pair × institution × sometimes program × sometimes GPA/major caps),
plus a formula against the resident tuition IPEDS already carries. The canonical
schema should model an exchange as a **derived price tier with a named basis**
(e.g. "WUE = 1.5 × in-state tuition, if participating and admitted under WUE") —
never a stored dollar pretending to be a published price. Ingest = a small
curated roster table (institution, program scope, formula, source URL, as-of
date); "missing" here means "not a member", which is _not-applicable_, not
not-reported.

## 7. Other candidates, briefly

- **IPEDS HD** (pinned): no money facts, but carries the NPC URL and
  control/level keys.
- **College Navigator**: a UI over the same IPEDS files — no new data.
- **NASSGAP annual survey**: state grant-aid totals by _state_, not by
  institution — context only.
- **Peterson's / College Board Annual Survey**: commercial licences; superset of
  CDS but paid — out of scope unless coverage of CDS-nonfilers becomes a product
  problem.
- **NSLDS / FSA Data Center**: loan-portfolio aggregates by school (e.g. PLUS
  volumes) — possible later for loan-burden colour; OPEID-keyed, public domain.

---

## Missing-value semantics, side by side (the brief's critical question)

| Source                                    | reported                | institution did not report                                          | not applicable                | publisher-suppressed                     | publisher-imputed                               |
| ----------------------------------------- | ----------------------- | ------------------------------------------------------------------- | ----------------------------- | ---------------------------------------- | ----------------------------------------------- |
| Scorecard (IPEDS-derived cost/price vars) | value                   | NULL                                                                | NULL (indistinguishable)      | —                                        | invisible (IPEDS flags stripped)                |
| Scorecard (NSLDS/Treasury debt/earnings)  | value                   | NULL                                                                | NULL                          | **`PrivacySuppressed`**                  | noise-perturbed, undisclosed per-cell           |
| IPEDS IC/IC_AY/SFA                        | X-flag **R**/C          | **B** blank, **D** don't know, **H** unusable; `-1` on categoricals | **A**; `-2` on categoricals   | none (institution-level, no suppression) | **G/J/K/L/N/P/Z** visible per cell              |
| CDS via collegedata.fyi                   | `value_status=reported` | row absent for the document                                         | `value_status=not_applicable` | n/a                                      | n/a (`parse_error` = corpus extraction failure) |
| NPC                                       | n/a — no dataset        |                                                                     |                               |                                          |                                                 |
| Exchanges                                 | roster membership       | n/a                                                                 | non-membership                | n/a                                      | n/a                                             |

Only IPEDS natively distinguishes all the states the brief's success criterion 2
needs. Scorecard adds one state (`PrivacySuppressed`) that the repo currently
throws away (`CollegeScorecardLoader.kt:1239,1307`). Any canonical missingness
encoding therefore needs at least: `reported`, `not_reported_by_institution`,
`not_applicable`, `suppressed_by_publisher`, `imputed_by_publisher`, and
unicoach's own `not_collected_by_us` (the state no source can supply — it is
ours by construction).

## Fill matrix — canonical concept × source

FILLS = first-class, right semantics. PARTIAL = usable but wrong
basis/coverage/blend. CANNOT = absent.

| Canonical concept                                                            | Scorecard                                       | IPEDS IC_AY                                                          | IPEDS IC (pinned)                            | IPEDS SFA                                                | CDS §H                                                    | NPC                                              | Exchanges                      |
| ---------------------------------------------------------------------------- | ----------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------- | -------------------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------ | ------------------------------ |
| Tuition+fees by residency (in-district / in-state / out-of-state)            | PARTIAL (IN collapses district/state; no 3-way) | **FILLS** (`CHG1/2/3AY*`, fees split via `*AT/AF`)                   | CANNOT (only `TUITVARY` flag)                | CANNOT                                                   | CANNOT (G1 exists but weak/not in corpus scope)           | CANNOT                                           | PARTIAL (derived tier formula) |
| Arrangement cost components (on/off/with-family; housing+food, books, other) | PARTIAL (blended; no with-family housing)       | **FILLS** (`CHG4-9AY*`, incl. with-family)                           | PARTIAL (`ROOMAMT/BOARDAMT` typical charges) | PARTIAL (arrangement population counts `GIS4ON/WF/OF`)   | CANNOT                                                    | CANNOT                                           | CANNOT                         |
| Published price / COA total                                                  | PARTIAL (`COSTT4_A` = blended, in-state-based)  | **FILLS** (components sum per arrangement × residency, same vintage) | CANNOT                                       | CANNOT                                                   | CANNOT                                                    | CANNOT                                           | CANNOT                         |
| Net price overall + by income band                                           | **FILLS** (NPT4 families; basis must be stored) | CANNOT                                                               | CANNOT                                       | **FILLS** (NPIST/NPGRN, NPIS41-45; 3 yrs/file)           | CANNOT                                                    | PARTIAL (per-family estimate only, not storable) | CANNOT                         |
| Need-aid generosity (% need met, need fully met, avg need grant)             | CANNOT                                          | CANNOT                                                               | CANNOT                                       | CANNOT                                                   | **FILLS** (H2 ladder) — coverage-limited                  | CANNOT                                           | CANNOT                         |
| Merit practice (non-need inst. aid counts/averages, criteria)                | CANNOT                                          | CANNOT                                                               | CANNOT                                       | CANNOT                                                   | **FILLS** (H2A — already ingested; H14 criteria)          | CANNOT                                           | CANNOT                         |
| Pell share / Pell average                                                    | **FILLS** (PCTPELL_DCS/FTFTPCTPELL)             | CANNOT                                                               | CANNOT                                       | **FILLS** (UPGRNT*, PGRNT_*; + averages Scorecard lacks) | CANNOT                                                    | CANNOT                                           | CANNOT                         |
| Loan burden (borrow %, median/avg debt)                                      | **FILLS** federal (DEBT_MDN splits, PCTFLOAN)   | CANNOT                                                               | CANNOT                                       | PARTIAL (LOAN__/UFLOAN_ = annual awards, not cumulative) | PARTIAL (H4/H5 incl. **private** loans; coverage-limited) | CANNOT                                           | CANNOT                         |
| Aid mix by source (fed/state/inst grants)                                    | CANNOT                                          | CANNOT                                                               | CANNOT                                       | **FILLS** (FGRNT/SGRNT/IGRNT + UG families)              | PARTIAL (H1 dollar pools)                                 | CANNOT                                           | CANNOT                         |
| Forms required (FAFSA / CSS Profile) + aid deadlines                         | CANNOT                                          | CANNOT                                                               | CANNOT                                       | CANNOT                                                   | **FILLS** (H7/H8/H9)                                      | CANNOT                                           | CANNOT                         |
| Exchange-discount eligibility                                                | CANNOT                                          | CANNOT                                                               | CANNOT                                       | CANNOT                                                   | CANNOT                                                    | CANNOT                                           | **FILLS** (roster + formula)   |

## Recommended source-priority order

1. **IPEDS IC_AY** — the single highest-value addition: first-class residency
   tiers (incl. in-district), true arrangement components (incl. with-family),
   fees split from tuition, freshest vintage, and full three-way missingness
   flags. Slots straight into the existing `bin/fetch-ipeds` all-or-nothing
   group. (This is brief 0003 D7, un-deferred.)
2. **IPEDS SFA** — net price with an honest population label, income-band aid,
   Pell/loan averages, aid mix by source, residency/arrangement population
   weights. Same fetch pattern, same flags.
3. **College Scorecard** (retained, demoted to what it is uniquely good at) —
   NSLDS debt medians and splits, earnings, and continuity for figures already
   served. Preserve `PrivacySuppressed` as a stored state instead of null.
4. **CDS via collegedata.fyi** (already plumbed) — extend extraction from H2A to
   H2 (% need met), H4/H5 (borrowing incl. private), H7/H8 (forms). Coverage is
   partial; the canonical layer's missingness states make that honest
   per-college.
5. **Exchange rosters (WUE first)** — small curated table + derived-tier
   formula; high family value per byte, but only after residency tiers exist to
   hang the formula on.
6. **NPC** — link-out only; never a stored figure.

A closing note for the schema decision: the same fact (e.g. Pell share, net
price by income) arrives from both Scorecard and IPEDS SFA with different
vintages and identical ancestry — Scorecard is downstream of IPEDS. The
canonical layer should record source + vintage per figure and prefer the
upstream (IPEDS) value when both exist, rather than averaging or
last-write-wins.
