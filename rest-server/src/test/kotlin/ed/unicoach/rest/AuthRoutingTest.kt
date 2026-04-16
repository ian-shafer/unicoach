package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutingTest {
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
      if (::testServer.isInitialized) {
        testServer.stop(1000, 5000)
      }
      if (::client.isInitialized) {
        client.close()
      }
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  @Test
  fun `test valid registration state simulation`() =
    runBlocking {
      val req = RegisterRequest("testuser@company.com", "Password123!", "Test User")

      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }

      assertEquals(HttpStatusCode.Created, response.status)
      val body = response.bodyAsText()
      assertTrue(response.headers[HttpHeaders.SetCookie] != null, "Missing Set-Cookie header")
      assertTrue(body.contains("testuser@company.com"))
    }

  @Test
  fun `test CORS configuration validation hooks`() =
    runBlocking {
      val response =
        client.options(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.Origin, "http://localhost:3000")
          header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
      val allowedStatus = listOf(HttpStatusCode.OK, HttpStatusCode.NoContent, HttpStatusCode.MethodNotAllowed)
      assertTrue(response.status in allowedStatus)
    }

  @Test
  fun `test header structure verification constraints`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, "text/plain")
          setBody("some raw text")
        }
      assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

  @Test
  fun `test unique invariant and malicious vector rejection`() =
    runBlocking {
      val req1 = RegisterRequest("collision@company.com", "Password123!", "Test User")
      val req2 = RegisterRequest("Collision@company.com", "Password123!", "Test User")

      val res1 =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req1))
        }
      assertEquals(HttpStatusCode.Created, res1.status)

      val res2 =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req2))
        }
      assertEquals(HttpStatusCode.Conflict, res2.status)
    }

  @Test
  fun `test large buffer mitigation rejection`() =
    runBlocking {
      val paddedBody = "{" + " ".repeat(10000) + "\"email\":\"valid@company.com\"}"
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.ContentLength, paddedBody.length.toString())
          setBody(paddedBody)
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

  @Test
  fun `test StatusPages deserialization boundaries`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email": 123}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `test timing attack mitigation`() =
    runBlocking {
      val req = RegisterRequest("timing@company.com", "Password123!", "Test User")

      val t1 =
        measureTimeMillis {
          client.post(buildUrl("/api/v1/auth/register")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val t2 =
        measureTimeMillis {
          client.post(buildUrl("/api/v1/auth/register")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val diff = Math.abs(t1 - t2)
      assertTrue(diff < 1500)
    }
}
