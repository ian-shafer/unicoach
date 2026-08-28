package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
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

  /** A uniquely-named college this test alone matches, on the shared un-truncated dev DB. */
  private fun seedCollege(name: String): UUID {
    val id = UUID.randomUUID()
    val uniqueUnitId = (id.leastSignificantBits and 0x3FFFFFFF).toInt()
    dbConnection
      .prepareStatement(
        """
        INSERT INTO colleges (id, unit_id, name, city, state, control, undergrad_enrollment)
        VALUES (?, ?, ?, 'Townsville', 'CA', 1, 5000)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.setInt(2, uniqueUnitId)
        stmt.setString(3, name)
        stmt.executeUpdate()
      }
    return id
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
  fun `search finds a typo'd name via the fuzzy arms (RFC 139)`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val marker = UUID.randomUUID().toString().take(8)
      val id = seedCollege("Amherst $marker College")

      // "Amhurst ... Colege": no substring arm can match this — only the
      // trigram similarity arm added by RFC 139. Route contract is otherwise
      // unchanged: same shape, same fields.
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
