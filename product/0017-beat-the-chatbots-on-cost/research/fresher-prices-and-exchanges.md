# Fresher prices and exchanges — data feasibility

Research report for brief 0017 (beat the chatbots on cost). Written 2026-11-04.
Builds on `product/0006-money-in-unicoach-shape/research/source-landscape.md`
section 6 (the four compacts as rules-and-rosters, modelled as a derived tier
with a named basis); that section is not repeated here.

The question is data feasibility in three parts: (A) how to get published
charges fresher than the 2023-24 / 2024-25 figures the repo serves today, (B)
whether the regional tuition exchanges have a roster a seed can be built from,
and (C) whether anything structured answers "what happens next at an
out-of-state public" (net price calculator, need-based aid for nonresidents).

## METHOD

Fetched and used (primary sources unless noted):

- IPEDS data center file listing for collection years 2024 and 2025, via a guest
  session (`login.aspx?gotoReportId=7&fromIpeds=true`, then
  `DataFiles.aspx?...&year=2024|2025`) — WebFetch loops on the redirect; curl
  with a cookie jar works.
- `HEAD` probes against both IPEDS download paths (`/ipeds/datacenter/data/` and
  `/ipeds/complete-data-files/`) for `IC2023_AY`, `HD2023`, `IC2024`,
  `IC2024_AY`, `COST1_2024`, `COST2_2024`, `DRVCOST2024`, `HD2025`, `IC2025`,
  `COST1_2025`, `SFA2324`.
- `COST1_2024_Dict.zip` (downloaded, variable list read from the xlsx shared
  strings) and the re-released `IC2023_AY.zip` at the new path (downloaded,
  compared row-for-row with the committed `db/seed/ipeds/ic2023_ay.csv`).
- IPEDS data release schedule
  (<https://nces.ed.gov/ipeds/survey-components/data-release-schedule>), the
  Fall 2025-26 provisional release memo, the IPEDS Access-database page, and
  This Week in IPEDS 332.
- College Scorecard `/data/` page, its changelog, and the current
  `CollegeScorecardDataDictionary.xlsx` (769,715 bytes, downloaded 2026-11-04;
  `README`, `ChangeLog` and `Most_Recent_Inst_Cohort_Map` sheets parsed).
- CDS 2025-26 section G as filed by UT Martin and by Oregon State, Oregon
  State's 2024-25 filing (G1 year wording only), and CDS 2025-26 section H as
  filed by William & Mary — all PDFs, text extracted with macOS PDFKit.
  `commondataset.org` itself answers 403 to every automated fetch, so the
  template wording is quoted from filed copies, not the blank template.
- collegedata.fyi: home page, `/api` page, and its public PostgREST
  (`cds_manifest` 5,334 rows paged in full; `cds_fields` section-G rows for
  2025-26). The read-only anon key was scraped from `/api` exactly as
  `bin/fetch-cds-seed` does.
- The six schools' own cost-of-attendance pages (listed in A4 with URLs).
  `admissions.wwu.edu` rejects WebFetch's certificate check; plain curl works.
- WICHE: WUE overview, list-of-schools page, Savings Finder page (whose HTML
  embeds the whole dataset as `var wueDataSource = {...}` — parsed), the WUE
  one-sheet PDF (Sept 2026 edition), the copyright-and-use page.
- MHEC/MSEP: `msep.mhec.org` home and `/about`, the MSEP one-pager PDF dated
  10.28.25. NEBHE: `nebhe.org/tuitionbreak/` and its undergraduate program PDF.
  SREB: `sreb.org/search-programs` (redirects to a Salesforce app).
- OSU and WWU WUE pages; UC/CSU/UW figures as above.
- `db/seed/ipeds/HD2023.csv` for NPRICURL coverage and a seeded random sample of
  20 four-year schools, each URL probed with `curl -sIL`.

Failed or not verified:

- `commondataset.org` (403), `mhec.org/.../about-msep/` (404),
  `nebhe.org/tuitionbreak/find-a-program/` (404), the IPEDS
  `use-the-data/ipeds-data-release-schedule` URL (404; the
  `survey-components/...` URL works).
- No official tally exists of how many institutions publish a CDS; the corpus
  count is used as a lower bound and labelled as such.
- The Scorecard statement that public-school net price covers in-state students
  only is from the Scorecard data documentation as previously read for brief
  0006; it was not re-fetched this run and is flagged where used.
- The MSEP institution list is behind a POST-only Drupal filter form; it was not
  enumerated, so the institution count is quoted from MHEC's own two
  (conflicting) figures.

## A. Fresher published charges

### A1. IPEDS: the charges file has been renamed, moved, and now releases in winter

Three things changed since `bin/fetch-ipeds` was written, and each one matters
for a "seed bump":

**1. The file is no longer `IC{yyyy}_AY`.** From the 2024-25 collection the
academic-year charges live in a new **Cost** component. The data center's 2024
listing (fetched 2026-11-04) names:

- `COST1_2024` — "Cost: Application fees, tuition and required fees, food and
  housing for undergraduate and graduate students"
- `COST2_2024` — "Cost: Full-time, first-time ... paying the in-state or
  in-district tuition rate by living arrangement in public institutions, 2021-22
  to 2023-24"
- `DRVCOST2024` — "Total cost of attendance: Academic year 2024-25"

`IC2024` still exists (updated September 2026) but carries only "Educational
offerings, organization, services and athletic associations". There is no
`IC2024_AY`: it answers 404 on both paths. The Cost I dictionary
(`COST1_2024_Dict.zip`, 75,005 bytes) describes the file as "Student charges for
cost of attendance estimates for first-time, full-time undergraduate students
2021-22 to 2024-25 and tuition and required fees and other student charges", and
keeps the familiar variable names: `TUITION1/2/3`, `FEE1/2/3`, `ROOMAMT`,
`BOARDAMT`, `CHG1..CHG9 AY0..AY3`, plus the `X` imputation flags. So the
loader's column model survives; the artifact name and its ingest option do not.

**2. The download path moved.** The listing's hrefs are
`/ipeds/complete-data-files/<name>.zip`. The old `/ipeds/datacenter/data/` path
still serves the 2023 archives the repo pinned (`IC2023_AY.zip` there is still
309,736 bytes, Last-Modified 2024-08-20, i.e. byte-identical to
`PROVENANCE.json`), but it serves **nothing** from 2024 onward except `HD2024`,
`IC2024` and `C2024_A`; `COST1_2024`, `HD2025`, `IC2025` are 404 there and 200
at the new path. A fetcher pointed at the old path would bump `HD`/`IC`/`C` and
silently fail to find the charges file.

**3. The 2023 charges were re-released.** `IC2023_AY.zip` at the new path is a
different archive (345,600 bytes, Last-Modified 2025-12-11, member
`ic2023_ay.csv` 2,338,571 bytes vs the committed 3,426,048). Compared
row-for-row: 235 columns and 3,825 rows in both; the only differences are a
trailing space removed from the `CHG9AY3` header and missing values written as
empty cells instead of `.`. Same numbers, different bytes — exactly the case the
member-drift guard is designed to stop and have a human look at.

**Release timing.** The release schedule
(<https://nces.ed.gov/ipeds/survey-components/data-release-schedule>) and the
observed file timestamps give:

| Collection | Release | Components                       | Provisional                                                                                       | Final          |
| ---------- | ------- | -------------------------------- | ------------------------------------------------------------------------------------------------- | -------------- |
| 2024-25    | Winter  | ADM, GR, GR200, OM, SFA, **CST** | **2025-12-09** (`COST1_2024.zip` Last-Modified 2025-12-15)                                        | not yet listed |
| 2024-25    | Fall    | IC, C, E12                       | (Sept 2025; `HD2024` Last-Modified 2025-09-21)                                                    | 2026-09-08     |
| 2025-26    | Fall    | IC, C, E12                       | **2026-07-28** (`HD2025`/`IC2025` Last-Modified 2026-07-21; the 2025 listing has no `COST1_2025`) | —              |
| 2025-26    | Winter  | not yet scheduled                | —                                                                                                 | —              |
| 2023-24    | Winter  | ADM, GR, GR200, OM, SFA          | —                                                                                                 | 2025-12-09     |

So, as of today:

- The newest public IPEDS charges file is **`COST1_2024` — academic year
  2024-25, provisional** (the 2024-25 Access database is also "provisional,
  March 2026"). An IPEDS bump moves the repo from 2023-24 to 2024-25 charges:
  one year, not two.
- **AY 2025-26 charges (`COST1_2025`)** are expected with the Winter 2025-26
  provisional release. NCES has not dated it; by the 2024-25 precedent that is
  roughly December 2026.
- **AY 2026-27 charges (`COST1_2026`)** are collected in the 2026-27 Cost
  component and, on the same precedent, would be public provisionally around
  **December 2027**, final a year or more after that. There is no route by which
  IPEDS shows 2026-27 prices during the 2026-27 application season.

The 2025-11-13 Federal Register notice on IPEDS changes
(<https://www.govinfo.gov/content/pkg/FR-2025-11-13/html/2025-19874.htm>) adds
only the Admissions and Consumer Transparency Supplement; it does not touch the
Cost component.

### A2. College Scorecard: already one year ahead of the public IPEDS files

The `/data/` page now names release **`11022026`** ("last updated November 2,
2026"), two releases after the `06102026` the repo pins. The changelog
(<https://collegescorecard.ed.gov/data/changelog/>):

- **October 15, 2026** — IPEDS metrics "derived from components collected in the
  IPEDS Winter 2025-26 collection season", naming Student Financial Aid, **Cost
  I and Cost II**, graduation rates and Outcome Measures; also IRS earnings and
  NSLDS refreshes.
- **November 2, 2026** — "a more recent version of the IPEDS Winter 2025-26
  data" that "captures revisions institutions submitted near the end of the
  IPEDS collection period".

The dictionary workbook downloaded today (`README`: "Released October 15, 2026")
dates, in `Most_Recent_Inst_Cohort_Map`:

| Variable                                                        | Committed transcription (`dictionary-variable-sources.csv`) | Current workbook                                 |
| --------------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------ |
| `TUITIONFEE_IN`, `TUITIONFEE_OUT`, `BOOKSUPPLY`, `ROOMBOARD_ON` | AcadYr 2024-25                                              | **AcadYr 2025-26, reported in IPEDS DCY2025-26** |
| `COSTT4_A`, `NPT4_PUB`, `NPT41_PUB`, `NPT4_PRIV`                | AcadYr 2023-24                                              | **AcadYr 2024-25, reported in IPEDS DCY2025-26** |
| `ADM_RATE`, `UGDS`, `SATVR25`                                   | Fall 2024                                                   | Fall 2024 (unchanged)                            |
| `C150_4`                                                        | Fall 2018 / AcadYr 2018-19 cohort                           | Fall 2019 / AcadYr 2019-20 cohort                |
| `OMAWDP8_ALL`                                                   | AY 2016-17 cohort                                           | AY 2017-18 cohort                                |

So the Department of Education publishes the winter collection in the Scorecard
**before** NCES publishes the IPEDS file: 2025-26 tuition, housing and books are
in the Scorecard today, and will not be in a public IPEDS archive until roughly
December 2026. For this repo that means `bin/fetch-scorecard` plus a re-dated
`dictionary-variable-sources.csv` (which `ScorecardDictionaryPinTest` and
`AssuranceTierClosureTest` will force) buys **2025-26 sticker and 2024-25 net
price now**, with no new loader.

When does the Scorecard move to 2026-27? The changelog pattern is one cohort
move per autumn (Oct 15, 2026 moved; the March and June 2026 entries were
refreshes, not moves). Expect 2026-27 tuition around **October 2027**, i.e.
after the 2026-27 application season has ended. The chatbots will be a year
ahead of the Scorecard every autumn, structurally.

### A3. Common Data Set section G: the only structured source that is a year ahead

**Field list and vintage.** Section G, as filed by UT Martin
(<https://www.utm.edu/offices-and-services/office-of-institutional-research/_media/2025-2026/Common%20Data%20Set%202025-2026%20-%20G.%20Annual%20Expenses.pdf>)
and by Oregon State
(<https://institutionalresearch.oregonstate.edu/sites/institutionalresearch.oregonstate.edu/files/2026-06/CDS-2025-26_1.pdf>),
reads, verbatim:

> G0 Please provide the URL of your institution's net price calculator:
>
> Provide **2026-2027** academic year costs of attendance for the following
> categories that are applicable to your institution. Check here if your
> institution's 2026-2027 academic year costs of attendance are not available at
> this time and provide an approximate date (i.e., month/day) when your
> institution's final 2026-2027 academic year costs of attendance will be
> available:
>
> G1 Undergraduate full-time tuition, required fees, food and housing — List the
> typical tuition, required fees, and food and housing for a full-time
> undergraduate student for the FULL 2026-2027 academic year.

G1 rows (two columns: First-Year Undergraduates / Undergraduates): private
tuition; public tuition in-district, in-state (out-of-district), out-of-state,
nonresident; required fees; food and housing (on-campus); housing only; food
only; comprehensive fee; other. G2 credits per term for the stated tuition
(min/max). G3 vary by year of study (Y/N). G4 vary by program (Y/N, and the
share paying more). G5 estimated expenses — books and supplies, housing only,
food only, food and housing total, transportation, other — for Residents /
Commuters (living at home) / Commuters (not living at home). G6 per-credit-hour
tuition (private; public in-district, in-state, out-of-state, nonresident).

**The CDS year-N filing carries year N+1 charges.** Oregon State's 2024-25
filing asks "Provide 2025-2026 academic year costs"; its 2025-26 filing asks for
2026-27. This is the template convention, not an OSU quirk (UT Martin's 2025-26
filing says the same). Consequences:

- The 2024-25 filings the repo's section-H seed is mostly built on already carry
  **2025-26** G1 charges — the same vintage the new Scorecard gives.
- The 2025-26 filings, published between roughly January and August 2026, carry
  **2026-27** G1 charges — the vintage ChatGPT and the schools show.
- The "not available at this time" box is real: a school that files before its
  board sets rates leaves G1 blank or writes a date (UT Martin wrote "July 15,
  2026"). Such a filing is a documented absence, not a number.

**Accuracy against the school's page.** OSU's CDS 2025-26 G1: in-state tuition
$13,440 (first-year) / $13,350, out-of-state $40,155 / $39,795, required fees
$2,592, food and housing $19,011. OSU's own 2026-27 cost-of-attendance page (A4)
shows tuition and fees $16,014 resident and $42,459 nonresident. CDS tuition +
fees = $16,032 resident (0.1% over) and $42,747 nonresident (0.7% over). Close
enough to quote as "the school's own filed figure", and the 0.7% gap is itself
worth a sentence: the CDS is "typical tuition" at 15 credits; the COA page is
the aid office's budget.

**Coverage.** There is no official count of CDS publishers; every source says
"hundreds". The collegedata.fyi corpus — the same one `bin/fetch-cds-seed` reads
— is the best measured lower bound (`cds_manifest`, paged in full, 2026-11-04):

| Canonical year                      | Documents | Distinct schools | With IPEDS id |
| ----------------------------------- | --------- | ---------------- | ------------- |
| 2023-24                             | 473       | 472              | 463           |
| 2024-25                             | 496       | 472              | 465           |
| 2025-26                             | 278       | 276              | 270           |
| 2026-27 (mislabelled early filings) | 4         | 4                | 4             |
| any year                            | 5,334     | 759              | 751           |

Against the 2,445 active four-year public and private-nonprofit institutions in
`HD2023.csv` (`ICLEVEL=1`, `SECTOR` 1 or 2, `CYACTIVE=1`), that is ~31% ever,
~19% for 2024-25, and ~11% for 2025-26 so far (the collector lags the schools).
The repo's own seed today has **366 distinct `unit_id`s** in
`db/seed/cds/aid_policy.csv` and 352 in `merit-aid.csv`; `PROVENANCE.json`
records 446 documents selected.

The corpus's normalised field table (`cds_fields`) does carry section G for
2025-26: 2,230 `G.1*` rows across **119 schools**, field ids `G.101`-`G.118`
(`G.105` and `G.109` are the two out-of-state tuition cells on OSU's row,
`G.111` fees, `G.112` food and housing). **51 schools** have a positive
out-of-state G1 tuition for 2026-27 in it today; 96 have any positive G1
tuition. That is less than half of the 276 2025-26 filings because field
extraction lags the manifest, and the "not available" box accounts for some of
the rest.

**Effort.** This is an extension of `bin/fetch-cds-seed` — a fifth fact group
over the same API, with the same per-document selection and the same
drop-and-count discipline — plus a loader column for the vintage the template
asks for (filing year + 1), which the script must derive from `schema_version`,
never from the file name. The 2024-25 and 2025-26 templates already number
C21/C22 differently (the script's docstring says so); G is the same hazard, so
the field map is per `schema_version` and an untaught template is refused.

### A4. Schools' own cost-of-attendance pages: the six schools

All six publish 2026-27 budgets as HTML tables; none needs JavaScript or a PDF
to read. None of the six pages has the same shape as another.

| School             | URL (fetched 2026-11-04)                                                                                                                                                            | Year label                                                                                          | Resident, on campus                                                   | Nonresident, on campus                                                       | Shape                                                                                                                                                                                                 |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UW Seattle         | <https://finance.uw.edu/tuition-fees/node/24790>                                                                                                                                    | 2026-27                                                                                             | **$37,680** ("RESIDENT UNDERGRADUATE ANNUAL TOTAL")                   | **$69,596**                                                                  | one static table for all three campuses, with linked PDF/DOCX; the aid office's own page (<https://www.washington.edu/financialaid/cost-of-attendance/>) shows a different nonresident total, $69,129 |
| Western Washington | <https://admissions.wwu.edu/cost>                                                                                                                                                   | "2026-2027 Estimated Annual Cost of Attendance"                                                     | **$33,483**                                                           | **$52,392**                                                                  | two tables (resident, nonresident), same eight rows each; host's TLS chain fails WebFetch                                                                                                             |
| Cal Poly SLO       | <https://www.calpoly.edu/financial-aid/costs-and-affordability/undergraduate-costs-attendance-2026-2027>                                                                            | 2026-27 (year-stamped URL)                                                                          | **$41,342** (Eng/Arch/Ag) or $40,304 (other colleges), residence hall | **$64,276** / $63,238                                                        | tables split by college group and by "Residence Hall" vs "On Campus"; nonresident shown as two add-on rows ($14,130 tuition + $8,804 opportunity fee)                                                 |
| UC Berkeley        | <https://financialaid.berkeley.edu/cost-attendance>                                                                                                                                 | "New Students Starting in the 2026-27 Academic Year"                                                | **$54,674** (includes $5,066 health insurance)                        | not stated; $54,674 + $39,270 Nonresident Supplemental Tuition = **$93,944** | tables by living arrangement; the nonresident total must be computed; caveat that Regents may change rates                                                                                            |
| San Diego State    | <https://sacd.sdsu.edu/financial-aid/financial-aid/eligibility/cost-of-attendance/cost-of-attendance-tables/undergraduate-non-resident> and `.../undergraduate-california-resident` | "2026-27 Standard Costs" **and** "2027-28 Standard Costs" on the same page, "Last Updated 10/26/26" | **$36,432** (2026-27); $36,840 (2027-28)                              | **$53,562** (2026-27); $54,996 (2027-28)                                     | one page per residency, two year blocks each, living-arrangement columns; the newest block on the page is already next year                                                                           |
| Oregon State       | <https://financialaid.oregonstate.edu/cost-attendance>                                                                                                                              | "2026-2027 Estimated Resident/Non-Resident Undergraduate"                                           | **$38,568**                                                           | **$65,013**                                                                  | one table per residency and year, three-term and one-term columns; says WUE amounts appear only on the aid offer                                                                                      |

What the sample says about a scraper: every page needed a human decision — which
of two official UW numbers, which Cal Poly college group, whether Berkeley's
health insurance is in the comparable total, which of SDSU's two year blocks,
whether OSU's "estimated total" is the same concept as WWU's "Total Cost of
Attendance". Six schools, six layouts, zero machine-readable markup. At 2,000+
institutions this is not a scraper; a generic HTML-table heuristic would produce
confident wrong totals, which is the one failure a cost coach cannot afford.
What is feasible is a **curated seed for list schools** on the
`db/seed/automatic-awards/awards.csv` pattern (61 rows today, each with
`source_url`, `archive_url`, `checked_on`, `expires_on`): an LLM-assisted
extraction that proposes the row, a human who confirms the two totals and the
year label, an archive URL, and an expiry at the next board cycle. A few hundred
schools is a realistic ceiling; the pages change every spring and SDSU shows
they can change in October too.

## B. Regional tuition exchanges

### WUE (WICHE) — the one compact with a real dataset

- Overview <https://www.wiche.edu/tuition-savings/wue/>: students from 15 states
  plus American Samoa, CNMI and Guam "pay no more than 150% of the institution's
  resident tuition rate"; "some schools charge less than 150%"; **not**
  automatic — "each school sets its own criteria and may require a minimum GPA,
  exclude certain majors, set early deadlines, or limit how many students
  receive the WUE rate"; some "automatically consider applicants", "many require
  applicants to request the WUE rate".
- List of schools
  <https://www.wiche.edu/tuition-savings/wue/wue-list-of-schools/>: 170
  institutions by count on the page (CO 26, CA 25, AZ 21, MT 12, NM 12, ND 11,
  UT 9, WA 9, WY 9, ID 7, NV 7, OR 7, SD 6, HI 4, AK 3, CNMI 1, Guam 1). The
  only download is the one-sheet PDF
  <https://www.wiche.edu/wp-content/uploads/2022/01/WUE-onesheet.pdf> ("As of
  Sept. 2026", "169 INSTITUTIONS", "$13,060 average savings per student in AY
  2025-26"), a two-page name list — no rates, no fields.
- **Savings Finder**
  <https://www.wiche.edu/tuition-savings/wue/wue-savings-finder/>: the page is a
  2.7 MB HTML document because it embeds the whole dataset inline as
  `var wueDataSource = {"cells": [...], "fields": [...]}`. Parsed: **169
  institution rows**, each with `id` (a Salesforce record id), `uniName`,
  `location.{city,state}`, **`tuition`** (the WUE annual tuition in dollars),
  `interstatePassport`, `applicationDeadline`, and `majors[]` — **13,688 major
  rows** with `name`, `program` (CIP-style title), `degreeType`, `studentStatus`
  ("Freshmen and Transfers" etc.), `discountAvailable` (Yes/No per major),
  `gradURL`. No field for GPA minimum, award cap, or automatic-vs-competitive;
  those live only in prose on the schools' pages. Rows for the six: Oregon State
  `tuition` 19,013, deadline "2/2/26" (stale), 94 majors; OSU-Cascades 19,013,
  "2/1/27"; Western Washington 11,619, "1/31/27", 179 majors.
- **Licence**: <https://www.wiche.edu/copyright-and-use/> — "Users may access
  and use the materials on this website for personal, educational, and
  non-commercial purposes. Any other use, including reproduction, distribution,
  modification, or transmission of the content, is strictly prohibited without
  prior written consent from WICHE"; freely available materials are CC BY-NC-SA
  "unless otherwise noted". For a paid product that is a written-permission
  question, not a fetch-and-seed.

### MSEP (MHEC) — rule published, roster not

- <https://msep.mhec.org/about>: eight states (IN, KS, MN, MO, NE, ND, OH, WI;
  "Illinois, Iowa, Michigan, and South Dakota do not participate at this time");
  "public institutions agree to charge students no more than 150% of the
  in-state resident tuition rate for selected programs with private institutions
  offering a 10% reduction"; "the campus may exercise its right to limit
  participation or set specific admission requirements"; "savings between $500
  and $7,000 annually". The one-pager
  (<https://mhec.org/wp-content/uploads/2025/11/MSEP-One-Pager-10.28.25.pdf>)
  says "more than 35 colleges and universities"; the site's own meta description
  says "over 70 institutions". Both are MHEC's; they disagree.
- The institution list is a Drupal entity list behind a POST filter form (state
  of residence × degree level × sector); no download, no API, no per-row rate.
  The site disclaims accuracy ("for general information purposes only").

### NEBHE Tuition Break / RSP — program-scoped, PDF and FileMaker

- <https://nebhe.org/tuitionbreak/>: 66 public institutions; "more than 2,700
  academic programs ... for 2024-25" (elsewhere "3,000+"); rate "based on a
  percentage (up to a maximum of 175 percent) of a college's in-state rate", set
  per college; eligibility is per program, generally one not offered in the home
  state, with `[F]` flexible (open to all five other states) and `[P]` proximity
  variants; the student declares the program as major and confirms with the
  college — not automatic.
- Rosters: four PDFs "Effective Fall 2025" (the undergraduate one is 28 pages,
  533 KB, ~2,900 text lines, laid out by state → institution → program with the
  eligible-state abbreviations per line) and a FileMaker WebDirect database
  (<https://db.nebhe.org/fmi/webd/TuitionBreak>). Neither is machine-readable;
  the PDF is parseable with effort. Footer: "All Rights Reserved".

### SREB Academic Common Market — program-scoped, Salesforce app

- <https://www.sreb.org/academic-common-market>: 15 states, "more than 100
  colleges and universities", "more than 2,200 undergraduate and graduate degree
  programs"; in-state tuition for a program the home state's publics do not
  offer; the student is certified by the home state's ACM coordinator after
  admission — per-student, not automatic. Florida and Texas participate at
  graduate level only; North Carolina withdrew in 2011 (SREB state contacts
  page). `sreb.org/search-programs` redirects to
  `acmi.sreb.org/acm/s/program-search-page-public`, a Salesforce Experience
  Cloud app — no download, no public API seen.

### The six schools under the compacts

| School             | WUE                                            | Terms (school's own page)                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ------------------ | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Western Washington | yes                                            | <https://admissions.wwu.edu/cost/first-year-oos-scholarships>: "Applicants are automatically considered"; "minimum eligibility for the WUE is set at an unweighted GPA of 3.25"; "reduces non-resident tuition to 150% of the in-state rate for up to four years"; "for the 2026-2027 academic year, the WUE award at Western is valued at about $14,910"; "our most prestigious non-resident scholarship and is highly competitive"; cannot be combined with the WAE ($10,000-$14,000) |
| Oregon State       | yes (Corvallis and Cascades)                   | <https://admissions.oregonstate.edu/wue>: "OSU's WUE Scholarship is Oregon State's most competitive scholarship for new undergraduate students"; "not automatically awarded", holistic review; about 30% of eligible students offered; pay no more than 150% of resident base tuition; renewable four years; apply by February 3                                                                                                                                                        |
| UW Seattle         | no (WSU and five other WA publics are)         | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| Cal Poly SLO       | no (Cal Poly Humboldt and Cal Poly Pomona are) | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| UC Berkeley        | no (UC Merced and UC Santa Cruz are)           | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| San Diego State    | no                                             | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

None of the six is in MSEP, NEBHE or SREB territory. So for this brief's own
six-school sample the exchange tier is a fact about two schools, and at both it
is a scholarship with a selection step, not a price.

### What a curated seed would look like

One row per (institution, exchange, scope), institution-scoped for WUE and MSEP,
program-scoped for NEBHE and SREB:

| Column                                                               | Notes                                                                                                                                            |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `unit_id`                                                            | IPEDS; the compacts' rosters carry names only, so the join is hand-made once and pinned                                                          |
| `exchange`                                                           | `wue`, `msep`, `nebhe_rsp`, `sreb_acm` — `TEXT` + `CHECK` with one Kotlin enum, per the schema convention                                        |
| `scope`                                                              | `institution` or `program`                                                                                                                       |
| `program`, `cip_code`                                                | nullable; required when `scope = program`                                                                                                        |
| `rate_rule`                                                          | `max_pct_of_resident_tuition` (150 / 175 / 100) or `flat_pct_discount` (10); the published basis, never a stored dollar pretending to be a price |
| `published_exchange_tuition_usd`                                     | nullable; only WUE publishes it (`tuition` in the finder); labelled as the compact's figure with its own `checked_on`                            |
| `award_basis`                                                        | `automatic`, `automatic_consideration`, `competitive`, `per_student_state_certification`, `unknown`                                              |
| `min_gpa`, `student_status`, `deadline`                              | nullable; from the school's page, not the compact                                                                                                |
| `eligible_states`                                                    | the compact's member list, or the per-program state set for NEBHE/SREB                                                                           |
| `publisher`, `source_url`, `archive_url`, `checked_on`, `expires_on` | the `automatic-awards` provenance quartet                                                                                                        |

Row counts for the whole US: institution-scoped, roughly **170 (WUE) + 35-70
(MSEP) + 66 (NEBHE) + ~100 (SREB) ≈ 370-400**; program-scoped, roughly **13,700
(WUE majors) + 2,700-3,000 (NEBHE) + 2,200 (SREB) ≈ 19,000**. The
institution-level table is a hand-curated seed; the program-level one is only
feasible for WUE (parse the embedded JSON) and only lawful with WICHE's consent.

## C. The next step at an out-of-state public

### C1. Net price calculator URLs (IPEDS HD `NPRICURL`)

From `db/seed/ipeds/HD2023.csv`: of 2,445 active four-year public and
private-nonprofit institutions, **2,364 (96.7%) carry an `NPRICURL`**; across
all 6,059 active institutions, 5,920 do. A seeded random sample of 20 of the
2,364, probed with `curl -sIL` on 2026-11-04:

| Result                                         | Count | Examples                                                                                                                                                        |
| ---------------------------------------------- | ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 200 at a calculator or calculator landing page | ~10   | Guilford and Seton Hall (College Board NPC), Charlotte Christian (ClearCost), IUP (PASSHE), Temple, NYSID, Kean, Sacred Heart, Hallmark→404, Loras→tuition page |
| 200 at a generic page that is not a calculator | 4     | Adler Graduate School (home page), Elmezzi (program page), Assumption (tuition page), Illinois State (`/paying/`)                                               |
| 403                                            | 1     | SUNY Geneseo (SUNY's shared NPC refuses HEAD)                                                                                                                   |
| 404                                            | 3     | Los Angeles Pacific, Hallmark, Oglala Lakota                                                                                                                    |
| 405                                            | 1     | Charles R. Drew (HEAD not allowed; GET may work)                                                                                                                |
| connection failure                             | 1     | Ashland (ClearCost host timed out)                                                                                                                              |

So roughly 14 of 20 resolve, and roughly half of the sample lands on something a
family could actually use. The URLs are 2023-vintage; `HD2025` (July 2026) would
refresh them for free in an IPEDS bump. An NPC URL is a "where to go next"
pointer, not a price: the calculators are JavaScript or vendor-hosted forms
(College Board, ClearCost, StudentAidCalculator) and cannot be read into a
number.

### C2. "Does this public give need-based institutional aid to nonresidents?"

**CDS section H does not answer it.** The 2025-26 template (William & Mary's
filing, <https://www.wm.edu/offices/ir/university_data/cds/cds_2025-26_h.pdf>):

- **H1** dollar totals and **H2** headcounts of need-based and non-need-based
  aid for all degree-seeking undergraduates — no residency split; the
  instructions even say to "include aid awarded to international students".
- **H6-H7 "Aid to Undergraduate Degree-seeking Nonresidents"**: "H6. Indicate
  your institution's policy regarding institutional scholarship and grant aid
  for undergraduate degree-seeking nonresidents: ☐ Institutional need-based
  scholarship or grant aid is available ☐ Institutional non-need-based
  scholarship or grant aid is available ☐ Institutional scholarship and grant
  aid is not available", with count, average and total. This reads like the
  answer but is not: in CDS vocabulary "nonresident" means nonresident **alien**
  — H7 asks which forms "nonresident first-year applicants" must submit and H8
  which forms "**domestic** first-year applicants" must submit. H6 is the
  international-student policy.
- **H14** "Check off criteria used in awarding institutional aid" has a
  "State/district residency" row with Non-Need-Based and Need-Based boxes. That
  is the closest cell: it says residency is a criterion, not which way it cuts
  or whether nonresidents are excluded from need-based grants.
- H12/H13 list aid programs offered; H15 is free text on affordability
  initiatives.

The repo's seed already reads H.201-H.211, H.401-H.515 and H.801-H.807
(`bin/fetch-cds-seed`); none of those is a residency fact, and there is no H
field to add that would be one.

**IPEDS does not answer it either.** SFA and Cost II count first-time full-time
students by which tuition rate they pay, and the Scorecard's `NPT4_PUB` net
price is computed for in-state payers at publics (per the Scorecard data
documentation as read for brief 0006; not re-fetched this run). Nothing federal
reports institutional grant dollars by residency.

**What exists is prose on each school's aid page** — the sort of sentence OSU's
and WWU's WUE pages carry, or a UC/CSU page saying its grant programs are for
California residents. The honest answer is: **nothing structured**; a per-school
curated fact on the `automatic-awards` pattern (`residency_basis` already exists
there as a column) is the only route, and its coverage would be whatever a human
has checked.

## Feasibility table

| Route                    | Source                                                                                                                                                                                                                     | Vintage it gives today                                                                                            | Coverage                                                                                                                                                      | Effort                                                                                                                                                                                   | Honest risk                                                                                                                                                                                                                                                                                                                    |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **C1a — IPEDS bump**     | `COST1_2024` (+ `HD2025`, `IC2025`) at `/ipeds/complete-data-files/`                                                                                                                                                       | AY **2024-25** charges, provisional; 2025-26 only after the Winter 2025-26 release (~Dec 2026); 2026-27 ~Dec 2027 | 3,800+ academic-year institutions                                                                                                                             | seed bump that is really a fetcher change: new artifact name (`COST1_{yyyy}`), new base path, new ingest option, and the re-released 2023 archive trips the member-drift guard by design | one year fresher at most; the file family changed under the fetcher and the old path keeps serving stale 2023 archives with a green exit                                                                                                                                                                                       |
| **C1b — Scorecard bump** | release `11022026`, dictionary "Released October 15, 2026"                                                                                                                                                                 | AY **2025-26** tuition/housing/books; AY **2024-25** COA and net price                                            | 6,273 institutions (current file)                                                                                                                             | seed bump: `bin/fetch-scorecard` + re-date `dictionary-variable-sources.csv` (two pin tests will fail until it is)                                                                       | still a year behind the chatbots; next move ~Oct 2027; the Nov 2 release is a mid-cycle revision so the number may move again                                                                                                                                                                                                  |
| **C1c — CDS section G**  | collegedata.fyi `cds_fields` `G.1*` over the existing fetcher                                                                                                                                                              | AY **2026-27** from 2025-26 filings (year N filing = year N+1 charges); AY 2025-26 from 2024-25 filings           | 276 schools with a 2025-26 filing in the corpus; 119 with G extracted; **51 publics with an out-of-state figure** today; ~470 schools for the 2025-26 vintage | new fact group in `bin/fetch-cds-seed` + a `schema_version`-keyed field map + a loader column for the asked-for year                                                                     | "not available" filings; extraction from PDF (`tier4_docling`) can misread a cell; coverage is a tenth of four-year schools and skews selective/private; the template may renumber G next year as it did C21/C22                                                                                                               |
| **C1d — school pages**   | each school's COA page (six verified in A4)                                                                                                                                                                                | AY **2026-27** (SDSU already shows 2027-28)                                                                       | whatever is curated; six today                                                                                                                                | new curated seed on the `automatic-awards` pattern, LLM-proposed and human-confirmed, with `archive_url`/`checked_on`/`expires_on`                                                       | not a scraper at scale: six schools, six layouts, every total needed a judgement; pages change each spring (and in October); a wrong confident total is the worst failure the brief can have                                                                                                                                   |
| **C2 — exchange tier**   | WUE embedded JSON (169 inst., 13,688 majors, dollar WUE tuition); MSEP prose rule, POST-only list; NEBHE PDFs/FileMaker; SREB Salesforce app                                                                               | rule-based, current (WUE list "as of Sept. 2026"; NEBHE "Fall 2025")                                              | ~370-400 institution rows US-wide; program scope only realistic for WUE                                                                                       | new curated seed (columns above); WUE could be semi-automated from the embedded dataset                                                                                                  | WICHE's terms forbid commercial reuse without written consent; MHEC's own counts disagree (35 vs 70); award basis (automatic / competitive / ~30%) is per-school prose; "WUE price" is a scholarship with a selection step at both sample schools, so it must be presented as a possibility with its basis, never as the price |
| **C3 — next-step facts** | `NPRICURL` from HD (96.7% of four-year schools; ~70% resolve, ~half to a real calculator); residency aid policy: **no structured source** (CDS H6 is about international students; H14 only says residency is a criterion) | HD2023 URLs now; HD2025 in an IPEDS bump                                                                          | NPC pointer: near-universal; residency policy: curated only                                                                                                   | NPC URL: already in the data, free with a bump; residency policy: new curated per-school fact (`automatic-awards` has `residency_basis` precedent)                                       | a resolving URL is not a usable calculator (4 of 14 200s were generic pages); any "nonresidents get need-based aid here" claim rests on a human having read that school's page on a dated visit                                                                                                                                |
