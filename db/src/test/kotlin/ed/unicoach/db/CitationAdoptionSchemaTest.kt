package ed.unicoach.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The post-RFC 228 shape of the two remaining cited corpora, executed against
 * the real database: `admission_test_administrations` (the testing calendar)
 * and `policy_parameters` (the federal aid figures).
 *
 * LITERAL SQL ON PURPOSE. The models typecheck the shape away -- a
 * `ScoreRelease` cannot exist without a url, and the DAOs resolve ids first --
 * which is exactly why the CHECKS still have to be executed here: a generated
 * seed, a migration and psql all write through them and none typecheck.
 *
 * BOTH TABLES SHIP COMMITTED ROWS, so this suite never empties them: every
 * probe row uses a key the committed corpus does not carry and deletes exactly
 * that key when it is done.
 */
class CitationAdoptionSchemaTest {
  companion object {
    private lateinit var connection: Connection

    private const val DATES_URL = "https://citation-adoption-test.invalid/dates"
    private const val RELEASE_URL = "https://citation-adoption-test.invalid/release"
    private const val PARAM_URL = "https://citation-adoption-test.invalid/param"

    private val DATES_ID = "(SELECT id FROM external_documents WHERE url = '$DATES_URL')"
    private val RELEASE_ID = "(SELECT id FROM external_documents WHERE url = '$RELEASE_URL')"
    private val PARAM_ID = "(SELECT id FROM external_documents WHERE url = '$PARAM_URL')"

    // A (test, test_date) the committed 2026-27 calendar does not carry.
    private const val PROBE_TEST_DATE = "DATE '2030-10-02'"
    private const val PROBE_AWARD_YEAR = 2028

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt ->
          stmt.execute("DELETE FROM admission_test_administrations WHERE test_date = $PROBE_TEST_DATE")
          stmt.execute("DELETE FROM policy_parameters WHERE award_year = $PROBE_AWARD_YEAR")
        }
        connection.close()
      }
    }
  }

  @BeforeEach
  fun setup() {
    connection.createStatement().use { stmt ->
      listOf(DATES_URL, RELEASE_URL, PARAM_URL).forEach { url ->
        stmt.execute(
          "INSERT INTO external_documents (url, url_status, url_checked_on) " +
            "VALUES ('$url', 'found', DATE '2026-09-11') ON CONFLICT (url) DO NOTHING",
        )
      }
      stmt.execute("DELETE FROM admission_test_administrations WHERE test_date = $PROBE_TEST_DATE")
      stmt.execute("DELETE FROM policy_parameters WHERE award_year = $PROBE_AWARD_YEAR")
    }
  }

  @Test
  fun `the calendar refuses a score release with no page, and a page with no release`() {
    val base =
      mapOf(
        "test" to "'sat'",
        "cycle" to "'2029-30'",
        "test_date" to PROBE_TEST_DATE,
        "registration_deadline" to "DATE '2030-09-17'",
        "late_registration_deadline" to "NULL",
        "score_release_date" to "DATE '2030-10-15'",
        "dates_document_id" to DATES_ID,
        "score_release_document_id" to RELEASE_ID,
        "checked_on" to "DATE '2026-09-11'",
        "expires_on" to "DATE '2031-07-31'",
      )

    // The legal row, so the refusals below are about the pair and not about the
    // fixture itself.
    assertNull(refusalFor(base), "the fixture row must be accepted")

    // A release with no page, and a page with no release: the pair travels
    // together, as RFC 197 required of the two url columns this replaces.
    assertEquals(
      "admission_test_administrations_score_release_document_check",
      violatedConstraint(base + mapOf("score_release_date" to "DATE '2030-10-15'", "score_release_document_id" to "NULL")),
      "a score release with no page must be refused",
    )
    assertEquals(
      "admission_test_administrations_score_release_document_check",
      violatedConstraint(base + mapOf("score_release_date" to "NULL", "score_release_document_id" to RELEASE_ID)),
      "a page with no release date must be refused",
    )
    connection.createStatement().use { it.execute("DELETE FROM admission_test_administrations WHERE test_date = $PROBE_TEST_DATE") }
  }

  @Test
  fun `the calendar refuses a row that cites no dates page`() {
    val missing =
      try {
        connection.createStatement().use { stmt ->
          stmt.execute(
            """
            INSERT INTO admission_test_administrations (
              test, cycle, test_date, registration_deadline, score_release_date,
              dates_document_id, score_release_document_id, checked_on, expires_on
            ) VALUES ('sat', '2029-30', $PROBE_TEST_DATE, DATE '2030-09-17', NULL, NULL, NULL, DATE '2026-09-11', DATE '2031-07-31')
            """.trimIndent(),
          )
        }
        null
      } catch (e: SQLException) {
        e.sqlState
      }
    assertEquals("23502", missing, "a row with no dates_document_id must be refused by NOT NULL")
  }

  @Test
  fun `a federal parameter with no citation cannot be written`() {
    val missing =
      try {
        connection.createStatement().use { stmt ->
          stmt.execute(
            """
            INSERT INTO policy_parameters (award_year, parameter, value, source_name)
            VALUES ($PROBE_AWARD_YEAR, 'pell_max_award_usd', 7500, 'Probe')
            """.trimIndent(),
          )
        }
        null
      } catch (e: SQLException) {
        e.sqlState
      }
    assertEquals("23502", missing, "a parameter with no source_document_id must be refused by NOT NULL")

    // And the legal shape inserts, so the refusal above is about the citation.
    assertNull(
      refusalFor(
        mapOf(
          "award_year" to "$PROBE_AWARD_YEAR",
          "parameter" to "'pell_max_award_usd'",
          "value" to "7500",
          "source_name" to "'Probe'",
          "source_document_id" to PARAM_ID,
        ),
        table = "policy_parameters",
      ),
    )
    connection.createStatement().use { it.execute("DELETE FROM policy_parameters WHERE award_year = $PROBE_AWARD_YEAR") }
  }

  private fun violatedConstraint(columns: Map<String, String>): String? = (refusalFor(columns) as? Refusal)?.constraint

  private fun refusalFor(
    columns: Map<String, String>,
    table: String = "admission_test_administrations",
  ): Any? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("INSERT INTO $table (${columns.keys.joinToString(", ")}) VALUES (${columns.values.joinToString(", ")})")
      }
      null
    } catch (e: SQLException) {
      Refusal(
        constraint = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint,
        sqlState = e.sqlState,
      )
    }

  private data class Refusal(
    val constraint: String?,
    val sqlState: String?,
  )
}
