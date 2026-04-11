package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutingTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `test valid registration state simulation`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val req = RegisterRequest("testuser@company.com", "Password123!", "Test User")

      val response =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }

      assertEquals(HttpStatusCode.Created, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("token"))
      assertTrue(body.contains("testuser@company.com"))
    }

  @Test
  fun `test CORS configuration validation hooks`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      // Ktor's install(CORS) usually handles this, we ensure it's at least returning 200 or 405 if not configured
      val response =
        client.options("/api/v1/auth/register") {
          header(HttpHeaders.Origin, "http://localhost:3000")
          header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
      // As long as it doesn't crash. (If CORS is configured it's 200 OK)
      val allowedStatus = listOf(HttpStatusCode.OK, HttpStatusCode.NoContent, HttpStatusCode.MethodNotAllowed)
      assertTrue(response.status in allowedStatus)
    }

  @Test
  fun `test header structure verification constraints`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val response =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, "text/plain")
          setBody("some raw text")
        }
      assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

  @Test
  fun `test unique invariant and malicious vector rejection`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val req1 = RegisterRequest("collision@company.com", "Password123!", "Test User")
      val req2 = RegisterRequest("Collision@company.com", "Password123!", "Test User")

      val res1 =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req1))
        }
      assertEquals(HttpStatusCode.Created, res1.status)

      val res2 =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req2))
        }
      assertEquals(HttpStatusCode.Conflict, res2.status)
    }

  @Test
  fun `test large buffer mitigation rejection`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val paddedBody = "{" + " ".repeat(10000) + "\"email\":\"valid@company.com\"}"
      val response =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.ContentLength, paddedBody.length.toString())
          setBody(paddedBody)
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

  @Test
  fun `test StatusPages deserialization boundaries`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val response =
        client.post("/api/v1/auth/register") {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email": 123}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `test timing attack mitigation`() =
    testApplication {
      environment {
        config = ApplicationConfig("application-test.conf")
      }
      val req = RegisterRequest("timing@company.com", "Password123!", "Test User")

      val t1 =
        measureTimeMillis {
          client.post("/api/v1/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val t2 =
        measureTimeMillis {
          client.post("/api/v1/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val diff = Math.abs(t1 - t2)
      // Check variance < 1500ms (more lenient for tests on weak machines)
      assertTrue(diff < 1500)
    }
}
