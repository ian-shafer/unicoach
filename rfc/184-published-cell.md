# RFC 184: `PublishedCell` — decode the publisher pair once, at the read boundary

## Summary

RFC 179 made an assurance tier a function of the `(source, source_variable)`
pair. The pair travels as TWO LOOSE FIELDS on every carrier above the DAO, and
the legal variable names are a function of the source with nothing in the type
system saying so. Today this compiles, and it is nonsense:

    FigureProvenance(status, MoneySource.SCORECARD, "H.209")   // a CDS field id under the Scorecard

Nothing catches it. It dies at read time, inside one family's cost report, in
`AssuranceTier.of`'s fatal.

This RFC closes the pair into one type, `PublishedCell`, built ONCE at the DAO
read boundary and carried whole by every layer above it. Three review lenses on
RFC 179 asked for this type and were declined there because it crosses four
modules; this is that follow-up, and it is the whole job.

It is a REFACTOR. No behaviour a family can see may change: no sentence moves,
no wire key changes, no tier assignment differs.

## Detailed Design

### The type

A closed union in `:db` models, beside `AssuranceTier`
(`db/src/main/kotlin/ed/unicoach/db/models/PublishedCell.kt`):

```kotlin
sealed class PublishedCell(variable: String) {
  abstract val source: MoneySource
  abstract val variable: String
  abstract val assurance: AssuranceTier

  // ONE rule for all three arms: no `source_variable` any fact table admits is
  // blank (every one of them CHECKs it), so a blank one is a construction-site
  // mistake. A sealed CLASS rather than an interface buys exactly this: the
  // shared requirement is stated once, in a base constructor, instead of being
  // copy-pasted into three `init` blocks.
  init { require(variable.isNotBlank()) }

  /** IPEDS SFA and IC_AY: one compelled, edit-checked survey whichever variable it is. */
  data class Surveyed(val survey: Survey, override val variable: String) : PublishedCell(variable) {
    override val source: MoneySource get() = survey.source
    override val assurance: AssuranceTier get() = AssuranceTier.MANDATORY_SURVEY
  }

  /** The Common Data Set: the school's own filing, whichever field id it carries. */
  data class SelfPublished(override val variable: String) : PublishedCell(variable) {
    override val source: MoneySource get() = MoneySource.COMMON_DATA_SET
    override val assurance: AssuranceTier get() = AssuranceTier.VOLUNTARY_SELF_REPORT
  }

  /** The one relay whose tier IS keyed on the variable. */
  @ConsistentCopyVisibility
  data class ScorecardCell private constructor(
    override val variable: String,
    override val assurance: AssuranceTier,
  ) : PublishedCell(variable) {
    override val source: MoneySource get() = MoneySource.SCORECARD

    companion object {
      fun of(variable: String, location: String): ScorecardCell = ...   // SCORECARD_TIERS or the located fatal
    }
  }

  /** The two IPEDS surveys, as a type — so `Surveyed` cannot name a third publisher. */
  enum class Survey(val source: MoneySource) {
    SFA(MoneySource.IPEDS_SFA),
    IC_AY(MoneySource.IPEDS_IC_AY),
  }

  companion object {
    /** The ONE decoder: a stored pair in, a cell out. A `when` over `MoneySource` with no `else`. */
    fun of(source: MoneySource, sourceVariable: String, location: String): PublishedCell
  }
}
```

Two of the three arms have PUBLIC constructors and need no factory, because
their shape already makes them right: `Surveyed` cannot be handed
`MoneySource.SCORECARD` (there is no such `Survey` member), and `SelfPublished`
takes no source at all. A public constructor that cannot be wrong is a better
guard than a private one plus a factory, because it never has to be enforced.

`ScorecardCell` is the exception and keeps a private constructor: its tier is a
function of the variable, so a public constructor could pair
`("TUITIONFEE_IN", ADMINISTRATIVE_RECORD)` — the very defect this RFC is about,
moved one type inward. `@ConsistentCopyVisibility` closes `copy()` with it.

### `AssuranceTier.of` keeps its signature and becomes one line

The decode `when` moves to `PublishedCell.of`, and the existing resolver
delegates:

```kotlin
fun of(source: MoneySource, sourceVariable: String, location: String): AssuranceTier =
  PublishedCell.of(source, sourceVariable, location).assurance
```

There is then exactly ONE `when` over the pair in the tree. `SCORECARD_TIERS`
stays where it is — it is a table of tiers, `ScorecardCell.of` reads it, and the
closure test in `:college` and the unit test in `:db` keep pointing at it
UNCHANGED. That is deliberate: acceptance criterion 3 asks the derived-set
closure tests to still assert what they assert, and the cheapest proof of that
is that their source does not move at all.

### The carriers hold one field where they held two

| Carrier                                          | Before                     | After                 |
| ------------------------------------------------ | -------------------------- | --------------------- |
| `PriceFigure` (read row)                         | `source`, `sourceVariable` | `cell: PublishedCell` |
| `CohortMoneyStat` (read row)                     | `source`, `sourceVariable` | `cell: PublishedCell` |
| `DatedFigure` (`CollegeFigures.kt:334`)          | `source`, `sourceVariable` | `cell: PublishedCell` |
| `DatedStat` (`CollegeFigures.kt:364`)            | `source`, `sourceVariable` | `cell: PublishedCell` |
| `FigureProvenance` (`CollegeFigures.kt:396`)     | `source`, `sourceVariable` | `cell: PublishedCell` |
| `FigureStatusNote` (`CollegeCostService.kt:716`) | `source`, `assurance`      | `cell: PublishedCell` |

All six implement ONE interface, `CellCarrier`, declared beside the union: it
holds `val cell: PublishedCell` and the single default
`val source: MoneySource get() = cell.source`. Six hand-written copies of that
delegation is six places for it to drift, and the interface also gives the shape
a NAME — "a thing that came off one published cell". `assurance` is deliberately
NOT on it: only `FigureStatusNote` republishes the tier, and one site is not
duplication, so putting it on the interface would invent surface five carriers
do not use. Every existing read site — the wire builders in
`CollegeCostChatTool`, the report page, the status copy — therefore compiles and
behaves IDENTICALLY. The diff is at the CONSTRUCTION sites, which is where the
defect was.

`CdsAidFact.Stat` (`AidPolicyDao.kt`) keeps its `assurance: AssuranceTier`
field: it is not a money carrier, it holds no variable, and widening it would be
scope this RFC did not argue for. `AidPolicyDao` builds the cell to get the tier
and keeps handing the tier on.

### The decode moves to the DAO — and what that changes

`CanonicalMoneyReadDao.mapPriceFigure` / `mapCohortMoneyStat` already decode
five enums and the reading through located `CorruptPersistedValueException`s,
inside ONE `try` that turns any such fault into an `UnreadableMoneyRow`: the row
leaves the batch, the other colleges on the list are still answered, and the
reader logs it. `PublishedCell.of` joins that list, with the same natural-key
`location` every sibling decode already passes.

This is the one honest behaviour delta, and it is confined to a fault path:

- BEFORE: an unmapped Scorecard `source_variable` threw out of the SERVICE
  layer, failing that request.
- AFTER: it is refused at the DAO, and costs ONE ROW.

One consequence, said out loud rather than left to be found: with the row
withdrawn, the surfaces above see a college that simply has no such row, so the
fit-lens digest speaks its ordinary "we have not collected this" copy instead of
omitting the key. That is not a new trade — it is the one RFC 166 already made
for every other undecodable cell in this batch read, and the alternative is a
second, harsher rule for this decode alone.

No variable any loader can write reaches either path —
`AssuranceTierClosureTest` proves totality over the loaders' own vocabularies,
by construction from `ScorecardInstitutionColumns.LOADED_VARIABLES`,
`SfaVariables.ALL` and `IpedsChargeVocabulary`. The delta is therefore only
reachable under DEPLOY SKEW, which is exactly the case `UnreadableMoneyRow` was
designed for (RFC 166): a build that meets a cell it does not know withdraws
that cell rather than the family's whole answer. Adopting the house's existing
answer for the house's existing case is the right call here; inventing a second,
harsher one for this decode alone is not.

### What stops a wrong pairing

`FigureProvenance(status, MoneySource.SCORECARD, "H.209")` does not compile
after this change: `FigureProvenance` has no `MoneySource` parameter. To express
"a Scorecard cell" a caller must write `PublishedCell.ScorecardCell.of(...)`,
whose only argument is a variable the Scorecard tier table answers for.

Being exact about the guarantee, because half-claims about type safety are worse
than none: `source_variable` is an open `TEXT` column, so the SET of legal
Scorecard variables cannot be closed at compile time from `:db` —
`LOADED_VARIABLES` lives in `:college`, which depends on `:db` and not the
reverse. What becomes IMPOSSIBLE is a cell whose SOURCE and VARIABLE FAMILY
disagree, which is the defect in the example. What stays a runtime refusal, in
exactly ONE place, is an unknown Scorecard string. An enum of the 24 Scorecard
variables in `:db` would close the second half too; it is rejected here because
it would duplicate the loader's registry in the module below it and make the
closure test a tautology.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/models/PublishedCell.kt` — NEW, the union
  and its decoder.
- `db/src/main/kotlin/ed/unicoach/db/models/AssuranceTier.kt` — `of` delegates;
  `SCORECARD_TIERS` unchanged.
- `db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyReadRows.kt` — `cell`
  replaces the pair on both read rows.
- `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyReadDao.kt` — build the
  cell in the two mappers.
- `db/src/main/kotlin/ed/unicoach/db/dao/AidPolicyDao.kt` — build the cell, hand
  on its tier.
- `service/.../canonical/CollegeFigures.kt` — `cell` on `DatedFigure`,
  `DatedStat`, `FigureProvenance`.
- `service/.../costs/CollegeCostService.kt` — `cell` on `FigureStatusNote`; the
  two `AssuranceTier.of` calls become field reads.
- `service/.../fitlens/FitLensService.kt` — `DigestNetPrice` DERIVES its tier
  from `stat.cell` instead of storing a second copy that could disagree with it.
- `db/src/test/.../CanonicalMoneyReadDaoTest.kt` — the one read assertion that
  names the variable, plus the new test for the refusal delta below.
- Fixture CONSTRUCTION sites only, no assertion touched: `CollegeFiguresTest`,
  `ResidencyTiersTest`, `ComparisonBasisTest`, `CostReportPageTest`, and
  `public-web`'s `FakeCostReportSource` — which stops naming a Scorecard column
  under an IPEDS publisher, the very mispairing this RFC closes.

`CanonicalMoneyDaoTest` and `FitLensServiceTest` are NOT in this list: every
`sourceVariable` they name is on the WRITE rows, which this RFC leaves alone.

NOT touched: the WRITE rows (`CanonicalMoneyRows.kt`), every loader in
`:college`, `Codebook.sourceVariable` (a different thing entirely — the
`assoc1..assoc6` codebook columns), `AssuranceTierTest`,
`AssuranceTierClosureTest`. No DDL, no migration, no prompt version, no seed.

## Implementation Plan

1. Add `PublishedCell` in `:db`; point `AssuranceTier.of` at it. `:db` compiles,
   nothing else moves.
2. Swap the pair for `cell` on the two read rows and build it in the two DAO
   mappers.
3. Swap the pair for `cell` on the three `CollegeFigures` carriers, with
   computed `source`.
4. Swap `source` + `assurance` for `cell` on `FigureStatusNote`; delete the two
   `AssuranceTier.of` call sites in `CollegeCostService` and the one in
   `FitLensService`.
5. `AidPolicyDao` builds the cell for its tier.
6. Fix the fixture and test construction sites. Assertions are not touched.

## Tests

- `AssuranceTierTest` and `AssuranceTierClosureTest` pass with NO source edit —
  the resolver's signature and `SCORECARD_TIERS` are unchanged.
- NEW, in `:db`: a `CanonicalMoneyReadDaoTest` case pinning the ONE behaviour
  delta this RFC argues — a stored Scorecard `source_variable` no tier answers
  for is refused inside the mapper's per-row `try` and costs exactly that row,
  while the other college on the batch still answers. The delta is the part of
  this change a reader is most entitled to see proven, so it is asserted rather
  than only argued, in the shape the four sibling unreadable-row tests already
  use.
- NEW, in `:db`: a `PublishedCellTest` pinning that `of` round-trips each source
  to the right arm, that `Surveyed`/`SelfPublished` report the source they stand
  for, and that `ScorecardCell.of` still raises the located
  `CorruptPersistedValueException` with both halves of the pair as its `value`.
- The pinned-word suites — `CollegeCostServiceTest`, `CollegeCostChatToolTest`,
  `FitLensServiceTest`, `CostReportPageTest`, `MoneySourceCopyTest`,
  `SystemPromptCatalogTest`, `AssuranceTierCopyTest` — pass with NO edit to what
  they assert. That unchanged assertion set IS the proof of "byte-identical
  user-visible output".
- REACHABILITY: none is claimed. This change adds no surface and no door; it is
  invisible to users by design, and the unchanged assertion set above is the
  evidence for that claim rather than a new route to click.
