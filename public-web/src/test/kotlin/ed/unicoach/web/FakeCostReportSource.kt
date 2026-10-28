package ed.unicoach.web

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.coaching.costs.BorrowingAtGraduation
import ed.unicoach.coaching.costs.BorrowingCoverage
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
import ed.unicoach.coaching.costs.ProgramFigureProvenance
import ed.unicoach.coaching.costs.ProgramReturn
import ed.unicoach.coaching.costs.ProgramReturnCollege
import ed.unicoach.coaching.costs.ProgramReturnFigure
import ed.unicoach.coaching.costs.ProgramReturnProgram
import ed.unicoach.coaching.costs.ProgramReturnSubject
import ed.unicoach.coaching.costs.ScorecardVariableNames
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.applicableTuitionFor
import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.residencyTiersOf
import ed.unicoach.coaching.costs.figureStatusesOf
import ed.unicoach.coaching.costs.notReportedOf
import ed.unicoach.coaching.costs.publishedPriceOf
import ed.unicoach.coaching.costs.reportedOf
import ed.unicoach.coaching.costs.tuitionLineOf
import ed.unicoach.coaching.testing.AutomaticAwardAnswer
import ed.unicoach.coaching.testing.NationalMeritPublishedAnswer
import ed.unicoach.coaching.testing.PublishedBaseRate
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.ScorecardFieldOfStudyColumns.FieldOfStudyVariable
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AutomaticAwardBandId
import ed.unicoach.db.models.AutomaticAwardId
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CohortMoneyStat
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.LoanTypeBorrowing
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.PublishedCell
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.ValueBearingStatus
import ed.unicoach.family.ClaimView
import ed.unicoach.web.report.CostReportOutcome
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.MissReason
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

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
  /**
   * [token] resolves to [profile]; every other token is not found, as a revoked
   * one would be. A signed-out reader is offered [ClaimView.SIGN_UP], as the
   * real port answers; a signed-in one is offered [signedInClaim].
   */
  data class Live(
    val profile: CollegeCostProfile,
    val token: String = TEST_LIVE_TOKEN,
    val signedInClaim: ClaimView = ClaimView.ASK_TO_CONNECT,
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

  override suspend fun getByShareToken(
    rawToken: String,
    viewer: UserId?,
  ): Result<CostReportOutcome> {
    seen.add(rawToken)
    return when (answer) {
      is FakeReportAnswer.Live -> {
        Result.success(
          if (rawToken == answer.token) {
            CostReportOutcome.Found(answer.profile, if (viewer == null) ClaimView.SIGN_UP else answer.signedInClaim)
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

/**
 * The Family Cost Report for [profile] as the live route serves it: the
 * status and the body (RFC 260).
 *
 * One home for "boot the app, open the share link, read the page", beside the
 * fixtures it renders, so every page test reads the page the same way and a
 * change to how the route is reached is made once. The status is kept for the
 * tests of a page that must refuse itself.
 */
fun reportResponseOf(profile: CollegeCostProfile): Pair<HttpStatusCode, String> {
  lateinit var result: Pair<HttpStatusCode, String>
  testApplication {
    application { testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Live(profile))) }
    val response = client.get("/report?token=$TEST_LIVE_TOKEN")
    result = response.status to response.bodyAsText()
  }
  return result
}

/**
 * The page body alone, for the tests that read a page that rendered -- and the
 * status ASSERTED, not dropped: an error page's body would otherwise reach a
 * test as "the report", and an absence assertion over it would pass for the
 * wrong reason.
 */
fun renderedReportOf(profile: CollegeCostProfile): String {
  val (status, body) = reportResponseOf(profile)
  assertEquals(HttpStatusCode.OK, status, "the report did not render: [$body]")
  return body
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
val FIXTURE_PRICE_YEAR: AcademicYear = AcademicYear(2023)

/** Where a refused fixture cell says it came from -- one home, so every row this file builds names the same place. */
private const val FIXTURE_LOCATION = "the cost-report page fixture"

/** [FIXTURE_PRICE_YEAR] as words, derived rather than a second literal (RFC 170 D14). */
val FIXTURE_PRICE_ACADEMIC_YEAR: String = FIXTURE_PRICE_YEAR.label

/** The vintage the blended averages carry -- a year behind the price list, as the two real sources are. */
val FIXTURE_BLENDED_YEAR: AcademicYear = AcademicYear(2022)

/** [FIXTURE_BLENDED_YEAR] as words, derived for the reason [FIXTURE_PRICE_ACADEMIC_YEAR] is. */
val FIXTURE_BLENDED_ACADEMIC_YEAR: String = FIXTURE_BLENDED_YEAR.label

/**
 * The OTHER academic year a fixture may hold a published figure at, and never
 * the one the page serves (RFC 166 §3): the YEAR GAP -- a figure this school
 * really published, held only outside the year its price is quoted at.
 *
 * Older than [FIXTURE_PRICE_ACADEMIC_YEAR] on purpose, so a row here can never
 * become the served year and turn a gap test into a year-selection test.
 */
val FIXTURE_YEAR_GAP_YEAR: AcademicYear = AcademicYear(2021)

/** [FIXTURE_YEAR_GAP_YEAR] as words, derived for the reason [FIXTURE_PRICE_ACADEMIC_YEAR] is. */
val FIXTURE_YEAR_GAP_ACADEMIC_YEAR: String = FIXTURE_YEAR_GAP_YEAR.label

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
  /**
   * What this school says its own graduates borrowed (RFC 175), or which
   * silence stands in its place -- [BorrowingCoverage.NoFiling] for a school
   * whose Common Data Set we do not hold.
   *
   * The fixture takes the assembled section rather than loose figures because
   * the page renders it through [ed.unicoach.coaching.costs.BorrowingWire], the
   * one home of that copy: a fixture that built its own sentences would let the
   * page's tests pass over words the coach never says.
   */
  borrowing: BorrowingCoverage = BorrowingCoverage.NoFiling,
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
  /**
   * WHICH IPEDS survey published this school's PRICE rows, or null for the
   * Scorecard.
   *
   * A parameter because it is the whole point of the seam: the loader ranks the
   * two IPEDS surveys above the Scorecard, so most real price rows are IPEDS,
   * and the page used to cite the Scorecard for every one of them. The cohort
   * rows stay the Scorecard's, because that is where the blended averages
   * really come from -- one school with two publishers is the ordinary case.
   *
   * The PUBLISHER, and the publisher's own naming home then supplies the column
   * PER FIELD ([publishedPriceOf]) -- never one cell handed to every row. Naming a
   * bare source was wrong for one reason (it kept a [ScorecardVariableNames]
   * column under a survey, the exact pairing RFC 184 exists to close), and
   * naming one cell was wrong for another: it applied the in-state tuition
   * column's name to the books-and-supplies row as well. A [PublishedCell.Survey]
   * cannot be the Scorecard, and every row is then named by the survey's own
   * vocabulary.
   */
  pricePublisher: PublishedCell.Survey? = null,
  /**
   * The publisher of the MEDIAN DEBT row, when the test needs that figure on a
   * tier the Scorecard's own column does not give it.
   *
   * Deliberately synthetic, and the only knob in this file that is: no publisher
   * we load pairs a survey with `GRAD_DEBT_MDN`, so a mixed-tier PAGE is not
   * constructible from real data today -- the page speaks a tier for exactly one
   * field. It exists because "two schools' same figure on two tiers" is the case
   * a grouping header must survive (RFC 191 D4), and a guarantee tested only in
   * a unit suite is a guarantee the renderer never has to keep.
   */
  debtPublisher: PublishedCell.Survey? = null,
  /**
   * What this school PUBLISHES about its automatic scholarships (RFC 239) -- one
   * answer per grid, exactly as `AutomaticAwardService`'s student-free door
   * answers them.
   *
   * The fixture takes the ASSEMBLED answers rather than award rows because the
   * five arms are that service's decision, not this file's: a fixture that
   * classified `award_kind` itself could let the page's tests pass over an arm
   * the read never produces.
   */
  automaticAwards: List<AutomaticAwardAnswer> = emptyList(),
  /**
   * What this school PUBLISHES for a National Merit standing (RFC 247), as
   * `NationalMeritService`'s student-free door answers it -- assembled answers
   * and not rows, for [automaticAwards]' reason: which arm a row is, and
   * whether its price is withheld, is that service's decision.
   */
  nationalMerit: List<NationalMeritPublishedAnswer> = emptyList(),
  /**
   * This school's id, nameable so a caller can build the answers that are KEYED
   * by it ([automaticAwards], [nationalMerit]) against the same school it is describing. A fresh
   * one otherwise, which is what every caller that does not care wants.
   */
  collegeId: CollegeId = CollegeId(UUID.randomUUID()),
): CollegeCost {
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
          .mapNotNull { (field, amountUsd) ->
            priceRow(collegeId, field, amountUsd, absenceStatuses, publisher = pricePublisher)
          } +
          heldOnlyInOtherYear.mapNotNull { (field, amountUsd) ->
            yearGapRow(collegeId, field, amountUsd, publisher = pricePublisher)
          },
      cohortStats = cohortRows(collegeId, control, publishedPrice, netPrice, medianDebt, absenceStatuses, debtPublisher),
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
    blendedAverageAcademicYear = served.blendedAverageVintage(band = null)?.label,
    residencyTiers = residencyTiersOf(served),
    figureStatuses = figureStatusesOf(served, netPrice, (netPrice as? NetPrice.BandSpecific)?.band),
    // The SERVICE's own derivation, exactly like every line around it: the page
    // names the publishers its own figures came from (RFC 177 D5), and a
    // fixture that hand-listed them could not fail for naming a publisher the
    // read never served.
    moneySources = served.servedSourcesOf((netPrice as? NetPrice.BandSpecific)?.band),
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
    // The cost REPORT (RFC 155) renders no aid-policy section; the fake says
    // so explicitly rather than leaving the field to a default that would
    // quietly start rendering one.
    aidPolicy = null,
    // The cost REPORT does render borrowing (RFC 175, D9): the parent-facing
    // artifact is where a federal-only debt figure misleads most.
    borrowing = borrowing,
    // The cost REPORT does render the school's published grid (RFC 239), and
    // nothing about the student: the default is the ordinary school, which has
    // no curated grid and no block.
    automaticAwards = automaticAwards,
    // And its National Merit rows (RFC 247): the default is the ordinary school,
    // which we hold no row for and which prints no block.
    nationalMerit = nationalMerit,
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
  academicYear: AcademicYear = FIXTURE_PRICE_YEAR,
  publisher: PublishedCell.Survey? = null,
): PriceFigure? {
  val address = (field.figureAddress as? FigureAddress.Price)?.address ?: return null
  return PriceFigure(
    collegeId = collegeId,
    priceConcept = address.concept,
    residencyBasis = address.residency,
    arrangement = address.arrangement,
    academicYear = academicYear,
    reading = readingOf(amountUsd, absenceStatuses[field] ?: AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
    // RFC 184: every price row names a real published cell, never a bare source.
    cell = publishedPriceOf(field, publisher, FIXTURE_LOCATION),
    publisherFlag = null,
  )
}

/**
 * One VALUE-BEARING row for [field] at the year the page does NOT serve -- the
 * year gap, and the only row this school has for that field.
 *
 * The served year gets no row at all for it, which is exactly the store's shape:
 * the school published the figure, and we hold it only for another year.
 *
 * It takes the school's [publisher] like every other price row. The gap row is one
 * of THIS school's published rows, so leaving it on the default would name it from
 * the Scorecard's vocabulary while the school's other rows carry a survey's --
 * rebuilding, in the one branch, the cross-publisher pairing RFC 184 closes.
 */
private fun yearGapRow(
  collegeId: CollegeId,
  field: CostField,
  amountUsd: Int,
  publisher: PublishedCell.Survey? = null,
): PriceFigure? = priceRow(collegeId, field, amountUsd, academicYear = FIXTURE_YEAR_GAP_YEAR, publisher = publisher)

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
  debtPublisher: PublishedCell.Survey? = null,
): List<CohortMoneyStat> {
  val blendScope =
    if (control is CollegeControl.Public) CohortResidencyScope.IN_STATE_RATE_PAYING else CohortResidencyScope.ALL
  return listOf(
    cohortRow(
      collegeId = collegeId,
      field = CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
      residencyScope = blendScope,
      incomeBand = null,
      vintage = FIXTURE_BLENDED_YEAR,
      amountUsd = publishedPrice,
      absent = absenceStatuses[CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD],
    ),
    cohortRow(
      collegeId = collegeId,
      field = CostField.NET_PRICE,
      residencyScope = blendScope,
      incomeBand = (netPrice as? NetPrice.BandSpecific)?.band,
      vintage = FIXTURE_BLENDED_YEAR,
      amountUsd = netPrice.amount,
      absent = absenceStatuses[CostField.NET_PRICE],
    ),
    cohortRow(
      collegeId = collegeId,
      field = CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      residencyScope = CohortResidencyScope.ALL,
      incomeBand = null,
      vintage = null,
      amountUsd = medianDebt,
      absent = absenceStatuses[CostField.MEDIAN_DEBT_AT_COMPLETION_USD],
      publisher = debtPublisher,
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
  vintage: AcademicYear?,
  amountUsd: Int?,
  absent: AbsenceStatus? = null,
  publisher: PublishedCell.Survey? = null,
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
    // The Scorecard's own column for this measure -- from the ONE home that
    // names them ([ScorecardVariableNames]), which the :service cost fixtures
    // read too. Typed here as well, the two copies were free to be corrected
    // apart, and a fixture writing a name the tier resolver does not know seeds
    // a row the production read refuses (RFC 179). Built through
    // [PublishedCell.ScorecardCell.of], so this fixture is refused at the same
    // door the read is (RFC 184).
    cell =
      publisher?.let { PublishedCell.Surveyed(it, ScorecardVariableNames.cohortOf(address.measure, incomeBand)) }
        ?: PublishedCell.ScorecardCell.of(
          ScorecardVariableNames.cohortOf(address.measure, incomeBand),
          FIXTURE_LOCATION,
        ),
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
  /** The major answer (RFC 231), which the report source leaves on the profile only behind a grant (RFC 251). */
  programReturn: ProgramReturn? = null,
): CollegeCostProfile =
  CollegeCostProfile(
    colleges = colleges,
    unknownCollegeIds = emptyList(),
    moneyProfile = moneyProfile,
    comparisonBasis = ComparisonBasis.of(colleges, moneyProfile),
    programReturn = programReturn,
  )

/** A merit-aid section with its own CDS citation, the second source the page cites separately. */
fun meritFixture(
  collegeName: String,
  freshmen: Int? = 1000,
  recipients: Int? = 250,
  averageAid: Int? = 12000,
  sourceYear: Int = 2024,
  /** The college's OWN file -- per school, so a link assertion cannot pass on a neighbour's url (RFC 196). */
  url: String = "https://example.test/cds.pdf",
  /** Our mirror. Given a value by the test that asserts the page links the college's file and never this one. */
  archiveUrl: String? = null,
  /**
   * Whether the college's own file was still served when we looked (RFC 200).
   * [SourceUrlStatus.FOUND] by default, because a linked filing is the case
   * every older assertion on this page was written against; the tests that care
   * pass NOT_FOUND or UNKNOWN by name.
   */
  sourceUrlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
): MeritPractice =
  MeritPractice(
    fullTimeFreshmen = freshmen,
    nonNeedMeritRecipients = recipients,
    averageNonNeedAid = averageAid,
    source =
      CdsCitation(
        collegeName = collegeName,
        sourceYear = sourceYear,
        url = url,
        archiveUrl = archiveUrl,
        sourceUrlStatus = sourceUrlStatus,
      ),
  )

/**
 * A borrowing section with its own CDS citation (RFC 175) -- the school's own
 * claim about its own graduating class, cited separately from the Scorecard.
 *
 * [averageDebtUsdByLoanType] and [borrowersByLoanType] are given per loan type
 * because the partial cases are the interesting ones: a school that filed a
 * federal figure and no private one is the case the whole slice exists for.
 */
fun borrowingFixture(
  collegeName: String,
  graduatingClass: Int? = 1000,
  averageDebtUsdByLoanType: Map<LoanType, Int> = mapOf(LoanType.FEDERAL to 20747, LoanType.PRIVATE to 43865),
  borrowersByLoanType: Map<LoanType, Int> = mapOf(LoanType.FEDERAL to 394, LoanType.PRIVATE to 83),
  sourceYear: Int = 2024,
  /**
   * The loan types this filing ANSWERS and we could not read (D7): OUR gap,
   * which the page says per loan type and never as the school's silence.
   */
  notReadLoanTypes: Set<LoanType> = emptySet(),
  /**
   * The same question for a cell that is value-less for someone ELSE's reason
   * -- the publisher withheld it, or the source says it does not apply.
   */
  gapStatusByLoanType: Map<LoanType, FigureStatus> = emptyMap(),
  /** OUR gap at CDS H.401, the denominator every share divides by. */
  graduatingClassGapStatus: FigureStatus? = null,
  /** The college's OWN file -- per school, so a link assertion cannot pass on a neighbour's url (RFC 196). */
  url: String = "https://example.test/cds.pdf",
  /** Our mirror. Given a value by the test that asserts the page links the college's file and never this one. */
  archiveUrl: String? = null,
  /** Whether the college's own file was still served when we looked (RFC 200); only NOT_FOUND drops the anchor. */
  sourceUrlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
): BorrowingCoverage.Reported =
  BorrowingCoverage.Reported(
    figures =
      BorrowingAtGraduation(
        graduatingClass = graduatingClass,
        byLoanType =
          (averageDebtUsdByLoanType.keys + borrowersByLoanType.keys).associateWith { loanType ->
            LoanTypeBorrowing(
              averageDebtUsd = averageDebtUsdByLoanType[loanType],
              borrowers =
                borrowersByLoanType[loanType]?.let { borrowers ->
                  graduatingClass?.let { BorrowerCounts.Counted(borrowers = borrowers, graduatingClass = it) }
                },
            )
          },
        source =
          CdsCitation(
            collegeName = collegeName,
            sourceYear = sourceYear,
            url = url,
            archiveUrl = archiveUrl,
            sourceUrlStatus = sourceUrlStatus,
          ),
      ),
    gapStatusByLoanType =
      gapStatusByLoanType + notReadLoanTypes.associateWith { FigureStatus.NOT_COLLECTED_BY_US },
    graduatingClassGapStatus = graduatingClassGapStatus,
  )

// -----------------------------------------------------------------------------
// The three verbose schools: every repeating block the page can print
// -----------------------------------------------------------------------------

/**
 * The three schools the word budget is measured over: every one of them
 * priced for all three ways of living, with a merit filing and a borrowing
 * filing, so the fixture carries every repeating block the page can print.
 *
 * Shared by `CostReportPageTest` and `CostReportShapeTest` (RFC 260), so the
 * page measured and the page asserted on are one fixture.
 */
fun verboseState(): CollegeCost =
  costFixture(
    name = "Lakeshore State University",
    control = CollegeControl.Public(TuitionApplicable.IN_STATE),
    tuitionInState = 12000,
    tuitionOutOfState = 34000,
    publishedPrice = 28000,
    netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
    housingAndFoodOnCampus = 11000,
    housingAndFoodOffCampus = 13000,
    booksAndSupplies = 1200,
    otherExpensesOnCampus = 2500,
    otherExpensesOffCampus = 2800,
    otherExpensesWithFamily = 3000,
    medianDebt = 21000,
    offersOnCampusHousing = true,
    meritAid = meritFixture("Lakeshore State University"),
    borrowing = borrowingFixture("Lakeshore State University"),
  )

fun verboseCampus(): CollegeCost =
  costFixture(
    name = "Eastbrook University",
    tuitionInState = 30000,
    publishedPrice = 52000,
    netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
    housingAndFoodOnCampus = 10000,
    housingAndFoodOffCampus = 12000,
    booksAndSupplies = 1000,
    otherExpensesOnCampus = 2000,
    otherExpensesOffCampus = 2400,
    otherExpensesWithFamily = 2600,
    medianDebt = 24000,
    offersOnCampusHousing = true,
    meritAid = meritFixture("Eastbrook University"),
    borrowing = borrowingFixture("Eastbrook University"),
  )

fun verbosePrivate(): CollegeCost =
  costFixture(
    name = "Wynnford College",
    tuitionInState = 41000,
    publishedPrice = 60000,
    netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 25000),
    housingAndFoodOnCampus = 12000,
    housingAndFoodOffCampus = 14000,
    booksAndSupplies = 1100,
    otherExpensesOnCampus = 2200,
    otherExpensesOffCampus = 2600,
    otherExpensesWithFamily = 2900,
    medianDebt = 27000,
    offersOnCampusHousing = true,
    meritAid = meritFixture("Wynnford College"),
    borrowing = borrowingFixture("Wynnford College"),
  )

// -----------------------------------------------------------------------------
// RFC 239: a school's published automatic-scholarship grid
// -----------------------------------------------------------------------------

/** The day we read the school's scholarship page -- the read date every grid block cites. */
val GRID_READ_ON: LocalDate = LocalDate.of(2026, 8, 1)

/** The day official scores must be with the school, rendered through the one date seam as `Dec 4, 2026`. */
val GRID_DEADLINE: LocalDate = LocalDate.of(2026, 12, 4)

/** The school's OWN sentence about submitting a score; the page quotes it and composes none of its own. */
const val GRID_REQUIREMENT = "A test score is required for scholarship consideration."

/** Who published the grid, as a family would recognise them on the page. */
const val GRID_PUBLISHER = "Riverside State University Office of Admissions"

/** A band on the SAT's own published scale -- the axis most cases are not about. */
fun sat(
  low: Int,
  high: Int,
): BandScoreAxis = BandScoreAxis.Range(test = AdmissionTest.SAT, low = low, high = high)

/**
 * One published cell. The award id is a placeholder: [gridAward] re-keys every
 * band to the row it hangs off, exactly as the read hands them out, so no test
 * can build a grid whose cells belong to another school's row.
 */
fun gridBand(
  awardName: String = "Presidential",
  score: BandScoreAxis = sat(1200, 1250),
  gpaLow: BigDecimal? = null,
  gpaHigh: BigDecimal? = null,
  awardPerYearUsd: Int? = 6000,
  awardAsPublished: String? = null,
): AutomaticAwardBand =
  AutomaticAwardBand(
    id = AutomaticAwardBandId(UUID.randomUUID()),
    awardId = AutomaticAwardId(UUID.randomUUID()),
    awardName = awardName,
    score = score,
    gpaLow = gpaLow,
    gpaHigh = gpaHigh,
    awardPerYearUsd = awardPerYearUsd,
    awardAsPublished = awardAsPublished,
  )

/** One school's published grid for one entry year and one residency basis, with its cells. */
fun gridAward(
  collegeId: CollegeId,
  bands: List<AutomaticAwardBand> = listOf(gridBand()),
  residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
  awardKind: AutomaticAwardKind = AutomaticAwardKind.SCORE_GRID,
  scoreDeadline: LocalDate? = GRID_DEADLINE,
  scoreDeadlineAsPublished: String? = "Official test scores must be received by December 4.",
): AutomaticAward {
  val id = AutomaticAwardId(UUID.randomUUID())
  return AutomaticAward(
    id = id,
    collegeId = collegeId,
    entryYear = AcademicYear(2027),
    residencyBasis = residencyBasis,
    awardKind = awardKind,
    scoreRequirement = ScoreRequirement.REQUIRED,
    scoreRequirementAsPublished = GRID_REQUIREMENT,
    superscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
    superscoreAsPublished = "Scholarship awards are based on a single test administration.",
    scoreDeadline = scoreDeadline,
    scoreDeadlineAsPublished = scoreDeadlineAsPublished,
    lastSittingConsidered = null,
    publisher = GRID_PUBLISHER,
    sourceUrl = "https://example.test/riverside-scholarships",
    archiveUrl = null,
    checkedOn = GRID_READ_ON,
    expiresOn = LocalDate.of(2027, 6, 30),
    bands = bands.map { it.copy(awardId = id) },
  )
}

/**
 * The answer the report actually receives: a score grid, which is what
 * `AutomaticAwardService`'s student-free door returns.
 *
 * IT CANNOT BE HANDED A STANDING AT ALL. It used to take one, so that a test
 * could prove the page printed nothing from a full one; the standing now lives
 * on the student-scoped `StudentAwardAnswer` that only the chat door builds,
 * so the privacy ruling is a property of the TYPE this page is handed rather
 * than of this renderer's restraint.
 */
fun scoreBands(award: AutomaticAward): AutomaticAwardAnswer =
  AutomaticAwardAnswer.ScoreBands(
    collegeId = award.collegeId,
    award = award,
    lastUsableSitting = null,
    boundBy = emptySet(),
    closedSittings = emptyList(),
    quotedCutOff = null,
  )

// -----------------------------------------------------------------------------
// RFC 247: the National Merit scholarships a school publishes
// -----------------------------------------------------------------------------

/** Who published a school's National Merit row, and the day we read it, rendered as `Sep 25, 2026`. */
const val MERIT_PUBLISHER = "Riverside State University Office of Scholarships"
val MERIT_READ_ON: LocalDate = LocalDate.of(2026, 9, 25)

/** Who published the base rate, and the day we read it, rendered as `Sep 1, 2026` -- a different day, so neither date can pass for the other. */
const val MERIT_SPONSOR = "Example Merit Corporation"
val MERIT_BASE_RATE_READ_ON: LocalDate = LocalDate.of(2026, 9, 1)

/** The first-choice deadline, rendered through the date seam as `May 1, 2027`. */
val MERIT_FIRST_CHOICE_BY: LocalDate = LocalDate.of(2027, 5, 1)
const val MERIT_FIRST_CHOICE_WORDS = "Name us your first choice with NMSC."

/** A school's own sentence for what it pays, in figures. */
const val MERIT_WORDS = "Finalists receive \$17,500 a year for four years."

/** The base rate every priced row in one read carries: 15,000 of 1,200,000 entrants, said as 1.3%. */
val meritBaseRate: PublishedBaseRate =
  PublishedBaseRate(
    competitionYear = 2028,
    funnel =
      NationalMeritFunnel(
        testTakers = 3_500_000,
        metEntryRequirements = 1_200_000,
        highScorersScreened = 50_000,
        commended = 34_000,
        semifinalists = 16_000,
        finalists = 15_000,
        winners = 6_700,
        asPublished = "About 1.2 million entrants; some 15,000 reach Finalist.",
      ),
    publisher = MERIT_SPONSOR,
    checkedOn = MERIT_BASE_RATE_READ_ON,
  )

/** One school's paying claim, with the shape most of the corpus's rows take. */
fun payingClaim(
  guarantee: AwardGuarantee = AwardGuarantee.AUTOMATIC,
  standing: QualifyingStanding = QualifyingStanding.FINALIST,
  asPublished: String = MERIT_WORDS,
  awardName: String? = null,
  perYearUsd: Int? = 17_500,
  totalUsd: Int? = 70_000,
  years: Int? = 4,
  firstChoice: FirstChoiceRule? = FirstChoiceRule.Required(deadline = MERIT_FIRST_CHOICE_BY, asPublished = MERIT_FIRST_CHOICE_WORDS),
): NationalMeritClaim.Paying =
  NationalMeritClaim.Paying(
    guarantee = guarantee,
    standing = standing,
    asPublished = asPublished,
    awardName = awardName,
    perYearUsd = perYearUsd,
    totalUsd = totalUsd,
    years = years,
    firstChoice = firstChoice,
    applyDeadline = null,
  )

/** One stored row, built in memory exactly as `NationalMeritAwardsDao` hands it out. */
fun meritAward(
  collegeId: CollegeId,
  claim: NationalMeritClaim = payingClaim(),
  residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
): NationalMeritAward =
  NationalMeritAward(
    id = NationalMeritAwardId(UUID.randomUUID()),
    collegeId = collegeId,
    entryYear = AcademicYear(2027),
    residencyBasis = residencyBasis,
    claim = claim,
    publisher = MERIT_PUBLISHER,
    sourceUrl = "https://example.test/riverside-national-merit",
    archiveUrl = null,
    checkedOn = MERIT_READ_ON,
    expiresOn = LocalDate.of(2027, 7, 31),
  )

/**
 * The answer the report receives for one stored row, decided by
 * [NationalMeritPublishedAnswer.of] -- the one home of the arm rule -- so the
 * page is only ever tested on answers the service can produce. By default on
 * the day the row was read, inside its belief window, and priced with
 * [meritBaseRate]; a null [baseRate] withholds the price.
 */
fun meritAnswer(
  award: NationalMeritAward,
  today: LocalDate = award.checkedOn,
  baseRate: PublishedBaseRate? = meritBaseRate,
): NationalMeritPublishedAnswer = NationalMeritPublishedAnswer.of(award, today, baseRate)

// -----------------------------------------------------------------------------
// RFC 251: the figures by field of study woven into the report
// -----------------------------------------------------------------------------

/** One Scorecard field-of-study variable's provenance, with the cohort it publishes for. */
fun fieldOfStudyProvenance(variable: FieldOfStudyVariable): ProgramFigureProvenance =
  requireNotNull(ProgramFigureProvenance.ofAgreeingCohort(variable, variable.cohortFirstYear, variable.cohortLastYear))

/** One matched program with the figures it holds, by measure. */
fun programFixture(
  cip: String,
  title: String,
  vararg held: ProgramReturnFigure.Held,
): ProgramReturnProgram = ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode(cip), title, held.associateBy { it.measure })

/** One school's slice of a subject: its matched programs, or none. */
fun programsAt(
  college: CollegeCost,
  vararg programs: ProgramReturnProgram,
): ProgramReturnCollege =
  if (programs.isEmpty()) {
    ProgramReturnCollege.NoProgram(college.collegeId, college.name)
  } else {
    ProgramReturnCollege.WithPrograms(college.collegeId, college.name, programs.first(), programs.drop(1))
  }

/** One stored subject across the schools on the page. */
fun subjectFixture(
  name: String,
  vararg colleges: ProgramReturnCollege,
): ProgramReturnSubject = ProgramReturnSubject(name.lowercase(), name, colleges.first(), colleges.drop(1))

/** The major answer (RFC 231) over [subjects], as the report source hands it to the page. */
fun programReturnOf(vararg subjects: ProgramReturnSubject): ProgramReturn = ProgramReturn(subjects.first(), subjects.drop(1))

/** A matched program every one of whose figures the publisher withheld. */
fun allWithheldProgram(
  cip: String,
  title: String,
): ProgramReturnProgram =
  programFixture(
    cip,
    title,
    *ScorecardFieldOfStudyColumns.VARIABLES.map { ProgramReturnFigure.Suppressed(fieldOfStudyProvenance(it)) }.toTypedArray(),
  )
