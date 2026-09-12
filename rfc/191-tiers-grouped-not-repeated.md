# RFC 191 — tiers grouped, not repeated

Product slice: `concise/05/tiers-grouped-not-repeated` (brief 0009 "say it
once", gate-1 **D3**, gate-2 **D9/D13**).

## Problem

RFC 179 gave every figure an assurance tier and a sentence saying what kind of
number it is. The sentence was then attached **to each figure**. On the
three-school Family Cost Report the administrative-record sentence — thirty
words — is printed **three times**, once inside every school's debt paragraph,
because the tier was treated as a property of a printed paragraph rather than of
the **set of figures that share it**.

Measured on `main@0403f784` (after RFC 190 landed), fixture
`verboseState + verboseCampus + verbosePrivate` — the fixture
`CostReportPageTest.WORD_CEILING` is set on:

| Fact                                                 |     Measured |
| ---------------------------------------------------- | -----------: |
| Page words                                           |        1,450 |
| `administrative_record` sentence printed             |            3 |
| `mandatory_survey` sentence printed                  |            0 |
| `voluntary_self_report` sentence printed             |            0 |
| Figures per school carrying a resolved tier          | 11 / 11 / 11 |
| Shown (numbered) figures per school                  | 11 / 10 / 10 |
| Figures per school whose tier the page says out loud |    1 / 1 / 1 |

Gate-1 D3: a figure's assurance tier is said **once for the set of figures it
covers**, never once per figure.

## Decision

**D1 — The page says each distinct tier sentence once, in one page-level block,
for the set of figures it covers.** `report-kinds`, placed with the other
standing material immediately above `sourcesSection`, because a reader asks what
a number IS before asking who published it. One paragraph per distinct tier: a
**scope** naming the figures it covers, then the tier sentence **verbatim** from
`AssuranceTierCopy`. The block is **absent**, not empty, where the page speaks
no such figure.

**D2 — The covered set is what the page says in PROSE, derived at the site that
renders it.** `proseFiguresOf` reads the same condition the debt paragraph
renders on. A second list beside the renderer would be free to drift into naming
a figure the page does not print, which is the header-covers-the-wrong-figure
failure this block exists to avoid.

**D3 — Two exclusions, both RFC 189's own, and both asserted.**

- A field with **no number** is in no group. There is no figure to be a kind of.
  Its own cell or paragraph keeps the status sentence and the tier sentence it
  prints today — `blankFor` is untouched — so no tier becomes unreachable (brief
  0009 D1). A page whose only debt figure was suppressed therefore renders no
  block and still states the tier in the paragraph.
- A line **we** authored is on no publisher's tier. The at-home `$0` housing
  line (`LineOrigin.ASSUMED_BY_UNICOACH`, RFC 166 §7) carries no published cell,
  so it cannot enter the walk. A test asserts the block names no housing figure
  and contains neither the assumption sentence nor our own-assumption note. RFC
  189's review caught the same hazard one surface over.

**D4 — A figure named by two groups is a defect, so the scope qualifies by
school where the schools disagree — measured over everything the SURFACE says a
tier for, not over what this block groups.** A label is bare where every school
puts that field on the same tier and **named with its school** where they do
not.

The second half was a defect in this RFC's first draft and review found it. A
withheld figure is covered by no group and still states its own tier in its own
paragraph (`blankFor`). With one school's debt figure shown on a federal tier
and another's withheld on a survey tier, ambiguity computed over the COVERED set
alone sees none, and the block prints a bare "The median federal loan debt:"
that a parent reads as covering the withheld figure too — a header describing a
figure it is false of, reached from a direction the rule did not model. So
`groupsOf` takes a second list, `spokenElsewhere`: never rendered, it only
widens the set of fields that must name their schools. The page fills it from
exactly the four statuses `blankFor` speaks a tier for.

One (college, field) pair carrying two tiers is refused with a `require`: it
would render two groups with the identical scope above contradictory sentences,
and nothing would tell a reader which was which.

**D5 — Grouping order is first appearance, never a ranking.** No number, no
score, no rating, no "stronger than" (RFC 179 D10) — asserted at the seam and on
the page, where the check is scoped to the block because "College **Score**card"
is a publisher's name and not a rating.

**D6 — A figure's family-readable name gets one home: `CostField.spokenLabel`.**
`CostReportPage.kt:74-79` says this file authors no money copy, and a scope is
money copy. Exhaustive with no `else`, like `componentRole` and `figureAddress`
beside it, so a new `CostField` must decide how a family hears it rather than
reaching a surface as a wire name. The three component names are
`ComponentRole.label` lowercased at its one home rather than re-typed.

**D7 — The three sentences are byte-identical and the composition order is
unchanged.** Status first, tier second, two sentences, never concatenated. Only
the number of emissions changes. `SystemPromptCatalogTest`'s v19 pin is
untouched; **no prompt version is minted** and `prompts/coach-system-prompt.md`
is not edited.

**D8 — The chat half was landed by RFC 189; this RFC tightens one sentence.**
RFC 189 already emits provenance once per college and already tells the coach to
"say the tier ONCE for the figures on the default". It said nothing about the
**exception** group, so three fields sharing one off-default tier were three
"name its own tier" instructions. The DESCRIPTION now carries the same rule for
them: say each distinct tier sentence once for the SET of figures on it, naming
that set, never once per figure. No new key, no payload change.

**D9 — Option B, on measured evidence.** Two shapes were put to the product
layer. **A** would have made the block cover every SHOWN figure, closing the
1-of-11 gap below; measured against the real strings that is **+9 words over the
1,450 ceiling** — a concision slice raising a ceiling RFCs 188 and 190 fought
down. **B** — group the sentences the page already says — measures **1,401**. B
was chosen because it changes only how OFTEN we say a sentence we already say,
which is exactly D3's charter; A would change WHICH FIGURES the page speaks
about, which is new information and a decision of its own.

**D10 — The ceiling is the measured number, and the brief's target is missed.**
`WORD_CEILING` 1,450 -> **1,401**, measured by setting it to 0 and reading the
failure. Brief 0009 wrote `<= 1,170` for this slice; that came off the
superseded 1,793-word chain the brief itself retired (SD1, SD2, SD5) and is not
reachable by grouping tier sentences — the page prints three thirty-word
sentences and now prints one, plus an eleven-word block that names what it
covers. The run reports the miss rather than weakening the assertion.

## Open items

**The 1-of-11 gap, measured and left exactly where RFC 179 left it.** Per school
on the three-school fixture: **11 / 10 / 10** shown figures carrying a resolved
tier — `mandatory_survey` **10 / 9 / 9** and `administrative_record` **1 / 1 /
1** — and the page states a tier for **one** of them, the federal debt figure.
Ten figures a school publishes (the published price, two tuition tiers, the
price after a financial aid offer, housing and food, books and supplies, other
expenses) reach a parent with no tier sentence anywhere on the page, although
the coach is handed one for each of them. **RFC 190 did not change this, and
neither does this RFC.**

**What closing it would cost, measured, not guessed: about +55 words** on a
three-school page — a scope naming the seven mandatory-survey labels (33 words)
plus the twenty-two-word survey sentence — said **ONCE**, for the whole page,
for every school. That is **not this brief's kind of cost**: brief 0009 removes
REPETITION, and a one-time statement is not a repetition. So nothing landing
here contradicts a later decision to close the gap; `AssuranceTierGrouping`
takes whatever covered set it is handed, and closing the gap is a change to
`proseFiguresOf` and to nothing else.

**Whose decision it is.** Whether the page should speak for all eleven figures
is an honesty question of brief 0008's kind, on its own merits, at its own time.
It was put to the product layer during this run and deferred deliberately.

## Accepted costs

**The tier sentence is now far from its figure.** It sits at the foot of the
page, after every school section, where it used to sit inside each school's debt
paragraph. D1's ordering argument is only about its position relative to
`sourcesSection`; the distance from the figure is a real cost and is accepted
here, not overlooked. It is the same trade `sourcesSection` already makes for
publishers, and the block names the figures it covers precisely so a reader who
meets it late still knows what it is about. A pointer from the debt paragraph
would cost words in a brief that exists to remove them.

**`KINDS_HEADING` is authored in the renderer.** "What kind of numbers these
are" is page chrome of the same kind as the summary heading and the sources
heading, not a claim about any school's figure; the sentences under it are the
domain's. It is named here because it is the one string in this feature the copy
seam does not own.

**The mixed-tier page fixture is synthetic.** No publisher we load pairs a
survey with `GRAD_DEBT_MDN`, so the page cannot be made mixed-tier from real
data: it speaks a tier for one field. `costFixture` therefore gains a
`debtPublisher` knob, documented as deliberately synthetic, because "two
schools' same figure on two tiers" is the case a grouping header must survive
and a guarantee tested only in a unit suite is one the renderer never has to
keep.

## Detailed Design

### The copy seam — `AssuranceTierGrouping`

New file beside `AssuranceTierCopy` in
`service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/`, because it is
copy about money and the page may not author that.

```kotlin
data class CoveredFigure(val collegeName: String, val field: CostField, val assurance: SpokenAssurance)

data class SpokenTierGroup(val assurance: SpokenAssurance, val scope: String) {
  val tier: AssuranceTier get() = assurance.tier
  val statement: String get() = assurance.statement   // AssuranceTierCopy's, derived
}

fun groupsOf(figures: List<CoveredFigure>): List<SpokenTierGroup>
```

`statement` is derived through `SpokenAssurance`, exactly as RFC 189 made the
pair inseparable: a group holding one tier's code beside another tier's words is
unconstructible. Ambiguity is computed over the **whole input**, once, because
it is a property of the input and not of the group being rendered.

The scope reads `The median federal loan debt:` on a uniform page and
`The median federal loan debt at Lakeshore State University:` /
`... at Wynnford College:` on a mixed one.

### The page

`respondCostReportPage` gains one call, `tierKindsSection(profile.colleges)`,
immediately before `sourcesSection`. `shownDebtSentence` stops composing the
tier onto the paragraph and keeps everything RFC 190 gave it — the publisher
opening label and the figure. `blankFor` is untouched.

### The tool DESCRIPTION

One sentence, inside the paragraph RFC 189 wrote, extending "say the tier ONCE
for the figures on the default" to the exception group.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/costs/CostField.kt` — new
  exhaustive `CostField.spokenLabel`.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/AssuranceTierGrouping.kt`
  — new: `CoveredFigure`, `SpokenTierGroup`, `groupsOf`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` —
  `KINDS_HEADING`, `proseFiguresOf`, `tierKindsSection`; `shownDebtSentence`
  drops its tier composition.
- `public-web/src/main/resources/static/site.css` — `.report-kinds`,
  `.report-kind-scope`.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` —
  one DESCRIPTION sentence.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/AssuranceTierGroupingTest.kt`
  — new.
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` — five new
  tests and the `WORD_CEILING` ratchet.

## Implementation Plan

1. `CostField.spokenLabel`, exhaustive, with a vocabulary test over every field.
2. `AssuranceTierGrouping` + `AssuranceTierGroupingTest` — pure, no render.
3. `tierKindsSection` + `proseFiguresOf`; `shownDebtSentence` simplified; CSS.
4. Measure the page with the ceiling at 0; ratchet `WORD_CEILING` to the number
   read off the failure.
5. The DESCRIPTION sentence.

## Tests

- **Said once for the set** — the administrative-record sentence appears exactly
  **once** on a three-school page (it appeared three times), inside
  `report-kinds`, above a scope naming the figures it covers, byte-identical to
  `AssuranceTierCopy`; the debt paragraph keeps its figure and its publisher.
- **Covered exactly once, walked** — for every college on the page, its debt
  note's tier is the tier of the one sentence whose scope names that figure. The
  other two sentences appear zero times, because no figure on the page is on
  them.
- **Mixed tier makes two headers**, at the seam (`AssuranceTierGroupingTest`)
  and **through the renderer** (`CostReportPageTest`, on the synthetic
  `debtPublisher` fixture): two schools on two tiers produce two headers, each
  naming only its own school, each sentence said once, and neither header can be
  read as covering the other's figure.
- **A bare label is refused where a withheld figure states a different tier** —
  one school's debt shown, another's suppressed on another tier: the header
  names the covered school only, and the withheld figure keeps both of its own
  sentences outside the block.
- **Our assumption is never swept up** — the at-home `$0` line is still stated
  as ours, and the block names no housing figure and carries neither the
  assumption sentence nor the own-assumption note.
- **A figure with no number is in no block** — a page whose only debt figure was
  suppressed renders no block at all, still states the suppression, and the tier
  it would have had is still reachable.
- **Nothing ranked, nothing counted** — no digit in the scope and no ranking
  word in the block (RFC 179 D10).
- **Every field has a family-readable name** in RFC 141's vocabulary, no wire
  name, no blank.
- **The DESCRIPTION carries the rule** — `CollegeCostChatToolTest` asserts the
  set sentence and the "never once per figure" clause, so the instruction cannot
  be deleted silently.
- **Word ceiling** ratcheted 1,450 -> 1,401, one counter, still a ratchet.
