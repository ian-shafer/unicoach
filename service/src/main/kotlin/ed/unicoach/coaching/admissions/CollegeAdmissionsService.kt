package ed.unicoach.coaching.admissions

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.common.util.Share
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AdmissionFactor
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeAdmissionFactors
import ed.unicoach.db.models.CollegeDeadline
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CollegeMeritAid
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * What one school reported about the money it hands out for something other
 * than financial need (RFC 148 D4).
 *
 * The denominator is the whole design constraint. The CDS reports the count of
 * freshmen with NO financial need who were given non-need aid, and the average
 * of those offers -- but it reports no count of no-need freshmen. The only
 * population figure in the section is ALL degree-seeking first-time full-time
 * freshmen, which is what [fullTimeFreshmen] holds. So the share this class
 * computes is, and can only be, a share of ALL full-time freshmen, and
 * [shareOfAllFullTimeFreshmenPct] carries that population in its own name. A
 * share of "freshmen without need" is a statistic nobody measured, and at a
 * school where half the class demonstrates need it would overstate the rate by
 * roughly a factor of two.
 *
 * Three rules follow, and every one of them is about honesty rather than
 * convenience:
 *
 * - The share exists only when BOTH counts are present. A quarter of the corpus
 *   reports no freshman total, so a quarter of it has no share -- reported
 *   missing, never reconstructed from a peer group, a prior cycle or a guess.
 *   [averageNonNeedAid] is an independent fact and may stand alone.
 * - Zero is a real reported value. A school that enrolled 480 freshmen and gave
 *   0 of them non-need aid is making one of the most useful statements the tool
 *   can pass on, so nullability is the ONLY test for missing -- never falsiness.
 * - A missing row is not a zero. It is handled a level up, by naming
 *   [AdmissionsField.MERIT_AID] in `data_availability`.
 * - A row with NO merit measure is not a merit section. [fullTimeFreshmen] is
 *   the share's denominator, not a merit fact: 28 of the seed's 368 rows carry
 *   only that total, and rendering them would put a citation with no merit fact
 *   under it in front of the coach AND keep the school's merit silence out of
 *   `data_availability`, where the coach reads it. [from] returns null for them.
 * - A row reporting ZERO full-time freshmen is the same case. The column allows
 *   `0` and the corpus's own CHECK then forces `0` recipients, so the section
 *   would be two bare zeroes with no share and no sentence; a school with no
 *   freshmen is a broken extraction, not a merit report, and [from] returns
 *   null for it too.
 */
data class MeritPractice(
  /** Every degree-seeking first-time full-time freshman, the only population the section reports. */
  val fullTimeFreshmen: Int?,
  /** Freshmen with no financial need who were given non-need (merit) aid. */
  val nonNeedMeritRecipients: Int?,
  /** Whole US dollars, the school's own reported average of those offers. */
  val averageNonNeedAid: Int?,
  val source: CdsCitation,
) {
  /**
   * The recipients as a percentage of ALL full-time freshmen, to one decimal
   * place, or null when either count is missing. Never rounded up to a whole
   * number and never interpolated; a school with 0 recipients has a real 0.0.
   *
   * A [Share] rather than a bare `Double`: the ratio -> percent conversion, the
   * one-decimal rule and the spoken form are one type, so the number this
   * payload carries and the sentence the coach reads cannot drift apart.
   */
  val shareOfAllFullTimeFreshmen: Share? =
    if (fullTimeFreshmen != null && nonNeedMeritRecipients != null) {
      Share.ofOrNull(part = nonNeedMeritRecipients, whole = fullTimeFreshmen)
    } else {
      null
    }

  /**
   * True when this row is not a merit section: the school reported NEITHER
   * merit measure, or it reported a freshman class of ZERO.
   *
   * [fullTimeFreshmen] deliberately does not count as a measure: it is the
   * share's denominator and says nothing about merit aid, so a row carrying
   * only it is a citation with no fact under it.
   *
   * A total of `0` is in domain
   * (`first_time_full_time_freshmen_headcount >= 0`) and the
   * `no_need_merit_recipients_headcount <= first_time_full_time_freshmen_headcount`
   * check then forces `0`
   * recipients, so such a row renders two bare zeroes with no share and no
   * sentence -- a school with no freshmen at all is an extraction fault, not a
   * merit report. It is ruled on exactly like the denominator-only row: the
   * section is silence, so [AdmissionsField.MERIT_AID] is named in
   * `data_availability` where the coach reads it.
   *
   * Private on purpose. The rule belongs to [from], which returns null for such
   * a row, so the two tools that must agree about a school's merit silence
   * cannot each keep their own copy of the test.
   */
  private val isEmpty: Boolean
    get() = (nonNeedMeritRecipients == null && averageNonNeedAid == null) || fullTimeFreshmen == 0

  companion object {
    /**
     * One stored row as a merit section, or null when the row reports no merit
     * measure at all -- the caller then names [AdmissionsField.MERIT_AID] in
     * `data_availability` instead of rendering an empty citation. The share
     * rule is the constructor's.
     */
    fun from(
      collegeName: String,
      row: CollegeMeritAid,
    ): MeritPractice? =
      MeritPractice(
        fullTimeFreshmen = row.firstTimeFullTimeFreshmenHeadcount,
        nonNeedMeritRecipients = row.noNeedMeritRecipientsHeadcount,
        averageNonNeedAid = row.noNeedMeritAverageUsd,
        source = CdsCitation(collegeName, row.sourceYear, row.sourceUrl, row.archiveUrl),
      ).takeIf { !it.isEmpty }
  }
}

/** One reported row of the C7 grid: the factor and the weight the school gives it. */
data class FactorWeight(
  val factor: AdmissionFactor,
  val rating: FactorRating,
)

/**
 * What a school says it weighs (RFC 148 D6). [weights] holds only the rows the
 * school actually REPORTED: a null column is dropped here rather than rendered,
 * because the grid has an explicit "not considered" rating and NULL means
 * something else entirely -- the school did not answer that row. One school in
 * five leaves the interview row blank, so rendering NULL as "not considered"
 * would manufacture an admissions statement for them.
 */
data class AdmissionFactorGrid(
  val weights: List<FactorWeight>,
  val source: CdsCitation,
) {
  /**
   * True when the school answered no row of the grid at all. Private for the
   * same reason as [MeritPractice]'s: the "a citation with no facts under it is
   * not data" rule lives with [from], not at each call site.
   */
  private val isEmpty: Boolean get() = weights.isEmpty()

  companion object {
    /**
     * One stored grid, or null when the school reported no row of it -- the
     * caller then names [AdmissionsField.ADMISSION_FACTORS] in
     * `data_availability` rather than emitting an empty factor list.
     */
    fun from(
      collegeName: String,
      row: CollegeAdmissionFactors,
    ): AdmissionFactorGrid? =
      AdmissionFactorGrid(
        weights =
          AdmissionFactor.entries.mapNotNull { factor ->
            factor.ratingOf(row)?.let { FactorWeight(factor, it) }
          },
        source = CdsCitation(collegeName, row.sourceYear, row.sourceUrl, row.archiveUrl),
      ).takeIf { !it.isEmpty }
  }
}

/**
 * One application round (RFC 148 D5). [offered] is `NOT NULL` in the corpus and
 * is its most reliable bit: 299 of 1031 rows say false, and a false row is the
 * school stating plainly that it does not run that round. Rendering it as
 * silence would convert the school's own statement into our missing data, so
 * the flag is always emitted. The dates are the opposite -- 60% of rows report
 * no closing date at all -- and are cycle-relative, carrying no year.
 */
sealed interface DeadlineRound {
  val round: ApplicationRound

  /** The school's own reported flag, always emitted -- true or false is the school speaking. */
  val offered: Boolean

  /** A round the school runs. Only this case can carry dates, so a date under
   * a round the school does not run is unrepresentable rather than filtered. */
  data class Offered(
    override val round: ApplicationRound,
    val closing: CdsMonthDay?,
    val notification: CdsMonthDay?,
  ) : DeadlineRound {
    override val offered: Boolean get() = true
  }

  /**
   * A round the school states it does not run. It carries NO dates by
   * construction: all 10 such seed rows that also carry a date are
   * `round=regular` -- a round no US college actually fails to offer -- so the
   * date is a source-parsing artifact, and 8 of the 10 carry only a
   * notification month. The flag is the reported fact; the contradictory date
   * is not one, and this type is where that ruling lives.
   */
  data class NotOffered(
    override val round: ApplicationRound,
  ) : DeadlineRound {
    override val offered: Boolean get() = false
  }

  companion object {
    /** One stored row as a round: the flag decides which case, so nothing downstream re-decides it. */
    fun from(row: CollegeDeadline): DeadlineRound =
      if (row.offered) {
        Offered(row.round, row.closing, row.notification)
      } else {
        NotOffered(row.round)
      }
  }
}

/**
 * A school's application calendar for its latest reported cycle. The rounds are
 * restricted to ONE cycle on purpose: the section carries one citation, and a
 * citation that named 2025-26 over a round read out of the 2024-25 document
 * would misattribute the school's own words. The newest document a school
 * published is its current statement; a round only the older document mentions
 * is not claimed.
 */
data class DeadlineSchedule(
  val rounds: List<DeadlineRound>,
  val source: CdsCitation,
)

/** One college's admissions facts, each section present only when that school reports it. */
data class CollegeAdmissions(
  val collegeId: CollegeId,
  val name: String,
  val city: String,
  val state: String,
  val listStatus: CollegeListEntryStatus,
  val meritAid: MeritPractice?,
  val factors: AdmissionFactorGrid?,
  val deadlines: DeadlineSchedule?,
) {
  /**
   * The sections this school does not report, so the coach says so instead of
   * improvising. DERIVED from the three sections, never passed in: "did this
   * school report this" is one fact, and a hand-built list beside the nullable
   * sections would be a second place to state it -- the exact drift this slice
   * exists to remove. A section is absent here exactly when it is absent above.
   */
  val notReported: List<AdmissionsField>
    get() =
      buildList {
        if (meritAid == null) add(AdmissionsField.MERIT_AID)
        if (factors == null) add(AdmissionsField.ADMISSION_FACTORS)
        if (deadlines == null) add(AdmissionsField.DEADLINES)
      }
}

/** The full admissions read for one student (RFC 148). */
data class CollegeAdmissionsProfile(
  val colleges: List<CollegeAdmissions>,
  val unknownCollegeIds: List<CollegeId>,
)

/**
 * Chat-free composition of the three CDS reference tables RFC 140 landed
 * (RFC 148): the student's active college list, joined to each school's own
 * latest-cycle merit-aid row, factor grid and application rounds. Read-only --
 * this service writes nothing, ever, and the [CollegeAdmissionsChatTool] above
 * it only renders what this returns.
 *
 * - The three reads are BATCHED, one query per table for the whole answer, so
 *   the cost of a fifty-school list is four queries and not one hundred and
 *   fifty.
 * - No cycle year is written down anywhere here. The corpus mixes 2024 and 2025
 *   per table per college, and the DAO resolves the newest per table; a
 *   hardcoded year would silently drop half the corpus.
 * - [collegeIds] filters to a subset of the active list; ids not on the list
 *   are reported in [CollegeAdmissionsProfile.unknownCollegeIds] while known
 *   ones still answer -- best-effort, never all-or-nothing
 *   ([ed.unicoach.coaching.costs.CollegeCostService]'s shape).
 */
class CollegeAdmissionsService(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(CollegeAdmissionsService::class.java)

  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): Result<CollegeAdmissionsProfile> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId, collegeIds) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed. Folding it
      // into `Result.failure` would log a cancelled chat turn as a database
      // fault, tell the model the read failed, and stop the cancellation
      // propagating -- three wrong answers to one correct event.
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
  ): CollegeAdmissionsProfile {
    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val facts = LatestCdsFacts.read(session, selection.selected)

    val colleges =
      selection.map { college, entry ->
        val listStatus = entry.status
        admissionsOf(
          college = college,
          listStatus = listStatus,
          merit = facts.meritById[college.id],
          factors = facts.factorsById[college.id],
          deadlines = facts.deadlinesById[college.id].orEmpty(),
        )
      }

    return CollegeAdmissionsProfile(colleges = colleges, unknownCollegeIds = selection.unknown)
  }

  /**
   * The three latest-cycle CDS reads for one answer, each already indexed by
   * college. ONE construct rather than three copies of
   * `getOrThrow().associateBy` inline in the orchestrator: the batching
   * contract -- one query per table for the whole answer -- becomes a property
   * of this type, [readInSession] reads as "select, fetch, assemble", and a
   * fourth table is one field here instead of a fourth copy of the idiom.
   */
  private data class LatestCdsFacts(
    val meritById: Map<CollegeId, CollegeMeritAid>,
    val factorsById: Map<CollegeId, CollegeAdmissionFactors>,
    val deadlinesById: Map<CollegeId, List<CollegeDeadline>>,
  ) {
    companion object {
      fun read(
        session: SqlSession,
        collegeIds: List<CollegeId>,
      ): LatestCdsFacts =
        LatestCdsFacts(
          meritById =
            CdsAdmissionsDao.listLatestMeritAid(session, collegeIds).getOrThrow().associateBy { it.collegeId },
          factorsById =
            CdsAdmissionsDao.listLatestAdmissionFactors(session, collegeIds).getOrThrow().associateBy { it.collegeId },
          deadlinesById =
            CdsAdmissionsDao.listLatestDeadlines(session, collegeIds).getOrThrow().groupBy { it.collegeId },
        )
    }
  }

  /**
   * Assembles one college. A section is present only when the school reported
   * something under it, and its absence is stated explicitly in
   * [CollegeAdmissions.notReported] rather than left as a silence the model has
   * to interpret. A row of all-null measures counts as unreported: a citation
   * with no facts under it is not data.
   */
  private fun admissionsOf(
    college: College,
    listStatus: CollegeListEntryStatus,
    merit: CollegeMeritAid?,
    factors: CollegeAdmissionFactors?,
    deadlines: List<CollegeDeadline>,
  ): CollegeAdmissions =
    CollegeAdmissions(
      collegeId = college.id,
      name = college.name,
      city = college.city,
      state = college.state,
      listStatus = listStatus,
      // Each factory owns the "a citation with no facts under it is not data"
      // rule for its own section and returns null when it holds; the unreported
      // list is then derived from these three, not restated beside them.
      meritAid = meritOf(college, merit),
      factors = factorsOf(college, factors),
      deadlines = scheduleOf(college, deadlines),
    )

  /**
   * One school's merit section, or null when the stored row reports no merit
   * measure (or an impossible zero freshman class). The suppression is LOGGED
   * rather than silent: the wire is right either way -- the section is absent
   * and named in `data_availability` -- but a cited row that yields no fact is
   * a corpus defect, and an operator can only find it if the run says which
   * college and which cycle it came from.
   */
  private fun meritOf(
    college: College,
    row: CollegeMeritAid?,
  ): MeritPractice? {
    if (row == null) return null
    return MeritPractice.from(college.name, row).also {
      if (it == null) {
        logger.info(
          "cds merit row reports no merit measure -- section suppressed: " +
            "college=[{}] cycle=[{}] first_time_full_time_freshmen_headcount=[{}] source=[{}]",
          college.id.value,
          row.sourceYear,
          row.firstTimeFullTimeFreshmenHeadcount,
          row.sourceUrl,
        )
      }
    }
  }

  /** One school's factor grid, or null when the stored row answers no row of the C7 grid -- logged for the same reason as [meritOf]. */
  private fun factorsOf(
    college: College,
    row: CollegeAdmissionFactors?,
  ): AdmissionFactorGrid? {
    if (row == null) return null
    return AdmissionFactorGrid.from(college.name, row).also {
      if (it == null) {
        logger.info(
          "cds factor row answers no grid row -- section suppressed: college=[{}] cycle=[{}] source=[{}]",
          college.id.value,
          row.sourceYear,
          row.sourceUrl,
        )
      }
    }
  }

  /**
   * The rounds of the newest cycle this school published, in the declared round
   * order. The DAO resolves the latest cycle per round, so a school that
   * dropped a round between cycles could otherwise mix documents under one
   * citation; taking the newest cycle present keeps the section's citation true
   * to every round under it.
   */
  private fun scheduleOf(
    college: College,
    deadlines: List<CollegeDeadline>,
  ): DeadlineSchedule? {
    val current = latestCycleOf(college, deadlines) ?: return null
    // `first()` is safe because [latestCycleOf] returns null rather than an
    // empty list -- the selection owns non-emptiness, so this assembly does not
    // have to re-check it from a distance.
    val citation = current.first().let { CdsCitation(college.name, it.sourceYear, it.sourceUrl, it.archiveUrl) }
    return DeadlineSchedule(
      rounds = current.map { roundOf(college, it) },
      source = citation,
    )
  }

  /**
   * One stored row as a round. A row flagged `offered=false` that nonetheless
   * carries dates is a source contradiction: the flag is the reported fact and
   * the date is not rendered ([DeadlineRound.NotOffered] cannot hold one), but
   * the dropped dates are LOGGED with the college and the round, so the
   * contradiction is auditable instead of vanishing at the mapping site.
   */
  private fun roundOf(
    college: College,
    row: CollegeDeadline,
  ): DeadlineRound {
    if (!row.offered && (row.closing != null || row.notification != null)) {
      logger.warn(
        "cds deadline row is not offered but reports dates -- dates not rendered: " +
          "college=[{}] round=[{}] cycle=[{}] closing=[{}] notification=[{}]",
        college.id.value,
        row.round.value,
        row.sourceYear,
        row.closing,
        row.notification,
      )
    }
    return DeadlineRound.from(row)
  }

  /**
   * The selection half of [scheduleOf]: the rows of the newest cycle this
   * school published, in the declared round order, or null when it published
   * none. Never empty -- a caller may read the cycle off `first()`.
   */
  private fun latestCycleOf(
    college: College,
    deadlines: List<CollegeDeadline>,
  ): List<CollegeDeadline>? {
    val latestYear = deadlines.maxOfOrNull { it.sourceYear } ?: return null
    val (current, earlier) = deadlines.partition { it.sourceYear == latestYear }
    // A round only an OLDER document mentions is dropped so the section's one
    // citation stays true to every round under it -- but to a coach that drop
    // reads exactly like "this school does not run ED1", which is the opposite
    // of the truth. The wire keeps one cycle; the log records what it cost.
    if (earlier.isNotEmpty()) {
      logger.info(
        "college [{}]: [{}] round(s) [{}] last reported in an earlier cycle are not rendered under the [{}] citation",
        college.id.value,
        earlier.size,
        earlier.map { it.round.value },
        latestYear,
      )
    }
    // The DECLARATION order of [ApplicationRound] IS the order a family hears
    // their deadlines in (RFC 148 D5), so `ordinal` is the product ordering here
    // and not an incidental sort. `CollegeAdmissionsChatToolTest` pins the
    // rendered sequence, so reordering that enum fails a test rather than
    // quietly changing the answer.
    return current.sortedBy { it.round.ordinal }
  }
}
