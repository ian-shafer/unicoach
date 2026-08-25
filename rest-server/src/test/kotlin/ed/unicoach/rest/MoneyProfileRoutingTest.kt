package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateMoneyProfileRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyProfileRoutingTest {
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

  private fun uniqueEmail(): String = "mp${UUID.randomUUID()}@company.com"

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
    val req = RegisterRequest(email, "Password123!", "Money Profile User")
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

  private suspend fun registerStudent(cookie: String) {
    client.post(buildUrl("/api/v1/students")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
    }
  }

  private suspend fun putProfile(
    cookie: String,
    body: UpdateMoneyProfileRequest,
  ) = client.put(buildUrl("/api/v1/students/me/money-profile")) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    header(HttpHeaders.Cookie, cookie)
    setBody(mapper.writeValueAsString(body))
  }

  private suspend fun getProfile(cookie: String) =
    client.get(buildUrl("/api/v1/students/me/money-profile")) {
      header(HttpHeaders.Cookie, cookie)
    }

  @Test
  fun `GET money-profile without a session returns 401`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/students/me/money-profile"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `GET money-profile without a student profile returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response = getProfile(cookie)
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(response.bodyAsText().contains("student_profile_required"))
    }

  @Test
  fun `GET money-profile before the first write returns 404`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response = getProfile(cookie)
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `PUT money-profile creates the profile and GET returns it`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)

      val put = putProfile(cookie, UpdateMoneyProfileRequest(incomeBand = "48k_to_75k", residencyState = "ca"))
      assertEquals(HttpStatusCode.OK, put.status)
      val putBody = mapper.readTree(put.bodyAsText())
      assertEquals("answered", putBody["profile"]["incomeBandStatus"].asText())
      assertEquals("48k_to_75k", putBody["profile"]["incomeBand"].asText())
      assertEquals("answered", putBody["profile"]["residencyStatus"].asText())
      assertEquals("CA", putBody["profile"]["residencyState"].asText(), "state must be normalized to uppercase")

      val get = getProfile(cookie)
      assertEquals(HttpStatusCode.OK, get.status)
      val getBody = mapper.readTree(get.bodyAsText())
      assertEquals("48k_to_75k", getBody["profile"]["incomeBand"].asText())
    }

  @Test
  fun `PUT money-profile with a subset leaves the other field untouched`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      putProfile(cookie, UpdateMoneyProfileRequest(incomeBand = "under_30k"))

      val second = putProfile(cookie, UpdateMoneyProfileRequest(residencyState = "NY"))
      assertEquals(HttpStatusCode.OK, second.status)
      val body = mapper.readTree(second.bodyAsText())
      assertEquals("under_30k", body["profile"]["incomeBand"].asText(), "partial update must not clear the income band")
      assertEquals("NY", body["profile"]["residencyState"].asText())
    }

  @Test
  fun `PUT money-profile decline clears the value and marks the field declined`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      putProfile(cookie, UpdateMoneyProfileRequest(incomeBand = "over_110k"))

      val declined = putProfile(cookie, UpdateMoneyProfileRequest(incomeBandDeclined = true))
      assertEquals(HttpStatusCode.OK, declined.status)
      val body = mapper.readTree(declined.bodyAsText())
      assertEquals("declined", body["profile"]["incomeBandStatus"].asText())
      assertTrue(body["profile"]["incomeBand"].isNull, "a declined field must carry no value")
    }

  @Test
  fun `PUT money-profile clear returns a field to unanswered`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      putProfile(cookie, UpdateMoneyProfileRequest(residencyState = "TX"))

      val cleared = putProfile(cookie, UpdateMoneyProfileRequest(residencyClear = true))
      assertEquals(HttpStatusCode.OK, cleared.status)
      val body = mapper.readTree(cleared.bodyAsText())
      assertEquals("unanswered", body["profile"]["residencyStatus"].asText())
      assertTrue(body["profile"]["residencyState"].isNull)
    }

  @Test
  fun `PUT money-profile with value and declined for the same field returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response = putProfile(cookie, UpdateMoneyProfileRequest(incomeBand = "under_30k", incomeBandDeclined = true))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("incomeBand"))
    }

  @Test
  fun `PUT money-profile with an unknown income band returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response = putProfile(cookie, UpdateMoneyProfileRequest(incomeBand = "billionaire"))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("incomeBand"))
    }

  @Test
  fun `PUT money-profile with a malformed residency state returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response = putProfile(cookie, UpdateMoneyProfileRequest(residencyState = "California"))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("residencyState"))
    }

  @Test
  fun `each student sees only their own money profile`() =
    runBlocking {
      val cookieA = registerAndGetCookie()
      registerStudent(cookieA)
      val cookieB = registerAndGetCookie()
      registerStudent(cookieB)

      putProfile(cookieA, UpdateMoneyProfileRequest(incomeBand = "under_30k"))

      // B has never written: B must see 404, not A's profile.
      assertEquals(HttpStatusCode.NotFound, getProfile(cookieB).status)

      putProfile(cookieB, UpdateMoneyProfileRequest(incomeBand = "over_110k"))
      val bodyA = mapper.readTree(getProfile(cookieA).bodyAsText())
      val bodyB = mapper.readTree(getProfile(cookieB).bodyAsText())
      assertEquals("under_30k", bodyA["profile"]["incomeBand"].asText())
      assertEquals("over_110k", bodyB["profile"]["incomeBand"].asText())
    }

  @Test
  fun `DELETE money-profile returns 405 with an Allow header`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response = client.delete(buildUrl("/api/v1/students/me/money-profile")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("GET, PUT", response.headers[HttpHeaders.Allow])
    }
}
