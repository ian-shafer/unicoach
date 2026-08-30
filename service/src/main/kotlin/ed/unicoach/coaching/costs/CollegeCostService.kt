package ed.unicoach.coaching.costs

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
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

    val costs =
      selection.map { college, listStatus ->
        costOf(college, listStatus, moneyProfile, meritById[college.id])
      }

    return CollegeCostProfile(
      colleges = costs,
      unknownCollegeIds = selection.unknown,
      moneyProfile = moneyProfile,
      ingestYear = ingestYearOf(selection.colleges),
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
  ): CollegeCost {
    val netPrice = netPriceOf(college, moneyProfile)
    return CollegeCost(
      collegeId = college.id,
      name = college.name,
      city = college.city,
      state = college.state,
      control = controlOf(college, moneyProfile),
      listStatus = listStatus,
      stickerCostOfAttendancePerYearUsd = college.costOfAttendancePerYearUsd,
      tuitionAndFeesInStatePerYearUsd = college.tuitionAndFeesInStatePerYearUsd,
      tuitionAndFeesOutOfStatePerYearUsd = college.tuitionAndFeesOutOfStatePerYearUsd,
      netPrice = netPrice,
      medianDebtAtCompletionUsd = college.medianDebtAtCompletionUsd,
      medianEarnings10yAfterEntryUsd = college.medianEarnings10yAfterEntryUsd,
      reportsBandPricing = reportsBandPricing(college),
      reportsPublishedTuition = reportsPublishedTuition(college),
      notReported = notReportedOf(college, netPrice),
      // A row with no merit measure under it is a citation with no facts, which
      // is not data: [MeritPractice.from] returns null and the result degrades
      // to no merit sub-object at all, exactly like a school with no row. The
      // rule lives there, so both tools cannot disagree about a school's silence.
      meritAid = merit?.let { MeritPractice.from(college.name, it) },
    )
  }

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

  /** The unreported cost fields, in the shared field vocabulary ([CostField]). */
  private fun notReportedOf(
    college: College,
    netPrice: NetPrice,
  ): List<CostField> =
    buildList {
      if (college.costOfAttendancePerYearUsd == null) add(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD)
      if (college.tuitionAndFeesInStatePerYearUsd == null) add(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD)
      if (college.tuitionAndFeesOutOfStatePerYearUsd == null) add(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD)
      if (netPrice.amount == null) add(CostField.NET_PRICE)
      if (college.medianDebtAtCompletionUsd == null) add(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)
      if (college.medianEarnings10yAfterEntryUsd == null) add(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD)
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
    private val ALL_UNANSWERED =
      MoneyProfileStatuses(
        incomeBandStatus = AnswerStatus.UNANSWERED,
        incomeBand = null,
        residencyStatus = AnswerStatus.UNANSWERED,
        residencyState = null,
      )
  }
}
