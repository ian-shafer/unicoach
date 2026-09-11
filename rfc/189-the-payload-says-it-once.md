# RFC 189 — the payload says it once

Product slice: `concise/02/the-payload-says-it-once`
(`product/0009-say-it-once/spec.md`).

## Problem

The `college_cost_profile` payload states the same provenance facts once per
field per college. `figureStatusesOf` (`CollegeCostService.kt:1979-1993`) walks
all 17 `CostField` members and emits a note for every field the read holds
provenance for — shown as well as blank — and `figureStatusObject`
(`CollegeCostChatTool.kt:747-775`) writes `assurance`, `assurance_statement` and
`source` on every one of them. Brief 0009's DISCOVER **reconstructed** a
realistic mix (10 shown, 7 blank) at ~5,366 bytes per college, ~75% of it
byte-identical, carrying exactly **one** distinct assurance sentence
(`product/0009-say-it-once/research/coach-verbosity.md` D1). That figure was
assembled from the real copy strings, not captured from a running service, and
this RFC does not rely on it: the budget below is measured on this run's own
fixture, before and after — 4,980 bytes, and carrying **three** distinct
`(tier, publisher)` pairs rather than the reconstruction's one (D5).

Two smaller repeats sit beside it:

- `comparison_basis.residency.by_college` and
  `comparison_basis.blended_figure_basis.by_college` are written unconditionally
  (`CollegeCostChatTool.kt:175`), so for N schools each fact is stated 1 + N
  times even when every school is on the same basis. The file already has the
  opposite rule for the same shape: `CHOSEN_BY_COLLEGE_KEY` is emitted only when
  the schools actually differ (`:273-286`,
  `ArrangementBasis.planNamedPerCollege`).
- `ArrangementCost.lines` is `listOfNotNull(tuitionLine) + componentLines`
  (`CostBreakdown.kt:319`), so the one residency-selected tuition figure is
  written under the same wire key inside all three arrangement objects
  (`:1031-1043`) — and a fourth time as the top-level tuition-tier key.

The coach recites what it is handed. Removing the cue is cheaper, and safer,
than writing a prompt rule that asks the model to ignore it.

## Decision

**Say each provenance fact once per college, and say a field's own provenance
only where it differs.** Three changes, all on the wire and its two domain
derivations. No DDL, no migration, no seeded row, no coach prompt version — the
per-figure "say it beside the figure" instruction this RFC narrows lives in the
tool DESCRIPTION (`:1736-1741`), which is ordinary code with a code revert
(brief 0009 DISCOVER 5).

**D1 — one default provenance object per college, and it is mandatory.** A new
per-college key `figure_provenance` carries `assurance`, `assurance_statement`
and `source`: the `(assurance, source)` pair most of this college's figures are
on. A `figure_statuses` entry is emitted only for a field that has something of
its own to say — a status sentence, a year gap, or a tier or publisher that
differs from the default.

**The resolution rule is PER KEY.** An entry repeats a provenance key only where
**that key** differs from the default, and a key it does not carry **is** the
default's. So an entry whose publisher alone differs carries `source` alone; one
whose tier alone differs carries `assurance` + `assurance_statement`; one that
differs in both carries all three. Each of the two facts resolves independently
as "the entry's own value, else the default's", which is still mechanical and
still lossless.

`assurance` and `assurance_statement` **never split** — the `income_band` +
`income_band_label` convention (RFC 151 D-D). They are not two nullable fields
that must agree: the tier rides as one value (`SpokenAssurance`) whose sentence
is derived from its own code through `AssuranceTierCopy`, so an entry with a
code and no sentence, or with a sentence that disagrees with the code beside it,
is not constructible.

This replaced an earlier "all three keys or none" rule, and the reason is
measured rather than aesthetic. The real corpus carries **three** distinct
`(tier, publisher)` pairs per college, not one: the College Scorecard relays
sixteen IPEDS cells (the same tier, a different publisher) **and** originates
two federal administrative records, `GRAD_DEBT_MDN` and `MD_EARN_WNE_P10` (a
different tier, the same publisher). All-three-or-none therefore paid the
172-byte administrative-record sentence on every entry whose tier was not what
differed — 2,228 bytes on the budget fixture against 1,864 for the per-key rule
(D5).

**The one narrowing, said plainly.** The slice text says the default key is
"mandatory, never optional". This RFC narrows that in exactly one case: a
college that holds **no figure provenance at all** carries no
`figure_provenance` key. A mandatory default over an empty set would be an
invented value — a tier and a publisher for no figure — and an invented value is
worse than an absence. The invariant the bar exists to protect is kept in full:
**an exception can never exist without a default**, because the two ride in one
type and the default field is non-null. There is no payload in which a reader
must guess a missing default.

The default is not optional **by type**, not by convention. The domain derives
one `FigureProvenanceSummary(default, exceptions)` from the note list, and its
`default` field is non-null; the summary as a whole is absent exactly when the
college holds no figure provenance at all, which is the case where there is no
figure to resolve. A reader therefore never meets a payload with exceptions and
no default, and never guesses: every figure resolves mechanically to exactly one
tier and exactly one publisher as _its own entry's pair, else the default_.

Identity is the pair **as the wire carries it** — `AssuranceTier` plus
`MoneySource` — not the `PublishedCell`. That is what makes the compression
lossless by construction rather than by review: two notes that agree on those
two values rendered byte-identical triples before this RFC, so collapsing them
removes bytes and no facts.

**D2 — `by_college` only where a school differs.** `ResidencyBasis` and
`BlendedFigureBasis` each grow a `namedPerCollege` derivation in the shape of
`ArrangementBasis.planNamedPerCollege`, and the renderer emits `by_college` only
when it is non-empty. A school is worth naming when the call-level sentence is
not the whole truth about it:

- residency: the scope is `mixed`, **or** any entry is
  `ComparedTuition.PublishedPriceUnknown` (it carries `source_control`, a datum
  no call-level sentence holds), **or** the entries are not uniform in their
  `tuition_basis` code.
- blended figures: the scope is `withheld_at_some_schools` or
  `basis_unknown_at_some_schools` (both sentences point the reader at a school's
  own line), **or** the entries are not uniform in `(code, applies)`.

The uniformity clause is the safety net and is deliberately not redundant: it is
what makes "nothing unreachable" a property of the data rather than of a scope
table staying correct. `college_id` and `name` are not lost with the array —
`colleges[]` carries both.

**D3 — each college's tuition figure once.** `breakdownObject` stops writing the
tuition line inside each arrangement object and the breakdown carries one
`tuition_field_in_totals` key naming the payload's own top-level tuition key
whose amount every total in this object includes. The figure stays exactly where
`putTuitionTiers` already writes it, the composition of each total stays
derivable, and the arrangement objects keep carrying their own components, their
`total_usd` and `assumed_by_unicoach`.

`tuition_field_in_totals` is mandatory whenever any arrangement carries a total:
a total is `null` unless the tuition line is present (`CostBreakdown.kt:340`),
so a total with no tuition field named is a state the domain cannot build, and a
test asserts the named key has a top-level amount in the same college object.

**D4 — the nine byte-frozen sentences are untouched.** Six in
`FigureStatusCopy`, three in `AssuranceTierCopy`. This RFC changes how many
times a sentence is emitted and nothing about its bytes. No paragraph opener and
no pinned prompt line is touched; `prompts/coach-system-prompt.md` is **not**
edited (its only `figure_statuses` line, `:124`, stays true — a figure with no
amount still gets an entry carrying the sentence to say).

**D5 — the budget is a test, not a paragraph.** `CollegeCostChatToolTest`
asserts a byte budget on a fixed fixture college: the provenance block
(`figure_provenance` plus `figure_statuses`) must be **≤ 40% of** the same
fixture's pre-RFC size, i.e. at least 60% smaller (gate-2 D13, gate-1 D4). The
baseline is measured on this run's base commit with the same fixture and
recorded here, so the budget is a real before/after rather than a reconstructed
one.

**The measured numbers.** Both were captured by serialising the college object's
own `figure_statuses` value and taking its UTF-8 length, through the real
service and the real test database — the "before" on this branch's base commit,
with a temporary test that was then removed.

| Fixture                                                           | before  | after   | after ÷ before |
| ----------------------------------------------------------------- | ------- | ------- | -------------- |
| `Six Statuses U` (`seedBudgetFixtureCollege`, the budget fixture) | 4,980 B | 1,864 B | **37.4%**      |
| `Plain U` (a bare `CostsTestDb.seedCollege`)                      | 3,365 B | 999 B   | **29.7%**      |

"after" is the whole provenance block, `figure_provenance` plus
`figure_statuses`; "before" is `figure_statuses`, which was the whole of it.
`Six Statuses U` is the budget fixture because it is the richer of the two: it
carries one cell per status as well as the shown rest, and all three
`(tier, publisher)` pairs.

**A correction to brief 0009's record.** DISCOVER's ~5,366 bytes
(`product/0009-say-it-once/research/coach-verbosity.md` D1) was
**reconstructed** from the real copy strings and never captured from a running
service, and this RFC does not rely on it. Two things about it are worth
recording, because the first budget this RFC drafted was built on them:

- the real measured baseline on a comparable fixture is **4,980 bytes**, not
  5,366;
- the reconstruction assumed **one** distinct `(tier, publisher)` pair per
  college. The real payload carries **three**, because the Scorecard both relays
  IPEDS cells and originates two federal administrative records. That is what
  made the per-key rule in D1 necessary to reach this budget at all:
  all-three-or-none measured 2,228 B, i.e. 44.7%, and missed it.

## Detailed Design

### The domain derivation

In `CollegeCostService.kt`, beside `FigureStatusNote`:

```kotlin
/** A tier and the sentence that says it, as ONE value: the two can never be separated. */
@JvmInline
value class SpokenAssurance(val tier: AssuranceTier) {
  val statement: String get() = AssuranceTierCopy.statementOf(tier)   // derived, never passed in
}

/** The (tier, publisher) pair most of a college's figures are on, and the fields that are not on it. */
data class FigureProvenanceSummary(
  val default: FigureProvenanceDefault,
  val exceptions: List<FigureProvenanceException>,
)

data class FigureProvenanceDefault(
  val assurance: SpokenAssurance,
  val source: MoneySource,
)

/** One field off the default, carrying ONLY the facts that differ. Null means "the default's". */
data class FigureProvenanceException(
  val note: FigureStatusNote,
  val assurance: SpokenAssurance?,
  val source: MoneySource?,
)
```

The two overrides are independently nullable because the two facts vary
independently (D1). They are not `AssuranceTier?` plus `String?`: the tier's
code and its sentence ride in one value, so the renderer cannot be handed a code
with no words.

`CollegeCost.figureProvenance: FigureProvenanceSummary?` is derived from
`figureStatuses`:

1. `null` when `figureStatuses` is empty — no figure, nothing to default.
2. the default is the most frequent `(assurance, source)` pair; ties are broken
   by the earliest field in `CostField` declaration order, so the choice is
   deterministic and does not depend on map iteration.
3. `exceptions` keeps a note when `note.statement != null`, or
   `note.heldAcademicYear != null`, or its tier differs from the default's, or
   its publisher differs from the default's — in `CostField` declaration order,
   as today — and narrows each note to the overrides it actually has.

`figureStatuses` itself is unchanged and stays the complete list: `public-web`'s
report page reads the domain, not this payload, so the page keeps every note it
has today and this RFC cannot shorten it.

### The wire

`figure_provenance` (new, per college, present iff `figureProvenance != null`):

```json
"figure_provenance": {
  "assurance": "mandatory_survey",
  "assurance_statement": "The college had to report this to the federal government, ...",
  "source": "ipeds_ic_ay"
}
```

`figure_statuses` keeps its key, its array shape and its entry keys. An entry
carries a provenance key only where **that key** differs from the default:
`source` alone where the publisher moved, `assurance` + `assurance_statement`
where the tier moved, all three where both did, and none of the three where
neither did. `field`, `status`, `statement` (absent for a shown plainly reported
figure) and `held_academic_year` are unchanged.

`cost_by_living_arrangement` gains `tuition_field_in_totals` and its arrangement
objects lose the tuition key. `comparison_basis.residency.by_college` and
`comparison_basis.blended_figure_basis.by_college` become conditional.

### The DESCRIPTION

The three paragraphs that describe the old shape are rewritten to describe the
resolution rule: where provenance lives, that each of the two facts is resolved
on its own as the entry's own value where the entry carries one and the
default's otherwise, that `assurance` and `assurance_statement` always travel
together, that a field with no entry is shown and plainly reported on the
default pair, that `by_college` appears only where schools differ, and that the
tuition figure is the top-level key `tuition_field_in_totals` names. The
per-figure "say it beside the figure" instruction becomes "say the tier once for
the figures on the default, and name a figure's own tier where it has one" — the
`assurance_statement` sentence itself is still quoted verbatim and still never
re-worded, ranked or scored (RFC 179 D10).

## Files Modified

| File                                                                            | Change                                                                                                              |
| ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt`      | `FigureProvenanceSummary` / `FigureProvenanceDefault`, `CollegeCost.figureProvenance`                               |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt`         | `ResidencyBasis.namedPerCollege`                                                                                    |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/BlendedFigureBasis.kt`      | `BlendedFigureBasis.namedPerCollege`                                                                                |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt`     | `figure_provenance`, thinner `figureStatusObject`, conditional `by_college`, `tuition_field_in_totals`, DESCRIPTION |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt` | the cases below, the byte budget                                                                                    |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`  | the default/exception derivation unit cases                                                                         |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/ComparisonBasisTest.kt`     | the `namedPerCollege` cases, including the unplaceable school the DB cannot store                                   |

No DDL. No migration. No `db/schema/ORDER` line. No prompt file edit, so
`bin/prompt-seed` writes nothing at land.

## Implementation Plan

1. Measure the baseline: on the base commit, serialize the fixture college's
   `figure_statuses` and record its byte count. This number goes in the RFC and
   in the budget test's comment.
2. Add the domain derivation and its unit tests (default selection, tie break,
   exception predicate).
3. Add `namedPerCollege` to both basis types with their uniformity clauses.
4. Change the renderer: `figure_provenance`, the thinner entry, the two
   conditional arrays, `tuition_field_in_totals`.
5. Rewrite the affected DESCRIPTION paragraphs.
6. Update the existing payload tests to the new shape and add the new cases.
7. `nix develop -c bin/test` (no module, no `-f`).

## Tests

Named, and each one is a case compression could break:

- **mixed publisher** — a college whose fields come from two sources: the
  default names one, and every field on the other carries its own `source` **and
  neither tier key**, because the tier did not move.
- **mixed tier** — two tiers on one college: the off-default fields carry their
  own `assurance` + `assurance_statement`, together, never one without the
  other, and no `source` where the publisher is the default's.
- **both differ** — an entry whose tier AND publisher are off the default
  carries all three keys.
- **`not_collected_by_us`** — still an entry, still with its sentence.
- **`held_academic_year` year gap** — still an entry, and the year still travels
  as data.
- **two schools on different residency bases** — `by_college` IS emitted, with
  both schools.
- **two schools on the same basis** — `by_college` is absent and no per-school
  fact is lost.
- **uniform-but-unplaceable** — one `published_price_unknown` school makes
  `by_college` emit so `source_control` survives.
- **total resolution** — every field in the payload resolves to exactly one tier
  and one publisher from default + exceptions, asserted by walking the payload.
- **tuition once** — the tuition key appears exactly once in the college object,
  `tuition_field_in_totals` names it, and that key carries an amount.
- **byte budget** — the provenance block is ≤ 40% of the recorded baseline.
- **frozen sentences** — the three `AssuranceTierCopy` sentences and the six
  `FigureStatusCopy` sentences are asserted byte-identical where they appear.
