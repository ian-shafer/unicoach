package ed.unicoach.db.dao

import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.SourceUrlStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    ExternalDocumentsDao.idOf(session, url).getOrThrow()

    // A caller that is merely CITING knows nothing about whether the page is
    // still served, and must not be able to record "we never asked" over an
    // answer somebody got. That is the check-that-never-ran-looking-like-one-
    // that-passed failure RFC 182 names.
    assertEquals("found", statusOf(url))
    assertEquals(LocalDate.parse("2026-09-14"), checkedOnOf(url))
  }

  @Test
  fun `citing a url we do not hold creates it as never asked`() {
    val url = "${PREFIX}new-page"

    ExternalDocumentsDao.idOf(session, url).getOrThrow()

    assertEquals("unknown", statusOf(url))
    // NULL is "we never asked". A date here would be "we asked and got no usable
    // answer", and the two must stay distinguishable.
    assertNull(checkedOnOf(url))
  }

  @Test
  fun `a status we could not establish keeps its date, and one we never asked for has none`() {
    val asked = "${PREFIX}asked-no-answer"
    val neverAsked = "${PREFIX}never-asked"

    ExternalDocumentsDao
      .upsert(session, NewExternalDocument(asked, SourceUrlStatus.UNKNOWN, LocalDate.parse("2026-09-14")))
      .getOrThrow()
    ExternalDocumentsDao.upsert(session, NewExternalDocument(neverAsked, SourceUrlStatus.UNKNOWN, null)).getOrThrow()

    assertEquals(LocalDate.parse("2026-09-14"), checkedOnOf(asked), "a 403 is asked-and-no-usable-answer, on a day")
    assertNull(checkedOnOf(neverAsked))
    // Both are `unknown` and both keep their link. The DATE is the whole
    // distinction, which is why there are three states and not four.
    assertEquals("unknown", statusOf(asked))
    assertEquals("unknown", statusOf(neverAsked))
  }

  @Test
  fun `a claim to know with no day it was looked at is refused, in Kotlin and in the database`() {
    val url = "${PREFIX}undated-claim"

    assertFailsWith<IllegalArgumentException> {
      NewExternalDocument(url, SourceUrlStatus.FOUND, null)
    }

    val refusal = violatedConstraint(mapOf("url" to "'$url'", "url_status" to "'found'", "url_checked_on" to "NULL"))
    assertEquals("external_documents_checked_when_known", refusal)
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

    ExternalDocumentsDao.idOf(session, url).getOrThrow()

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

  private fun violatedConstraint(columns: Map<String, String>): String? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO external_documents (${columns.keys.joinToString(", ")}) " +
            "VALUES (${columns.values.joinToString(", ")})",
        )
      }
      null
    } catch (e: SQLException) {
      (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint
        ?: "a [${e.sqlState}] with no constraint name: ${e.message}"
    }

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
