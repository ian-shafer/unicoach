package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.models.CreateCollegeListEntryRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateCollegeListEntryRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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

class CollegeListRoutingTest {
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

  private fun uniqueEmail(): String = "cle${UUID.randomUUID()}@company.com"

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
    val req = RegisterRequest(email, "Password123!", "College List User")
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

  private fun seedCollege(): UUID {
    val id = UUID.randomUUID()
    // Each test class instance is fresh per @Test (JUnit default), so an
    // instance counter would restart at the same value across tests sharing
    // the un-truncated dev DB; a masked random int keeps ipeds_unit_id unique
    // across the whole suite without a shared counter.
    val uniqueIpedsUnitId = (id.leastSignificantBits and 0x3FFFFFFF).toInt()
    dbConnection
      .prepareStatement(
        """
        INSERT INTO colleges (id, ipeds_unit_id, name, city, state, control)
        VALUES (?, ?, 'Test College', 'Townsville', 'CA', 1)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.setInt(2, uniqueIpedsUnitId)
        stmt.executeUpdate()
      }
    return id
  }

  // --- POST /students/me/college-list ---

  @Test
  fun `POST college-list without a student profile returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college)))
        }
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(response.bodyAsText().contains("student_profile_required"))
    }

  @Test
  fun `POST college-list with an invalid status string returns 400 with a FieldError naming status`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(
            """{"collegeId":"$college","status":"bogus"}""",
          )
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("status"))
    }

  @Test
  fun `POST college-list with oversized reasons returns 400 with a FieldError naming reasons`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "x".repeat(2049))))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("reasons"))
    }

  @Test
  fun `POST college-list with empty reasons returns 400 with a FieldError naming reasons`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "")))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("reasons"))
    }

  @Test
  fun `POST college-list with an unknown collegeId returns 404`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(UUID.randomUUID())))
        }
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("not_found"))
    }

  @Test
  fun `POST college-list unauthenticated returns 401`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(UUID.randomUUID())))
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  // --- Full CRUD happy path ---

  @Test
  fun `full CRUD happy path through HTTP`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()

      val createResponse =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "Good fit")))
        }
      assertEquals(HttpStatusCode.Created, createResponse.status)
      val created = mapper.readTree(createResponse.bodyAsText())
      val entryId = created["entry"]["id"].asText()
      assertEquals(1, created["entry"]["version"].asInt())
      // RFC 137: every success body names the college, not just its id.
      assertEquals("Test College", created["entry"]["collegeName"].asText())

      val listResponse =
        client.get(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, listResponse.status)
      val listBody = listResponse.bodyAsText()
      assertTrue(listBody.contains(entryId))
      assertTrue(listBody.contains("Test College"), "collection GET must carry collegeName")

      val getResponse =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, getResponse.status)
      assertEquals("Test College", mapper.readTree(getResponse.bodyAsText())["entry"]["collegeName"].asText())

      val patchResponse =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", "Applied now")))
        }
      assertEquals(HttpStatusCode.OK, patchResponse.status)
      val patched = mapper.readTree(patchResponse.bodyAsText())
      assertEquals("applying", patched["entry"]["status"].asText())
      assertEquals(2, patched["entry"]["version"].asInt())
      assertEquals("Test College", patched["entry"]["collegeName"].asText())

      val deleteResponse =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId?version=2")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

      val getAfterDelete =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, getAfterDelete.status)
    }

  // --- GET/PATCH/DELETE on another student's entry ---

  private suspend fun createEntryFor(
    cookie: String,
    college: UUID,
  ): String {
    val response =
      client.post(buildUrl("/api/v1/students/me/college-list")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college)))
      }
    return mapper.readTree(response.bodyAsText())["entry"]["id"].asText()
  }

  @Test
  fun `GET PATCH DELETE on another students entry id returns 404`() =
    runBlocking {
      val ownerCookie = registerAndGetCookie()
      registerStudent(ownerCookie)
      val college = seedCollege()
      val entryId = createEntryFor(ownerCookie, college)

      val otherCookie = registerAndGetCookie()
      registerStudent(otherCookie)

      val getResponse =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, otherCookie)
        }
      assertEquals(HttpStatusCode.NotFound, getResponse.status)

      val patchResponse =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, otherCookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null)))
        }
      assertEquals(HttpStatusCode.NotFound, patchResponse.status)

      val deleteResponse =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId?version=1")) {
          header(HttpHeaders.Cookie, otherCookie)
        }
      assertEquals(HttpStatusCode.NotFound, deleteResponse.status)
    }

  // --- PATCH stale version ---

  @Test
  fun `PATCH with a stale version returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val entryId = createEntryFor(cookie, college)

      client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null)))
      }

      val stale =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "admitted", null)))
        }
      assertEquals(HttpStatusCode.Conflict, stale.status)
      assertTrue(stale.bodyAsText().contains("version_conflict"))
    }

  // --- DELETE missing version ---

  @Test
  fun `DELETE missing the version query parameter returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val entryId = createEntryFor(cookie, college)

      val response =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("validation_failed"))
    }

  // --- Unauthenticated on every route ---

  @Test
  fun `unauthenticated request to every route returns 401`() =
    runBlocking {
      val randomId = UUID.randomUUID()

      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get(buildUrl("/api/v1/students/me/college-list")).status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get(buildUrl("/api/v1/students/me/college-list/$randomId")).status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client
          .patch(buildUrl("/api/v1/students/me/college-list/$randomId")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null)))
          }.status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.delete(buildUrl("/api/v1/students/me/college-list/$randomId?version=1")).status,
      )
    }

  // --- 405 with Allow header, per-route ---

  @Test
  fun `PUT college-list returns 405 with Allow`() =
    runBlocking {
      val response =
        client.request(buildUrl("/api/v1/students/me/college-list")) {
          method = HttpMethod.Put
        }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertTrue(response.headers[HttpHeaders.Allow]?.contains("POST") == true)
      assertTrue(response.headers[HttpHeaders.Allow]?.contains("GET") == true)
    }

  @Test
  fun `POST college-list entryId returns 405 with Allow`() =
    runBlocking {
      val randomId = UUID.randomUUID()
      val response = client.post(buildUrl("/api/v1/students/me/college-list/$randomId"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      val allow = response.headers[HttpHeaders.Allow].orEmpty()
      assertTrue(allow.contains("GET"))
      assertTrue(allow.contains("PATCH"))
      assertTrue(allow.contains("DELETE"))
    }
}
