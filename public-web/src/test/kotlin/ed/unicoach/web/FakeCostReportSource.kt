package ed.unicoach.web

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.coaching.costs.ChosenLivingPlan
import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.ComparedLivingPlan
import ed.unicoach.coaching.costs.ComparisonBasis
import ed.unicoach.coaching.costs.CostBreakdown
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.MoneyProfileStatuses
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.applicableTuitionFor
import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.residencyTiersOf
import ed.unicoach.coaching.costs.figureStatusesOf
import ed.unicoach.coaching.costs.notReportedOf
import ed.unicoach.coaching.costs.reportedOf
import ed.unicoach.coaching.costs.tuitionLineOf
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CohortMoneyStat
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.VINTAGE_UNDATED
import ed.unicoach.db.models.ValueBearingStatus
import ed.unicoach.web.report.CostReportOutcome
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.MissReason
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The raw token the fake treats as live; every other token is simply not found. */
const val TEST_LIVE_TOKEN = "live-share-token"

/**
 * What a [FakeCostReportSource] answers with: the three disjoint modes, never
 * two at once.
 *
 * A union rather than three independent nullables, so a fake is EXACTLY one
 * mode. Three optional fields spanned combinations that were not modes — a
 * profile plus a failure compiled and silently dropped the profile, and neither
 * one was a silent not-found — and the mode then had to be recovered at runtime
 * by a `!!` re-proving what the constructor should have made unrepresentable.
 */
sealed interface FakeReportAnswer {
  /** [token] resolves to [profile]; every other token is not found, as a revoked one would be. */
  data class Live(
    val profile: CollegeCostProfile,
    val token: String = TEST_LIVE_TOKEN,
  ) : FakeReportAnswer

  /** No token resolves — the unknown and revoked cases the port cannot tell apart. */
  data object NothingLive : FakeReportAnswer

  /** The database-fault branch the route must render as the branded 503 rather than as a dead link. */
  data class Fault(
    val failure: Throwable,
  ) : FakeReportAnswer
}

/**
 * A hand-written fake [CostReportSource] (a real class, not a mock), so the
 * report page's rendering and degradation tests need no database.
 *
 * A [FakeReportAnswer.Live] fake answers [CostReportOutcome.Found] for exactly
 * one token and [CostReportOutcome.NotFound] for every other — which is
 * precisely how the real adapter behaves for an unknown token AND for a revoked
 * one, because the DAO makes a revoked row invisible. A revoked token in these
 * tests is therefore not a weaker probe than an unknown one; the port cannot
 * tell them apart by construction.
 */
class FakeCostReportSource(
  private val answer: FakeReportAnswer = FakeReportAnswer.NothingLive,
) : CostReportSource {
  /** The recorder itself stays inside: a caller reads the evidence, it never edits it. */
  private val seen: MutableList<String> = CopyOnWriteArrayList()

  /**
   * Every raw token handed to the port, so a test can prove the route passed
   * what the client sent — a DEFENSIVE COPY, so one assertion cannot append to
   * or clear another test's evidence.
   */
  val tokensSeen: List<String> get() = seen.toList()

  override suspend fun getByShareToken(rawToken: String): Result<CostReportOutcome> {
    seen.add(rawToken)
    return when (answer) {
      is FakeReportAnswer.Live -> {
        Result.success(
          if (rawToken == answer.token) {
            CostReportOutcome.Found(answer.profile)
          } else {
            CostReportOutcome.NotFound(MissReason.NO_LIVE_SHARE)
          },
        )
      }

      FakeReportAnswer.NothingLive -> {
        Result.success(CostReportOutcome.NotFound(MissReason.NO_LIVE_SHARE))
      }

      is FakeReportAnswer.Fault -> {
        Result.failure(answer.failure)
      }
    }
  }
}

/** A money profile with nothing answered — the tri-state floor every student starts at. */
val UNANSWERED_MONEY =
  MoneyProfileStatuses(
    incomeBandStatus = AnswerStatus.UNANSWERED,
    incomeBand = null,
    residencyStatus = AnswerStatus.UNANSWERED,
    residencyState = null,
    living = ComparedLivingPlan.Unanswered,
  )

/** A fully answered money profile: a band in dollars and a state, so band pricing and a tuition column both apply. */
fun answeredMoney(
  band: IncomeBand = IncomeBand.K48_TO_75K,
  state: String = "CA",
): MoneyProfileStatuses =
  MoneyProfileStatuses(
    incomeBandStatus = AnswerStatus.ANSWERED,
    incomeBand = band,
    residencyStatus = AnswerStatus.ANSWERED,
    residencyState = state,
    living = ComparedLivingPlan.Unanswered,
  )

/**
 * The ONE academic year every published-price row these fixtures build is dated
 * (RFC 166 §3).
 *
 * One year, because the report page is not the surface the year selection is
 * tested on: a fixture spanning two years would let a page test pass or fail on
 * `CollegeFigures.publishedPriceYearOf`, which is the projection's own unit-tested
 * decision. What the page owes is that it prints the year the domain served.
 */
const val FIXTURE_PRICE_ACADEMIC_YEAR: String = "2023-24"

/** The vintage the blended averages carry -- a year behind the price list, as the two real sources are. */
const val FIXTURE_BLENDED_ACADEMIC_YEAR: String = "2022-23"

/**
 * The OTHER academic year a fixture may hold a published figure at, and never
 * the one the page serves (RFC 166 §3): the YEAR GAP -- a figure this school
 * really published, held only outside the year its price is quoted at.
 *
 * Older than [FIXTURE_PRICE_ACADEMIC_YEAR] on purpose, so a row here can never
 * become the served year and turn a gap test into a year-selection test.
 */
const val FIXTURE_YEAR_GAP_ACADEMIC_YEAR: String = "2021-22"

/**
 * One school's cost facts, assembled the way `CollegeCostService` assembles
 * them: the published amounts go into CANONICAL ROWS -- `price_figures` and
 * `cohort_money_stats` read models -- and the breakdown, the reported set and
 * the tuition line are then COMPUTED by the domain ([CostBreakdown.of]) rather
 * than hand-written here.
 *
 * That matters: a hand-built breakdown could carry a total the parts do not
 * support, and the page's whole job is to print what the domain computed. The
 * fixture may only choose what a school publishes.
 *
 * RFC 166 replaced the `colleges` row this used to build with [CollegeFigures].
 * Not one of the page's test bodies moved with it -- the parent-facing surface
 * asserts the same HTML from a different source -- except the one gate-2 D17
 * reverses outright, the at-home food-and-housing line.
 */
@Suppress("LongParameterList")
fun costFixture(
  name: String,
  control: CollegeControl = CollegeControl.PrivateNonprofit,
  city: String = "Springfield",
  state: String = "CA",
  tuitionInState: Int? = null,
  tuitionOutOfState: Int? = null,
  publishedPrice: Int? = null,
  netPrice: NetPrice.Reported = NetPrice.OverallAverage(null),
  housingAndFoodOnCampus: Int? = null,
  housingAndFoodOffCampus: Int? = null,
  booksAndSupplies: Int? = null,
  otherExpensesOnCampus: Int? = null,
  otherExpensesOffCampus: Int? = null,
  otherExpensesWithFamily: Int? = null,
  medianDebt: Int? = null,
  offersOnCampusHousing: Boolean? = null,
  meritAid: MeritPractice? = null,
  listStatus: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  /**
   * WHY a field carries no amount, where the fixture wants a status other than
   * the school's own silence -- the publisher's suppression, or a gap of OURS.
   *
   * Both change what this PAGE prints, so both belong here: the page used to
   * call every blank "Not reported by this school", which is a false statement
   * about a named school for either of them (RFC 166 §6).
   */
  absenceStatuses: Map<CostField, AbsenceStatus> = emptyMap(),
  /**
   * Fields this school really published, at [FIXTURE_YEAR_GAP_ACADEMIC_YEAR] and
   * at no other year: the YEAR GAP. The row bears a VALUE -- that is the whole
   * point of it -- and the served year holds no row for the field at all.
   */
  heldOnlyInOtherYear: Map<CostField, Int> = emptyMap(),
): CollegeCost {
  val collegeId = CollegeId(UUID.randomUUID())
  val figures =
    CollegeFigures(
      collegeId = collegeId,
      priceFigures =
        listOf(
          CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD to tuitionInState,
          CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD to tuitionOutOfState,
          CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD to housingAndFoodOnCampus,
          CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD to housingAndFoodOffCampus,
          CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to booksAndSupplies,
          CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD to otherExpensesOnCampus,
          CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD to otherExpensesOffCampus,
          CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD to otherExpensesWithFamily,
        )
          // A YEAR-GAP field has NO row at the served year -- that is exactly
          // what makes it a gap rather than a silence -- so it keeps only its
          // row at the other year.
          .filterNot { (field, _) -> field in heldOnlyInOtherYear }
          .mapNotNull { (field, amountUsd) -> priceRow(collegeId, field, amountUsd, absenceStatuses) } +
          heldOnlyInOtherYear.mapNotNull { (field, amountUsd) -> yearGapRow(collegeId, field, amountUsd) },
      cohortStats = cohortRows(collegeId, control, publishedPrice, netPrice, medianDebt, absenceStatuses),
    )
  // Every derivation below is the SERVICE's own, published for exactly this
  // caller: a fixture may choose only what a school publishes, never re-decide
  // what the read makes of it. The local copies these replace had already
  // drifted. SIX of six now, with no exception: `figureStatusesOf` and
  // `notReportedOf` were the last two re-implemented here, and both were already
  // wrong -- the local status walk had no year-gap arm and no shown/imputed
  // rule, and `entries - reported` is the complement `notReportedOf` says in
  // words is not a complement at all.
  val served = CostBreakdown.servedFiguresOf(figures, applicableTuitionFor(control))
  val tuitionLine = tuitionLineOf(served, control)
  val reported = reportedOf(served, netPrice)
  // RFC 157 D-A is NOT re-applied here. This fixture hands [CollegeCost] the
  // figures as PUBLISHED, exactly as the service does, and the type withholds
  // what this family may not see -- so the page's tests can never pass over a
  // rule the real read no longer makes.
  return CollegeCost(
    collegeId = collegeId,
    name = name,
    city = city,
    state = state,
    control = control,
    listStatus = listStatus,
    publishedStickerCostOfAttendancePerYearUsd = publishedPrice,
    served = served,
    blendedAverageAcademicYear = served.blendedAverageVintage(band = null),
    residencyTiers = residencyTiersOf(served),
    figureStatuses = figureStatusesOf(served, netPrice, (netPrice as? NetPrice.BandSpecific)?.band),
    publishedNetPrice = netPrice,
    medianDebtAtCompletionUsd = medianDebt,
    medianEarnings10yAfterEntryUsd = null,
    reportsBandPricing = netPrice is NetPrice.BandSpecific,
    reportsPublishedTuition = tuitionInState != null || tuitionOutOfState != null,
    publishedNotReported =
      notReportedOf(served, netPrice, offersOnCampusHousing, (netPrice as? NetPrice.BandSpecific)?.band),
    publishedReported = reported,
    breakdown = CostBreakdown.of(served, tuitionLine, offersOnCampusHousing),
    offersOnCampusHousing = offersOnCampusHousing,
    meritAid = meritAid,
    chosen = ChosenLivingPlan.NotChosen,
  )
}

/**
 * One `price_figures` row for [field], at the fixture's one academic year.
 *
 * The canonical address is read off [CostField.figureAddress] rather than
 * spelled out a second time here: that property is the ONE home of which cell
 * answers for which field, and a fixture repeating the concept/residency/
 * arrangement triple would be free to drift from the read it is feeding.
 *
 * A null amount still writes a ROW, absent and reasoned, because that is what
 * the real fill does: the Scorecard writes a cell per column whatever it holds,
 * and the status is how the store says the school reported nothing. A field with
 * no price address at all -- the assumed at-home line, the cohort figures --
 * gets no row, because no publisher has one to write.
 */
private fun priceRow(
  collegeId: CollegeId,
  field: CostField,
  amountUsd: Int?,
  absenceStatuses: Map<CostField, AbsenceStatus> = emptyMap(),
  academicYear: String = FIXTURE_PRICE_ACADEMIC_YEAR,
): PriceFigure? {
  val address = (field.figureAddress as? FigureAddress.Price)?.address ?: return null
  return PriceFigure(
    collegeId = collegeId,
    priceConcept = address.concept,
    residencyBasis = address.residency,
    arrangement = address.arrangement,
    academicYear = academicYear,
    reading = readingOf(amountUsd, absenceStatuses[field] ?: AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
    source = MoneySource.SCORECARD,
    sourceVariable = field.wireName,
    publisherFlag = null,
  )
}

/**
 * One VALUE-BEARING row for [field] at the year the page does NOT serve -- the
 * year gap, and the only row this school has for that field.
 *
 * The served year gets no row at all for it, which is exactly the store's shape:
 * the school published the figure, and we hold it only for another year.
 */
private fun yearGapRow(
  collegeId: CollegeId,
  field: CostField,
  amountUsd: Int,
): PriceFigure? = priceRow(collegeId, field, amountUsd, academicYear = FIXTURE_YEAR_GAP_ACADEMIC_YEAR)

/**
 * The cohort rows behind the two blended figures and the undated debt figure,
 * with the population basis `CanonicalMoneyLoader.mapScorecardStats` gives them.
 *
 * The residency scope follows the CONTROL exactly as the fill does: the
 * Scorecard builds `COSTT4_A` and the `NPT4` family for in-state-rate-paying
 * undergraduates at a public school and for everybody at a private one. Getting
 * that wrong here would let a page test pass against a basis no row carries.
 *
 * Median debt is `undated`: no source we hold dates it, and a fixture that gave
 * it a year would let the page print one.
 */
private fun cohortRows(
  collegeId: CollegeId,
  control: CollegeControl,
  publishedPrice: Int?,
  netPrice: NetPrice.Reported,
  medianDebt: Int?,
  absenceStatuses: Map<CostField, AbsenceStatus>,
): List<CohortMoneyStat> {
  val blendScope =
    if (control is CollegeControl.Public) CohortResidencyScope.IN_STATE_RATE_PAYING else CohortResidencyScope.ALL
  return listOf(
    cohortRow(
      collegeId = collegeId,
      field = CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
      residencyScope = blendScope,
      incomeBand = null,
      vintage = FIXTURE_BLENDED_ACADEMIC_YEAR,
      amountUsd = publishedPrice,
      absent = absenceStatuses[CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD],
    ),
    cohortRow(
      collegeId = collegeId,
      field = CostField.NET_PRICE,
      residencyScope = blendScope,
      incomeBand = (netPrice as? NetPrice.BandSpecific)?.band,
      vintage = FIXTURE_BLENDED_ACADEMIC_YEAR,
      amountUsd = netPrice.amount,
      absent = absenceStatuses[CostField.NET_PRICE],
    ),
    cohortRow(
      collegeId = collegeId,
      field = CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      residencyScope = CohortResidencyScope.ALL,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      amountUsd = medianDebt,
      absent = absenceStatuses[CostField.MEDIAN_DEBT_AT_COMPLETION_USD],
    ),
  )
}

/**
 * One `cohort_money_stats` row for [field]; the store's value is `NUMERIC`, so
 * whole dollars go in as a double.
 *
 * The measure, the population and the aid scope are read off
 * [CostField.figureAddress] -- the same one home [priceRow] reads -- rather than
 * typed here: three hand-written cohort addresses were free to drift from the
 * read they feed, and a drifted one writes a row the page's read never finds.
 * The scope and the vintage stay the fixture's own, because they are facts about
 * this college and this source rather than about where a field lives.
 */
private fun cohortRow(
  collegeId: CollegeId,
  field: CostField,
  residencyScope: CohortResidencyScope,
  incomeBand: IncomeBand?,
  vintage: String,
  amountUsd: Int?,
  absent: AbsenceStatus? = null,
): CohortMoneyStat {
  val address =
    requireNotNull((field.figureAddress as? FigureAddress.Cohort)?.address) {
      "no `cohort_money_stats` row answers for [${field.wireName}], so this fixture cannot write one for it"
    }
  return CohortMoneyStat(
    collegeId = collegeId,
    measure = address.measure,
    population = address.population,
    residencyScope = residencyScope,
    aidScope = address.aidScope,
    incomeBand = incomeBand,
    vintage = vintage,
    reading =
      amountUsd?.let { FigureReading.Present(it.toDouble(), ValueBearingStatus.REPORTED) }
        ?: FigureReading.Absent(absent ?: AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
    source = MoneySource.SCORECARD,
    sourceVariable = address.measure.value,
    publisherFlag = null,
  )
}

/**
 * An amount the school reported, or the absence [absent] gives the reason for.
 *
 * The reason DEFAULTS to the school's own silence, which is what these fixtures
 * mostly build, and is a parameter because the other absences are not the
 * school's: the publisher's suppression and a gap of OURS change what this PAGE
 * prints, not only what a coach says (RFC 166 §6).
 */
private fun readingOf(
  amountUsd: Int?,
  absent: AbsenceStatus = AbsenceStatus.NOT_REPORTED_BY_INSTITUTION,
): FigureReading<Int> =
  amountUsd?.let { FigureReading.Present(it, ValueBearingStatus.REPORTED) }
    ?: FigureReading.Absent(absent)

/**
 * A cost profile over [colleges].
 *
 * The comparison basis is built by [ComparisonBasis.of] rather than supplied,
 * so the below-two-colleges rule stays the domain's: a fixture cannot hand the
 * page a comparison the coach would never have been given.
 */
fun costProfile(
  colleges: List<CollegeCost>,
  moneyProfile: MoneyProfileStatuses = UNANSWERED_MONEY,
): CollegeCostProfile =
  CollegeCostProfile(
    colleges = colleges,
    unknownCollegeIds = emptyList(),
    moneyProfile = moneyProfile,
    ingestYear = 2026,
    comparisonBasis = ComparisonBasis.of(colleges, moneyProfile),
  )

/** A merit-aid section with its own CDS citation, the second source the page cites separately. */
fun meritFixture(
  collegeName: String,
  freshmen: Int? = 1000,
  recipients: Int? = 250,
  averageAid: Int? = 12000,
  sourceYear: Int = 2024,
): MeritPractice =
  MeritPractice(
    fullTimeFreshmen = freshmen,
    nonNeedMeritRecipients = recipients,
    averageNonNeedAid = averageAid,
    source =
      CdsCitation(
        collegeName = collegeName,
        sourceYear = sourceYear,
        url = "https://example.test/cds.pdf",
        archiveUrl = null,
      ),
  )
