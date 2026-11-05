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

### `cost/01/the-scorecard-year`

**Needs:** —

**What and why.** Serve the College Scorecard release of 2026-11-02: tuition and
fees, housing and food, books and other expenses move from 2024-25 to
**2025-26**, and the published cost of attendance and every net price from
2023-24 to **2024-25** — one year fresher on both, for every school we hold, by
a fetch and a re-dated transcription. Serves D1 (fresher prices, two routes
now): "the cheapest year we will ever buy".

**Foundations.** DISCOVER 1–2: `bin/fetch-scorecard`,
`db/seed/scorecard/dictionary-variable-sources.csv`, the two
`CanonicalMoneyLoader` pins, `ScorecardDictionaryPinTest`, `price_figures` keyed
on `academic_year`.

**Decided here.** The transcription is re-read from the publisher's own cohort
map and re-dated column by column, never by a shared offset (the loader's own
comment). Both pins move only to the year the dictionary states. The served year
label on every figure follows the pin automatically; no copy changes. Any
Scorecard variable the new dictionary renames or drops is a located fault at
ingest, not a silent blank. **Left to /work:** whether the IC_AY fallback rows
for 2023-24 stay served where the new Scorecard row is incomplete (DISCOVER 2
says the precedence already does this; confirm by test).

**Acceptance criteria.**

- **Door:** the coach's `college_cost_profile` tool on iOS and the web chat, and
  the Family Cost Report page — unchanged doors, fresher figures.
- A cost answer for UW Seattle states tuition and fees for **2025-26** and a net
  price for **2024-25**, by year label, and the two pin tests pass against the
  re-dated transcription.
- The ingest's PROVENANCE records the release stamp it served from.
- No new table, no migration, no prompt change.
- **First-session test:** a Washington student with UW on their list asks what
  it costs; the answer's year labels read 2025-26 and 2024-25 and the tuition
  and fees figure matches the Scorecard's new row for UW to the dollar.

---

### `cost/02/the-schools-own-next-year`

**Needs:**

- PREFER cost/01/the-scorecard-year — a 2026-27 school figure beside a 2024-25
  federal one widens the gap this slice exists to close; land the bump first so
  the pair reads one year apart.

**What and why.** Where a school's Common Data Set filing states next year's
charges — the 2025-26 filing's section G gives **2026-27** tuition, required
fees, food and housing, and the G5 estimates — show that figure beside the
federal one, with the school as publisher, the year the filing asks for, and the
read date. Serves D1's second route: the only structured source a year ahead,
arriving through the publisher-kind-year seam we already have. Where no filing
exists, nothing changes.

**Foundations.** DISCOVER 3: `bin/fetch-cds-seed` fact groups and
`schema_version` map, `MoneySource.COMMON_DATA_SET`, the figure seam (RFC 177
`MoneySourceCopy`, RFC 179 assurance tiers — a CDS figure is
`VOLUNTARY_SELF_REPORT`), `price_figures` keyed on year.

**Decided here (D9).** The school's figure is a **labelled line beside** the
federal figure, never a replacement: "the school's own 2026-27 tuition and fees,
from its Common Data Set filing, read 2026-11-04". An arrangement total is built
from section G **only when the filing fills every line of that arrangement**;
otherwise the lines are shown and no total is. **Never a mixed-year total**: a
total is one publisher and one year. Where the federal and the school's figures
describe the same year and differ, both are shown (RFC 149's rule: "say both,
never hide either"). The cost tool emits the new figures above
`putVintageLabels` (DISCOVER 5). The report page shows the same line. **Left to
/work:** the G5 living-arrangement mapping onto our three arrangements (G5 has
commuter-at-home and commuter-not-at-home; our at-home arrangement counts no
housing and food), and whether the fetcher's drift guard needs a per-group
threshold for a group that is new.

**Acceptance criteria.**

- **Door:** the coach's cost tool (iOS and web chat) and the Family Cost Report
  page.
- A school with a 2025-26 filing that states G1 shows its 2026-27 tuition and
  fees as a labelled line beside the Scorecard's 2025-26 figure; the publisher
  is the school, the kind is self-report, the year is 2026-27 and the read date
  is said once per answer.
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
  costs; the answer gives Oregon State's own 2026-27 out-of-state tuition and
  fees from its filing beside the 2025-26 federal figure, each with its year and
  publisher, and the on-campus total is from one publisher and one year.

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

Defaults pre-chosen. Approving costs one word; amending costs one line.

- **D8 — the slice set, order and waves.** Five `cost/` slices plus `shape/09`
  unparked in brief 0006. Wave 1: `cost/01`, `cost/03`, `cost/04`, `shape/09`
  (all startable now; `cost/03` and `cost/04` rebase onto each other's prompt
  line). Wave 2: `cost/02` after `cost/01`. Wave 3: `cost/05` last, one re-read
  of the money section. The exchange tier is `shape/09`, not a new slice.
  **DEFAULT: approve.**
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

## Wave board (for the reader; `slice-board` is the answer)

| Wave | Slices                                                   |
| ---- | -------------------------------------------------------- |
| 1    | `cost/01`, `cost/03`, `cost/04`, `shape/09` (brief 0006) |
| 2    | `cost/02`                                                |
| 3    | `cost/05`                                                |
