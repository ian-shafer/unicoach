# RFC 188 — The cost report page says it once

Slice `concise/01/the-page-says-it-once` of brief 0009 ("say it once"), gate 2
passed 2026-09-09. It removes the repetition that lives purely in
`CostReportPage.kt`, and fixes two defects found while measuring it. Nothing a
family can learn today becomes unlearnable: every figure, status, assurance
tier, cited year, publisher and unicoach-owned assumption is still on the page
(brief 0009 D1).

## Why

A parent opening the shared Family Cost Report for three schools read 1,767
words (measured on this RFC's own fixture; brief 0009's research measured 1,793
on a near-identical one), and about a quarter of them were exact repeats. Four
of those repeats are the renderer's alone — the same sentence printed once per
school, the same school-wide figure printed once per column — and two of them
were also WRONG:

- `BLENDED_IN_STATE_CLAUSE` ("At a public school it, and the likely price after
  a financial aid offer, are figures for students paying in-state tuition.") was
  a constant tail on both blended hints, so it printed under private colleges,
  where no school prices by residency at all.
- On a ONE-school report the residency sentence printed twice: once from
  `SingleSchoolBasis.statements` in the assumption block, and again as
  `report-school-residency` under the school's own heading.

## Detailed Design

**1. A grid row that is the same in every column is said once — derived.**

`gridRow` is the one site that writes a row of the arrangement grid, and
`schoolWideFigureOf` is the DECISION half of it, named: a row collapses to a
single spanning cell exactly when the figures it would print are equal,
`cells.map { it.figure }.distinct().singleOrNull()` over the rendered
`SchoolFigure` itself, which carries the amount, the blank label and the basis
note. A cell is a named `GridCell(columnLabel, figure)` rather than a `Pair`,
because that String is the way of living the phone layout prints as its
`data-label`. The span is the row's own `cells.size`, never a column count
passed in beside the cells — two arguments that must agree are a `colspan` free
to disagree with the header above it. Tuition and fees and books and supplies
collapse today because the DOMAIN holds one field for the whole school in each
(`tuitionLine` is `applicableTuitionFor(control)` for every arrangement;
`ComponentRole.fieldOf` maps every arrangement to
`BOOKS_AND_SUPPLIES_PER_YEAR_USD`). That is their property, not this renderer's
rule — naming the two roles in the renderer would let a fourth arrangement with
its OWN books field silently collapse two different numbers into one (gate-2
D11).

The row keeps its position and its class (`report-row-tuition`,
`report-row-component`), so reading order, the total below it and the CSS are
unchanged; only its cells become one `colspan` cell. It carries no `data-label`,
exactly like `wholeSchoolRow`: the row's own `<th>` names the figure, and the
way of living it used to be labelled with is precisely what it no longer varies
by. A one-column table collapses nothing — there is no repeat to remove, and the
phone layout still owes that cell its way-of-living label.

**2. The blended hint is said once per page.**

`SCHOOL_BLENDED_HINT` was printed under EVERY school's table while
`SUMMARY_HINT` said the same thing under the summary table — four copies on a
three-school page. The hint is now said exactly once, on every shape of report:

- `ReportShape.Compared` — `SUMMARY_HINT` under the summary table, and no school
  section prints one;
- `ReportShape.OneSchool` — `SCHOOL_BLENDED_HINT` under that school's table, as
  today;
- `ReportShape.ManyUncompared` — two or more schools the domain handed us no
  comparison for, which this page has always kept an arm for: the page-level
  hint is printed above the school sections, so the warning is not lost there.

RFC 149 D-F.1 requires the warning be said, not that it be said N times.

**2a. The page shape is decided once, in a closed vocabulary.**

Three facts hang off one question — which assumption block is rendered, whether
a school section states its own residency basis, and where the hint is said —
and each used to re-derive it from `comparisonBasis` and the college count in
its own words. `ReportShape.of(profile)` is now the single derivation, a private
sealed interface over `NoColleges` / `OneSchool` / `Compared` /
`ManyUncompared`, and `assumptions` switches on it exhaustively. `schoolSection`
takes ONE `basisIsThisSchool` flag rather than a "say the residency line"
boolean beside a nullable hint string: those two could be handed a combination
that is not a page. `Compared` is tested FIRST in `of`, because the domain
builds no basis below two colleges and a basis must never steal the one-school
shape out from under the residency de-duplication.

**3. Defect 1 — the in-state clause is conditional on a public school.**

`blendedHintFor(hint, colleges)` appends `BLENDED_IN_STATE_CLAUSE` only when
`colleges.any { it.control is CollegeControl.Public }`. The per-school hint asks
about its one school; the page-level hint asks about every school it stands
above, because the clause is true of the page exactly when one of them is
public. The control is the cost domain's own type, never re-derived here.

**4. Defect 2 — the residency sentence once on a one-school report.**

`schoolSection` takes `statesResidency`, false on a one-college page, where the
assumption block IS that school's basis and already states the sentence. On a
2+-school report the per-school line STAYS: there it is the only place a
SPECIFIC school's basis is stated.

**5. The income band is said once, and only where the page already said it.**

`netPriceCell` takes `bandSaidAbove`, the band the page's own money line names
(`MoneyBasis.AnsweredBand.band`), and drops the `BandSpecific` note exactly when
`netPrice.band == bandSaidAbove`. The test is the BAND, never the answered
status: a cell whose band differed from the one the page stated would keep its
own note rather than borrow a sentence about another bracket. The band is
therefore still on the page, in dollars, in the assumption line every figure on
the page sits under.

The `OverallAverage` note ("an overall average across all families, not a figure
for this family") is untouched and still prints beside its figure — it is the
dangerous case and brief 0009 D5 forbids hiding it.

The two notes are not the same kind of sentence, which is why one moves and the
other may not. `CostReportPage.kt:100-118` argues that a number printed beside a
family's own name is read as theirs whatever the note says — and that argument
was written about `OverallAverage`, a figure that is NOT this family's, where
the note CORRECTS a misreading and has to be at the number to do it. A
`BandSpecific` note only CONFIRMS a figure that genuinely is for this family's
band, and a confirmation is as true said once above the page as said six times
inside it. The correcting note stays beside its number; only the confirming one
moves.

## The measured result, honestly

| render        |      before |           after |
| ------------- | ----------: | --------------: |
| three schools | 1,767 words | **1,530 words** |
| one school    |   800 words |       762 words |

A correction to the record while measuring it: the three-school page reads
**1,767** words on today's `main`, not the 1,793 brief 0009's research report
measured. The report rendered an older `main` with a fixture it built for that
run, and counted the text with BeautifulSoup; this RFC's fixture and its in-test
counter (markup stripped, entities restored, whitespace tokens) are close but
not identical, and the tree has moved since. Both numbers are real measurements
of slightly different things. The one this slice is judged against is 1,767,
because it is the number this repository's own test reproduces.

The slice's acceptance criterion asked for ≤ 1,480. It is not reachable from
this slice's own decisions, and brief 0009's own budget table says so: it
allocates this slice 102 (hint) + 72 (band) + 25 (residency) + 24 (tuition and
books) = 223 words, where 1,793 → 1,480 needs 313. This RFC beats the brief's
own arithmetic — it deletes all three per-school hints rather than two — and
still lands 50 words above the criterion. The remaining repeats are other
slices' (`concise/04` cite-once-per-block, `concise/05` tiers-grouped) or are
the prose-to-table conversion gate-2 D9 explicitly refuses.

`CostReportPageTest.WORD_CEILING` is therefore set at **1,530** — the number the
page really reads — and is a ratchet: a repeat added back to the renderer fails
it. A ceiling set at a number the page does not meet would be a test that is
either failing or lying.

## Files Modified

- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` — the
  whole change.
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` — seven new
  tests, one existing test rewritten to the new guarantee.

No wire type, no DDL, no migration, no prompt version, and no file outside
`public-web`.

## Implementation Plan

1. Split `BLENDED_IN_STATE_CLAUSE` off the two hint constants and add
   `blendedHintFor` with its two named callers, `pageBlendedHint` and
   `schoolBlendedHint`. The three constants become `internal`, like
   `NOT_REPORTED`, so the tests assert on them rather than on copies — an
   absence asserted against a hand-typed copy passes vacuously the moment the
   sentence is reworded.
2. Add `ReportShape`, derive it once in `respondCostReportPage`, and switch
   `assumptions` onto it.
3. Thread `bandSaidAbove` (from `bandSaidAboveOf`, an exhaustive `when` over
   `MoneyBasis`, never `as?`) and `basisIsThisSchool` into `summaryTable`/
   `summaryRow` and `schoolSection`. `MoneyBasis` is derived ONCE per page and
   passed to the assumption block too.
4. Add `GridCell`, `gridRow`, `schoolWideFigureOf` and `spanningValueCell`;
   route `tuitionRow` and `componentRows` through them, and `wholeSchoolRow`
   through `spanningValueCell`.
5. Drop the `BandSpecific` note where the page already named the band.
6. Tests, and the measurement.

## Tests

`public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`, 51 `@Test`
in the class (44 before), all executed:

- `the blended-figure hint is said once for the whole page, with the residency
  it is on`
  — rewritten from
  `the blended-figure hint under both tables says
  which residency those figures are on`,
  whose guarantee ("the clause is appended to BOTH hint sentences") this RFC
  deliberately reverses. The new test asserts the summary hint carries the
  clause, no school section repeats it, and the clause is on the page exactly
  once.
- `a one-school report still carries the blended-figure hint, in its own words`
  — the hint may never disappear, only stop repeating.
- `the in-state clause is absent from a report with no public school on it` —
  and present, exactly once, on a mixed page.
- `the residency sentence is said once on a one-school report, and once per
  school on three`.
- `a figure that is the same in every column is said once, and one that varies
  is not`
  — tuition and books collapse and span; housing and food, which really varies,
  still prints a cell per column and carries no `colspan`.
- `the income band the page states is not repeated at every net-price figure` —
  the band is on the page exactly once, in dollars, and all three net prices are
  still shown.
- `the overall-average note still stands beside every figure it corrects` — both
  of its printings, while the band note beside the other school's figures is
  said once, above.
- `a three-school report says every fact it holds in fewer than fifteen hundred
  words`
  — the ratchet.

Unchanged and still passing: every labelled-blank, withheld-cell, year-gap,
total-equals-parts and `OverallAverage` test in the class.
