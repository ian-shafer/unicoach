package ed.unicoach.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web auth gate (RFC 224).
 *
 * The gate states the same rule `rest-server`'s `EmailVerificationGate` states,
 * in the medium a browser understands: where the API answers `403
 * email_not_verified`, the web shows the screen that names the address the mail
 * went to and offers the resend button. So the assertions here are about WHICH
 * SCREEN a family lands on, not about a status code alone — landing on the
 * right status at the wrong page is the failure that would make the slice
 * useless to the person using it.
 */
class WebAuthGateTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `the public pages stay public`() =
    testApplication {
      application { testPublicWebModule() }

      for (path in listOf("/", "/terms", "/privacy", "/healthz", "/site.css", "/sign-in", "/register")) {
        assertEquals(HttpStatusCode.OK, client.get(path).status, "[$path] is no longer reachable signed out")
      }
    }

  /**
   * An unmatched path must still 404. The gate's default is OPEN precisely so
   * that a crawler asking for `/robots.txt` is told there is nothing there,
   * rather than being redirected to a login form it cannot use.
   */
  @Test
  fun `an unmatched path still answers the branded 404 rather than a redirect`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/no-such-page")

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("404 Not Found"))
    }

  @Test
  fun `a signed-out caller asking for the account page is sent to sign-in`() =
    testApplication {
      application { testPublicWebModule() }

      val noFollow = noFollowClient()
      for (path in listOf("/account", "/account/email")) {
        val response = noFollow.get(path)
        assertEquals(HttpStatusCode.Found, response.status, "[$path] did not redirect")
        assertEquals("/sign-in", response.headers[HttpHeaders.Location], "[$path] redirected somewhere other than sign-in")
      }
    }

  /**
   * A refused POST is redirected with `303`, not `302`.
   *
   * After a `302` a client may repeat the original METHOD, so the refused
   * sign-out or resend body could be re-POSTed at the sign-in form. `303` is
   * defined to turn the follow-up into a `GET` — the same rule every successful
   * POST in this slice already answers with.
   */
  @Test
  fun `a signed-out caller posting to a signed-in route is sent to sign-in with 303`() =
    testApplication {
      application { testPublicWebModule() }

      val noFollow = noFollowClient()
      for (path in listOf("/sign-out", "/verify-email/resend")) {
        val response = formPost(path, client = noFollow)
        assertEquals(
          HttpStatusCode.SeeOther,
          response.status,
          "[$path] refused a POST with something other than 303: a 302 may be repeated as a POST",
        )
        assertEquals("/sign-in", response.headers[HttpHeaders.Location])
      }
    }

  /**
   * The verification gate, and the whole reason it is a redirect: the family
   * must be told what to do next, and told it about THEIR address.
   */
  @Test
  fun `a signed-in but unverified caller is held at the verification screen, which names their address`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email, verified = false)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val redirect = noFollowClient().get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
      assertEquals(HttpStatusCode.Found, redirect.status)
      assertEquals("/verify-email", redirect.headers[HttpHeaders.Location])

      val hold = client.get("/verify-email") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
      assertEquals(HttpStatusCode.OK, hold.status)
      val body = hold.bodyAsText()
      assertTrue(body.contains("Check your email"), "the hold screen did not render: [$body]")
      assertTrue(body.contains(email), "the hold screen does not name the address the mail went to")
      assertTrue(body.contains("action=\"/verify-email/resend\""), "the hold screen offers no way to resend")
    }

  @Test
  fun `a signed-in and verified caller reaches the account page`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email, verified = true)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("Your account"))
    }

  /**
   * "Lands in the app, not on a marketing page" — as far as `web/02` can take
   * it before `web/04` exists.
   */
  @Test
  fun `a signed-in caller asking for the home page or either form is sent to their account`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val noFollow = noFollowClient()
      for (path in listOf("/", "/sign-in", "/register")) {
        val response = noFollow.get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
        assertEquals(HttpStatusCode.Found, response.status, "[$path] did not redirect a signed-in caller")
        assertEquals("/account", response.headers[HttpHeaders.Location], "[$path] redirected somewhere other than /account")
      }
    }

  /**
   * A signed-OUT visitor asking for `/verify-email` with no token has nothing to
   * verify and nothing to be held at, so the honest answer is the sign-in form.
   * Before RFC 224 there was no session to ask about and the page rendered
   * "Link not valid" to everyone.
   */
  @Test
  fun `a signed-out caller asking for verify-email with no token is sent to sign-in`() =
    testApplication {
      application { testPublicWebModule() }

      val response = noFollowClient().get("/verify-email")

      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/sign-in", response.headers[HttpHeaders.Location])
    }

  /**
   * Resend answers the SAME thing whether or not a token was issued. A different
   * answer per branch tells whoever holds the session whether the address is
   * already confirmed — state they did not have before pressing the button.
   */
  @Test
  fun `resend answers identically for a verified and an unverified caller`() =
    testApplication {
      val unverified = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(unverified, verified = false)
      application { testPublicWebModule() }
      val unverifiedToken = WebAuthTestSupport.login(unverified)

      val unverifiedBody = resend(unverifiedToken)

      val verified = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(verified, verified = true)
      val verifiedToken = WebAuthTestSupport.login(verified)
      val verifiedBody = resend(verifiedToken)

      assertEquals(
        unverifiedBody.replace(unverified, "ADDRESS"),
        verifiedBody.replace(verified, "ADDRESS"),
        "resend renders a different page for a verified caller, which leaks whether the address is confirmed",
      )
      assertTrue(unverifiedBody.contains("we have sent a new link"), "the resend confirmation changed")
    }

  /**
   * Resend, followed by the GET it redirects to.
   *
   * The POST answers 303 rather than rendering, so a reload cannot send a second
   * email; the confirmation the family reads is on the page this returns.
   */
  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.resend(token: String): String {
    val cookie = WebAuthTestSupport.cookieHeader(token)
    val noFollow = noFollowClient()

    val posted = formPost("/verify-email/resend", sessionToken = token, client = noFollow)
    assertEquals(HttpStatusCode.SeeOther, posted.status, "resend did not post-redirect-get: a reload would send another email")
    val location = posted.headers[HttpHeaders.Location].orEmpty()
    assertTrue(location.startsWith("/verify-email?"), "resend redirected somewhere unexpected: [$location]")

    val rendered = noFollow.get(location) { header(HttpHeaders.Cookie, cookie) }
    assertEquals(HttpStatusCode.OK, rendered.status)
    return rendered.bodyAsText()
  }
}
