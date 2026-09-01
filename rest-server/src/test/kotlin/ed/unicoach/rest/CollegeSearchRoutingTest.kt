package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/v1/colleges` (RFC 137): validation, auth, and response shape.
 * Same live-server harness as [CollegeListRoutingTest].
 */
class CollegeSearchRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)

      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      dbConnection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "csr${UUID.randomUUID()}@company.com"

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun registerAndGetCookie(): String {
    val email = uniqueEmail()
    val req = RegisterRequest(email, "Password123!", "College Search User")
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    markEmailVerified(email)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  /**
   * The published `us_states` row `colleges.state` foreign-keys into since
   * migration 0067. Seeded here rather than in a `@BeforeEach`: this suite
   * shares an un-truncated dev database and only [seedCollege] needs the rows,
   * and the fixture is idempotent, so the cheapest correct place is the one
   * call site that would otherwise fail.
   */
  private fun seedCodebookReference() = CodebookReferenceFixture.seed(dbConnection)

  /** A uniquely-named college this test alone matches, on the shared un-truncated dev DB. */
  private fun seedCollege(name: String): UUID {
    val id = UUID.randomUUID()
    val uniqueIpedsUnitId = (id.leastSignificantBits and 0x3FFFFFFF).toInt()
    seedCodebookReference()
    withTransaction {
      dbConnection
        .prepareStatement(
          """
          INSERT INTO colleges (id, ipeds_unit_id, name, city, state, control, undergrad_enrollment_headcount)
          VALUES (?, ?, ?, 'Townsville', 'CA', 1, 5000)
          """.trimIndent(),
        ).use { stmt ->
          stmt.setObject(1, id)
          stmt.setInt(2, uniqueIpedsUnitId)
          stmt.setString(3, name)
          stmt.executeUpdate()
        }
      rebuildNameWords()
      rebuildSearchIndex()
    }
    return id
  }

  /**
   * Runs [body] as ONE transaction on [dbConnection], restoring autocommit
   * afterwards. The seed needs it because `CollegesDao.rebuildNameWords` is
   * documented as a `DELETE` + `INSERT … SELECT` inside the caller's single
   * transaction: under autocommit the DELETE commits on its own, and the
   * embedded server this test drives is live on the SAME database, so a
   * concurrent `GET /api/v1/colleges` could see an EMPTY `college_name_words`
   * and silently lose its one-keystroke arm. Committing the college row in the
   * same transaction also means the row and its words become visible together.
   */
  private fun <T> withTransaction(body: () -> T): T {
    dbConnection.autoCommit = false
    try {
      val value = body()
      dbConnection.commit()
      return value
    } catch (e: Throwable) {
      dbConnection.rollback()
      throw e
    } finally {
      dbConnection.autoCommit = true
    }
  }

  /**
   * Re-derives `college_name_words` after a direct seed (RFC 146). The table is
   * derived state the ingest rebuilds wholesale in its own phase; a test that
   * INSERTs into `colleges` behind the ingest's back must do the same, or its
   * college is invisible to the one-keystroke arm. Called inside
   * [withTransaction], which supplies the single transaction the DAO requires.
   */
  private fun rebuildNameWords() {
    val session =
      object : SqlSession {
        override fun prepareStatement(sql: String): java.sql.PreparedStatement = dbConnection.prepareStatement(sql)
      }
    CollegesDao.rebuildNameWords(session).getOrThrow()
  }

  /**
   * Re-derives `college_search_index` after a direct seed (RFC 150). Both
   * search entry points now MATCH and RANK on that table, so a college seeded
   * behind the ingest's back is invisible to `GET /api/v1/colleges` until the
   * index carries it — the same rule as [rebuildNameWords], and the same
   * single transaction.
   */
  private fun rebuildSearchIndex() {
    val session =
      object : SqlSession {
        override fun prepareStatement(sql: String): java.sql.PreparedStatement = dbConnection.prepareStatement(sql)
      }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
  }

  private fun encode(q: String): String = URLEncoder.encode(q, Charsets.UTF_8)

  @Test
  fun `search unauthenticated returns 401`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/colleges?q=Columbia"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `search without q returns 400 naming q`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/colleges")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("\"q\""))
    }

  @Test
  fun `search with a blank q returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/colleges?q=${encode("   ")}")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("validation_failed"))
    }

  @Test
  fun `search with an overlong q returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/colleges?q=${"x".repeat(CollegeSearchService.MAX_QUERY_LENGTH + 1)}")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      // The 400 names the observed length, not just the bound.
      assertTrue(body.contains("got ${CollegeSearchService.MAX_QUERY_LENGTH + 1}"))
    }

  @Test
  fun `search with a non-integer limit returns 400 naming limit`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/colleges?q=Columbia&limit=lots")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("limit"))
      // The 400 carries the offending raw value.
      assertTrue(body.contains("[lots]"))
    }

  @Test
  fun `search matches by substring and returns the summary shape, without needing a student profile`() =
    runBlocking {
      // No student profile is created: searching the catalog must not 409.
      val cookie = registerAndGetCookie()
      val marker = UUID.randomUUID().toString().take(8)
      val id = seedCollege("Substring $marker University")

      val response =
        client.get(buildUrl("/api/v1/colleges?q=${encode("g $marker uni")}")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val colleges = mapper.readTree(response.bodyAsText())["colleges"]
      assertEquals(1, colleges.size())
      assertEquals(id.toString(), colleges[0]["id"].asText())
      assertEquals("Substring $marker University", colleges[0]["name"].asText())
      assertEquals("Townsville", colleges[0]["city"].asText())
      assertEquals("CA", colleges[0]["state"].asText())
    }

  @Test
  fun `search finds a typo'd name via the one-keystroke rule (RFC 146)`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val marker = UUID.randomUUID().toString().take(8)
      val id = seedCollege("Amherst $marker College")

      // "Amhurst ... Colege": no substring arm can match this — every word is
      // one keystroke off, which is the RFC 146 rule. Route contract is
      // otherwise unchanged: same shape, same fields.
      val response =
        client.get(buildUrl("/api/v1/colleges?q=${encode("Amhurst $marker Colege")}")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val colleges = mapper.readTree(response.bodyAsText())["colleges"]
      assertTrue(colleges.size() >= 1)
      assertEquals(id.toString(), colleges[0]["id"].asText())
      assertEquals("Amherst $marker College", colleges[0]["name"].asText())
    }

  @Test
  fun `search respects the limit parameter`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val marker = UUID.randomUUID().toString().take(8)
      repeat(3) { seedCollege("Limited $marker College $it") }

      val response =
        client.get(buildUrl("/api/v1/colleges?q=${encode("Limited $marker")}&limit=2")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(2, mapper.readTree(response.bodyAsText())["colleges"].size())
    }

  @Test
  fun `POST colleges returns 405 with Allow`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.post(buildUrl("/api/v1/colleges")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertTrue(response.headers[HttpHeaders.Allow]?.contains("GET") == true)
    }
}
