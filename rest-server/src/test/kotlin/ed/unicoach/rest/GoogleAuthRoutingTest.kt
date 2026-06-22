package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives `POST /api/v1/auth/google` against a fully booted server using the stub
 * verifier (selected via `GOOGLE_AUTH_PROVIDER=stub` in `.env.test`). Stub tokens
 * follow the documented fake-token format decoded by `StubGoogleTokenVerifier`.
 */
class GoogleAuthRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

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

  private fun stubToken(
    sub: String,
    email: String,
    verified: Boolean = true,
  ) = "stub:sub=$sub;email=$email;email_verified=$verified;name=Google User"

  private suspend fun postGoogle(idToken: String) =
    client.post(buildUrl("/api/v1/auth/google")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody(mapper.writeValueAsString(mapOf("idToken" to idToken)))
    }

  @Test
  fun `valid token for a new user returns 200 with a session cookie`() =
    runBlocking {
      val response = postGoogle(stubToken("rt-new-${UUID.randomUUID()}", "rt-new-${UUID.randomUUID()}@example.com"))
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.headers[HttpHeaders.SetCookie] != null, "Missing Set-Cookie header")
    }

  @Test
  fun `valid token for a returning user returns 200`() =
    runBlocking {
      val sub = "rt-return-${UUID.randomUUID()}"
      val email = "rt-return-${UUID.randomUUID()}@example.com"
      val first = postGoogle(stubToken(sub, email))
      assertEquals(HttpStatusCode.OK, first.status)

      val second = postGoogle(stubToken(sub, email))
      assertEquals(HttpStatusCode.OK, second.status)
      assertTrue(second.headers[HttpHeaders.SetCookie] != null)
    }

  @Test
  fun `unverified email returns 403 email_not_verified`() =
    runBlocking {
      val response =
        postGoogle(stubToken("rt-unverified-${UUID.randomUUID()}", "rt-unverified-${UUID.randomUUID()}@example.com", verified = false))
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("email_not_verified"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `invalid token returns 401 unauthorized`() =
    runBlocking {
      val response = postGoogle("stub:invalid")
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `transient verifier failure returns 503 service_unavailable`() =
    runBlocking {
      val response = postGoogle("stub:unavailable")
      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertTrue(response.bodyAsText().contains("service_unavailable"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `non-POST methods are rejected with 405`() =
    runBlocking {
      val getResponse = client.get(buildUrl("/api/v1/auth/google"))
      assertEquals(HttpStatusCode.MethodNotAllowed, getResponse.status)

      val deleteResponse = client.delete(buildUrl("/api/v1/auth/google"))
      assertEquals(HttpStatusCode.MethodNotAllowed, deleteResponse.status)
    }

  @Test
  fun `the issued cookie authenticates a subsequent auth me`() =
    runBlocking {
      val response = postGoogle(stubToken("rt-me-${UUID.randomUUID()}", "rt-me-${UUID.randomUUID()}@example.com"))
      assertEquals(HttpStatusCode.OK, response.status)

      val setCookie = response.headers[HttpHeaders.SetCookie]!!
      val cookiePair = setCookie.substringBefore(";")

      val me =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.OK, me.status)
    }
}
