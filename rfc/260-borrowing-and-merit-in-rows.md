# RFC 260 — Borrowing and merit in rows

**Slice:** `glance/01/borrowing-and-merit-in-rows` (brief 0016, _the report at a
glance_). **Status:** proposed. **Base:** `main@a8567607`. **Lane:** A (rfc).

## Executive Summary

On the Family Cost Report each school's merit aid and borrowing figures become
two small tables instead of six sentences. Each table opens with a caption that
links the school's own Common Data Set filing once. The hedges every school
repeated today are said once per page, in "What kind of numbers these are". The
federal median debt stays one sentence and loses its repeated no-year clause.

This is the first conversion in brief 0016, so it also lands what every later
`glance` slice inherits: the caption convention, the `data-publisher` attribute,
the asserted shape rule with its shrinking exemption set (D3, D9), and the
printed-not-asserted prose share (brief 0009 D20).

The coach is untouched (brief 0016 D8): `MeritAidWire.objectOf`,
`BorrowingWire.objectOf`, `BorrowingWire.blockOf`, every label sentence and
every prompt row are byte-identical before and after. The row seam is a new
value beside `blockOf` that reads the same figure functions.

## Context

Measured on `main` (`product/0016-the-report-at-a-glance/research/`): the
three-school page prints 1,404 visible words, 214 in tables. Each school's
borrowing block is a 24-word linked header plus four sentences, and its merit
block two sentences carrying a 17-word hedge. The frame repeats at every school:
375 repeated words on the page, 197 in these two blocks.

Six facts in the code fix the shape (spec.md X1–X6):

1. The six `FigureStatusCopy.agentlessStatementOf` sentences are pinned against
   the served prompt. A blank cell carries one verbatim, never a dash.
2. Only the borrowing block has a shared sentence builder the page prints
   verbatim (`BorrowingWire.blockOf`, page-only). The coach reads `objectOf`.
3. `CostReportPageTest.kt` asserts every `block.sentences` string in wire order
   at two places
   (`every borrowing figure on the page renders under the block's
   one attribution`,
   `the page and the coach speak the loan types in ONE order`). Both are
   rewritten to assert rows.
4. The citation-in-link sweep fails any `"<College>'s 2024-25 Common Data Set"`
   outside an `<a>`, and anchor counts are exact (six on the three-school page;
   three on the program page). The caption is the only place a table links its
   filing.
5. The `"award"` sweep forbids that word outside the school's quoted words;
   `FIGURE_COMPARISON_WORDS` forbids "gap", "higher", "lower", "more often",
   "less often", "worse odds"; the kinds block forbids digits inside a
   `report-kind-scope` span and the words "rank", "score", "rating".
6. No report table has a `<caption>`; `.guide-table caption` is scoped to
   `/fafsa`. The report sets `data-label` on every value cell and `scope` on
   every `th`.

## Detailed Design

### 1. The row seam: `BorrowingRows` beside `blockOf`

In `service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt`:

```kotlin
/** One school's borrowing section as ROWS: the attribution, the class, one row per loan type the filing speaks. */
data class BorrowingRows(
  val citation: CdsCitation,
  /** CDS H.401, formatted by the page; null when the filing carries no count we hold. Whether that is OUR gap is a `GraduatingClassNotReadByUs` in [gaps]. */
  val graduatingClass: Int?,
  /** In [LoanType.SPOKEN_ORDER]; only loan types with at least one figure. */
  val rows: List<LoanRow>,
  /** The absences said under the table, in order: the unread class, each loan type's gap in SPOKEN_ORDER, the private absence. */
  val gaps: List<BorrowingGap>,
)

/** One absence said under the table, kept as its typed cause; `sentenceOf` is the one place it becomes words. */
sealed interface BorrowingGap {
  data object GraduatingClassNotReadByUs : BorrowingGap
  /** Built only from `gapStatusOf`, so [status] always has a `gapLabel` sentence. */
  data class LoanTypeStatus(val loanType: LoanType, val status: FigureStatus) : BorrowingGap
  data object PrivateNotFiled : BorrowingGap
}

data class LoanRow(
  val loanType: LoanType,
  /** The wire's own words for the loan type, mid-sentence form ("federal loans"). */
  val spokenLoanType: String,
  val averageOwed: OwedAverage,
  /** The CONSISTENT pair's count and share, or null (not reported, a class of zero, or the school's own contradiction, D6). */
  val borrowed: BorrowedShare?,
)

/** The average cell: the figure, the reason the filing's answer is not shown, or the filing's own silence. */
sealed interface OwedAverage {
  data class Shown(val usd: Int) : OwedAverage
  /** [statement] is `FigureStatusCopy.agentlessStatementOf(status)`, resolved once in `rowsOf`. */
  data class Withheld(val status: FigureStatus, val statement: String) : OwedAverage
  data object NotInFiling : OwedAverage
}

/** The borrowed cell's count and its NON-null share; the class it divides by is `BorrowingRows.graduatingClass`. */
data class BorrowedShare(val borrowers: Int, val share: Share)

fun rowsOf(coverage: BorrowingCoverage.Reported): BorrowingRows

/** Exhaustive, no `else`: `graduatingClassNotReadByUsLabel()`, `gapLabel(loanType, status, citation)`, `privateNotFiledLabel()`. */
fun sentenceOf(gap: BorrowingGap, citation: CdsCitation): String
```

Rules, all shared with `objectOf` and `blockOf` by construction:

- A loan type gets a row when `figures.of(loanType)` has an average, or a
  `BorrowerCounts.Counted` with a non-null `share`. A loan type with only a gap
  status gets no row; its gap is in `gaps`.
- `borrowed` is the `Counted` pair's count and share only when `share != null`;
  a `ContradictsGraduatingClass` is null here exactly as `putLoanType` emits
  nothing for it.
- `averageOwed` is `Shown` when the filing has the average, else `Withheld` for
  the `gapStatusByLoanType[loanType]` whose `gapKey` is non-null (its sentence
  resolved there, failing loudly with the loan type, status and `citedAs` if the
  status has none), else `NotInFiling`.
- `gaps` is `GraduatingClassNotReadByUs` first when the coverage says so, then
  `LoanTypeStatus(loanType, status)` for each loan type with a non-null
  `gapKey`, in `SPOKEN_ORDER`, then `PrivateNotFiled` when
  `privateAverageIsUnfiled()`. Mapped through `sentenceOf`, these are the
  sentences `blockOf` emits minus the average and share sentences, in
  `blockOf`'s order. The page renders each as
  `sentenceOf(gap, rows.citation) + "."` in its own `<p>`.
- `spokenLoanType` is the existing private `LoanType.spoken`, exposed through
  the row rather than made public: a surface that holds a row holds the words.

The output of `blockOf` and `objectOf` does not change. Their internals do in
one place: both now ask the private `gapStatusOf` filter that `rowsOf` asks, so
the three seams share one rule for which statuses are gaps. `HEADER_TAIL` is
built from a new `COHORT_HEDGES` constant with its value unchanged, so the
page's K2 sentence can reuse the same hedge words. `rowsOf` reads `figures.of`,
`BorrowerCounts.Counted.share`, `gapKey`, `gapLabel`, `privateAverageIsUnfiled`,
`graduatingClassNotReadByUsLabel` and `privateNotFiledLabel`, the same
functions.

A `BorrowingWireTest` case asserts that for every fixture in the existing
borrowing test set, `blockOf(c).sentences` equals the sentences `rowsOf(c)`
implies, in order: `averageDebtLabel` and `shareLabel` per row, interleaved with
`gaps` mapped through `sentenceOf`, in `SPOKEN_ORDER`. The two seams cannot
narrate one filing differently.

### 2. The merit seam

`MeritPractice` already is the row value (`shareOfAllFullTimeFreshmen`,
`averageNonNeedAid`). No new type.

`MeritAidWire` gains one constant: the tier of these figures, read from the one
type that says what a Common Data Set cell is:

```kotlin
/** What KIND of number a merit figure is: the school's own filing, unaudited (RFC 179). */
val ASSURANCE: AssuranceTier = PublishedCell.SelfPublished(variable = CollegeMeritAid.CDS_SECTION).assurance
```

The variable id is the Common Data Set section the merit dao maps, H2A. No
constant named it, so `CollegeMeritAid` (the row the dao maps) gains
`const val CDS_SECTION = "H2A"`, and the dao's two KDocs cite it. The page never
types a tier.

### 3. The page: `CostReportPage.kt`

**Count formatting.** A new `ed.unicoach.common.util.WholeCount` with
`fun spoken(count: Int): String = "%,d".format(Locale.US, count)`, beside
`WholeDollars`' pattern and for its reason: one home for the thousands
separator. Used for the graduating class in the borrowing header.

**Merit block** (`meritBlock`). Same section class, same `h3`. The `Source:`
paragraph and the two sentences are replaced by one table:

```
<table class="data-table report-table report-merit-table" data-publisher="common_data_set">
  <caption>{sourceLink(citation)}, about last year's full-time freshmen.</caption>
  <thead><tr><th scope=col>Non-need (merit) aid</th><th scope=col>Last year's full-time freshmen</th></tr></thead>
  <tbody>
    <tr><th scope=row>Share who received it</th><td class=report-value data-label="…">25%</td></tr>
    <tr><th scope=row>Average received</th><td class=report-value data-label="…">$12,000</td></tr>
  </tbody>
</table>
```

- The share cell is `share.spokenPercent()`; the average `WholeDollars.spoken`.
- A null measure's cell holds `NOT_IN_FILING` = `"Not in this filing."` (the
  block's own absence words; `MeritPractice` carries no `FigureStatus`). Both
  rows always print when the block prints; the block is absent, as today, when
  both measures are null. Every blank cell in both tables wraps its words in the
  page's existing `report-blank` span, so it is quiet like every other blank.
- `data-publisher` is `MoneySource.COMMON_DATA_SET.value`. A later table holding
  two publishers writes both values space-separated.
- Dropped (brief 0016 D7): "a grant the student never pays back" and "reported
  for last year's class" move to K1 and the caption; "not an offer to this
  student" is already said in the sources section.

**Borrowing** (`borrowingParagraphs` → `borrowingBlock`). Inside
`section.report-debt`, after the program blocks, a `div("report-borrowing")`:

```
<table class="data-table report-table report-borrowing-table" data-publisher="common_data_set">
  <caption>{sourceLink(citation)}, about what the students who graduated from it that year borrowed.</caption>
  <thead><tr>
    <th scope=col>Loan type</th>
    <th scope=col>Borrowed, of the 1,000 who graduated</th>   <!-- "Borrowed" when graduatingClass is null -->
    <th scope=col>Owed on average at graduation</th>
  </tr></thead>
  <tbody>
    <tr><th scope=row>Federal loans</th><td …>394 (39.4%)</td><td …>$20,747</td></tr>
    <tr><th scope=row>A private loan</th><td …>83 (8.3%)</td><td …>$43,865</td></tr>
  </tbody>
</table>
<p class="report-borrowing-gap">{each gapSentence}.</p>
```

- Row header = `spokenLoanType.replaceFirstChar { it.uppercase() }`.
- Borrowed cell =
  `"${WholeCount.spoken(borrowed.borrowers)} (${borrowed.share.spokenPercent()})"`;
  with no `BorrowedShare` the cell holds `NO_SHARE_SHOWN` =
  `"No share is shown here."` (neutral on purpose: a missing pair is the
  school's silence, our unread denominator, or the school's own contradiction,
  and D6 says the contradiction is never spoken).
- Owed cell, by `OwedAverage`: `Shown` prints `WholeDollars.spoken(usd)`;
  `Withheld` prints its `statement`, the frozen
  `FigureStatusCopy.agentlessStatementOf(status)` resolved in `rowsOf`;
  `NotInFiling` prints `NOT_IN_FILING`.
- The kinds section's self-report sentence keeps its link promise only when
  every shown merit filing links to its file (`CdsCitation.linksToFile`).
  Otherwise it speaks the frozen sentence without the promise
  (`FilingLink.NOT_LINKED`), because a dead filing's caption carries no link.
- No total row, no `<tfoot>`. The gap sentences render as one `<p>` each,
  verbatim with the page's trailing period, exactly as today.
- Dropped: the header paragraph (`HEADER_TAIL`), whose two negative hedges move
  to K2 once per page. `HEADER_TAIL` itself is unchanged for the coach.

**Federal debt** (`shownDebtSentence`). Loses
`" The source publishes no year for
this figure."`; the sentence is otherwise
unchanged. Its `<p>` carries class `report-debt-federal`, the rule's permanent
named exemption (D4). The blank arm is unchanged.

**Price table.** Gains class `report-price-table` and nothing else, so the
caption rule can name it (section 5). No visible change.

**Kinds section** (`tierKindsSection`), in this order after the tier groups:

- The merit figures join the covered set (D10): for each school whose merit
  table prints, one `CoveredFigure` per shown measure with a new
  `FigureIdentity.MeritAid(measure: MeritMeasure)` (sealed variant in
  `AssuranceTierGrouping.kt`; `MeritMeasure` is a two-value enum beside it,
  `SHARE`/`AVERAGE`, with `spokenLabel` "the share of full-time freshmen who
  received non-need (merit) aid" / "the average non-need (merit) aid" and
  `wireName` `MeritAidWire.SHARE_KEY` / `AVERAGE_KEY`) and
  `SpokenAssurance(MeritAidWire.ASSURANCE)`. The grouping then prints the frozen
  voluntary-self-report sentence once under a scope with no digit. Its "We link
  to the college's own file" clause is true of the captions.
- K1, when any merit table prints:
  `p("report-kind report-kind-merit") { +"Non-need (merit) aid is a grant the student never pays back." }`
- K2, when any borrowing table prints:
  `p("report-kind report-kind-borrowing") { +"The borrowing figures describe the students who graduated from a school in the year of its filing -- not this year's freshmen, and not all its undergraduates." }`
- K3, when any federal debt figure is shown:
  `p("report-kind report-kind-debt-year") { +"The source publishes no year for the median federal loan debt." }`

The section's early return widens to these three conditions.

### 4. CSS (`site.css`)

Under the `.report-table` rules:

```css
/* The table's publisher and cohort, above it: left-aligned and quiet, because
   it names the filing rather than competing with the figures. */
.report-table caption {
  text-align: left;
  color: var(--muted);
  font-size: 0.9rem;
  padding-bottom: 0.4rem;
}
```

Nothing in the stacking block changes (that is `glance/05`).

### 5. The asserted rule and the measurement — `CostReportShapeTest.kt` (new)

`org.jsoup:jsoup` is added as a `testImplementation` dependency of `public-web`
(version in `gradle/libs.versions.toml`): the rule walks nested sections and
cells, and the repo's own tests say a hand-rolled tag strip is not an HTML
parser. Production code does not depend on it.

Named constants in the test class's companion object, with their reason in a
KDoc (plus `PRICE_TABLE = "report-price-table"`, the class rule (c) exempts):

```kotlin
/** Blocks not yet converted (brief 0016 D9). Each later slice removes its own entry; the slice that empties this set deletes the mechanism. */
val UNCONVERTED_BLOCKS = setOf("report-award-grid", "report-national-merit", "report-program-return")
/** The one permanent exemption: the federal median debt is one figure, one sentence (brief 0016 D4). */
const val FEDERAL_DEBT_SENTENCE = "report-debt-federal"
/** Tables that exist today without a caption; `glance/02` captions them and empties this set. */
val UNCAPTIONED_TABLES = setOf("report-summary-table", "report-price-table")
```

Rendered over the three-school verbose fixture AND over one render that prints
every unconverted block (grid, National Merit, program; composed from the
existing `gridAward`/`meritAward`/program fixture helpers, moved to
`FakeCostReportSource.kt` if they are private):

- **(a) Currency in prose.** Within each `section.report-school`, after removing
  elements whose class is in `UNCONVERTED_BLOCKS`, elements with class
  `FEDERAL_DEBT_SENTENCE`, every `td`/`th`, and `.report-merit-words`, no `p` or
  `li` text matches `\$[0-9,]+`.
- **The ratchet is live.** Each class in `UNCONVERTED_BLOCKS` appears in the
  all-blocks render AND, unexempted, would fail (a): a `p`/`li` inside it holds
  a currency figure. An entry that is no longer needed fails here, which is how
  `glance/03` and `glance/04` are made to remove theirs.
- **(b) Caption first.** Every `table` whose classes do not intersect
  `UNCAPTIONED_TABLES` has a `caption` as its first child, non-blank, and a
  non-blank `data-publisher`. Every class in `UNCAPTIONED_TABLES` appears on
  some table (the same stale-exemption check).
- **(c) No total outside the price table.** No `table` without
  `report-price-table` has a `tfoot`, or a `th[scope=row]` whose lower-cased
  text contains "total", "all", "combined" or "sum" as a word.
- **(d) One publisher vocabulary per table.** No `table`'s text contains both
  "College Scorecard" and "Common Data Set".
- **Measured, never asserted (brief 0009 D20).** One `[MEASURE]` line per
  render: visible words, words in tables, prose share as a percentage, and the
  per-school word count, using `visibleProseOf`/`proseOf` from `ReportProse.kt`
  (tables counted by `proseOf` over each `table` element's outer HTML).

### 6. Tests rewritten in `CostReportPageTest.kt` (X3)

No fact loses its test. Each rewrite is a one-to-one move:

| Today asserts                                                                       | After                                                                                                                                                                              |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `block.sentences` all in body, after the linked header, none naming the CDS         | `rowsOf(c).rows` each render as a `tr` with the row header and both cells, after the caption; no cell text contains "Common Data Set"; each `gaps` entry's `sentenceOf` is a `<p>` |
| `sentences.sortedBy { body.indexOf(it) }` (wire order)                              | row headers in body order equal `rows.map { spoken }`; "Federal loans" precedes "A loan of any kind"                                                                               |
| `borrowingMarkers` pinned to wire sentences                                         | markers pinned to `rowsOf`'s rendered strings (row header, cell text, `sentenceOf` per gap), same pin-first-then-sweep shape                                                       |
| linked header `…</a> reports these figures for the students who`                    | linked caption `…</a>, about what the students who graduated from it that year borrowed.`                                                                                          |
| merit `shareLabel` sentence on the page                                             | `25%` in a `td` under the merit row header, with the merit caption linked; `shareLabel` NOT on the page                                                                            |
| "The source publishes no year for this figure" beside the debt figure (three tests) | the sentence is NOT in any `report-school`; K3 is in the kinds block exactly once                                                                                                  |
| `every figure … covered by exactly one sentence`: VOLUNTARY_SELF_REPORT count 0     | count 1, under a scope naming both merit measures; still 0 for MANDATORY_SURVEY                                                                                                    |
| `the sentence that promises a link …` KDoc says it cannot fail today                | KDoc rewritten: it is live, the captions are the links; anchor count stays 6                                                                                                       |
| `CostReportProgramReturnTest`: woven paragraphs before `HEADER_TAIL`                | before the `report-borrowing` div                                                                                                                                                  |

Unchanged and still green: the never-sum sweep (its forbidden strings), the
citation-in-link sweep, the anchor-target order test, the `"award"` sweep, the
kinds-block ranking test, the per-school link test, the no-merit-section test.

New in `CostReportPageTest.kt`: a borrowing fixture with a federal average and
no counts renders `NO_SHARE_SHOWN` in the borrowed cell; a fixture with a
counted private share and
`gapStatusByLoanType[PRIVATE] = SUPPRESSED_BY_PUBLISHER` renders the frozen
withheld sentence in the owed cell and the gap `<p>` under the table; a merit
fixture with no average renders `NOT_IN_FILING` in that row; a `NOT_FOUND`
filing renders the caption's citation as bare text with no anchor (RFC 200) and
the citation-in-link sweep is adjusted to allow a caption's text only when the
fixture's status is `NOT_FOUND`.

### 7. What does not change

`CollegeCostChatTool.kt`, `MeritAidWire.objectOf/shareLabel/averageLabel`, every
`prompts/**` and `db/schema/**` file, `AssuranceTierCopy` and `FigureStatusCopy`
are not edited. The output of
`BorrowingWire.objectOf/blockOf/blockHeader/HEADER_TAIL/NOTE` does not change;
section 1 names the two internal edits. The existing `BorrowingWireTest` and
`CollegeCostChatToolTest` pin that output, and a new `BorrowingWireTest` case
pins the literal `HEADER_TAIL` string. No new snapshot is added.

## Decisions

- **D-A Blank cell words.** `NOT_IN_FILING` for a measure the filing lacks,
  `NO_SHARE_SHOWN` for a missing share, the frozen status sentence for an
  average with a gap status. _Alternative:_ omit the row. Rejected: the spec
  fixes two rows per merit table, and a dropped row hides that the school filed
  half the section.
- **D-B The caption rule needs its own exemption set.** The spec says the markup
  rules assert over every table from this slice on, and also that `glance/02`
  captions the price and summary tables. Both cannot be true today, so
  `UNCAPTIONED_TABLES` ratchets exactly as `UNCONVERTED_BLOCKS` does. Reported
  to /chart as spec drift.
- **D-C Jsoup in tests.** A hardened parser over a hand-rolled tag strip for a
  rule about nesting. Test scope only.
- **D-D `WholeCount`.** One formatter for counts, beside the one for dollars,
  rather than a `"%,d"` typed on the page.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/common/util/WholeCount.kt` (new)
- `service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt` —
  `BorrowingRows`, `LoanRow`, `rowsOf`
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/MeritAidWire.kt` —
  `ASSURANCE`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeMeritAid.kt` — `CDS_SECTION`
  (new); `db/src/main/kotlin/ed/unicoach/db/dao/CdsAdmissionsDao.kt` — two KDocs
  cite it
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/AssuranceTierGrouping.kt`
  — `FigureIdentity.MeritAid`, `MeritMeasure`
- `service/src/test/kotlin/.../BorrowingWireTest.kt` — rows/sentences parity
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`
- `public-web/src/main/resources/static/site.css`
- `public-web/build.gradle.kts`, `gradle/libs.versions.toml` — jsoup (test)
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportShapeTest.kt` (new)
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`,
  `CostReportProgramReturnTest.kt`, `FakeCostReportSource.kt`
- `rfc/260-borrowing-and-merit-in-rows.md`

## Implementation Plan

1. `WholeCount`; `BorrowingRows`/`LoanRow`/`rowsOf` with the parity test;
   `MeritAidWire.ASSURANCE`; `FigureIdentity.MeritAid`. Verify:
   `nix develop -c bin/test` (the hook decides scope; `service` and `common`
   tasks run).
2. Page: merit table, borrowing table and gap paragraphs, debt sentence and its
   class, price table class, kinds section K1–K3 and the merit covered figures.
   CSS caption rule.
3. Jsoup dependency; `CostReportShapeTest.kt` with the four rules, the two
   ratchet checks and the `[MEASURE]` lines.
4. Rewrite the tests in section 6; add the new blank-cell and `NOT_FOUND` cases.
5. `nix develop -c bin/test`, unscoped. Read the `[MEASURE]` lines and record
   them in the run record.

## Tests

- `BorrowingWireTest`: `rowsOf` and `blockOf` imply the same sentences in the
  same order for every fixture; a loan type with only a gap status has no row
  and one gap sentence; a contradicting pair yields `borrowers == null`.
- `CostReportShapeTest`: rules (a)–(d); the two stale-exemption checks; the
  `[MEASURE]` lines print and assert nothing.
- `CostReportPageTest`: section 6's rewrites and additions; the anchor count
  stays six; VOLUNTARY_SELF_REPORT said once under a digit-free scope; K1–K3
  each exactly once on the three-school page and absent on a page with no merit
  table / no borrowing table / no shown debt respectively.
- `CostReportProgramReturnTest`: the woven-paragraph order test against the
  borrowing div; the anchor-order test unchanged.
- `CollegeCostChatToolTest`, `MeritAidWireLocaleTest`,
  `SystemPromptCatalogTest`: unchanged and green (D8).

## Rollback

Revert the commit. No schema, seed, prompt or stored shape changes; the page
reverts to sentences and the coach never changed.
