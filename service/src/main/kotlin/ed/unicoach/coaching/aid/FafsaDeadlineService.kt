package ed.unicoach.coaching.aid

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.FafsaFilingDeadlineDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsStateRegion
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.FafsaFilingCycle
import ed.unicoach.db.models.FafsaFilingDeadline
import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.db.models.PublishedDeadline
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * Why a read can name no deadline at all (RFC 230). Three genuinely different
 * facts, and the two doors say a different sentence for each.
 */
enum class NoDeadlinesReason {
  /**
   * The table is empty. "The seeded dates are out of date" would be a FALSE
   * statement about a corpus that does not exist, so this arm says what is
   * actually so.
   */
  EMPTY,

  /**
   * Every row belongs to a cycle BEFORE the one being filed. The rows are
   * healthy and unexpired; serving last cycle's dates would be the one failure
   * this read exists to prevent.
   */
  SUPERSEDED_CYCLE,

  /**
   * The live cycle's rows are no longer believed: `expires_on` has passed. "The
   * seeded dates are out of date" is TRUE here.
   */
  EXPIRED,
}

/**
 * The one answer both FAFSA doors render (RFC 230). Two cases, because with no
 * live answer there are no dates to answer with at all.
 */
sealed interface FafsaDeadlines {
  /**
   * There is no live answer, and WHICH of the three ways that is so.
   *
   * It carries the cycles the publishers labelled what we held -- EMPTY for an
   * empty table -- and no `asOf`, no window and no jurisdiction list: a refusal
   * exists to withhold dates, and a shape that forced today onto it would leave
   * only the renderer's discipline between that date and the wire.
   */
  data class NoDeadlines(
    /**
     * Distinct cycles we hold, oldest first -- the WHOLE table's cycles on
     * every arm, never one arm's subset of them; empty for
     * [NoDeadlinesReason.EMPTY], where there are no rows to hold a cycle.
     */
    val heldAwardYears: List<AcademicYear>,
    val reason: NoDeadlinesReason,
  ) : FafsaDeadlines

  /** The live set is current, so the dates are real. */
  data class Resolved(
    /** Today, in the caller's device zone (fallback [FafsaDeadlineService.FALLBACK_ZONE]). */
    val asOf: LocalDate,
    /** The cycle the clock names; NOT a claim the other cycle is shut (D22). */
    val filingYear: AcademicYear,
    /** Non-nullable: a window that exists is one we are inside. */
    val window: FafsaDeadlineWindow,
    /** The federal jurisdiction's live rows, in DAO order. */
    val federal: List<FafsaDeadline>,
    /** One entry per served state, region-attached. */
    val states: List<StateDeadlines>,
    /**
     * The served jurisdictions (D13) we hold nothing for, in `us_states.name`
     * order; never a silent gap.
     *
     * TYPED, the same [DeadlineJurisdiction.State] a served state carries two
     * fields above: the two halves of one answer are then comparable without a
     * convention, and a door cannot mis-key a gap with a state name or a
     * lowercased code. It is total: `us_states` publishes exactly the 59 codes
     * [DeadlineJurisdiction.State] admits, so nothing is dropped in the typing.
     */
    val missingJurisdictions: List<DeadlineJurisdiction.State>,
  ) : FafsaDeadlines
}

/** The live window: which cycles it names, and the EARLIEST expiry any live row carries. */
data class FafsaDeadlineWindow(
  /** Distinct cycles present in the live set, oldest first -- several present are all named. */
  val cycles: List<AcademicYear>,
  val expiresOn: LocalDate,
)

/** One live row, with its own cycle named on it (D23). */
data class FafsaDeadline(
  val awardYear: AcademicYear,
  val program: String,
  val event: AidDeadlineEvent,
  val deadline: PublishedDeadline,
  val publisher: String,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,
)

/** One served state: its typed USPS code, its name, its region, and its live-cycle rows in DAO order. */
data class StateDeadlines(
  val jurisdiction: DeadlineJurisdiction.State,
  val name: String,
  val region: String,
  val deadlines: List<FafsaDeadline>,
)

/**
 * The one read the public `/fafsa` page and the coach both render (RFC 230), so
 * the two doors cannot disagree (D4). It reads the seeded
 * `fafsa_filing_deadlines` rows whole, decides the live set per jurisdiction
 * (D23), refuses with one of three named arms when there is no live answer, and
 * returns the region grouping and the jurisdictions we hold nothing for.
 *
 * IT RENDERS NOTHING. Every value is typed; not one English sentence is
 * authored in this file. The words a family reads live in the two doors.
 *
 * The clock is an [InstantSource] and not a [java.time.Clock], for the
 * [TestTimelineService] reason: the ZONE is per request, supplied by the
 * device, so a type that carries one would advertise a zone it discards. A null
 * zone -- the device did not say -- falls back to [FALLBACK_ZONE] in ONE home.
 * `LocalDate.now` appears nowhere in this file.
 */
class FafsaDeadlineService(
  private val database: Database,
  private val clock: InstantSource = InstantSource.system(),
) {
  suspend fun get(zone: ZoneId? = null): Result<FafsaDeadlines> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, zone) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed
      // (CollegeAdmissionsService's rule).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session, extracted so the batching contract is
   * assertable: the deadline read plus, on the answering path only, the region
   * read. [get] is this function plus the connection and the `Result` wrapper,
   * and nothing else.
   *
   * THE NUMBER IS ASSERTED, in `FafsaDeadlineServiceTest`, against
   * [READ_STATEMENTS]. The three refusal arms return after the deadline read
   * and never pay for the region read.
   */
  internal fun readInSession(
    session: SqlSession,
    zone: ZoneId?,
  ): FafsaDeadlines {
    val today = LocalDate.ofInstant(clock.instant(), zone ?: FALLBACK_ZONE)
    val filingYear = FafsaFilingCycle.currentFilingYear(today)
    val rows = FafsaFilingDeadlineDao.listAll(session).getOrThrow()

    if (rows.isEmpty()) {
      return refuse(NoDeadlinesReason.EMPTY, today, filingYear, emptyList(), earliestExpiry = null)
    }

    val newestCycleHeld = rows.maxOf { it.awardYear }
    if (newestCycleHeld < filingYear) {
      return refuse(NoDeadlinesReason.SUPERSEDED_CYCLE, today, filingYear, cyclesOf(rows), earliestExpiry = null)
    }

    // THE LIVE SET (D23): for each jurisdiction, only its newest cycle's rows.
    // Rows are committed only when verified (D9), so "newest cycle we VERIFIED
    // per jurisdiction" is, in the store, "newest award_year per jurisdiction".
    val live = liveRows(rows)
    val expiresOn = live.minOf { it.expiresOn }
    // PAST the expiry day, not ON it: a row is believed THROUGH its `expires_on`. That is
    // TestTimelineService's own predicate for the same question -- `if (expiry == null || today > expiry)`
    // -- and what the calendar schema states: "Past expires_on the coach REFUSES to name a date"
    // (db/schema/create-admission-test-calendar.sql). `>=` here would retire every row a day early.
    if (today > expiresOn) {
      // cyclesOf(ROWS), not of the live set: [FafsaDeadlines.NoDeadlines.heldAwardYears]
      // is the cycles we HOLD, and that is one question with one answer on all
      // three arms. A refusal that named only the live subset would answer a
      // different question under the same field name.
      return refuse(NoDeadlinesReason.EXPIRED, today, filingYear, cyclesOf(rows), earliestExpiry = expiresOn)
    }

    return resolve(session, today, filingYear, live, expiresOn)
  }

  /**
   * The refusal, and the server-side record of what decided it.
   *
   * AN ABSENT ANSWER IS AN OPERATOR PROBLEM, not a family one: an empty
   * `/fafsa` page is "seed the next cycle" or "re-verify the corpus" or "load
   * the corpus at all", and those are three different fixes. The values that
   * chose the arm are logged as slf4j properties, on the
   * `FederalAidPolicyService` precedent.
   *
   * THE SERVED TYPE IS UNCHANGED. The facts go to the operator's log, never
   * onto the wire: a refusal exists to withhold dates (RFC 230), and that
   * decision is not what this function moves.
   */
  private fun refuse(
    reason: NoDeadlinesReason,
    today: LocalDate,
    filingYear: AcademicYear,
    heldAwardYears: List<AcademicYear>,
    earliestExpiry: LocalDate?,
  ): FafsaDeadlines.NoDeadlines {
    logger.warn(
      "serving no FAFSA deadlines: reason=[{}] asOf=[{}] filingYear=[{}] heldAwardYears=[{}] earliestExpiry=[{}]",
      reason,
      today,
      filingYear.label,
      heldAwardYears.map { it.label },
      earliestExpiry,
    )
    return FafsaDeadlines.NoDeadlines(heldAwardYears = heldAwardYears, reason = reason)
  }

  /**
   * The live set as the served answer: the federal rows, the region grouping,
   * and the jurisdictions it leaves out.
   *
   * Apart from [readInSession], which decides WHETHER there is an answer. This
   * builds the one there is, and is the only half that pays for the region read.
   */
  private fun resolve(
    session: SqlSession,
    today: LocalDate,
    filingYear: AcademicYear,
    live: List<FafsaFilingDeadline>,
    expiresOn: LocalDate,
  ): FafsaDeadlines.Resolved {
    val statesWithRegion = CodebooksDao.usStatesWithRegion(session).getOrThrow()
    val (federalRows, stateRows) = splitByJurisdiction(live)
    val federal = federalRows.map(::deadlineOf)
    val states = servedStates(stateRows, statesWithRegion.associateBy { it.uspsCode })
    return FafsaDeadlines.Resolved(
      asOf = today,
      filingYear = filingYear,
      window = FafsaDeadlineWindow(cycles = cyclesOf(live), expiresOn = expiresOn),
      federal = federal,
      states = states,
      missingJurisdictions = missingJurisdictions(statesWithRegion, states),
    )
  }

  /**
   * The live rows split in two: the federal jurisdiction's, in DAO order, and
   * each state's under its own typed code, first-seen order.
   *
   * ONE EXHAUSTIVE `when` and not two independent filters, because
   * [DeadlineJurisdiction] is `:db`'s vocabulary: a variant added there fails
   * THIS file's compile instead of falling out of BOTH halves of the answer
   * while still feeding the window -- a served row that reaches no door, which
   * is the silent gap this service exists to prevent. It also removes the
   * unchecked cast two filters made necessary.
   */
  private fun splitByJurisdiction(
    live: List<FafsaFilingDeadline>,
  ): Pair<List<FafsaFilingDeadline>, Map<DeadlineJurisdiction.State, List<FafsaFilingDeadline>>> {
    val federal = mutableListOf<FafsaFilingDeadline>()
    val states = linkedMapOf<DeadlineJurisdiction.State, MutableList<FafsaFilingDeadline>>()
    live.forEach { row ->
      when (val jurisdiction = row.jurisdiction) {
        DeadlineJurisdiction.Federal -> federal += row
        is DeadlineJurisdiction.State -> states.getOrPut(jurisdiction) { mutableListOf() } += row
      }
    }
    return federal to states
  }

  /**
   * The live state rows as served containers, region-attached, ordered by region
   * then state name.
   *
   * A TRANSFORM AND NOTHING ELSE: the served codes are derived from what this
   * returns ([missingJurisdictions]), so no accumulator can fall out of step
   * with the containers it was supposed to describe.
   */
  private fun servedStates(
    stateRows: Map<DeadlineJurisdiction.State, List<FafsaFilingDeadline>>,
    regionsByCode: Map<String, UsStateRegion>,
  ): List<StateDeadlines> =
    stateRows
      .map { (jurisdiction, rows) ->
        // A served state with no us_states row is a broken invariant to throw
        // on -- and the throw names the row, the cycles it carried and the
        // vocabulary that lacks it, because whoever reads the trace has none of
        // them in hand (FederalAidPolicyService's rule, with its payload).
        val region =
          regionsByCode[jurisdiction.uspsCode]
            ?: throw IllegalStateException(
              "fafsa_filing_deadlines rows for state [${jurisdiction.uspsCode}] " +
                "(award years [${rows.map { it.awardYear.label }.distinct()}]) have no us_states row: " +
                "usStatesWithRegion returned [${regionsByCode.size}] codes [${regionsByCode.keys.sorted()}]",
            )
        // The SAME D13 rule [missingJurisdictions] draws its list from, applied
        // to the SERVED half as well: `DeadlineJurisdiction.State` admits all 59
        // published codes, so "not federal" would serve a territory as a state
        // while the gap list insists territories are out of scope. A deadline
        // row for one is a harvest error that says so rather than reaching a door.
        require(region.jurisdictionKind.isServedJurisdiction()) {
          "fafsa_filing_deadlines rows exist for [${jurisdiction.uspsCode}], whose jurisdiction_kind is " +
            "[${region.jurisdictionKind}]: this read serves the 50 states and DC only (D13)"
        }
        StateDeadlines(
          jurisdiction = jurisdiction,
          name = region.name,
          region = region.region,
          deadlines = rows.map(::deadlineOf),
        )
      }.sortedWith(compareBy({ it.region }, { it.name }))

  /**
   * The served jurisdictions (D13) that carry no live row, in `us_states.name`
   * order -- typed, the same value the served half carries.
   */
  private fun missingJurisdictions(
    statesWithRegion: List<UsStateRegion>,
    states: List<StateDeadlines>,
  ): List<DeadlineJurisdiction.State> {
    val served = states.mapTo(mutableSetOf()) { it.jurisdiction }
    return statesWithRegion
      .filter { it.jurisdictionKind.isServedJurisdiction() }
      .map { state ->
        // A published us_states code the jurisdiction vocabulary does not admit
        // is the codebook and the model having diverged -- say so, rather than
        // serve a code no door can key. Total today: us_states publishes exactly
        // the 59 codes UsStateCodes.ALL names.
        DeadlineJurisdiction.State.of(state.uspsCode)
          ?: throw IllegalStateException("us_states row [${state.uspsCode}] is not a state DeadlineJurisdiction names")
      }.filter { it !in served }
  }

  /**
   * Whether this read is expected to name a deadline gap for a jurisdiction of
   * this kind: the 50 states and DC (D13), and not the territories or the
   * freely-associated states.
   *
   * AN EXHAUSTIVE `when` and not an `||` chain, because [JurisdictionKind] is
   * somebody else's vocabulary: a kind added there fails THIS file's compile
   * instead of silently defaulting to "not a gap we name", which is exactly the
   * silent gap [FafsaDeadlines.Resolved.missingJurisdictions] exists to prevent.
   */
  private fun JurisdictionKind.isServedJurisdiction(): Boolean =
    when (this) {
      JurisdictionKind.STATE, JurisdictionKind.FEDERAL_DISTRICT -> true
      JurisdictionKind.TERRITORY, JurisdictionKind.FREELY_ASSOCIATED_STATE -> false
    }

  /** Per jurisdiction, only its newest cycle's rows -- the D23 live set. */
  private fun liveRows(rows: List<FafsaFilingDeadline>): List<FafsaFilingDeadline> =
    rows
      .groupBy { it.jurisdiction }
      .values
      .flatMap { jurisdictionRows ->
        val newest = jurisdictionRows.maxOf { it.awardYear }
        jurisdictionRows.filter { it.awardYear == newest }
      }

  /** One stored live row as the served value, its own cycle named on it. */
  private fun deadlineOf(row: FafsaFilingDeadline): FafsaDeadline =
    FafsaDeadline(
      awardYear = row.awardYear,
      program = row.program,
      event = row.event,
      deadline = row.deadline,
      publisher = row.publisher,
      sourceUrl = row.sourceUrl,
      archiveUrl = row.archiveUrl,
      checkedOn = row.checkedOn,
    )

  /** The cycles [rows] are labelled with, distinct and oldest first -- several present are all named, never one chosen. */
  private fun cyclesOf(rows: List<FafsaFilingDeadline>): List<AcademicYear> = rows.map { it.awardYear }.distinct().sorted()

  companion object {
    /**
     * The zone a null request zone falls back to: America/Los_Angeles, the ONE
     * home for that decision. Both doors pass the SAME zone for the SAME device,
     * so page and coach still cannot disagree.
     */
    val FALLBACK_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    /**
     * What the answering path costs, in prepared statements: the whole-table
     * deadline read plus the state-and-region read. Each refusal arm returns
     * after the deadline read and costs one.
     *
     * ABSOLUTE, and asserted -- a stated number is a claim a test can contradict
     * (RFC 187).
     */
    const val READ_STATEMENTS: Int = 2

    /**
     * What a refusal costs, in prepared statements: the whole-table deadline
     * read alone. Each of the three arms returns before the region read, which
     * is the whole difference from [READ_STATEMENTS].
     *
     * ABSOLUTE, and asserted -- a stated number is a claim a test can contradict
     * (RFC 187), and it belongs beside the number it is derived from rather than
     * inline in a test body.
     */
    const val REFUSAL_STATEMENTS: Int = 1

    private val logger = LoggerFactory.getLogger(FafsaDeadlineService::class.java)
  }
}
