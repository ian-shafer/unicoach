# Brief 0016 — spec: the report at a glance

Gate 2 artifact. Slice handle: **`glance`** (IDs `glance/NN/name`).

Gate-1 decisions D1-D8 are recorded in `brief.md` and are binding here. This
file adds the DISCOVER corrections, the slices, and gate-2 decisions D9-D13.

## Preamble — what DISCOVER corrected

DISCOVER ran inside the research phase: `research/row-shaped-seams.md` read
every block of `CostReportPage.kt` with `path:line` on each claim, and
`research/page-inventory.md` measured 62 distinct real renders of `main`. The
brief's foundations were right about what exists and wrong in six places about
what that lets a slice do. Ranked:

| #  | The brief (or the kickoff) assumed                                                     | The code says                                                                                                                                                                                                                                                                                                                                                                                                                   | Consequence for the slices                                                                                                                                                                              |
| -- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| X1 | The `AssuranceTierCopy` tier sentences are byte-frozen against an immutable prompt row | They are frozen by `AssuranceTierCopyTest.kt:113-118, 171-194`; no prompt row or seed carries them. What `SystemPromptCatalogTest.kt:3283-3308` pins against the served prompt is the six `FigureStatusCopy.agentlessStatementOf` sentences, which the page prints inside blanks (`blankFor`) and program gap marks (`ProgramReturnCopy.kt:209-211`)                                                                            | Both freezes hold. **A blank or withheld table cell carries its status sentence verbatim** — never a dash, never "N/A", never a reworded version. The kinds section is untouched.                       |
| X2 | Every block's prose comes from a seam the coach also speaks                            | Only the borrowing block prints a shared sentence list verbatim (`BorrowingWire.blockOf`, `BorrowingWire.kt:250`, page-only, its sole caller `CostReportPage.kt:2243`). The coach reads `objectOf` (`CollegeCostChatTool.kt:803`). The award grid and National Merit blocks have **no copy seam at all**: every string is private to the page, and the values are already row types (`AutomaticAwardBand`, `PrintableMeritRow`) | The row seam is one new value beside `blockOf` for borrowing; the grid and National Merit are page work plus test rewrites; merit and program rows read fields the coach already reads                  |
| X3 | Tests pin sentences the conversion must keep                                           | `CostReportPageTest.kt:1393-1398, 1486-1490` assert every `block.sentences` string in wire order; `:2255-2258, 2316, 2384, 2433, 2639-2660` assert exact `<li>` strings; `:2731, 2757` count `<li>`; `:2673-2674` asserts a words-only National Merit row carries no `report-merit-figure`                                                                                                                                      | These tests are **rewritten to assert rows, cells and captions**; none is deleted without its fact being asserted in the new shape                                                                      |
| X4 | A caption can name the filing in plain text                                            | The citation-in-link sweep (`CostReportPageTest.kt:1893-1909`) fails any `"<College>'s 2024-25 Common Data Set"` outside an `<a>`, and anchor counts are exact (`:1952` six; `CostReportProgramReturnTest.kt:320-327` three)                                                                                                                                                                                                    | **The caption is the one place a table links its filing**, through `sourceLink(citation)`; the body never links it again                                                                                |
| X5 | Column headers are free copy                                                           | The `"award"` sweep (`:664-676, 2505-2515, 2837-2850`) forbids the word outside the school's quoted words; `figureComparisonWordsIn` (`CostReportProgramReturnTest.kt:303`) forbids "gap", "higher", "lower", "more often", "less often", "worse odds"; the kinds block forbids digits inside a scope span and "rank", "score", "rating" (`:1763-1778`)                                                                         | Header words are chosen under those sweeps ("Scholarship", not "Award"; "Test score" is a column header outside the kinds block). A new once-per-page kinds sentence carries no digit in its scope span |
| X6 | The page already styles a caption                                                      | No report table has a `<caption>`; `.guide-table caption` (`site.css:483-487`) is scoped to `/fafsa`; stacking is `thead { display: none }` for every `.data-table` (`site.css:649-679`); the report sets `data-label` on every value cell (`CostReportPage.kt:1375, 787`) and `scope` on every `th`                                                                                                                            | `glance/01` adds the caption rule for `.report-table`; `glance/05` reworks the shared stacking rule and must verify `/fafsa` too                                                                        |

Two more facts no slice may re-litigate: **no live branch touches the page, its
CSS, its tests or the three copy seams**; `pipeline/rfc-258` rewrites
`CollegeCostChatTool.kt` wholesale, so no slice here edits that file (D8 rules
it out anyway). And the measurement test prints 1,404 words with its own
tokenizer (`CostReportPageTest.kt:1096-1113`, `ReportProse.kt`); every number a
slice quotes uses that tokenizer, so the printed `[MEASURE]` line and the spec
agree.

## Standing product decisions carried in

- **D12/brief 0001 (standing)** — value before ask. This page is read before any
  account exists and asks nothing; every slice keeps it that way and keeps the
  sign-up door (RFC 254) where it is, after the report.
- **D1/brief 0009** — information loss: no figure, status, assurance tier, cited
  year, publisher or unicoach assumption becomes unreachable; a restatement may
  go. Every slice below carries its block's fact checklist from
  `research/page-inventory.md` §3 and asserts each fact in the new shape.
- **D8/brief 0009 (standing)** — say it once; compression never removes
  reachability.
- **D23/brief 0011 (standing)** — the caveat budget: a limitation earns copy in
  proportion to how often it bites.
- **D7/brief 0008 (standing)** — no confidence score, percentage or rating,
  ever. A caption or header never ranks a publisher.
- **D8/brief 0016 (standing, this brief)** — on the Family Cost Report a figure
  lives in a cell; a sentence carries only what a cell cannot.

## Conventions every slice uses (decided in `glance/01`, inherited after)

- **Caption.** Every `<table>` the report renders opens with a `<caption>`
  naming the publisher(s) and cohort (D2, D3b), and carries one `data-publisher`
  attribute per publisher it holds. A Common Data Set caption contains the
  filing's `sourceLink` (X4).
- **Hedges hoisted.** A hedge that applies to every figure of a kind is said
  once per page in "What kind of numbers these are" (`tierKindsSection`), as a
  page-authored sentence with no digit in its scope span (X5). The slice that
  first needs a sentence adds it: K1 merit grant, K2 borrowing cohort, K3
  Scorecard no-year (`glance/01`); K4 National Merit paid/considered
  (`glance/03`); K5 program cohorts and dollar year (`glance/04`).
- **The rule and its ratchet (D3, D9).** `glance/01` lands the asserted rule: no
  currency figure in a `<p>` or `<li>` inside `section.report-school` except
  inside a `<td>`/`<th>` or inside the school's own quoted words, with two
  exemptions: a permanent one for the Scorecard debt sentence (its `<p>` carries
  one named class, D4), and a **named set of unconverted block classes**
  (`report-award-grid`, `report-national-merit`, `report-program-return`) that
  each later slice removes its own entry from. The slice that empties the set
  deletes the mechanism. The markup rules (caption first, `data-publisher`, no
  total row outside the price table, no table naming both "College Scorecard"
  and "Common Data Set") assert from `glance/01` on, over every table.
- **Blank cells.** A cell with no figure holds its status sentence verbatim (X1)
  — the frozen `FigureStatusCopy` sentence where the status is one of the six,
  the block's own absence sentence otherwise. Never empty, never a dash.
- **The coach is untouched (D8).** No sentence, label, payload key or prompt row
  changes; no prompt version is minted. A row seam shares the sentence builder's
  figure functions.

---

# Slices

### `glance/01/borrowing-and-merit-in-rows`

**Needs:**

- — (nothing: it is the first conversion and it lands the conventions every
  later slice inherits).

**What and why.** D1 (scope) and D5 (table for parallel figures): the clear win.
Today each school says what its graduates borrowed in a 24-word header and four
sentences, and what it gives as merit aid in two sentences with a 17-word hedge;
the same frame is re-read at every school (375 repeated words on the
three-school page, 197 of them in this block). After: a two-row merit table and
a two-row borrowing table, each captioned once with the school's filing, and the
hedges said once per page. D4: the Scorecard median debt figure stays one
sentence with its no-year hedge hoisted.

**Foundations.** `BorrowingWire.blockOf` / `BorrowingBlock`
(`BorrowingWire.kt:39, 250`), `BorrowingCoverage.Reported`,
`BorrowerCounts.Counted.share`, `privateAverageIsUnfiled` (`:296`);
`MeritAidWire` (`MeritAidWire.kt:55-56`, `shareLabel`/`averageLabel`
coach-shared); `meritBlock` (`CostReportPage.kt:1424`), `debtBlock` /
`shownDebtSentence` (`:1965, :2052`), `borrowingParagraphs` (`:2225`),
`tierKindsSection` (`:2160`), `sourceLink` (`:2299`); the never-sum test
(`CostReportPageTest.kt:1426, 1459-1461`) and the measurement test
(`:1096-1113`, `ReportProse.kt`); `site.css:334-342` (`.report-table`).

**Decided here.**

- A `BorrowingRows` value beside `blockOf` (shape sketched in
  `research/row-shaped-seams.md` "borrowingParagraphs → Smallest row seam"):
  citation, graduating class and its not-read-by-us flag, one `LoanRow` per loan
  type the filing speaks in `LoanType.SPOKEN_ORDER`, the private-not-filed flag.
  `objectOf`, `blockOf` and the new value read the same figure functions.
- The borrowing table: row header = loan type in the wire's own spoken words;
  column 2 = borrowers with the share, the graduating class said ONCE in the
  header ("Borrowed, of the 1,000 who graduated"), formatted with a thousands
  separator through the one number formatter the page already uses; column 3 =
  average owed, the moment ("at graduation") in the header. No total row.
  Caption = `sourceLink(citation)` + the cohort ("what the students who
  graduated from it that year borrowed"). The gap and variant sentences (not
  read by us; withheld; class not read; private not filed) are coach-voiced and
  have no cell shape: they render as one `<p>` each under the table, verbatim.
- The merit table: two rows (share of full-time freshmen who received non-need
  aid; average non-need aid), caption = `sourceLink(citation)` + "last year's
  full-time freshmen". The block is absent when there is no filing, as today.
- The Scorecard debt sentence loses its second sentence; its `<p>` carries one
  named class the rule exempts by name. It is never a row in any table.
- K1, K2, K3 in the kinds section, once per page, only when a block on the page
  needs them. The merit figures' assurance tier is spoken there too (D10).
- D7's drops in this block: "not an offer to this student" (the sources section
  says it), the per-school repeats of K1-K3.
- The asserted rule with its exemption set, the markup rules, and the printed
  prose share and per-school word count (D3). The exemption set is a named
  constant in the test, listed in this slice's RFC.
- `.report-table caption` styling: above the table, left-aligned, muted.

**Left to /work's spec phase.** The exact caption wording for a filing we no
longer link (RFC 200: same words, no anchor); whether `LoanType.spoken` is
exposed or a row-header helper is added beside it; the number formatter's name.

**Acceptance criteria.**

- Every fact in `research/page-inventory.md` §3 "Merit block" and "Borrowing
  block" is on the page: in a cell, a header, a caption, or (K1-K3) the kinds
  section, each asserted by name. The two sentence-list tests (X3) are rewritten
  to assert rows in wire order; no fact loses its test.
- The never-sum test still passes and gains the two markup rules: the merit and
  borrowing tables carry `data-publisher` for the Common Data Set only; no
  table's text names both publishers; no row outside the price table is a total.
- The caption is the only anchor to the filing (X4): the anchor count test stays
  exact.
- The asserted rule passes with the three unconverted blocks exempted by name;
  the test prints `[MEASURE] … visible words, … in tables, prose share …` for
  the three-school render, never asserts the share.
- The coach's `objectOf` payload, `blockOf` sentences, `MeritAidWire` labels and
  every prompt row are byte-identical before and after (D8); no prompt version
  is minted.
- **Reachable:** the Family Cost Report link a student shares — the door parents
  already walk through; and the same figures reach a linked parent's coach
  unchanged through RFC 250's seam.
- **Value before ask (standing D12):** the page asks nothing; the sign-up door
  stays after the report.

**First-session test.** A parent opens the link, scrolls past the price table,
and reads in two rows what graduates of that school borrowed and owed, with the
school's own filing named once above them.

### `glance/02/the-price-table-says-its-basis`

**Needs:**

- BLOCKS glance/01/borrowing-and-merit-in-rows — the caption and
  `data-publisher` conventions, the caption CSS and the rule's exemption
  mechanism are landed there; this slice adds captions to the two tables that
  exist today and must not re-decide them.

**What and why.** D1's two top-of-page defects and D7's drops around the price
table. Today the side-by-side table ("The schools side by side") says "per year"
nowhere; the at-home "$0" note ("our assumption, not a figure this school
published") is printed in every school's cell AND in the assumption list; each
school's price table is preceded by a residency sentence and, at a single-tier
school, by a 24-word reading instruction about its own row label.

**Foundations.** `summaryTable` (`CostReportPage.kt:736`), `summaryHeaderRow`
(`:765`), `priceTable` (`:937`), `tuitionTierLine` (`:909`), `schoolSection`
(`:852`), `assumedNoteFor` (`:1196`), `assumptionList` (`:698`),
`SingleSchoolBasis`; the assumption tests (`CostReportPageTest.kt:228, 423`) and
the one-school hint test (`:979`).

**Decided here.**

- The side-by-side table gains a caption carrying the unit once ("Per year") and
  `data-publisher` for the Scorecard.
- Each price table gains a caption that carries the unit and the residency basis
  ("Per year, at the in-state tuition and fees this family would pay at this
  public school"); the separate residency `<p>` goes. On a one-school report,
  where the assumption list already states the basis, the caption still says the
  unit.
- The at-home note moves to the living-at-home column header, once per table
  ("housing and food counted as $0, our assumption"); the per-cell note goes;
  the assumption-list bullet stays (it is the page's statement of the
  assumption, the header is the figure's label).
- D7: the generic tier sentence is dropped. The district-price sentence and the
  two-figures-no-state sentence state an absence and stay as a `<p>`.
- The price table's "Total per year" row stays the page's one total row, and the
  rule names it.

**Left to /work's spec phase.** Caption wording per `CollegeResidencyBasis` arm
(the exhaustive `when` stays exhaustive); whether the one-school report's
caption repeats the basis the list above states (0009 D1 says a restatement may
go).

**Acceptance criteria.**

- Every fact in `research/page-inventory.md` §3 "Price table and its intro
  lines" and "Summary table" is on the page, asserted by name: the unit, the
  residency basis per school, the at-home assumption beside its figure, every
  absence sentence verbatim.
- The assumption tests still pass: the six comparison assumptions are stated in
  full above the tables; nothing band-specific or number-changing moves (0009
  D5).
- The in-state clause is still emitted only where a public school is on the page
  (RFC 188's defect fix is not undone).
- The printed `[MEASURE]` line is quoted in the run record before and after.
- **Reachable:** the Family Cost Report link.

**First-session test.** A parent sees "per year" above the first table they
read, and the $0 for living at home says in its column header, once, that it is
our assumption.

### `glance/03/scholarships-in-rows`

**Needs:**

- BLOCKS glance/01/borrowing-and-merit-in-rows — the caption convention and the
  rule's exemption set; this slice removes `report-award-grid` and
  `report-national-merit` from that set.
- CONFLICTS glance/02/the-price-table-says-its-basis — both edit
  `CostReportPage.kt` and `CostReportPageTest.kt`. A rebase risk, not an order.

**What and why.** D5: the automatic-scholarship grid (RFC 239) becomes a table,
and each National Merit row (RFC 247) becomes a definition list. Today the grid
is a `<ul>` of "Presidential — SAT 1200–1250 — $6,000 a year" lines (a real grid
runs to ~14 of them), and a National Merit row is five to seven sentences, nine
figure tokens in 71 words, with a 14-word guarantee sentence repeated per row.

**Foundations.** `awardGridBlocks` / `awardGridBlock` / `bandLine` / `paysOf` /
`scoreRangeOf` / `gpaRangeOf` (`CostReportPage.kt:1475-1750`),
`AutomaticAwardBand`, `BandScoreAxis.NoScore`, `readOnCitationOf` (`:1537`);
`nationalMeritBlocks` / `nationalMeritBlock` / `meritRowLines` and helpers
(`:1753-1960`), `PrintableMeritRow`, `PublishedPackage.Priced`,
`AwardGuarantee`, `FirstChoiceRule`; `AWARD_KIND_SENTENCE`,
`NATIONAL_MERIT_KIND_SENTENCE`, `baseRateSentenceOf` (`:1924`); the exact-string
and `<li>`-count tests (X3), the `"award"` sweep (X5), the no-student sweep
(`CostReportPageTest.kt:2380`), the words-only-row test (`:2673-2674`).

**Decided here.**

- The grid: one `<table>` per published grid (residency audience stays in the
  `h3`, as today). Columns: scholarship (the school's name for the band), test
  score (the band's range, or the published no-score row's label), GPA (bound or
  bounds), pays a year — a column is omitted when no band in the grid uses it.
  Row order = published order, unchanged. A money cell holds the school's own
  words verbatim where it published words ("tuition and fees"; "The award amount
  varies from $8,000- $12,000 annually"); the header unit never claims those
  words are per year. Caption = publisher + read date + the score requirement
  sentence (or "does not say") + the score deadline, verbatim as today. Header
  words are chosen under the `"award"` sweep.
- National Merit: one `<dl>` per published row, one `<dt>`/`<dd>` per fact: for
  (standing, entering class, audience), paid or considered (one word), a year,
  in total (with years), the school's words, first choice, apply by — a pair
  omitted when the row has no such fact; the award name first when the row has
  one. A words-only row has no money pair and no `$0` (the existing test is
  rewritten to the new class). K4 in the kinds section once: a row marked paid
  is paid to every student holding that standing who meets the school's terms;
  one marked considered is not promised. The kind sentence and the base-rate
  sentence stay as they are.
- Nothing about the student, still (brief 0014 D-F, RFC 239 D-E): the no-student
  sweep stays and is run on the new markup.
- The exemption set loses both classes.

**Left to /work's spec phase.** The exact column header words; how an empty cell
is represented in markup so the phone card never prints "GPA: " alone
(coordinate with `glance/05`: omit the cell, or mark it; the table must not
mis-align either way).

**Acceptance criteria.**

- Every fact in `research/page-inventory.md` §3 "National Merit row" and "Award
  grid" is on the page, asserted by name; the exact-string tests are rewritten
  to cells and pairs; `<li>` counts become row counts.
- The caption names the publisher and read date; the `"award"` sweep, the
  no-student sweep and the kinds-block bans all pass on the new markup.
- The asserted rule passes with only `report-program-return` still exempted.
- **Reachable:** the Family Cost Report link, for a school with a published grid
  or a National Merit row in the corpus.

**First-session test.** A parent whose student's list holds a school with a
published scholarship grid reads it as the school prints it: one row per band,
the amount in a column, the source and the deadline above it.

### `glance/04/programs-in-rows`

**Needs:**

- BLOCKS glance/01/borrowing-and-merit-in-rows — the caption and
  `data-publisher` conventions and the rule's exemption set; this slice removes
  `report-program-return`, the last entry, and deletes the mechanism (D9).
- CONFLICTS glance/02/the-price-table-says-its-basis — same two files.
- CONFLICTS glance/03/scholarships-in-rows — same two files, and both add a
  kinds sentence.

**What and why.** D2: the page's one real violation of the shape rule. Today a
program with all three figures prints them in one 90-word paragraph with two
publishers and two cohorts named inline; a subject with three programs is ~230
words. After: one table per subject per school (program × earnings × federal
debt × monthly payment), the publisher named in each money column's header, the
cohorts and the dollar year said once per page in the kinds section, so the
caption is "Biology programs at <school>, which your student wants to study".
This block renders only behind the student's yes (RFC 248 `intended_subjects`
permission, read in the report's session); nothing about that gate changes.

**Foundations.** `programReturnBlocks` / `subjectReturnBlock` /
`subjectPrograms` / `programParagraph` (`CostReportPage.kt:2000-2050`),
`ProgramReturnCopy` (`subjectLeadOf`, `subjectLayoutOf`, `programLabelOf`,
`programSentencesOf`, `coveredFiguresOf`, `COVERAGE_SENTENCE`, `SOURCES`; the
private `markLabelOf`, `spokenPublisherOf`, `spokenYearsOf` at
`ProgramReturnCopy.kt:222-289`), `ProgramReturnProgram`, `ProgramReturnFigure`,
`ProgramFigureProvenance` (what `CollegeCostChatTool.putHeldProgramFigure`
reads, `:206-225`); `fieldOfStudySources` (`CostReportPage.kt:2393`);
`CostReportProgramReturnTest.kt` (anchor count `:320-327`, comparison-words
sweep `:303`, cohort assertions `:179-189`).

**Decided here.**

- One table per (school, subject), caption = the subject lead's facts ("Biology
  programs at Lakeshore State University, which your student wants to study"),
  `data-publisher` for both publishers. Columns: program (row header, the
  published title), median earnings a year four years after finishing (header
  names the U.S. Treasury), median federal loan debt at finishing (header names
  the federal student loan system), median monthly payment on a standard 10-year
  plan (same). No total row.
- K5 once per page in the kinds section: the two cohort ranges and the inflation
  base year, from `ProgramFigureProvenance`, never typed. The existing coverage
  sentence, tier scope and sources paragraph stay.
- A withheld, not-reported, not-applicable, not-collected or no-figure cell
  holds its sentence verbatim (X1: three of them are frozen). An imputed figure
  keeps its figure in the cell and its "estimated this figure; the school did
  not report it" sentence beneath the table, once per table. A program with no
  published figures is one spanning row with its sentence; a subject the school
  does not run, or whose every figure is withheld, keeps today's one `<p>` (no
  figure, no table).
- `markLabelOf`, `spokenPublisherOf`, `spokenYearsOf` become the cell and header
  helpers (non-private members of `ProgramReturnCopy` or a sibling
  `ProgramReturnRows`), read by the page; the coach's structured fields are
  untouched (D8).
- The exemption set is emptied and the mechanism deleted; the rule stands alone
  with its one named exemption (the Scorecard debt sentence).

**Left to /work's spec phase.** Whether the two-publisher caption also repeats
the publishers (the headers already name them); the footnote marker for an
imputed figure.

**Acceptance criteria.**

- Every fact in `research/page-inventory.md` §3 "Program returns" is on the
  page, asserted by name; the anchor count stays exactly three; the
  comparison-words sweep passes over headers and captions too.
- No `<p>` on the page carries three currency figures; the asserted rule passes
  with no exemption set in the test.
- The permission gate is unchanged and its tests untouched: never asked and
  refused hide the block, granted shows it.
- The coach's `putHeldProgramFigure` payload is byte-identical (D8).
- **Reachable:** the Family Cost Report link, for a student who has said yes to
  showing their subjects.

**First-session test.** A parent whose student shared their subjects reads, per
program, three figures in three labelled columns, and learns once at the foot of
the page which years those figures describe.

### `glance/05/tables-on-a-phone`

**Needs:**

- PREFER glance/01/borrowing-and-merit-in-rows — the rule "two-column tables
  stay grids" has nothing to verify on until a two-column table exists; the CSS
  could land first, checked only on the price table, and we would rather check
  it on the tables it was written for.
- CONFLICTS glance/01/borrowing-and-merit-in-rows — both edit `site.css`
  (`glance/01` adds the caption rule).

**What and why.** D6: today every `.data-table` becomes one card per row below
640px with `thead { display: none }`, which over-stacks a two-column table and
drops the table's semantics for a screen reader. After: a table with three or
more data columns stacks; a table with one row-header column and at most two
data columns stays a grid; a stacked table keeps its roles, a visually hidden
header row, and its caption above the first card; an empty cell is never printed
as a bare label. The FAFSA page shares the rule and is verified too.

**Foundations.** `site.css:271-288` (`.data-table` grid), `:334-342`
(`.report-table`), `:644-687` (the 640px stacking rule and `data-label`),
`:483-487` (`.guide-table caption`); the renderer sets `data-label` on every
value cell (`CostReportPage.kt:1375, 787`); `/fafsa`'s `ExplainerPage.kt` table
(RFC 238); `research/table-anatomy.md` rules T11-T15 and T18.

**Decided here.**

- Which tables stack is marked in the markup by the renderer (a class on the
  table), derived from the table's column count at render time, never a
  per-table hand-set flag; the CSS stacks only that class.
- A stacked table keeps `role="table"`, rows `role="row"`, headers
  `role="columnheader"`/`role="rowheader"`, cells `role="cell"`; the header row
  is visually hidden, not `display: none`; the `<caption>` keeps its computed
  display and precedes the first card; `data-label` equals the column header's
  text character for character; a cell with no value is hidden on the card.
  Numeric columns stay right-aligned wherever the table is a grid.
- Print does not stack: the print stylesheet shows the header row.
- Verified for real at 375px and at desktop width on the report (every table the
  slices above produce, on the three-school fixture and the richest National
  Merit and award-grid renders) and on `/fafsa`; the run record carries the
  screenshots.

**Left to /work's spec phase.** The class name; whether the roles are set in the
DSL by one helper shared with `/fafsa`'s table or in each renderer; the hiding
mechanism for an empty cell (omit vs `:empty`).

**Acceptance criteria.**

- A test over the rendered markup: every table with three or more data columns
  carries the stacking class, every table with two or fewer does not, every
  stacked table carries the roles, every `data-label` equals its header text.
- Screenshots at 375px of the report and of `/fafsa` in the run record; a
  two-column table is a grid, a wide one is cards with the caption above the
  first.
- No horizontal page scroll at 375px on either page.
- **Reachable:** the Family Cost Report link, opened on a phone, which is where
  a parent most likely opens it; and the FAFSA page.

**First-session test.** A parent opens the link from a text message and compares
the two loan rows at a glance, without a card per row.

---

## Gate 2 decisions

Defaults pre-chosen; "approve" takes all of them.

**D9 — the rule lands first, with a shrinking exemption set.** _Default:_
**`glance/01` lands the asserted rule with a named set of the three unconverted
block classes exempted; each later slice removes its own class; the slice that
empties the set deletes the mechanism.** The Scorecard debt sentence is a
permanent, named exemption (D4). _Alternative:_ land the rule last, in a slice
of its own, once every block is converted — nothing enforces the shape while the
work is in flight, and a block converted early could regress unnoticed.

**D10 — the merit figures get their kind sentence, once per page.** _Default:_
**yes, in `glance/01`.** Today the merit figures (a Common Data Set self-report)
are the one figure kind on the page with no assurance-tier sentence anywhere;
the page says what kind of number the federal debt figure is and not what kind
the merit figure is. The row seam hands the kinds section a covered figure for
them, so the frozen voluntary-self-report sentence is said once per page under
its scope, and its "We link to the college's own file so you can see it" clause
is already true of the caption link. Costs about 25 words once. _Alternative:_
not this brief; record it in LEARN as a defect found. _Reason for the default:_
brief 0008's whole point was that every figure says what kind of number it is,
and the gap was found while measuring.

**D11 — facts a table could add, but D1 does not ask for.** _Default:_ **add
none.** The merit filing's two counts (`fullTimeFreshmen`,
`nonNeedMeritRecipients`) are on the coach's wire and not on the page today; the
borrowing graduating-class count already prints and keeps printing (once, in the
header). 0009 D1 is about not losing facts; adding a fact is a product call this
brief does not make. _Alternative:_ print the merit counts in the merit table's
header ("of 1,900 full-time freshmen") — consistent with the borrowing header,
four more words per school.

**D12 — order and waves.** _Default:_ **`glance/01` alone first; then
`glance/02`, `glance/03` and `glance/04` in any order, one at a time or with a
rebase (they CONFLICT on two files); `glance/05` after `glance/01` by
preference.** The slice that lands last among 03 and 04 deletes the exemption
mechanism, whichever it is. `slice-board` is the answer to "what may I start";
this paragraph is a plan.

**D13 — where the FAFSA page's stacking change is verified.** _Default:_ **in
`glance/05`, both pages at 375px and desktop width, screenshots in the run
record, before the slice lands.** The shared rule is the reason the brief's
constraint 5 said "verify, not assume". _Alternative:_ give `/fafsa` its own
stacking rule and leave it as it is — two rules for one answer, the drift the
CSS comment at `site.css:644` was written against.

## Wave board (for the reader; `slice-board` is the answer)

| Wave | Slices                                             | Why here                                                                                                  |
| ---- | -------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 1    | `glance/01`                                        | lands the conventions and the rule                                                                        |
| 2    | `glance/02`, `glance/03`, `glance/04`, `glance/05` | all unblocked by 01; 02/03/04 conflict on two files, 05 conflicts with 01 on CSS and prefers to follow it |

## Deferred, with reasons (not dependencies)

- **The top of the page** (brief candidate C: a shorter assumption list; merit
  and debt columns on the side-by-side table). Not a slice. The prose floor
  after this brief is ~744 standing words, so the three-school page bottoms near
  65% prose; going lower means changing what a parent sees first, which deserves
  a decision after a parent has been watched reading this page.
- **A one-screen summary card** (candidate D). Rejected at gate 1: a second
  place saying every figure, which 0009 D8 forbids.
