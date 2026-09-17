package ed.unicoach.web

import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.PASSWORD_FIELD
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web session cookie, and the ONE thing about it that has to be true:
 * the cookie and the `sessions` row expire together (RFC 224 D-D).
 *
 * The 30-day figure is Ian's override of brief decision D21 at this run's gate.
 * There is no sliding refresh in the web tier, so a web session ends 30 days
 * after it was minted rather than 30 days after last use — which is exactly why
 * the number is 30 and not 7, and exactly why both halves are asserted here. A
 * cookie that outlives its row shows a signed-in shell to a signed-out user; a
 * row that outlives its cookie signs a family out early with no explanation.
 */
class SessionCookieTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `a web sign-in mints a 30-day row and a cookie whose Max-Age is the same 30 days`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val response = signIn(email)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
      assertTrue(
        setCookie.contains("Max-Age=${WEB_SESSION_LIFETIME.seconds}"),
        "the cookie does not carry the 30-day Max-Age; got [$setCookie]",
      )

      val rowSeconds = WebAuthTestSupport.soleSessionLifetimeSeconds()
      assertTrue(
        rowSeconds in (WEB_SESSION_LIFETIME - CLOCK_SLACK).seconds..WEB_SESSION_LIFETIME.seconds,
        "the sessions row lives [$rowSeconds]s, not the [${WEB_SESSION_LIFETIME.seconds}]s the cookie claims",
      )
    }

  /**
   * The split, stated from the CONFIG rather than from a served header, because
   * it is the config that makes it true. `rest-server` keeps 7 days: lengthening
   * every iOS session to match the web would widen the window in which a stolen
   * token is still good, which is a security change nobody asked for.
   */
  @Test
  fun `the web lifetime is 30 days and rest-server's shared block still says 7`() {
    assertEquals(Duration.ofDays(30), WebAuthTestSupport.sessionConfig.expiration, "publicWeb.session.expiration is no longer 30 days")
    assertEquals(
      Duration.ofDays(7),
      WebAuthTestSupport.config.getDuration("session.expiration"),
      "the shared session block's 7-day expiration changed: that is rest-server's iOS session lifetime",
    )
  }

  @Test
  fun `the cookie carries the shared identity and the fixed security attributes`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val setCookie = signIn(email).headers[HttpHeaders.SetCookie].orEmpty()
      val session = WebAuthTestSupport.sessionConfig

      assertTrue(setCookie.startsWith("${session.cookieName}="), "the cookie is not the shared name; got [$setCookie]")
      assertTrue(setCookie.contains("Domain=${session.cookieDomain}"), "the cookie is not on the shared domain; got [$setCookie]")
      assertTrue(setCookie.contains("Path=/"), "the cookie is not site-wide; got [$setCookie]")
      assertTrue(setCookie.contains("HttpOnly"), "the cookie is readable from script; got [$setCookie]")
      assertTrue(setCookie.contains("SameSite=Strict"), "the cookie lost SameSite=Strict; got [$setCookie]")
      assertEquals(
        session.cookieSecure,
        setCookie.contains("Secure"),
        "the cookie's Secure flag does not follow the configured value; got [$setCookie]",
      )
    }

  /**
   * The slice's HEADLINE claim, in the direction the web mints: a session
   * created by a web sign-in resolves through the same [AuthService] the iOS app
   * calls. One row, one cookie, either client.
   */
  @Test
  fun `a session minted by a web sign-in resolves through AuthService, as the iOS app would resolve it`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val setCookie = signIn(email).headers[HttpHeaders.SetCookie].orEmpty()
      val token = WebAuthTestSupport.tokenFromSetCookie(setCookie)

      val resolved = WebAuthTestSupport.resolveSession(token)
      assertEquals(
        user.id,
        resolved?.user?.id,
        "the token the web sign-in put in the browser's cookie does not resolve to the user through AuthService",
      )
      assertEquals(
        1,
        WebAuthTestSupport.liveSessionCount(user.id.value),
        "the web sign-in did not leave exactly one live session for the user",
      )
    }

  @Test
  fun `sign-out expires the cookie in the browser`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val token = WebAuthTestSupport.login(email)
      val response = formPost("/sign-out", sessionToken = token)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
      assertTrue(setCookie.contains("Max-Age=0"), "sign-out did not expire the cookie; got [$setCookie]")
    }

  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signIn(email: String) =
    formPost(
      "/sign-in",
      parameters {
        append(EMAIL_FIELD, email)
        append(PASSWORD_FIELD, WebAuthTestSupport.DEFAULT_PASSWORD)
      },
    )

  private companion object {
    /** The web session lifetime this suite asserts; `Max-Age` sees it as seconds. */
    val WEB_SESSION_LIFETIME: Duration = Duration.ofDays(30)

    /**
     * The row's remaining life is measured against `NOW()` AFTER it was
     * inserted, so it is always a little under the nominal figure. The window is
     * the assertion: "within a minute of 30 days" catches a 7 and catches a 0,
     * which is what this test is for, without failing on a slow machine.
     */
    val CLOCK_SLACK: Duration = Duration.ofMinutes(1)
  }
}
