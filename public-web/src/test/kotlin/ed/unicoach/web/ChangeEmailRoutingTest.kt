package ed.unicoach.web

import ed.unicoach.web.render.EMAIL_FIELD
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Changing the account's email address (RFC 224 D-E).
 *
 * This is the one signed-in form in the slice, and it carries a NAMED deviation
 * from brief decision D12: the form-state rail D12 would write into is `web/05`
 * and does not exist yet, so this form re-renders the typed value on error and
 * stores no draft. The deviation is stated in the page's KDoc, in the RFC, and
 * here — and the last test below proves it is a deviation rather than a quiet
 * claim, by asserting the session's `metadata` column is still NULL.
 */
class ChangeEmailRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `the form names the current address and warns that verification is cleared`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val body = client.get("/account/email") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText()

      assertTrue(body.contains("Change your email address"), "missing the heading")
      assertTrue(body.contains(email), "the form does not name the address it is about to replace")
      assertTrue(body.contains("verification email"), "the form does not warn that the new address must be verified")
      assertTrue(body.contains("action=\"/account/email\""), "the form does not post to itself")
    }

  /**
   * The family must actually SEE the confirmation.
   *
   * Changing the address clears verification in `:service`, so `/account` is the
   * one page the gate then refuses — a `303` to `/account?notice=...` would be
   * bounced to `/verify-email` with the query string dropped, and the
   * confirmation would be unreachable rather than merely unseen. So the
   * assertion is not about the first hop: it follows the redirect and reads the
   * rendered sentence off the page the family lands on.
   */
  @Test
  fun `a valid change lands the family on the hold screen with the confirmation rendered`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val newEmail = WebAuthTestSupport.uniqueEmail()

      val response = changeEmail(token, newEmail)

      assertEquals(HttpStatusCode.SeeOther, response.status, "a successful POST must answer 303")
      val location = response.headers[HttpHeaders.Location].orEmpty()
      assertTrue(
        location.startsWith("/verify-email?"),
        "the redirect does not go to the page the gate will actually let them reach: [$location]",
      )

      val landed = noFollowClient().get(location) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
      assertEquals(HttpStatusCode.OK, landed.status, "the confirmation target is itself a redirect: [$location]")
      val body = landed.bodyAsText()
      assertTrue(
        body.contains("Your email address has been changed"),
        "the confirmation was never rendered to the family: [$body]",
      )
      assertTrue(body.contains(newEmail), "the hold screen does not name the NEW address the verification link went to")
    }

  @Test
  fun `an unparseable address re-renders the form at 400 with the service's own message`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = changeEmail(token, "not-an-address")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("value=\"not-an-address\""), "the typed value was not re-rendered: [$body]")
      assertTrue(body.contains("Email must be of the form"), "the message is not the one :service returned: [$body]")
    }

  @Test
  fun `an address already in use is refused at 409`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val taken = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      WebAuthTestSupport.seedUser(taken)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = changeEmail(token, taken)

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(response.bodyAsText().contains("cannot be used"))
    }

  /**
   * THE D12 HONESTY TEST, change-email half.
   *
   * Unlike sign-in and registration a session DOES exist here, so D12 reaches
   * this form and the deviation is real. What must not happen is the deviation
   * being mistaken for compliance: the re-rendered value above is ordinary form
   * handling, and the proof is that the session's `metadata` — the column a form
   * draft would live in — is untouched.
   */
  @Test
  fun `after a failed change-email the session metadata is still NULL`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      changeEmail(token, "not-an-address")

      assertNull(
        WebAuthTestSupport.soleSessionMetadata(),
        "the change-email re-render wrote a draft into the session: the RFC says it stores nothing, and web/05 owns the rail",
      )
    }

  private suspend fun ApplicationTestBuilder.changeEmail(
    token: String,
    newEmail: String,
  ): HttpResponse =
    formPost(
      "/account/email",
      parameters { append(EMAIL_FIELD, newEmail) },
      sessionToken = token,
    )
}
