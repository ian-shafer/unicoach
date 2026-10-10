package ed.unicoach.db.dao

import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.HandTypedReferenceData
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.NationalMeritCycle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The committed National Merit cycle corpus, read OUT OF A DATABASE BUILT BY
 * MIGRATION ALONE (RFC 243 Route A, RFC 229's acceptance pattern, brief 0010
 * D19/D20).
 *
 * THAT IS WHY THIS ONE READS THE DATABASE AND ITS SIBLINGS READ A FILE.
 * [AdmissionTestStateProgrammeSeedTest] and [FafsaFilingDeadlineSeedTest] assert
 * their committed CSV from disk, and the drift between that CSV and the generated
 * migration is `bin/<generator> -n`'s job. For this corpus the claim that matters
 * is the ROUTE: `bin/test` does `db-reset` -- drop, create, migrate -- and NEVER
 * ingests, so every row visible here was put there by
 * `db/schema/seed-national-merit-cycles-*.sql` replaying from the top. "What this
 * test is and when it is" therefore cannot depend on an import having run, and
 * this file is what says so rather than a sentence in an RFC.
 *
 * STALENESS IS A BUILD FAILURE, and that is the other half of the point. NMSC and
 * College Board re-key these pages every year; a corpus nobody re-read tells a
 * junior a window that has moved, which is a date a family ACTS on.
 *
 * LIVENESS IS KEYED ON THE COMPETITION YEAR AND NOT ON THE STORED DATES, which is
 * the rule this design changed in writing and the one thing here that is easy to
 * get wrong. The 2029 row is real, is the answer a sophomore gets, and carries NO
 * DATES AT ALL -- NMSC dates only the cycle in progress. A liveness rule reading
 * `window_opens_on` or `alternate_entry_deadline` would never look at that row, so
 * it would rot in silence while every test stayed green; `a cycle with no dates at
 * all is still guarded` is the case that holds the rule to that.
 *
 * NEWEST-PER-CYCLE IS NOT NEEDED HERE, unlike in the file-reading siblings, and
 * the reason is worth one sentence: the CSV is append-only, so a re-read ADDS a
 * row and a reader of the file must take the newest per key -- but the generated
 * seeds UPSERT `ON CONFLICT (competition_year)`, so the TABLE holds exactly one
 * row per cycle, which is the latest reading by construction.
 */
class NationalMeritCycleSeedTest {
  companion object {
    private lateinit var connection: Connection

    /**
     * The competition years the corpus shipped with (RFC 243): the cycle NMSC has
     * dated, and the one it has only described. A FLOOR over LIVE cycles and not an
     * equality -- a re-read may add the cycle NMSC dates next, and no documented
     * action ever removes one, because a cycle that ends is a row that goes dead
     * with its own year rather than a row anybody deletes.
     *
     * TWO, because the pair is the design: brief 0010 D22's deliberately unlike
     * cases are exactly "the cycle in progress" and "the cycle described and not
     * dated", and a corpus holding one of them is half-written.
     */
    private const val MINIMUM_LIVE_CYCLES = 2

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /**
   * Every cycle the migrated database holds, through the DAO the service reads
   * with. NOTHING HERE WRITES: this suite is the only one in the module that
   * asserts the rows the MIGRATION put there, so a write would be a test rewriting
   * the artifact it is the guard on.
   */
  private val rows: List<NationalMeritCycle> = NationalMeritCyclesDao.list(session).getOrThrow()

  /**
   * The refresh instruction, in full, because this test's failure IS the
   * instruction: whoever hits it is being told to re-read two publishers' pages and
   * regenerate a migration, and a failure message that names neither the file nor
   * the command gets ignored for a week.
   */
  private val refreshInstruction =
    "re-read NMSC's own pages and College Board's, APPEND a row per cycle to " +
      "db/seed/national-merit/cycles.csv (never edit a landed row) with its new checked_on, its " +
      "expires_on and whatever those pages now publish, sweep the cited urls with " +
      "`nix develop -c bin/check-link` into link-status.csv beside it, update PROVENANCE.json, and " +
      "regenerate the seed with `nix develop -c bin/national-merit-seed`"

  /**
   * ROUTE A, ACCEPTED. A freshly `db-reset` database -- drop, create, migrate, no
   * ingest -- holds both committed cycles, with the cells the coach actually speaks.
   *
   * THE KEY CELLS AND NOT JUST THE COUNT. A seed that applied and stored the window
   * under the wrong cycle, or the administrator's page as the sponsor's, would pass
   * a row count perfectly well -- and twenty-one of this table's columns are TEXT or
   * DATE, so nothing but a comparison catches a column swap.
   */
  @Test
  fun `a database built by migration alone holds both committed cycles`() {
    assertEquals(
      listOf(2029, 2028),
      rows.map { it.competitionYear },
      "a db-reset database must hold the committed cycles, newest competition first, with no ingest having run; " +
        "if this is empty the generated seed is not in db/schema/ORDER, so nothing applied it",
    )

    val dated = rows.single { it.competitionYear == 2028 }
    val window = assertIs<CycleWindow.Dated>(dated.window, "the 2028 cycle is the one NMSC has DATED")
    assertEquals(LocalDate.of(2026, 10, 1), window.opensOn, "the 2028 competition is the October 2026 sitting")
    assertEquals(LocalDate.of(2026, 10, 30), window.closesOn, "the 2028 window closes on the day College Board publishes")
    assertEquals(11, dated.qualifyingGrade, "the qualifying sitting is the junior year, stored and never remembered")
    val alternateEntry = assertNotNull(dated.alternateEntry, "the alternate entry route is why a missed October is not final")
    assertEquals(LocalDate.of(2027, 4, 1), alternateEntry.deadline, "the letter has its own published deadline")
    assertEquals(1200000, dated.funnel.metEntryRequirements, "the base rate's denominator is the entrants, not the test takers")
    assertEquals(15000, dated.funnel.finalists, "the base rate's numerator is the Finalists")
    assertEquals(2500, dated.nmscAward?.amountUsd, "NMSC's OWN award is the small number, and it is stored so nobody remembers it")
    assertEquals("National Merit Scholarship Corporation", dated.sponsor.publisher, "NMSC sponsors the competition")
    assertEquals("College Board", dated.administrator.publisher, "College Board administers the test and publishes the window dates")
    assertTrue(
      dated.sponsor.sourceUrl != dated.administrator.sourceUrl,
      "the row cites two pages, because no page carries all of it; it cited [${dated.sponsor.sourceUrl}] twice",
    )
    assertEquals(LocalDate.of(2026, 9, 25), dated.checkedOn, "the day a human read those pages")

    // THE UNDATED CYCLE, WHICH IS THE NORMAL CASE AND NOT AN EDGE CASE. Measured
    // during implementation: no publisher has dated any cycle after 2028, so the
    // row a sophomore gets carries the description and nothing that looks like a
    // date, a funnel or a price.
    val undated = rows.single { it.competitionYear == 2029 }
    assertIs<CycleWindow.Described>(
      undated.window,
      "the 2029 cycle is DESCRIBED and not dated; a seed that invented a window would fail here",
    )
    assertTrue(undated.window.asPublished.isNotBlank(), "a described window is still the publisher's own words")
    assertNull(undated.alternateEntry, "NMSC publishes no alternate entry route for a cycle it has not dated")
    assertNull(undated.funnel.finalists, "NMSC publishes a funnel only for the cycle in progress")
    assertNull(undated.funnel.metEntryRequirements, "so the sophomore's arm has no base rate of her own to speak")
    assertNull(undated.nmscAward, "the Types of Scholarships page prices the 2028 competition, not this one")
    assertNull(undated.sponsorCapAsPublished, "and it caps a sponsor's award for that competition, not this one")
  }

  /**
   * THE BASE RATE IS DERIVED AT READ TIME AND NEVER STORED (CLAUDE.md's schema
   * convention, RFC 243 correction 4). The seed carries the funnel stages and no
   * share column, so this is the one place the two halves meet: the dated cycle
   * computes a share from its own stages, and the undated cycle answers null rather
   * than borrowing its sibling's.
   */
  @Test
  fun `the base rate is derived from the stored funnel, and the undated cycle has none`() {
    val dated = rows.single { it.competitionYear == 2028 }
    val share = assertNotNull(dated.funnel.finalistShareOfEntrants, "the dated cycle publishes both stages the share needs")
    assertEquals(
      15000.0 / 1200000.0,
      share,
      "the share is Finalists over entrants, computed at read time from the seeded stages",
    )
    assertNull(
      rows.single { it.competitionYear == 2029 }.funnel.finalistShareOfEntrants,
      "a cycle with no published funnel has no base rate, and borrowing the other cycle's would speak a figure NMSC " +
        "has not published for this class",
    )
  }

  /**
   * THE EXPIRY RULE THE CORPUS CHOSE, ASSERTED, because it is the one an
   * implementor would "fix" into something worse. Both rows expire on the SAME day,
   * deliberately: the undated cycle is a DESCRIPTION pending dates, and its last
   * actionable day is the day its publishers date it -- a summer 2027 event, which
   * is the same re-read that the dated sibling's own expiry asks for.
   *
   * The literal per-cycle rule would give the 2029 row 2028-07-31, and the
   * [REFRESH_LEAD] guard would then fire in the spring of 2028 -- AFTER the 2029
   * window had already closed. One re-read answers for both rows, so they expire
   * together.
   */
  @Test
  fun `the undated cycle expires WITH its dated sibling, not a year later`() {
    assertEquals(
      1,
      rows.map { it.expiresOn }.distinct().size,
      "the committed cycles must expire on one day, so one re-read answers for both; they said " +
        "[${rows.map { "${it.competitionYear} expires ${it.expiresOn}" }}]",
    )
    assertTrue(
      rows.all { it.expiresOn.isAfter(it.checkedOn) },
      "an expiry is the day we stop believing the page, never the day we read it",
    )
  }

  /**
   * THE GUARD, over the committed rows as they stand today. It passes now and will
   * stop passing: both readings expire on 2027-07-31, so the [REFRESH_LEAD] bar
   * puts the failure in the spring of 2027 unless somebody re-reads the pages.
   */
  @Test
  fun `every live cycle has a current reading`() {
    assertEquals(
      emptyList(),
      guardFailures(rows, today()),
      "the committed National Merit cycle corpus has rotted, so the coach is about to tell a family a testing window " +
        "or a deadline we last checked too long ago; $refreshInstruction",
    )
  }

  /**
   * A CYCLE WITH NO DATES AT ALL IS STILL GUARDED, which is the whole reason
   * liveness is keyed on the competition YEAR.
   *
   * The 2029 row's window and alternate-entry deadline are both NULL, so a
   * date-keyed liveness rule would not select it -- it would sit in the corpus
   * expiring quietly while this suite stayed green, and the first person to notice
   * would be a sophomore being told about a sitting that had moved. Here its expiry
   * is pushed inside the lead and its dated sibling's is not, and the guard must
   * name the undated cycle and only it.
   */
  @Test
  fun `a cycle with no dates at all is still guarded`() {
    val today = today()
    val undated = rows.single { it.competitionYear == 2029 }
    assertIs<CycleWindow.Described>(undated.window, "this case is about the row with NO dates; it has some")
    assertNull(undated.alternateEntry, "and no alternate-entry deadline either, which is the other date a rule might key on")

    val corpus =
      rows.map { row ->
        if (row.competitionYear == 2029) {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days - 1L))
        } else {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days + 1L))
        }
      }

    assertEquals(
      listOf(2029),
      staleCycles(corpus, today),
      "the undated cycle is inside the refresh lead, so the guard must name it by year; it said " +
        "[${guardFailures(corpus, today)}]",
    )
  }

  /**
   * A STALE CYCLE CANNOT HIDE BEHIND A FRESH NEIGHBOUR, which is the reason the bar
   * is per cycle rather than over the corpus. Refresh every cycle but the oldest
   * live one and the corpus is overwhelmingly current; the one left behind still
   * fails, BY YEAR.
   */
  @Test
  fun `a stale cycle cannot hide behind a fresh neighbour`() {
    val today = today()
    val live = rows.filter { isLive(it, today) }
    assertTrue(live.size >= MINIMUM_LIVE_CYCLES, "this case needs at least two live cycles to hide one behind the other")
    val leftBehind = live.minOf { it.competitionYear }
    val corpus =
      live.map { row ->
        if (row.competitionYear == leftBehind) {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days - 1L))
        } else {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days + 1L))
        }
      }

    assertEquals(
      listOf(leftBehind),
      staleCycles(corpus, today),
      "exactly the one cycle nobody re-read is stale, by its own competition year and not by a substring of the " +
        "message; the guard said [${guardFailures(corpus, today)}]",
    )
  }

  /**
   * THE REFRESH PATH, EXECUTED. A guard has to be clearable by the only action its
   * own message asks for, and a guard nobody has seen fire is not a guard: past
   * every committed expiry the corpus fails, and a re-read of every live cycle
   * clears it.
   *
   * The re-read here is a HYPOTHESIS about what re-reading looks like -- a new
   * checked_on and an expiry a year on -- and is written nowhere. A real refresh is
   * retyped from the publishers' pages, because a date-shifted cycle is not a cycle
   * anybody published.
   */
  @Test
  fun `past every committed expiry the guard fails, and re-reading every live cycle clears it`() {
    val afterEveryReading = rows.maxOf { it.expiresOn }.plusDays(1)

    assertTrue(
      guardFailures(rows, afterEveryReading).isNotEmpty(),
      "past every committed expiry there is no current reading left, so the guard must fail",
    )

    val reRead =
      rows.map { row ->
        row.copy(checkedOn = afterEveryReading, expiresOn = afterEveryReading.plusYears(1))
      }

    assertEquals(
      emptyList(),
      guardFailures(reRead, afterEveryReading),
      "re-reading every cycle is the refresh the failure message asks for, so it must clear the guard",
    )
  }

  /**
   * A HALF-WRITTEN CORPUS FAILS, and it is a DIFFERENT fault from a stale one: a
   * corpus that lost the described cycle is not stale, it is incomplete, and every
   * remaining row could be perfectly fresh.
   */
  @Test
  fun `a corpus holding fewer live cycles than it shipped with fails the guard`() {
    val today = today()
    val onlyOne = rows.filter { it.competitionYear == 2028 }

    val failures = guardFailures(onlyOne, today)

    assertTrue(
      failures.any { it.contains("half-written") },
      "a corpus holding [${onlyOne.size}] live cycle must be reported half-written; it said [$failures]",
    )
    assertTrue(
      failures.any { it.contains("bin/national-merit-seed") },
      "every guard failure carries the refresh instruction, command included; it said [$failures]",
    )
  }

  /**
   * Everything wrong with [rows] as of [today], as sentences. Empty is the pass.
   *
   * ONE function, called by the guard test and by every hypothetical above, so "the
   * guard" is a thing that can be run against a corpus this suite made up rather
   * than a shape only the committed rows can be in.
   *
   * OVER LIVE CYCLES ONLY. A guard that counted dead cycles could never be cleared:
   * the 2028 row keeps its own `expires_on` forever, and once the class of 2028 has
   * graduated nobody is going to re-read pages about their competition -- there is
   * nothing left to act on.
   */
  private fun guardFailures(
    rows: List<NationalMeritCycle>,
    today: LocalDate,
  ): List<String> {
    val live = rows.filter { isLive(it, today) }
    val halfWritten =
      if (live.size >= MINIMUM_LIVE_CYCLES) {
        emptyList()
      } else {
        listOf(
          "the corpus holds [${live.size}] live cycle(s), fewer than the [$MINIMUM_LIVE_CYCLES] it shipped with, " +
            "so it is half-written: $refreshInstruction",
        )
      }
    val stale =
      live
        .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < REFRESH_LEAD.days }
        .sortedBy { it.competitionYear }
        .map {
          "[${it.competitionYear}] checked_on=[${it.checkedOn}] expires_on=[${it.expiresOn}] is inside the " +
            "[${REFRESH_LEAD.days}]-day refresh lead: $refreshInstruction"
        }
    return halfWritten + stale
  }

  /** The competition years the guard reported stale, as values rather than as substrings of a sentence. */
  private fun staleCycles(
    rows: List<NationalMeritCycle>,
    today: LocalDate,
  ): List<Int> =
    rows
      .filter { isLive(it, today) }
      .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < REFRESH_LEAD.days }
      .map { it.competitionYear }
      .sorted()

  /**
   * A cycle is LIVE while its competition year is not BEHIND the class now
   * graduating -- keyed on the YEAR, and on nothing this table stores as a date.
   *
   * The class now graduating is INCLUDED rather than excluded, which is a reading of
   * RFC 243's "still ahead of the class now graduating": that class is still inside
   * its own competition in the spring of its competition year -- Finalist standing
   * and the awards themselves land then -- so its row is the answer to a senior's
   * question and re-reading it still matters. A cycle whose year has PASSED is dead:
   * nothing about it is actionable by anyone, which is what makes the guard
   * clearable at all.
   */
  private fun isLive(
    cycle: NationalMeritCycle,
    today: LocalDate,
  ): Boolean = cycle.competitionYear >= classNowGraduating(today)

  /**
   * The graduation year of the class now in its final year of school, on the
   * boundary the US school year turns over on: in September 2026 that is the
   * class of 2027.
   *
   * THE MONTH IS [HandTypedReferenceData.CYCLE_EXPIRY]'s, not a second `7` typed
   * here: the turnover and the day a hand-typed cycle stops being believed are
   * the same summer boundary, and the sibling guards each retyped their own.
   */
  private fun classNowGraduating(today: LocalDate): Int =
    if (today.month < HandTypedReferenceData.CYCLE_EXPIRY.month) today.year else today.year + 1

  /** Today, in the ONE zone every hand-typed corpus's guard reads it in ([HandTypedReferenceData.GUARD_ZONE]). */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), HandTypedReferenceData.GUARD_ZONE)
}
