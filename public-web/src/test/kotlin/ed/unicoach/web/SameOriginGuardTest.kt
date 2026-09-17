package ed.unicoach.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same-origin guard (RFC 224 D-C).
 *
 * The guard is the whole of this slice's CSRF defence until `web/05`'s tokens
 * land, and it has exactly one way to be useless: to pass a request it should
 * have refused. So the cases here are the EDGES — a foreign origin, a missing
 * origin, a `Referer` standing in for a missing `Origin`, both headers absent —
 * rather than the happy path alone, and each of them is a separate test so the
 * first failure does not hide the rest.
 */
class SameOriginGuardTest {
  private val ownOrigin = WebAuthTestSupport.urlBase
  private val foreignOrigin = "https://attacker.example"

  @Test
  fun `a POST carrying the site's own Origin proceeds`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { header(HttpHeaders.Origin, ownOrigin) }

      assertEquals(HttpStatusCode.OK, response.status, "a same-origin POST was refused")
    }

  @Test
  fun `a POST carrying a foreign Origin is refused with the branded 403`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { header(HttpHeaders.Origin, foreignOrigin) }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertForbiddenPage(response)
    }

  /**
   * The fallback, and the reason it reads only the ORIGIN of the `Referer`: a
   * form is served from `/sign-in` and posts to `/sign-in`, so a check that
   * compared whole URLs would depend on which page a family happened to come
   * from and would refuse a legitimate submission.
   */
  @Test
  fun `a POST with no Origin but a same-origin Referer proceeds`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { header(HttpHeaders.Referrer, "$ownOrigin/verify-email?token=abc") }

      assertEquals(HttpStatusCode.OK, response.status, "a POST with a same-origin Referer was refused")
    }

  @Test
  fun `a POST with no Origin and a foreign Referer is refused`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { header(HttpHeaders.Referrer, "$foreignOrigin/trap") }

      assertEquals(HttpStatusCode.Forbidden, response.status)
    }

  /**
   * THE strict arm, and the decision the RFC says was made consciously. Allowing
   * a header-less request is the arm that quietly re-opens the hole, because "no
   * headers at all" is exactly what an attacker would send if it were accepted.
   */
  @Test
  fun `a POST with neither Origin nor Referer is refused`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { }

      assertEquals(HttpStatusCode.Forbidden, response.status, "the strict arm was relaxed: a header-less POST was accepted")
    }

  /**
   * An `Origin` PRESENT and a `Referer` also present must be decided by the
   * `Origin`. A guard that fell back to `Referer` whenever the `Origin` did not
   * match would be defeated by any page that can control its own referrer.
   */
  @Test
  fun `a foreign Origin is refused even when the Referer is same-origin`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        postVerifyEmail {
          header(HttpHeaders.Origin, foreignOrigin)
          header(HttpHeaders.Referrer, "$ownOrigin/sign-in")
        }

      assertEquals(HttpStatusCode.Forbidden, response.status, "the Referer overrode a foreign Origin")
    }

  /** A browser's opaque origin (a sandboxed or `file://` document) is not this site. */
  @Test
  fun `the literal opaque origin null is refused`() =
    testApplication {
      application { testPublicWebModule() }

      val response = postVerifyEmail { header(HttpHeaders.Origin, "null") }

      assertEquals(HttpStatusCode.Forbidden, response.status)
    }

  /**
   * The three-state rule (RFC 224 D-C): a PRESENT `Origin` is the claim, so an
   * opaque `Origin: null` must NOT fall back to a same-origin `Referer`. The old
   * elvis demoted a present-but-unparseable origin to the weaker header and let
   * exactly this request through.
   */
  @Test
  fun `an opaque Origin null is refused even when the Referer is same-origin`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        postVerifyEmail {
          header(HttpHeaders.Origin, "null")
          header(HttpHeaders.Referrer, "$ownOrigin/sign-in")
        }

      assertEquals(HttpStatusCode.Forbidden, response.status, "the unparseable Origin fell back to the same-origin Referer")
    }

  /**
   * The guard is an ALLOWLIST, not a list of methods it happened to remember:
   * only `GET`/`HEAD` are exempt, so a method this file does not name — `OPTIONS`
   * today, whatever a later slice registers tomorrow — is still checked.
   */
  @Test
  fun `a method outside the safe set is checked even when this file does not name it`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.options("/terms") {
          header(HttpHeaders.Origin, foreignOrigin)
        }

      assertEquals(HttpStatusCode.Forbidden, response.status, "an unlisted method walked past the same-origin guard")
    }

  /**
   * GET is NEVER checked. Every link, prefetch and crawler is a `GET` with no
   * `Origin`; checking them would break the site for ordinary navigation while
   * protecting nothing, because a request that changes no state is not worth
   * forging.
   */
  @Test
  fun `a GET carrying a foreign Origin is untouched`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.get("/terms") {
          header(HttpHeaders.Origin, foreignOrigin)
        }

      assertEquals(HttpStatusCode.OK, response.status, "a safe method was refused by the same-origin guard")
    }

  @Test
  fun `a GET with no Origin at all is untouched`() =
    testApplication {
      application { testPublicWebModule() }

      assertEquals(HttpStatusCode.OK, client.get("/terms").status)
    }

  /**
   * The refusal is a PAGE, not a blank body. The people most likely to meet it
   * are a family whose browser strips both headers, and a blank 403 is
   * indistinguishable from the site being broken.
   */
  private suspend fun assertForbiddenPage(response: HttpResponse) {
    val body = response.bodyAsText()
    assertTrue(body.contains("403 Request blocked"), "the refusal is not the branded 403 page: [$body]")
    assertTrue(body.contains("class=\"site-header\""), "the refusal does not render in the site shell")
    assertTrue(body.contains("href=\"/sign-in\""), "the refusal does not link back to the form")
    assertTrue(
      !body.contains(foreignOrigin),
      "the refusal echoes the origin it refused, which tells a prober exactly what the guard compared",
    )
  }

  /**
   * `POST /verify-email` is the probe on purpose: it is the one state-changing
   * route that predates this slice, it needs no session, and a blank token is a
   * complete no-op that renders a page. So each test here is about the GUARD and
   * nothing else — a failure cannot be a sign-in bug wearing a guard's clothes.
   */
  private suspend fun ApplicationTestBuilder.postVerifyEmail(headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
    client.post("/verify-email") {
      contentType(ContentType.Application.FormUrlEncoded)
      setBody("token=")
      headers()
    }
}
