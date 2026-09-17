package ed.unicoach.db

import ed.unicoach.db.dao.CodebookReferenceFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `source_documents` adoption of `external_documents` (RFC 228), executed
 * against the real database.
 *
 * THE REFUSALS ARE STAGED BACKWARDS, inside one transaction that is always
 * rolled back: the migration can no longer be reached forwards -- the whole
 * point is that a dateless page cannot be stored -- so the table is returned to
 * its pre-migration shape, given the row the migration exists to refuse, and
 * then the COMMITTED migration file is executed verbatim. A copy of the SQL in
 * this test would prove only that the copy refuses.
 */
class SourceDocumentsAdoptionTest {
  companion object {
    private lateinit var connection: Connection

    private val MIGRATION = File("schema/adopt-external-documents-for-source-documents.sql")

    private const val PAGE_URL = "https://source-documents-adoption-test.invalid/page"
    private const val MIRROR_URL = "https://source-documents-adoption-test.invalid/mirror"

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
      // colleges.state keys into us_states (0067), and a migrated-only database
      // holds no codebook rows -- the same emptiness this brief's test/05.3
      // slice is about. The fixture seeds them, exactly as
      // CollegeScoreBandsMigrationTest does before inserting a college.
      val session =
        object : ed.unicoach.db.dao.SqlSession {
          override fun prepareStatement(sql: String): java.sql.PreparedStatement = connection.prepareStatement(sql)
        }
      CodebookReferenceFixture.seed(session)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  // The pre-migration shape of `source_documents`, restored inside the rolled-
  // back transaction so the committed migration can be executed against it: the
  // column list is what `record-source-url-status.sql` added and the adoption
  // migration drops, and the CHECK is the one it drops by name. One statement
  // per `execute`, because the PG JDBC driver does not run a multi-statement
  // string past its first statement.
  private val preMigrationShape =
    listOf(
      "ALTER TABLE source_documents DROP CONSTRAINT source_documents_archive_is_not_the_source_check",
      "ALTER TABLE source_documents DROP COLUMN external_document_id",
      "ALTER TABLE source_documents DROP COLUMN archive_external_document_id",
      "ALTER TABLE source_documents ADD COLUMN source_url TEXT",
      "ALTER TABLE source_documents ADD COLUMN archive_url TEXT",
      "ALTER TABLE source_documents ADD COLUMN source_url_status TEXT NOT NULL DEFAULT 'unknown' " +
        "CHECK (source_url_status IN ('unknown', 'found', 'not_found'))",
      "ALTER TABLE source_documents ADD COLUMN source_url_checked_on DATE",
      "ALTER TABLE source_documents ADD CONSTRAINT source_documents_checked_when_known " +
        "CHECK (source_url_status = 'unknown' OR source_url_checked_on IS NOT NULL)",
    )

  private fun insertCollege(): String {
    // source_documents is college-keyed, so the staged row needs a college. The
    // migrated-only test database holds no colleges (they arrive at ingest), so
    // one is inserted here, like CollegeScoreBandsMigrationTest does, and its
    // real, database-generated id is returned.
    return connection.createStatement().use { stmt ->
      stmt
        .executeQuery(
          """
          INSERT INTO colleges (ipeds_unit_id, name, city, state, control)
          VALUES (900000 + (SELECT COUNT(*) FROM colleges), 'Adoption Probe', 'Townsville', 'CA', 1)
          RETURNING id
          """.trimIndent(),
        ).use { rs ->
          rs.next()
          rs.getString("id")
        }
    }
  }

  /** Runs [body] in one explicit transaction and ALWAYS rolls it back. */
  private fun <T> Connection.autoCommitOff(body: () -> T): T {
    autoCommit = false
    try {
      return body()
    } finally {
      rollback()
      autoCommit = true
    }
  }

  @Test
  fun `a page with no recorded check refuses the migration, by name and count`() {
    val refusal =
      connection.autoCommitOff {
        val collegeId = insertCollege()
        connection.createStatement().use { stmt ->
          preMigrationShape.forEach { stmt.execute(it) }
          stmt.execute(
            """
            INSERT INTO source_documents (college_id, source, academic_year, source_url, archive_url, source_url_status, source_url_checked_on)
            VALUES ('$collegeId', 'common_data_set', 2025, '$PAGE_URL', NULL, 'unknown', NULL)
            """.trimIndent(),
          )
        }
        assertFailsWith<SQLException> {
          connection.createStatement().use { stmt -> stmt.execute(MIGRATION.readText()) }
        }
      }

    assertEquals("P0001", refusal.sqlState, "the guard refuses by raising, not by any incidental failure")
    assertTrue(
      refusal.message!!.contains("never checked"),
      "the refusal names the defect; got: ${refusal.message}",
    )
    assertTrue(
      refusal.message!!.contains("bin/cds-link-sweep"),
      "the refusal names the tool that produces the missing answer; got: ${refusal.message}",
    )

    // Rolled back: the staged row is gone, and the post-migration shape is
    // back -- no probe document was left behind by the staged insert.
    assertEquals(0, countWhere("external_documents", "url = '$PAGE_URL'"), "no staged document survives the rollback")
  }

  @Test
  fun `a mirror nobody asked about is left unlinked, never dated`() {
    connection.autoCommitOff {
      val collegeId = insertCollege()
      connection.createStatement().use { stmt ->
        preMigrationShape.forEach { stmt.execute(it) }
        stmt.execute(
          """
          INSERT INTO source_documents (college_id, source, academic_year, source_url, archive_url, source_url_status, source_url_checked_on)
          VALUES ('$collegeId', 'common_data_set', 2025, '$PAGE_URL', '$MIRROR_URL', 'found', DATE '2026-09-13')
          """.trimIndent(),
        )
      }
      connection.createStatement().use { stmt -> stmt.execute(MIGRATION.readText()) }

      // The page carried evidence and became a document with it; the mirror did
      // not, so it stayed NULL. The alternative -- writing a date nobody
      // established -- is the invention this slice exists to refuse.
      val row =
        connection.createStatement().use { stmt ->
          stmt
            .executeQuery(
              """
              SELECT d.url, d.url_status, d.url_checked_on,
                     sd.archive_external_document_id
              FROM source_documents sd
              JOIN external_documents d ON d.id = sd.external_document_id
              WHERE sd.college_id = '$collegeId'
              """.trimIndent(),
            ).use { rs ->
              rs.next()
              mapOf(
                "url" to rs.getString("url"),
                "status" to rs.getString("url_status"),
                "date" to rs.getString("url_checked_on"),
                "archiveId" to rs.getString("archive_external_document_id"),
              )
            }
        }
      assertEquals(PAGE_URL, row["url"])
      assertEquals("found", row["status"])
      assertEquals("2026-09-13", row["date"])
      assertNull(row["archiveId"], "a mirror with no check must not be linked")
      assertEquals(0, countWhere("external_documents", "url = '$MIRROR_URL'"), "a mirror with no check must not be stored")
    }
  }

  @Test
  fun `the migrated table enforces its new shape`() {
    // Two cases, two transactions: a failed INSERT aborts the surrounding
    // PostgreSQL transaction, so a second refusal cannot be read after the
    // first inside one block.
    connection.autoCommitOff {
      val collegeId = insertCollege()
      connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO external_documents (url, url_status, url_checked_on) " +
            "VALUES ('$PAGE_URL', 'found', DATE '2026-09-13') ON CONFLICT (url) DO NOTHING",
        )
        // A row that cites nothing is the uncitable row this table exists to
        // prevent: NOT NULL, so no constraint name, only the column.
        val missing =
          try {
            stmt.execute(
              "INSERT INTO source_documents (college_id, source, academic_year) " +
                "VALUES ('$collegeId', 'common_data_set', 2025)",
            )
            null
          } catch (e: SQLException) {
            e.sqlState
          }
        assertEquals("23502", missing, "a row with no external_document_id must be refused by NOT NULL")
      }
    }

    connection.autoCommitOff {
      val collegeId = insertCollege()
      connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO external_documents (url, url_status, url_checked_on) " +
            "VALUES ('$PAGE_URL', 'found', DATE '2026-09-13') ON CONFLICT (url) DO NOTHING",
        )
        val pageId = "(SELECT id FROM external_documents WHERE url = '$PAGE_URL')"
        // An archive may not BE the page.
        val archiveIsSource =
          try {
            stmt.execute(
              "INSERT INTO source_documents (college_id, source, academic_year, external_document_id, archive_external_document_id) " +
                "VALUES ('$collegeId', 'common_data_set', 2025, $pageId, $pageId)",
            )
            null
          } catch (e: SQLException) {
            (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint
          }
        assertEquals("source_documents_archive_is_not_the_source_check", archiveIsSource)
      }
    }
  }

  private fun countWhere(
    table: String,
    where: String,
  ): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
