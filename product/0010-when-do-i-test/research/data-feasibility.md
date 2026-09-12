# Brief 0010 — research: data feasibility

## Method

Repo-first, then public sources. I read the schema under `db/schema/`, the
fetchers in `bin/` (`fetch-cds-seed`, `fetch-scorecard`), the loaders in
`college/src/main/kotlin/ed/unicoach/college/`, `composeSystem` in
`service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`, and
`prompts/coach-system-prompt.md`. I counted the committed seed CSVs directly (no
DB run, no ingest). I then queried the live collegedata.fyi PostgREST corpus
read-only (`https://api.collegedata.fyi/rest/v1`, anon key scraped exactly as
`bin/fetch-cds-seed` does) for the C8/C9 field families and their definitions,
downloaded IPEDS `ADM2023` plus its dictionary, and read the College Board and
ACT date pages. No code was modified.

Unavailable: the `websearch` skill has no Serper key configured, so all web
evidence below is direct fetches of primary pages, not search results. I did not
run `bin/test`, so all counts come from committed CSVs and live APIs.

## 1. Testing data unicoach already has

| Item                               | Table.column                                                                    | Ingest                                                                                                                                     | Coverage                                                                                          | Vintage                                          |
| ---------------------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| SAT average (equivalent)           | `colleges.sat_average_equivalent_score`                                         | `bin/fetch-scorecard` -> `db/seed/scorecard/Most-Recent-Cohorts-Institution.csv` col `SAT_AVG` -> `CollegeScorecardLoader` (`COL_SAT_AVG`) | 1,037 of 6,273 rows in the committed CSV                                                          | Scorecard release `06102026` ([PROVENANCE.json]) |
| Test-score weight in admission     | `college_admission_factors.test_scores` (CDS C.704), domain `cds_factor_rating` | `bin/fetch-cds-seed` -> `db/seed/cds/admission-factors.csv` -> `CdsSeedLoader`                                                             | 369 schools; 125 `very_important`, 46 `important`, 152 `considered`, 38 `not_considered`, 8 blank | corpus fetched 2026-09-09                        |
| Application rounds + closing MM/DD | `college_deadlines` (round, `offered`, `closing_month/day`)                     | same fetcher                                                                                                                               | 831 rows                                                                                          | same                                             |

Two limits matter. `SAT_AVG` is a single blended number — useless for "am I in
range?" and useless for "when". `test_scores` at C.704 is the **weight** a
school puts on a score, not whether a score is **required**: 152 schools sit at
`considered`, which a student reads as "so do I need it or not?". Neither field
answers the seed question.

Staleness: the CDS seed is a committed CSV regenerated only when someone runs
the fetcher; `PROVENANCE.json` records `fetched_at: 2026-09-09`. The Scorecard
seed is release `06102026`. Both are frozen artifacts, reviewed by diff — good
for audit, but a testing feature that depends on them inherits a manual refresh.

## 2. Missing but ingestible

**CDS C8 (test policy) — present in the corpus, but thin and dirty.**
`bin/fetch-cds-seed`'s `FIELD_IDS` fetches H2/H4/H5/H8, `C.701`–`C.718`, and the
C14/C16/C21/C22 deadline fields. **It fetches no C8 and no C9 field at all.**
The corpus does hold them (`cds_field_definitions`, live):

- `C.801` "Does your institution make use of SAT or ACT scores in admission
  decisions…" — **180 rows / 149 distinct IPEDS ids**. Values are mostly clean
  (`Yes` 100, `No` 49) but ~13% is extraction junk (`YN`, `0.0027`,
  `ACT Composite`).
- `C.802/C.803/C.804` ("SAT or ACT" / "ACT Only" / "SAT Only") carry the
  four-way policy vocabulary: `Required`, `Recommended`,
  `Not required for admission, but considered if submitted`, `Not Used`.
  Coverage 144 / 73 / 74 rows, with a comparable junk rate (`/ADMS_CONSIDER`,
  bare decimals).
- `C.8E` (2024-25) / `C.8E01`+`C.8E02` (2025-26): **"Latest date by which SAT or
  ACT scores must be received for fall-term admission."** This is literally the
  seed question, per college. Coverage is **46 rows** and the values are badly
  extracted (`124`, `2026-06-01 00:00:00`, `YN`, `45509`, and a correct
  `6`/`15`).
- I found **no** "policy for the 2026 entering class" cell in the 2025-26
  schema; the closest is the free-text `C.8F` clarification field (4 rows).
  Treat that ask as not available.

**CDS C9 (score ranges) — present and good.** `C.901`–`C.916` are the percent
submitting and the SAT/ACT percentiles. Live counts: `C.905` (SAT composite
25th) 559 rows / **386 distinct IPEDS ids**, of which **309 overlap the 369
schools already in `admission-factors.csv`**; `C.907` 561, `C.911` 588, `C.914`
(ACT comp 25th) 581, `C.901/C.902` percent-submitting ~560 each. All 556 numeric
`C.905` values are inside 400–1600 — clean.

**IPEDS ADM2023 — the better source for both.**
`https://nces.ed.gov/ipeds/datacenter/data/ADM2023.zip` (+ `ADM2023_Dict.zip`),
1,972 institutions:

- `ADMCON7` "Admission test scores", **populated for all 1,972**, with an
  official three-value codebook: `1` Required (100), `5` Not required but
  considered if submitted / test optional (1,100), `3` Not considered even if
  submitted / test blind (772).
- `SATVR25/50/75`, `SATMT25/50/75`, `ACTCM25/50/75`, `SATPCT`, `ACTPCT` (994 /
  972 / 1,166 non-null).
- Of the 369 CDS-seed schools, 360 are in ADM2023; **360 have `ADMCON7`**, 298
  have `SATVR25`, 307 have `SATPCT`.

**Cheapest path of all: the file is already committed.**
`db/seed/scorecard/Most-Recent-Cohorts-Institution.csv` already contains
`SATVR25, SATVR75, SATMT25, SATMT75, SATVRMID, SATMTMID, ACTCM25, ACTCM75,
ACTCMMID, SATVR50, SATMT50, ACTCM50`
— 949 rows with `SATVR25`, 920 with `ACTCM25`. Percentile bands need **no new
fetch**: add columns to `colleges` and lines to `CollegeScorecardLoader`.
Scorecard does **not** carry `ADMCON7`, so test policy needs a new source.

## 3. Calendar data

There is **no machine-readable feed**. Both publishers put the dates in HTML
tables I read directly:

- SAT: <https://satsuite.collegeboard.org/sat/registration/dates-deadlines> —
  Aug 22, Sep 12, Oct 3, Nov 7, Dec 5 2026; Mar 6, May 1, Jun 5 2027, each with
  a registration deadline and a late-registration date; plus _anticipated_
  2027-28 dates.
- ACT:
  <https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html>
  — 7 national dates Sep 19 2026 … Jul 10 2027 with registration, late, standby
  and score-release dates; plus _projected_ 2027-28 dates.

So: hand-maintained. Cost is small (~15 rows/year) but the staleness risk is
sharp and **silent** — a hard-coded table quietly starts advising a student to
register for a date that has passed. Both publishers already print the next year
as "anticipated/projected", so ~2 years can be seeded, and the honest design is
a `valid_through` date after which the feature says "check the College Board
site" rather than guessing.

## 4. What the coach's context lacks — confirmed

`composeSystem` (CoachingService.kt:845) returns `prompt.body` plus, only when
present, open commitments, fit suggestions, and the money-profile block. There
is **no** date and **no** student profile line. `prompts/coach-system-prompt.md`
(158 lines) never mentions today, a grade, or a graduation year. The data exists
(`students.expected_high_school_graduation_year/_month/_day`, 0005).

Smallest honest addition: one more optional block in `composeSystem`, built from
values already loaded in the pre-flight transaction — two lines, e.g.
`Today is 2026-09-30.` and
`The student expects to finish high school in June
2027.` Precision must follow
the stored precision (year-only when month is NULL), and the clock must be the
injected `Clock` the service already uses, not `LocalDate.now()`, so it stays
testable.

## Verdicts

| Item                                         | Verdict    | Reason                                                                                                                             |
| -------------------------------------------- | ---------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Today's date + graduation year in the prompt | **EASY**   | Data already stored and already loaded; ~10 lines in `composeSystem` plus prompt wording.                                          |
| SAT/ACT percentile bands per college         | **EASY**   | Columns already sit in the committed Scorecard CSV; loader + schema only.                                                          |
| Test-required / optional / blind policy      | **MEDIUM** | Needs a new IPEDS ADM ingest (`ADMCON7`, 100% populated, official codebook). CDS C8 alone is too thin (149 schools) and too dirty. |
| Per-college "scores must arrive by" date     | **HARD**   | CDS `C.8E` has 46 rows and mostly unusable values. Better to derive from `college_deadlines` and say so.                           |
| SAT/ACT national test-date calendar          | **MEDIUM** | No feed; hand-transcribed table with an explicit expiry, refreshed yearly.                                                         |

## Implications for what to build

1. **Ship the context fix first.** Date + graduation year in `composeSystem` is
   the smallest change and unblocks every "when" answer, including ones we hold
   no new data for. Nothing else in this brief works without it.
2. **Get policy from IPEDS `ADMCON7`, not from CDS C8.** One new fetcher against
   a stable federal file gives a required / test-optional / test-blind answer
   for every college, with a published codebook. C8 is a fallback for nuance
   only.
3. **Percentile bands are nearly free — take them.** `SATVR25/75`, `SATMT25/75`,
   `ACTCM25/75` are already in the committed CSV, so "am I in range for my
   list?" costs a migration and a few loader lines.
4. **Hard-code the calendar, but make it expire loudly.** Seed two years of SAT
   and ACT dates with a `valid_through`; past that, the coach must say to check
   the publisher rather than invent a date.
5. **Never fabricate a per-college score deadline.** `C.8E` is not usable.
   Anchor the timeline on `college_deadlines` (which we already hold for 831
   rows) and on score-release lag (2–4 weeks ACT, published), and say which is
   which.
6. **Keep `test_scores` (C.704) as weight, not requirement.** Pair it with
   `ADMCON7` in any answer, or the coach will keep reading `considered` as a
   rule.
