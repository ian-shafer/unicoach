package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestStateProgramme
import ed.unicoach.db.models.GradeRange
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.SchoolYearBasis
import ed.unicoach.db.models.TestChooser
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `admission_test_state_programmes` corpus, its additional-source child rows
 * and their DAO (RFC 208), against the real database.
 *
 * [AdmissionTestCalendarDaoTest] is the precedent and this differs from it in
 * one way, because the table differs in one way: the calendar has ONE current
 * shape, so its suite can assert against the migration's own rows, while this
 * corpus is append-only history whose answers are per state -- so the suite
 * needs a corpus it chose, and writes one.
 *
 * IT PUTS THE MIGRATION'S ROWS BACK. The committed corpus is saved into two
 * session-local temporary tables before the first test and restored after the
 * last, so a suite that runs later in the same Gradle invocation finds the
 * database as `db-reset` left it. The alternative -- truncating and walking away
 * -- makes every later suite's answer depend on test ordering, and the failure
 * it produces names this file nowhere.
 */
class AdmissionTestStateProgrammeDaoTest {
  companion object {
    private lateinit var connection: Connection

    /** The page every literal-SQL row in this suite cites (RFC 213). */
    private const val TN_SOURCE_URL = "https://tn.gov/education/testing"

    /**
     * A legal Tennessee row as SQL literals, which every bad row in this suite
     * is one override away from: a case that had to restate fourteen columns
     * would hide which column it was actually about.
     */
    private val PROGRAMME_COLUMNS: Map<String, String> =
      linkedMapOf(
        "state" to "'TN'",
        "school_year" to "2026",
        "school_year_basis" to "'stated_by_source'",
        "participation" to "'required'",
        "school_must_offer" to "NULL",
        "test_chooser" to "'district'",
        "test" to "NULL",
        "lowest_grade" to "11",
        "highest_grade" to "11",
        "state_funded" to "TRUE",
        // The citation is a REFERENCE now (RFC 213). The document is created in
        // [setup] before every case, so a literal-SQL row can name it by url
        // without this map having to carry an id that changes per run.
        "source_document_id" to "(SELECT id FROM external_documents WHERE url = '$TN_SOURCE_URL')",
        "archive_document_id" to "NULL",
        "checked_on" to "DATE '2026-09-14'",
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
        stmt.execute("CREATE TEMP TABLE saved_programmes AS SELECT * FROM admission_test_state_programmes")
      }
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt ->
          stmt.execute("DELETE FROM admission_test_state_programmes")
          stmt.execute("INSERT INTO admission_test_state_programmes SELECT * FROM saved_programmes")
          stmt.execute("DROP TABLE saved_programmes")
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
    AdmissionTestStateProgrammeFixture.deleteAll(session)
    // The document the literal-SQL rows below cite. It is created and never
    // deleted: external_documents is shared by the committed corpus this suite
    // restores, so a test that emptied it would take the corpus's citations with
    // it.
    //
    // IT STATES ITS EVIDENCE like every other writer since RFC 220 -- a fixture
    // is not exempt from the rule, and a bare insert here is refused by the
    // column exactly as one in production would be.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) " +
          "VALUES ('$TN_SOURCE_URL', 'found', DATE '2026-09-14') ON CONFLICT (url) DO NOTHING",
      )
    }
  }

  @Test
  fun `a programme row round-trips, and its unknowns come back unknown`() {
    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(
        AdmissionTestStateProgrammeFixture.programme(
          state = "VA",
          participation = SchoolDayParticipation.OFFERED,
          schoolMustOffer = true,
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.SAT),
          grades = GradeRange(11, 12),
          stateFunded = null,
          archiveUrl = "https://web.archive.org/va",
        ),
      ),
    )

    val row = AdmissionTestStateProgrammeDao.listNewestPerState(session).getOrThrow().single()

    assertEquals("VA", row.state)
    assertEquals(AcademicYear(AdmissionTestStateProgrammeFixture.DEFAULT_SCHOOL_YEAR), row.schoolYear)
    assertEquals(SchoolYearBasis.STATED_BY_SOURCE, row.schoolYearBasis)
    assertEquals(SchoolDayParticipation.OFFERED, row.participation)
    assertEquals(true, row.schoolMustOffer)
    assertEquals(SchoolDayTestChoice.StateChose(AdmissionTest.SAT), row.choice)
    assertEquals(TestChooser.STATE, row.choice.chooser)
    assertEquals(GradeRange(11, 12), row.grades)
    // THE CITATION SURVIVES THE MOVE (RFC 213). Both urls live in
    // external_documents now; the read joins them back, and nothing above this
    // DAO can tell that the storage changed.
    assertEquals(AdmissionTestStateProgrammeFixture.defaultSourceUrl("VA"), row.sourceUrl)
    assertEquals("https://web.archive.org/va", row.archiveUrl)
    assertEquals(AdmissionTestStateProgrammeFixture.DEFAULT_CHECKED_ON, row.checkedOn)
    assertEquals(AdmissionTestStateProgrammeFixture.DEFAULT_EXPIRES_ON, row.expiresOn)
    // The one that matters most: an unread funding sentence comes back as
    // UNKNOWN and never as `false`, because "the state does not pay" is a claim
    // with no page behind it.
    assertNull(row.stateFunded, "a state page that does not say who pays must read back as null")
  }

  @Test
  fun `a locally-chosen row names no test, and reads back as the chooser it stored`() {
    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(
        AdmissionTestStateProgrammeFixture.programme(
          state = "SC",
          choice = SchoolDayTestChoice.ChosenLocally(TestChooser.STUDENT),
          grades = null,
        ),
      ),
    )

    val row = AdmissionTestStateProgrammeDao.listNewestPerState(session).getOrThrow().single()

    assertEquals(SchoolDayTestChoice.ChosenLocally(TestChooser.STUDENT), row.choice)
    assertNull(row.grades, "a source that named no grade must read back as no grade, never as a guessed one")
  }

  @Test
  fun `a second school year is a second row, and the newest is the one read`() {
    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(
        AdmissionTestStateProgrammeFixture.programme(
          state = "IL",
          schoolYear = AcademicYear(2025),
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.SAT),
          checkedOn = AdmissionTestStateProgrammeFixture.DEFAULT_CHECKED_ON.minusYears(1),
          expiresOn = AdmissionTestStateProgrammeFixture.DEFAULT_EXPIRES_ON.minusYears(1),
        ),
        AdmissionTestStateProgrammeFixture.programme(
          state = "IL",
          schoolYear = AcademicYear(2026),
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.ACT),
        ),
      ),
    )

    assertEquals(2, countRows("admission_test_state_programmes"), "a re-read in a later year APPENDS; it replaces nothing")
    val newest = AdmissionTestStateProgrammeDao.listNewestPerState(session).getOrThrow().single()
    assertEquals(AcademicYear(2026), newest.schoolYear)
    // Illinois really did move from the SAT to the ACT, which is why keeping the
    // older row is worth the second row: last year's answer stays true about
    // last year, and today's answer is the newer one.
    assertEquals(SchoolDayTestChoice.StateChose(AdmissionTest.ACT), newest.choice)
  }

  @Test
  fun `a same-year correction rewrites the row in place`() {
    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN", stateFunded = null)),
    )
    val first = AdmissionTestStateProgrammeDao.listNewestPerState(session).getOrThrow().single()

    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN", stateFunded = true)),
    )

    assertEquals(1, countRows("admission_test_state_programmes"), "a corrected re-read of one year is one row, not two")
    val corrected = AdmissionTestStateProgrammeDao.listNewestPerState(session).getOrThrow().single()
    assertEquals(first.id, corrected.id, "the correction must be the same row, on the (state, school_year) key")
    assertEquals(true, corrected.stateFunded)
  }

  @Test
  fun `the read costs one statement, whatever the size of the corpus`() {
    val empty = CountingSqlSession(session)
    assertEquals(emptyList<AdmissionTestStateProgramme>(), AdmissionTestStateProgrammeDao.listNewestPerState(empty).getOrThrow())
    assertEquals(1, empty.prepared.size, "an empty corpus still costs exactly its one read: ${empty.prepared}")

    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(
        AdmissionTestStateProgrammeFixture.programme(state = "TN"),
        AdmissionTestStateProgrammeFixture.programme(state = "SC"),
        AdmissionTestStateProgrammeFixture.programme(state = "VA"),
      ),
    )
    val counting = CountingSqlSession(session)
    assertEquals(3, AdmissionTestStateProgrammeDao.listNewestPerState(counting).getOrThrow().size)
    assertEquals(
      1,
      counting.prepared.size,
      "the read is one DISTINCT ON over the whole corpus, never one statement per row: ${counting.prepared}",
    )
  }

  /**
   * The SHAPE is all the database checks today, and this test says so out loud
   * rather than pretending otherwise: 'zz' and 'USA' are refused here, and 'ZZ'
   * -- a well-shaped code that is nobody's jurisdiction -- is NOT, because the
   * foreign key that would catch it cannot exist until `us_states` is populated
   * at migration time (`test/05.3/own-our-codebooks`; the DDL says why).
   *
   * MEMBERSHIP is checked where the fix is, over the committed file, by
   * [AdmissionTestStateProgrammeSeedTest]. A test that asserted a database
   * refusal we do not have would be a test asserting a guarantee nobody holds.
   */
  @Test
  fun `a badly shaped state code is refused, and a well-shaped unknown one is not`() {
    val failure =
      assertFailsWith<ConstraintViolationException> {
        AdmissionTestStateProgrammeFixture.seed(session, listOf(AdmissionTestStateProgrammeFixture.programme(state = "usa")))
      }
    assertEquals("admission_test_state_programmes_state_format_check", failure.constraint)
    assertEquals(0, countRows("admission_test_state_programmes"))

    AdmissionTestStateProgrammeFixture.seed(session, listOf(AdmissionTestStateProgrammeFixture.programme(state = "ZZ")))
    assertEquals(
      1,
      countRows("admission_test_state_programmes"),
      "'ZZ' is well shaped and unpublished, and the database cannot tell yet -- the committed-file test is what does",
    )
  }

  @Test
  fun `every CHECK on the programme table refuses its own bad row, by name`() {
    val cases =
      listOf(
        "admission_test_state_programmes_school_year_basis_check" to mapOf("school_year_basis" to "'stated_by_the_moon'"),
        // A row that says the year is OURS must carry the year we actually read
        // it in, or 'reading_year' is an unfalsifiable label on any year.
        "admission_test_state_programmes_reading_year_check" to
          mapOf("school_year_basis" to "'reading_year'", "school_year" to "2025"),
        // A page may name NEXT year; a typo'd 2028 would become that state's
        // permanent answer AND would never be stale.
        "admission_test_state_programmes_school_year_horizon_check" to mapOf("school_year" to "2028"),
        "admission_test_state_programmes_participation_check" to mapOf("participation" to "'encouraged'"),
        "admission_test_state_programmes_test_chooser_check" to mapOf("test_chooser" to "'principal'"),
        "admission_test_state_programmes_test_check" to mapOf("test_chooser" to "'state'", "test" to "'psat'"),
        // A district-chosen row carrying a test says the state chose after all.
        "admission_test_state_programmes_test_pairing_check" to mapOf("test_chooser" to "'district'", "test" to "'sat'"),
        // A state-chosen row carrying none tells a family to sit something unnamed.
        "admission_test_state_programmes_test_pairing_check" to mapOf("test_chooser" to "'state'", "test" to "NULL"),
        "admission_test_state_programmes_grade_pairing_check" to mapOf("highest_grade" to "NULL"),
        "admission_test_state_programmes_grade_range_check" to mapOf("lowest_grade" to "12", "highest_grade" to "11"),
        // The url SHAPE checks moved to external_documents with the urls
        // themselves (RFC 213) and are exercised in [ExternalDocumentsDaoTest].
        // What is left here is the rule this table still owns: an archived
        // address that IS the page is the same link twice, and a coach told to
        // offer both links would print one url as two.
        "admission_test_state_programmes_archive_is_not_the_source_check" to
          mapOf(
            "archive_document_id" to "(SELECT id FROM external_documents WHERE url = '$TN_SOURCE_URL')",
          ),
        "admission_test_state_programmes_expiry_after_checked_check" to mapOf("expires_on" to "DATE '2026-09-14'"),
      )

    // Every case is run and every mismatch reported, so one failing CHECK does
    // not hide the twelve behind it -- the same rule the seed generators follow.
    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val actual = violatedConstraint(PROGRAMME_COLUMNS + overrides)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countRows("admission_test_state_programmes"), "no refused row may be left behind")
  }

  @Test
  fun `a programme row cannot cite nothing, and a cited document cannot be deleted`() {
    AdmissionTestStateProgrammeFixture.seed(
      session,
      listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")),
    )

    // NOT NULL, not a CHECK: a row that cites nothing is the uncitable row the
    // corpus exists to prevent, and it is the guarantee `source_url NOT NULL`
    // gave before the move.
    val orphan = PROGRAMME_COLUMNS + mapOf("state" to "'KY'", "source_document_id" to "NULL")
    val refusal = violatedConstraint(orphan)
    assertTrue(
      refusal != null && refusal.contains("source_document_id"),
      "a programme row with no source document must be refused; got [$refusal]",
    )

    val failure =
      assertFailsWith<SQLException> {
        connection.createStatement().use { stmt ->
          stmt.execute("DELETE FROM external_documents WHERE url = '${AdmissionTestStateProgrammeFixture.defaultSourceUrl("TN")}'")
        }
      }
    // A document outlives every reading of it. Deleting one out from under a
    // citation must fail rather than cascade: the reading is the thing that
    // would become uncitable.
    assertEquals("23503", (failure as? PSQLException)?.sqlState, "deleting a cited document must be refused")
  }

  /**
   * Writes one row from literal SQL and returns the constraint PostgreSQL
   * refused it with, or null if it was accepted.
   *
   * IT GOES AROUND THE MODEL ON PURPOSE. [SchoolDayTestChoice], [GradeRange] and
   * the enums make most of these rows unconstructable in Kotlin, which is the
   * point of them -- and it is exactly why the CHECKS still have to be executed:
   * the database is what a generated seed, a migration and `psql` all write
   * through, and none of those typecheck.
   */
  private fun violatedConstraint(
    columns: Map<String, String>,
    table: String = "admission_test_state_programmes",
  ): String? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("INSERT INTO $table (${columns.keys.joinToString(", ")}) VALUES (${columns.values.joinToString(", ")})")
      }
      null
    } catch (e: SQLException) {
      (e as? PSQLException)?.serverErrorMessage?.constraint
        ?: "a [${e.sqlState}] with no constraint name: ${e.message}"
    }

  private fun countRows(table: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
