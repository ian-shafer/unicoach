package ed.unicoach.web

import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.PASSWORD_FIELD
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sign in and sign out on the web (RFC 224).
 *
 * The suite that matters most here is the FAILURE one. `AuthService.login`
 * returns four distinct failures and the web must not distinguish them: the two
 * halves of "no such user" and "wrong password" are a list of who has an
 * account, and a form that answers them differently hands that list to anyone
 * who asks. So the four responses are compared to EACH OTHER rather than each
 * to an expected string — an edit that gave one branch its own message would
 * pass a per-branch assertion and fail this one.
 */
class SignInRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `GET sign-in renders the form with both controls and the door back to register`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/sign-in")

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "missing the sign-in heading")
      assertTrue(body.contains("action=\"/sign-in\""), "the form does not post to /sign-in")
      assertTrue(body.contains("method=\"post\""), "the form is not a POST")
      assertTrue(body.contains("name=\"email\""), "no email control")
      assertTrue(body.contains("name=\"password\""), "no password control")
      assertTrue(body.contains("href=\"/register\""), "no way to reach registration from the sign-in form")
      assertTrue(body.contains("class=\"site-header\""), "the form does not render in the site shell")
    }

  @Test
  fun `a correct password sets the session cookie and redirects to the account page`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val response = signIn(email, WebAuthTestSupport.DEFAULT_PASSWORD)

      assertEquals(HttpStatusCode.SeeOther, response.status, "a successful POST must answer 303, not 200 or 302")
      assertEquals("/account", response.headers[HttpHeaders.Location])
      assertTrue(
        response.headers[HttpHeaders.SetCookie].orEmpty().startsWith(WebAuthTestSupport.sessionConfig.cookieName),
        "no session cookie was set",
      )
    }

  /**
   * All four `LoginResult` failures, compared to one another.
   *
   * `InvalidEmail` (an unparseable address), `UserNotFound`, `PasswordNotSet`
   * (an SSO-only account) and `PasswordMismatch` are four different facts about
   * the account, and the response must carry none of them.
   */
  @Test
  fun `the four login failure branches are indistinguishable in status and body`() =
    testApplication {
      val known = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(known)
      val ssoOnly = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedPasswordlessUser(ssoOnly)
      application { testPublicWebModule() }

      val responses =
        mapOf(
          "InvalidEmail" to signIn("not-an-address", "whatever"),
          "UserNotFound" to signIn(WebAuthTestSupport.uniqueEmail(), WebAuthTestSupport.DEFAULT_PASSWORD),
          "PasswordNotSet" to signIn(ssoOnly, WebAuthTestSupport.DEFAULT_PASSWORD),
          "PasswordMismatch" to signIn(known, "the-wrong-password"),
        )

      val statuses = responses.mapValues { (_, response) -> response.status }
      assertEquals(
        responses.keys.associateWith { HttpStatusCode.Unauthorized },
        statuses,
        "a login failure branch answers its own status, which says which one it was",
      )

      val bodies = responses.mapValues { (_, response) -> response.bodyAsText() }
      val distinct = bodies.values.map { stripTypedEmail(it) }.distinct()
      assertEquals(
        1,
        distinct.size,
        "the four login failures render [${distinct.size}] different bodies: the form is an account-enumeration oracle",
      )
      assertTrue(
        bodies.values.all { it.contains("Email or password is incorrect.") },
        "the generic message is not what a failed sign-in renders",
      )
    }

  @Test
  fun `a failed sign-in re-renders the typed email and never the password`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val body = signIn(email, "the-wrong-password").bodyAsText()

      assertTrue(body.contains("value=\"$email\""), "the typed email was not re-rendered: [$body]")
      assertTrue(!body.contains("the-wrong-password"), "the typed PASSWORD was re-rendered into the HTML")
    }

  /**
   * THE D12 HONESTY TEST (RFC 224, brief 0013 D12).
   *
   * D12 says that wherever a session exists, every form stores its data in it.
   * Sign-in is a PRE-SESSION form — D5 mints nothing for a signed-out visitor —
   * so the rule does not reach it, and the value re-rendered above is ordinary
   * form handling and NOT session storage. This is the test that stops a later
   * reader believing otherwise: after a failed attempt the typed value is in the
   * HTML and there is NO session row at all, anywhere, to have stored it in.
   */
  @Test
  fun `after a failed sign-in the typed value is in the HTML and no session row exists`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val body = signIn(email, "the-wrong-password").bodyAsText()

      assertTrue(body.contains("value=\"$email\""), "the re-render did not carry the typed value")
      assertEquals(
        0,
        WebAuthTestSupport.sessionRowCount(),
        "a failed sign-in created a session row: the re-render would then be session storage, and D12 would apply",
      )
    }

  /**
   * The Operation Boundary rule: `receiveParameters` throws for a body that is
   * not form-encoded, and the old read let that land in the `exception<Throwable>`
   * catch-all as a branded 503 — a server-fault page for a client error. The
   * form must be re-read defensively and re-rendered instead.
   */
  @Test
  fun `a sign-in POST whose body is not a form re-renders the form, never the branded 503`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.post("/sign-in") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.Json)
          setBody("""{"email":"someone@example.com","password":"x"}""")
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status, "a malformed body must answer a client error, not a server fault")
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "the sign-in form was not re-rendered: [$body]")
      assertTrue(!body.contains("503 Service Unavailable"), "the malformed body was answered as a server fault: [$body]")
    }

  /**
   * A body carrying two `email` fields is a request this form did not render,
   * so it is re-rendered rather than the first of several being handed to
   * `:service`. The old `.orEmpty()` read silently took the first.
   */
  @Test
  fun `a sign-in body carrying two email fields is re-rendered without taking the first`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.post("/sign-in") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("email=first@example.com&email=second@example.com&password=${WebAuthTestSupport.DEFAULT_PASSWORD}")
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "the form was not re-rendered: [$body]")
      assertTrue(
        !body.contains("first@example.com"),
        "the first of two email fields was echoed, so the surplus was silently discarded: [$body]",
      )
    }

  @Test
  fun `sign-out revokes the row so the same cookie no longer resolves`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val token = WebAuthTestSupport.login(email)
      assertEquals(1, WebAuthTestSupport.liveSessionCount(user.id.value))

      val response = formPost("/sign-out", sessionToken = token)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/", response.headers[HttpHeaders.Location])
      assertEquals(0, WebAuthTestSupport.liveSessionCount(user.id.value), "sign-out did not revoke the session row")

      // The revoked cookie now behaves exactly as no cookie at all.
      val afterwards =
        noFollowClient().get("/account") {
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
        }
      assertEquals(HttpStatusCode.Found, afterwards.status)
      assertEquals("/sign-in", afterwards.headers[HttpHeaders.Location])
    }

  /**
   * The slice's other headline direction: a session minted the way `rest-server`
   * mints one resolves on a web page. "Signing in on the web and then using the
   * iOS app does not require signing in again, and the reverse holds."
   */
  @Test
  fun `a session minted through AuthService resolves on a web page request`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val token = WebAuthTestSupport.login(email)

      val response =
        client.get("/account") {
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
        }

      assertEquals(HttpStatusCode.OK, response.status, "a session minted outside the web tier did not resolve on a web page")
      assertTrue(response.bodyAsText().contains(email), "the account page does not name the signed-in address")
    }

  /**
   * The typed email is the ONE thing that legitimately differs between the four
   * failure bodies, because each branch is posted with a different address. It
   * is removed before comparison so the comparison is about the MESSAGE.
   */
  private fun stripTypedEmail(body: String): String = body.replace(Regex("value=\"[^\"]*\""), "value=\"\"")

  private suspend fun ApplicationTestBuilder.signIn(
    email: String,
    password: String,
  ): HttpResponse =
    formPost(
      "/sign-in",
      parameters {
        append(EMAIL_FIELD, email)
        append(PASSWORD_FIELD, password)
      },
    )
}
