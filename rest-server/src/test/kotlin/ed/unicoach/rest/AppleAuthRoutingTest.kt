package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.models.RegisterRequest
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
 * Drives `POST /api/v1/auth/apple` against a fully booted server using the stub
 * verifier (selected via `APPLE_AUTH_PROVIDER=stub` in the test dotenv layer).
 * Mirrors [GoogleAuthRoutingTest]. Stub tokens follow the documented fake-token
 * format decoded by `StubIdTokenVerifier` — the format carries no provider
 * distinction, so the same helper builds tokens for either route.
 */
class AppleAuthRoutingTest {
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

  /** Apple's identity token never carries a name claim — the stub token omits it. */
  private fun stubToken(
    sub: String,
    email: String,
    verified: Boolean = true,
  ) = "stub:sub=$sub;email=$email;email_verified=$verified"

  private suspend fun postApple(
    idToken: String,
    name: String? = null,
  ) = client.post(buildUrl("/api/v1/auth/apple")) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    val body = mutableMapOf<String, String?>("idToken" to idToken)
    if (name != null) body["name"] = name
    setBody(mapper.writeValueAsString(body))
  }

  @Test
  fun `valid token for a new user returns 200 with a session cookie and the supplied name`() =
    runBlocking {
      val response =
        postApple(stubToken("rt-apple-new-${UUID.randomUUID()}", "rt-apple-new-${UUID.randomUUID()}@example.com"), name = "Ada Client")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.headers[HttpHeaders.SetCookie] != null, "Missing Set-Cookie header")
      assertTrue(response.bodyAsText().contains("Ada Client"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `a first Apple sign-in reports emailVerified true`() =
    runBlocking {
      val response =
        postApple(stubToken("rt-apple-verified-${UUID.randomUUID()}", "rt-apple-verified-${UUID.randomUUID()}@example.com"))
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        mapper.readTree(response.bodyAsText())["user"]["emailVerified"].asBoolean(),
        "body was: [${response.bodyAsText()}]",
      )
    }

  @Test
  fun `valid token with no name returns 200 and derives the name from the email local-part`() =
    runBlocking {
      val email = "rt-apple-noname-${UUID.randomUUID()}@example.com"
      val response = postApple(stubToken("rt-apple-noname-${UUID.randomUUID()}", email))
      assertEquals(HttpStatusCode.OK, response.status)
      // Assert the name field itself: the body always echoes the email, so a
      // substring match would pass for any derived name, right or wrong.
      assertEquals(email.substringBefore('@'), mapper.readTree(response.bodyAsText())["user"]["name"].asText())
    }

  @Test
  fun `a returning Apple login does not overwrite the stored name with a resent one`() =
    runBlocking {
      // The client caches Apple's first-authorization name and resends it on
      // every later sign-in (ios-app/UnicoachiOS/AppleNameStore.swift), which is
      // only safe while the server ignores `name` for an already-provisioned
      // account. This pins that promise, so the resend cannot silently start
      // clobbering a name the user has since changed.
      val sub = "rt-apple-rename-${UUID.randomUUID()}"
      val email = "rt-apple-rename-${UUID.randomUUID()}@example.com"
      val first = postApple(stubToken(sub, email), name = "Ada Lovelace")
      assertEquals(HttpStatusCode.OK, first.status)

      val returning = postApple(stubToken(sub, email), name = "Someone Else")
      assertEquals(HttpStatusCode.OK, returning.status)
      assertEquals("Ada Lovelace", mapper.readTree(returning.bodyAsText())["user"]["name"].asText())
    }

  @Test
  fun `valid token for a returning user returns 200`() =
    runBlocking {
      val sub = "rt-apple-return-${UUID.randomUUID()}"
      val email = "rt-apple-return-${UUID.randomUUID()}@example.com"
      val first = postApple(stubToken(sub, email))
      assertEquals(HttpStatusCode.OK, first.status)

      val second = postApple(stubToken(sub, email))
      assertEquals(HttpStatusCode.OK, second.status)
      assertTrue(second.headers[HttpHeaders.SetCookie] != null)
    }

  @Test
  fun `unverified provider email returns 403 email_not_verified`() =
    runBlocking {
      val response =
        postApple(
          stubToken("rt-apple-unverified-${UUID.randomUUID()}", "rt-apple-unverified-${UUID.randomUUID()}@example.com", verified = false),
        )
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("email_not_verified"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `registered-but-unverified local account with matching email returns 403 account_email_not_verified`() =
    runBlocking {
      val email = "rt-apple-unverified-local-${UUID.randomUUID()}@example.com"
      val registerResponse =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Unverified Local")))
        }
      assertEquals(HttpStatusCode.Created, registerResponse.status)

      // register leaves email_verified_at null; the SSO linking gate (RFC 111)
      // must refuse to attach the Apple identity to this unverified account.
      val response = postApple(stubToken("rt-apple-link-blocked-${UUID.randomUUID()}", email))
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("account_email_not_verified"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `invalid token returns 401 unauthorized`() =
    runBlocking {
      val response = postApple("stub:invalid")
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `transient verifier failure returns 503 service_unavailable`() =
    runBlocking {
      val response = postApple("stub:unavailable")
      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertTrue(response.bodyAsText().contains("service_unavailable"), "body was: ${response.bodyAsText()}")
    }

  @Test
  fun `non-POST methods are rejected with 405`() =
    runBlocking {
      val getResponse = client.get(buildUrl("/api/v1/auth/apple"))
      assertEquals(HttpStatusCode.MethodNotAllowed, getResponse.status)

      val deleteResponse = client.delete(buildUrl("/api/v1/auth/apple"))
      assertEquals(HttpStatusCode.MethodNotAllowed, deleteResponse.status)
    }

  @Test
  fun `the issued cookie authenticates a subsequent auth me`() =
    runBlocking {
      val response = postApple(stubToken("rt-apple-me-${UUID.randomUUID()}", "rt-apple-me-${UUID.randomUUID()}@example.com"))
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
