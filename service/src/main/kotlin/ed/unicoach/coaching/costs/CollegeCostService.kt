package ed.unicoach.coaching.costs

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CollegeMeritAid
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

/**
 * The net-price answer for one college — the ethos label (RFC 135): the coach
 * can never silently present an overall average as a personal number, because
 * the case says which one it is, and only [BandSpecific] can carry a band —
 * a band on an overall average is unrepresentable. [amount] is null when the
 * college does not report the selected figure (it then also appears in
 * [CollegeCost.notReported]).
 */
sealed interface NetPrice {
  val amount: Int?

  /** The serialized `basis` label — derived from the case, never stored beside it. */
  val basis: String

  /** The student's answered household income band selected the bracket column. */
  data class BandSpecific(
    val band: IncomeBand,
    override val amount: Int?,
  ) : NetPrice {
    override val basis: String get() = "your_income_band"
  }

  /** The band is unanswered or declined; the amount is the all-family average. */
  data class OverallAverage(
    override val amount: Int?,
  ) : NetPrice {
    override val basis: String get() = "overall_average"
  }
}

/**
 * Which published tuition figure applies to this student at a public college,
 * from residency vs the college's state. Carried only by
 * [CollegeControl.Public] — for a private college in-state/out-of-state is not
 * a distinction, and the type makes it uncarryable.
 */
enum class TuitionApplicable(
  val value: String,
) {
  IN_STATE("in_state"),
  OUT_OF_STATE("out_of_state"),

  /** Public college, residency unanswered or declined. */
  UNKNOWN("unknown"),
}

/**
 * Scorecard control (`colleges.control`) as the cost read renders it: the
 * cost-domain shape around the vocabulary, which lives in one home for every
 * module in [InstitutionControl] (RFC 143). The codes are mapped to these
 * cases in [CollegeCostService]; the [label] each case renders is read from
 * [InstitutionControl], never hand-written here, so search and the cost tool
 * cannot drift apart. Tuition applicability lives only on the [Public] case,
 * so a private college cannot carry an in-state price (RFC 135).
 */
sealed interface CollegeControl {
  /** The wire `control` label the coach reads. */
  val label: String

  /** Code 1 — the only case where residency selects a tuition figure. */
  data class Public(
    val tuitionApplicable: TuitionApplicable,
  ) : CollegeControl {
    override val label: String get() = InstitutionControl.PUBLIC.label
  }

  /** Code 2 — one price, no residency distinction. */
  data object PrivateNonprofit : CollegeControl {
    override val label: String get() = InstitutionControl.PRIVATE_NONPROFIT.label
  }

  /** Code 3 — one price, no residency distinction. */
  data object PrivateForProfit : CollegeControl {
    override val label: String get() = InstitutionControl.PRIVATE_FOR_PROFIT.label
  }

  /**
   * A code the Scorecard vocabulary does not define; the label carries the raw
   * code so it stays observable at the wire. It renders through
   * [InstitutionControl.unknownLabel], NOT the total `labelFor`: this case
   * means "outside the vocabulary" by construction, so it must read as unknown
   * for whatever code it holds rather than picking up a recognised phrase if
   * one were ever passed here.
   */
  data class Unrecognized(
    val code: Int,
  ) : CollegeControl {
    override val label: String get() = InstitutionControl.unknownLabel(code)
  }
}

/** One college's cost facts, composed from its list entry, the `colleges` row, and the money profile. */
data class CollegeCost(
  val collegeId: CollegeId,
  val name: String,
  val city: String,
  val state: String,
  val control: CollegeControl,
  val listStatus: CollegeListEntryStatus,
  val stickerCostOfAttendancePerYearUsd: Int?,
  val tuitionAndFeesInStatePerYearUsd: Int?,
  val tuitionAndFeesOutOfStatePerYearUsd: Int?,
  val netPrice: NetPrice,
  val medianDebtAtCompletionUsd: Int?,
  val medianEarnings10yAfterEntryUsd: Int?,
  /** True when the college reports at least one `net_price_per_year_income_qN_usd` bracket column. */
  val reportsBandPricing: Boolean,
  /**
   * True when the college publishes at least one tuition figure — the residency
   * twin of [reportsBandPricing]: an answered residency selects between them,
   * so a college that publishes neither has no upgrade to promise.
   */
  val reportsPublishedTuition: Boolean,
  /** The cost fields this college does not report, so the coach says so instead of improvising. */
  val notReported: List<CostField>,
  /**
   * The cost fields this college DOES carry a figure for -- the positive twin of
   * [notReported], and exactly the set [CollegeCostChatTool] renders for it.
   *
   * Derived in [CollegeCostService] from [CostField.reportedAmountOf], the one
   * primitive that answers "does this college report this field", so no reader
   * repeats the per-field null checks: a [CostField] added to the vocabulary
   * gains its column there and is classified here without a second edit nobody
   * would fail for forgetting.
   *
   * Not the complement of [notReported]: the two on-campus components a
   * no-dorms school suppresses (RFC 149 D-B) are in neither list -- they are
   * inapplicable rather than silent, and they are also not rendered.
   */
  val reported: Set<CostField>,
  /**
   * The published price split by living arrangement (RFC 149), or null when
   * this school reports no component at all. The arithmetic lives in
   * [CostBreakdown], reached from [CollegeCostService.costOf], so the totals
   * are assertable without a JSON round trip.
   */
  val breakdown: CostBreakdown?,
  /**
   * Whether this school offers on-campus housing, from IPEDS `IC.ROOM`
   * (`college_ipeds.offers_housing`) -- null when IPEDS does not say.
   *
   * Three explicit states, never inferred from a null `ROOMBOARD_ON`: offers
   * housing, does NOT offer housing ("this school has no residence halls"), and
   * not reported. It is deliberately NOT a [notReported] entry -- that
   * vocabulary means "this college does not report this SCORECARD cost field",
   * and a school with no dorms is answering, not staying silent.
   */
  val offersOnCampusHousing: Boolean?,
  /**
   * What this school reported about the non-need (merit) money it gives, or
   * null when it reports none (RFC 148 D7). Purely additive: a college without
   * it produces exactly the cost answer it produced before this field existed,
   * and nothing in the cost answer depends on it.
   *
   * Deliberately NOT folded into [notReported], whose [CostField] vocabulary
   * means "this college does not report this SCORECARD cost field". Merit aid
   * is a second source with its own silences, and mixing them would misattribute
   * which source is quiet; `college_admissions_profile` owns that report.
   */
  val meritAid: MeritPractice?,
)

/** The money-profile field statuses echoed with every result, so the coach knows the history. */
data class MoneyProfileStatuses(
  val incomeBandStatus: AnswerStatus,
  val incomeBand: IncomeBand?,
  val residencyStatus: AnswerStatus,
  val residencyState: String?,
)

/**
 * The full cost read for one student (RFC 135). [ingestYear] is the most recent
 * `colleges.updated_at` ingest year among the returned rows (null when
 * [colleges] is empty).
 */
data class CollegeCostProfile(
  val colleges: List<CollegeCost>,
  val unknownCollegeIds: List<CollegeId>,
  val moneyProfile: MoneyProfileStatuses,
  val ingestYear: Int?,
  /**
   * The assumptions a side-by-side holds constant (RFC 151), or NULL below two
   * colleges: a one-school answer is already fully labelled by its per-college
   * keys, and a comparison object on it would invite the coach to narrate a
   * comparison it is not making.
   *
   * See [ComparisonBasis] for why assembling it costs no query.
   */
  val comparisonBasis: ComparisonBasis?,
) {
  /**
   * The in-answer upgrade invitations (RFC 135, RFC 145) for one returned
   * [college] — derived, never stored, and in [PrecisionOffer]'s declaration
   * order, which is the order the coach should raise them: residency first,
   * because it is the cheaper question and the bigger correction (a median
   * $6,300/yr at a public college against ~$1,376 for a middle-band income
   * correction). Empty when this college has no upgrade to promise.
   *
   * The order is the enum's rather than this function's on purpose: filtering
   * [PrecisionOffer.entries] means adding a member in its intended slot is the
   * whole of adding an offer's position, and each member owns the rule for when
   * it applies ([PrecisionOffer.appliesTo]).
   */
  fun precisionOffersFor(college: CollegeCost): List<PrecisionOffer> = PrecisionOffer.entries.filter { it.appliesTo(moneyProfile, college) }
}

/**
 * The upgrade invitations a cost result can carry (RFC 145), declared in the
 * order the coach should raise them — residency first, and that IS the wire
 * order, because [CollegeCostProfile.precisionOffersFor] filters [entries].
 * Each case names the `money_profiles` [field] it would fill and owns the rule
 * for when it is on offer; the sentence the coach may say lives with the
 * rendering, in [ed.unicoach.coaching.costs.CollegeCostChatTool]. So a third
 * upgrade (M4's living arrangement) is a member here plus a copy string there:
 * it cannot compile without deciding its own [appliesTo], and it cannot ship
 * without words.
 *
 * Every rule is keyed off [AnswerStatus.UNANSWERED] rather than off the absence
 * of a value, and that is the whole point: an offer derived from a missing
 * value would re-raise a closed topic on every cost answer, because a decline
 * leaves the value missing too. Keying off the status makes a decline permanent
 * for residency exactly as it already is for the income band. Each rule also
 * requires that this college reports the figure the upgrade would sharpen, so
 * an offer never rests on a college that reports nothing for it — but residency
 * is admitted on EITHER published tuition figure
 * ([CollegeCost.reportsPublishedTuition]), so it is the copy, not the rule, that
 * keeps that offer's promise no wider than the data.
 */
enum class PrecisionOffer(
  /**
   * The wire `field` name — the `update_money_profile` parameter this offer
   * would fill. `CollegeCostChatToolTest` binds these to that tool's own input
   * schema, so a rename there fails here rather than shipping an invitation
   * naming a parameter nothing accepts.
   */
  val field: String,
) {
  /**
   * Residency is on offer only at a public college (a private college has one
   * price, so the question buys nothing there), with residency
   * [AnswerStatus.UNANSWERED], and only when the college publishes tuition for
   * the answer to select.
   *
   * The [TuitionApplicable.UNKNOWN] term is deliberate redundancy, not logic
   * the status leaves undecided: it binds the offer to the `tuition_applicable`
   * label the SAME payload renders, so the coach's cue and its stated
   * justification can never diverge. [AnswerStatus.UNANSWERED] is the authority
   * — UNKNOWN covers unanswered AND declined alike, so keying off it would
   * reopen a declined topic on every cost answer.
   */
  RESIDENCY("residency_state") {
    /**
     * Exhaustive with no `else`, exactly as [CollegeCostService]'s `controlOf`
     * is: a safe cast would fold three sealed cases into one unstated default,
     * so a control added to the vocabulary would silently lose the offer. Here
     * it must fail to compile until it says whether residency selects a
     * tuition figure for it.
     */
    override fun appliesTo(
      moneyProfile: MoneyProfileStatuses,
      college: CollegeCost,
    ): Boolean =
      when (val control = college.control) {
        // The only case with two published prices for residency to choose between.
        is CollegeControl.Public -> {
          control.tuitionApplicable == TuitionApplicable.UNKNOWN &&
            moneyProfile.residencyStatus == AnswerStatus.UNANSWERED &&
            college.reportsPublishedTuition
        }

        // One price each, so the question buys the family nothing.
        CollegeControl.PrivateNonprofit -> {
          false
        }

        CollegeControl.PrivateForProfit -> {
          false
        }

        // Outside the Scorecard vocabulary: we cannot promise which price applies.
        is CollegeControl.Unrecognized -> {
          false
        }
      }
  },

  /** Unchanged from RFC 135: an unanswered band, and a college that reports at least one bracket column. */
  INCOME_BAND("income_band") {
    override fun appliesTo(
      moneyProfile: MoneyProfileStatuses,
      college: CollegeCost,
    ): Boolean = moneyProfile.incomeBandStatus == AnswerStatus.UNANSWERED && college.reportsBandPricing
  },
  ;

  /** True when this upgrade is on offer for [college], given the student's [moneyProfile]. */
  abstract fun appliesTo(
    moneyProfile: MoneyProfileStatuses,
    college: CollegeCost,
  ): Boolean
}

/**
 * Chat-free composition of the pieces RFC 133/134 landed (RFC 135): the
 * student's active college list, their money profile, and each college's cost
 * columns, folded into one [CollegeCostProfile]. Read-only — this service
 * writes nothing, ever.
 *
 * - An absent money-profile row is simply all-unanswered (RFC 134's
 *   NotFoundException-as-absence convention), not an error.
 * - The band -> `net_price_per_year_income_qN_usd` selection stays in its one home,
 *   [IncomeBand.netPriceFor].
 * - [collegeIds] filters to a subset of the active list; ids not on the list
 *   (unknown or another student's) are reported in
 *   [CollegeCostProfile.unknownCollegeIds] while known ones still answer —
 *   best-effort read, never all-or-nothing.
 */
class CollegeCostService(
  private val database: Database,
) {
  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): Result<CollegeCostProfile> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId, collegeIds) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed: a
      // cancelled chat turn must not be logged as a database fault, reported to
      // the model as a read error, or stop propagating (the same rule as
      // [ed.unicoach.coaching.admissions.CollegeAdmissionsService]).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session, extracted so the batching contract above is
   * assertable: a test can hand this a session that counts the statements it
   * prepares and prove that a five-college list costs the same statements as a
   * one-college list. [getForStudent] is this function plus the connection and
   * the `Result` wrapper, and nothing else.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
  ): CollegeCostProfile {
    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val moneyProfile = moneyProfileOf(session, studentId)

    // One extra query for the whole answer, inside the SAME connection as
    // the cost read (RFC 148 D7): batched over the ids already selected,
    // so a fifty-school list still costs one merit read and not fifty.
    val meritById =
      CdsAdmissionsDao
        .listLatestMeritAid(session, selection.selected)
        .getOrThrow()
        .associateBy { it.collegeId }

    // The no-dorms fact (RFC 149 D-B), on the SAME connection and batched over
    // the units already selected -- one IPEDS read for the whole answer, so a
    // fifty-school list still costs one statement here and not fifty. Joined by
    // ipeds_unit_id, which is the natural key both tables carry.
    val offersHousingByUnitId =
      CollegeIpedsDao
        .housingFlagsByIpedsUnitId(session, selection.colleges.map { it.ipedsUnitId })
        .getOrThrow()

    val costs =
      selection.map { college, listStatus ->
        costOf(college, listStatus, moneyProfile, meritById[college.id], offersHousingByUnitId[college.ipedsUnitId])
      }

    return CollegeCostProfile(
      colleges = costs,
      unknownCollegeIds = selection.unknown,
      moneyProfile = moneyProfile,
      ingestYear = ingestYearOf(selection.colleges),
      // Reads the per-college list above, so it is built after it. Why it costs
      // no query of its own is stated once, on [ComparisonBasis].
      comparisonBasis = ComparisonBasis.of(costs, moneyProfile),
    )
  }

  /** RFC 134's fallback convention: an absent money-profile row reads as all-unanswered, not an error. */
  private fun moneyProfileOf(
    session: SqlSession,
    studentId: StudentId,
  ): MoneyProfileStatuses {
    val result = MoneyProfilesDao.findActiveByStudent(session, studentId)
    return when {
      result.isSuccess -> {
        val p = result.getOrThrow()
        requireIntactAnswers(p)
        MoneyProfileStatuses(p.incomeBandStatus, p.incomeBand, p.residencyStatus, p.residencyState)
      }

      result.exceptionOrNull() is NotFoundException -> {
        ALL_UNANSWERED
      }

      else -> {
        throw result.exceptionOrNull()!!
      }
    }
  }

  /**
   * Guards `db/schema/0046`'s two `*_value_iff_answered_check` constraints in
   * code, for BOTH money-profile fields: an answered status with no stored
   * value is row corruption, surfaced as [CorruptPersistedValueException]
   * naming the column and row (the DAO convention,
   * [ed.unicoach.coaching.CoachingService]'s `renderMoneyField` precedent).
   *
   * Residency is audited alongside the band because RFC 145 made its status
   * decision-bearing: a corrupt answered-with-no-state row would otherwise
   * render `tuition_applicable: "unknown"` AND withhold the residency offer
   * that exists to resolve it — the one state the coach cannot talk its way
   * out of. Never folded into an unknown label or a silently missing offer.
   */
  private fun requireIntactAnswers(profile: MoneyProfile) {
    requireStoredValueWhenAnswered(profile.incomeBandStatus, profile.incomeBand, "income_band", profile)
    requireStoredValueWhenAnswered(profile.residencyStatus, profile.residencyState, "residency_state", profile)
  }

  /** One status/value pair, in the shared message shape: the column that is corrupt, and the row it is in. */
  private fun requireStoredValueWhenAnswered(
    status: AnswerStatus,
    storedValue: Any?,
    column: String,
    profile: MoneyProfile,
  ) {
    if (status == AnswerStatus.ANSWERED && storedValue == null) {
      throw CorruptPersistedValueException(
        "null",
        ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
        location = "money_profiles.[$column] (row [${profile.id.value}])",
      )
    }
  }

  /** Assembles one college's [CollegeCost]; every rule lives in its named helper. */
  private fun costOf(
    college: College,
    listStatus: CollegeListEntryStatus,
    moneyProfile: MoneyProfileStatuses,
    merit: CollegeMeritAid?,
    offersOnCampusHousing: Boolean?,
  ): CollegeCost {
    val netPrice = netPriceOf(college, moneyProfile)
    val control = controlOf(college, moneyProfile)
    warnOnHousingContradiction(college, offersOnCampusHousing)
    return CollegeCost(
      collegeId = college.id,
      name = college.name,
      city = college.city,
      state = college.state,
      control = control,
      listStatus = listStatus,
      stickerCostOfAttendancePerYearUsd = college.costOfAttendancePerYearUsd,
      tuitionAndFeesInStatePerYearUsd = college.tuitionAndFeesInStatePerYearUsd,
      tuitionAndFeesOutOfStatePerYearUsd = college.tuitionAndFeesOutOfStatePerYearUsd,
      netPrice = netPrice,
      medianDebtAtCompletionUsd = college.medianDebtAtCompletionUsd,
      medianEarnings10yAfterEntryUsd = college.medianEarnings10yAfterEntryUsd,
      reportsBandPricing = reportsBandPricing(college),
      reportsPublishedTuition = reportsPublishedTuition(college),
      notReported = notReportedOf(college, netPrice, offersOnCampusHousing),
      reported = reportedOf(college, netPrice),
      breakdown = CostBreakdown.of(college, tuitionLineOf(college, control), offersOnCampusHousing),
      offersOnCampusHousing = offersOnCampusHousing,
      // A row with no merit measure under it is a citation with no facts, which
      // is not data: [MeritPractice.from] returns null and the result degrades
      // to no merit sub-object at all, exactly like a school with no row. The
      // rule lives there, so both tools cannot disagree about a school's silence.
      meritAid = merit?.let { MeritPractice.from(college.name, it) },
    )
  }

  /**
   * Says the IPEDS/Scorecard disagreement out loud (RFC 149 D-B): the published
   * figures win and the flag still rides beside them, so a systematic
   * divergence must stay visible rather than merely be absorbed.
   *
   * A named step rather than ten lines inside [costOf], whose own contract is
   * that every rule lives in a helper: the guard, the wording and the evidence
   * are one subject and belong one level down from the composition.
   */
  private fun warnOnHousingContradiction(
    college: College,
    offersOnCampusHousing: Boolean?,
  ) {
    if (!CostBreakdown.publishedOnCampusContradictsFlag(college, offersOnCampusHousing)) return
    logger.warn(
      "college=[{}] ipeds_unit_id=[{}] IPEDS offers_housing=false but the Scorecard publishes on-campus " +
        "figures [{}]; rendering the published on-campus arrangement and reporting the flag beside it",
      college.id.value,
      college.ipedsUnitId,
      publishedOnCampusFieldNames(college),
    )
  }

  /** The on-campus components this college publishes in spite of the flag -- the warning's evidence. */
  private fun publishedOnCampusFieldNames(college: College): List<String> =
    LivingArrangement.ON_CAMPUS.exclusiveComponents
      .filter { it.amountOn(college) != null }
      .map { it.wireName }

  /** The basis selection (RFC 135): an answered band picks its bracket column; anything else is the overall average. */
  private fun netPriceOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): NetPrice {
    val band = moneyProfile.incomeBand.takeIf { moneyProfile.incomeBandStatus == AnswerStatus.ANSWERED }
    return if (band != null) {
      NetPrice.BandSpecific(band, band.netPriceFor(college))
    } else {
      NetPrice.OverallAverage(college.netPricePerYearUsd)
    }
  }

  /**
   * The cost domain's reading of a control: [InstitutionControl] (the one home
   * for the codes themselves) -> [CollegeControl], residency resolved on the
   * public case. Branching on the enum rather than the raw integers keeps the
   * literals 1/2/3 in that one file, and the `when` is exhaustive with no
   * `else` on purpose: a member added to the vocabulary must fail to compile
   * here — the one site that owes it a cost decision — instead of quietly
   * falling through to [CollegeControl.Unrecognized].
   */
  private fun controlOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): CollegeControl =
    when (InstitutionControl.fromCode(college.control)) {
      InstitutionControl.PUBLIC -> CollegeControl.Public(tuitionApplicabilityOf(college, moneyProfile))
      InstitutionControl.PRIVATE_NONPROFIT -> CollegeControl.PrivateNonprofit
      InstitutionControl.PRIVATE_FOR_PROFIT -> CollegeControl.PrivateForProfit
      null -> CollegeControl.Unrecognized(college.control)
    }

  /**
   * Which published tuition figure applies at a public college, from residency
   * vs the college's state. The plain string equality is exact because both
   * sides are already the same normalised vocabulary: USPS two-letter codes —
   * the money profile normalises residency on write
   * ([ed.unicoach.coaching.moneyprofile.MoneyProfileService]'s `parseResidencyState`:
   * trim, uppercase, membership), and `colleges.state` is the ingested
   * Scorecard `STABBR`, which is canonical. Do not add ad-hoc case folding
   * here: a mismatch means a writer skipped normalisation, and hiding it would
   * misprice a family's tuition.
   */
  private fun tuitionApplicabilityOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): TuitionApplicable {
    val residency = moneyProfile.residencyState.takeIf { moneyProfile.residencyStatus == AnswerStatus.ANSWERED }
    return when {
      residency == null -> TuitionApplicable.UNKNOWN
      residency == college.state -> TuitionApplicable.IN_STATE
      else -> TuitionApplicable.OUT_OF_STATE
    }
  }

  /** True when the college reports any bracket column, via the band -> column home ([IncomeBand.netPriceFor]). */
  private fun reportsBandPricing(college: College): Boolean = IncomeBand.entries.any { it.netPriceFor(college) != null }

  /**
   * True when the college publishes at least one tuition figure — the residency
   * upgrade has something to select. EITHER figure admits the offer, not both,
   * and that is deliberate: residency decides WHICH price applies, so the
   * answer is worth having even at a half-reporting college, and a family
   * sorted onto the side this school does not report gets the ordinary
   * `data_availability` answer said plainly — which
   * [ed.unicoach.coaching.costs.CollegeCostChatTool.RESIDENCY_OFFER] promises in
   * words rather than promising a number. Tightening this to `&&` would drop
   * the offer for the majority of families it can still answer.
   */
  private fun reportsPublishedTuition(college: College): Boolean =
    college.tuitionAndFeesInStatePerYearUsd != null || college.tuitionAndFeesOutOfStatePerYearUsd != null

  /**
   * The tuition line for one arrangement (RFC 149 D-C): the published figure
   * this student's residency selects, reusing the existing
   * [TuitionApplicable] decision rather than making a second one.
   *
   * Null -- and so no arrangement total -- in exactly two cases:
   *
   * - residency is unanswered or declined at a PUBLIC college. A total that
   *   silently picked one residency would be a lie, and the payload already
   *   carries the residency [PrecisionOffer] that fixes it.
   * - the applicable figure is not published. A total missing its largest part
   *   is not a total.
   *
   * A private college has ONE price, so there is no residency question to
   * answer there and no `tuition_applicable` label on its result; the in-state
   * column is the one the Scorecard publishes it in. An [CollegeControl.Unrecognized]
   * control gets no line at all: outside the vocabulary we cannot say which
   * price applies, and inventing one is the failure this whole file is against.
   */
  private fun tuitionLineOf(
    college: College,
    control: CollegeControl,
  ): CostLine? {
    val field = applicableTuitionFor(control) ?: return null
    return field.amountOn(college)?.let { CostLine(field, it) }
  }

  /**
   * WHICH published tuition figure applies, decided from the control alone --
   * split out from [tuitionLineOf] because it answers a different question about
   * a different subject: this one is about the school and the student, the caller
   * is about what the school published.
   *
   * Null here means "no figure applies to this reader" -- an unanswered residency
   * at a public college, or a control outside the vocabulary. Null in
   * [tuitionLineOf] can also mean "the applicable figure is not published". Both
   * produce no line and no total, but they are not the same fact, and folding
   * them into one function made the payload's `data_availability` and
   * `precision_offer` answers look like one decision when they are two.
   *
   * Exhaustive with no `else`, exactly as `controlOf` is: a control added to the
   * vocabulary must fail to compile here rather than quietly lose its tuition line.
   */
  private fun applicableTuitionFor(control: CollegeControl): CostField? =
    when (control) {
      is CollegeControl.Public -> {
        when (control.tuitionApplicable) {
          TuitionApplicable.IN_STATE -> CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
          TuitionApplicable.OUT_OF_STATE -> CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD
          TuitionApplicable.UNKNOWN -> null
        }
      }

      // One price, published in the in-state column, and no residency question
      // to answer.
      CollegeControl.PrivateNonprofit -> {
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
      }

      CollegeControl.PrivateForProfit -> {
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
      }

      // Outside the vocabulary we cannot say which price applies, and inventing
      // one is the failure this whole file is against.
      is CollegeControl.Unrecognized -> {
        null
      }
    }

  /**
   * The unreported cost fields, in the shared field vocabulary ([CostField]).
   *
   * [offersOnCampusHousing] is read for one reason only (RFC 149 D-B): at a
   * school with no residence halls AND nothing on-campus published, the two
   * on-campus components are not silence, they are inapplicable -- the school
   * answered by having no dorms. Listing them would tell the coach "this school
   * does not report its on-campus housing cost" when the truth is "there is no
   * on-campus". That answer rides `offers_on_campus_housing` instead.
   *
   * When the school publishes an on-campus figure in spite of the flag, the
   * arrangement is rendered and nothing here is suppressed: a part missing from
   * a rendered arrangement is ordinary silence, and calling a published figure
   * unreported would be false about it either way.
   */
  private fun notReportedOf(
    college: College,
    netPrice: NetPrice,
    offersOnCampusHousing: Boolean?,
  ): List<CostField> {
    val computed = computedAmountsOf(netPrice)

    // The two on-campus components are inapplicable -- not silent -- only at a
    // school the no-dorms flag actually suppresses: one with no residence halls
    // AND nothing on-campus published (RFC 149 D-B). When the school publishes
    // an on-campus figure anyway the arrangement IS rendered, so a part still
    // missing from it is ordinary silence and must be named. The rule is read
    // from [CostBreakdown], the one home for it, so the payload can never render
    // an arrangement it also calls inapplicable. Books and supplies is shared by
    // every arrangement and is never in this set.
    val inapplicable =
      if (CostBreakdown.isOnCampusSuppressed(college, offersOnCampusHousing)) {
        LivingArrangement.ON_CAMPUS.exclusiveComponents
      } else {
        emptySet()
      }

    // Enum declaration order, and every member considered: adding a CostField is
    // one edit (its column in `reportedAmountOf`), not one edit plus a null check here
    // that nothing would have failed for forgetting.
    return CostField.entries.filter { field -> field !in inapplicable && isNotReported(field, college, computed) }
  }

  /**
   * Every field this college carries a figure for, read through the ONE
   * primitive that owns the question ([CostField.reportedAmountOf]).
   *
   * The SAME per-field decision [notReportedOf] makes, read the other way round
   * -- never a second ladder of null checks, and deliberately not
   * `entries - notReported`: the on-campus components suppressed at a no-dorms
   * school are absent from [CollegeCost.notReported] because they are
   * inapplicable, and they carry no figure either, so they belong in neither
   * list. [CostField.reportedAmountOf] underneath is exhaustive, so a field
   * added tomorrow must gain a column there and cannot silently drop out of the
   * figures this call is said to report.
   */
  private fun reportedOf(
    college: College,
    netPrice: NetPrice,
  ): Set<CostField> = CostField.entries.filterNot { isNotReported(it, college, computedAmountsOf(netPrice)) }.toSet()

  /**
   * The fields that are NOT a `colleges` column, and the computed figure that
   * answers for each.
   *
   * [isNotReported] refuses rather than guesses: a new column-less [CostField]
   * that nobody added here fails loudly on the first read -- naming the field
   * and the row -- instead of reporting a computed figure as a silence the
   * college never kept. One table, read by both the silence list and its
   * positive twin, so the two can never disagree about one figure.
   */
  private fun computedAmountsOf(netPrice: NetPrice): Map<CostField, Int?> = mapOf(CostField.NET_PRICE to netPrice.amount)

  /**
   * Whether this college is silent about ONE field -- a question about a field,
   * split out from the list-shaped decisions above it.
   *
   * A field with a column answers from it. A field with no column has nothing
   * to be silent with, so [computed] is the only thing that can answer for it;
   * a MISSING key there is a programming error, not a silence, and is named as
   * one. `containsKey` rather than `?:`, because a present null is the
   * legitimate "the computed figure is not reported" case -- and the message
   * carries the field, the row and what the map did hold, because the stdlib
   * "Key X is missing in the map" says nothing about which cost read produced
   * it.
   */
  private fun isNotReported(
    field: CostField,
    college: College,
    computed: Map<CostField, Int?>,
  ): Boolean =
    when (val reported = field.reportedAmountOf(college)) {
      is ReportedAmount.Column -> {
        reported.amountUsd == null
      }

      ReportedAmount.NoColumn -> {
        require(computed.containsKey(field)) {
          "cost field [${field.wireName}] is not a `colleges` column and no computed figure answers for it: " +
            "college_id=[${college.id.value}] ipeds_unit_id=[${college.ipedsUnitId}] " +
            "computed_fields=[${computed.keys.joinToString(", ") { it.wireName }}]"
        }
        computed[field] == null
      }
    }

  /**
   * The recency the attribution quotes. `colleges.updated_at` is the row's
   * modification time — the last ingest that touched it — used as a proxy for
   * data vintage; it is *not* the Scorecard release year, which we do not
   * store. Taken over the returned rows only, so a subset read may report an
   * older year than the whole list: honest for what was answered. If a
   * non-ingest write ever touches `colleges`, this stops being a vintage at
   * all and the attribution must move to a real ingest column.
   */
  private fun ingestYearOf(colleges: Collection<College>): Int? = colleges.maxOfOrNull { it.updatedAt.atZone(ZoneOffset.UTC).year }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostService::class.java)

    private val ALL_UNANSWERED =
      MoneyProfileStatuses(
        incomeBandStatus = AnswerStatus.UNANSWERED,
        incomeBand = null,
        residencyStatus = AnswerStatus.UNANSWERED,
        residencyState = null,
      )
  }
}
