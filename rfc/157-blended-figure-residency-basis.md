# RFC 157 — The residency basis of the blended figures

Status: proposed\
Base: `main@22019416` · Branch: `pipeline/rfc-157`

## Summary

A WA family opened a shared Family Cost Report with UC San Diego on the list. It
showed per-year totals of $77K, $77K and $60K — correct, built from components
with out-of-state tuition — and, two rows below, **"The published price:
$39K"**, with no label. That number is a price for a California family. Nothing
on the page said so.

The same defect sits under "the likely price after a financial aid offer": for a
public school the Scorecard's net price is an **in-state** figure. A top-band WA
family at UCSD reads $28,785 where their real published price is near $77,102.

This RFC gives the two blended Scorecard figures — `COSTT4_A` (the published
price) and the `NPT4` family (the net price) — the residency basis they have
always had and never stated, and stops us printing them to a family they do not
apply to.

## The evidence

The Scorecard **Institution Data Documentation** (June 2024) states it twice:

> The broadest measure of costs to students is cost of attendance, also reported
> to IPEDS by institutions for students paying the **in-state or in-district**
> tuition rate.

> For public institutions, this metric is limited to full-time, first-time,
> degree/certificate-seeking undergraduates who **pay in-state tuition** and
> receive Title IV aid. (NPT4, and identically for NPT41..45)

The Data Dictionary itself never mentions residency, which is how the omission
reached our schema comments and every docstring written from them.

UC San Diego (unit 110680), from the live Scorecard API, settles it
arithmetically:

| figure                             | value                                      |
| ---------------------------------- | ------------------------------------------ |
| `COSTT4_A`                         | 38,701                                     |
| `TUITIONFEE_IN` / `TUITIONFEE_OUT` | 16,758 / 50,958                            |
| in-state arrangement totals        | on 42,902 · off 43,459 · family 25,723     |
| out-of-state arrangement totals    | on 77,102 · off 77,659 · family **59,923** |
| `NPT4_PUB` · top band `NPT45`      | 12,470 · 28,785                            |

The out-of-state totals reproduce the reported 77K/77K/60K exactly.
`COSTT4_A =
38,701` sits inside the in-state span and **below the out-of-state
minimum of 59,923** — and a weighted average cannot fall below its smallest
input, so no arrangement weighting on out-of-state totals can produce it.
In-state basis, proved rather than inferred.

**There is nothing better to ingest.** All 85 cost elements in the Scorecard
were enumerated: only `COSTT4_A` and `COSTT4_P` exist, and residency appears
only in `TUITIONFEE_IN`/`OUT`. An out-of-state published price is obtainable
only as out-of-state tuition plus components — which is exactly what our
arrangement totals already are. **This is a missing label, not a wrong column.**

## Decisions

### D-A. A figure whose residency does not apply to this family is WITHHELD, not labelled

The tempting fix is a caveat. We reject it. RFC 142 landed because a labelled
`net_price_q1..q5` still got read as "the Q5 net price"; a number printed beside
a family's own name is read as theirs whatever the footnote says. And this page
has **no coach in the loop** — a parent reads it alone.

So when residency is known and the school is a public whose state it does not
match, the published price and the net price render as a labelled blank naming
the reason and pointing at the figure that IS theirs:

> Not shown — this school publishes this figure for in-state students. Your
> family would pay the out-of-state price — the totals above.

The pointer has to be TRUE where it is printed, so there are two wordings, not
one. "The totals above" is false in the cross-school summary, which carries no
arrangement totals at all, so there the blank ends:

> Not shown — this school publishes this figure for in-state students. Your
> family would pay the out-of-state price — the totals in this school's own
> table below.

A blank whose pointer points at nothing is a number taken away, which is the one
thing this decision refuses to do.

The REASON in both blanks is the domain's own (`WithheldReason.cellPhrase`, the
short form beside the sentence the coach ships), and only the pointer is the
page's. A page and a coach explain one blank with one vocabulary.

Withholding applies only to a figure the school ACTUALLY publishes. A public
school in another state that reports neither figure is silent, and its silence
stays its own: it reads "not reported by this school", not "this school
publishes this figure for in-state students".

When residency is known and DOES match, both figures print, and the basis
sentence says they are in-state figures for an in-state family.

At a private college the distinction does not exist (RFC 135), so both figures
print unchanged.

### D-B. Residency unknown: state the basis, withhold nothing

An unanswered residency question is not licence to hide the only price we hold.
Both figures print with their basis said plainly — "this school publishes this
figure for students paying in-state tuition" — and the existing precision offer
already asks the residency question. This keeps guided-not-gated (0001 D11).

### D-C. `comparison_basis` gains a sixth fact, and residency stops meaning "tuition"

`ResidencyBasis` is scoped to tuition by construction — the vocabulary is
`ComparedTuition`. The class docstring claims residency is one of the five facts
that make a table honest; for two columns it does not keep that promise. A sixth
fact, `blended_figure_basis`, states which residency the published price and net
price are on, and which schools in this table they therefore do not describe.

`CollegeCostChatToolTest` asserts the basis key set is exactly five. That test
fails the moment this lands, which is the correct alarm, not an obstacle.

### D-D. `CostField` gains a residency axis beside its arrangement and vintage axes

`COSTT4_A` is already isolated on two axes — blended across arrangements, older
vintage — and both are modelled. Residency is the third axis and the only
unmodelled one. It belongs in the same enum, so a future figure cannot be added
without answering the question.

### D-E. The schema comments are the root fix

`0059`'s comments name the cohort, the aid basis and the `LPROGRAM` caveat, and
say nothing about residency — while naming `NPT4_PUB` in the same sentence.
Every downstream docstring was written from them. A new migration rewrites the
comments for `cost_of_attendance_per_year_usd`, `net_price_per_year_usd` and the
`NPT41..45` band columns to state the in-state basis. Comments only; no data
change.

### D-F. Coach prompt v16

v15 never says either figure is in-state, and one line — "Keep one residency and
one way of living in a column" — invites the wrong read. v16 appends one
paragraph: the published price and the net price are in-state figures at a
public school; never offer them to a family from another state as their price;
say the out-of-state total instead. Rollback is `v15`.

### D-G. THE SEARCH INDEX IS NOT FIXED HERE

`net_price_percentile_share` is `percent_rank()` over the in-state net price, so
every family — resident or not — is ranked on it, and migration 0064 carries no
tuition columns at all, so net price is the only price axis search and
similarity have. "Like Bowdoin but cheaper" compares two in-state figures.

That is a real defect and a **different one**: it is brief 0004's surface, it
needs new index columns rather than new copy, and it raises a design question
this RFC cannot answer honestly (what does "cheaper" rank on when residency is
unknown, and what happens to the pinned percentile corpus). Fixing it here would
smuggle an index migration into a labelling change.

**Reported to /chart as a new slice.** This RFC states the boundary rather than
pretending the problem is solved.

## Detailed Design

1. **`CostField`** gains `residency: ResidencyAxis?` — `IN_STATE_ONLY` for
   `STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD` and `NET_PRICE`, the explicit
   in-state/out-of-state pair for the two tuition fields, `null` for the
   residency-free components, debt and earnings.
2. **`CollegeCost`** gains a derived
   `blendedFiguresApply: BlendedFigureApplicability` — `APPLIES` at a private or
   a matching-state public, `WITHHELD` at a non-matching public, `BASIS_STATED`
   when residency is unanswered. Three named outcomes rather than a `Boolean?`,
   because two of the three readings of a nullable boolean compile and the
   dangerous one (`!= true`) folds the OPEN question into the withheld case,
   which D-B forbids. The rule itself lives on `ComparedTuition`, the vocabulary
   the basis sentence already speaks, and `blendedFigureApplicabilityOf` reaches
   it through the existing control → vocabulary map: the number that is withheld
   and the sentence explaining it are ONE expression. Derived means a computed
   property over `CollegeCost.control`, NOT a constructor parameter: a stored
   flag could be handed a value its own control contradicts, which is the split
   this decision removes. `CollegeCost` takes the figures AS PUBLISHED and
   derives `withheld`, the two shown amounts and the two reported lists itself,
   so the shipped defect — an out-of-state family holding an in-state published
   price — has no constructor and no `copy()`.
3. **`CollegeCost`** withholds the two figures when that outcome is `WITHHELD`
   and the school reports them, exactly as the service already withholds tuition
   on an unknown residency. A withheld net price is its own `NetPrice` case
   (`NetPrice.Withheld`, carrying the reason and the BASIS the family's own
   answer selected — a `NetPriceBasis`, which holds no amount at all, so the
   number being withheld has no field to ride in), so a site that has not
   handled withholding fails to COMPILE rather than printing the school's
   silence over our rule. The withheld figure says so on its own `net_price`
   object too — the reason code and its statement, beside the basis and, on a
   band-specific basis, the band label that basis promises — because an object
   with a basis and no amount is otherwise indistinguishable from the school's
   silence. The withheld FIELD NAMES join `data_availability` — one list, one
   instruction, "no number for this field here" whatever the cause, and absent
   rather than empty when a school reports everything — and the REASON rides
   beside them in a new `withheld_figures` array, because a flat array of
   strings cannot carry a reason and re-typing its entries would break every
   existing consumer of that key for one new case. The tool DESCRIPTION now
   names both causes of a `data_availability` entry: the school does not report
   the field, or we hold the field and it is not this family's.
4. **`ComparisonBasis`** gains `BlendedFigureBasis` with a stable code and a
   ready-written statement, and `SingleSchoolBasis` the single-school form.
5. **`CostReportPage`** renders the labelled blank and the new sentence; the two
   hint lines gain the residency clause.
6. **`CollegeCostChatTool`** emits the sixth basis object and the withheld
   reason, and states the blended-figure outcome per college in the cost object
   too — the one-college answer builds no `comparison_basis` (RFC 151 D-B) and
   is exactly where the basis would otherwise go unsaid. TWO keys, because the
   fact has three states: `applies_to_this_family_basis` carries the state
   itself and is ALWAYS written, so no outcome is readable only from a key that
   is not there (and an absent key had two causes — an open residency question
   and an unrecognised control — the coach could not tell apart), while
   `applies_to_this_family` stays the known-only boolean, so an open residency
   question is never shipped as a `false`.
7. **Migration A**: schema comments (D-E). **Migration B**: prompt v16 (D-F).
8. **`ForbiddenCostArithmeticTest`** (RFC 149 D-F rule 2) is retuned, because
   this RFC's own copy trips it: `"...out-of-state tuition and fees..."` is
   English with a hyphen in it, and the old scan read the hyphen as a minus
   between two money words — eleven prose lines reported as price arithmetic.
   The fix is at the source rather than in the operator: the scan anchors its
   OPERANDS on the money vocabulary, on either side of the minus, so prose
   cannot match — neither side of `out-of-state` is a money identifier — while
   the minus itself stays whitespace-agnostic, so `a-b`, which the compiler
   accepts and ktlint's `spacing-around-operators` does not police inside a
   string template, is still caught. Nothing is blanked, so a subtraction
   written INSIDE a `${...}` template is judged as the code it is: an earlier
   revision blanked whole double-quoted literals to silence the prose and went
   blind to exactly that shape (and to raw strings and `'"'` char literals with
   it). The positive control carries the unspaced shape, the hyphenated-copy
   line and the interpolated subtraction, so every half of the retune is pinned.
9. **`FitLensService`** labels the net price it puts in the candidate prompt:
   the key reads `inStateNetPricePerYearUsd` and one line above the list says it
   is the school's own published in-state figure, not this family's. This read
   goes round `CollegeCostService` and therefore round its withholding; the
   search index it reads carries no residency (D-G), so WITHHOLDING there
   belongs to the /chart slice. Labelling at the boundary is what can be done
   honestly today, and it means no model is handed a bare number whose residency
   basis is unstated.
10. **`CollegeSearchTool` / `SimilarCollegesTool`** carry the same label for the
    same figure: `matchObject`'s `net_price_per_year_usd` and every
    `net_price_by_income_band` amount reach the coach through both tools, so one
    shared `NET_PRICE_BASIS_NOTE` (in `CollegeMatchRow.kt`, beside the row it
    describes) is appended to both DESCRIPTIONs. Labelling only, for the same
    reason Design 9 is labelling only: the index carries no residency (D-G).

## Files Modified

- `db/schema/<n>.name-blended-figure-residency-basis.sql` (comments only)
- `db/schema/<n+1>.seed-coach-system-prompt-v16.sql`
- `service/.../coaching/costs/CostField.kt`, `CollegeCostService.kt`,
  `ComparisonBasis.kt`, `SingleSchoolBasis.kt`, `CollegeCostChatTool.kt`
- `public-web/.../render/CostReportPage.kt`
- `service/.../coaching/fitlens/FitLensService.kt` (the boundary label,
  Design 9)
- `college/.../CollegeMatchRow.kt`, `CollegeSearchTool.kt`,
  `SimilarCollegesTool.kt` (the same label on the same figure, Design 10)
- `college/src/test/resources/scorecard-institutions-real-fixture.csv` (one more
  verbatim real row: UC San Diego, 110680)
- `service/src/main/resources/service.conf` (prompt pin)
- tests listed below

## Implementation Plan

1. `CostField` residency axis + the schema-comment migration.
2. The `CollegeCost` derived flag beside `applicableTuitionFor`.
3. Service withholding + `data_availability` reason.
4. `BlendedFigureBasis` in `ComparisonBasis` and `SingleSchoolBasis`.
5. Chat tool payload (sixth fact, withheld reason) + its tests.
6. Report page rendering + its tests.
7. Prompt v16 + pin.

## Tests

- **The UCSD case, as a fixture**: a WA family, a CA public with all seven
  components — assert the arrangement totals are the out-of-state ones, and that
  the published price and net price are withheld with the stated reason.
- The in-state family at the same school: both figures print, basis stated.
- A private college: unchanged, both print.
- Residency unanswered: both print WITH the basis sentence, nothing withheld.
- `comparison_basis` carries six facts; the exact-five assertion is updated
  deliberately, not deleted.
- The report page: labelled blank, its reason, and the pointer to the totals.
- A guard test asserting that no surface fed by `CollegeCostService` — the read
  itself, the chat tool payload, and the Family Cost Report page — prints
  `COSTT4_A` or an `NPT4` figure to a non-matching-residency family. That is the
  span of the guard, stated rather than overclaimed: the fit-lens candidate
  prompt (`FitLensService`) reads the college row directly, so the withholding
  never reaches it. It is an explicit BOUNDARY here — labelled at the boundary
  by Detailed Design 9, with its own test, and owned as a withholding problem by
  the /chart search-index slice (D-G).
- **A data invariant over the real rows this repo commits**: for every
  `CONTROL=1` row carrying all seven components,
  `min(in-state arrangement totals) <= COSTT4_A <= max(in-state arrangement totals)`.
  This is the falsifier for the whole RFC, so it is worth owning as a test.
- The fit-lens candidate prompt names its net price as the school's in-state
  figure, and carries no unlabelled `netPricePerYearUsd=`.
- A non-matching public school that publishes NEITHER figure withholds nothing
  and keeps both fields in `notReported`: the school's silence stays the
  school's.
- A one-college answer states the blended-figure outcome: the always-written
  `applies_to_this_family_basis` code in all three cases, and the known-only
  `applies_to_this_family` boolean in the two known ones.
- A school that reports everything ships NO `data_availability` key: absent,
  never empty, the convention `withheld_figures` follows.
- A table holding a school whose control we could not recognise says so, and
  names it, rather than claiming a residency basis for every school in it.
- The vocabulary itself: a withheld figure can only be paired with a reason its
  own residency axis carries, and a drifted control states the basis rather than
  withholding.
- Prompt v16 = v15 byte-identical plus one paragraph; v15 stays selectable.

## Open items

- The data invariant now runs over TWO real public rows — Auburn University at
  Montgomery (100830) and UC San Diego (110680), both verbatim Scorecard rows in
  `scorecard-institutions-real-fixture.csv`, the second being the institution
  this RFC argues from. `n = 2` is not a corpus; the scan widens by itself the
  day a real snapshot is ingested, and it names what it reached when it fires.
- The search index (D-G) goes to /chart as its own slice.
- `TUITIONFEE_IN` is strictly the in-district rate at institutions that
  distinguish them; immaterial for a public four-year and not modelled here.
