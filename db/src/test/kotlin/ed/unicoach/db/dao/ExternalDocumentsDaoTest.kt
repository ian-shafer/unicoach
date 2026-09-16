package ed.unicoach.db.dao

import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.SourceUrlStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `external_documents` and its DAO (RFC 213), against the real database.
 *
 * IT CLEANS UP BY URL PREFIX AND NEVER BY TRUNCATION. The committed
 * state-programme corpus cites rows of this table, so a suite that emptied it
 * would take those citations with it and leave every later suite in the same
 * Gradle invocation reading a corpus that no longer joins.
 */
class ExternalDocumentsDaoTest {
  companion object {
    private lateinit var connection: Connection

    /** Every url this suite writes starts here, and nothing else in the tree does. */
    private const val PREFIX = "https://external-documents-test.invalid/"

    /** Postgres' code for "a required value was not supplied". */
    private const val NOT_NULL_VIOLATION = "23502"

    /** How [refusalOf] names that refusal, since it carries no constraint name of its own. */
    private const val NOT_NULL_ON = "NOT NULL on %s"

    /**
     * The evidence a citation has carried since RFC 220, for the tests that are
     * about something else entirely.
     */
    private val EVIDENCE = mapOf("url_status" to "'found'", "url_checked_on" to "DATE '2026-09-14'")

    /**
     * The migration under test, executed verbatim rather than quoted
     * ([SourceUrlStatusSchemaPinTest]'s relative path idiom: the test's working
     * directory is the `db` module).
     */
    private val MIGRATION = File("schema/require-a-check-on-every-external-document.sql")

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
        deleteOurs()
        connection.close()
      }
    }

    private fun deleteOurs() {
      connection.createStatement().use { stmt ->
        stmt.execute("DELETE FROM external_documents WHERE url LIKE '$PREFIX%'")
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun setup() {
    deleteOurs()
  }

  @Test
  fun `a document is one row per url, and a re-sweep overwrites what it just measured`() {
    val url = "${PREFIX}state-page"
    val first =
      ExternalDocumentsDao
        .upsert(
          session,
          NewExternalDocument(url, SourceUrlStatus.FOUND, LocalDate.parse("2026-09-14")),
        ).getOrThrow()
    val second =
      ExternalDocumentsDao
        .upsert(
          session,
          NewExternalDocument(url, SourceUrlStatus.NOT_FOUND, LocalDate.parse("2026-10-01")),
        ).getOrThrow()

    assertEquals(first, second, "the url is the natural key: the same document twice is one row")
    assertEquals(1, countOurs())
    // The pair is a fact ABOUT the url this statement is writing. Leaving the
    // earlier verdict in place would attach yesterday's answer to today's url.
    assertEquals("not_found", statusOf(url))
    assertEquals(LocalDate.parse("2026-10-01"), checkedOnOf(url))
  }

  @Test
  fun `citing a document does not overwrite what a sweep found`() {
    val url = "${PREFIX}cited-page"
    ExternalDocumentsDao.upsert(session, NewExternalDocument(url, SourceUrlStatus.FOUND, LocalDate.parse("2026-09-14"))).getOrThrow()

    ExternalDocumentsDao
      .idOf(session, NewExternalDocument(url, SourceUrlStatus.NOT_FOUND, LocalDate.parse("2026-10-01")))
      .getOrThrow()

    // A caller that is merely CITING must not be able to record what it happens
    // to believe over an answer a sweep got. That is the check-that-never-ran-
    // looking-like-one-that-passed failure RFC 182 names. Since RFC 220 the
    // citer always carries evidence, so this is the stronger version of the same
    // claim: even a contradicting verdict does not land on an existing row.
    assertEquals("found", statusOf(url))
    assertEquals(LocalDate.parse("2026-09-14"), checkedOnOf(url))
  }

  @Test
  fun `citing a url we do not hold creates it with the evidence the citer supplied`() {
    val url = "${PREFIX}new-page"

    ExternalDocumentsDao
      .idOf(session, NewExternalDocument(url, SourceUrlStatus.FOUND, LocalDate.parse("2026-09-14")))
      .getOrThrow()

    // There is no "never asked" row to create any more (RFC 220). A citer states
    // what it knows, and for a document nothing has swept that is what the row
    // holds.
    assertEquals("found", statusOf(url))
    assertEquals(LocalDate.parse("2026-09-14"), checkedOnOf(url))
  }

  @Test
  fun `a status we could not establish still keeps its date`() {
    val asked = "${PREFIX}asked-no-answer"

    ExternalDocumentsDao
      .upsert(session, NewExternalDocument(asked, SourceUrlStatus.UNKNOWN, LocalDate.parse("2026-09-14")))
      .getOrThrow()

    // A 403, a timeout, a DNS fault: we asked and got no usable answer, on a
    // day. That row is exactly as storable as it ever was -- nine of the
    // committed corpus's rows hold it. What RFC 220 removed is the FOURTH state,
    // a url nobody checked, and it removed it by making the date required.
    assertEquals("unknown", statusOf(asked))
    assertEquals(LocalDate.parse("2026-09-14"), checkedOnOf(asked), "a 403 is asked-and-no-usable-answer, on a day")
  }

  @Test
  fun `a citation with no evidence at all is refused by the columns themselves`() {
    // The acceptance criterion of RFC 220, driven as written. The DEFAULT is
    // gone, so a url on its own leaves url_status NULL and its own NOT NULL --
    // which the table has always had -- refuses the row.
    assertEquals(
      NOT_NULL_ON.format("url_status"),
      refusalOf(mapOf("url" to "'${PREFIX}bare-insert'")),
      "INSERT INTO external_documents (url) VALUES (...) must fail",
    )

    // And a status with no day it was established on.
    assertEquals(
      NOT_NULL_ON.format("url_checked_on"),
      refusalOf(mapOf("url" to "'${PREFIX}undated-claim'", "url_status" to "'found'")),
      "you cannot claim to know without saying when you looked",
    )

    // The pair CHECK that used to carry half of this is gone, because a NOT NULL
    // date makes it trivially true. Nothing may re-add it silently.
    assertNull(
      refusalOf(mapOf("url" to "'${PREFIX}unknown-with-a-date'", "url_status" to "'unknown'", "url_checked_on" to "DATE '2026-09-14'")),
      "unknown WITH a date is a first-class row and must still insert",
    )
  }

  @Test
  fun `every url CHECK refuses its own bad url, by name`() {
    val cases =
      listOf(
        "external_documents_url_nonempty_check" to "''",
        // The scheme is STATED, never guessed at: prepending https:// would be
        // inventing part of an address a human was meant to supply.
        "external_documents_url_shape_check" to "'example.com/cds.pdf'",
        "external_documents_url_shape_check" to "'mailto:registrar@example.com'",
        "external_documents_url_shape_check" to "'ftp://example.com/cds.pdf'",
      )

    val mismatches =
      cases.mapNotNull { (expected, url) ->
        val actual = violatedConstraint(mapOf("url" to url))
        if (actual == expected) null else "url $url expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the url it exists to refuse")
  }

  @Test
  fun `http is accepted, because six of our seven archived addresses are http`() {
    val url = "${PREFIX}plain-http".replace("https://", "http://")

    ExternalDocumentsDao
      .idOf(session, NewExternalDocument(url, SourceUrlStatus.FOUND, LocalDate.parse("2026-09-14")))
      .getOrThrow()

    assertTrue(countRows("external_documents", "url = '$url'") == 1, "http is accepted; https is preferred, not required")
    connection.createStatement().use { stmt -> stmt.execute("DELETE FROM external_documents WHERE url = '$url'") }
  }

  @Test
  fun `a status outside the vocabulary is refused by the domain`() {
    val refusal = violatedConstraint(mapOf("url" to "'${PREFIX}bad-status'", "url_status" to "'maybe'"))
    // A DOMAIN, so the refusal names the domain's constraint rather than a
    // per-table one -- the point of declaring the vocabulary once.
    assertEquals("link_status_check", refusal)
  }

  @Test
  fun `the migration refuses a database holding a dateless row, and invents nothing`() {
    // The case cannot be reached forwards -- the whole point of the migration is
    // that a dateless row can no longer be written -- so it is staged backwards,
    // inside ONE transaction that is always rolled back: the table is returned
    // to its pre-migration shape, given the row the migration exists to refuse,
    // and then the COMMITTED migration file is executed verbatim. A copy of the
    // SQL in this test would prove only that the copy refuses.
    val url = "${PREFIX}dateless"
    val refusal =
      connection.autoCommitOff {
        connection.createStatement().use { stmt ->
          stmt.execute(
            """
            ALTER TABLE external_documents
                ALTER COLUMN url_status SET DEFAULT 'unknown',
                ALTER COLUMN url_checked_on DROP NOT NULL,
                ADD CONSTRAINT external_documents_checked_when_known
                    CHECK (url_status = 'unknown' OR url_checked_on IS NOT NULL)
            """.trimIndent(),
          )
          stmt.execute("INSERT INTO external_documents (url) VALUES ('$url')")
        }

        val raised =
          assertFailsWith<SQLException> {
            connection.createStatement().use { stmt -> stmt.execute(MIGRATION.readText()) }
          }
        raised
      }

    assertEquals("P0001", refusal.sqlState, "the guard refuses by raising, not by any incidental failure")
    assertTrue(
      refusal.message!!.contains("holds [1] row(s) with no url_checked_on"),
      "the refusal names how many rows it found; got: ${refusal.message}",
    )
    assertTrue(
      refusal.message!!.contains("bin/check-link"),
      "the refusal names the tool that produces the missing answer; got: ${refusal.message}",
    )

    // Rolled back: the staged row is gone, and so is the old shape. Asserted,
    // never assumed -- a leaked ALTER would reopen the hole for every later test
    // in this Gradle invocation.
    assertEquals(0, countRows("external_documents", "url = '$url'"))
    assertEquals(
      NOT_NULL_ON.format("url_checked_on"),
      refusalOf(mapOf("url" to "'${PREFIX}after-rollback'", "url_status" to "'found'")),
      "the tightened schema survives the rolled-back transaction",
    )
  }

  /**
   * Runs [body] in one explicit transaction and ALWAYS rolls it back, restoring
   * auto-commit whatever happened. The DDL above takes an ACCESS EXCLUSIVE lock
   * on one table for the few milliseconds it is held.
   */
  private fun <T> Connection.autoCommitOff(body: () -> T): T {
    autoCommit = false
    try {
      return body()
    } finally {
      rollback()
      autoCommit = true
    }
  }

  /**
   * The refusal an INSERT of EXACTLY [columns] earns, as one stable string: the
   * constraint or domain name where one is named, `NOT NULL on <column>` where a
   * required value is missing, and null where the row inserted.
   *
   * A NOT NULL violation carries no constraint NAME, only a column, so a helper
   * that read `constraint` alone would report every one of them identically and
   * a test asserting on it would pass for the wrong reason.
   */
  private fun refusalOf(columns: Map<String, String>): String? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO external_documents (${columns.keys.joinToString(", ")}) " +
            "VALUES (${columns.values.joinToString(", ")})",
        )
      }
      null
    } catch (e: SQLException) {
      val server = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
      when {
        server?.constraint != null -> server.constraint
        e.sqlState == NOT_NULL_VIOLATION && server?.column != null -> NOT_NULL_ON.format(server.column)
        else -> "a [${e.sqlState}] with no constraint name: ${e.message}"
      }
    }

  /**
   * As [refusalOf], but supplying the evidence every row has required since
   * RFC 220, so a test about the URL is refused BY the url rule rather than by
   * the missing status it did not mean to omit.
   */
  private fun violatedConstraint(columns: Map<String, String>): String? = refusalOf(EVIDENCE + columns)

  private fun statusOf(url: String): String? = scalar("SELECT url_status FROM external_documents WHERE url = '$url'")

  private fun checkedOnOf(url: String): LocalDate? =
    scalar("SELECT url_checked_on FROM external_documents WHERE url = '$url'")?.let(LocalDate::parse)

  private fun scalar(sql: String): String? =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        if (rs.next()) rs.getString(1) else null
      }
    }

  private fun countOurs(): Int = countRows("external_documents", "url LIKE '$PREFIX%'")

  private fun countRows(
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
