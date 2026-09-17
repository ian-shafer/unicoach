package ed.unicoach.web.common.http

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ONE session-cookie writer (RFC 224 D-B), pinned at the byte level.
 *
 * D-B collapsed five copies of one attribute set into one function, and that is
 * only worth doing if the header it emits is the header the copies emitted. So
 * the first two tests hold the PRE-RFC-224 rest-server code beside the shared
 * writer and compare the rendered `Set-Cookie` strings. A reordered, dropped or
 * differently-spelled attribute is a different cookie to a browser, whatever
 * the Kotlin reads like — comparing arguments would not catch that, and
 * comparing against a hand-typed expected string would pin Ktor's rendering
 * rather than our own change.
 */
class SessionCookieTest {
  @Test
  fun `the set header is byte-identical to the attribute set rest-server emitted before RFC 224`() =
    testApplication {
      routing {
        get("/shared") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = true,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
        get("/legacy") {
          call.legacySetSessionCookie()
          call.respondText("")
        }
      }

      val shared = client.get("/shared").headers[HttpHeaders.SetCookie]
      val legacy = client.get("/legacy").headers[HttpHeaders.SetCookie]

      assertEquals(legacy, shared, "the shared writer changed the Set-Cookie header rest-server emits")
      assertTrue(!shared.orEmpty().contains("Max-Age", ignoreCase = true), "rest-server's set path carries no Max-Age; got [$shared]")
    }

  @Test
  fun `the clear header is byte-identical to the attribute set rest-server emitted before RFC 224`() =
    testApplication {
      routing {
        get("/shared") {
          call.clearSharedSessionCookie(name = COOKIE_NAME, domain = DOMAIN, secure = true)
          call.respondText("")
        }
        get("/legacy") {
          call.legacyClearSessionCookie()
          call.respondText("")
        }
      }

      val shared = client.get("/shared").headers[HttpHeaders.SetCookie]
      val legacy = client.get("/legacy").headers[HttpHeaders.SetCookie]

      assertEquals(legacy, shared, "the shared writer changed the Set-Cookie header rest-server emits on logout")
      assertTrue(shared.orEmpty().contains("Max-Age=0"), "the clear form must expire the cookie; got [$shared]")
    }

  /**
   * The attributes that are FIXED here rather than passed in. A caller cannot
   * weaken any of them, which is the reason they are not parameters.
   */
  @Test
  fun `the cookie is always site-wide, HttpOnly and SameSite Strict`() =
    testApplication {
      routing {
        get("/set") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = false,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
      }

      val header = client.get("/set").headers[HttpHeaders.SetCookie].orEmpty()

      assertTrue(header.contains("Path=/"), "expected Path=/; got [$header]")
      assertTrue(header.contains("HttpOnly"), "expected HttpOnly; got [$header]")
      assertTrue(header.contains("SameSite=Strict"), "expected SameSite=Strict; got [$header]")
    }

  /** `secure` is a parameter because dev serves plain HTTP; it must actually vary. */
  @Test
  fun `Secure follows the configured flag in both directions`() =
    testApplication {
      routing {
        get("/secure") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = true,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
        get("/insecure") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = false,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
      }

      assertTrue(
        client
          .get("/secure")
          .headers[HttpHeaders.SetCookie]
          .orEmpty()
          .contains("Secure"),
      )
      assertTrue(
        !client
          .get("/insecure")
          .headers[HttpHeaders.SetCookie]
          .orEmpty()
          .contains("Secure"),
      )
    }

  /**
   * The one attribute this slice actually adds: a lifetime, for the web client
   * (RFC 224 D-D). 30 days is asserted against the row's expiry in `public-web`;
   * here it is only that the row's own [java.time.Duration] reaches the header
   * as seconds, which is the only unit `Max-Age` has.
   */
  @Test
  fun `a MatchingRow lifetime is emitted as Max-Age in seconds`() =
    testApplication {
      val lifetime = Duration.ofDays(30)

      routing {
        get("/set") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = true,
            lifetime = SessionCookieLifetime.MatchingRow(lifetime),
          )
          call.respondText("")
        }
      }

      val header = client.get("/set").headers[HttpHeaders.SetCookie].orEmpty()

      assertTrue(header.contains("Max-Age=${lifetime.seconds}"), "expected a 30-day Max-Age; got [$header]")
    }

  /**
   * The two lifetimes are DIFFERENT cookies, and the difference is the whole
   * reason the parameter is sealed: [SessionCookieLifetime.BrowserSession]
   * emits no `Max-Age` at all, which is not the same header as any number.
   */
  @Test
  fun `BrowserSession emits no Max-Age at all`() =
    testApplication {
      routing {
        get("/set") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = DOMAIN,
            secure = true,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
      }

      val header = client.get("/set").headers[HttpHeaders.SetCookie].orEmpty()

      assertTrue(!header.contains("Max-Age", ignoreCase = true), "a browser-session cookie must carry no Max-Age; got [$header]")
    }

  /** A null domain is host-only — `admin-web`'s shape, kept reachable. */
  @Test
  fun `a null domain emits no Domain attribute`() =
    testApplication {
      routing {
        get("/set") {
          call.setSharedSessionCookie(
            name = COOKIE_NAME,
            token = TOKEN,
            domain = null,
            secure = false,
            lifetime = SessionCookieLifetime.BrowserSession,
          )
          call.respondText("")
        }
      }

      val header = client.get("/set").headers[HttpHeaders.SetCookie].orEmpty()

      assertTrue(!header.contains("Domain"), "a null domain must be host-only; got [$header]")
    }

  private companion object {
    const val COOKIE_NAME = "UNICOACH_SESSION"
    const val TOKEN = "raw-session-token"
    const val DOMAIN = "cookie.example.test"

    /**
     * `rest-server`'s `setSessionCookie` exactly as it read at `main@8bca6fc0`,
     * copied here as the reference the shared writer must reproduce. It is a
     * FROZEN copy: it is not called by production code and must not be
     * "tidied" to match the writer, or the comparison asserts nothing.
     */
    fun ApplicationCall.legacySetSessionCookie() {
      response.cookies.append(
        name = COOKIE_NAME,
        value = TOKEN,
        domain = DOMAIN,
        path = "/",
        secure = true,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
      )
    }

    /** `rest-server`'s `clearSessionCookie` at `main@8bca6fc0`, frozen as above. */
    fun ApplicationCall.legacyClearSessionCookie() {
      response.cookies.append(
        name = COOKIE_NAME,
        value = "",
        domain = DOMAIN,
        path = "/",
        secure = true,
        httpOnly = true,
        maxAge = 0L,
        extensions = mapOf("SameSite" to "Strict"),
      )
    }
  }
}
