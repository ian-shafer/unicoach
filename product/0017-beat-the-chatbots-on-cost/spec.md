# 0017 — Beat the chatbots on cost: spec and slices

**Handle:** `cost` (brief 0017). Slice IDs are `cost/NN/name`. One slice of this
beat lives in brief 0006 under its own ID, `shape/09/exchange-participation`,
unparked by this brief (gate 1 outcome, question 1).

Gate-1 decisions D1–D7 are in `brief.md` and bind every slice below. Standing
rules from earlier briefs that bind here: the money vocabulary (0003), value
before ask (0001 D12), DDL at the approval stop for any new table (0001 D10),
the coach never labels or scores a school (0011), every figure names its
publisher, kind and year (0008).

## DISCOVER — what the code says, against `main@48018319`

Read before the slices; where a slice and this preamble disagree with the code,
the code wins.

1. **The year a family is told comes from two pins, and a test refuses a bump
   until the transcription moves.**
   `CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR =
   AcademicYear(2024)` (2024-25
   charges) and `BLENDED_AVERAGE_VINTAGE =
   AcademicYear(2023)` are asserted
   against `db/seed/scorecard/dictionary-variable-sources.csv` by
   `ScorecardDictionaryPinTest` ("the published-charge year is the year the
   dictionary states for all eight charge columns"; "the blended-average vintage
   is the year the dictionary states for COSTT4_A and the NPT4 family"). The
   Scorecard release of 2026-11-02 (dictionary "Released October 15, 2026")
   dates the eight charge columns to **2025-26** and `COSTT4_A` / `NPT4_*` to
   **2024-25**. `bin/fetch-scorecard` discovers the release stamp rather than
   pinning it, so the fetch itself needs no edit.
2. **Charges are served from the Scorecard where it prices an arrangement
   completely, else from IPEDS IC_AY (2023-24).** `price_figures` keys on
   `(college, concept, residency, arrangement, academic_year)` — year is in the
   key, source is not (`db/schema/0083`), so two years coexist today and a bump
   adds no migration. An IPEDS IC_AY bump is NOT in this brief (D1): the file
   family was renamed to `COST1_{yyyy}` and the newest is 2024-25.
3. **The Common Data Set is already a `MoneySource` member**
   (`COMMON_DATA_SET("common_data_set")`), and `bin/fetch-cds-seed` normalises
   four fact groups (H2A merit aid, C7 factors, deadlines, section H aid policy)
   from the collegedata.fyi corpus, keyed by `schema_version`. Section G is not
   read. In the 2025-26 template, G1 asks for the **2026-27** full-time
   undergraduate tuition, required fees, and food and housing (first-year and
   all-undergraduate columns, in-state and out-of-state for publics); G5 asks
   for estimated books and supplies, housing only, and other expenses by living
   arrangement (on campus / commuter at home / commuter not at home). The corpus
   holds `G.1*` for 119 schools in 2025-26, 51 publics with an out-of-state
   figure. The repo's seed has 366 distinct `unit_id`s.
4. **Residency tiers exist; no exchange does.** In-state, out-of-state and
   in-district are served as tiers by `CollegeCostChatTool` (RFC 157, migration
   `0075`); the in-district rule in the prompt ("a labelled price beside the
   other two, never as the tuition and fees line inside a total") is the
   sentence shape an exchange tier reuses. `ForbiddenCostArithmeticTest` forbids
   subtraction only; the parent money plan's `Math.multiplyExact` is the
   multiplication precedent. Derivation belongs in the `:service` cost domain,
   never in the loader.
5. **The cost service carries each school's automatic awards; the chat tool
   never emits them.** `CollegeCostService.automaticAwards` is read only by the
   Family Cost Report page. The emit order in `CollegeCostChatTool` (breakdown →
   chosen arrangement → housing flag → vintage labels) is load-bearing: any new
   figure-bearing key goes ABOVE `putVintageLabels`; a key that carries no
   `CostField` may go below.
6. **The calculator link is in the data and nowhere else.** IPEDS HD carries
   `NPRICURL`; `IpedsLoader.REQUIRED_HD_COLUMNS` does not read it and
   `college_ipeds` has no column for it. The link-status pattern exists:
   `external_documents` with the `link_status` domain, `url_status` and
   `url_checked_on` (RFC 213), and `bin/check-link` prints the HTTP status of
   URLs handed to it.
7. **No federal field says whether a public gives nonresidents need-based aid.**
   `aid_policy.csv` has 22 facts, none about residency; CDS H6 is about
   nonresident aliens; H14 only lists residency as a criterion. The awards seed
   (`db/seed/automatic-awards/awards.csv`, table
   `create-college-automatic-awards.sql`) is the curated-fact precedent:
   `publisher, source_url, archive_url, checked_on, expires_on`, a
   `residency_basis` key and an `as_published` column.
8. **The four-year arithmetic is one function.** `MoneyPlan.of` and
   `FamilyMoneyPlanChatTool.payloadOf` (public) build the parent's plan; length
   is a flat four from `HD.ICLEVEL`; the all-years statement is one constant.
   `StudentToolRegistry` registers no money-plan tool.
9. **The prompt order is pinned by test.** `prompts/coach-system-prompt.md` says
   "say those five lines first … above the table" and "still come first";
   `SystemPromptCatalogTest` asserts those strings in the SERVED body. A prompt
   change is authored in the source file and cut by `bin/prompt-seed` at land;
   each landing slice mints its own version (v51 is the tip).
10. **The follow-up offer is enum order.** `PrecisionOffer` offers residency,
    then income, then living plan, each only while unanswered. In the test
    session the first two were answered, so living plan was the only offer the
    tool could make — the brief's fourth complaint is about the order of the
    prose, not the offer.

## Slices

Order lands value first and each later slice reads what the earlier one changed.
The aha — a student sees a price within a year of the school's own page, with
the exchange and the next step beside it — is live end to end after `cost/02`
and `shape/09`.

---

### `cost/01/the-latest-release`

**Needs:** —

**What and why.** Every College Scorecard figure is served under the year the
publisher's own dictionary states for it, read from the release that was fetched
— never a year typed into Kotlin or hand-transcribed into a CSV. Moving to a
newer release then costs a fetch and an ingest and nothing else, and the first
such move is this slice's land: the newest release on the day it runs. Serves D1
("the cheapest year we will ever buy") read under D15: the release D1 names by
date was the newest on the day D1 was written; the decision is the newest
release, every time.

**Foundations.** DISCOVER 1–2: `bin/fetch-scorecard` already discovers the
release stamp rather than pinning it and records it in PROVENANCE; the
publisher's `Most_Recent_Inst_Cohort_Map` sheet of
`CollegeScorecardDataDictionary.xlsx` dates every variable. Today that sheet is
hand-transcribed into `db/seed/scorecard/dictionary-variable-sources.csv` and
pinned by two Kotlin constants and `ScorecardDictionaryPinTest` (RFC 183),
because RFC 173 declined an xlsx reader. `price_figures` keys on
`academic_year`, so two years coexist and a newer release adds no migration.

**Decided here.** The year on a Scorecard figure has one home: the publisher's
dictionary as fetched, from the same release as the data, with PROVENANCE
recording both. The two constants and the hand transcription go, or become
artefacts the fetch writes and no human edits. A variable the new dictionary
renames, drops, or dates to an earlier year than the one served is a located
fault at ingest naming the variable, never a silent blank or a silent back-date.
The served year label on every figure follows the data; no copy changes. **Left
to /work:** how the dictionary is read — the RFC either reverses RFC 173's
no-xlsx-reader decision and says why, or finds the same sheet in a form the
publisher ships that the JVM reads today; what guard replaces RFC 183's pin test
(the thing to keep: a re-dating that moves a family's figure to another year
shows in the diff of a fetched artefact, never only in a served answer); and
whether the IC_AY fallback rows stay served where a new Scorecard row is
incomplete (DISCOVER 2 says the precedence already does this; confirm by test).

**Acceptance criteria.**

- **Door:** the coach's `college_cost_profile` tool on iOS and the web chat, and
  the Family Cost Report page — unchanged doors, the newest figures.
- Fetching and ingesting a newer release changes the year a family is told
  without a code or seed edit — pinned by a test that ingests a dictionary
  dating a served column to a later year and sees that year served.
- A dictionary that renames, drops, or back-dates a variable we serve fails the
  ingest, naming the variable.
- No year literal for a Scorecard figure lives in `college/` or `service/`
  Kotlin, main or test; the fetched dictionary is the one home, and PROVENANCE
  records the release stamp and the dictionary's digest. _Appended 2026-11-05
  (D17): the fourteen search and admissions figures stored on `colleges` with no
  year keep their constants, pinned to the fetched dictionary, until
  `cost/07/the-figures-carry-their-year` deletes them._
- No new table, no migration, no prompt change.
- **First-session test:** a Washington student with UW on their list asks what
  it costs; every year label in the answer equals the year the fetched
  dictionary states for that column, and the tuition and fees figure matches the
  fetched row for UW to the dollar.

---

### `cost/02/the-schools-own-next-year`

**Needs:**

- PREFER cost/01/the-latest-release — a school's figure sits a year or two ahead
  of the federal one; land the latest release first so the pair reads one year
  apart rather than two.

**What and why.** Where a school's newest Common Data Set filing states charges
for a year later than the federal figure's — section G asks, by the template's
construction, for tuition, required fees, food and housing, and the G5 estimates
for the academic year after the one the filing covers — show the filing's figure
beside the federal one, with the school as publisher, the year the filing
states, and the read date. The year is read from the filing (its template
version says which year section G asks for), never typed. Serves D1's second
route: the only structured source a year ahead, arriving through the
publisher-kind-year seam we already have. Where no filing exists, nothing
changes; where a newer filing arrives, the fetch takes it and the year label
moves with it.

**Foundations.** DISCOVER 3: `bin/fetch-cds-seed` fact groups and
`schema_version` map, `MoneySource.COMMON_DATA_SET`, the figure seam (RFC 177
`MoneySourceCopy`, RFC 179 assurance tiers — a CDS figure is
`VOLUNTARY_SELF_REPORT`), `price_figures` keyed on year.

**Decided here (D9).** The school's figure is a **labelled line beside** the
federal figure, never a replacement: "the school's own <year> tuition and fees,
from its Common Data Set filing, read <date>", both read from the filing and the
fetch, never typed. An arrangement total is built from section G **only when the
filing fills every line of that arrangement**; otherwise the lines are shown and
no total is. **Never a mixed-year total**: a total is one publisher and one
year. Where the federal and the school's figures describe the same year and
differ, both are shown (RFC 149's rule: "say both, never hide either"). The cost
tool emits the new figures above `putVintageLabels` (DISCOVER 5). The report
page shows the same line. **Left to /work:** the G5 living-arrangement mapping
onto our three arrangements (G5 has commuter-at-home and commuter-not-at-home;
our at-home arrangement counts no housing and food), and whether the fetcher's
drift guard needs a per-group threshold for a group that is new.

**Acceptance criteria.**

- **Door:** the coach's cost tool (iOS and web chat) and the Family Cost Report
  page.
- A school whose newest filing states G1 shows that tuition and fees as a
  labelled line beside the Scorecard's figure, each under its own year; the
  publisher is the school, the kind is self-report, the year is the one the
  filing states, and the read date is said once per answer.
- A newer filing (the next template year) moves the year label and the figure by
  a fetch alone — pinned by a test over two template years; no year literal for
  a CDS figure lives in Kotlin or the prompt, and the template-year map the
  fetcher already keeps by `schema_version` is the one home.
- A school with no filing, or a filing that leaves G1 blank or writes a date
  instead of a figure, shows nothing new and says nothing about it.
- No total mixes years or publishers; a section-G total appears only when the
  filing fills every line of that arrangement — pinned by test.
- The new fact group has its own drift guard, header assertion and PROVENANCE
  entry like the four existing ones.
- Coach copy: one new prompt line saying the school's own figure the way the
  in-district tier is said — beside, never inside a total. Minted as a new
  prompt version at land.
- **First-session test:** a student with Oregon State on their list asks what it
  costs; the answer gives Oregon State's own out-of-state tuition and fees for
  the year its newest filing states, beside the federal figure for the year the
  Scorecard states, each with its year and publisher, and the on-campus total is
  from one publisher and one year.

---

### The exchange tier is brief 0006's `shape/09/exchange-participation`, unparked here

Not a slice of this brief: it is declared once, in
`product/0006-money-in-unicoach-shape/spec.md`, where its `Needs:` line lives
(its BLOCKS and PREFER edges have landed) and where this brief appended three
refinements and a `CONFLICTS cost/05/the-table-first` edge. This section says
what the unparking decided, so a reader of this brief need not open 0006.

**What and why.** D2 (the exchange tier, read from the school) executed on the
slice brief 0006 parked for exactly this moment. The three refinements appended
to its text: (a) the **award basis in the school's own words** — automatic,
automatic consideration, competitive, with the share where the school publishes
one — on an own-enumeration column with an `as_published` twin; (b) the
**deadline**; (c) the coach says it as **"if admitted under WUE"**, a labelled
possibility beside the out-of-state tier, **never as the price, never inside a
total**, and always with its basis and deadline. The roster is read from each
school's own exchange page as the publisher; WICHE's finder locates which
schools to read and is never ingested (its terms forbid commercial reuse without
written consent). WUE first; MSEP, NEBHE and SREB rows only when a school on a
student's list is in one. A school with no row says nothing about exchanges:
absence is unknown, not "not a member", until the roster is marked complete for
a compact (D10).

**First rows.** The WUE members among the test's six — Western Washington
(automatic consideration, GPA 3.25, "highly competitive") and Oregon State
("most competitive scholarship", not automatic, about 30% of eligible
applicants, 3 February deadline) — then the rest of WUE's roster as a human
reads each school's page.

**Acceptance criteria** (in addition to 0006's): the door is the coach's cost
tool and the report page; a Washington student with Oregon State on their list
is told the exchange exists, its basis and its deadline, and the capped price as
a labelled possibility that never enters a total; a student from a state the
roster does not list as eligible for that school is told nothing about the
exchange (the eligible-states column decides, never the coach); DDL at the
approval stop. **First-session test:** the Washington student above.

---

### `cost/06/the-newer-release`

**Needs:**

- PREFER cost/01/the-latest-release — "served release" is what this slice
  compares against; after `cost/01` it is one fetched artefact, before it the
  comparison also has to read two Kotlin pins.

**What and why.** Nothing today tells anyone a newer College Scorecard release
or a newer Common Data Set corpus exists; the fetchers compare the live stamp to
PROVENANCE only when someone runs them. Under D15 staleness is a fact the system
holds, not a thing an operator remembers: a periodic job discovers the newest
release stamp of each source and records it beside the served one, and a newer
one is visible where an operator looks. Ingest stays a human act (D16).

**Foundations.** `bin/fetch-scorecard`'s discovery step and
`bin/fetch-cds-seed`'s corpus read, each already separable from the download;
`db/seed/*/PROVENANCE.json` as the served stamp; the `:cron` process and
`periodic_jobs` table (RFC 97) for the job; the admin manual triggers (RFC 100)
as the operator surface precedent.

**Decided here.** Discovery only: the job reads one stamp per source and writes
it; it downloads nothing and ingests nothing. The served stamp is read from the
same PROVENANCE the fetchers write, never a copy. **Left to /work:** where the
comparison is shown (the admin surface or the daily log — one place, named in
the RFC), the cadence, and whether the two sources share one job.

**Acceptance criteria.**

- **Door:** operator-facing only; no user-visible change.
- The job records, per source, the newest stamp the publisher offers and the
  stamp served, and the two are readable in one place with a date.
- A publisher that cannot be reached is a dated failure on that source, not a
  stale "newest" that reads as current.
- No table beyond what the job needs; DDL at the approval stop if any.
- **First-session test:** with PROVENANCE at one release and the publisher at a
  later one, the job's next run shows the two stamps and their dates in the
  named place.

---

### `cost/07/the-figures-carry-their-year`

**Needs:**

- BLOCKS cost/01/the-latest-release — the ingest stamps each figure with the
  year read from the fetched publisher dictionary, through the reader `cost/01`
  lands; without it there is no year to stamp.

**What and why.** Fourteen College Scorecard figures the coach quotes — the
undergraduate headcount, admission rate, SAT average, the six SAT and ACT band
scores, the four-year completion rate, the three eight-year completion rates and
the transfer-out rate — are columns on `colleges` with no year beside them. The
year the coach says for them is a constant in Kotlin
(`ScorecardOutcomeCitations`, `ScoreBandCitation.COHORT_TERM`), pinned against
the fetched dictionary by a test from `cost/01` on (D17). A fact about stored
data lives in code, linked to the data by nothing the database can check. Each
figure becomes a row that carries its own academic year, written by the same
load that writes the value, and `colleges` sheds fourteen columns. Serves D15
("the latest data, always") and D17 (the database stands alone).

**Foundations.** `price_figures` (RFC 158): one row per college per named price
per academic year, with `source` / `source_variable` provenance — the shape this
slice extends to the non-money figures. Migration 0094, which moved the money
columns off `colleges` and `colleges_versions` and shrank the history trigger.
`college_search_index` (migration 0064), rebuilt wholesale from `colleges` by
the `search-index` phase. The dictionary reader `cost/01` lands (RFC 274;
`ScorecardDictionary` in its design), which parses each served variable's period
at ingest.

**Decided here (D17).** One table, `college_figures`, one row per college per
figure. Five typed value columns, exactly one set per row, and the one set is
the one the figure's unit names:

```sql
CREATE TABLE college_figures (
    college_id        UUID             NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    figure            TEXT             NOT NULL,
    share             DOUBLE PRECISION NULL,
    sat_total_score   SMALLINT         NULL,
    sat_section_score SMALLINT         NULL,
    act_score         SMALLINT         NULL,
    headcount         INTEGER          NULL,
    academic_year     academic_year    NOT NULL,   -- the year the figure describes; for a cohort, the year it entered
    source            TEXT             NOT NULL,   -- provenance, as price_figures: 'scorecard'
    source_variable   TEXT             NOT NULL,   -- provenance: 'ADM_RATE'
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT college_figures_pkey PRIMARY KEY (college_id, figure),
    CONSTRAINT college_figures_figure_check CHECK (figure IN (
        'undergrad_enrollment', 'admission_rate', 'sat_average',
        'sat_reading_25th_percentile', 'sat_reading_75th_percentile',
        'sat_math_25th_percentile', 'sat_math_75th_percentile',
        'act_composite_25th_percentile', 'act_composite_75th_percentile',
        'completion_rate_150pct_4yr', 'completion_rate_8yr',
        'completion_rate_8yr_pell', 'completion_rate_8yr_non_pell',
        'transfer_out_rate_4yr')),
    CONSTRAINT college_figures_one_value_check
        CHECK (num_nonnulls(share, sat_total_score, sat_section_score, act_score, headcount) = 1),
    CONSTRAINT college_figures_share_figures_check
        CHECK ((share IS NOT NULL) = (figure IN ('admission_rate', 'completion_rate_150pct_4yr',
            'completion_rate_8yr', 'completion_rate_8yr_pell', 'completion_rate_8yr_non_pell',
            'transfer_out_rate_4yr'))),
    CONSTRAINT college_figures_sat_total_figures_check
        CHECK ((sat_total_score IS NOT NULL) = (figure = 'sat_average')),
    CONSTRAINT college_figures_sat_section_figures_check
        CHECK ((sat_section_score IS NOT NULL) = (figure IN ('sat_reading_25th_percentile',
            'sat_reading_75th_percentile', 'sat_math_25th_percentile', 'sat_math_75th_percentile'))),
    CONSTRAINT college_figures_act_figures_check
        CHECK ((act_score IS NOT NULL) = (figure IN ('act_composite_25th_percentile',
            'act_composite_75th_percentile'))),
    CONSTRAINT college_figures_headcount_figures_check
        CHECK ((headcount IS NOT NULL) = (figure = 'undergrad_enrollment')),
    CONSTRAINT college_figures_share_range_check       CHECK (share BETWEEN 0 AND 1),
    CONSTRAINT college_figures_sat_total_range_check   CHECK (sat_total_score BETWEEN 400 AND 1600),
    CONSTRAINT college_figures_sat_section_range_check CHECK (sat_section_score BETWEEN 200 AND 800),
    CONSTRAINT college_figures_act_range_check         CHECK (act_score BETWEEN 1 AND 36),
    CONSTRAINT college_figures_headcount_range_check   CHECK (headcount >= 0),
    CONSTRAINT college_figures_source_nonempty_check          CHECK (source <> ''),
    CONSTRAINT college_figures_source_variable_nonempty_check CHECK (source_variable <> '')
);
```

`figure` is an own enumeration: the CHECK above and exactly one Kotlin enum. An
entering-term cohort (a fall term) is stored as the academic year it opens and
spoken from it. The fourteen columns leave `colleges` and `colleges_versions`
and the history trigger, as 0094 did for money. The rejected shapes and their
reasons are in D17. **Left to /work:** how readers pivot rows back to one line
per college (the search index rebuild and the result pages), and the order of
the column drop against the first ingest that fills the table.

**Acceptance criteria.**

- **Door:** the coach's college search, similar-colleges and admissions answers
  on iOS and the web chat — unchanged doors; every dated figure in them reads
  its year from its own row.
- `ScorecardOutcomeCitations`' period values, `ScoreBandCitation.COHORT_TERM`
  and their pins against the dictionary are deleted; no year literal for a
  Scorecard figure lives in `college/` or `service/` Kotlin, main or test —
  `cost/01`'s criterion, completed (D17).
- A test ingests a dictionary that dates a figure one year later and sees that
  year on the row and in the coach's answer.
- `colleges` holds no figure column; the search index rebuilds from
  `college_figures` with the same results on the committed fixtures.
- **DDL at the approval stop** (0001 D10): the table above, unless /work's RFC
  finds a reason to amend it, in which case the amendment is presented.
- **First-session test:** a student asks the coach for colleges like UW; every
  admission-rate and graduation-rate year in the answer equals the
  `academic_year` stored on that college's row for that figure.

---

### `cost/03/the-next-step`

**Needs:**

- CONFLICTS cost/02/the-schools-own-next-year — both add a line to the coach
  prompt's money section and both add a key to the cost tool's result. A rebase,
  not an order.

**What and why.** One sentence per out-of-state public, from three facts we can
hold (D3): the school's **net price calculator link**, loaded from the IPEDS
header file and link-checked, withheld when dead; the school's **automatic merit
grid**, which the service already holds and the chat tool will now emit; and,
where a human has read the school's own aid page on a dated visit, **whether it
gives need-based institutional aid to nonresidents**, as a curated fact. Where
we hold none of the three, the coach says the calculator is the school's own
answer and stops. Serves D3; turns "no comparable figure" into "here is what to
do".

**Foundations.** DISCOVER 5–7: `CollegeCostService.automaticAwards`, the
`NPRICURL` column in HD, `college_ipeds`, the `external_documents` link-status
pattern and `bin/check-link`, the awards seed's provenance quartet and
`as_published` column.

**Decided here (D11).** (a) The calculator URL is stored on `college_ipeds` (one
column, migration) and carried as an `external_documents` row so it has
`url_status` and `url_checked_on`; the coach says the link only when its status
is resolving, and says "the school's own calculator" never "an estimate". (b)
The chat tool emits the awards grid under its own key, below `putVintageLabels`
because it carries its own citation and no `CostField` (RFC 148 D7 precedent).
(c) The nonresident aid stance is a new curated seed and table on the awards
pattern: `unit_id`, `need_based_aid_to_nonresidents` as an own enumeration
(`yes` / `no` / `not_stated`) with an `as_published` twin, the provenance
quartet, rows only where a human read the page. First rows: the four California
and Oregon schools in the test. DDL at the approval stop. **Left to /work:**
whether (c) is a column on the awards table or its own table — argued in the RFC
against the awards table's one-row-per-award grain.

**Acceptance criteria.**

- **Door:** the coach's cost tool (iOS and web chat); the report page gains the
  calculator link under each out-of-state school.
- For UC Berkeley, a Washington student's answer carries one sentence: whether
  Berkeley gives nonresidents need-based aid (its own words), its grid if any,
  and its calculator link — and never a dollar figure we did not read.
- A dead or unknown-status calculator link is not said; the sentence degrades to
  the facts we hold.
- A school with none of the three gets the one closing sentence and nothing
  invented.
- Coach copy: one new prompt line; minted at land.
- **First-session test:** the Washington student with the six-school list asks
  what the list costs; each of the four out-of-state schools ends with its
  next-step sentence, and the two in-state schools do not.

---

### `cost/04/all-the-years-for-the-student`

**Needs:**

- CONFLICTS cost/02/the-schools-own-next-year — both author the coach prompt's
  money section. A rebase, not an order.
- CONFLICTS cost/03/the-next-step — same.

**What and why.** A thin student tool, `money_plan`, over the parent's
arithmetic (RFC 261): each four-year school's published price and price after
grants over all the years, flat, labelled as ours beside the per-year figure,
never summed across schools, a reason instead of a number where the data cannot
support one. The student and the parent then read the same numbers. Serves D4.

**Foundations.** DISCOVER 8: `MoneyPlan.of`,
`FamilyMoneyPlanChatTool.payloadOf`, `ALL_YEARS_STATEMENT`,
`StudentToolRegistry`, the parent prompt's money-plan paragraph.

**Decided here (D12).** Same payload, same all-years statement, same refusal
arms; no growth assumption; one registry line; one prompt paragraph mirroring
the parent coach's ("never multiply, add or estimate a figure yourself"). The
coach offers the all-years view once a cost picture is on the table and the
student has not declined it — value before ask — and never opens with it. **Left
to /work:** the tool's exact name and whether `payloadOf` moves to a shared home
now that two tools read it.

**Acceptance criteria.**

- **Door:** the student's coach, after a cost answer: "Want to see what all four
  years come to?" — and on the student asking.
- Each four-year school gets an all-years published price and, where a net price
  exists for this family, an all-years price after grants, each labelled as ours
  with the one-year figure beside it; a two-year school gets the reason.
- No sum across schools; nothing multiplied by the model (prompt rule, pinned).
- **First-session test:** the Washington student, having seen the six-school
  cost table, says yes to the offer and gets six all-years figures, each
  labelled ours, with Western Washington's the lowest.

---

### `cost/05/the-table-first`

**Needs:**

- PREFER cost/02/the-schools-own-next-year — this slice re-orders the money
  section the three slices above add lines to; landing last means one re-read
  instead of three rebases.
- PREFER cost/03/the-next-step — same.
- PREFER cost/04/all-the-years-for-the-student — same.
- CONFLICTS shape/09/exchange-participation — both author the coach prompt's
  money section. A rebase, not an order.

**What and why.** The residency sentence stays above the table because it
decides which column applies; "who these figures describe", "what a net price
is" and the at-home $0 move **below** the table and are said **once** per
answer, with the publisher and year on the row or in one line under it. The
follow-up offer keeps its order and no aid-input question is added (D6). Serves
D5; copy only.

**Foundations.** DISCOVER 9–10: `prompts/coach-system-prompt.md` ("say those
five lines first … above the table", "still come first"),
`SystemPromptCatalogTest`'s served-body pins, `bin/prompt-seed`,
`PrecisionOffer` order.

**Decided here (D13).** The two prompt lines that pin the old order are
**re-pinned to the new order, not removed**: residency and the comparison basis
above, the three provenance sentences below, each said once. The report page
keeps its own order (brief 0016 D8: the coach and the page are separate
surfaces). No change to `PrecisionOffer`. **Left to /work:** the exact words;
the measure is the length and the order, not the phrasing.

**Acceptance criteria.**

- **Door:** the coach's cost answer on iOS and the web chat.
- The first line after the residency sentence is the table; the provenance
  sentences appear once, after it; the answer is **no longer than today's** (the
  transcript in `research/side-by-side-2026-11-04.md`: 2,215 characters of prose
  outside the table, whitespace collapsed — count the same way).
- `SystemPromptCatalogTest` asserts the new order in the served body.
- **First-session test:** the six-school question; the table is the second thing
  on screen and the whose-figures sentence appears once, below it.

---

## Gate 2 — decisions

**PASSED 2026-11-05 — Ian, verbatim: "Approve"**, after the amendment recorded
in D15 (presented 2026-11-04, amended and re-presented 2026-11-05). D8–D16 stand
as written.

Defaults pre-chosen. Approving costs one word; amending costs one line.

- **D8 — the slice set, order and waves.** Six `cost/` slices plus `shape/09`
  unparked in brief 0006. Wave 1: `cost/01`, `cost/03`, `cost/04`, `shape/09`
  (all startable now; `cost/03` and `cost/04` rebase onto each other's prompt
  line). Wave 2: `cost/02` and `cost/06` after `cost/01`. Wave 3: `cost/05`
  last, one re-read of the money section. The exchange tier is `shape/09`, not a
  new slice. _Amended 2026-11-05: `cost/06` added under D15–D16._ **DEFAULT:
  approve.**
- **D9 — the school's own figure is a labelled line beside the federal one,
  never a replacement, and a total is one publisher and one year.** A section-G
  total only when the filing fills every line of that arrangement; where the two
  publishers disagree about the same year, both are shown. A CDS figure is a
  voluntary self-report and says so. **DEFAULT: yes.**
- **D10 — exchange vocabulary and absence.** `award_basis` is an own enumeration
  (`automatic`, `automatic_consideration`, `competitive`, `unknown`) with an
  `as_published` twin; the rate rule is the published basis (150% of resident
  tuition) and a dollar figure is stored only when the school's own page states
  it; eligible states are a column, never the coach's memory; a school with no
  row says nothing about exchanges until the roster is marked complete for that
  compact. WICHE's dataset is never ingested. **DEFAULT: yes.**
- **D11 — the next step's three facts.** The calculator link on `college_ipeds`
  with an `external_documents` status row, said only when resolving; the awards
  grid emitted by the chat tool under its own key; the nonresident aid stance as
  an own enumeration (`yes` / `no` / `not_stated`) with the school's words, on a
  curated seed with the awards provenance quartet, rows only where a human read
  the page. New table or column: DDL at the approval stop. **DEFAULT: yes.**
- **D12 — the student's money plan is the parent's arithmetic.** Same function,
  same statement, same refusals, no growth assumption; offered once after a cost
  answer, never opened with. **DEFAULT: yes.**
- **D13 — the order is re-pinned, not unpinned.** The served-body tests move to
  the new order; the report page keeps its own. **DEFAULT: yes.**
- **D14 — the re-run is /chart's, not /work's.** After each land the coordinator
  re-runs the six-school question, records the answer in the brief's LEARN
  section against the five criteria, and marks the slice's criterion met or not.
  /work is never asked to run it. **DEFAULT: yes; `standing` for this brief.**
- **D15 — no year in a spec; the latest data, always.** Ian, at the gate on
  2026-11-05, verbatim: "We should not be writing specs for specific years. We
  should always strive to have the latest data". Applied: a slice names the
  mechanism that serves the newest release or filing, with the year read from
  the publisher, never a year; `cost/01` and `cost/02` as presented on
  2026-11-04 named years and are rewritten above. The rule reaches every source:
  `bin/fetch-ipeds` pins `SURVEY_YEAR` and the IPEDS charge file family has been
  renamed, and the CDS corpus fetch takes a version — both are recorded in the
  backlog as mechanism work under this rule; D1's deferral of buying the IPEDS
  year stands. **`standing`, for every brief. Recorded, not asked.**
- **D16 — how a newer release is noticed.** Today, only by running a fetch.
  Option A: a periodic discovery job records the newest stamp per source beside
  the served one, visible in one operator place; ingest stays a human act
  (`cost/06`). Option B: no machinery — the coordinator's standing re-run (D7,
  D14) begins with the fetchers' discovery step and reports staleness in the
  brief. Option C: periodic fetch and ingest in prod, unattended. **DEFAULT:
  A.** C is a later brief: an unattended ingest of a mid-cycle revision or a
  renamed variable is the failure `cost/01`'s located faults exist to catch, and
  it should catch them in front of a person first.

## Appended decisions (post-gate, 2026-11-05)

Gate decisions are immutable, so a changed decision is a new numbered decision
(chart non-negotiable 7). Raised by Ian after gate 2 closed.

- **D17 — the figures carry their year.** Raised at RFC 274's approval stop
  (`cost/01`), where Ian found fourteen search and admissions figures — the
  undergraduate headcount, admission rate, SAT average, the SAT and ACT bands,
  the completion and transfer-out rates — stored as columns on `colleges` with
  no stored year, so their year lives only in a Kotlin constant. Ian: the
  database should stand alone. Decided: those figures move to one
  `college_figures` table, one row per college per figure, five typed value
  columns with exactly one set per row, each row carrying its own
  `academic_year` (DDL in `cost/07/the-figures-carry-their-year`). Rejected, and
  why: a side table of periods keyed by figure name (data and year linked only
  by a name two languages must spell alike); a year column beside each figure on
  `colleges` (a 50-column table); seven tables (one shape repeated); one untyped
  value column with a CASE range check (the unit inferred from a name suffix).
  The work is its own slice, `cost/07`, blocked by `cost/01`. **`cost/01`
  continues without a schema change**: for these fourteen figures its "no year
  literal in Kotlin" criterion is met by pinning the remaining constants to the
  fetched dictionary, and `cost/07` deletes them. D8 stands as committed; this
  adds a seventh `cost/` slice to wave 2.

## Wave board (for the reader; `slice-board` is the answer)

| Wave | Slices                                                   |
| ---- | -------------------------------------------------------- |
| 1    | `cost/01`, `cost/03`, `cost/04`, `shape/09` (brief 0006) |
| 2    | `cost/02`, `cost/06`, `cost/07` (added post-gate, D17)   |
| 3    | `cost/05`                                                |
