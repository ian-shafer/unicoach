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
import ed.unicoach.coaching.costs.reportedOf
import ed.unicoach.coaching.costs.tuitionLineOf
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.web.report.CostReportOutcome
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.MissReason
import java.time.Instant
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
 * One school's cost facts, assembled the way `CollegeCostService` assembles
 * them: the published columns go onto a real [College] row, and the breakdown,
 * the reported set and the tuition line are then COMPUTED by the domain
 * ([CostBreakdown.of]) rather than hand-written here.
 *
 * That matters: a hand-built breakdown could carry a total the parts do not
 * support, and the page's whole job is to print what the domain computed. The
 * fixture may only choose what a school publishes.
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
): CollegeCost {
  val college =
    collegeRow(
      name = name,
      city = city,
      state = state,
      tuitionInState = tuitionInState,
      tuitionOutOfState = tuitionOutOfState,
      publishedPrice = publishedPrice,
      housingAndFoodOnCampus = housingAndFoodOnCampus,
      housingAndFoodOffCampus = housingAndFoodOffCampus,
      booksAndSupplies = booksAndSupplies,
      otherExpensesOnCampus = otherExpensesOnCampus,
      otherExpensesOffCampus = otherExpensesOffCampus,
      otherExpensesWithFamily = otherExpensesWithFamily,
      medianDebt = medianDebt,
    )
  // Both derivations are the SERVICE's own, published for exactly this caller:
  // a fixture may choose only what a school publishes, never re-decide what the
  // read makes of it. The two local copies these replace had already drifted.
  val tuitionLine = tuitionLineOf(college, control)
  val reported = reportedOf(college, netPrice)
  // RFC 157 D-A is NOT re-applied here. This fixture hands [CollegeCost] the
  // figures as PUBLISHED, exactly as the service does, and the type withholds
  // what this family may not see -- so the page's tests can never pass over a
  // rule the real read no longer makes.
  return CollegeCost(
    collegeId = college.id,
    name = name,
    city = city,
    state = state,
    control = control,
    listStatus = listStatus,
    publishedStickerCostOfAttendancePerYearUsd = publishedPrice,
    tuitionAndFeesInStatePerYearUsd = tuitionInState,
    tuitionAndFeesOutOfStatePerYearUsd = tuitionOutOfState,
    publishedNetPrice = netPrice,
    medianDebtAtCompletionUsd = medianDebt,
    medianEarnings10yAfterEntryUsd = null,
    reportsBandPricing = netPrice is NetPrice.BandSpecific,
    reportsPublishedTuition = tuitionInState != null || tuitionOutOfState != null,
    publishedNotReported = CostField.entries.filterNot { it in reported },
    publishedReported = reported,
    breakdown = CostBreakdown.of(college, tuitionLine, offersOnCampusHousing),
    offersOnCampusHousing = offersOnCampusHousing,
    meritAid = meritAid,
    chosen = ChosenLivingPlan.NotChosen,
  )
}

/** An in-memory `colleges` row carrying only the cost columns these tests are about (the `IncomeBandTest` precedent). */
@Suppress("LongParameterList")
private fun collegeRow(
  name: String,
  city: String,
  state: String,
  tuitionInState: Int?,
  tuitionOutOfState: Int?,
  publishedPrice: Int?,
  housingAndFoodOnCampus: Int?,
  housingAndFoodOffCampus: Int?,
  booksAndSupplies: Int?,
  otherExpensesOnCampus: Int?,
  otherExpensesOffCampus: Int?,
  otherExpensesWithFamily: Int?,
  medianDebt: Int?,
): College =
  College(
    id = CollegeId(UUID.randomUUID()),
    version = 1,
    ipedsUnitId = 1,
    opeid = null,
    name = name,
    city = city,
    state = state,
    region = null,
    locale = null,
    latitude = null,
    longitude = null,
    control = 2,
    undergradEnrollmentHeadcount = null,
    admissionRateShare = null,
    satAverageEquivalentScore = null,
    costOfAttendancePerYearUsd = publishedPrice,
    netPricePerYearUsd = null,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = tuitionInState,
    tuitionAndFeesOutOfStatePerYearUsd = tuitionOutOfState,
    completionRate150pct4yrShare = null,
    medianEarnings10yAfterEntryUsd = null,
    medianDebtAtCompletionUsd = medianDebt,
    housingAndFoodOnCampusPerYearUsd = housingAndFoodOnCampus,
    housingAndFoodOffCampusPerYearUsd = housingAndFoodOffCampus,
    booksAndSuppliesPerYearUsd = booksAndSupplies,
    otherExpensesOnCampusPerYearUsd = otherExpensesOnCampus,
    otherExpensesOffCampusPerYearUsd = otherExpensesOffCampus,
    otherExpensesWithFamilyPerYearUsd = otherExpensesWithFamily,
    pellShare = null,
    website = null,
    aliases = emptyList(),
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

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
