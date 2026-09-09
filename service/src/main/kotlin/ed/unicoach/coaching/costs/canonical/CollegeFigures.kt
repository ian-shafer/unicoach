package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.FigureGroup
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortMoneyStat
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CohortStatAddress
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MeasureUnit
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.ResidencyBasis
import kotlin.math.roundToInt

/**
 * WHERE one [CostField] lives in the canonical price grid: the
 * concept/residency/arrangement address of the `price_figures` cell behind it.
 *
 * The read-side twin of `IpedsChargeVocabulary.PriceCoordinate`, which says the
 * same thing for the write side. Two homes because the two sides key off
 * different vocabularies -- a publisher's variable stem there, this repo's wire
 * field here -- and neither can be derived from the other.
 */
data class PriceAddress(
  val concept: PriceConcept,
  val residency: ResidencyBasis,
  val arrangement: FigureArrangement,
)

/**
 * WHERE one [CostField] lives in `cohort_money_stats`: the measure, and whether
 * the family's answered income band selects the row.
 *
 * [bandSelected] is [CostField.NET_PRICE]'s one peculiarity, kept as data rather
 * than as a special case at the read site: the NPT4 band series files one row
 * per band under the same measure, so the band selects the ROW where it used to
 * select a column.
 */
data class CohortAddress(
  val measure: MoneyMeasure,
  /**
   * WHO the statistic is about. Pinned, never inferred: `avg_net_price` is
   * filed for TWO different populations in this store -- the Title IV-aided
   * undergraduates the NPT4 band series describes, and the full-time
   * first-time aid cohort IPEDS SFA publishes a grant-aided net price for --
   * and they are different numbers about different students.
   */
  val population: CohortPopulation,
  /**
   * Which aid relationship bounds the cohort. Pinned for the same reason: the
   * SFA fill writes a `grant_aided` overall net price BESIDE the Scorecard's
   * `federal_aid_receiving` one, at a NEWER vintage, so a selector keyed on the
   * measure alone would serve the newer row as "the average net price" -- wrong
   * cohort, wrong number, and no label saying which.
   */
  val aidScope: CohortAidScope,
  /**
   * WHICH family of figures a field at this address belongs to (RFC 166 §3), or
   * null for a measure the store writes `undated` -- median debt, median
   * earnings -- which is on neither dated basis and may borrow no year.
   *
   * Declared HERE and nowhere else. The group used to be a second, hand-typed
   * column on [CostField] beside this address, and two columns cannot be "the
   * single classifier": a `price_figures` field mistyped `BLENDED_AVERAGE`
   * would have shipped a published tuition price under the cohort year, with
   * nothing failing. [ed.unicoach.coaching.costs.canonical.figureGroup] derives the
   * classifier from the address, so the two can no longer disagree.
   */
  val group: FigureGroup?,
  val bandSelected: Boolean = false,
)

/**
 * What one [CostField] reads out of the canonical store -- the read-side
 * replacement for `CostField.reportedAmountOf(College)`, and the same three
 * answers a nullable `Int` used to collapse into one.
 *
 * Exhaustive with no `else`: this is the ONE site that owes a new [CostField] an
 * address, and it must refuse to compile rather than let a field read as a
 * silence the school never kept.
 */
sealed interface FigureAddress {
  /**
   * WHICH family of figures an address serves (RFC 166 §3) -- the classifier
   * [ed.unicoach.coaching.costs.CostField] used to state a second time.
   *
   * On the interface, so a case added here owes an answer before it compiles:
   * where a figure LIVES decides which year labels it, and the two facts may
   * not be maintained apart.
   */
  val group: FigureGroup?

  /** A `price_figures` cell. */
  data class Price(
    val address: PriceAddress,
  ) : FigureAddress {
    /** A school's own price list for one year -- always the published-price group. */
    override val group: FigureGroup get() = FigureGroup.PUBLISHED_PRICE
  }

  /** A `cohort_money_stats` row -- a number about a population, never a price anyone is quoted. */
  data class Cohort(
    val address: CohortAddress,
  ) : FigureAddress {
    /** The address's own group: blended for the dated series, null for a measure the store writes `undated`. */
    override val group: FigureGroup? get() = address.group
  }

  /**
   * NO ROW ANYWHERE, and never one: the amount is unicoach's own assumption
   * (RFC 166 §7, gate-2 D17). The one member is the with-family
   * food-and-housing line, whose `$0` is a fact about what enrolling does not
   * cost rather than a figure any publisher withheld.
   */
  data object AssumedByUnicoach : FigureAddress {
    /**
     * The published-price group, and not a group of its own: the `$0` is a line
     * inside a published-price budget and is dated with it (RFC 166 §7), so it
     * must share that group or [ed.unicoach.coaching.costs.ArrangementCost]
     * would refuse the at-home total D17 exists to produce.
     */
    override val group: FigureGroup get() = FigureGroup.PUBLISHED_PRICE
  }
}

/**
 * The canonical address of every [CostField] (RFC 166 §1).
 *
 * Exhaustive with no `else` on purpose, exactly as `reportedAmountOf` was: a
 * field added to the vocabulary must say which canonical cell answers for it
 * before it compiles, instead of silently reading as "not reported".
 */
val CostField.figureAddress: FigureAddress
  get() =
    when (this) {
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD -> {
        FigureAddress.Cohort(
          CohortAddress(
            MoneyMeasure.PUBLISHED_COST_BLEND,
            CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
            CohortAidScope.ALL,
            group = FigureGroup.BLENDED_AVERAGE,
          ),
        )
      }

      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.TUITION_AND_FEES, ResidencyBasis.IN_STATE, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.TUITION_AND_FEES, ResidencyBasis.OUT_OF_STATE, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.TUITION_AND_FEES, ResidencyBasis.IN_DISTRICT, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.FEES_ONLY, ResidencyBasis.IN_DISTRICT, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.FEES_ONLY, ResidencyBasis.IN_STATE, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.FEES_ONLY, ResidencyBasis.OUT_OF_STATE, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.NET_PRICE -> {
        FigureAddress.Cohort(
          CohortAddress(
            MoneyMeasure.AVG_NET_PRICE,
            CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
            CohortAidScope.FEDERAL_AID_RECEIVING,
            group = FigureGroup.BLENDED_AVERAGE,
            bandSelected = true,
          ),
        )
      }

      CostField.MEDIAN_DEBT_AT_COMPLETION_USD -> {
        FigureAddress.Cohort(
          CohortAddress(
            MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
            CohortPopulation.FEDERAL_LOAN_BORROWING_COMPLETERS,
            CohortAidScope.FEDERAL_LOAN_BORROWING,
            // UNDATED (RFC 149 D-E): a completers' cohort on neither dated
            // basis, so it carries no group and no spoken year rather than
            // borrowing the blend's.
            group = null,
          ),
        )
      }

      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD -> {
        FigureAddress.Cohort(
          CohortAddress(
            MoneyMeasure.MEDIAN_EARNINGS_10Y,
            CohortPopulation.EMPLOYED_NOT_ENROLLED_10Y_AFTER_ENTRY,
            CohortAidScope.ALL,
            // UNDATED for the same reason: a ten-years-after-entry cohort.
            group = null,
          ),
        )
      }

      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
        )
      }

      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS),
        )
      }

      // No source publishes it, and none ever will: IPEDS assumes zero food and
      // housing for a student living at home and publishes no variable at all.
      // The zero is OURS (RFC 166 §7), so it has no address in a store of what
      // publishers said.
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD -> {
        FigureAddress.AssumedByUnicoach
      }

      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.BOOKS_AND_SUPPLIES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.NOT_APPLICABLE),
        )
      }

      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
        )
      }

      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS),
        )
      }

      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD -> {
        FigureAddress.Price(
          PriceAddress(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.WITH_FAMILY),
        )
      }
    }

/**
 * WHICH family of figures this field belongs to (RFC 166 §3), DERIVED from the
 * canonical cell that answers for it.
 *
 * One fact, one home. The group was a second column on the [CostField] member,
 * hand-maintained beside [figureAddress] and free to disagree with it: a
 * `price_figures` field declared [FigureGroup.BLENDED_AVERAGE] would have been
 * emitted under `blended_average_academic_year` -- a published tuition price
 * stamped with the cohort year -- and nothing would have failed. Reading the
 * group off the address makes that disagreement unrepresentable rather than
 * untested, and a field added to the vocabulary declares its group by declaring
 * where it lives.
 *
 * Null for the two measures the store writes `undated`: on neither dated basis,
 * so no year labels them and nothing may sum them.
 */
val CostField.figureGroup: FigureGroup?
  get() = figureAddress.group

/**
 * One price figure as this surface serves it: the reading, and the academic year
 * of the row it was read from.
 *
 * The year is DATA here, not a constant on a closed enum. `price_figures` holds
 * four IPEDS academic years and one Scorecard year, so "the latest" is a
 * per-college, per-key question, and a Scorecard-only college is honestly a year
 * behind an IC_AY one.
 */
data class DatedFigure(
  val academicYear: AcademicYear,
  val reading: FigureReading<Int>,
  /**
   * The publisher whose row won this cell (RFC 177).
   *
   * NON-NULL, because a [DatedFigure] IS a row: `price_figures.source` is a
   * required column and `PriceFigure.source` is non-null, so a figure with no
   * publisher is not a state this surface can be in. "No publisher" is the
   * ABSENCE of a figure -- no [DatedFigure] at all -- never one carrying a null.
   *
   * It rides on the figure because it was dropped here: this index used to keep
   * only the reading, so nothing above the domain layer could know whether a
   * price came from IPEDS or from the Scorecard, and every surface that wanted
   * to say it typed one publisher's name by hand.
   */
  val source: MoneySource,
) {
  /** The dollars, or null when the reading bears no value. */
  val amountUsd: Int? get() = (reading as? FigureReading.Present)?.value

  val status: FigureStatus get() = reading.status
}

/**
 * One cohort statistic as this surface serves it: the reading rounded to whole
 * dollars, the vintage the row carries, and the POPULATION BASIS it was built
 * on.
 *
 * [residencyScope] rides along because a blended average is only true of the
 * students it averaged: the Scorecard builds `COSTT4_A` and the `NPT4` family
 * for in-state-rate-paying undergraduates at a PUBLIC school and for everybody
 * at a private one, and a reader that hand-asserts "in-state" is wrong at half
 * the corpus (RFC 166 §9).
 *
 * [vintage] is null for the literal `'undated'` the store writes where the
 * source dates nothing -- median debt and median earnings say no year at all,
 * exactly as they do today, rather than borrowing one.
 */
data class DatedStat(
  val vintage: AcademicYear?,
  val residencyScope: CohortResidencyScope,
  val reading: FigureReading<Int>,
  /** The publisher whose row won this cell (RFC 177) -- the cohort twin of [DatedFigure.source], and non-null for the same reason. */
  val source: MoneySource,
) {
  val amountUsd: Int? get() = (reading as? FigureReading.Present)?.value

  val status: FigureStatus get() = reading.status
}

/**
 * One figure's canonical STATUS and the publisher it came from, travelling
 * together (RFC 177).
 *
 * A pair rather than a widened return tuple at eight call sites: the status is
 * the reason beside a blank and the source is who owes us that reason, and a
 * caller that reads one without the other is exactly how a Scorecard name ended
 * up beside an IPEDS figure.
 *
 * Both fields come off ONE row, so [source] is non-null: where no row answers --
 * the assumed at-home line, or a cell no publisher ever wrote -- [statusOf]
 * returns no provenance at all rather than a provenance with nothing in it.
 */
data class FigureProvenance(
  val status: FigureStatus,
  val source: MoneySource,
)

/**
 * The resolved canonical figure set for ONE college -- the type that replaces
 * `College` as the cost path's input (RFC 166 §2).
 *
 * It is built once per request by [CanonicalCostReader] from the rows the two
 * batched reads returned, and it answers exactly the questions the cost domain
 * used to ask a `colleges` row: what does this school say about this field, in
 * which year, and with what status. What it adds is the three things the
 * publisher-shaped columns could not carry -- a third tuition tier, a fees
 * split, and the reason a figure is missing.
 *
 * ONE academic year per college for the published-price group
 * ([publishedPriceAcademicYear]). RFC 166 §3 rule 2 requires a total to be
 * composed from figures of one year, and rule 4 requires the year to be STATED
 * under one wire key per college; choosing the year per arrangement would leave
 * that key with no truthful value when two arrangements resolved differently.
 * So the year is chosen once, here, and every published-price figure this
 * college serves is read at it.
 */
class CollegeFigures(
  val collegeId: CollegeId,
  priceFigures: List<PriceFigure>,
  cohortStats: List<CohortMoneyStat>,
) {
  /**
   * Every price row this college carries, keyed by its canonical address and
   * then by academic year.
   *
   * A `Map` of `Map`s rather than a list scanned per lookup: the cost path asks
   * this object about a field once per field per arrangement per college, and
   * a college carries ~48 price rows.
   */
  private val pricesByAddress: Map<PriceAddress, Map<AcademicYear, DatedFigure>> =
    priceFigures
      .groupBy { PriceAddress(it.priceConcept, it.residencyBasis, it.arrangement) }
      // The whole served figure, not the bare reading: the row's own
      // [PriceFigure.source] is the publisher that won this cell, and keeping
      // only the reading here is where it used to die (RFC 177).
      .mapValues { (_, rows) ->
        rows.associate { it.academicYear to DatedFigure(it.academicYear, it.reading, it.source) }
      }

  /**
   * The cohort rows, keyed by their FULL canonical address -- measure,
   * population, residency scope, aid scope and income band -- with only the
   * latest vintage per key kept.
   *
   * The full address, never the measure alone. `cohort_money_stats` files more
   * than one row under `avg_net_price`: the Scorecard's Title IV-aided
   * `federal_aid_receiving` figure, and (since RFC 162) IPEDS SFA's
   * `grant_aided` figure for the full-time first-time aid cohort, at a NEWER
   * vintage. Keyed on the measure and taken by latest vintage, the SFA row
   * would be served as "the average net price" everywhere -- a different
   * cohort's number, unlabelled. RFC 166 §8 pins the whole address for exactly
   * this reason, and [CostField.figureAddress] is where each figure names it.
   *
   * Deliberately NOT exhaustive over [MoneyMeasure]: that enum is the STORE's
   * vocabulary and grows with every source (RFC 162 added nine members), while
   * this surface serves only the closed set of addresses it names. It serves a
   * CLOSED SET IT
   * NAMES ([CostField.figureAddress]) and treats every other measure as a
   * statistic it does not render.
   */
  private val cohortByKey: Map<CohortKey, DatedStat> =
    cohortStats
      // WHOLE-DOLLAR statistics only. `cohort_money_stats.value` is one NUMERIC
      // column carrying two units, named on the measure ([MoneyMeasure.unit]),
      // and a 0-1 SHARE has no dollar form: rounded like a dollar figure,
      // `pell_share = 0.183` would be held here as `amountUsd = 0` -- a share
      // served as money. A share row is NOT CARRIED rather than rounded, so a
      // field added at a share address reads as "no such figure" instead of as
      // `$0`. Today no [CostField] names a share, and that is a coincidence of
      // the current vocabulary, not a property of the type.
      .filter { it.measure.unit == MeasureUnit.USD_PER_YEAR }
      .groupBy { CohortKey(it.measure, it.population, it.residencyScope, it.aidScope, it.incomeBand) }
      .mapValues { (key, rows) ->
        // Latest DATED vintage wins, and "undated" is an ABSENT year rather
        // than a magic string (RFC 170 D14), so it sorts first by TYPE and
        // cannot be compared as though it were a year. The sentinel this
        // replaced ordered BACKWARDS against the raw column -- `"undated" >
        // "2023-24"` by char code -- and the dated row's year was discarded. A
        // measure DOES carry both at one key: the SFA fill writes `pell_share`
        // dated beside the Scorecard's undated one.
        val row = rows.maxWith(compareBy(VINTAGE_ORDER) { it.vintage })
        val vintage = row.vintage
        // A series whose ADDRESS declares a [FigureGroup] is dated by
        // definition, so an undated row at it is a store/address contradiction
        // rather than a figure. Refused HERE, so a blended amount can never
        // reach either surface with no year beside it -- the vintage label is
        // written per group ([blendedAverageVintage]), and a null year emits no
        // label at all rather than an unlabelled dollar figure.
        check(vintage != null || CohortStatAddress(key.measure, key.population, key.aidScope) !in DATED_COHORT_SERIES) {
          // The WHOLE natural key, all six columns of it. Four of them named
          // the series and not the ROW, so an operator holding this message
          // still had to guess which of a college's rows to go and look at --
          // and residency scope is exactly the column two rows of one address
          // differ by. Every value is already bound on this line.
          "a dated cohort series may not be served from an undated row: college_id=[${collegeId.value}] " +
            "measure=[${key.measure.value}] population=[${key.population.value}] " +
            "residency_scope=[${key.residencyScope.value}] aid_scope=[${key.aidScope.value}] " +
            "income_band=[${key.incomeBand?.value}] source=[${row.source.value}]"
        }
        DatedStat(
          vintage = vintage,
          residencyScope = row.residencyScope,
          reading = row.reading.toWholeDollars(),
          // The winning row is in hand on this line, and its publisher used to
          // be dropped from it (RFC 177).
          source = row.source,
        )
      }

  /**
   * The academic years this college carries any published-price row in, latest
   * first. Lexicographic order IS chronological order for `'YYYY-YY'`.
   *
   * INTERNAL, with [hasValuesAt] and [hasAnyValueAt]: public year arithmetic
   * is an invitation to compose a total out of two years, which is exactly what
   * this design forbids. The year is chosen once, by [publishedPriceYearOf], and
   * every
   * caller outside this module asks for a figure AT a year rather than doing
   * its own sums over the years available.
   */
  internal val publishedPriceYears: List<AcademicYear> =
    pricesByAddress.values
      .flatMap { it.keys }
      .distinct()
      .sortedDescending()

  /**
   * The vintage of the blended averages THIS SURFACE SERVES, or null when it
   * serves none -- the label twin of [cohortOf], derived from the same closed
   * set of addresses.
   *
   * Never a fold over every row this college carries. `cohort_money_stats` holds
   * measures no [CostField] names and, since RFC 162, a SECOND `avg_net_price`
   * series at a NEWER vintage than the Scorecard blend this surface serves. A
   * max over all of them stamped the served figure with a year belonging to a
   * row nobody was shown -- a money figure under the wrong year, which is the
   * one thing the vintage labels exist to prevent.
   *
   * [band] is the family's answered income band, so the label describes the ROW
   * the payload served: the band selects the row for a band-selected address,
   * and is ignored for the others.
   */
  fun blendedAverageVintage(band: IncomeBand?): AcademicYear? =
    CostField.entries
      .filter { it.figureGroup == FigureGroup.BLENDED_AVERAGE }
      .map { field ->
        // Exhaustively, never `as?`: a blended-average field that does not live
        // in `cohort_money_stats` is a vocabulary mistake, and dropping it here
        // would date the REST of the group with a year that does not cover it.
        val address =
          when (val figureAddress = field.figureAddress) {
            is FigureAddress.Cohort -> {
              figureAddress.address
            }

            is FigureAddress.Price, FigureAddress.AssumedByUnicoach -> {
              error(
                "cost field [${field.wireName}] is grouped as a blended average but does not live in " +
                  "cohort_money_stats, so no cohort row dates it: college_id=[${collegeId.value}] " +
                  "address=[$figureAddress]",
              )
            }
          }
        cohortOf(address, selectedBandOf(address, band))
      }.mapNotNull { it?.vintage }
      .toSet()
      // THREE states, three answers -- never the newest of several. No served
      // blend has no year; ONE distinct year IS the year; two different years
      // may not be collapsed, because one label dates every figure in the group
      // and picking the newer stamps the older figure with a year no row of it
      // carries. Reachable, not hypothetical: the SFA fill writes the BAND net
      // price rows at the address this surface serves at a newer aid year than
      // the Scorecard's COSTT4_A blend, so any family with an answered band had
      // the sticker cost quoted under the net price's year.
      //
      // Null on disagreement rather than a throw: the null path already exists
      // and is already right downstream -- `putVintageLabels` emits no
      // `blended_average` key and `DatedFigures.of` filters the college out --
      // so the figures are said with NO year instead of with a wrong one.
      .singleOrNull()

  /**
   * This field's figure at the college's chosen published-price year, or its
   * latest cohort row -- THE one answer to "what does this school say about
   * this field", and the primitive every other site derives from.
   *
   * Null means there is no row at all: the fill has never written this cell for
   * this college. That is a different fact from a row whose status says the
   * school did not report it, and the two are kept apart here for the same
   * reason `ReportedAmount` kept "no column" apart from "no value".
   */
  fun figureOf(
    field: CostField,
    year: AcademicYear?,
  ): DatedFigure? =
    when (val address = field.figureAddress) {
      is FigureAddress.Price -> priceAt(address.address, year)

      // A cohort statistic is not dated by the published-price year and is
      // never read through this door; [cohortOf] answers for it.
      is FigureAddress.Cohort -> null

      FigureAddress.AssumedByUnicoach -> null
    }

  /**
   * The canonical STATUS behind one field -- the reason beside its blank (RFC
   * 166 §6), whichever table answers for it.
   *
   * Dispatched on the sealed [FigureAddress] with no `else`, so the COMPILER
   * owes every arm an answer. [figureOf] keeps its price-only meaning and
   * answers `null` for a cohort field, which is right for a VALUE (a cohort
   * statistic must not acquire the published-price year) and was wrong for a
   * STATUS: read through that door, a Scorecard net price the publisher
   * SUPPRESSED FOR PRIVACY had no status at all, so it was published as the
   * school's own silence and its sentence never reached the family.
   *
   * Null means no row answers for this field: a cell the fill has never
   * written, or the assumed at-home line, which no publisher owes us a status
   * for.
   */
  fun statusOf(
    field: CostField,
    year: AcademicYear?,
    band: IncomeBand?,
  ): FigureProvenance? =
    when (val address = field.figureAddress) {
      // The row at the served year answers; when this college has none, a
      // VALUE-FREE row at another year still does. That row is not a year gap
      // ([yearGapOf] declines it -- we hold no figure for that year either), so
      // without this fall-through the field would have no statement at all and
      // a publisher's suppression one year over would be spoken as this
      // school's own silence. A VALUE-BEARING row at another year is left to
      // [yearGapOf], so exactly one door speaks for each.
      is FigureAddress.Price -> {
        (priceAt(address.address, year) ?: latestValuelessPriceOf(field))
          ?.let { FigureProvenance(it.status, it.source) }
      }

      is FigureAddress.Cohort -> {
        cohortOf(address.address, selectedBandOf(address.address, band))
          ?.let { FigureProvenance(it.status, it.source) }
      }

      FigureAddress.AssumedByUnicoach -> {
        null
      }
    }

  /**
   * The distinct publishers behind the figures this college SERVES at [year] and
   * [band] -- what a page or a payload may honestly name as its sources (RFC 177
   * D5).
   *
   * VALUE-BEARING ONLY. A publisher enters this list when a figure of its own,
   * with a number in it, is shown at the served year and band -- never because a
   * row of its exists. A suppressed, not-applicable or not-reported cell is a
   * row and not a figure, and a value-free row at ANOTHER year ([statusOf]'s
   * fall-through) is not even at this year: naming its publisher would tell a
   * family "the cost, price and federal debt figures come from X" about an X
   * that published no figure they can see, which is the false attribution this
   * whole seam exists to delete, one size smaller. Such a row still speaks in
   * its OWN sentence, through [statusOf] -- that sentence names the publisher of
   * that cell, and is right to.
   *
   * It walks the same closed set of addresses the surfaces render, so a college
   * with no IPEDS figure on it never names IPEDS. Never a fold over every row
   * the store carries.
   */
  fun servedSourcesOf(
    year: AcademicYear?,
    band: IncomeBand?,
  ): List<MoneySource> =
    CostField.entries
      .mapNotNull { servedPublisherOf(it, year, band) }
      .distinct()

  /**
   * The publisher of this field's SHOWN figure at [year] and [band], or null
   * where no figure with a number in it is shown.
   *
   * Dispatched on the sealed [FigureAddress] with no `else`, exactly as
   * [statusOf] and [cohortOf] are: a fourth address must decide whether it
   * serves a figure before it compiles. The price arm reads the row at the
   * SERVED year only -- a value-bearing row at another year is a year gap, which
   * this answer deliberately does not show ([yearGapOf]).
   */
  private fun servedPublisherOf(
    field: CostField,
    year: AcademicYear?,
    band: IncomeBand?,
  ): MoneySource? =
    when (val address = field.figureAddress) {
      is FigureAddress.Price -> {
        priceAt(address.address, year)?.takeIf { it.amountUsd != null }?.source
      }

      is FigureAddress.Cohort -> {
        cohortOf(address.address, selectedBandOf(address.address, band))?.takeIf { it.amountUsd != null }?.source
      }

      FigureAddress.AssumedByUnicoach -> {
        null
      }
    }

  /** This field's price row at [year] specifically, or null when this college has none. */
  fun priceAt(
    address: PriceAddress,
    year: AcademicYear?,
  ): DatedFigure? {
    if (year == null) return null
    return pricesByAddress[address]?.get(year)
  }

  /**
   * The latest row this college holds for [field] when it holds NONE at [year]
   * -- a YEAR GAP, and the primitive RFC 166 §3's no-silent-drop rule is built
   * on.
   *
   * Null when the field has a row at the served year (whatever that row says --
   * a suppressed reading is an answer, and it speaks in its own status), null
   * when the college holds no row for the field at all (that is our source
   * coverage, not a year gap), and null when the only row at another year bears
   * no value (that row is that year's own answer, and [statusOf] speaks it).
   *
   * Everything else is a figure this school DID publish and this answer is not
   * showing, because the year it was published in is not the year the rest of
   * this school's price is quoted at. That is a gap of OURS, and it is stated;
   * before this existed the figure was dropped with no key and no status, which
   * is the one thing this surface may never do.
   */
  fun yearGapOf(
    field: CostField,
    year: AcademicYear?,
  ): DatedFigure? {
    val address = (field.figureAddress as? FigureAddress.Price)?.address ?: return null
    if (priceAt(address, year) != null) return null
    // A year gap is a figure this school PUBLISHED in another year. A row that
    // bears NO value is not one: we hold no figure for that year either, and
    // saying we do claims a number the school never published -- and buries the
    // row's own status, so a suppression one year over is never spoken.
    // [statusOf] answers for that row instead.
    return latestPriceOf(field)?.takeIf { it.amountUsd != null }
  }

  /**
   * The latest row for [field] that bears no value, whatever its year -- called
   * only where the served year has no row ([statusOf]). The twin of [yearGapOf],
   * and the row whose own status answers for the field.
   *
   * Private: the two are one decision about one row, and a caller choosing
   * between them is the split that let a `not_applicable` row be spoken as a
   * price we hold.
   */
  private fun latestValuelessPriceOf(field: CostField): DatedFigure? = latestPriceOf(field)?.takeIf { it.amountUsd == null }

  /** The LATEST row for this address whatever its year -- what a figure is quoted at when it is quoted alone. */
  fun latestPriceOf(field: CostField): DatedFigure? {
    val address = (field.figureAddress as? FigureAddress.Price)?.address ?: return null
    val byYear = pricesByAddress[address] ?: return null
    val year = byYear.keys.maxOrNull() ?: return null
    return byYear.getValue(year)
  }

  /**
   * The cohort statistic behind one [CostField], at this band -- the door every
   * caller outside this file uses, so the ADDRESS of a figure is stated once
   * ([CostField.figureAddress]) and never re-typed at a call site.
   *
   * REFUSES a field that is not a cohort statistic at all: null here means one
   * thing only -- this college carries no such row.
   *
   * [band] has NO default, here or on the overload below: it is the difference
   * between "the average family pays $20,000" and "a family in your band pays
   * $6,000", and a caller that forgot it still compiled and still returned a
   * number -- the band-less row, served where the band's row was meant. "No
   * band" is now a decision stated on the page.
   */
  fun cohortOf(
    field: CostField,
    band: IncomeBand?,
  ): DatedStat? =
    // Exhaustively, mirroring [statusOf]: a safe cast would answer the SAME
    // null for "this is not a cohort field" as for "this college carries no
    // such row", and that null is rendered to a family as the school's silence.
    // A field read through the wrong door is a mistaken call, not a coverage
    // fact about a school.
    when (val address = field.figureAddress) {
      is FigureAddress.Cohort -> {
        cohortOf(address.address, band)
      }

      is FigureAddress.Price, FigureAddress.AssumedByUnicoach -> {
        error(
          "cost field [${field.wireName}] is not a cohort statistic, so no cohort row answers for it: " +
            "college_id=[${collegeId.value}] address=[$address]",
        )
      }
    }

  /**
   * The cohort statistic at this canonical ADDRESS and band, or null when this
   * college carries no such row.
   *
   * The address pins measure, population and aid scope (RFC 166 §8). Residency
   * scope is NOT part of what a caller asks for -- the fill chooses it from the
   * school's control, `in_state_rate_paying` at a public school and `all`
   * everywhere else, so a college carries one or the other and never both -- and
   * it rides back on [DatedStat.residencyScope] so no reader hand-asserts it.
   *
   * Should a college somehow carry BOTH, this REFUSES rather than resolving.
   * The two rows are two different cohorts' numbers, the surfaces carry no key
   * saying which was served, and the resolution this used to make was an enum's
   * declaration order in another module -- so reordering [CohortResidencyScope]
   * silently changed which cohort's net price a public school quoted, with
   * every test still green. One address is one population basis.
   */
  fun cohortOf(
    address: CohortAddress,
    band: IncomeBand?,
  ): DatedStat? {
    // [CohortAddress.bandSelected] is load-bearing HERE, at the read site its
    // own KDoc describes, rather than at one unrelated caller: a measure that
    // files no band series has no row an income band could select, so asking for
    // one is a mistaken call, not a college with no figure. Answering null let
    // it read as "we hold no median debt for this school".
    require(band == null || address.bandSelected) {
      "cohort address is not band-selected, so no income band selects a row: " +
        "college_id=[${collegeId.value}] measure=[${address.measure.value}] " +
        "population=[${address.population.value}] aid_scope=[${address.aidScope.value}] band=[${band?.value}]"
    }
    val candidates =
      cohortByKey
        .entries
        .filter { (key, _) ->
          key.measure == address.measure &&
            key.population == address.population &&
            key.aidScope == address.aidScope &&
            key.incomeBand == band
        }
    val scopes = candidates.map { it.key.residencyScope }.toSet()
    require(scopes.size <= 1) {
      "one cohort address is one population basis: college_id=[${collegeId.value}] " +
        "measure=[${address.measure.value}] population=[${address.population.value}] " +
        "aid_scope=[${address.aidScope.value}] band=[${band?.value}] " +
        "residency_scopes=[${scopes.map { it.value }.sorted().joinToString(", ")}]"
    }
    // With one scope left, [CohortKey] is fully pinned, so there is one row or
    // none -- no ordering, and so no `?: ""` floor standing in for a vintage.
    return candidates.singleOrNull()?.value
  }

  /**
   * True when every one of [fields] bears a VALUE at [year] -- the test the
   * year choice is made on.
   *
   * A field with no price address at all (the assumed at-home line) is complete
   * in every year: its amount is ours and does not depend on what the school
   * published, so it must not veto a year.
   *
   * A cohort field answers true for a different reason: it is not dated by the
   * published-price year at all ([figureOf] declines it), so it may neither
   * complete a year nor veto one.
   */
  internal fun hasValuesAt(
    fields: Collection<CostField>,
    year: AcademicYear,
  ): Boolean =
    fields.all { field ->
      when (val address = field.figureAddress) {
        is FigureAddress.Price -> priceAt(address.address, year)?.amountUsd != null
        is FigureAddress.Cohort -> true
        FigureAddress.AssumedByUnicoach -> true
      }
    }

  /**
   * True when at least one of [fields] bears a value at [year] -- the fallback
   * test. Non-price fields answer FALSE here, the opposite of [hasValuesAt]:
   * an `all` may not be vetoed by a field with no published price, but an `any`
   * may not be SATISFIED by one either, or a cohort statistic would select a
   * published-price year no published figure is held in.
   */
  internal fun hasAnyValueAt(
    fields: Collection<CostField>,
    year: AcademicYear,
  ): Boolean =
    fields.any { field ->
      val address = (field.figureAddress as? FigureAddress.Price)?.address ?: return@any false
      priceAt(address, year)?.amountUsd != null
    }

  /**
   * The college's one published-price year (RFC 166 §3): the latest year
   * completely pricing SOME [candidateSets], else the latest year bearing any
   * value at all, else the latest year this college holds a price row in at all
   * -- null ONLY for a college with no price row. The last arm is required, not
   * cosmetic: [ServedFigures] refuses a null year for a college that publishes
   * rows, so a college whose only rows are value-free must still be served AT one
   * of its own years.
   *
   * [candidateSets] is one set per living arrangement -- the applicable tuition
   * field plus that arrangement's own published components. Handed in rather
   * than derived here because which tuition applies is a fact about the FAMILY,
   * and this type knows only about the school.
   */
  fun publishedPriceYearOf(candidateSets: List<Set<CostField>>): AcademicYear? {
    val complete = publishedPriceYears.firstOrNull { year -> candidateSets.any { it.isNotEmpty() && hasValuesAt(it, year) } }
    if (complete != null) return complete
    val allFields = candidateSets.flatten().toSet()
    return publishedPriceYears.firstOrNull { year -> hasAnyValueAt(allFields, year) }
      ?: publishedPriceYears.firstOrNull()
  }

  override fun toString(): String =
    "CollegeFigures(collegeId=[${collegeId.value}], published_price_years=[$publishedPriceYears], " +
      "price_addresses=[${pricesByAddress.size}], cohort_rows=[${cohortByKey.size}])"

  /**
   * The full canonical address of one `cohort_money_stats` row -- every column
   * of its natural key but the college and the vintage.
   *
   * Private, and a type rather than a tuple, because the defect it exists
   * against is a key with a column MISSING from it: a `Pair(measure, band)`
   * silently merged two populations' net prices into one series and served
   * whichever the newer fill wrote.
   */
  private data class CohortKey(
    val measure: MoneyMeasure,
    val population: CohortPopulation,
    val residencyScope: CohortResidencyScope,
    val aidScope: CohortAidScope,
    val incomeBand: IncomeBand?,
  )

  /**
   * The family's band where the address files a band series, and null
   * everywhere else -- the ONE statement of that coercion.
   *
   * Named and private because the alternative is what this file used to do:
   * spell `band.takeIf { it.bandSelected }` at each reading door while
   * [cohortOf] REFUSED the identical input two screens below. One illegal
   * (address, band) pair had two resolutions, and the reading doors quietly
   * restored the band-less default the refusal exists to prevent. The coercion
   * happens where a caller MEANS "the family's band, if it selects anything";
   * [cohortOf] stays the boundary for a caller that names a band outright.
   */
  private fun selectedBandOf(
    address: CohortAddress,
    band: IncomeBand?,
  ): IncomeBand? = band.takeIf { address.bandSelected }

  companion object {
    /**
     * How a cohort vintage orders: an undated row -- an ABSENT year, which is
     * how the store says it (RFC 170 D14) -- below every [AcademicYear], and
     * years among themselves in calendar order.
     *
     * Named rather than inlined because "undated sorts oldest" is a POLICY
     * about what a missing year means here, not a property of the type; the
     * type only refuses to compare one. (It was also once a `'undated'` string
     * that sorted ABOVE every year by char code, which is the accident this
     * comparator was first written to undo.)
     */
    private val VINTAGE_ORDER: Comparator<AcademicYear?> = nullsFirst()

    /**
     * The cohort series this surface serves that a [FigureGroup] DATES -- the
     * addresses at which an `undated` row is a contradiction.
     *
     * A [CohortStatAddress]: the shared triple that says WHERE a cell lives,
     * which is exactly the part of a [CohortKey] a [CohortAddress] pins, with
     * no residency scope and no band. This surface's own [CohortAddress] is
     * NOT that triple -- it carries a [FigureGroup] and [bandSelected] besides,
     * which are facts about how a [CostField] reads, not about where the cell
     * is -- so it stays its own type and this set is keyed on the shared one.
     *
     * Derived from [CostField.figureAddress], so a field added at a dated
     * address is covered with no edit here, and the two undated measures
     * (median debt, median earnings) stay out of it by declaring no group.
     */
    private val DATED_COHORT_SERIES: Set<CohortStatAddress> =
      CostField.entries
        .mapNotNull { field ->
          when (val address = field.figureAddress) {
            is FigureAddress.Cohort -> address.address
            is FigureAddress.Price, FigureAddress.AssumedByUnicoach -> null
          }
        }.filter { it.group != null }
        .map { CohortStatAddress(it.measure, it.population, it.aidScope) }
        .toSet()

    /**
     * A `NUMERIC` cohort value read as whole dollars.
     *
     * ROUNDED, never truncated: `cohort_money_stats.value` is `NUMERIC` and may
     * be negative by design (aid exceeding cost at the lowest income bands),
     * and the column carries no non-negative CHECK for exactly that reason.
     *
     * REFUSED, never clamped, outside the `Int` range. `roundToInt` answers
     * `Int.MAX_VALUE` above it and `Int.MIN_VALUE` below, and throws on `NaN` --
     * which the unconstrained `NUMERIC` column admits -- so the clamp would show
     * a family `$2,147,483,647` with full confidence and no status. A value with
     * no whole-dollar form is a corrupt row, and it says so.
     */
    private fun FigureReading<Double>.toWholeDollars(): FigureReading<Int> =
      when (this) {
        is FigureReading.Present -> {
          require(value.isFinite() && value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()) {
            "cohort_money_stats.[value]=[$value] has no whole-dollar form"
          }
          FigureReading.Present(value.roundToInt(), bearing)
        }

        is FigureReading.Absent -> {
          this
        }
      }
  }
}
