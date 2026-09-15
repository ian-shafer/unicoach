package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.coaching.SubjectVocabularyService
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/subjects` (RFC 205 D29/D41): the picker's list — auth, the two
 * validated query parameters, the clamp, and the deprecation exclusion. Same
 * live-server harness as [CollegeSearchRoutingTest].
 *
 * NO COUNT OF EITHER LIST IS ASSERTED HERE. Every test seeds its OWN taxonomy
 * rows under a per-test marker and asserts about those rows only, so the size of
 * `db/data/subjects.json` and the size of the federal programme list are both
 * irrelevant to whether this suite passes.
 */
class SubjectsRoutingTest {
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

  private fun uniqueEmail(): String = "sub${UUID.randomUUID()}@company.com"

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
    val req = RegisterRequest(email, "Password123!", "Subjects User")
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

  /** A marker no other row carries, so a `q` built from it matches this test's rows and nothing else. */
  private fun marker(): String = UUID.randomUUID().toString().take(8)

  /**
   * One authored taxonomy row on the shared un-truncated test database, the
   * [CollegeSearchRoutingTest] seeding convention.
   *
   * `cip_prefixes` carries a real two-digit CIP series because `subjects` guards
   * the column with `subjects_cip_prefixes_nonempty_check` and
   * `subjects_cip_prefixes_format_check` (0064). Nothing here reads it — the
   * route serves `slug` and `name` only, and no federal code reaches a student.
   */
  private fun seedSubject(
    slug: String,
    name: String,
    status: String = "active",
  ) {
    dbConnection
      .prepareStatement(
        "INSERT INTO subjects (slug, name, cip_prefixes, status) VALUES (?, ?, '{51}'::TEXT[], ?)",
      ).use { stmt ->
        stmt.setString(1, slug)
        stmt.setString(2, name)
        stmt.setString(3, status)
        stmt.executeUpdate()
      }
  }

  /** Three ACTIVE subjects whose names sort A, B, C, so name order is observable without a count. */
  private fun seedThreeSubjects(marker: String): List<String> {
    seedSubject("fixture-$marker-cartography", "Fixture $marker Cartography")
    seedSubject("fixture-$marker-aardvark", "Fixture $marker Aardvark Studies")
    seedSubject("fixture-$marker-beekeeping", "Fixture $marker Beekeeping")
    return listOf(
      "Fixture $marker Aardvark Studies",
      "Fixture $marker Beekeeping",
      "Fixture $marker Cartography",
    )
  }

  private fun encode(q: String): String = URLEncoder.encode(q, Charsets.UTF_8)

  private fun namesOf(body: String): List<String> =
    mapper
      .readTree(body)["subjects"]
      .map { it["name"].asText() }

  @Test
  fun `subjects without a session returns 401`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/subjects"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `subjects without q serves the list in name order`() =
    runBlocking {
      // No student profile is created: reading a closed published list must not 409.
      val cookie = registerAndGetCookie()
      val marker = marker()
      val ordered = seedThreeSubjects(marker)

      val response = client.get(buildUrl("/api/v1/subjects")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, response.status)
      val served = namesOf(response.bodyAsText()).filter { it.contains(marker) }
      assertEquals(ordered, served, "an absent q serves the whole list, and it arrives in name order")
    }

  @Test
  fun `subjects with a blank q returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/subjects?q=${encode("   ")}")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      assertTrue(body.contains("\"q\""), "the 400 must name q, got $body")
    }

  @Test
  fun `subjects with an overlong q returns 400 naming the observed length`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val overlong = "x".repeat(SubjectVocabularyService.MAX_QUERY_LENGTH + 1)
      val response =
        client.get(buildUrl("/api/v1/subjects?q=$overlong")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      // The 400 names the observed length, not just the bound.
      assertTrue(body.contains("got ${SubjectVocabularyService.MAX_QUERY_LENGTH + 1}"), "got $body")
    }

  @Test
  fun `subjects with a non-integer limit returns 400 naming limit`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/subjects?limit=lots")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      assertTrue(body.contains("limit"), "got $body")
      // The 400 carries the offending raw value.
      assertTrue(body.contains("[lots]"), "got $body")
    }

  @Test
  fun `subjects clamps a zero limit and a huge limit instead of rejecting them`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val marker = marker()
      val ordered = seedThreeSubjects(marker)

      val zero =
        client.get(buildUrl("/api/v1/subjects?q=$marker&limit=0")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, zero.status, "limit=0 is clamped to the floor, not refused")
      assertEquals(
        listOf(ordered.first()),
        namesOf(zero.bodyAsText()),
        "limit=0 clamps to ${SubjectVocabularyService.MIN_LIMIT}, so the first row in name order is served",
      )

      val huge =
        client.get(buildUrl("/api/v1/subjects?q=$marker&limit=9999")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, huge.status, "limit=9999 is clamped to the ceiling, not refused")
      assertEquals(ordered, namesOf(huge.bodyAsText()), "the clamp serves the matching rows rather than an error")
    }

  @Test
  fun `subjects omits a deprecated subject`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val marker = marker()
      seedSubject("fixture-$marker-active", "Fixture $marker Offered")
      seedSubject("fixture-$marker-dropped", "Fixture $marker Dormant", status = "deprecated")

      val response =
        client.get(buildUrl("/api/v1/subjects?q=$marker")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val served = namesOf(response.bodyAsText())
      assertTrue(served.contains("Fixture $marker Offered"), "an active subject is offered, got $served")
      assertFalse(
        served.contains("Fixture $marker Dormant"),
        "a deprecated subject is offered to nobody (D40), got $served",
      )
    }
}
