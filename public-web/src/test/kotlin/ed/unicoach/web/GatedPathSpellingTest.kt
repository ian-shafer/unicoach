package ed.unicoach.web

import io.ktor.client.HttpClient
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
import io.ktor.http.encodedPath
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gated paths, spelled the ways a URL can be spelled (RFC 224 tier-2 T1).
 *
 * Ktor resolves a route AFTER dropping empty segments and percent-DECODING each
 * one, so `//account` and `/%61ccount` reach the very same handler as
 * `/account`. Any access decision taken by string-matching the RAW request path
 * therefore decides about a different path from the one that runs, and the
 * strings that disagree are exactly the ones an attacker gets to choose.
 *
 * So this suite asserts the OUTCOME rather than the mechanism: no spelling of a
 * gated path may render its page to a caller who is not entitled to it, and one
 * that reaches the route must be sent to the screen that caller belongs at.
 */
class GatedPathSpellingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  /** Every spelling of `/account` a client may send that Ktor resolves to the account route. */
  private val accountSpellings =
    listOf("/account", "//account", "/%61ccount", "/account/", "/./account")

  /** The same, for the change-email form. */
  private val changeEmailSpellings =
    listOf("/account/email", "//account/email", "/%61ccount/email", "/account/email/", "/./account/email", "/account/%65mail")

  @Test
  fun `no spelling of a gated path renders it to a signed-out caller`() =
    testApplication {
      application { testPublicWebModule() }

      assertEverySpellingRefused(cookie = null, expected = SIGN_IN)
    }

  @Test
  fun `no spelling of a gated path renders it to a signed-in but unverified caller`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email, verified = false)
      application { testPublicWebModule() }

      assertEverySpellingRefused(WebAuthTestSupport.cookieHeader(WebAuthTestSupport.login(email)), VERIFY_EMAIL)
    }

  /**
   * Walks EVERY spelling and reports EVERY one that got through, rather than
   * stopping at the first.
   *
   * Which spellings leak is the whole finding: a suite that aborts on
   * `//account` says nothing about `/%61ccount`, so a half-fix reads as
   * progress.
   */
  private suspend fun ApplicationTestBuilder.assertEverySpellingRefused(
    cookie: String?,
    expected: String,
  ) {
    val noFollow = noFollowClient()
    val leaks = mutableListOf<String>()

    for (path in accountSpellings + changeEmailSpellings) {
      leaks += refusalFailure(noFollow.rawGet(path, cookie), "GET [$path]", expected)
    }
    for (path in changeEmailSpellings) {
      leaks += refusalFailure(rawFormPost(path, cookie, noFollow), "POST [$path]", expected)
    }

    assertTrue(leaks.isEmpty(), "a gated path was reachable by [${leaks.size}] spelling(s):\n${leaks.joinToString("\n")}")
  }

  /**
   * Why [response] is NOT a refusal, or an empty list when it is.
   *
   * A refusal renders no gated content and answers no `200`. A spelling Ktor
   * does not resolve to the route at all may honestly 404; one that does
   * resolve must redirect, and to [expected] — the screen that says what this
   * caller has to do next.
   */
  private suspend fun refusalFailure(
    response: HttpResponse,
    request: String,
    expected: String,
  ): List<String> {
    val body = response.bodyAsText()
    val location = response.headers[HttpHeaders.Location]
    return when {
      ACCOUNT_MARKERS.any { body.contains(it) } -> {
        listOf("[$request] rendered gated content [${response.status}]")
      }

      response.status == HttpStatusCode.OK -> {
        listOf("[$request] answered 200 to a caller who may not see it")
      }

      response.status == HttpStatusCode.Found || response.status == HttpStatusCode.SeeOther -> {
        if (location == expected) emptyList() else listOf("[$request] redirected to [$location] rather than [$expected]")
      }

      response.status == HttpStatusCode.NotFound -> {
        emptyList()
      }

      else -> {
        listOf("[$request] answered [${response.status}], neither a redirect to [$expected] nor a 404")
      }
    }
  }

  /**
   * A `GET` at the path EXACTLY as written.
   *
   * `client.get("//account")` would be parsed as a protocol-relative URL and
   * never leave the client as that path, so the raw form is set on the builder.
   */
  private suspend fun HttpClient.rawGet(
    path: String,
    cookie: String?,
  ): HttpResponse =
    get {
      url { encodedPath = path }
      cookie?.let { header(HttpHeaders.Cookie, it) }
    }

  /** The `POST` twin of [rawGet], carrying the same-origin header every form POST carries. */
  private suspend fun ApplicationTestBuilder.rawFormPost(
    path: String,
    cookie: String?,
    client: HttpClient,
  ): HttpResponse =
    client.post {
      url { encodedPath = path }
      header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
      cookie?.let { header(HttpHeaders.Cookie, it) }
      contentType(ContentType.Application.FormUrlEncoded)
      setBody("email=someone%40example.com")
    }

  private companion object {
    const val SIGN_IN = "/sign-in"
    const val VERIFY_EMAIL = "/verify-email"

    /** Text that appears only on the two gated pages. */
    val ACCOUNT_MARKERS = listOf("Your account", "Change your email")
  }
}
