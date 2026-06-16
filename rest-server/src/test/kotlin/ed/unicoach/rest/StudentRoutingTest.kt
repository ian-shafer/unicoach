package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateStudentRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudentRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "student${java.util.UUID.randomUUID()}@company.com"

  /** Registers a fresh user and returns the `name=value` session cookie pair. */
  private suspend fun registerAndGetCookie(): String {
    val req = RegisterRequest(uniqueEmail(), "Password123!", "Student User")
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  // --- POST /students ---

  @Test
  fun `POST students unauthenticated returns 401`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/students")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `POST students returns 201 for each precision with the date echoed`() =
    runBlocking {
      for (iso in listOf("2028", "2028-06", "2028-06-15")) {
        val cookie = registerAndGetCookie()
        val response =
          client.post(buildUrl("/api/v1/students")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Cookie, cookie)
            setBody(mapper.writeValueAsString(CreateStudentRequest(iso)))
          }
        assertEquals(HttpStatusCode.Created, response.status, "Expected 201 for $iso")
        assertTrue(response.bodyAsText().contains(iso), "Response should echo $iso")
      }
    }

  @Test
  fun `POST students malformed date returns 400 with field error`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.post(buildUrl("/api/v1/students")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateStudentRequest("2028-13-40")))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("VALIDATION_ERROR"))
      assertTrue(body.contains("expectedHighSchoolGraduationDate"))
    }

  @Test
  fun `POST students when one already exists returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val first =
        client.post(buildUrl("/api/v1/students")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
        }
      assertEquals(HttpStatusCode.Created, first.status)

      val second =
        client.post(buildUrl("/api/v1/students")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateStudentRequest("2029")))
        }
      assertEquals(HttpStatusCode.Conflict, second.status)
      assertTrue(second.bodyAsText().contains("STUDENT_ALREADY_EXISTS"))
    }

  // --- GET /students/me ---

  @Test
  fun `GET students me returns 200 with profile`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028-06")))
      }

      val response =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("2028-06"))
    }

  @Test
  fun `GET students me without profile returns 404`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("STUDENT_NOT_FOUND"))
    }

  @Test
  fun `GET students me unauthenticated returns 401`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/students/me"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  // --- PATCH /students/me ---

  @Test
  fun `PATCH students me with correct version returns 200`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }

      val response =
        client.patch(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateStudentRequest("2029-09", 1)))
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("2029-09"))
    }

  @Test
  fun `PATCH students me with stale version returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }
      // Bump to version 2.
      client.patch(buildUrl("/api/v1/students/me")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(UpdateStudentRequest("2029", 1)))
      }

      val stale =
        client.patch(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateStudentRequest("2030", 1)))
        }
      assertEquals(HttpStatusCode.Conflict, stale.status)
      assertTrue(stale.bodyAsText().contains("VERSION_CONFLICT"))
    }

  @Test
  fun `PATCH students me malformed date returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }

      val response =
        client.patch(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateStudentRequest("not-a-date", 1)))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

  @Test
  fun `update student with non-int version returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }

      // A JSON string supplied for the numeric `version` field must be rejected
      // (ALLOW_COERCION_OF_SCALARS disabled), not coerced to 1.
      val response =
        client.patch(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"expectedHighSchoolGraduationDate":"2028","version":"1"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString()) == true,
        "Coercion rejection must respond application/json",
      )
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `PATCH students me unauthenticated returns 401`() =
    runBlocking {
      val response =
        client.patch(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(UpdateStudentRequest("2029", 1)))
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  // --- DELETE /students/me ---

  @Test
  fun `DELETE students me returns 204 clears cookie and invalidates account`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }

      val deleteResponse =
        client.delete(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NoContent, deleteResponse.status)
      val cleared = deleteResponse.headers[HttpHeaders.SetCookie]
      assertTrue(cleared != null)
      assertTrue(cleared.contains("Max-Age=0") || cleared.contains("max-age=0"))

      // Account is gone: /api/v1/auth/me returns 401 for the old cookie.
      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

  @Test
  fun `DELETE students me without profile returns 404`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val response =
        client.delete(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("STUDENT_NOT_FOUND"))
    }

  @Test
  fun `DELETE students me unauthenticated returns 401`() =
    runBlocking {
      val response = client.delete(buildUrl("/api/v1/students/me"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
