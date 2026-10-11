package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/v1/students/me/sharing-permissions` and
 * `PUT /api/v1/students/me/sharing-permissions/{permission}` (RFC 248). Same
 * live-server harness as [IntendedSubjectsRoutingTest].
 */
class SharingPermissionsRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false, port = 0)
      boundPort = testServer.boundPort()
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

  private val collectionPath = "/api/v1/students/me/sharing-permissions"

  private val intendedSubjectsPath = "$collectionPath/intended_subjects"

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  /** A registered, email-verified caller with no student profile. */
  private suspend fun userCookie() = registerVerifiedUser(client, buildUrl(""), dbConnection)

  /** A registered, email-verified caller who also has a student profile. */
  private suspend fun studentCookie() = registerStudentSession(client, buildUrl(""), dbConnection)

  private suspend fun getPermissions(cookie: String) =
    client.get(buildUrl(collectionPath)) {
      header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun putRaw(
    cookie: String,
    path: String,
    body: String,
  ) = client.put(buildUrl(path)) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    header(HttpHeaders.Cookie, cookie)
    setBody(body)
  }

  private suspend fun putStatus(
    cookie: String,
    status: String,
  ) = putRaw(cookie, intendedSubjectsPath, """{"status":"$status"}""")

  private suspend fun json(response: HttpResponse): JsonNode = mapper.readTree(response.bodyAsText())

  /** The caller's stored status for `intended_subjects`, read back through GET. */
  private suspend fun storedStatus(cookie: String): String {
    val get = getPermissions(cookie)
    assertEquals(HttpStatusCode.OK, get.status, "got ${get.bodyAsText()}")
    val entries = json(get)["permissions"]
    assertEquals(listOf("intended_subjects"), entries.map { it["permission"].asText() })
    return entries.single()["status"].asText()
  }

  @Test
  fun `GET and PUT without a session return 401`() =
    runBlocking {
      assertEquals(HttpStatusCode.Unauthorized, client.get(buildUrl(collectionPath)).status)
      val put =
        client.put(buildUrl(intendedSubjectsPath)) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"status":"granted"}""")
        }
      assertEquals(HttpStatusCode.Unauthorized, put.status)
    }

  @Test
  fun `GET and PUT without a student profile return 409`() =
    runBlocking {
      val cookie = userCookie()
      val get = getPermissions(cookie)
      assertEquals(HttpStatusCode.Conflict, get.status)
      assertTrue(get.bodyAsText().contains("student_profile_required"))

      val put = putStatus(cookie, "granted")
      assertEquals(HttpStatusCode.Conflict, put.status)
      assertTrue(put.bodyAsText().contains("student_profile_required"))
    }

  @Test
  fun `GET with nothing stored is 200 with not_asked`() =
    runBlocking {
      val cookie = studentCookie()
      assertEquals("not_asked", storedStatus(cookie))
    }

  @Test
  fun `PUT grants, withdraws and grants again, and GET follows`() =
    runBlocking {
      val cookie = studentCookie()
      listOf("granted", "refused", "granted").forEach { status ->
        val put = putStatus(cookie, status)
        assertEquals(HttpStatusCode.OK, put.status, "got ${put.bodyAsText()}")
        val body = json(put)
        assertEquals("intended_subjects", body["permission"].asText())
        assertEquals(status, body["status"].asText())
        assertEquals(status, storedStatus(cookie))
      }
    }

  @Test
  fun `PUT of the current status again is a 200`() =
    runBlocking {
      val cookie = studentCookie()
      assertEquals(HttpStatusCode.OK, putStatus(cookie, "refused").status)
      val again = putStatus(cookie, "refused")
      assertEquals(HttpStatusCode.OK, again.status)
      assertEquals("refused", json(again)["status"].asText())
    }

  @Test
  fun `PUT to an unknown permission is 404`() =
    runBlocking {
      val cookie = studentCookie()
      val response = putRaw(cookie, "$collectionPath/college_list", """{"status":"granted"}""")
      assertEquals(HttpStatusCode.NotFound, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("not_found"))
      assertTrue(body.contains("[college_list]"), "got $body")
      assertTrue(body.contains("intended_subjects"), "got $body")
      assertEquals("not_asked", storedStatus(cookie))
    }

  @Test
  fun `PUT with a missing, unknown or not_asked status is 400 and writes nothing`() =
    runBlocking {
      val cookie = studentCookie()
      listOf("""{}""", """{"status":"withheld"}""", """{"status":"not_asked"}""").forEach { body ->
        val response = putRaw(cookie, intendedSubjectsPath, body)
        assertEquals(HttpStatusCode.BadRequest, response.status, "body $body got ${response.bodyAsText()}")
        assertTrue(response.bodyAsText().contains("validation_failed"))
        assertEquals("not_asked", storedStatus(cookie), "a refused body $body must write nothing")
      }

      // The same refusals after an answer exists leave that answer standing.
      assertEquals(HttpStatusCode.OK, putStatus(cookie, "granted").status)
      assertEquals(HttpStatusCode.BadRequest, putStatus(cookie, "not_asked").status)
      assertEquals("granted", storedStatus(cookie))
    }

  @Test
  fun `one student's PUT does not change another student's GET`() =
    runBlocking {
      val granting = studentCookie()
      val other = studentCookie()
      assertEquals(HttpStatusCode.OK, putStatus(granting, "granted").status)
      assertEquals("granted", storedStatus(granting))
      assertEquals("not_asked", storedStatus(other))
    }
}
