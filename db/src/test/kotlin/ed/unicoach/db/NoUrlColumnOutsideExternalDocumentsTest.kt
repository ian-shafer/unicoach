package ed.unicoach.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One home for every citation, enforced in the SCHEMA (RFC 228, slice
 * test/05.2/one-home-for-every-citation, brief 0010 D26).
 *
 * The slice's first acceptance criterion is "a table declaring its own
 * `source_url` fails review". A review is a person, so for the rule to be true
 * it has to be a test -- and a grep for one spelling is the guard that can be
 * dodged by naming a column `web_address`. This test reads the live schema
 * instead: every column whose NAME says url or link is matched against an
 * explicit allowlist with a reason.
 *
 * After RFC 228 the allowlist holds exactly one entry, `external_documents.url`.
 * A new url column anywhere fails the suite by name, and the fix is either a
 * foreign key into `external_documents` or a line in the list with a reason.
 *
 * ANTI-VACUITY IS PROVEN, not asserted: the test creates a probe column inside
 * a rolled-back transaction and shows the guard names it, so a typo in the
 * query cannot produce a guard that always passes.
 */
class NoUrlColumnOutsideExternalDocumentsTest {
  companion object {
    private lateinit var connection: Connection

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
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  /**
   * The columns a url-shaped NAME may legitimately appear on, with the reason.
   *
   * A reason is required, so the list is a record and not a shrug: the day a
   * second entry is justified, its reason names the namespace it belongs to
   * (the `PublisherMoneyColumnsAreGoneTest` discipline).
   */
  private val permitted: Map<String, String> =
    mapOf(
      "external_documents.url" to "the one table every citation lives in (RFC 213)",
      "external_documents.url_status" to "the link-rot verdict that rides beside the url, on the same row",
      "external_documents.url_checked_on" to "the day the verdict was established, on the same row",
      "supporter_link_requests.link_id" to
        "a foreign key to the supporter_student_links row an approval made (RFC 254) -- a family link, not a web link",
    )

  /** Every public column whose name says url or link. */
  private fun urlColumns(): Map<String, String> {
    val found = mutableMapOf<String, String>()
    connection
      .prepareStatement(
        """
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND (column_name ILIKE '%url%' OR column_name ILIKE '%link%')
        ORDER BY table_name, column_name
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            found["${rs.getString("table_name")}.${rs.getString("column_name")}"] = ""
          }
        }
      }
    return found
  }

  @Test
  fun `only external_documents declares a url column`() {
    val columns = urlColumns()
    // Anti-vacuity by identity: a typo in the pattern would read an empty set
    // and pass the assertion below, so the one column that MUST be there is
    // held to it first.
    assertTrue(
      "external_documents.url" in columns,
      "the guard must see external_documents.url; it saw [$columns]",
    )
    val offenders = columns.keys - permitted.keys
    assertEquals(
      emptySet(),
      offenders,
      "a table declares its own url-shaped column: [$offenders]. Every citation lives in " +
        "external_documents (RFC 228); replace the column with a foreign key, or add it to " +
        "`permitted` with its reason.",
    )
    // The allowlist is held to the schema too: an entry whose column went away
    // is a permit nobody needs, and it must be deleted rather than left behind.
    val stale = permitted.keys - columns.keys
    assertEquals(
      emptySet(),
      stale,
      "an allowlisted url column no longer exists: [$stale]; delete it from `permitted`.",
    )
  }

  @Test
  fun `the guard can fail, proven inside a rolled-back transaction`() {
    // The probe is a REAL table in the public schema, seen by
    // information_schema, created and dropped in one always-rolled-back
    // transaction. Without this, a wrong query could pass the test above
    // forever because it saw nothing at all.
    connection.autoCommit = false
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("CREATE TABLE guard_probe_owned_by_no_one (source_url TEXT)")
      }
      val columns = urlColumns()
      assertTrue(
        "guard_probe_owned_by_no_one.source_url" in columns,
        "the guard must name a newly added url column; it saw [$columns]",
      )
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
    assertTrue(
      "guard_probe_owned_by_no_one.source_url" !in urlColumns(),
      "the rolled-back probe column must be gone",
    )
  }
}
