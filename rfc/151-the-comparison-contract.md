# RFC 151 — The comparison contract

Status: proposed\
Slice: `money/03/comparison-contract` (brief 0003, M3)\
Base: `main@d1d55d16` · Branch: `pipeline/rfc-151`

## Summary

A side-by-side is where cost advice either earns trust or quietly lies. A dollar
figure is a statistic about a population, a year, a residency and a living
arrangement, and a bare column label hides all four.

This RFC makes the assumptions travel with the numbers. When a
`college_cost_profile` result carries two or more colleges, the payload gains
one new per-call object, `comparison_basis`, carrying five facts: whose price it
is, the residency held constant (stated per school), the living arrangement held
constant, the academic year and its vintage, and the aid basis. Coach prompt v11
tells the coach to state those five lines as ordinary copy **above** the table,
to render the stable block (tuition and fees) above the estimate block, to leave
a labelled blank rather than a zero, and never to mix two bases into one column.

No new table, no DDL. One migration seeds prompt v11.

## Decisions

### D-A. `comparison_basis` is per call, not per college; residency is the one per-school element inside it

The slice left this open. Per-call wins: four of the five facts (population,
arrangement, year, aid basis) are identical for every college in a call, and
repeating them per college invites the coach to read one school's copy and treat
it as that school's own caveat. Residency is genuinely per school — a public
school in the family's state and one outside it do not share a tuition line — so
it appears as a `by_college` array _inside_ the per-call object, which is
exactly the shape "held constant and stated per school" describes.

Rejected: per-college duplication (invites divergent-looking caveats, and the
payload already repeats the two vintage objects per college — a wart, not a
precedent to extend).

### D-B. It rides when the payload carries two or more colleges

The contract is about schools appearing _together_. A one-school answer is
already fully labelled by RFC 149's per-college keys, and adding a "comparison"
object to it would invite the coach to narrate a comparison it is not making.
`count >= 2` is the gate; below it, the key is absent (the payload's
never-emit-an-empty-container convention).

### D-C. Assembled in the service, rendered by the tool

Same split as RFC 149 D-C. A `ComparisonBasis` domain type is built in
`CollegeCostService` from facts it already holds (the `MoneyProfileStatuses`,
the per-college `TuitionApplicable`, the arrangements present in each breakdown,
the `ScorecardVintage` constants); `CollegeCostChatTool` only serialises it. No
new query, no new statement — the batching contract of `readInSession` is
unchanged.

### D-D. Every fact carries a code _and_ a spoken statement

The repo's paired-label convention (`income_band` + `income_band_label`,
`share_label`, `average_label`): whenever a code goes on the wire, the sentence
the coach may say goes with it, from the same construct. So the coach reports
the basis rather than composing it from memory — which is the point of the
slice. The statements use the RFC 141 vocabulary verbatim: _tuition and fees_,
_housing and food_, _the published price_, _a financial aid offer_, and they
never subtract loans.

### D-E. The arrangement fact names the arrangements comparable across _all_ the colleges in the call

"Living arrangement held constant" is only truthful if the arrangement exists
for every school in the column set. `comparison_basis.living_arrangement`
therefore carries `comparable`, the intersection of the arrangements present in
every college's breakdown, and `incomplete_by_college`, naming each college that
lacks one of them. A school with no residence halls is stated as such (the
existing `offers_on_campus_housing = false` fact), not as missing data.

### D-F. Missing data stays a labelled blank; the basis object adds no new silence channel

`data_availability` per college is already the positive statement of silence
(RFC 149). This RFC adds no second list of missing figures and no zeros. The new
rule lives in the prompt: a school missing a component renders a labelled blank
in the table, is never summed as zero, and never borrows a neighbour's number.

### D-G. Prompt v11 is v10 plus one appended, positively worded comparison paragraph

The catalog is insert-only and immutable; a copy change is a new version, never
an edit. v11 keeps v10 as a byte-identical prefix and appends one paragraph. It
is worded positively so `assertFalse(...)` over the appended span stays
available (the RFC 141 glossary is stated contrastively elsewhere and cannot be
swept). It also restates the three-column cap (RFC 124) in the concrete
comparison case: rows are schools, and a comparison table stays within three
columns or becomes a list.

Every occurrence of "subtract" in the new paragraph is immediately preceded by
"never ", so `SystemPromptCatalogTest`'s served-body guard keeps passing.

### D-H. No DDL. One migration, and rollback is one environment variable

`db/schema/0066.seed-coach-system-prompt-v11.sql` inserts one row. The pin moves
to `v11` in `service/src/main/resources/service.conf`. Rollback is
`COACHING_SYSTEM_PROMPT_VERSION=v10`; the v10 row is immutable and stays in the
catalog. Migration and RFC numbers are claimed at commit time — RFC 150 landed
`0064` and `0065` (and prompt v10) while this branch was being built, so this
seed is `0066` and the version it adds is `v11`.

## Detailed Design

### Read path (`:service`)

New file
`service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt`:

```kotlin
/** The five facts that make a multi-school cost table honest (RFC 151). */
data class ComparisonBasis(
    val population: PopulationBasis,
    val residency: ResidencyBasis,
    val livingArrangement: ArrangementBasis,
    val academicYears: List<DatedFigures>,
    val aid: AidBasis,
)
```

- `PopulationBasis` — a constant: code `first_time_full_time_aid_recipients`,
  statement _"These are averages for first-year, full-time students who received
  federal aid — not a quote for this family."_
- `ResidencyBasis` — the family's answer as a sealed `ComparedResidency`
  (`Answered(state)` / `Unanswered` / `Declined`, mirroring `ComparedTuition`,
  so an answered-with-no-state is unrepresentable rather than merely unwritten),
  rendered as `status` (`answered` / `unanswered` / `declined`) plus
  `residency_state` on the answered case only, one statement — computed from the
  colleges actually in the call, so an all-private table gets no caveat about
  public tuition and a mixed one names the public schools the residency is about
  — beside a `basis` code for that same decision (`all_public` / `no_public` /
  `mixed`, a `ResidencyScope`), because D-D means the sentence never ships alone
  — and `by_college`: `{college_id, name, tuition_basis, statement}` for every
  college. `tuition_basis` is its OWN key with its own five-code vocabulary, not
  the per-college `tuition_applicable`: that one is the public-only `in_state` /
  `out_of_state` / `unknown` fact, while this one also answers what kind of
  school it is, and one name for two vocabularies would make a reader remember
  which object it is in. A public school carries `in_state` / `out_of_state` /
  `unknown`; a private school has one published price and says so once with
  `tuition_basis: "single_published_price"`; a college whose control is outside
  the Scorecard vocabulary carries `published_price_unknown`, stays in the
  array, and carries `source_control` — the raw control rendered as the same
  label the per-college `control` key uses ("unknown (control [9])"), so the
  value that defeated the residency line is recoverable without a bare source
  code on the wire (RFC 143). That fifth code is its own fact: `unknown` states
  that the family's state is not on file, which is the wrong missing fact to
  state about a school whose kind we never recognised, and dropping the school
  would be the unlabelled silence D-F forbids.
- `ArrangementBasis` — `comparable` (intersection over the call, in
  `LivingArrangement` declaration order), `incomplete_by_college`, statement. A
  gap carries `reason: no_on_campus_housing` when the school's own
  `offers_on_campus_housing` fact says it has no residence halls, and
  `reason: not_reported` when it publishes no figure for that way of living (the
  RFC 149 D-B split, not reintroduced as one code). A school with both kinds of
  gap appears **twice** in `incomplete_by_college`, once per reason, because one
  entry means "these arrangements, for this reason".
- `DatedFigures` — reuses the existing shape `{academic_year, figures}` per
  `ScorecardVintage` present in the call, plus a statement naming the year.
- `AidBasis` — code `grants_and_scholarships_only`, statement _"A net price is
  the published price minus the average grants and scholarships the school gave.
  Loans and work-study are never subtracted."_ This is the one fact the payload
  has never carried; the definition is lifted from the `0059` column comment.

`CollegeCostService.readInSession` builds it after the per-college list is
assembled, from data already in hand, and returns it on the profile. It is null
when fewer than two colleges are read.

### Wire (`:service`, `CollegeCostChatTool`)

One new top-level key in `profileObject`, emitted only when non-null:

```json
"comparison_basis": {
  "population":  {"basis": "first_time_full_time_aid_recipients", "statement": "..."},
  "residency":   {"basis": "mixed", "status": "answered", "residency_state": "CA",
                  "statement": "...",
                  "by_college": [{"college_id": "...", "name": "...",
                                  "tuition_basis": "in_state", "statement": "..."}]},
  "living_arrangement": {"comparable": ["on_campus", "off_campus"],
                         "incomplete_by_college": [{"college_id": "...", "name": "...",
                                                    "missing": ["on_campus"],
                                                    "reason": "no_on_campus_housing"}],
                         "statement": "..."},
  "academic_years": [{"basis": "published_price_academic_year",
                      "academic_year": "2022-23", "figures": ["..."], "statement": "..."}],
  "aid": {"basis": "grants_and_scholarships_only", "statement": "..."}
}
```

`comparable`, `incomplete_by_college` and `academic_years` follow the payload's
absent-never-empty convention (the same one D-B gates the whole object on): an
empty list is omitted rather than emitted empty, and
`living_arrangement.statement` says which case the call is in either way. A call
whose schools report only the undated cohort figures dates nothing, so it
carries no `academic_years` key at all rather than an empty array.

Per-college keys are untouched, so nothing moves relative to
`putVintageLabels`'s ordering rule. The tool description gains a paragraph
stating the contract, asserted in the tool test as RFC 149 did.

### Prompt

`db/schema/0066.seed-coach-system-prompt-v11.sql` = the v10 body verbatim,
joined by one leading space to one new paragraph. Its content, in the same
voice:

- Whenever two or more schools appear together, say the five lines from
  `comparison_basis` first, as ordinary sentences above the table, not as a
  disclaimer at the bottom.
- Put tuition and fees — the price the school sets and publishes — above the
  estimated living costs, and say which block is which.
- Rows are schools; keep the table to three columns and short cells, or say it
  as a list.
- When a school does not report a part, leave the cell blank and label it as not
  reported; never write a zero, never carry a neighbour's number across, and
  never add up what is there and call it the total.
- A school with no residence halls has none — say so rather than calling it
  unreported.
- Never mix two residencies or two living arrangements into one column, and
  never subtract loans, work-study, or a net price from any of these figures.

## Files Modified

| File                                                                            | Change                                              |
| ------------------------------------------------------------------------------- | --------------------------------------------------- |
| `db/schema/0066.seed-coach-system-prompt-v11.sql`                               | new — v10 + one appended comparison paragraph       |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt`         | new — the five basis types                          |
| `service/.../coaching/costs/CollegeCostService.kt`                              | assemble `ComparisonBasis`; carry it on the profile |
| `service/.../coaching/costs/CollegeCostChatTool.kt`                             | serialise `comparison_basis`; description paragraph |
| `service/src/main/resources/service.conf`                                       | prompt pin `v11` + comment block                    |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`            | pin literal `v11`                                   |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`       | v11 append test, positive-wording assertions        |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt` | payload + description assertions                    |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`  | assembly, intersection, statement-count guard       |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/ComparisonBasisTest.kt`     | new — the cases no DB row can reach                 |

Not modified: `db/schema/*` beyond the one seed, any table, `bin/*`,
`.claude/**`.

## Implementation Plan

1. `ComparisonBasis.kt` with the five types and their statements; unit-level
   assembly from a fixture profile. Green.
2. `CollegeCostService`: build it in `readInSession`, null below two colleges;
   assert the statement count is unchanged for N colleges. Green.
3. `CollegeCostChatTool`: serialise the key, extend the description, extend the
   bare-source-code guard's nested-key walk to the new object. Green.
4. `0066.seed-coach-system-prompt-v11.sql`, `service.conf` pin,
   `CoachingConfigTest`, `SystemPromptCatalogTest` append + wording tests.
   Green.
5. `nix develop -c bin/test`.

## Tests

- **Service** — with two colleges the basis rides; with one it is null; the
  residency `by_college` entry is `in_state` for a public school in the answered
  state, `out_of_state` outside it, `unknown` when residency is unanswered or
  declined, `single_published_price` for a private school, and
  `published_price_unknown` — the code and the sentence that goes with it — for
  a control outside the Scorecard vocabulary; `comparable` is the intersection
  and drops an arrangement missing from any one college; a no-residence-halls
  school appears in `incomplete_by_college` with reason `no_on_campus_housing`,
  not as unreported, while a school that simply publishes no figure for a way of
  living appears with reason `not_reported`; the residency statement is true of
  the actual college set (an all-public table, an all-private one, and a mixed
  one each get their own sentence); the statement count across N colleges is
  unchanged from RFC 149's number.
- **Tool** — the key is absent for a one-college payload and present for two;
  every basis carries both a code and a non-empty statement; the figures named
  in `academic_years` are exactly the dated figures the same payload rendered,
  and a call that dates nothing carries no `academic_years` key; the RFC 143
  bare-source-code guard passes over the new nested object; the description
  states the contract.
- **Prompt catalog** — v11 is v10 plus exactly one appended paragraph; the RFC
  142 source-jargon sentence and the RFC 141 glossary pairs survive
  byte-identically; the appended paragraph contains no "room and board",
  "sticker", or "award"; the served-body guard (every "subtract" preceded by
  "never ") still passes; the `service.conf` pin exists in the catalog.
- **Type-level (no DB)** — a control outside the vocabulary carries its code and
  its sentence; an entry with nothing missing is refused, naming the school; a
  residency basis with no college is refused; an `answered` residency with no
  stored state is refused as `CorruptPersistedValueException` naming
  `money_profiles.[residency_state]`, never relabelled `unanswered`; each of the
  three residency shapes gets its own `basis` code and its own sentence.
- **Forbidden arithmetic** — `ForbiddenCostArithmeticTest` still passes over the
  cost package with the new file in it.
