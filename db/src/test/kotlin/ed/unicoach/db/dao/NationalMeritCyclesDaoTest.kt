package ed.unicoach.db.dao

import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.CommendedCutoffBasis
import ed.unicoach.db.models.CyclePublication
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.NationalMeritCycle
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.NmscOwnAward
import ed.unicoach.db.models.SelectionIndexScale
import ed.unicoach.db.models.SemifinalistCutoffBasis
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `national_merit_cycles`, its DAO and every CHECK on it (RFC 243, slice
 * `test/08/the-psat-is-money`), against the real database.
 *
 * THE MIGRATION IS UNDER TEST HERE TOO. `bin/test` re-inits and migrates the
 * test database before Gradle runs, so a suite that connects at all has proved
 * `create-national-merit-cycles.sql` applies to a freshly recreated database.
 *
 * EVERY CHECK IS PROVED BY A FAILED WRITE and never by reading the DDL, which
 * is why the sweep goes around the models through literal SQL
 * ([AutomaticAwardsDaoTest]'s idiom): the database is what a migration, a
 * generated seed and `psql` all write through, and none of those typechecks.
 * Where the Kotlin model ALSO refuses the state, both refusals are asserted --
 * `the read models refuse` below is that half, and it is a separate test
 * because a `require` in a data class and a CHECK in a table are two different
 * guarantees with two different blast radii.
 *
 * IT PUTS THE MIGRATION'S ROWS BACK ([AdmissionTestStateProgrammeDaoTest]'s
 * idiom). This corpus arrives by MIGRATION (Route A), so the committed rows are
 * in the test database when the suite starts -- and every case here needs a
 * corpus it chose, because a fixture year would otherwise collide with a real
 * row for it. The committed rows are saved into a session-local temporary table
 * before the first test and restored after the last, so a suite that runs later
 * in the same Gradle invocation finds the database as `db-reset` left it.
 * Truncating and walking away makes every later suite's answer depend on test
 * ordering, and the failure it produces names this file nowhere.
 * `external_documents` is shared with every other cited corpus and is never
 * emptied here.
 */
class NationalMeritCyclesDaoTest {
  companion object {
    private lateinit var connection: Connection

    private const val SOURCE_URL = NationalMeritFixture.DEFAULT_SOURCE_URL
    private const val ADMINISTRATOR_URL = NationalMeritFixture.DEFAULT_ADMINISTRATOR_SOURCE_URL

    /** A third page, for the archive citations and the two archive cases. */
    private const val ARCHIVE_URL = "https://web.archive.org/web/2026/example-national-merit"

    /**
     * A LEGAL cycle row as SQL literals, which every bad row in this suite is
     * one override away from: a case that had to restate thirty-three columns
     * would hide which column it was actually about.
     *
     * Every value here is inside every CHECK: the window is an October two
     * calendar years before the competition, the alternate-entry deadline is in
     * the cycle, the funnel narrows at every stage, the two publishers are two,
     * and the expiry is after the reading day.
     */
    private val CYCLE_COLUMNS: Map<String, String> =
      linkedMapOf(
        "competition_year" to "2028",
        "qualifying_grade" to "11",
        "window_opens_on" to "DATE '2026-10-01'",
        "window_closes_on" to "DATE '2026-10-31'",
        "window_as_published" to "'The test is given in school in October of the junior year.'",
        "alternate_entry_deadline" to "DATE '2027-04-01'",
        "alternate_entry_contact" to "'ae@example.invalid'",
        "alternate_entry_as_published" to "'A student who misses the test writes to us by April 1.'",
        "selection_index_low" to "48",
        "selection_index_high" to "228",
        "semifinalist_cutoff_basis" to "'state_representational'",
        "commended_cutoff_basis" to "'national'",
        "cutoff_as_published" to "'Qualifying scores vary from state to state and from year to year.'",
        "score_visibility_as_published" to "'Scores are not reported to colleges.'",
        "test_takers" to "3500000",
        "met_entry_requirements" to "1200000",
        "high_scorers_screened" to "50000",
        "commended" to "34000",
        "semifinalists" to "16000",
        "finalists" to "15000",
        "winners" to "6700",
        "funnel_as_published" to "'About 1.2 million entrants; some 15,000 reach Finalist.'",
        "nmsc_award_usd" to "2500",
        "nmsc_award_as_published" to "'A single payment of \$2,500.'",
        "sponsor_cap_as_published" to "'A sponsor college award is capped per year.'",
        "publisher" to "'${NationalMeritFixture.DEFAULT_SPONSOR}'",
        "source_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')",
        "archive_document_id" to "NULL",
        "administrator_publisher" to "'${NationalMeritFixture.DEFAULT_ADMINISTRATOR}'",
        "administrator_document_id" to "(SELECT id FROM external_documents WHERE url = '$ADMINISTRATOR_URL')",
        "administrator_archive_document_id" to "NULL",
        "checked_on" to "DATE '2026-09-25'",
        "expires_on" to "DATE '2027-07-31'",
      )

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
      connection.createStatement().use { stmt ->
        stmt.execute("CREATE TEMP TABLE saved_cycles AS SELECT * FROM national_merit_cycles")
      }
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt ->
          stmt.execute("DELETE FROM national_merit_cycles")
          stmt.execute("INSERT INTO national_merit_cycles SELECT * FROM saved_cycles")
          stmt.execute("DROP TABLE saved_cycles")
        }
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun setup() {
    connection.autoCommit = true
    NationalMeritFixture.deleteAllCycles(session)
    // The documents every row cites. Created and never deleted:
    // external_documents is shared by every cited corpus, so a suite that
    // emptied it would take their citations with it. It states its evidence
    // like every other writer since RFC 220.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) VALUES " +
          "('$SOURCE_URL', 'found', DATE '2026-09-25'), " +
          "('$ADMINISTRATOR_URL', 'found', DATE '2026-09-25'), " +
          "('$ARCHIVE_URL', 'found', DATE '2026-09-25') " +
          "ON CONFLICT (url) DO NOTHING",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Every CHECK, proved by a failed write
  // ---------------------------------------------------------------------------

  @Test
  fun `every CHECK on the cycles table refuses its own bad row, by name`() {
    val cases =
      listOf(
        // The key is a competition YEAR, not a date and not an id.
        //
        // THE DATES COME OFF for this one case, and that is the table saying
        // something about itself: the window and the alternate-entry bounds are
        // computed FROM `competition_year`, so a row that moves the year and
        // keeps the dates breaks three CHECKs at once and PostgreSQL reports
        // whichever it evaluated first. An undated cycle is a legal row (it is
        // the normal shape two years out), so this case is about the year and
        // nothing else.
        "national_merit_cycles_competition_year_check" to
          mapOf(
            "competition_year" to "2019",
            "window_opens_on" to "NULL",
            "window_closes_on" to "NULL",
            "alternate_entry_deadline" to "NULL",
          ),
        "national_merit_cycles_qualifying_grade_check" to mapOf("qualifying_grade" to "13"),
        // One window date without the other is half a fact, in both directions.
        "national_merit_cycles_window_pair_check" to mapOf("window_closes_on" to "NULL"),
        "national_merit_cycles_window_pair_check" to mapOf("window_opens_on" to "NULL"),
        // A window that closes before it opens.
        "national_merit_cycles_window_ordered_check" to
          mapOf("window_opens_on" to "DATE '2026-10-31'", "window_closes_on" to "DATE '2026-10-01'"),
        // The qualifying sitting is in the autumn TWO calendar years before the
        // competition year, and BOTH ends of that bound are executed: a window
        // typed into the wrong cycle is the mistake a curator actually makes,
        // and it is the one that sends a junior to a test that has been and
        // gone.
        "national_merit_cycles_window_in_cycle_check" to
          mapOf("window_opens_on" to "DATE '2026-06-30'", "window_closes_on" to "DATE '2026-07-01'"),
        "national_merit_cycles_window_in_cycle_check" to
          mapOf("window_opens_on" to "DATE '2027-07-01'", "window_closes_on" to "DATE '2027-07-31'"),
        // A deadline or an address we cannot show the publisher's words for is
        // a claim we made ourselves -- and this is the route that makes "miss
        // it and it is gone forever" false, so an unsourced version of it is
        // worse than none.
        "national_merit_cycles_alternate_entry_trio_check" to mapOf("alternate_entry_as_published" to "NULL"),
        "national_merit_cycles_alternate_entry_in_cycle_check" to mapOf("alternate_entry_deadline" to "DATE '2028-01-01'"),
        "national_merit_cycles_selection_index_ordered_check" to
          mapOf("selection_index_low" to "228", "selection_index_high" to "48"),
        "national_merit_cycles_selection_index_range_check" to mapOf("selection_index_low" to "0"),
        "national_merit_cycles_selection_index_range_check" to mapOf("selection_index_high" to "501"),
        // Our own enumerations (TEXT + CHECK IN), and each case uses the OTHER
        // column's legal member: 'national' is a real basis and is the wrong
        // one for Semifinalist standing, which is allocated per state. A row
        // that crossed them would tell a family a national number was her bar.
        "national_merit_cycles_semifinalist_cutoff_basis_check" to mapOf("semifinalist_cutoff_basis" to "'national'"),
        "national_merit_cycles_commended_cutoff_basis_check" to
          mapOf("commended_cutoff_basis" to "'state_representational'"),
        // Zero winners is not a published stage; it is an empty cell typed as a
        // number.
        "national_merit_cycles_funnel_positive_check" to mapOf("winners" to "0"),
        "national_merit_cycles_funnel_positive_check" to mapOf("winners" to "-1"),
        "national_merit_cycles_funnel_positive_check" to mapOf("high_scorers_screened" to "0"),
        // A FUNNEL ONLY NARROWS. Typing 15,000 Semifinalists under 16,000
        // Finalists inverts the base rate the coach is required to speak, and
        // every stage pair is executed because each is a different sentence.
        "national_merit_cycles_funnel_narrows_check" to mapOf("met_entry_requirements" to "3500001"),
        "national_merit_cycles_funnel_narrows_check" to mapOf("high_scorers_screened" to "1200001"),
        "national_merit_cycles_funnel_narrows_check" to mapOf("commended" to "1200001"),
        "national_merit_cycles_funnel_narrows_check" to mapOf("semifinalists" to "1200001"),
        "national_merit_cycles_funnel_narrows_check" to mapOf("finalists" to "16001"),
        "national_merit_cycles_funnel_narrows_check" to mapOf("winners" to "15001"),
        // NMSC's own award is small, and a figure with no sentence beside it is
        // our number rather than theirs -- which is the whole of correction 3.
        "national_merit_cycles_nmsc_award_pair_check" to mapOf("nmsc_award_as_published" to "NULL"),
        "national_merit_cycles_nmsc_award_pair_check" to mapOf("nmsc_award_usd" to "0"),
        // A quoted sentence that is blank is not a quotation, on every column
        // that carries one.
        "national_merit_cycles_words_not_blank_check" to mapOf("window_as_published" to "'   '"),
        "national_merit_cycles_words_not_blank_check" to mapOf("cutoff_as_published" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("score_visibility_as_published" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("funnel_as_published" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("publisher" to "'  '"),
        "national_merit_cycles_words_not_blank_check" to mapOf("alternate_entry_as_published" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("alternate_entry_contact" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("nmsc_award_as_published" to "''"),
        "national_merit_cycles_words_not_blank_check" to mapOf("sponsor_cap_as_published" to "''"),
        // An archived address that IS the page is the same link twice, and a
        // coach told to offer both would print one url as two. BOTH publishers'
        // archives are executed, because each has its own pair.
        "national_merit_cycles_archive_differs_check" to
          mapOf("archive_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')"),
        "national_merit_cycles_archive_differs_check" to
          mapOf(
            "administrator_archive_document_id" to
              "(SELECT id FROM external_documents WHERE url = '$ADMINISTRATOR_URL')",
          ),
        // THE SPONSOR AND THE ADMINISTRATOR ARE DIFFERENT PUBLISHERS. A row
        // citing one page for both, or naming one organisation twice, has lost
        // half its provenance -- and the four cells that are the
        // administrator's (the window dates, the Selection Index scale, the
        // score-visibility sentence) would then be attributed to NMSC.
        "national_merit_cycles_two_publishers_check" to
          mapOf("administrator_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')"),
        "national_merit_cycles_two_publishers_check" to
          mapOf("administrator_publisher" to "'${NationalMeritFixture.DEFAULT_SPONSOR}'"),
        "national_merit_cycles_two_publishers_check" to mapOf("administrator_publisher" to "'   '"),
        // The boundary, not a comfortable failure: a row that expires the day
        // it was read is never live for a moment.
        "national_merit_cycles_expiry_after_checked_check" to mapOf("expires_on" to "DATE '2026-09-25'"),
      )

    // Every case is run and every mismatch reported, so one failing CHECK does
    // not hide the thirty behind it.
    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val actual = violatedConstraint(CYCLE_COLUMNS + overrides)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countCycles(), "no refused row may be left behind")
  }

  /**
   * The legal row the sweep overrides IS legal -- the assertion that makes
   * every line above mean something.
   *
   * Without it, a `CYCLE_COLUMNS` that no longer inserts (a renamed column, a
   * CHECK tightened past it) would leave every case failing on ITS OWN
   * constraint and the sweep would report whichever one PostgreSQL happened to
   * evaluate first, for thirty rows that were never about it.
   */
  @Test
  fun `the row every case overrides is itself accepted`() {
    assertNull(violatedConstraint(CYCLE_COLUMNS), "the fixture row must be inside every CHECK")
    assertEquals(1, countCycles())
  }

  /**
   * The states the models refuse one layer earlier, asserted BESIDE the
   * database's own refusal rather than instead of it.
   *
   * Two guarantees, not one: the CHECK is what a migration, a generated seed
   * and `psql` write through, and the `require` is what a renderer reading a
   * reconstructed row gets. A row built in a fixture never reaches a CHECK
   * until it is written, which is the case this half covers.
   */
  @Test
  fun `the read models refuse the rows the schema refuses, one layer earlier`() {
    val refusals =
      listOf<Pair<String, () -> Any>>(
        "national_merit_cycles_window_ordered_check" to
          {
            CycleWindow.Dated(
              opensOn = LocalDate.of(2026, 10, 31),
              closesOn = LocalDate.of(2026, 10, 1),
              asPublished = "October.",
            )
          },
        "national_merit_cycles_selection_index_ordered_check" to { SelectionIndexScale(228, 48) },
        "national_merit_cycles_funnel_narrows_check" to
          { NationalMeritFixture.funnel(semifinalists = 15_000, finalists = 16_000) },
        "national_merit_cycles_funnel_narrows_check" to
          { NationalMeritFixture.funnel(finalists = 15_000, winners = 15_001) },
        "national_merit_cycles_archive_differs_check" to
          { CyclePublication("Example Merit Corporation", SOURCE_URL, SOURCE_URL) },
        // The two publishers, on the READ model: one organisation named twice,
        // and one page cited twice.
        "national_merit_cycles_two_publishers_check" to
          {
            readCycle(
              administrator =
                CyclePublication(NationalMeritFixture.DEFAULT_SPONSOR, ADMINISTRATOR_URL, null),
            )
          },
        "national_merit_cycles_two_publishers_check" to
          {
            readCycle(
              administrator =
                CyclePublication(NationalMeritFixture.DEFAULT_ADMINISTRATOR, SOURCE_URL, null),
            )
          },
        "national_merit_cycles_expiry_after_checked_check" to
          { readCycle(expiresOn = LocalDate.of(2026, 9, 25)) },
      )

    // Each refusal NAMES the constraint it mirrors, so a reader who hits one in
    // a log can find the other half of the guarantee.
    val unnamed =
      refusals.mapNotNull { (constraint, build) ->
        val message = assertFailsWith<IllegalArgumentException> { build() }.message
        if (message?.contains(constraint) == true) null else "a refusal mirroring [$constraint] said [$message]"
      }
    assertEquals(emptyList<String>(), unnamed, "a model refusal must name the CHECK it mirrors")
  }

  // ---------------------------------------------------------------------------
  // No cutoff is stored, which is the design
  // ---------------------------------------------------------------------------

  /**
   * THE TABLE HOLDS NO CUTOFF NUMBER (correction 4, D9).
   *
   * NMSC allocates Semifinalist standing state-representationally, publishes no
   * qualifying score in advance, and says outright that the score varies by
   * state and by year. A numeric cutoff column would be filled with a third
   * party's estimate and spoken as hers -- so what this asserts is that every
   * `cutoff` column is TEXT: a basis, or the publisher's own words.
   *
   * IT READS THE CATALOGUE AND NOT THE DDL FILE. A column added by a later
   * migration is exactly what this exists to catch, and a test that grepped
   * `create-national-merit-cycles.sql` would not see one.
   */
  @Test
  fun `no cutoff number is stored, on any column`() {
    val numericCutoffColumns =
      connection
        .prepareStatement(
          """
          SELECT column_name, data_type
          FROM information_schema.columns
          WHERE table_name = 'national_merit_cycles'
            AND column_name LIKE '%cutoff%'
          """.trimIndent(),
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            buildList {
              while (rs.next()) {
                val type = rs.getString("data_type")
                if (type != "text") add("${rs.getString("column_name")} is [$type]")
              }
            }
          }
        }
    assertEquals(emptyList<String>(), numericCutoffColumns, "a cutoff column must be a basis or words, never a number")
  }

  // ---------------------------------------------------------------------------
  // The DAO
  // ---------------------------------------------------------------------------

  @Test
  fun `a dated cycle round-trips through the DAO, window, route and funnel intact`() {
    val written = NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle())

    val read = assertNotNull(NationalMeritCyclesDao.findByCompetitionYear(session, 2028).getOrThrow())

    assertEquals(written, read, "the row the upsert answered with is the row the read path returns")
    val window = assertIs<CycleWindow.Dated>(read.window, "a dated window comes back on the arm that has dates")
    assertEquals(LocalDate.of(2026, 10, 1), window.opensOn)
    assertEquals(LocalDate.of(2026, 10, 31), window.closesOn)
    val route = assertNotNull(read.alternateEntry, "the alternate entry route is the reason a missed October is not final")
    assertEquals(LocalDate.of(2027, 4, 1), route.deadline)
    assertEquals(SemifinalistCutoffBasis.STATE_REPRESENTATIONAL, read.semifinalistCutoff)
    assertEquals(CommendedCutoffBasis.NATIONAL, read.commendedCutoff)
    assertEquals(15_000, read.funnel.finalists)
    assertEquals(NationalMeritFixture.DEFAULT_SPONSOR, read.sponsor.publisher)
    assertEquals(SOURCE_URL, read.sponsor.sourceUrl)
    assertEquals(NationalMeritFixture.DEFAULT_ADMINISTRATOR, read.administrator.publisher)
    assertEquals(ADMINISTRATOR_URL, read.administrator.sourceUrl)
  }

  /**
   * THE UNDATED CYCLE IS THE NORMAL CASE, not an edge case: measured during
   * implementation, no publisher has dated any cycle after 2028, and NMSC names
   * the next one in a single sentence. The row exists, carries the description
   * and no dates, and the coach answering a sophomore says exactly that much.
   */
  @Test
  fun `an undated cycle round-trips as words, with no dates invented`() {
    val described =
      CycleWindow.Described("Students take the test in school in October of their junior year.")
    NationalMeritFixture.seedCycle(
      session,
      NationalMeritFixture.cycle(
        competitionYear = 2029,
        window = described,
        alternateEntry = null,
        funnel = NationalMeritFixture.funnel(null, null, null, null, null, null, null),
        nmscAward = null,
        sponsorCapAsPublished = null,
      ),
    )

    val read = assertNotNull(NationalMeritCyclesDao.findByCompetitionYear(session, 2029).getOrThrow())

    assertEquals(described, read.window)
    assertNull(read.alternateEntry, "a cycle whose route is not yet described carries none")
    assertNull(read.nmscAward)
    assertNull(read.funnel.finalists)
    assertNull(read.funnel.finalistShareOfEntrants, "a cycle with no funnel publishes no base rate")
  }

  /**
   * A PRICED AWARD WITH NO PUBLISHER'S SENTENCE IS REFUSED, and the figure is
   * named rather than thrown away.
   *
   * `national_merit_cycles_nmsc_award_pair_check` makes this unreachable
   * through the DAO, so the row is written with the constraint dropped for the
   * length of the test ([MoneyProfilesDaoTest]'s idiom) -- the only way to
   * exercise the mapper's pair reader. The read it replaced resolved such a row
   * to `null` and discarded the dollar figure with no refusal and no log, while
   * the three sibling readers in the same file refuse that exact shape with a
   * located error and the schema calls it corruption.
   */
  @Test
  fun `a priced NMSC award with no publisher's sentence is located, not dropped`() {
    NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle())
    connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE national_merit_cycles DROP CONSTRAINT national_merit_cycles_nmsc_award_pair_check")
      stmt.execute(
        "UPDATE national_merit_cycles SET nmsc_award_as_published = NULL WHERE competition_year = 2028",
      )
    }

    val error = NationalMeritCyclesDao.findByCompetitionYear(session, 2028).exceptionOrNull()

    // Restored BEFORE the assertions, and the row with it: the suite shares one
    // database, so a corrupt row left behind would fail whichever test next
    // reads every cycle.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "UPDATE national_merit_cycles SET nmsc_award_as_published = 'A single payment of \$2,500.' " +
          "WHERE competition_year = 2028",
      )
      stmt.execute(
        "ALTER TABLE national_merit_cycles ADD CONSTRAINT national_merit_cycles_nmsc_award_pair_check " +
          "CHECK (nmsc_award_usd IS NULL OR (nmsc_award_usd > 0 AND nmsc_award_as_published IS NOT NULL))",
      )
    }

    assertTrue(error is CorruptPersistedValueException, "got $error")
    assertTrue(
      error.message!!.contains("nmsc_award_usd=[2500]"),
      "the refusal must carry the dollar figure it would otherwise discard: [${error.message}]",
    )
    assertTrue(
      error.message!!.contains("nmsc_award_as_published"),
      "the refusal must name the column that is missing: [${error.message}]",
    )
    assertTrue(
      error.message!!.contains("national_merit_cycles (competition_year [2028])"),
      "the refusal must locate the row an operator has to open: [${error.message}]",
    )
  }

  @Test
  fun `findByCompetitionYear is null for a class we hold no cycle for`() {
    NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle())

    assertNull(
      NationalMeritCyclesDao.findByCompetitionYear(session, 2031).getOrThrow(),
      "we hold no dates for this year: the answer is that, and it names no date",
    )
  }

  @Test
  fun `list answers newest competition first`() {
    NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle(competitionYear = 2028))
    NationalMeritFixture.seedCycle(
      session,
      NationalMeritFixture.cycle(
        competitionYear = 2029,
        window = CycleWindow.Described("October of the junior year."),
        alternateEntry = null,
      ),
    )

    val years = NationalMeritCyclesDao.list(session).getOrThrow().map { it.competitionYear }

    assertEquals(listOf(2029, 2028), years)
  }

  @Test
  fun `a corrected re-read of the same competition year corrects the row rather than adding one`() {
    NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle())

    val corrected =
      NationalMeritFixture.seedCycle(
        session,
        NationalMeritFixture.cycle(
          window =
            CycleWindow.Dated(
              opensOn = LocalDate.of(2026, 10, 5),
              closesOn = LocalDate.of(2026, 10, 30),
              asPublished = "The window narrowed when the administrator published its dates.",
            ),
        ),
      )

    assertEquals(1, countCycles())
    assertEquals(LocalDate.of(2026, 10, 5), assertIs<CycleWindow.Dated>(corrected.window).opensOn)
  }

  // ---------------------------------------------------------------------------
  // The base rate, derived and never stored
  // ---------------------------------------------------------------------------

  /**
   * THE BASE RATE IS DERIVED AT READ TIME AND LABELLED (CLAUDE.md: anything
   * computable from stored columns is computed, never stored), and correction 4
   * is why it exists at all: the six-figure package figures are the memorable
   * part, and ~1% is the part a family needs in the same breath.
   */
  @Test
  fun `the base rate is derived from the stored funnel and is not a column`() {
    NationalMeritFixture.seedCycle(session, NationalMeritFixture.cycle())

    val read = assertNotNull(NationalMeritCyclesDao.findByCompetitionYear(session, 2028).getOrThrow())

    val share = assertNotNull(read.funnel.finalistShareOfEntrants)
    assertEquals(15_000.0 / 1_200_000.0, share, 1e-12)
    assertTrue(share < 0.02, "the published funnel puts Finalist standing around one per cent of entrants")

    val stored =
      connection
        .prepareStatement(
          "SELECT column_name FROM information_schema.columns WHERE table_name = 'national_merit_cycles'",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.getString("column_name")) }
          }
        }
    assertTrue(
      stored.none { it.contains("rate") || it.contains("share") || it.contains("percent") },
      "a derived figure has no column: found $stored",
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Writes one row from literal SQL and answers with the constraint PostgreSQL
   * refused it with, or null if it was accepted.
   *
   * IT GOES AROUND THE MODELS ON PURPOSE. [CycleWindow], [SelectionIndexScale]
   * and the two data classes' own `require`s make most of these rows
   * unconstructable in Kotlin, which is the point of them -- and it is exactly
   * why the CHECKs still have to be EXECUTED: the database is what a migration,
   * a generated seed and `psql` all write through, and none of those
   * typechecks.
   */
  private fun violatedConstraint(columns: Map<String, String>): String? = violatedConstraintOf(connection, "national_merit_cycles", columns)

  /** One reconstructed cycle, with everything a case is not about already legal. */
  private fun readCycle(
    sponsor: CyclePublication = CyclePublication(NationalMeritFixture.DEFAULT_SPONSOR, SOURCE_URL, null),
    administrator: CyclePublication =
      CyclePublication(NationalMeritFixture.DEFAULT_ADMINISTRATOR, ADMINISTRATOR_URL, null),
    checkedOn: LocalDate = LocalDate.of(2026, 9, 25),
    expiresOn: LocalDate = LocalDate.of(2027, 7, 31),
  ): NationalMeritCycle =
    NationalMeritCycle(
      competitionYear = 2028,
      qualifyingGrade = 11,
      window = CycleWindow.Described("October of the junior year."),
      alternateEntry = AlternateEntry(null, null, "Write to us."),
      selectionIndex = SelectionIndexScale(48, 228),
      semifinalistCutoff = SemifinalistCutoffBasis.STATE_REPRESENTATIONAL,
      commendedCutoff = CommendedCutoffBasis.NATIONAL,
      cutoffAsPublished = "Qualifying scores vary from state to state and from year to year.",
      scoreVisibilityAsPublished = "Scores are not reported to colleges.",
      funnel = NationalMeritFunnel(null, null, null, null, null, null, null, "No figures are published yet."),
      nmscAward = NmscOwnAward(2_500, "A single payment of \$2,500."),
      sponsorCapAsPublished = null,
      sponsor = sponsor,
      administrator = administrator,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  private fun countCycles(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM national_merit_cycles").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
