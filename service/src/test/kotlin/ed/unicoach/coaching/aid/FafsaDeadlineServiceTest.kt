package ed.unicoach.coaching.aid

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.FafsaFilingDeadlineFixture
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.NewFafsaFilingDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ed.unicoach.db.dao.FafsaFilingDeadlineFixture.deadline as row

/**
 * `FafsaDeadlineService` (RFC 230): the one read both FAFSA doors render. The
 * three-arm refusal, the D23 live set, the region grouping, the
 * missing-jurisdiction list, the statement budget and the zone rule.
 *
 * Every test pins an INSTANT and passes an explicit [ZoneId] (the
 * `TestTimelineServiceTest` rule), except the one that proves the null zone
 * falls back to [FafsaDeadlineService.FALLBACK_ZONE]. Every date asserted is a
 * row seeded here or a stated derivation; nothing asserts a date this suite
 * invented.
 */
class FafsaDeadlineServiceTest {
  @BeforeEach
  fun setUp() {
    FafsaFilingDeadlineFixture.deleteAll(CoachingTestDb.sqlSession)
    // Every state row foreign-keys us_states, and the service joins us_states
    // to ipeds_regions for the region grouping -- so both vocabularies must be
    // present before any deadline row is written or read.
    CodebookReferenceFixture.seed(CoachingTestDb.sqlSession)
  }

  /**
   * THE ACCEPTANCE CRITERION, the named failure mode: the filing cycle has
   * moved past the seeded corpus, so serving last cycle's dates is refused
   * rather than rendered. Seed only 2026-27 rows, clock past 1 October (filing
   * year 2027) -- every row looks healthy and unexpired, and the answer is
   * SUPERSEDED_CYCLE with the cycle we held named, never the 2026 dates.
   */
  @Test
  fun `a corpus one cycle behind the clock is refused, never rendered as this cycle`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2026)),
          row(jurisdiction = state("TX"), awardYear = AcademicYear(2026)),
        ),
      )

      val refused = assertIs<FafsaDeadlines.NoDeadlines>(read(serviceAt(AFTER_OCTOBER_2026)))

      assertEquals(NoDeadlinesReason.SUPERSEDED_CYCLE, refused.reason)
      assertEquals(listOf(AcademicYear(2026)), refused.heldAwardYears, "the cycle we hold is named, not served")
    }

  /** The sibling that makes the acceptance criterion mean something: the same rows, clocked before 1 October, answer. */
  @Test
  fun `the same corpus before the October turnover still answers`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2026)),
          row(jurisdiction = state("TX"), awardYear = AcademicYear(2026)),
        ),
      )

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(BEFORE_OCTOBER_2026)))

      assertEquals(AcademicYear(2026), resolved.filingYear)
      assertEquals(listOf(AcademicYear(2026)), resolved.window.cycles)
      assertEquals(2, resolved.states.size)

      // "WITH THOSE DATES", asserted as dates: a count proves two containers
      // came back, not that the published day, its wording, its citation and
      // its as-of day survived the read, and those are what the page renders.
      val california = resolved.states.single { it.jurisdiction.uspsCode == "CA" }
      assertEquals(
        listOf(FafsaFilingDeadlineFixture.DEFAULT_DEADLINE),
        california.deadlines.map { it.deadline },
        "the seeded row's own published day and its published wording, served unchanged",
      )
      assertEquals(
        listOf(FafsaFilingDeadlineFixture.defaultSourceUrl(state("CA"), FafsaFilingDeadlineFixture.DEFAULT_PROGRAM)),
        california.deadlines.map { it.sourceUrl },
        "the citation survives the external_documents join",
      )
      assertEquals(
        listOf(FafsaFilingDeadlineFixture.DEFAULT_CHECKED_ON),
        california.deadlines.map { it.checkedOn },
        "the page's as-of",
      )
    }

  /** AN EMPTY TABLE IS NOT AN OUT-OF-DATE ONE. Both refuse, and they refuse with different arms. */
  @Test
  fun `an empty table refuses as EMPTY with no held cycles`() =
    runTest {
      val refused = assertIs<FafsaDeadlines.NoDeadlines>(read(serviceAt(BEFORE_OCTOBER_2026)))

      assertEquals(NoDeadlinesReason.EMPTY, refused.reason)
      assertEquals(emptyList(), refused.heldAwardYears, "there is no cycle label, because there are no rows")
    }

  /** Rows whose expiry has passed refuse as EXPIRED, and one day earlier the same rows answer. */
  @Test
  fun `live rows past their expiry refuse as EXPIRED, and one day earlier they answer`() =
    runTest {
      seed(
        listOf(
          row(
            jurisdiction = state("CA"),
            awardYear = AcademicYear(2026),
            checkedOn = LocalDate.of(2026, 9, 1),
            expiresOn = EXPIRY,
          ),
        ),
      )

      val refused = assertIs<FafsaDeadlines.NoDeadlines>(read(serviceAt(NOON_AFTER_EXPIRY)))
      assertEquals(NoDeadlinesReason.EXPIRED, refused.reason, "we held this cycle and no longer believe it")
      assertEquals(listOf(AcademicYear(2026)), refused.heldAwardYears)

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(NOON_ON_EXPIRY)))
      assertEquals(EXPIRY, resolved.window.expiresOn, "the expiry day itself still answers")
    }

  /**
   * `heldAwardYears` IS THE WHOLE TABLE'S CYCLES, on the EXPIRED arm as on the
   * others -- witnessed, not asserted in a comment. A jurisdiction holding two
   * cycles whose live set (2027, its newest) has expired: the refusal names both
   * cycles we hold, and never the live subset alone. Every other case in this
   * suite builds a store where the two answers coincide, so this is the one that
   * can see the difference.
   */
  @Test
  fun `an expired refusal names every cycle held, not the live subset`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2026)),
          row(
            jurisdiction = state("CA"),
            awardYear = AcademicYear(2027),
            checkedOn = LocalDate.of(2026, 9, 1),
            expiresOn = EXPIRY,
          ),
        ),
      )

      val refused = assertIs<FafsaDeadlines.NoDeadlines>(read(serviceAt(NOON_AFTER_EXPIRY)))

      assertEquals(NoDeadlinesReason.EXPIRED, refused.reason, "the live set is CA's 2027 rows, and they have expired")
      assertEquals(
        listOf(AcademicYear(2026), AcademicYear(2027)),
        refused.heldAwardYears,
        "both cycles we hold, oldest first -- not [2027], the live subset that expired",
      )
    }

  /** D23, executed: each jurisdiction serves only its newest cycle's rows, and every row names its own cycle. */
  @Test
  fun `a mixed-cycle store serves the newest cycle per jurisdiction, and each row names its own cycle`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2026)),
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2027)),
          row(jurisdiction = state("TX"), awardYear = AcademicYear(2026)),
        ),
      )

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))

      assertEquals(
        listOf(AcademicYear(2026), AcademicYear(2027)),
        resolved.window.cycles,
        "both cycles present in the live set are named, oldest first",
      )

      val california = resolved.states.single { it.jurisdiction.uspsCode == "CA" }
      assertEquals(
        listOf(AcademicYear(2027)),
        california.deadlines.map { it.awardYear },
        "a state with 2026 and 2027 rows serves only its newest cycle's rows",
      )
      val texas = resolved.states.single { it.jurisdiction.uspsCode == "TX" }
      assertEquals(
        listOf(AcademicYear(2026)),
        texas.deadlines.map { it.awardYear },
        "a state with only 2026 rows serves 2026, not nothing",
      )
    }

  /** Federal rows are served on the container that names them federal, and get the same newest-cycle rule (D23). */
  @Test
  fun `federal rows are served apart from the states, newest cycle only`() =
    runTest {
      seed(
        listOf(
          row(awardYear = AcademicYear(2026)),
          row(awardYear = AcademicYear(2027)),
          row(jurisdiction = state("CA")),
        ),
      )

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))

      assertEquals(
        listOf(AcademicYear(2027)),
        resolved.federal.map { it.awardYear },
        "the federal jurisdiction gets the same newest-cycle rule a state gets",
      )
      assertEquals(
        listOf("CA"),
        resolved.states.map { it.jurisdiction.uspsCode },
        "a federal row is served on `federal` and never as a state",
      )
    }

  /** The region is attached per state from ipeds_regions.name, and states are ordered by region then name. */
  @Test
  fun `states are region-attached and ordered by region then name`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("AL")),
          row(jurisdiction = state("CT")),
          row(jurisdiction = state("NV")),
          row(jurisdiction = state("CA")),
        ),
      )

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))

      assertEquals(
        listOf("CA", "NV", "CT", "AL"),
        resolved.states.map { it.jurisdiction.uspsCode },
        "Far West (CA, NV) leads, then New England (CT), then Southeast (AL): region first, then name",
      )
      resolved.states.forEach { state ->
        val published = publishedStateOf(state.jurisdiction.uspsCode)
        assertEquals(published.name, state.name)
        assertEquals(regionName(published.ipedsRegion), state.region, "the region is ipeds_regions.name, attached here")
      }
    }

  /** The jurisdictions we hold nothing for come back as a list, never as silence, and never the territories. */
  @Test
  fun `the jurisdictions with no live row are named, in name order, and never the territories`() =
    runTest {
      // The territories are in us_states FOR THIS CASE ONLY, so "never the
      // territories" is the service's jurisdiction_kind filter under test
      // rather than a row that was absent anyway: CodebookReferenceFixture.seed
      // holds only the 51 non-territory jurisdictions. removeOtherJurisdictions
      // is seedOtherJurisdictions' exact inverse, so the shared database is
      // handed back as it was found.
      CodebookReferenceFixture.seedOtherJurisdictions(CoachingTestDb.connection)
      try {
        // The 51 non-territory jurisdictions minus the four the harvest could not
        // verify -- so the list below is exactly AR, DE, NC, NH (D9/D13).
        seed(CodebookReferenceFixture.STATES.filter { it.uspsCode !in ABSENT }.map { row(jurisdiction = state(it.uspsCode)) })

        val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))

        // ABSENT, not a second typing of its four codes: the seed above is
        // built from it, so "we seeded everything but these; exactly these come
        // back" is the relationship the case is about, and one edit moves both
        // halves of it.
        assertEquals(
          ABSENT,
          resolved.missingJurisdictions.mapTo(mutableSetOf()) { it.uspsCode },
          "exactly the four unverified jurisdictions -- and no territory, though PR/GU/VI are seeded and hold no row either",
        )
        assertEquals(
          listOf("AR", "DE", "NH", "NC"),
          resolved.missingJurisdictions.map { it.uspsCode },
          "us_states.name order: Arkansas, Delaware, New Hampshire, North Carolina",
        )
      } finally {
        CodebookReferenceFixture.removeOtherJurisdictions(CoachingTestDb.connection)
      }
    }

  /** The answering read costs the stated number of statements. */
  @Test
  fun `the answering read costs two statements`() =
    runTest {
      seed(listOf(row(jurisdiction = state("CA"))))

      val answering = CoachingTestDb.CountingSession()
      val resolved = serviceAt(AFTER_OCTOBER_2026).readInSession(answering, ZONE)
      assertIs<FafsaDeadlines.Resolved>(resolved)
      assertEquals(
        FafsaDeadlineService.READ_STATEMENTS,
        answering.prepared.size,
        "the whole answering read is [${FafsaDeadlineService.READ_STATEMENTS}] statements -- the deadline " +
          "read and the region read. It prepared [${answering.prepared}]; if that is correct, say so in " +
          "readInSession's KDoc and in READ_STATEMENTS in the same commit",
      )
    }

  /**
   * A refusal carries no region grouping, so it must not read one -- its own
   * case, because @BeforeEach already leaves the table empty and the refusal
   * arm therefore needs no setup of its own. Splitting it from the answering
   * budget means a failure here names the refusal.
   */
  @Test
  fun `a refusal costs one statement, because it reads no region grouping`() =
    runTest {
      val refusing = CoachingTestDb.CountingSession()
      val refused = serviceAt(AFTER_OCTOBER_2026).readInSession(refusing, ZONE)
      assertIs<FafsaDeadlines.NoDeadlines>(refused)
      assertEquals(
        FafsaDeadlineService.REFUSAL_STATEMENTS,
        refusing.prepared.size,
        "a refusal carries no region grouping, so it must not read one: [${refusing.prepared}]",
      )
    }

  /** A null zone falls back to America/Los_Angeles, so an instant that is October in UTC is still September here. */
  @Test
  fun `a null zone falls back to America Los Angeles`() =
    runTest {
      assertEquals(ZoneId.of("America/Los_Angeles"), FafsaDeadlineService.FALLBACK_ZONE)
      seed(listOf(row(jurisdiction = state("CA"), awardYear = AcademicYear(2026))))

      val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(OCTOBER_1_0300Z), zone = null))

      assertEquals(LocalDate.parse("2026-09-30"), resolved.asOf, "the fallback zone's day, not the UTC day")
      assertEquals(AcademicYear(2026), resolved.filingYear)
    }

  /** The day, not the instant, decides the filing year: the same instant serves both cycles in two zones. */
  @Test
  fun `the same instant is the old filing year in a zone still on the old day, and the next in one over the line`() =
    runTest {
      seed(
        listOf(
          row(jurisdiction = state("CA"), awardYear = AcademicYear(2027)),
          row(jurisdiction = state("TX"), awardYear = AcademicYear(2026)),
        ),
      )

      val oldDay = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(OCTOBER_TURNOVER_INSTANT), zone = ZONE))
      assertEquals(AcademicYear(2026), oldDay.filingYear, "Los Angeles is still on the old day at this instant")

      val nextDay = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(OCTOBER_TURNOVER_INSTANT), zone = AUCKLAND))
      assertEquals(AcademicYear(2027), nextDay.filingYear, "Auckland is already over the line at the same instant")
    }

  /** A cancelled read is rethrown, never reported as a read failure. */
  @Test
  fun `a cancelled read is rethrown, never reported as a read failure`() =
    runBlocking {
      seed(listOf(row(jurisdiction = state("CA"))))
      val service = serviceAt(AFTER_OCTOBER_2026)

      var thrown: Throwable? = null
      var completed: Result<FafsaDeadlines>? = null
      // UNDISPATCHED runs the body up to the first real suspension -- the
      // connection hand-off -- and the cancel then lands while the read is in
      // flight, with no sleep and no race.
      val job =
        launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            completed = service.get(ZONE)
          } catch (e: Throwable) {
            thrown = e
          }
        }
      job.cancel()
      job.join()

      assertTrue(thrown is CancellationException, "cancellation must propagate, got [$thrown]")
      assertNull(completed, "a cancelled read must not answer with a Result at all")
    }

  /**
   * A READ THAT COULD NOT RUN is a Result.failure, never a throw out of `get`.
   *
   * The fault is injected at the STATEMENT, which is the only fault production
   * can actually produce here and the one that reaches the `getOrThrow()` on
   * the DAO's `Result.failure`: a clock that throws would never reach a
   * statement at all. [withDeadlineTableHidden] owns the rename, its inverse
   * and the two preconditions they depend on.
   */
  @Test
  fun `a failed statement is a Result failure`() =
    runTest {
      val service = serviceAt(AFTER_OCTOBER_2026)
      withDeadlineTableHidden {
        val result = service.get(ZONE)

        assertTrue(result.isFailure, "a read that could not run must be a Result.failure, not a throw")
        // The DAO's own wrapper: this IS the value listAll put in its
        // Result.failure, so the assertion proves the getOrThrow() in
        // readInSession is what `get` converted -- and the driver's own fault
        // is still under it. The chain is WALKED rather than matched one deep
        // because coroutine stack-trace recovery copies the thrown exception
        // and hangs the original off the copy.
        val failure = assertIs<DatabaseException>(result.exceptionOrNull())
        val chain = generateSequence(failure as Throwable) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        assertTrue(chain.any { it is SQLException }, "the driver's own fault is carried, not swallowed: [$chain]")
      }
    }

  /**
   * Runs [block] with the deadline table renamed out from under the read, and
   * puts it back whatever [block] does. THE ONE HOME for this suite's only DDL,
   * so the rename and its inverse cannot be paired wrongly.
   *
   * IT ASSERTS BOTH PRECONDITIONS IT RESTS ON, because the rename is DDL
   * against the SHARED test database:
   *
   * - the table is there to hide. The `finally` covers a failing assertion, but
   *   not a JVM-level failure between the two renames -- and that would leave
   *   every LATER suite in the run reading a table that is not there, for a
   *   reason none of them mentions. Asserted here, a leak reports itself by
   *   name, in the case that leaked it.
   * - the connection is in AUTOCOMMIT. The rename runs on the shared
   *   connection while `get` takes its own from the pool: inside an open
   *   transaction the rename would hold its ACCESS EXCLUSIVE lock and the read
   *   would BLOCK rather than fail -- a hang, not an assertion failure.
   *
   * Nothing runs beside it either (no `org.gradle.parallel`, no
   * `maxParallelForks`, and no JUnit parallel execution, so one test JVM runs
   * one test at a time) -- but that is a build setting no assertion here can
   * see, which is why these two are asserted and that one is stated.
   */
  private inline fun <T> withDeadlineTableHidden(block: () -> T): T {
    assertTrue(
      CoachingTestDb.connection.autoCommit,
      "the rename must commit before the read, or the read blocks on its ACCESS EXCLUSIVE lock",
    )
    assertTrue(
      tableExists(TABLE),
      "[$TABLE] is missing before this case runs -- an earlier run leaked the rename to [$HIDDEN_TABLE]",
    )
    renameDeadlineTable(TABLE, HIDDEN_TABLE)
    // The restore is guarded, because an unguarded throw from it REPLACES the
    // failure `block()` threw -- the assertion that actually diagnosed the bug,
    // lost to the cleanup of the very situation this helper exists to survive.
    // The primary failure still propagates; the restore fault rides with it.
    var failure: Throwable? = null
    try {
      return block()
    } catch (e: Throwable) {
      failure = e
      throw e
    } finally {
      try {
        renameDeadlineTable(HIDDEN_TABLE, TABLE)
      } catch (restoreFailure: Throwable) {
        if (failure == null) throw restoreFailure
        failure.addSuppressed(restoreFailure)
      }
    }
  }

  /** Whether [table] is a table this connection can see -- the precondition [withDeadlineTableHidden] asserts. */
  private fun tableExists(table: String): Boolean =
    CoachingTestDb.connection.prepareStatement("SELECT to_regclass(?::text) IS NOT NULL").use { stmt ->
      stmt.setString(1, table)
      stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }

  /** The rename itself, one way; [withDeadlineTableHidden] is the only caller and owns the inverse. */
  private fun renameDeadlineTable(
    from: String,
    to: String,
  ) = CoachingTestDb.connection.prepareStatement("ALTER TABLE $from RENAME TO $to").use { it.executeUpdate() }

  /** The service with today pinned -- an INSTANT with no zone on it, because the service applies the caller's zone. */
  private fun serviceAt(instant: String): FafsaDeadlineService =
    FafsaDeadlineService(CoachingTestDb.database, InstantSource.fixed(Instant.parse(instant)))

  private suspend fun read(
    service: FafsaDeadlineService,
    zone: ZoneId? = ZONE,
  ): FafsaDeadlines = service.get(zone).getOrThrow()

  private fun seed(rows: List<NewFafsaFilingDeadline>) {
    FafsaFilingDeadlineFixture.seed(CoachingTestDb.sqlSession, rows)
  }

  /**
   * The typed USPS code, so a case names a state in two words.
   *
   * There is no `row` of this suite's own: `row` IS
   * [FafsaFilingDeadlineFixture.deadline], imported under that name. The
   * builder already carries every parameter a case here varies and every
   * default an unremarkable row wears, so a wrapper could only restate them --
   * and a restated default is one this file and the fixture can disagree about.
   * A federal row is that same row with no state on it, which is why it needs
   * no second builder either.
   */
  private fun state(code: String): DeadlineJurisdiction.State =
    requireNotNull(DeadlineJurisdiction.State.of(code)) {
      // The rejected code, named: `!!` here threw a NullPointerException
      // carrying neither the code nor the vocabulary that refused it, which is
      // the least informative failure the JVM has for a one-word typo.
      "[$code] is not a USPS code DeadlineJurisdiction.State admits"
    }

  private fun publishedStateOf(code: String) = CodebookReferenceFixture.STATES.first { it.uspsCode == code }

  private fun regionName(slug: String) = CodebookReferenceFixture.REGIONS.first { it.slug == slug }.name

  private companion object {
    /** The zone every test passes, except the one that proves the null fallback. */
    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    /** A zone already over the October line when Los Angeles is not. */
    val AUCKLAND: ZoneId = ZoneId.of("Pacific/Auckland")

    /** Noon UTC on 15 September 2026 -- before the October turnover, still filing year 2026 in every US zone. */
    const val BEFORE_OCTOBER_2026 = "2026-09-15T12:00:00Z"

    /**
     * How far down a cause chain the fault case looks for the driver's own
     * `SQLException`. A bound and not the whole chain, because coroutine
     * stack-trace recovery can hand back a self-referential chain and a
     * `generateSequence` over one does not terminate.
     */
    const val MAX_CAUSE_DEPTH = 10

    /** Noon UTC on 2 October 2026 -- past the turnover, filing year 2027 in the US. */
    const val AFTER_OCTOBER_2026 = "2026-10-02T12:00:00Z"

    /** An instant that is still 30 September in Los Angeles and already 1 October in Auckland. */
    const val OCTOBER_TURNOVER_INSTANT = "2026-10-01T06:00:00Z"

    /** An instant that is 1 October in UTC and still 30 September in Los Angeles -- the null-fallback probe. */
    const val OCTOBER_1_0300Z = "2026-10-01T03:00:00Z"

    /**
     * The day the expiry case's row stops being believed -- THE ONE HOME for
     * that case's boundary: the seed, the assertion and both pinned instants
     * below are read from it, so the off-by-one under test cannot be moved
     * one-sidedly and leave a case that passes while testing nothing.
     */
    val EXPIRY: LocalDate = LocalDate.of(2026, 9, 14)

    /** Noon UTC on [EXPIRY] -- the expiry day itself, which still answers. */
    val NOON_ON_EXPIRY: String = noonUtcOn(EXPIRY)

    /** Noon UTC on the day after [EXPIRY] -- the first day those rows are refused. */
    val NOON_AFTER_EXPIRY: String = noonUtcOn(EXPIRY.plusDays(1))

    /** The four jurisdictions the harvest could not verify (D9), and therefore the four this service names. */
    val ABSENT = setOf("AR", "DE", "NC", "NH")

    /** The deadline table, and the name it wears while the fault case has it renamed out from under the read. */
    const val TABLE = "fafsa_filing_deadlines"

    const val HIDDEN_TABLE = "fafsa_filing_deadlines_hidden"

    /**
     * Noon UTC on [day] -- mid-day, so no US zone is on a different date than
     * UTC is, and so a pinned instant derived here says the same day everywhere
     * this suite reads it.
     */
    private fun noonUtcOn(day: LocalDate): String = day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toString()
  }
}
