package ed.unicoach.coaching.costs

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
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
 * Scorecard control (`colleges.control`) as the cost read renders it — the one
 * home for the code -> label vocabulary on the cost service/tool paths
 * (RFC 135; the codes are mapped in [CollegeCostService]). Tuition
 * applicability lives only on the [Public] case, so a private college cannot
 * carry an in-state price.
 */
sealed interface CollegeControl {
  /** The wire `control` label the coach reads. */
  val label: String

  /** Code 1 — the only case where residency selects a tuition figure. */
  data class Public(
    val tuitionApplicable: TuitionApplicable,
  ) : CollegeControl {
    override val label: String get() = "public"
  }

  /** Code 2 — one price, no residency distinction. */
  data object PrivateNonprofit : CollegeControl {
    override val label: String get() = "private_nonprofit"
  }

  /** Code 3 — one price, no residency distinction. */
  data object PrivateForProfit : CollegeControl {
    override val label: String get() = "private_for_profit"
  }

  /** A code the Scorecard vocabulary does not define; the label carries the raw code so it stays observable at the wire. */
  data class Unrecognized(
    val code: Int,
  ) : CollegeControl {
    override val label: String get() = "unknown (control [$code])"
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
  val stickerCostAttendance: Int?,
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val netPrice: NetPrice,
  val medianDebt: Int?,
  val medianEarnings: Int?,
  /** True when the college reports at least one `net_price_qN` bracket column. */
  val reportsBandPricing: Boolean,
  /** The cost fields this college does not report, so the coach says so instead of improvising. */
  val notReported: List<CostField>,
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
   * The in-answer upgrade invitation (RFC 135) for one returned [college] —
   * derived, never stored: true exactly when the income band is
   * [AnswerStatus.UNANSWERED] (never after a decline, so the coach is never
   * cued to reopen a closed topic — the ethos assertion) AND the college
   * reports at least one bracket column (a college with no band data makes no
   * upgrade promise).
   */
  fun precisionOfferFor(college: CollegeCost): Boolean =
    moneyProfile.incomeBandStatus == AnswerStatus.UNANSWERED && college.reportsBandPricing
}

/**
 * Chat-free composition of the pieces RFC 133/134 landed (RFC 135): the
 * student's active college list, their money profile, and each college's cost
 * columns, folded into one [CollegeCostProfile]. Read-only — this service
 * writes nothing, ever.
 *
 * - An absent money-profile row is simply all-unanswered (RFC 134's
 *   NotFoundException-as-absence convention), not an error.
 * - The band -> `net_price_qN` selection stays in its one home,
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
      database.withConnection { session ->
        val entries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
        val moneyProfile = moneyProfileOf(session, studentId)

        val entryByCollegeId = entries.associateBy { it.collegeId }
        val (selected, unknown) = splitSelection(entries, entryByCollegeId, collegeIds)

        val collegeById =
          CollegesDao
            .listByIds(session, selected)
            .getOrThrow()
            .associateBy { it.id }

        val costs =
          selected.map { id ->
            val college =
              collegeById[id] ?: error(
                "invariant broken: active list entry references a college listByIds did not return " +
                  "(the list-entry FK guarantees the colleges row exists): " +
                  "student=[${studentId.value}] collegeId=[${id.value}]",
              )
            costOf(college, entryByCollegeId.getValue(id).status, moneyProfile)
          }

        Result.success(
          CollegeCostProfile(
            colleges = costs,
            unknownCollegeIds = unknown,
            moneyProfile = moneyProfile,
            ingestYear = ingestYearOf(collegeById.values),
          ),
        )
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * One split, one membership predicate: known ids answer, the rest are
   * reported. A null [collegeIds] selects the whole active list; duplicates
   * are read once.
   */
  private fun splitSelection(
    entries: List<CollegeListEntry>,
    entryByCollegeId: Map<CollegeId, CollegeListEntry>,
    collegeIds: List<CollegeId>?,
  ): Pair<List<CollegeId>, List<CollegeId>> =
    if (collegeIds == null) {
      entries.map { it.collegeId } to emptyList()
    } else {
      collegeIds.distinct().partition { it in entryByCollegeId }
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
        requireIntactIncomeBand(p)
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
   * Guards the schema's `money_profiles_income_band_value_iff_answered_check`
   * in code: an answered status with no stored value is row corruption,
   * surfaced as [CorruptPersistedValueException] naming the column and row
   * (the DAO convention, [ed.unicoach.coaching.CoachingService]'s
   * `renderMoneyField` precedent) — never folded into the overall average.
   */
  private fun requireIntactIncomeBand(profile: MoneyProfile) {
    if (profile.incomeBandStatus == AnswerStatus.ANSWERED && profile.incomeBand == null) {
      throw CorruptPersistedValueException(
        "null",
        ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
        location = "money_profiles.income_band (row [${profile.id.value}])",
      )
    }
  }

  /** Assembles one college's [CollegeCost]; every rule lives in its named helper. */
  private fun costOf(
    college: College,
    listStatus: CollegeListEntryStatus,
    moneyProfile: MoneyProfileStatuses,
  ): CollegeCost {
    val netPrice = netPriceOf(college, moneyProfile)
    return CollegeCost(
      collegeId = college.id,
      name = college.name,
      city = college.city,
      state = college.state,
      control = controlOf(college, moneyProfile),
      listStatus = listStatus,
      stickerCostAttendance = college.costAttendance,
      tuitionInState = college.tuitionInState,
      tuitionOutState = college.tuitionOutState,
      netPrice = netPrice,
      medianDebt = college.medianDebt,
      medianEarnings = college.medianEarnings,
      reportsBandPricing = reportsBandPricing(college),
      notReported = notReportedOf(college, netPrice),
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
      NetPrice.OverallAverage(college.netPrice)
    }
  }

  /** The one home for the Scorecard control codes: code -> [CollegeControl], residency resolved on the public case. */
  private fun controlOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): CollegeControl =
    when (college.control) {
      1 -> CollegeControl.Public(tuitionApplicabilityOf(college, moneyProfile))
      2 -> CollegeControl.PrivateNonprofit
      3 -> CollegeControl.PrivateForProfit
      else -> CollegeControl.Unrecognized(college.control)
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

  /** The unreported cost fields, in the shared field vocabulary ([CostField]). */
  private fun notReportedOf(
    college: College,
    netPrice: NetPrice,
  ): List<CostField> =
    buildList {
      if (college.costAttendance == null) add(CostField.STICKER_COST_ATTENDANCE)
      if (college.tuitionInState == null) add(CostField.TUITION_IN_STATE)
      if (college.tuitionOutState == null) add(CostField.TUITION_OUT_STATE)
      if (netPrice.amount == null) add(CostField.NET_PRICE)
      if (college.medianDebt == null) add(CostField.MEDIAN_DEBT)
      if (college.medianEarnings == null) add(CostField.MEDIAN_EARNINGS)
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
