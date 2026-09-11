# Research: verbosity of the public parent report (Family Cost Report)

Brief 0009 "say it once". Topic: the shared Family Cost Report HTML page.
READ-ONLY research. No repo file was changed.

## METHOD

1. Read `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`
   (1127 lines), its tests, and the wire types under
   `service/src/main/kotlin/ed/unicoach/coaching/costs/`.
2. **Rendered the page for real.** I made a throwaway `git worktree` of `HEAD`
   (`212fbe89`) at `/tmp/uc-render` — outside the repo — added one dumping test
   there that drives the same route the existing tests drive
   (`testApplication` + `FakeCostReportSource`, exactly as
   `CostReportPageTest.render()` does,
   `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt:77-87`),
   and ran `nix develop -c ./gradlew :public-web:test`. BUILD SUCCESSFUL in 11s.
   Output: `/tmp/report-3school.html` (23,260 bytes) and
   `/tmp/report-1school.html` (9,008 bytes). The worktree was deleted
   afterwards. **The sample below is real rendered output, not reconstructed.**
3. Parsed the HTML with BeautifulSoup and counted words/occurrences
   programmatically. Every count in this report is measured on that output.
4. Fixture caveat, stated honestly: the fixtures reuse `meritFixture` and
   `borrowingFixture`, so the merit and borrowing NUMBERS are identical at all
   three schools. In production the numbers differ; the repeated FRAMING
   sentences (which is what this report counts) do not.

Measured totals:

| Render    | visible words | HTML bytes |
| --------- | ------------- | ---------- |
| 1 school  | 798           | 9,008      |
| 3 schools | 1,793         | 23,260     |

Per-school marginal cost: **~404 visible words per school** (`report-school`
sections total 1,213 words over 3 schools). Fixed overhead (assumptions +
summary + sources) is ~564 words.

---

## 1. THE REAL RENDERED SAMPLE (1 school, complete)

Text extracted from `/tmp/report-1school.html`. `||` separates table cells.

```
Your student's college list
This report is live — it updates as your student updates their list.
What these figures assume
  These are averages for first-year, full-time students who received federal aid - not a quote for this family.
  Riverside State University is a public school, and the tuition and fees figure that applies to this family is its in-state one.
  Riverside State University publishes its published price and its price after a financial aid offer for students paying in-state tuition, which is what this family would pay there, so both figures are theirs.
  This school is priced for living on campus, renting off campus and living at home, and each way of living is quoted on its own below.
  Living at home, we count no food-and-housing cost: eating at home is not free, but it is not a new cost that enrolling creates, so the at-home total counts it as zero. That is our assumption, not a figure any school published.
  The published price figures shown for this school, which are tuition and fees and the parts of living cost it publishes, come from the 2023-24 academic year.
  The published price and the price after a financial aid offer shown for this school come from the 2022-23 academic year, and are averages blended across the ways of living.
  A net price is the published price minus the average grants and scholarships the school gave. Loans and work-study are never subtracted.
  The price after a financial aid offer below is the average for families with a household income of $48,001 to $75,000, which is the band on file.
Riverside State University
Springfield, CA
Riverside State University is a public school, and the tuition and fees figure that applies to this family is its in-state one.
For this school our source does not separate a district price from the state price, so the in-state figure is shown as it was published and we cannot say whether a lower district price exists.
Per year || living on campus || renting off campus || living at home
Tuition and fees || $12,000 || $12,000 || $12,000
Housing and food || $11,000 || $13,000 || $0 | our assumption, not a figure this school published
Books and supplies || $1,200 || $1,200 || $1,200
Other expenses || $2,500 || $2,800 || $3,000
Total per year || $26,700 || $29,000 || $16,200
The published price || $28,000
The likely price after a financial aid offer || $15,000 | the average for families with a household income of $48,001 to $75,000
The published price is this school's own published cost of attendance, an average blended across the ways of living, so it is not the sum of the parts above it. At a public school it, and the likely price after a financial aid offer, are figures for students paying in-state tuition.
Money this school gives for something other than financial need
25% of all full-time freshmen received non-need (merit) aid.
the average non-need (merit) aid was $12,000 - a grant the student never pays back, reported for last year's class, not an offer to this student.
Source: Riverside State University's 2024-25 Common Data Set.
Students who finished here carried a median of $21,000 in federal loans. The source publishes no year for this figure. This comes from federal loan and tax records, not from the college. It covers only students who got federal aid, and small numbers are blurred a little to protect privacy.
Riverside State University reports these borrowing figures for the students who graduated from it in 2024-25 -- not this year's freshmen, and not all its undergraduates.
Riverside State University reports that the students who graduated in 2024-25 and borrowed federal loans owed $20,747 on average by the time they graduated.
Riverside State University reports that 394 of the 1000 students who graduated in 2024-25 borrowed federal loans -- 39.4%.
Riverside State University reports that the students who graduated in 2024-25 and borrowed a private loan owed $43,865 on average by the time they graduated.
Riverside State University reports that 83 of the 1000 students who graduated in 2024-25 borrowed a private loan -- 8.3%.
Source: Riverside State University's 2024-25 Common Data Set.
Sources and what this is not
The cost, price and federal debt figures come from the U.S. Department of Education College Scorecard. The merit and borrowing figures come from each school's own Common Data Set, cited beside them. The federal debt figure and the school's own borrowing figures are different measures from different publishers over different groups of students, so they are never added together and never compared with each other.
These are averages for past students at each school. They are not an offer, and no figure here is a price this family has been quoted.
Only a school's own financial aid offer is a price for this family. Loans and work-study are never subtracted from any price here.
```

A per-school section from the 3-school render is identical in shape; only the
name and the figures change.

---

## 2. DUPLICATION INVENTORY (measured on the 3-school render)

### 2a. Repeated sentences and cell labels

`times` = occurrences in the 1,793-word body. `excess` = `(times-1) x words`,
i.e. what a "say it once" version would save if the block were stated once.

| block                                                                                | times | words each | excess |
| ------------------------------------------------------------------------------------ | ----: | ---------: | -----: |
| `SCHOOL_BLENDED_HINT` (whole hint, once per school)                                  |     3 |         51 |    102 |
| "the average for families with a household income of $48,001 to $75,000"             |     7 |         12 |     72 |
| `BLENDED_IN_STATE_CLAUSE` ("At a public school it, and the likely price...")         |     4 |         21 |     63 |
| assurance tier sentence "This comes from federal loan and tax records..."            |     3 |         30 |     60 |
| merit hedge "a grant the student never pays back, reported for last year's class..." |     3 |         18 |     36 |
| tier line "This school publishes one tuition price in what we hold; ..."             |     2 |         26 |     26 |
| "reports these borrowing figures for the students who graduated from it in"          |     3 |         12 |     24 |
| heading "Money this school gives for something other than financial need"            |     3 |         10 |     20 |
| "not this year's freshmen, and not all its undergraduates"                           |     3 |          9 |     18 |
| column headers "living on campus / renting off campus / living at home"              |     3 |          9 |     18 |
| "The source publishes no year for this figure."                                      |     3 |          8 |     16 |
| "our assumption, not a figure this school published"                                 |     3 |          8 |     16 |
| "of all full-time freshmen received non-need (merit) aid."                           |     3 |          8 |     16 |
| "Common Data Set." citation suffix (merit + borrowing, 2 per school)                 |     6 |          3 |     15 |

**Subtotal excess: 502 words = 28.0% of the whole page**, before counting the
per-sentence attribution frames below. At 6 schools the same blocks cost ~5/2 x
that, roughly **985 excess words**.

### 2b. Ian's example (a): tuition repeated per living situation

Measured, the tuition row is the SAME number in every column at every school:

```
Tuition and fees || $12,000 || $12,000 || $12,000
Tuition and fees || $30,000 || $30,000 || $30,000
Tuition and fees || $41,000 || $41,000 || $41,000
```

`$12,000` appears 8 times in the 3-school body. **Books and supplies has the
same shape** — `$1,200 | $1,200 | $1,200`, `$1,000` x3, `$1,100` x3 — and I did
not find that named in the brief, so add it.

This is not a coincidence of the fixture; it is structural (see §3.1). Only
HOUSING_AND_FOOD and OTHER_EXPENSES can vary by arrangement.

### 2c. Ian's example (b): the "<college> <year> Common Data Set reports ..." frame

Measured in the 3-school body:

- `"2024-25"` — **21 occurrences**
- `"reports that"` — **12 occurrences** (4 per school)
- `"who graduated in 2024-25"` — **12 occurrences**
- School name occurrences: Riverside 13, Northgate 12, Ashford 12.
- `"Common Data Set"` — 7 occurrences (2 `Source:` lines per school + the
  standing sentence in `report-sources`).

One school's borrowing block, verbatim, is five paragraphs each of which
re-states the school name and the cycle:

```
Riverside State University reports these borrowing figures for the students who graduated from it in 2024-25 -- not this year's freshmen, and not all its undergraduates.
Riverside State University reports that the students who graduated in 2024-25 and borrowed federal loans owed $20,747 on average by the time they graduated.
Riverside State University reports that 394 of the 1000 students who graduated in 2024-25 borrowed federal loans -- 39.4%.
Riverside State University reports that the students who graduated in 2024-25 and borrowed a private loan owed $43,865 on average by the time they graduated.
Riverside State University reports that 83 of the 1000 students who graduated in 2024-25 borrowed a private loan -- 8.3%.
Source: Riverside State University's 2024-25 Common Data Set.
```

The `report-debt` section measures **172 / 166 / 166 words** at the three
schools (7 paragraphs each) — **504 of 1,793 words, 28% of the page, is the
debt+borrowing block.**

### 2d. Repeats ACROSS the two tables

Every school's published price and net price is printed twice: once in the
cross-school summary and once in its own table. Measured: `$28,000` x2,
`$15,000` x2, `$52,000` x2, `$22,000` x2, `$60,000` x2, `$25,000` x2. Tuition is
printed a 4th time (summary + 3 arrangement columns).

### 2e. The residency sentence is printed twice on a one-school report

Measured in `/tmp/report-1school.html`: the sentence "Riverside State University
is a public school, and the tuition and fees figure that applies to this family
is its in-state one." appears **2 times** — once in the assumption list and once
as `report-school-residency`.

### 2f. HTML-level duplication (not visible, but it is bytes)

57 `data-label="..."` attributes, **1,737 bytes** in the 3-school page. Every
column label is emitted twice by design (`CostReportPage.kt:180-186` KDoc:
"every one of them is printed TWICE -- as a `<th>` in the desktop grid and again
in the cell's `data-label`"). That is a real mobile-layout requirement
(`public-web/src/main/resources/static/site.css:221`), not waste.

### 2g. A repeat that is also probably WRONG

`SCHOOL_BLENDED_HINT` (`CostReportPage.kt:228-231`) is an unconditional constant
printed under EVERY school's table (`:512`). It ends with
`BLENDED_IN_STATE_CLAUSE` — "At a public school it, and the likely price after a
financial aid offer, are figures for students paying in-state tuition." In the
render above, that sentence is printed under **Northgate University** and
**Ashford College**, both `CollegeControl.PrivateNonprofit`. It is a sentence
about public schools attached to a private school's table. Compressing this is
also a correctness win.

---

## 3. STRUCTURAL CAUSE OF EACH REPEAT

### 3.1 Tuition and books repeat because the MODEL is per-arrangement

`ArrangementCost` holds a `tuitionLine: CostLine?` per arrangement
(`service/.../CostBreakdown.kt`, `class ArrangementCost` — "The published
tuition figure this student's residency selects"). The figure it holds is
selected by `applicableTuitionFor(control)`, ONE field for the whole school, so
all arrangements carry the same line by construction.

Books is the same shape at the field level: `ComponentRole.fieldOf(arrangement)`
(`service/src/main/kotlin/ed/unicoach/coaching/costs/CostField.kt:291-292`) maps
BOOKS_AND_SUPPLIES to the single field `BOOKS_AND_SUPPLIES_PER_YEAR_USD`
(`CostField.kt:312`) for every arrangement. Only housing/food and other expenses
have three distinct fields (`CostField.kt:306-318`).

The renderer then does `arrangements.forEach Ellipsis` per row
(`CostReportPage.kt` `tuitionRow`, `componentRows`), so a school-wide figure is
printed once per column. **The row model, not the copy, is the cause.**

### 3.2 The CDS frame repeats because attribution is PER SENTENCE, by RFC decree

Every borrowing sentence is built in `BorrowingWire` and each one
re-interpolates `source.collegeName` and
`CdsCitation.cycleLabel(source.sourceYear)`: `cohortLabel`,
`graduatingClassLabel`, `averageDebtLabel`, `shareLabel`, `notReadByUsLabel`,
`gapLabel`, `privateNotFiledLabel` (`service/.../BorrowingWire.kt:333-425`).
`CdsCitation.citedAs` is
`"$collegeName's ${cycleLabel(sourceYear)} Common Data Set"`
(`service/src/main/kotlin/ed/unicoach/coaching/admissions/CdsCitation.kt:35`).

`CostReportPage.borrowingParagraphs` renders one `<p>` per sentence and then a
`Source:` line (`CostReportPage.kt` `borrowingParagraphs`). So N sentences = N
school names + N cycles. This is deliberate: RFC 175 D10 requires the attributed
voice (see §4).

### 3.3 Per-school hint / heading / hedge repeats because the unit of layout is the school

`schoolSection` (`CostReportPage.kt:504-515`) emits, unconditionally, per
school: `report-school-where`, `report-school-residency`, `tuitionTierLine`,
`priceTable`, `SCHOOL_BLENDED_HINT`, `meritBlock`, `debtBlock`. Nothing hoists a
sentence that is the same at every school to the page level.
`SCHOOL_BLENDED_HINT` is a `const` with no per-school variation at all.

### 3.4 The band note repeats because it is attached to the FIGURE

`netPriceCell` attaches
`"the average for families with a household income of
${netPrice.band.bracket}"`
to every net-price amount (`CostReportPage.kt:829-841`), and net price is
printed once in the summary and once per school table — plus the assumption list
already states the band ("...which is the band on file"). Hence 7 occurrences of
one 12-word phrase.

### 3.5 The assurance tier sentence repeats because it is composed per figure

`AssuranceTierCopy.composedStatementOf(statusStatement, tier)` joins the status
sentence and the tier sentence
(`service/.../canonical/AssuranceTierCopy.kt:54-57`). The page calls it in
`shownDebtSentence` per school and in `blankFor` per blank cell
(`CostReportPage.kt` `blankFor`, `shownDebtSentence`). With one debt figure per
school the ADMINISTRATIVE_RECORD sentence (30 words) is printed once per school.
On a school with several suppressed cells it would print several times.

### 3.6 The residency double-print is two call sites for one fact

`SingleSchoolBasis.statements` adds `residency.statement`
(`service/.../SingleSchoolBasis.kt:53,57`) and `schoolSection` prints
`residency.statement` again (`CostReportPage.kt:509`).

---

## 4. WHAT IS LOAD-BEARING AND MUST NOT BE LOST

Compression must keep every one of these. Each is an RFC decision, not style.

| Guarantee                                                                                                                                                                               | Where it lives                                                                              | RFC                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| A missing figure is a LABELLED BLANK, never a zero, never a neighbour's number; no total is summed from a partial set                                                                   | `NOT_REPORTED`, `NO_TOTAL`, `ArrangementCost.totalPerYearUsd`                               | RFC 149 D-F, `CostReportPage.kt:74-86,160`                                       |
| Never sum across vintages; an arrangement total mixes only one vintage                                                                                                                  | `ArrangementCost` refuses a mixed list                                                      | RFC 149 D-F.3 (`rfc/149-component-cost-split.md:190-195`)                        |
| Never compute `net_price - tuition`; the published price is NOT the sum of the parts                                                                                                    | `SUMMARY_HINT`, `SCHOOL_BLENDED_HINT`                                                       | RFC 149 D-F.1-2 (`rfc/149-component-cost-split.md:185-189`)                      |
| A figure carries the ACADEMIC YEAR it is on, and an undated figure carries none                                                                                                         | `ComparisonBasis.academicYears` statements; "The source publishes no year for this figure." | RFC 149 D-E (`rfc/149-component-cost-split.md:143-181`)                          |
| The publisher is named only where the sentence is about the publisher's ACT; our gap is never spoken as the school's silence                                                            | `blankFor` exhaustive `when` (`CostReportPage.kt` `blankFor`)                               | RFC 177 D3 (`rfc/177-one-hedge-seam.md:54-72`)                                   |
| The source list names ONLY publishers of figures actually shown, with a number in them                                                                                                  | `sourcesSection(profile.moneySources)` (`CostReportPage.kt:267,1081`)                       | RFC 177 D5 (`rfc/177-one-hedge-seam.md:89-110`)                                  |
| Every shown figure can carry an assurance-tier sentence; a tier is a slug + a sentence, never a number/score/rating                                                                     | `AssuranceTierCopy`                                                                         | RFC 179 D6, D10 (`rfc/179-assurance-tiers.md:194-218,261-268`)                   |
| Tier sentences name NO publisher (the publisher is named once, by `FigureStatusCopy`)                                                                                                   | `AssuranceTierCopy.statementOf`                                                             | RFC 179 D9 (`rfc/179-assurance-tiers.md:244-260`)                                |
| Every CDS/borrowing figure is spoken as the SCHOOL'S OWN CLAIM ("Amherst College reports that..."), never in the flat administrative voice; a test asserts no unattributed CDS sentence | `BorrowingWire.kt:333-425`                                                                  | RFC 175 D10 (`rfc/175-borrowing-at-graduation.md:120-135`)                       |
| The cohort is named out loud: the students who graduated in <cycle>, not freshmen, not all undergraduates                                                                               | `BorrowingWire.cohortLabel`                                                                 | RFC 175 D8 (`rfc/175-borrowing-at-graduation.md:101-107`)                        |
| Federal debt and CDS borrowing are never summed, differenced, or shown as one number; a school with no private figure is said to have none                                              | `debtBlock`, `sourcesSection`                                                               | RFC 175 D9 (`rfc/175-borrowing-at-graduation.md:109-118`)                        |
| A loan is never subtracted from a price; a debt figure is never presented as a price                                                                                                    | `sourcesSection` closing paragraph                                                          | RFC 175 D8, brief 0003                                                           |
| Loan types are separate and overlap; never add two                                                                                                                                      | `BorrowingWire.NOTE`                                                                        | RFC 175 D3 (`rfc/175-borrowing-at-graduation.md:55-61`)                          |
| The family's income band must be stated wherever a net price is shown                                                                                                                   | `netPriceCell` note + `MoneyBasis` line                                                     | RFC 142 / D-F, `CostReportPage.kt:56-62,839`                                     |
| The year we cite is asserted against the publisher, not re-typed                                                                                                                        | year constants pinned                                                                       | RFC 183 D1 (`rfc/183-the-year-we-cite.md:49-129`)                                |
| Ingest refusals mean some cells legitimately have no figure (freshman-count and multi-attribution refusals), so blanks must stay explainable                                            | seed refusal rules                                                                          | RFC 182 (`rfc/182-two-more-ingest-refusals.md:19-27`)                            |
| IC_AY publishes no with-family food-and-housing figure, so the at-home `$0` is OURS and must say so                                                                                     | `ASSUMED_BY_US`, `AT_HOME_ASSUMPTION_STATEMENT`                                             | RFC 161 finding 1 (`rfc/161-ipeds-ic-ay-canonical-charges.md:31-43`), RFC 166 §7 |

Two page-level rules also constrain any redesign (`CostReportPage.kt:52-72`):
**no student identity** and **no upgrade cue**.

---

## 5. CONCRETE COMPRESSION OPTIONS VISIBLE IN THE CODE

Ordered by measured saving per unit of risk.

### O1. Hoist school-wide rows out of the arrangement grid

Print "Tuition and fees" and "Books and supplies" ONCE per school, above the
per-arrangement columns (they are `wholeSchoolRow`-shaped facts, and
`wholeSchoolRow` already exists, `CostReportPage.kt:596-608`). Keep them inside
the total.

- Touches: `CostReportPage.tuitionRow`, `componentRows`, `priceTableHeaderRow`;
  `CostReportPageTest`.
- Saves: 6 of 9 tuition cells + 6 of 9 books cells in a 3-school render.
- Risk: **low-medium**. Books is only school-wide because `fieldOf` maps one
  field; the rule must be derived (`distinct().size == 1`, the policy
  `schoolWideTuition` already implements at `CostReportPage.kt` for the
  summary), never hard-coded per role, or a fourth arrangement with its own
  books field would silently collapse two different numbers into one.

### O2. Grouped citation footer for the borrowing block

State the claimant and the cycle ONCE ("Riverside State University's 2024-25
Common Data Set reports:") and let the four figures be a list that does not
re-name the school.

- Touches: `BorrowingWire.listSentences` / the label functions
  (`BorrowingWire.kt:333-425`), `CostReportPage.borrowingParagraphs`, plus the
  coach surface that shares the copy, and `BorrowingWireTest` +
  `CostReportPageTest.borrowingMarkers`.
- Saves: ~30 words per school of pure frame; the borrowing block is 28% of the
  page.
- Risk: **HIGH on honesty.** RFC 175 D10 is explicit that every borrowing
  sentence attributes the figure, and a test asserts it. A grouped header that
  scopes the whole list is arguably still attribution, but it is an RFC-level
  change and needs a new RFC, not a renderer edit. Do NOT do this silently.

### O3. Hoist the constant hints to the page level, and make the public clause conditional

`SCHOOL_BLENDED_HINT` is identical at every school and its
`BLENDED_IN_STATE_CLAUSE` tail is false-flavoured at private schools (§2g). Say
the blended-average warning once above the school sections, and emit the
in-state clause only where `cost.control is CollegeControl.Public`.

- Touches: `CostReportPage.kt:220-231,512`; `CostReportPageTest`.
- Saves: 102 words at 3 schools, and removes a public-school sentence from
  private-school tables.
- Risk: **low**. The warning is about the published price generally; RFC 149
  D-F.1 requires it be said, not that it be said N times.

### O4. Say the income band once

The band is already in the assumption list ("...which is the band on file").
Drop the per-cell 12-word note in favour of a short column-header marker, or
keep the note only in the summary table.

- Touches: `CostReportPage.netPriceCell` (`:829-841`), `NET_PRICE_COLUMN`.
- Saves: 72 words at 3 schools, growing with school count.
- Risk: **medium-high.** `CostReportPage.kt:100-118` is an explicit argument
  that a number printed beside a family's name is read as theirs whatever the
  note says. The `OverallAverage` note ("an overall average across all families,
  not a figure for this family") must NOT be hoisted or dropped — it is the
  dangerous case. Only the `BandSpecific` note is a candidate.

### O5. Merge the merit + debt + borrowing prose into one table per school

The merit block is 3 paragraphs (`meritBlock`, `CostReportPage.kt:948`) and the
debt block is 7 (`debtBlock`, `:981`); together ~220 of the ~404 words per
school. A two-column "what this school reports about money after graduation"
table with one shared citation footer keeps every figure and every cohort label
while dropping the repeated frames.

- Touches: `CostReportPage.meritBlock` + `debtBlock` + `borrowingParagraphs`,
  `site.css` (`report-merit`, `report-debt`), `CostReportPageTest`.
- Risk: **high**, same reason as O2 plus RFC 175 D9's "two publishers, two
  vintages, never summed" — a shared table makes summing look inviting. Any
  table must keep the two publishers visibly separate.

### O6. Collapsed disclosure (`<details>`) for the standing hedges

Put the standing, non-school-specific hedges (the assumption list, the
`report-sources` section, the assurance-tier sentences) behind an
open-by-default or clearly labelled `<details>`; keep every word, just not all
at once.

- Touches: `CostReportPage.assumptions` / `sourcesSection`, `site.css`. There is
  currently **no `<details>` anywhere in `public-web/src/main`** (grepped, zero
  hits), so this is new chrome.
- Saves: 0 words, but ~400 words of first-screen load.
- Risk: **low-medium.** Nothing is dropped, so no honesty guarantee is touched —
  but a hedge a parent must scroll-and-click to see is weaker than one they
  cannot avoid. The `report-sources` "these are not an offer" paragraph is the
  one I would NOT collapse.

### O7. Print the residency sentence once on a one-school report

Drop the duplicate at `CostReportPage.kt:509` when the assumption block is
`report-single-basis` (which already carries it, `SingleSchoolBasis.kt:53,57`).

- Touches: `CostReportPage.schoolSection`.
- Saves: ~25 words on the commonest report shape.
- Risk: **low**, but check the 2+-school path first: there
  `ComparisonBasis.residency` is a page-level sentence and the per-school line
  is the only place a specific school's basis is stated, so the line must stay.

---

## What I could NOT measure

- Real production data. All numbers above come from the test fixtures; the
  fixture reuses one merit and one borrowing filing for every school, so merit
  and borrowing FIGURES repeat in a way production would not. Sentence FRAMES
  repeat identically either way.
- Reading time / scroll length on a real phone. I measured words and bytes only.
- Whether any live school actually renders many blank cells at once (which would
  multiply the assurance-tier sentence beyond the 3 occurrences measured here).
