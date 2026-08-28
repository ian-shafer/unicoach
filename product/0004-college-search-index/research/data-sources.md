# 0004 research — what data can populate a rich college search index

**Scope:** external data sources only (schema/design is a separate brief input).
**Author:** research sub-agent, brief 0004.

## Method, and what I could not verify

Serper web search was not configured in this session, so **every external claim
below was verified by fetching the primary source directly** (`curl`) and, where
the source is a data file, by loading it and computing the number myself.
Specifically I downloaded and parsed: the College Scorecard institution data
dictionary
(<https://collegescorecard.ed.gov/assets/CollegeScorecardDataDictionary.xlsx>,
3,567 rows), the Most-Recent Field-of-Study CSV
(<https://collegescorecard.ed.gov/data/>, 227,980 rows), and the IPEDS 2023
`HD`, `IC`, `ADM`, and `C_A` files plus their dictionaries from
`https://nces.ed.gov/ipeds/datacenter/data/` (e.g. `HD2023.zip`, `IC2023.zip`,
`ADM2023.zip`, `C2023_A.zip` — all fetch with a plain GET, **no login**, despite
the Data Center UI requiring one). Coverage percentages below are my own
computation over a stated universe, not a published figure.

**Not verified / open:** (1) Common Data Set — `commondataset.org` returns HTTP
403 to a scripted fetch, so I could not read its terms; treat CDS licensing as
unconfirmed. (2) Carnegie — the ACE Data Center page
(<https://carnegieclassifications.acenet.edu/data-center/>) is JS-rendered and I
could not resolve a stable bulk-file URL or a licence statement. (3) EADA — the
athletics site is an SPA; I confirmed its year API
(`https://ope.ed.gov/athletics/api/datafiles/years` → `[2003..2025]`) but not a
stable bulk-download URL. (4) I did not run any repo command; the Nix shell was
not exercised.

## 1. Which of Ian's attributes actually exist in machine-readable public data

Universe for all percentages: **2,488 institutions** = IPEDS 2023 `HD` rows with
`ICLEVEL=1` (4-year) ∧ `UGOFFER=1` ∧ `PSET4FLG=1` ∧ `CYACTIVE=1`.

**Available, essentially free:** size, control, state/city/lat-long, campus
setting (`LOCALE`, 100%), selectivity (`ADM_RATE`, SAT/ACT percentiles), majors
(CIP, see §3), outcomes (completion, earnings, debt), aid generosity (net price
by income band — already in
`db/schema/0045.add-college-income-band-net-price.sql:5`), religious affiliation
(`IC.RELAFFIL`, 741 institutions carry a denomination — the other 1,747 are
coded `-2` "not applicable", i.e. a real _no_, not a gap), ROTC (`IC.SLO5`, 98%
reported, 954 yes), study abroad (`IC.SLO6`, 98% reported, 1,550 yes),
disability services (`IC.DISAB`/`DISABPCT` — a 3%-threshold band, 98% reported,
plus `HD.DISAURL`), housing (`IC.ROOM`, `ROOMCAP` 69%), test policy
(`ADM.ADMCON7`, also in the Scorecard institution file — 1,739 4-year
institutions report: 1,080 test-optional, 567 test-blind, 92 required),
athletics _membership and conference_ (`IC.ASSOC1`–`ASSOC6`, `SPORT1`–`4`,
`CONFNO1`–`4`; 1,094 NCAA members), Carnegie classification (`HD.C21BASIC`, 94%;
also mirrored in Scorecard as `CCBASIC`/`CCUGPROF`/`CCSIZSET`).

**Not available in federal data — decide whether to buy, scrape, or drop:**

| Attribute                                                        | Why it's missing                                                                                          | Cheapest honest substitute                                                   |
| ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| **Greek life** (% in fraternities/sororities)                    | No IPEDS or Scorecard variable exists (I grepped all 3,567 Scorecard elements and all 130 `IC` variables) | Common Data Set §F1, per-institution PDFs; or Wikipedia/Wikidata; both messy |
| **Application deadlines** (EA/ED/RD)                             | Not in `ADM` (57 variables, all admissions-criteria/counts/scores) nor `IC`                               | CDS §C13–C21, or Common App; no bulk feed                                    |
| **NCAA division** (D1 vs D3)                                     | `IC` gives conference _name_, not division                                                                | Derive conference→division map (one-off, ~100 rows), or EADA                 |
| **Weather / "sunny"**                                            | Not an education dataset at all                                                                           | Derive from `HD.LATITUDE/LONGITUD` + NOAA climate normals                    |
| **"Vibe" attributes** (politics, party school, advising quality) | Not published anywhere authoritative                                                                      | Do not fake it                                                               |

**"Similar colleges to X"** needs no new source: it is a distance function over
vectors we can already build (control, size, `C21BASIC`, `LOCALE`, admit rate,
net price, CIP mix). IPEDS even ships a ready-made comparison group per
institution (`HD.DFRCGID`) as a sanity check. The design question is the metric
and the weights, not the data.

## 2. Candidate sources

| Source                                 | Coverage                                                                                      | Cadence                                                                                                                               | Format                                                                                        | Licence                                                                  | Ingest effort                                                                                    |
| -------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| **College Scorecard institution file** | ~6,500 Title IV institutions, 1996‑97→2025‑26                                                 | 3–4 releases/yr (2026‑06‑10, 2026‑03‑23, 2025‑11‑17, 2025‑05‑21 per the [changelog](https://collegescorecard.ed.gov/data/changelog/)) | CSV in zip (23 MB)                                                                            | US federal work, public domain; redistributable, no attribution required | **Already done** (`bin/ingest-colleges`)                                                         |
| **Scorecard field-of-study file**      | 227,980 rows, **4-digit CIP only** (verified: every `CIPCODE` is 4 chars), pooled award years | with above                                                                                                                            | CSV (17 MB)                                                                                   | same                                                                     | already done                                                                                     |
| **Scorecard API**                      | same data                                                                                     | live                                                                                                                                  | JSON, `https://api.data.gov/ed/collegescorecard/v1/schools`, key required, `per_page` max 100 | same                                                                     | low, but paging 6.5k rows is worse than the bulk file                                            |
| **IPEDS `HD`** (directory)             | all 6,163 institutions                                                                        | annual (Fall collection)                                                                                                              | CSV zip, **no login**                                                                         | public domain                                                            | low — 73 columns, `UNITID` joins straight to `colleges.unit_id`                                  |
| **IPEDS `IC`**                         | 6,049                                                                                         | annual, Fall                                                                                                                          | CSV zip                                                                                       | public domain                                                            | low — the single highest-value addition (ROTC, abroad, religion, housing, athletics, disability) |
| **IPEDS `ADM`**                        | 1,972 (only institutions with an admissions process report)                                   | annual, **Winter**                                                                                                                    | CSV zip                                                                                       | public domain                                                            | low                                                                                              |
| **IPEDS `C_A`** (completions)          | 303,292 rows, **6-digit CIP × award level, all institutions**                                 | annual, Fall                                                                                                                          | CSV zip (9 MB)                                                                                | public domain                                                            | medium — the right source for "does X offer a literature major"                                  |
| **IPEDS `SFA`, `EF`, `GR`/`OM`**       | all                                                                                           | annual (SFA/GR Winter, EF Spring)                                                                                                     | CSV zip                                                                                       | public domain                                                            | medium; largely duplicates Scorecard                                                             |
| **NCES College Navigator**             | consumer UI over the above                                                                    | —                                                                                                                                     | HTML                                                                                          | public domain                                                            | **avoid** — no bulk endpoint, use the survey files                                               |
| **Common Data Set**                    | ~1,000 institutions publish it                                                                | annual, no fixed date                                                                                                                 | per-institution PDF/XLS on each college's site                                                | **unverified** (site 403s)                                               | **high** — no central file; scraping ~1,000 heterogeneous PDFs                                   |
| **Common App**                         | ~1,100 members                                                                                | annual                                                                                                                                | no public bulk feed                                                                           | proprietary                                                              | high / not available                                                                             |
| **Carnegie (ACE)**                     | ~4,000                                                                                        | ~3-yearly (2021, 2025 editions)                                                                                                       | xlsx via Data Center                                                                          | unverified                                                               | **skip** — `HD.C21BASIC` already carries it                                                      |
| **NCAA / EADA**                        | EADA years 2003–2025 (verified)                                                               | annual (institutions file by 15 Oct)                                                                                                  | web app + downloads                                                                           | public domain (federal)                                                  | medium; only needed for division/sport detail                                                    |
| **FSA data center**                    | Title IV eligibility, cohort default                                                          | quarterly-ish                                                                                                                         | CSV/XLS                                                                                       | public domain                                                            | low; Scorecard already re-publishes it                                                           |
| **Wikidata**                           | good for founding year, mascot, Greek/vibe trivia                                             | continuous                                                                                                                            | JSON/SPARQL, [CC0](https://www.wikidata.org/wiki/Wikidata:Data_access)                        | CC0, no attribution                                                      | medium; unverified crowd data — never mix into an authoritative field                            |

## 3. CIP and "a literature program"

CIP 2020 (<https://nces.ed.gov/ipeds/cipcode/browse.aspx?y=56>) puts literature
in two places: series **23 English Language and Literature/Letters** — `23.01`
General, `23.13` Rhetoric/Writing (incl. `23.1302` Creative Writing), `23.14`
**Literature** (`23.1401` General, `23.1402/03` American, `23.1404`
English/British, `23.1405` Children's/Adolescent) — and **`16.0104` Comparative
Literature**, which lives under series 16 (Foreign Languages) and would be
silently missed by a naive `cip LIKE '23%'`. That is the whole argument for a
human-facing taxonomy layer: a student says "literature", the index must expand
it to a _curated set_ of CIP codes across series.

There is **no public CIP→plain-English-major crosswalk**. NCES publishes only
CIP↔CIP (2010→2020) and CIP→SOC 2018 (occupations) at
<https://nces.ed.gov/ipeds/cipcode/resources.aspx?y=56>. A subject taxonomy is
therefore something we author: ~60–100 curated subjects, each a hand-picked list
of CIP prefixes, with synonyms ("lit", "English", "creative writing"). Small,
reviewable, versioned in the repo — and it is exactly the kind of judgement call
Ian should own rather than infer.

**Granularity matters and it is a live repo issue.** `college_programs.cip_code`
was relaxed to 2/4/6 digits
(`db/schema/0021.relax-college-programs-cip-format.sql:1-6`), and the loader
takes `CIPCODE` verbatim
(`college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt:488`),
so today's programs table is **4-digit only** — it can answer "23.14 Literature"
but not "23.1404 English Literature", and it only lists programs with enough
federal-aid recipients to survive Scorecard's cohort rules. IPEDS `C_A` gives
full 6-digit coverage of _every_ conferred degree. Worked example, all four
Maine public 4-year campuses with a bachelor's in CIP 23 in 2023 (`C2023_a.csv`,
`AWLEVEL=5`, `MAJORNUM=1`): UMaine Orono (27 awards, `23.0101`), UMaine
Farmington (20, `23.0101`+`23.1302`), USM (18, `23.0101`), UMaine Presque Isle
(7); UMA has 3. That is Ian's Maine query answered end-to-end from public data —
with one caveat worth designing against: my universe filter also admitted
"University of Maine-System Central Office" (`UNITID` 161280), a system office,
not a college.

## 4. Update cadence reality

Scorecard publishes **3–4 times a year** but the releases are not equivalent:
2026‑03‑23 refreshed _all_ IPEDS-derived metrics from a new collection year and
the IRS earnings series; 2026‑06‑10 refreshed only the Fall-collection IPEDS
metrics plus FSA flags; 2025‑11‑17 touched only OPE minority-serving flags and
FSA fields. IPEDS itself runs on three collection cycles — `IC` and `C` are
**Fall**, `ADM`/`SFA`/`GR`/`OM` are **Winter**, `EF`/`Finance`/`HR` are
**Spring** (<https://nces.ed.gov/ipeds/survey-components>) — and each component
appears first as **provisional (PD)** and later as **final (FD)**, with `_RV`
revised files shipped alongside (both `IC2023.csv` and `IC2023_RV.csv` are in
the zip).

Practical implication: **a quarterly rebuild is sufficient and an annual one is
defensible.** Between releases, what changes is mostly (a) a new collection year
rolling in, (b) revisions to prior-year values, (c) FSA operating/eligibility
flags. Names, control, locale, religious affiliation, ROTC, and study abroad are
near-static. Closures and mergers are the one genuinely time-sensitive class
(`HD.DEATHYR`, `CLOSEDAT`, `NEWID`, `CYACTIVE`) — a stale index recommending a
closed college is the visible failure mode.

## 5. Attribute → source table

Coverage = % of the 2,488-institution universe with a usable (non-sentinel)
value, computed by me from the 2023 files. Licence for every federal row: **US
Government work, public domain, redistributable, no attribution required**; the
`CC0` row is Wikidata.

| Attribute                         | Best source                   | Field                                    | Coverage                                  | Cadence         | Licence                        |
| --------------------------------- | ----------------------------- | ---------------------------------------- | ----------------------------------------- | --------------- | ------------------------------ |
| Name / city / state / URL         | Scorecard (have)              | `INSTNM`,`CITY`,`STABBR`,`INSTURL`       | 100%                                      | 3–4×/yr         | PD                             |
| Lat / long                        | IPEDS `HD`                    | `LATITUDE`,`LONGITUD`                    | 100%                                      | annual          | PD                             |
| Campus setting                    | IPEDS `HD` (have as `locale`) | `LOCALE`                                 | 100%                                      | annual          | PD                             |
| Metro area                        | IPEDS `HD`                    | `CBSA`,`CBSATYPE`                        | 96%                                       | annual          | PD                             |
| Control (public/private)          | Scorecard (have)              | `CONTROL`                                | 100%                                      | 3–4×/yr         | PD                             |
| Size                              | Scorecard (have)              | `UGDS`                                   | ~100%                                     | 3–4×/yr         | PD                             |
| Carnegie type                     | IPEDS `HD`                    | `C21BASIC`,`C21UGPRF`,`C21SZSET`         | 94%                                       | ~3-yearly       | PD (unverified for ACE direct) |
| Selectivity                       | Scorecard (have)              | `ADM_RATE`,`SAT_AVG`                     | 70% (only institutions that report `ADM`) | 3–4×/yr         | PD                             |
| **Test-optional policy**          | IPEDS `ADM` / Scorecard       | `ADMCON7` (1 req / 5 optional / 3 blind) | 70%                                       | annual (Winter) | PD                             |
| **Religious affiliation**         | IPEDS `IC`                    | `RELAFFIL` (40 denominations)            | 30% affiliated, 70% explicit "no"         | annual (Fall)   | PD                             |
| **ROTC**                          | IPEDS `IC`                    | `SLO5`,`SLO51`–`53`                      | 98% reported                              | annual (Fall)   | PD                             |
| **Study abroad**                  | IPEDS `IC`                    | `SLO6`                                   | 98% reported                              | annual (Fall)   | PD                             |
| **Disability services**           | IPEDS `IC` + `HD`             | `DISAB`,`DISABPCT` (52%), `DISAURL`      | 98% / 52%                                 | annual (Fall)   | PD                             |
| On-campus housing                 | IPEDS `IC`                    | `ROOM`,`ROOMCAP`,`MEALSWK`               | 98% / 69%                                 | annual (Fall)   | PD                             |
| Application fee                   | IPEDS `IC`                    | `APPLFEEU`                               | 98%                                       | annual (Fall)   | PD                             |
| Athletics membership + conference | IPEDS `IC`                    | `ASSOC1`–`6`,`SPORT1`–`4`,`CONFNO1`–`4`  | 98% / 31% have football conf.             | annual (Fall)   | PD                             |
| NCAA **division**, sport rosters  | EADA                          | institution files 2003–2025              | not measured                              | annual          | PD                             |
| Majors offered (6-digit)          | IPEDS `C_A`                   | `CIPCODE`,`AWLEVEL`,`CTOTALT`            | all degree-granting                       | annual (Fall)   | PD                             |
| Majors (4-digit, aid cohorts)     | Scorecard FoS (have)          | `CIPCODE`,`CREDLEV`                      | 4-digit only                              | 3–4×/yr         | PD                             |
| Outcomes                          | Scorecard (have)              | `C150_4`,`MD_EARN_WNE_P10`               | high                                      | 3–4×/yr         | PD                             |
| Aid generosity                    | Scorecard (have, 0045)        | `NPT41..45_*`,`GRAD_DEBT_MDN`            | high                                      | 3–4×/yr         | PD                             |
| Closure / merger status           | IPEDS `HD`                    | `CYACTIVE`,`DEATHYR`,`CLOSEDAT`,`NEWID`  | 100%                                      | annual          | PD                             |
| **Deadlines**, **Greek life**     | Common Data Set               | §C13–C21, §F1                            | ~1,000 institutions, PDF                  | annual          | **unverified**                 |
| Trivia / mascot / founding        | Wikidata                      | SPARQL                                   | variable                                  | continuous      | CC0                            |

## Decisions this leaves for Ian

1. **Add IPEDS as a second federal source, or stay Scorecard-only?** One extra
   loader (`HD`+`IC`+`ADM`, ~1.5 MB of CSV, no auth) unlocks religion, ROTC,
   study abroad, disability, housing, athletics, Carnegie, closure status.
   Everything Scorecard-only cannot answer is in that file set.
2. **Program truth: Scorecard FoS (4-digit, aid-cohort) or IPEDS Completions
   (6-digit, census)?** Today's `college_programs` is the former; the Maine
   query works either way, but "English Literature specifically" needs the
   latter.
3. **Author a subject taxonomy over CIP, or expose raw CIP?** No public
   crosswalk exists; this is a ~60–100-row curated artefact someone must own and
   maintain.
4. **Rebuild cadence:** quarterly (tracks Scorecard) vs annual (tracks IPEDS) —
   with a possible exception path for closures.
5. **Non-federal attributes (deadlines, Greek life): buy, scrape, or decline.**
   I would flag declining as a legitimate answer; CDS scraping is the single
   largest effort item in this whole report and its terms are unverified.
