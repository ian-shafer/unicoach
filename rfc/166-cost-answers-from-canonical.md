# RFC 166 — Cost answers from the canonical money store

Slice: `shape/04/cost-answers-from-canonical` (brief 0006, gate 2 approved
2026-09-02; gate-2 D17 added 2026-09-05).\
Needs: BLOCKS `shape/01/canonical-store` — LANDED as RFC 158. PREFER
`shape/02/ipeds-ic-ay` — LANDED as RFC 161. CONFLICTS `shape/03/ipeds-sfa`
(`pipeline/rfc-162`) — in flight, no live run at claim time; rebase risk only,
enumerated under **Risks and traps**.

## Summary

The canonical money store is filled and nothing reads it. A grep for
`price_figures|cohort_money_stats|CanonicalMoneyDao` over `service/`,
`rest-server/`, `public-web/`, `admin-web/`, `chat/` and `mcp-server/` returns
zero hits, and `db/schema/0083.create-canonical-money-tables.sql:3-4` says so in
words: "Nothing reads these yet; the `shape/04` slice opens that door."

Every cost answer comes from one `SELECT * FROM colleges WHERE id IN (…)`
(`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:487`) and one exhaustive
switch, `CostField.reportedAmountOf(college)` in
`service/src/main/kotlin/ed/unicoach/coaching/costs/CostBreakdown.kt`. This RFC
replaces exactly those two things. The domain types the RFC 149/151/152/157
honesty layer is built from — `CollegeCostProfile`, `CollegeCost`,
`ArrangementCost`, `NetPrice`, `ComparisonBasis`, `ArrangementGap`,
`NoTotalReason` — all survive. The source under them changes.

The payoff is four user-visible truths that the publisher-shaped columns cannot
carry:

1. **A third tuition tier.** `price_figures` holds `in_district` as a
   first-class residency basis (`IpedsChargeVocabulary.kt:72`, `CHG1AY`), and
   RFC 161 measured 269 institutions where the Scorecard's "in-state" figure is
   really the in-district one. A repo-wide grep for
   `in_district|IN_DISTRICT|inDistrict` outside `rfc/` hits only
   `ResidencyBasis.kt:16`, `IpedsChargeVocabulary.kt:72,75` and two `:college`
   tests. No consumer surface has ever shown the tier.
2. **Fees split from tuition.** `PriceConcept.FEES_ONLY` (`CHG1AF`/`CHG2AF`/
   `CHG3AF`) is canonical-only today; the consumer side has one combined
   `tuition_and_fees_*` figure.
3. **A complete with-family total** whose food-and-housing line is `$0`, with
   the assumption stated in words and attributed to unicoach (gate-2 D17).
4. **The six figure statuses, spoken.** `suppressed_by_publisher`,
   `imputed_by_publisher` and `not_collected_by_us` are stored today
   (`db/schema/0083…sql:80-91`) and reachable by nobody; a NULL column cannot
   tell a family whether a number is missing because the publisher withheld it,
   because the school never reported it, or because we have not collected it.

Nothing regresses. The 168 DB-backed tests in
`service/src/test/kotlin/ed/unicoach/coaching/costs/` keep their expectations
and only change fixture source, and the DB-free `public-web` routing and
share-link tests keep their assertions untouched. There are no golden files
anywhere in this surface — every expectation is Kotlin, so nothing can be
re-blessed silently.

## Four findings the code forced (not decisions taken here)

1. **The `$0` with-family line is a reversal of committed behaviour in four
   places, one of which makes it unrepresentable.** `CostBreakdown`'s own doc
   ("a `$0` housing line there would be a fabricated fact"), the two-part
   `ARRANGEMENT_COMPONENTS[WITH_FAMILY]` map, the `ArrangementCost` constructor
   `require` that refuses a line which is not one of the arrangement's own
   components, and the tool description's with-family paragraph. D17 overturns
   all four **for `with_family` only**. Every other arrangement keeps the
   no-partial-total and no-silent-zero rules exactly, and the zero is a unicoach
   assumption carried as such, never a `price_figures` row.
2. **This is the first coach prompt version that is not "v(N-1) byte-identical
   plus one appended paragraph".** `service.conf:8-74` repeats that phrase for
   RFCs 150/151/152/153/154/155/157/159/160. v19 edits existing blocks: the
   at-home paragraph (`0082…v18.sql:131-158`) and the two-tier residency
   paragraph (`0082…v18.sql:73-88`).
3. **`published_price` is a concept no source fills.** Zero rows
   (`db/schema/0083…sql:122-124`; both fills mapped in `CanonicalMoneyLoader.kt`
   and `IpedsChargeVocabulary.kt:70-84`). Every total this slice serves is
   composed from components; the all-in figure a family sees as
   `sticker_cost_of_attendance_per_year_usd` stays what it is today — the cohort
   blend `published_cost_blend` (COSTT4_A), in-state-basis and withheld under
   RFC 157.
4. **The in-district tier is value-bearing for a minority.** IC_AY covers 3,825
   of ~6,100 institutions (`rfc/161…:44-46`); the ~2,300 IC_PY institutions have
   no `in_district` row at all, and at most of the 3,825 the row exists but is
   flagged `A` → `not_applicable`. The tier is shown where it bears a value and
   is silent — with words — where it does not.

## Detailed Design

### 1. The read API: a new file, not `CanonicalMoneyDao`

New `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyReadDao.kt`. It is a
new file deliberately: RFC 162 both inserted functions into the middle of
`CanonicalMoneyDao.kt` and rewrote its private tail, so a read function added
anywhere in that file is a rebase conflict for no benefit.

Two batched reads, mirroring `CollegesDao.listByIds`'s contract
(`CollegesDao.kt:480-493`): one statement whatever the list size, an empty id
list short-circuits with no query, and the caller owns the `SqlSession` — the
Family Cost Report reads on the caller's connection
(`ServiceCostReportSource.kt:71,80`).

```kotlin
fun listPriceFigures(session: SqlSession, collegeIds: List<CollegeId>): Result<CanonicalMoneyRead<PriceFigure>>
fun listCohortStats(session: SqlSession, collegeIds: List<CollegeId>): Result<CanonicalMoneyRead<CohortMoneyStat>>
```

`CanonicalMoneyRead` (`CanonicalMoneyReadRows.kt:63-66`) is the rows grouped by
college BESIDE the rows this build could not decode (§2).

Both are `SELECT … WHERE college_id IN (?, …)`, served by the existing
`price_figures_college_idx` / `cohort_money_stats_college_idx` (`0083:219,293`).
No latest-year SQL, no window function: a college carries ~48 price rows and ~10
cohort rows, so the year selection is a pure Kotlin function (§3) that a DB-free
test can drive.

Read models go in a new
`db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyReadRows.kt`
(`CanonicalMoneyRows.kt` is write-shaped and rfc-162 appends to it):

```kotlin
data class PriceFigure(
  val collegeId: CollegeId,
  val priceConcept: PriceConcept,
  val residencyBasis: ResidencyBasis,
  val arrangement: FigureArrangement,
  val academicYear: String,
  val reading: FigureReading<Int>,
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String?,
)

data class CohortMoneyStat(
  val collegeId: CollegeId,
  val measure: MoneyMeasure,
  val population: CohortPopulation,
  val residencyScope: CohortResidencyScope,
  val aidScope: CohortAidScope,
  val incomeBand: IncomeBand?,
  val vintage: String,
  val reading: FigureReading<Double>,
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String?,
)
```

**The decode is the reverse of the write.** `CanonicalMoneyDao.kt:190-193`
derives `(amount_usd, status)` from one `FigureReading`; nothing anywhere
constructs a `FigureReading` from a row. `FigureReading.kt` gains one companion
function:

```kotlin
fun <T : Any> of(value: T?, status: FigureStatus, column: String, naturalKey: String): FigureReading<T>
```

exhaustive over `FigureStatus` with no `else`, so a seventh status fails to
compile here. A value-bearing status with a null value, or a value under a
valueless status, throws `CorruptPersistedValueException` naming
`price_figures.[amount_usd]` and the row's natural key — which is why the
signature carries the column and the key rather than the two arguments the
decode itself needs. Neither has a default, so no caller can drop the context
and leave the exception unable to say which row is corrupt. It is the
`requireStoredValueWhenAnswered` precedent (`CollegeCostService.kt:1146`). The
database CHECK already forbids both (`price_figures_value_iff_status_check`,
`0083:206-208`); the Kotlin refusal exists so a hand-written fixture cannot
produce a reading the type system says is impossible. The throw is per ROW and
is caught at the row boundary (§2), so a corrupt cell costs its own row and
nothing else.

### 2. The projection package

New sub-package `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/`,
five files:

- **`CanonicalCostReader.kt`** — the two DAO calls plus the per-college fold
  into a `CollegeFigures` (below). It is an **interface**
  (`CanonicalCostReader.kt:44`) with one production implementation,
  `DbCanonicalCostReader` (`:116`), injected as a defaulted constructor
  parameter into both consumers — `CollegeCostService.kt:938` and
  `FitLensService.kt:98`. That is the ONE door onto the canonical store: no
  consumer imports the DAO, and a test substitutes the store rather than only
  Postgres. One statement per table per request; the batching contract pinned by
  `CollegeCostServiceTest.kt:2682` ("resolving the living plan adds no query per
  college") is preserved, and `readInSession` goes from four statements to five.
- **`CollegeFigures.kt`** — the per-college resolved figure set: a
  `Map<CostField, FigureReading<Int>>`, the chosen academic year per figure
  group, the residency tiers actually published, and the cohort readings. This
  is the type that replaces `College` as the cost path's input.
- **`ServedFigures.kt`** — a college's figures BOUND to the one academic year
  this answer serves them at (§3).
- **`FigureStatusCopy.kt`** — the six statuses, spoken (§6).
- **`ResidencyTiers.kt`** — which tuition tiers this college publishes, and what
  to say about the ones it does not (§4).

**One unreadable row may never fail every college's answer.** The decode is per
ROW, inside the DAO (`CanonicalMoneyReadDao.kt:101-128`): a row whose stored
code this build cannot read raises `CorruptPersistedValueException`, is caught
at the row boundary, and leaves the batch as an `UnreadableMoneyRow` carrying
its table, the stored value and the row's natural key. Both reads therefore
answer `CanonicalMoneyRead(byCollege, unreadable)` — the rows this build could
decode BESIDE the ones it could not. Nothing in `:db` logs, so the unreadable
rows travel out as DATA and `DbCanonicalCostReader` warns over them
(`CanonicalCostReader.kt:126-137`), which puts the warning in the layer that
knows what the read was for. The address such a row would have answered is then
one we hold no row for, so the existing `not_collected_by_us` /
`part_not_collected_by_us` words state it exactly. `Result.failure` is kept for
what it is true of: a broken connection, a missing table, a SQL fault.

The package is a sub-package of `costs/`, and `ForbiddenCostArithmeticTest`'s
sweep is RECURSIVE (`walkTopDown`, `ForbiddenCostArithmeticTest.kt:55-68`) so it
reaches every file in it. A non-recursive scan would have kept passing while the
new money code went unread — a failure that is silent by construction, since the
sweep would still assert an empty offender list — so the recursion carries its
own anti-vacuity test (`:150`).

### 3. Latest year, per key, and no cross-year total

Gate-2 D15 is "store history, serve latest"; nothing implements the read half.
`price_figures` carries four IPEDS academic years (2020-21 … 2023-24,
`IpedsChargeVocabulary.kt:35-38`) and one Scorecard year (2022-23,
`CanonicalMoneyLoader.kt:1124`), so "latest" is a per-key question, not a
constant.

The rule, in order:

1. **One published-price year per COLLEGE, chosen once.** It is the latest year
   that completely prices some way of living at that college, and failing that
   the latest year bearing any published value. Every published-price figure the
   college serves is read at that one year.

   This is deliberately not "the latest year per key". Rules 2 and 4 cannot both
   hold if two arrangements resolve to two years:
   `published_price_academic_year` is one wire key and would have no truthful
   value. One year per college keeps the label honest and keeps every total
   composable.

2. **A total is composed from figures of ONE academic year** — the year rule 1
   chose. The composer takes the tuition figure and every one of that
   arrangement's components at that year.

   The chosen year is not passed as an argument beside the figures. It is BOUND
   to them in `ServedFigures` (`canonical/ServedFigures.kt:31-49`), built once
   by `CostBreakdown.servedFiguresOf` (`CostBreakdown.kt:501-511`), and every
   published-price read this answer makes goes through that object. Two
   `require`s make a wrong year unconstructable: the year must be one the
   college publishes, and a null year is legal only for a college that publishes
   no price row at all. "No year" is therefore a named state about the school —
   `servesNoPublishedPrice` (`ServedFigures.kt:62`) — and not the shape a lost
   year takes.

3. **If the chosen year is not complete, there is no total**, and
   `NoTotalReason` says whose gap it is.

4. **The year is stated, and it names its schools.**
   `published_price_academic_year` keeps its wire key and its meaning; its value
   is read off the rows rather than off a Kotlin constant, and it differs per
   college — a Scorecard-only college says 2022-23 where an IC_AY college says
   2023-24.

   A year in `comparison_basis` is a fact about the SCHOOLS it is true of, so
   `DatedFigures` carries them: `colleges` is a required member whose `init`
   refuses an empty list (`ComparisonBasis.kt:1153-1182`), the statement names
   them, and `DatedFigures.of` groups the colleges BY their year and reads
   figures only from the schools at that year (`:1241-1254`). A comparison
   spanning two years therefore carries two entries that share no figure claim,
   and a year is never read onto a school it does not name. The subject rides on
   the wire as `colleges` (`CollegeCostChatTool.kt:1171`, emitted `:395`).

   Every line carries the year it describes: `CostLine.academicYear` is non-null
   (`CostBreakdown.kt:203`), so an undated line is unrepresentable rather than
   refused at runtime.

5. **A figure held only in ANOTHER year is never dropped in silence.** Choosing
   one year per college means a figure whose only row sits in a different year
   would otherwise vanish with no key and no explanation. It does not. Such a
   figure:

   - is never rendered as a line, and never enters `data_availability` — that
     list means "this college does not report this cost field", which is not
     true here;
   - ALWAYS produces a `figure_statuses` entry at `not_collected_by_us`, naming
     BOTH years: the year this school's prices are shown at, and the most recent
     year we hold the figure for;
   - where it is a component of a shown arrangement, makes that arrangement
     incomplete, with `no_total_reason = part_not_collected_by_us` — ours, never
     "the school does not publish it".

   `not_collected_by_us` is reused rather than a seventh status invented: what
   we have not collected is this figure FOR THIS YEAR, and the statement says
   exactly that. A field with NO row in any year is unchanged — that is our
   source coverage, not a year gap, and it stays in neither published list.

`ScorecardVintage` hard-coded `PUBLISHED_PRICE(2022)` and
`BLENDED_AVERAGE(2021)`. The **years** are deleted; the two-member **grouping**
survives as `FigureGroup(wireName)` in `costs/FigureGroup.kt`, because C's wire
keys keep their names and because `ArrangementCost.init` still needs to refuse a
total that mixes a published price with a blended average. It lives in the money
DOMAIN — the slot `ScorecardVintage` vacated, beside the total that refuses to
sum across it — not in the store projection: `costs.canonical` is the read side
of one store and holds no user of the type.

A figure's group is not declared beside its address; it is READ from it.
`FigureAddress` owes every case a group (`CollegeFigures.kt:88-128`),
`CohortAddress` carries one per measure (`:76`), and `CostField.figureGroup` is
that lookup (`:289`). A field declares which family of figures it belongs to by
declaring where it lives, so a published price can no longer be stamped with the
cohort year by a mistyped second column.

The vintage guard is "exactly one `(group, academic_year)` pair", not "exactly
one enum member" (`CostBreakdown.kt:393-396`):

```kotlin
val dating = lines.map { it.group to it.academicYear }.toSet()
if (dating.size != 1 || dating.single().first == null) throw MixedVintageArrangementException(collegeId, arrangement, lines)
```

`MixedVintageArrangementException` (`CostBreakdown.kt:250-262`) stays. It
becomes **unreachable from the read path by construction** — the composer picks
one year before it reads a line — rather than unreachable by luck. That matters:
today a vintage mismatch is folded into `Result.failure` and surfaces as a **503
on `/report`**, which is the sharpest failure mode this surface has.

Cohort statistics use `vintage` the same way, at the full address rather than
the bare measure (§8): the latest vintage of the row this surface actually
served. The blended-average year is derived from the SERVED addresses and the
family's answered band (`CollegeFigures.blendedAverageVintage:466`), never a max
over every cohort row a college carries — since RFC 162 that fold would stamp
the served figure with the year of an SFA row nobody was shown. The literal
`'undated'` (`0083:277-278`) carries no year and no group, so median debt and
median earnings keep saying no year at all, as they do today.

### 4. Three tiers, and the in-district mislabel

`ResidencyAxis` (`ResidencyAxis.kt:29-48`) gains `IN_DISTRICT`. It is **not**
`IN_STATE_ONLY`, so `CostField.IN_STATE_ONLY_FIELDS` (`CostField.kt:212-213`)
and the whole RFC 157 withholding chain are unchanged by the addition — a point
worth a test, because that chain is derived from the axis and a wrong axis would
silently withhold or un-withhold a figure.

`CostField` gains five members (§5). `CostField.TUITION_FIELDS`
(`CostField.kt:181-191`) gains the in-district member, so the `ArrangementCost`
tuition-slot `require` (`CostBreakdown.kt:343-347`) accepts it.

**What the family is told, per case:**

| The college's rows                                                                 | Shown                                                                                                              |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Value-bearing `in_district`, `in_state`, `out_of_state`                            | Three named tiers. The applicable tuition stays the state-selected one.                                            |
| `in_district` present but `not_applicable` (the typical public 4-year and private) | No in-district key. The tier is not mentioned.                                                                     |
| No `in_district` row at all, `in_state` row `source = scorecard`                   | The in-state figure is served **as in-state**, plus the sentence that the publisher does not separate in-district. |

The third row is RFC 161's open item, handed here in its own words
(`rfc/161…:307-312`): for the IC_PY remainder "re-labelling it in-district on a
guess would trade one wrong label for another". So the figure keeps the label
the publisher gave it and we say what we do not know. **An in-state figure is
never presented as an in-district price.**

**No new question is asked.** Value-before-ask stands: no `money_profiles`
column, no new `PrecisionOffer` member, no district field anywhere.
`money_profiles.residency_state` cannot select in-district — a state answer does
not answer a district — so the in-district figure is shown as a labelled tier
and is never the tuition line inside a total. What widens is the copy:
`CollegeCostChatTool.RESIDENCY_OFFER` (`CollegeCostChatTool.kt:1316-1320`) names
exactly two prices ("the in-state one or the out-of-state one"), which is wrong
at a community college, so a three-tier variant sits beside it (`:1336`).

**The invitation follows the school's `ResidencyTierBasis`, not a figure.** Only
`three_tiers_published` carries the three-tier wording, and the `when` over the
basis is exhaustive with no `else` (`CollegeCostChatTool.kt:955-978`), so a
seventh basis must state its own invitation before it compiles. The tier code,
its statement, the emitted tuition keys and the offer are ONE decision, read
from `CollegeCost.publishedTuitionTiers` (`CollegeCostService.kt:443`,
delegating to `ResidencyTiers.publishedTuitionTiersOf:196`): the tool emits its
tuition keys by iterating that list (`CollegeCostChatTool.kt:423-434`) and
`reportsPublishedTuition` is derived from it (`CollegeCostService.kt:1492`). A
payload can therefore never say "not all three tiers" beside an offer promising
three prices.

**The tier sentence reaches the family too.** The report page prints it
(`CostReportPage.kt:528-543`) from the same derivation, and only at a school
that publishes at least one tuition price: a school publishing none falls to
`single_published_price`, whose words would be a new false claim on a page whose
tuition row is blank.

The prompt half (`0082…v18.sql:73-88`) widens with it in v19. The offer is still
admitted only on the public case and still keys off `AnswerStatus.UNANSWERED`
(`CollegeCostService.kt:838`), so a decline stays permanent and no answer is
gated.

### 5. Wire keys: additive this version

Existing keys keep their names and meanings; the rename to canonical concept
names is the target state and is deferred to `shape/08`. `putVintageLabels`
stays the last figure-bearing key emitted (`CollegeCostChatTool.kt:458`), which
the ordering test at `CollegeCostChatToolTest.kt:2467` pins.

Added to `CostField`, in declaration order after the two tuition members
(declaration order **is** wire order, `CostField.kt:57-164`):

| member                                      | wire key                                    | group           | residency axis |
| ------------------------------------------- | ------------------------------------------- | --------------- | -------------- |
| `TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD` | `tuition_and_fees_in_district_per_year_usd` | published price | `IN_DISTRICT`  |
| `FEES_ONLY_IN_DISTRICT_PER_YEAR_USD`        | `fees_only_in_district_per_year_usd`        | published price | `IN_DISTRICT`  |
| `FEES_ONLY_IN_STATE_PER_YEAR_USD`           | `fees_only_in_state_per_year_usd`           | published price | `IN_STATE`     |
| `FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD`       | `fees_only_out_of_state_per_year_usd`       | published price | `OUT_OF_STATE` |
| `HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD` | `housing_and_food_with_family_per_year_usd` | published price | `null`         |

The fees figures are **not** components of any arrangement: `fees_only` is a
part _of_ `tuition_and_fees`, not a part beside it (`money-vocabulary.json`:
"Published required fees alone, where a source separates them from tuition").
Adding them to `ARRANGEMENT_COMPONENTS` would double-count fees inside every
total. They are reported beside the combined figure and named as a split, and
`ForbiddenCostArithmeticTest` keeps anyone from subtracting one from the other.

Added to each college object:

- `figure_statuses` — array of `{field, status, statement}`, one entry per field
  with no amount (§6). `data_availability` keeps its exact shape (a bare
  wire-name list, absent when empty, `CollegeCostChatTool.kt:577-580`); this
  array is the reason beside it, in the `withheld_figures` shape the payload
  already uses.
- `residency_tiers` — `{basis, statement}`, `basis` ∈ `three_tiers_published` |
  `two_tiers_published` | `publisher_does_not_separate_in_district` |
  `in_district_and_one_other_tier` | `single_published_price` |
  `no_published_tuition` (§4).

  **Six codes, and the last two are why.** The sentence a code ships with may
  never contradict the tuition keys emitted beside it, so every shape a school
  can actually be in needs its own words rather than the nearest wrong ones:

  - a district price beside exactly ONE state tier is neither three-tiered nor
    single-priced — `two_tiers_published`'s words name the in-state and
    out-of-state pair specifically, and `single_published_price` would state one
    price beside two emitted keys. Hence `in_district_and_one_other_tier`.
  - a school we hold NO value-bearing tuition tier for is not a school with one
    price. `no_published_tuition` exists because this fact is emitted for every
    college, so a fall-through would announce "this school publishes one tuition
    price" about a school whose price list we do not have.

  `single_published_price` is correspondingly narrow: it means exactly one tier
  bears a value, and its words do not claim the price applies to everybody — the
  one tier we hold may itself be residency-specific (a public school whose
  out-of-state price is suppressed keeps its in-state one), which the
  `tuition_applicable` key beside it says.
- inside each `cost_by_living_arrangement` entry: `assumed_by_unicoach` — an
  array of wire names whose amount is ours, not the school's. Absent when empty,
  so it appears on `with_family` only (§7).

Added to `comparison_basis`:

- each `academic_years` entry gains `colleges` — the schools that year is a fact
  about (§3, `CollegeCostChatTool.kt:1171`, emitted `:395`). A year with no
  subject is a claim about every school in the call, and in a two-year
  comparison that claim is false of half of them.

Every code ships with its words, from one home (RFC 151 D-D, the `income_band` +
`income_band_label` convention, `CollegeCostChatTool.kt:1294-1300`).

### 6. The six statuses, spoken

`FigureStatusCopy` is an exhaustive `when` over `FigureStatus` with no `else`.
The words are brief 0003's vocabulary, and the OURS/THEIRS split is RFC 149 D-B
reused a fourth time:

| status                        | whose     | statement                                                                                 |
| ----------------------------- | --------- | ----------------------------------------------------------------------------------------- |
| `reported`                    | —         | no statement; the figure is shown plainly.                                                |
| `imputed_by_publisher`        | publisher | "This is the publisher's own estimate for this school, not a figure the school reported." |
| `suppressed_by_publisher`     | publisher | "This figure is withheld by the publisher for privacy."                                   |
| `not_reported_by_institution` | school    | "This school did not report this figure."                                                 |
| `not_applicable`              | school    | "This figure does not apply at this school, and the source says so."                      |
| `not_collected_by_us`         | **ours**  | "We have not collected this figure yet."                                                  |

**The status walk covers BOTH fact tables.** `CollegeFigures.statusOf`
(`CollegeFigures.kt:545-563`) dispatches on the sealed `FigureAddress` with no
`else`, so the compiler owes the cohort arm an answer exactly as it owes the
price arm one. A Scorecard net price the publisher SUPPRESSED FOR PRIVACY
therefore carries the publisher's sentence and never enters `data_availability`
— that list means "this school does not report this field", which is false of a
figure the publisher withheld. The band is part of the address, so the status
described is the status of the row the payload served
(`CollegeCostService.kt:1739-1760`, band resolved once at `:1173`).

**The Family Cost Report routes the same status.** `CostReportPage.blankFor`
(`CostReportPage.kt:626-648`) is exhaustive over `FigureStatus` with no `else`,
and "Not reported by this school" — a statement about a named school, printed
for a parent — survives for exactly two cases: `not_reported_by_institution`,
and a field we hold no row for at all. The publisher's suppression, a figure we
have not collected, and the year gap of rule 5 print the store's own sentence
instead. The page authors none of these words; they are `FigureStatusCopy`'s,
carried on `CollegeCost.statusNoteFor` (`CollegeCostService.kt:501`).

**The prompt says what the payload says.** `SystemPromptCatalogTest` walks
`FigureStatus.entries` and asserts the v19 status paragraph contains
`FigureStatusCopy.statementOf(status)` verbatim (`:1459-1467`), so the coach's
instructions and the wire copy cannot drift into two different sentences about
the same blank.

Two rules the type system carries:

1. **A status that is OURS may never produce an `ArrangementGap`.**
   `ArrangementGap` may only claim what the SCHOOL published
   (`ComparisonBasis.kt:626-676`); `not_collected_by_us` therefore maps to
   `NoTotalReason`, never to `NOT_REPORTED`. The mapping is one function, `when`
   over the status, no `else`.

   This costs a new `NoTotalReason` member, `part_not_collected_by_us`, and it
   must be WIRED, not merely written. A routing function that returns the same
   reason from all of its arms is not a rule; it is dead code that reads like
   one, and it leaves a component WE never collected being explained to a family
   as the school not publishing it. The school's silence and the publisher's
   suppression keep sharing `part_not_published` — that phrase is agentless and
   true of both — but our own gap gets its own words and its own member.
2. **An imputed figure is still a figure.** `imputed_by_publisher` is
   value-bearing (`0083:83`, `FigureStatus.kt:21`), so it enters totals and is
   shown — labelled. The 263 `XFEE2` rows flagged `Z` (implied zero) are the
   only imputed figures in the corpus today (`rfc/161…:234-243`), and they are
   real zeros the publisher implied, so they are shown as `$0` with the
   publisher's-estimate sentence beside them.

`not_collected_by_us` is never emitted by any fill today
(`money-vocabulary.json`, "reserved, never emitted by the v1 Scorecard fill").
It is still spoken, because the alternative is a status with no words that the
first fill to emit one discovers in production.

### 7. Living at home: a complete total, and whose zero it is

Gate-2 D17. No source publishes with-family food and housing — IPEDS assumes
zero and publishes no variable (`IpedsChargeVocabulary.kt:64-68`,
`rfc/161…:33-43`), and neither does the Scorecard. The figure is not missing
data about the school; it is a cost that enrolling does not create.

`ARRANGEMENT_COMPONENTS[WITH_FAMILY]` (`CostBreakdown.kt:57-63`) is three parts:
housing and food, books and supplies, other expenses. The with-family total is
therefore complete and is shown.

**The zero is ours, and the type says so.** `CostLine`
(`CostBreakdown.kt:200-238`) carries an origin:

```kotlin
enum class LineOrigin(val value: String) { PUBLISHED("published"), ASSUMED_BY_UNICOACH("assumed_by_unicoach") }
```

Two `require`s on `CostLine` admit `ASSUMED_BY_UNICOACH` for exactly one pair —
`HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD` at
`ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD` (`:168`) — and refuse both the other
direction and every other assumed line at construction (`:218-237`). No
canonical row is written, no `price_figures` row is invented, and a `$0` cannot
appear under any other arrangement or field. The "never a zero" rule is
otherwise untouched.

**Whose number a line carries is DERIVED, not listed.**
`CostField.isAssumedByUnicoach` is
`figureAddress == FigureAddress.AssumedByUnicoach` (`CostBreakdown.kt:140-141`)
— the field declares that no publisher fills it by declaring where it lives, so
no `price_figures` row is ever looked for and a SECOND figure of ours is
attributed to us the day the compiler forces its address, with no edit here.
`CostLine.origin` has NO default (`:211`): "the school published this" is the
most consequential claim on a line and the most damaging one to make by
omission, so it is stated at every construction site and is never the silent
case.

**The assumption is stated in words, on both surfaces, once.** It becomes a
seventh fact on `ComparisonBasis` and its single-school twin, so it reaches the
coach and the parent-facing report through the mechanism that already exists —
`statements` is one ordered list a renderer may not sub-select from
(`ComparisonBasis.kt:73`, `SingleSchoolBasis.kt:53`). The sentence itself has
one home, `AT_HOME_ASSUMPTION_STATEMENT` (`ComparisonBasis.kt:134-137`), beside
the fact that speaks it and not in the store projection: the canonical package
holds what publishers said, never our own assumption. The statement is present
whenever the with-family arrangement is SHOWN — not merely when it carries a
total. Gating it on the total is the subtle version of the bug D17 exists to
prevent: the `$0` line and `assumed_by_unicoach` would ship to the coach with
nothing on the page saying whose zero it is. The words follow the line:

> "Living at home, we count no food-and-housing cost: eating at home is not
> free, but it is not a new cost that enrolling creates, so the at-home total
> counts it as zero. That is our assumption, not a figure any school published."

On the wire the same fact rides as `assumed_by_unicoach` inside the arrangement
(§5), so a model reading the payload can never attribute the zero to the school.
On the report page the `$0` cell prints with a `report-note` beside it, through
the existing `figureOf(amount, blank, note)` (`CostReportPage.kt:751`) — a note
belongs to a figure that exists, which is exactly the case here.

Every other arrangement keeps both committed rules: no partial total
(`CostBreakdown.kt:278-280`), and no silent zero.

### 8. Net price and the blended figures

`netPriceOf` (`CollegeCostService.kt:1410`) and `reportsBandPricing` (`:1478`)
stop reading `colleges` columns and read `cohort_money_stats`.
`IncomeBand.netPriceFor(college)` (`db/…/IncomeBand.kt:67`) is left alone: its
one remaining caller is the search-index row builder (`CollegeMatchRow.kt:83`),
which is `shape/05`'s column and not this slice's. The addresses this surface
serves:

| figure                       | measure                     | population                              | residency scope                         | income band       |
| ---------------------------- | --------------------------- | --------------------------------------- | --------------------------------------- | ----------------- |
| overall net price            | `avg_net_price`             | `title_iv_aided_undergraduates`         | `in_state_rate_paying` (public) / `all` | `NULL`            |
| band net price               | `avg_net_price`             | same                                    | same                                    | the answered band |
| published price (`COSTT4_A`) | `published_cost_blend`      | same                                    | same                                    | `NULL`            |
| median debt                  | `median_debt_at_completion` | `federal_loan_borrowing_completers`     | `all`                                   | `NULL`            |
| median earnings              | `median_earnings_10y`       | `employed_not_enrolled_10y_after_entry` | `all`                                   | `NULL`            |

**The selector is the FULL address, never the measure alone.** Every cohort read
names measure + population + aid scope + income band, and carries the residency
scope; a lookup keyed on `(measure, income_band)` with "take the newest vintage"
is a defect, not a shortcut. RFC 162 landed while this slice was open and files
a SECOND `avg_net_price` series — the IPEDS SFA first-time full-time grant-aided
cohort (`CanonicalMoneyLoader.kt:1149-1153`) — at a NEWER vintage than the
Scorecard's 2021-22 row. Under a measure-only key that series would be served as
"the overall average net price" in the coach payload, the parent report and the
FitLens digest: wrong cohort, wrong number, and no label saying so. The full
address makes the right row the only row that answers.

**The grid is enforced across modules.** `:college` exports the write side of
the address grid — `IpedsChargeVocabulary.PRICE_CELLS`
(`IpedsChargeVocabulary.kt:104`) and `CanonicalMoneyLoader.COHORT_ADDRESSES`
(`CanonicalMoneyLoader.kt:1201-1210`), both DERIVED from the fills rather than
re-typed beside them — and `:service` reads the same grid from the other end
through `CostField.figureAddress`. Neither side can be derived from the other:
one is keyed by a publisher's variable stem, the other by this repo's wire
field. So the agreement is a test, `CanonicalAddressContractTest` (4 `@Test`),
which asserts the price grid equal in BOTH directions, that every cohort address
this surface serves is one a fill writes, and that the served net price is the
Scorecard cohort and never the SFA grant-aided row at the same measure. A read
address no fill writes is a figure we hold and tell a family we do not; a fill
that moves serves a different cohort's number under the same label. The test
fails the moment either side is edited alone.

**The `'undated'` sentinel has ONE home**,
`ed.unicoach.db.models.VINTAGE_UNDATED` (`db/…/CanonicalMoneyRows.kt:29`),
referenced by the loader, the reader and every fixture. A drifted copy would
hand the sentinel back AS an academic year, so a DB-backed test writes at the
constant, reads the column back byte-for-byte and asserts a second sentinel is
refused by `0083`'s CHECK (`CanonicalMoneyDaoTest.kt:345`).

`NetPrice.BandSpecific` vs `NetPrice.OverallAverage`
(`CollegeCostService.kt:88-110`) is unchanged, and `CostField.NET_PRICE` keeps
its "no column of its own" shape (`CostField.kt:116`, `:339`): the band selects
the ROW now instead of the column. `value` is `NUMERIC` and may be negative by
design (`0083:256,263-284` has no nonneg CHECK on it), so the projection rounds
to whole dollars and never clamps.

`IncomeBand.bracket` (`IncomeBand.kt:24-36`) **stays in Kotlin, and this slice
adds no guard for it**, because RFC 165 already settled the question and settled
it the other way.

RFC 158 recorded that the bracket text "is deleted at `shape/04`"
(`0083:168-173`). RFC 165 then landed `GET /api/v1/vocabularies`, whose
`income_bands` vocabulary is built from the enum — `VocabularyService.kt:186`,
`IncomeBand.entries.map { VocabularyEntry(value = it.value, label = it.bracket) }`
— with the reason written at `VocabularyService.kt:176-181`: "No DB read:
`IncomeBand.bracket` IS the dollar range in words, and the codebook loader
already asserts byte-equality between it and `income_bands.bracket_label`, so
the enum and the table cannot drift."

That claim is true. `MoneyVocabularyLoader.kt:351-354` fails the load, fatally,
when `enum.bracket != row.bracketLabel`, and two tests pin it
(`MoneyVocabularyLoaderTest.kt:71` over all five bands, `:141` on the fatal
path). So drift is already impossible, at ingest, before any row is served — a
new DB drift test in this slice would be a third copy of a check that is already
fatal.

The obligation is therefore **superseded, not deferred**. Deleting
`IncomeBand.bracket` would now break a landed public endpoint or force it into
the per-request DB read it explicitly declined. `shape/08` should not do it
either while `/api/v1/vocabularies` reads the enum; the table column stays as
the store's own copy, held equal by the loader.

### 9. FitLens: the digest number only

`FitLensService`'s cost digest prints one money figure, the school's own average
net price. Before this change it was read off `CollegeMatch.netPricePerYearUsd`
— the search index copy (`college_search_index.net_price_per_year_usd`,
`db/schema/0064…sql:138`) — by an explicit decision to go round
`CollegeCostService`, and so round its withholding. That is the read this slice
replaces.

This slice moves **the digest number** to the canonical store, through the same
one door the cost path uses: `CanonicalCostReader.readCohortStats`, injected at
`FitLensService.kt:98` and called in three lines at `:760-765` over the matched
college ids after `collegeSearchService.search` returns. FitLens imports no DAO
and opens no connection of its own. Three things improve, all from data already
on the row:

- a suppressed or absent figure is written in the words of §6 rather than the
  local `NOT_REPORTED = "not reported"` constant (`FitLensService.kt:138`);
- the key stops hand-asserting "in-state" at a private school — the basis comes
  from `residency_scope`, so a `scope = all` row is not labelled in-state;
- the digest states its `vintage`, which today it does not state at all.

The read **degrades; it never fails the pass — and the degrade is a STATE, not a
sentence.** The digest number is advisory — one line of context for a lens that
is not about money — so a transient canonical read failure must not kill the
whole fit-lens run. `DigestNetPrices` is sealed with two members, `Read` and
`Unavailable` (`FitLensService.kt:1005-1011`), because they are two different
things to say: "this college has no figure" is a claim about our data, "the read
failed" is a claim about this run. Where the read was unavailable the digest
OMITS the net-price keys entirely (`:783-784`). "We have not collected this
figure yet." is reserved for a college the read reached and holds no row for
(`:810`). `CancellationException` is rethrown FIRST (`:733`), as every sibling
in this package does, so a cancelled pass unwinds at this read instead of
continuing into a second, billed LLM call.

The **search-index filter and ranking column** — `net_price_per_year_usd` in
`IndexFilter("net_price_per_year_usd <= ?", …)` (`CollegesDao.kt:685-687`), the
sort at `:1906`, the percentile pass at `:2408-2427` — **stays exactly as it
is**. It is `shape/05`'s column, and moving it here would drag the index rebuild
(`CollegesDao.kt:2309,2344`) into this slice.

RFC 157 withholding still cannot be applied in FitLens: nothing in its read
phase (`FitLensService.kt:193-270`) reads `money_profiles`, so it does not know
the family's residency. Canonical makes that cheap — the axis is a column — but
it does not do it, and this slice does not add it. That remains D-G's, in
`shape/05`.

### 10. The prompt: v19

`COACHING_SYSTEM_PROMPT_VERSION` is config plus a seed row, not a Kotlin
constant: `service/src/main/resources/service.conf:97-98` pins the version,
seeded by a migration. v19 ships as
`db/schema/0086.seed-coach-system-prompt-v19.sql` with rollback
`COACHING_SYSTEM_PROMPT_VERSION=v18` — the v18 row is immutable and stays
selectable — the RFC 159/160 precedent (`service.conf:82-83`).

Three blocks change, and two of them are **edits, not appends** — the first
break from the "byte-identical plus one appended paragraph" convention:

1. `0082…v18.sql:73-88` — the residency block names two published prices. It
   gains the third tier, and the rule that a district is never asked about.
2. `0082…v18.sql:131-158` — "Say the same four lines in the same order every
   time" is true of the at-home arrangement for the first time; the block gains
   the instruction to state our zero assumption in words and never to attribute
   it to the school. `0082…v18.sql:240-266`'s "Living at home is never something
   you assume quietly" is unchanged and still governs.
3. One appended paragraph: the six statuses, and which of them are ours.

The existing bans are re-stated verbatim, not weakened:
`SystemPromptCatalogTest.kt:631` asserts the served prompt forbids the net-price
arithmetic and the cross-vintage sum, with its own anti-vacuity check, and
`:93-97` of the seed body ("Never name a data source's internal buckets, codes
or field names") still forbids saying `CHG1AY` or `price_figures` to a family.

### 11. What does not change

- **`colleges` keeps its row shape.** No column is dropped or renamed.
  `log_college_version()`'s live body is 0062's (`db/schema/0062…sql:74-110`),
  names 18 cost/aid columns, and is stored as TEXT — a drop passes the migration
  and then kills the next ingest write. `shape/08` drops them, after a
  reader-count audit.
- **The search index, `similar_colleges`, `admin-web`'s `CollegesResource` and
  every ingest writer** stay on publisher columns.
- **The share link.** `cost_report_shares` holds no money
  (`db/schema/0073…sql:15-23`) and the report reads live at request time
  (`ServiceCostReportSource.kt:71`, `CostReportPage.kt:83` "This report is
  live"). Every already-shared parent link picks this change up with no backfill
  and no re-mint — and no safety margin.
- **The list gate.** `college_cost_profile` answers only for colleges on the
  student's active list (`StudentCollegeSelection.kt:67-86`), so the
  first-session path stays `find_college` → `update_college_list` →
  `college_cost_profile`. Removing the gate would be a new product decision.

## Files Modified

**New — database read side**

- `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyReadDao.kt` — the two
  batched per-college reads; a new file because `CanonicalMoneyDao.kt` is a hot
  rebase surface for rfc-162.
- `db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyReadRows.kt` — the
  `PriceFigure` / `CohortMoneyStat` read models, which do not exist today.

**New — the projection**

- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/CanonicalCostReader.kt`
  — the interface that is the one door onto the store, and
  `DbCanonicalCostReader`, which reads and folds canonical rows into per-college
  figures and warns over the rows it could not decode.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/CollegeFigures.kt`
  — the resolved figure set that replaces `College` as the cost path's input,
  the `FigureAddress` / `PriceAddress` / `CohortAddress` vocabulary, and
  `CostField.figureGroup` derived from the address.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/ServedFigures.kt`
  — the figures bound to the one academic year this answer serves them at, and
  `servesNoPublishedPrice`.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/FigureStatusCopy.kt`
  — the six statuses spoken, and the OURS/THEIRS routing into `ArrangementGap`
  vs `NoTotalReason`.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/ResidencyTiers.kt`
  — `ResidencyTierBasis`, which tiers a college publishes, and the
  in-district-not-separated sentence.

**New — the money domain**

- `service/src/main/kotlin/ed/unicoach/coaching/costs/FigureGroup.kt` — the
  wire-key grouping that outlives `ScorecardVintage`, in the domain beside the
  total that refuses to sum across it.

**New — migration**

- `db/schema/0086.seed-coach-system-prompt-v19.sql` — the v19 coach body.
  (Number claimed live at commit time; it must be greater than rfc-162's
  `0085`.)

**Deleted**

- `service/src/main/kotlin/ed/unicoach/coaching/costs/ScorecardVintage.kt` — the
  closed year enum; years are row attributes now.

**Edited — `db`**

- `db/src/main/kotlin/ed/unicoach/db/models/FigureReading.kt` — the
  `(amount, status) → FigureReading` decode, exhaustive, refusing the pairings
  the CHECK forbids.
- `db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyRows.kt` —
  `VINTAGE_UNDATED`, the one home of the `'undated'` sentinel.
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt` —
  `CorruptPersistedValueException.location` becomes a property, so a caller that
  carries the failure out as data can name the cell without parsing the message.

**Edited — `college`**

- `college/src/main/kotlin/ed/unicoach/college/IpedsChargeVocabulary.kt` —
  `PRICE_CELLS`, the write side of the price grid, derived from `CELLS` and
  exported for the contract test; the object stops being `internal`.
- `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt` —
  `COHORT_ADDRESSES` and the named cohort coordinates, derived from the fills;
  the `'undated'` literal becomes `VINTAGE_UNDATED`.
- `college/src/main/kotlin/ed/unicoach/college/CollegeSfaLoader.kt` — the three
  Pell staging variables become named constants (`PELL_RECIPIENT_COUNT`,
  `PELL_SHARE`, `PELL_AVERAGE_AWARD`), so the fill and the staging vocabulary
  cannot drift apart on spelling.

**Edited — `service`, the cost package**

- `costs/CostField.kt` — five new members (in-district tuition, three fees-only,
  with-family housing and food); every member declares its canonical
  `figureAddress` and no longer states a group beside it; `TUITION_FIELDS` gains
  in-district.
- `costs/ResidencyAxis.kt` — `IN_DISTRICT`, explicitly not `IN_STATE_ONLY`.
- `costs/CostBreakdown.kt` — `reportedAmountOf(college)` deleted and replaced by
  a lookup on `ServedFigures`; `servedFiguresOf` chooses the year once; the
  with-family component list gains housing and food; `CostLine` gains
  `LineOrigin` (no default) and a non-null `academicYear`;
  `CostField.isAssumedByUnicoach` derives attribution from the address; the
  vintage guard becomes `(group, academicYear)`.
- `costs/CollegeCostService.kt` — `readInSession` reads canonical through an
  injected `CanonicalCostReader`; `netPriceOf` and the blended figures read
  `cohort_money_stats` at the full address; `publishedTuitionTiers` is the one
  tier derivation; `figureStatusesOf` / `notReportedOf` / `statusNoteFor` are
  published for the report fixture; `ingestYearOf` keeps its freshness meaning
  and stops being read as a vintage.
- `costs/CollegeCostChatTool.kt` — the new keys of §5, the basis-driven
  residency offer, `colleges` on each dated-figures entry, and the DESCRIPTION
  rewrite (the with-family paragraph and the wire-key list).
- `costs/ComparisonBasis.kt` — `DatedFigures` carries the schools it is true of
  and is grouped by year; `AT_HOME_ASSUMPTION_STATEMENT` and the seventh fact
  (the at-home assumption) added to `statements`.
- `costs/SingleSchoolBasis.kt` — the same seventh fact and the data-driven
  `yearStatement`.

**Edited — other modules**

- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` — the
  digest number through the injected `CanonicalCostReader`; basis and vintage
  from the row; `DigestNetPrices` and the omit-on-unavailable degrade.
- `service/src/main/resources/service.conf` — `systemPromptVersion = "v19"` and
  the rollback comment.
- `service/src/main/kotlin/ed/unicoach/coaching/AcademicYear.kt` — one KDoc
  reference retargeted from the deleted `ScorecardVintage` to `FigureGroup`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` — the
  assumed `$0` line prints with its note; `blankFor` routes every blank through
  the store's status; the tuition-tier sentence is printed. The page authors no
  money copy of its own — every sentence is the domain's.

**Edited — fixtures**

- `service/src/test/kotlin/ed/unicoach/coaching/costs/CostsTestDb.kt` —
  `seedCollege` keeps its parameter list and writes canonical rows instead of
  `colleges` money columns, through field-addressed `seedPriceFigure` /
  `seedCohortStat` helpers; `reset()` gains `MoneyVocabularyFixture.seed` and
  truncates the two fact tables.
- `public-web/src/test/kotlin/ed/unicoach/web/FakeCostReportSource.kt` — builds
  a `CollegeFigures` from the amounts it already takes as parameters, instead of
  a `College` row, and imports all six production derivations rather than
  re-deciding any of them.
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt` —
  its seeder gains an `avg_net_price` cohort row.

**New — tests**

- `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/CollegeFiguresTest.kt`
  — the DB-free projection suite.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/CanonicalAddressContractTest.kt`
  — the cross-module address grid.
- `db/src/test/kotlin/ed/unicoach/db/dao/CanonicalMoneyReadDaoTest.kt` — the
  read-side DAO suite. (`db/…/FigureReadingTest.kt` already exists and is
  extended with the decode cases.)
- `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/DbCanonicalCostReaderTest.kt`
  (2 `@Test`) — the production reader's warn-and-drop behaviour over undecodable
  rows.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/ResidencyTiersTest.kt`
  (7 `@Test`) — the tier derivation and its six bases.

**Edited — tests**: see below.

## Implementation Plan

Ordered so each step is green before the next. The write preconditions are
explicit: the five vocabulary tables are FKs of every fact row
(`CanonicalMoneyDao.kt:354-362`), and a suite that skips them fails on a foreign
key that has nothing to do with what it asserts — `CanonicalMoneyDaoTest.kt:265`
already proves that.

1. **`FigureReading.of` decode** plus its unit tests in `FigureReadingTest.kt`.
   Pure Kotlin, no DB, no other file depends on it yet.
2. **`CanonicalMoneyReadDao` + read models**, with `CanonicalMoneyReadDaoTest`.
   The test's `setUp` calls `MoneyVocabularyFixture.seed(sqlSession)` FIRST,
   then inserts figures through the existing `CanonicalMoneyDao` write path, so
   the read is tested against rows the real writer produced.
3. **The projection package, DB-free.** `CollegeFigures`, `ServedFigures`,
   `FigureGroup`, `FigureStatusCopy`, `ResidencyTiers`, and the
   latest-complete-year selection. Unit tests drive it with hand-built
   `PriceFigure` lists — four years, a suppressed cell, an incomplete latest
   year — with no database at all.
4. **`ForbiddenCostArithmeticTest` scope**, before any new money code lands in
   the sub-package: make the sweep recursive, add the directory, add the
   read-guard assertion, extend `MONEY_TOKENS`, extend the positive control.
   Doing this first means the new files are scanned from their first commit.
5. **`CostField` / `ResidencyAxis` / `CostLine` / `ArrangementCost`** — the
   vocabulary changes and the with-family component list. This is the step that
   breaks compilation across the package; it ends with `ScorecardVintage.kt`
   deleted.
6. **`CollegeCostService.readInSession`** cuts over to `CanonicalCostReader`,
   `netPriceOf` to `cohort_money_stats`. `CollegeCostServiceTest` is red here
   until step 7.
7. **`CostsTestDb.seedCollege`** writes canonical rows: vocabulary seed in
   `reset()` first, then `price_figures` for the seven price cells and
   `cohort_money_stats` for the net-price rows, at a named academic year
   constant. The 96 service and 72 tool expectations do not move; the source
   does. Both suites go green together.
8. **`CollegeCostChatTool`** — the new keys, the widened offer copy, the
   description. Then `CollegeCostChatToolTest`.
9. **`FakeCostReportSource`** rebuilt on `CollegeFigures`, importing the
   production derivations rather than re-deciding them; the routing and
   share-link test bodies are untouched. Then `CostReportPage.kt`: the note for
   the assumed line, the status-routed blanks, and the tuition-tier sentence.
10. **FitLens digest** and its fixture row.
11. **Prompt v19**: the migration, the `service.conf` bump,
    `CoachingConfigTest`, and the `SystemPromptCatalogTest` pair (v19 served,
    v18 still selectable as the rollback).
12. **Full `nix develop -c bin/test`**, then `bin/shell-tests`. Report the real
    executed counts.

## Tests

### Moved, not weakened

- **`service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`
  (96 → 114 `@Test`)** — every one runs through `CostsTestDb.seedCollege`, so
  the original 96 change fixture source and none changes expectation. Four named
  `ScorecardVintage` and now read the year off the rows:
  `an arrangement can never mix Scorecard vintages` (`:1497`),
  `the mixed-vintage refusal carries the offending lines, not just a set of vintages`
  (`:1721`),
  `no arrangement total ever equals the sticker cost by construction`, and
  `the academic years name only the vintages the call actually carries`
  (`:2198`). The first two keep asserting the guard by constructing an
  `ArrangementCost` directly, since the read path can no longer reach it.
  `with_family carries no housing and food line at all, never a zero` is the one
  test this RFC **reverses**; it is rewritten as
  `with_family carries a labelled zero housing and food line, and the zero is ours`
  (`:989`), with the reversal recorded in its own comment (`:972`).
- **`CollegeCostChatToolTest.kt` (72 → 83 `@Test`)** — same fixture, same
  consequence. The vintage-label tests (`:1269`, `:1331`, `:1358`) and the
  emit-order test (`:2322`) keep their assertions and read the year off the
  fixture's rows rather than off the enum.
- **`ComparisonBasisTest.kt` (11 → 15 `@Test`)** — type-level, no DB. Unchanged
  except where the seventh basis fact changes a `statements` length assertion;
  the two added cases pin the dated-figures subject (a 2022-23 school beside a
  2023-24 school, and one year shared by both).
- **`public-web` (36 → 43 `@Test`: `CostReportPageTest` 25 → 32,
  `CostReportRoutingTest` 9, `ReportLinkContractTest` 2)** — the routing and
  share-link bodies are byte-unchanged, and only `FakeCostReportSource.kt` (a
  fixture with zero `@Test`) is rewritten under them. That is the no-regression
  evidence: the parent-facing surface asserts the same HTML from an entirely
  different source. `CostReportPageTest` is where the parent-facing new truths
  land: the at-home line D17 reverses (`:249`), and the blanks that must not
  blame the school.
- **The share family** and `CostReportSharesDaoTest` — read no money column;
  untouched.

### New truths, each pinned

1. **The in-district tier is labelled.** A community-college fixture with
   value-bearing `in_district` 2,550, `in_state` 8,580 and `out_of_state` 10,590
   (RFC 161's Austin CC shape, `rfc/161…:16-24`) emits three named tuition keys;
   the applicable tuition for a CA family is the in-state one; the in-district
   figure never enters an arrangement total.
2. **The mislabel is never re-labelled.** A Scorecard-only college (no
   `in_district` row, `in_state` row `source = scorecard`) emits **no**
   in-district key, and `residency_tiers.basis` is
   `publisher_does_not_separate_in_district` with its statement. A test asserts
   the string "in-district" never appears attached to that figure.
3. **The tier code, the emitted keys and the offer are one decision.** A school
   with a value-bearing in-district figure and exactly one state tier asserts
   all three together: basis `in_district_and_one_other_tier`, no out-of-state
   key, and an invitation that never promises three prices.
4. **A with-family total is complete, and the zero is ours.** The at-home
   arrangement carries three lines including
   `housing_and_food_with_family_per_year_usd = 0`, `total_per_year_usd` is
   present and equals the sum, `assumed_by_unicoach` names exactly that one key,
   and the basis statements contain the at-home sentence. A second test asserts
   the payload nowhere says the school reported or failed to report it.
5. **The zero is unrepresentable anywhere else.** Constructing a `CostLine` with
   `ASSUMED_BY_UNICOACH` for any other field, or with a non-zero amount, is
   refused at construction (`CostBreakdown.kt:218-237`). `CollegeFiguresTest`
   loops `CostField.entries` × every `LivingArrangement`, so a second assumed
   field is covered with no edit to the test.
6. **Fees are split, and never summed twice.** A college with `fees_only` rows
   emits the three fees keys beside the combined tuition-and-fees figures, and
   the arrangement total equals tuition-and-fees plus components — the fees
   figure is not in it.
7. **The six statuses are spoken, from both tables.** One fixture carries one
   figure per status. The result asserts, per field: the status code, the exact
   sentence, and that `not_collected_by_us` produces a `NoTotalReason`-shaped
   blank (ours) while `not_reported_by_institution` produces the school's. An
   `imputed_by_publisher` `$0` is shown with the publisher's-estimate sentence,
   not hidden. An end-to-end case seeds a SUPPRESSED Scorecard net price and
   asserts it speaks the publisher's sentence and never enters
   `data_availability`.
8. **The report page never blames the school for someone else's gap.**
   `CostReportPageTest` pins the suppressed figure, our own gap, and the year
   gap printing the store's sentence, and a real institutional silence still
   reading as one.
9. **No total ever mixes years, and the year names its schools.** A fixture
   whose 2023-24 year is missing one component and whose 2022-23 year is
   complete produces a total built entirely from 2022-23, and
   `published_price_academic_year` says `2022-23`. A second fixture with no
   complete year produces no total, the parts at their own years, and a
   `NoTotalReason`. A third asserts every line in a served `ArrangementCost`
   shares one `academic_year`. In a two-school comparison each dated entry names
   only its own school and dates only that school's figures.
10. **The served year is a type.** `ServedFigures` refuses a year the college
    does not publish, and a null year is legal only for a college that publishes
    no price row at all (`CollegeFiguresTest`).
11. **The full address wins over the measure.** A college carrying BOTH the
    Scorecard `avg_net_price` row and RFC 162's SFA grant-aided row at a newer
    vintage serves the Scorecard number and the Scorecard year — asserted in the
    projection, end to end through `CostsTestDb.seedCohortStat`, and across
    modules in `CanonicalAddressContractTest`.
12. **One bad row costs its own row.** A stored code no enum in this build reads
    leaves the batch and every other college still answers; the affected field
    falls to `not_collected_by_us`.
13. **The withholding chain is unchanged by the new axis.**
    `CostField.IN_STATE_ONLY_FIELDS` contains exactly the two members it
    contains today, with `IN_DISTRICT` present in the vocabulary — the
    derived-not-listed rule (`CostField.kt:212-213`). The RFC 157 evidence case
    (a WA family at UC San Diego gets the out-of-state totals and NEITHER
    blended figure) keeps passing verbatim.
14. **FitLens obeys the store, and degrades as a state.** A suppressed
    `avg_net_price` renders the spoken status, not a number and not the bare
    local `"not reported"`. A private college's row (`residency_scope = all`) is
    not labelled in-state. The digest states its vintage. A throwing reader
    OMITS the net-price keys rather than speaking a data claim, its no-row twin
    still speaks the sentence, and a cancelled pass unwinds before the second
    billed call. The **filter** column is asserted unchanged: a
    `maxNetPricePerYearUsd` query still compiles to
    `net_price_per_year_usd <= ?` over the index.
15. **No new `IncomeBand.bracket` test.** The drift it would guard is already
    fatal at load (`MoneyVocabularyLoader.kt:351-354`) and already pinned
    (`MoneyVocabularyLoaderTest.kt:71,141`). See Detailed Design §8.

### The guard tests

- **`ForbiddenCostArithmeticTest.kt` (3 → 4 `@Test`).** The sweep is recursive
  (`walkTopDown`, `:55-68`) so the new sub-package cannot be silently unscanned;
  the read-guard set names the new files; `MONEY_TOKENS` gains the projection's
  own money identifiers; the positive control gains a line in the new
  vocabulary. The fourth test asserts the scan reaches a file in the
  **sub**-package — the anti-vacuity guard for the recursion itself (`:150`).
- **`CanonicalAddressContractTest.kt` (4 `@Test`, new).** The price grid equal
  in BOTH directions, every served cohort address written by some fill, and the
  net price pinned to the Scorecard cohort rather than the SFA grant-aided row
  at the same measure. Mutation-verified both ways.
- **`SystemPromptCatalogTest.kt` (38 → 40 `@Test`).** A v19 test stating what
  CHANGED, since v19 is not v18 plus an append, and a v18-rollback-selectable
  test. The v19 test walks `FigureStatus.entries` and asserts the served prompt
  carries `FigureStatusCopy.statementOf(status)` verbatim, so the prompt and the
  payload cannot speak two different sentences about one blank.
  `every system prompt service dot conf pins exists in the migration-seeded catalog`
  and
  `the served coach prompt forbids the net-price arithmetic and the cross-vintage sum`
  both still pass against v19.
- **`CoachingConfigTest.kt:17`** — `assertEquals("v19", …)`.
- **`chat/src/testFixtures/kotlin/ed/unicoach/chat/BareSourceCodeGuard.kt`** —
  the cost allowlist derives from `CostField.entries`, so it follows the five
  new wire keys automatically. None of them contains a `q1`..`q5` token, so
  `QUINTILE_CODE` (`:34`) is not tripped. Asserted, not assumed.

### DAO tests

- **`CanonicalMoneyReadDaoTest` (13 `@Test`, new).** Batched read returns every
  row for the ids asked for and nothing else; an empty id list issues no query;
  each of the six statuses round-trips through the real writer on both tables; a
  hand-written row violating value-IFF-status is refused by the CHECK, and the
  Kotlin decode refuses the same pairing when handed it directly; a stored slug
  no enum reads costs its own row while every other college still answers.
- **`FigureReadingTest.kt` (1 → 4 `@Test`)** — the decode itself, per status and
  per refused pairing.
- **`CanonicalMoneyDaoTest.kt` (14 → 15 `@Test`)** — write-side, plus the
  `VINTAGE_UNDATED` sentinel written, read back byte-for-byte, and a second
  sentinel refused by the CHECK (`:345`).
- **`CollegeFiguresTest.kt` (43 `@Test`, new)** — the DB-free projection: year
  selection, the address lookups, the status walk, the derived group and the
  derived attribution.

Full suite at HEAD: **2865 tests, 0 failures, 0 errors, 0 skipped**.

### First-session test

A new student with no profile asks what Foothill College costs. The scenario
includes the `update_college_list` step, because `college_cost_profile` answers
only for colleges on the active list (`StudentCollegeSelection.kt:67-86`) and
this slice does not change that gate. The assertion: the result names three
tiers, the residency offer is present with its three-tier copy, and no answer is
withheld pending an answer — the offer is offered, never forced
(`CollegeCostService.kt:765-830`, brief 0001 D11).

## Risks and traps

- **RFC 162 widened the store under this slice.** It landed as migration `0085`,
  so this slice is `0086`. It appended to `MoneyMeasure`, `CohortPopulation` and
  `CohortAidScope`, and added `UNKNOWN` to both `ResidencyBasis` and
  `FigureArrangement` — so **an exhaustive `when (measure)` is a hazard, not a
  safety net**: the enum grows with every fill. The projection therefore routes
  on a _closed set it names_ (the five addresses of §8) and treats every other
  measure as "not a figure this surface serves". Its most consequential effect
  is the SECOND `avg_net_price` series at a newer vintage, which is why every
  cohort read names the FULL address and why `CanonicalAddressContractTest`
  exists. The read API is a new file because RFC 162 rewrote the tail of
  `CanonicalMoneyDao.kt`.
- **`log_college_version()`.** The live body is 0062's
  (`db/schema/0062…sql:74-110`), names 18 `colleges` columns and is stored as
  TEXT. Dropping or renaming a price column passes the migration and kills the
  **next ingest write**, not a query. This slice drops nothing; the rule is
  simply that it must not start.
- **`published_price` has zero rows.** No source fills the concept
  (`0083:122-124`), so every total is composed from components and the all-in
  figure stays the cohort blend. Anyone implementing "read the published price
  from `price_figures`" gets `null` for every college in the corpus and will
  read it as a coverage bug.
- **In-district is value-bearing for a minority.** 3,825 of ~6,100 institutions
  have IC_AY rows at all, and RFC 161 measured **269** where the in-district
  figure actually differs from in-state. Most colleges will show two tiers and
  the third will be silent. A test written against a private or public 4-year
  fixture will not exercise the feature; the community-college fixture is the
  one that does.
- **The vocabulary is a write precondition.** Every fixture that writes a
  canonical row must seed the five vocabulary tables first
  (`MoneyVocabularyFixture.kt:12-20`), or it fails on a foreign key unrelated to
  its assertion.
- **The report is live.** Any behaviour change here reaches already-shared
  parent links immediately (`ServiceCostReportSource.kt:80`) — including
  flipping a printed total to `No total — a part of this price is missing`.
  There is no snapshot to stage behind.
- **Two fixtures name `College` in their signatures.** `FakeCostReportSource.kt`
  calls `tuitionLineOf(college, control)`, `reportedOf(college, netPrice)` and
  `CostBreakdown.of(college, …)`. Those helpers stop taking a `College`, so the
  fixture changes even though not one of the 36 `public-web` test bodies does.
- **No populated database exists to measure against.** No local `unicoach%`
  database has a non-zero `price_figures` count, and `IC2023_AY.csv` is not on
  disk (only the codebook zip). Coverage claims in this RFC are RFC 161's
  measured figures and code-derived counts; the runtime evidence is
  `college_index_build.price_figure_rows` / `canonical_money_summary`
  (`0083:367-386`) after an ingest run.
