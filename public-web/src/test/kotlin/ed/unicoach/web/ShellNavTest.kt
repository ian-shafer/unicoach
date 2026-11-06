package ed.unicoach.web

import ed.unicoach.web.auth.COLLEGES_PATH
import ed.unicoach.web.auth.CONVERSATIONS_PATH
import ed.unicoach.web.auth.YOUR_DETAILS_PATH
import ed.unicoach.web.render.CONVERSATIONS_LINK
import ed.unicoach.web.render.FAFSA_PATH
import ed.unicoach.web.render.MY_COLLEGES_TITLE
import ed.unicoach.web.render.SETTINGS_HEADING
import ed.unicoach.web.render.YOUR_DETAILS_TITLE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's navigation, signed out and signed in (RFC 224).
 *
 * The DOOR — "a Sign in link in the shell's navigation" — is the slice's stated
 * entry point, and it lives in the shared header rather than on a page for the
 * reason every shared thing here lives in one place: a per-page link is missing
 * from the page that forgot it, and a door nobody can find is not a door.
 */
class ShellNavTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `every signed-out page carries exactly one sign-in link, and no account link`() =
    testApplication {
      application { testPublicWebModule() }

      for (path in listOf("/", "/terms", "/privacy", FAFSA_PATH, "/sign-in", "/register")) {
        // OK FIRST, and the path read from the page that owns it. The branded
        // 404 renders this very header, so both assertions below pass for a
        // path that is no longer a page at all -- which is what a renamed
        // route would leave behind.
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status, "[$path] is not a page")
        // The HEADER, not the whole page. The registration page also links to
        // sign-in from its own body ("Already have an account?"), which is a
        // different link with a different job; counting over the whole document
        // would make this test pass or fail on page copy.
        val header = headerOf(response.bodyAsText())
        assertEquals(
          1,
          Regex("<a href=\"/sign-in\">Sign in</a>").findAll(header).count(),
          "[$path] does not carry exactly one shell sign-in link; header was [$header]",
        )
        assertTrue(!header.contains("href=\"/account\""), "[$path] offers an account link to a signed-out visitor")
        assertTrue(!header.contains("href=\"/chat\""), "[$path] offers the chat to a signed-out visitor")
        assertTrue(!header.contains("action=\"/sign-out\""), "[$path] offers a sign-out control to a signed-out visitor")
      }
    }

  @Test
  fun `the signed-in shell carries the account link and the sign-out form, and no sign-in link`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedStudentUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val shellHeader = headerOf(client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText())

      assertTrue(shellHeader.contains("href=\"/account\""), "the signed-in header carries no account link")
      // RFC 273, D-A: the door to Settings is the last link before sign-out,
      // its label the page's own heading and its path unchanged.
      val settingsLink = "<a href=\"/account\">$SETTINGS_HEADING</a>"
      val settingsAt = shellHeader.indexOf(settingsLink)
      val signOutAt = shellHeader.indexOf("action=\"/sign-out\"")
      assertTrue(
        settingsAt >= 0 && settingsAt < signOutAt,
        "the signed-in header carries no Settings link before sign-out; header was [$shellHeader]",
      )
      assertTrue(
        !shellHeader.substring(settingsAt + settingsLink.length, signOutAt).contains("<a "),
        "a link sits between Settings and sign-out; header was [$shellHeader]",
      )
      // RFC 262: "Chat" comes before "Settings", so the signed-in root is the first door.
      assertTrue(
        shellHeader.indexOf("<a href=\"/chat\">Chat</a>").let { it >= 0 && it < shellHeader.indexOf("href=\"/account\"") },
        "the signed-in header carries no Chat link before Settings; header was [$shellHeader]",
      )
      // RFC 270: "Your details" sits between "Chat" and "Settings", its words and path the page's own.
      val detailsLink = "<a href=\"$YOUR_DETAILS_PATH\">$YOUR_DETAILS_TITLE</a>"
      assertTrue(
        shellHeader
          .indexOf(
            detailsLink,
          ).let { it > shellHeader.indexOf("href=\"/chat\"") && it < shellHeader.indexOf("href=\"/account\"") },
        "the signed-in header carries no Your details link between Chat and Settings; header was [$shellHeader]",
      )
      // RFC 271: "My colleges" sits between "Chat" and "Your details", its words and path the page's own.
      val collegesAt = shellHeader.indexOf("<a href=\"$COLLEGES_PATH\">$MY_COLLEGES_TITLE</a>")
      assertTrue(
        collegesAt > shellHeader.indexOf("href=\"/chat\"") && collegesAt < shellHeader.indexOf(detailsLink),
        "the signed-in header carries no My colleges link between Chat and Your details; header was [$shellHeader]",
      )
      // RFC 272, D-A: "Conversations" sits right after "Chat", its path the page's own.
      val conversationsAt = shellHeader.indexOf("<a href=\"$CONVERSATIONS_PATH\">$CONVERSATIONS_LINK</a>")
      assertTrue(
        conversationsAt > shellHeader.indexOf("href=\"/chat\"") && conversationsAt < collegesAt,
        "the signed-in header carries no Conversations link between Chat and My colleges; header was [$shellHeader]",
      )
      assertTrue(shellHeader.contains("action=\"/sign-out\""), "the signed-in header carries no sign-out form")
      assertTrue(shellHeader.contains("method=\"post\""), "sign-out is not a POST: an anchor would be followed by every prefetcher")
      assertTrue(!shellHeader.contains("href=\"/sign-in\""), "the signed-in header still offers the door")
    }

  /**
   * A supporter's header offers Your details too (RFC 270, D-D), as it offers
   * the chat: the header is not role-aware, and the route sends a supporter to
   * their account.
   */
  @Test
  fun `a supporter's signed-in header carries the Conversations, Your details and My colleges links`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      runBlocking { WebAuthTestSupport.supporterService.ensure(user.id).getOrThrow() }
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(headerOf(response.bodyAsText()).contains("<a href=\"$YOUR_DETAILS_PATH\">$YOUR_DETAILS_TITLE</a>"))
      // My colleges (RFC 271) by the same rule.
      assertTrue(headerOf(response.bodyAsText()).contains("<a href=\"$COLLEGES_PATH\">$MY_COLLEGES_TITLE</a>"))
      // Conversations (RFC 272) by the same rule.
      assertTrue(headerOf(response.bodyAsText()).contains("<a href=\"$CONVERSATIONS_PATH\">$CONVERSATIONS_LINK</a>"))
    }

  /**
   * The header is resolved PER REQUEST, on every page — not wired into the three
   * responders that happen to be about a session.
   *
   * The sign-out control lives in the shared header, so a page that renders the
   * signed-out header is a page a signed-in family cannot sign out from, and a
   * page that offers them a door they are already through. The legal pages, the
   * guide and the branded 404 are the ones a signed-in family reaches most
   * easily, so they are the ones asserted.
   */
  @Test
  fun `a signed-in family gets the signed-in header on every page, error pages included`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      // EACH PATH WITH THE STATUS IT OWES. The 404 page is deliberately in the
      // set -- it is a page a signed-in family reaches -- and it renders the
      // same header as every other, so without the expected status beside it a
      // real page that had been renamed away would pass here as "the 404".
      val pages =
        listOf(
          "/terms" to HttpStatusCode.OK,
          "/privacy" to HttpStatusCode.OK,
          "/guides/who-pays-for-college-advice" to HttpStatusCode.OK,
          FAFSA_PATH to HttpStatusCode.OK,
          "/no-such-page" to HttpStatusCode.NotFound,
        )
      for ((path, expected) in pages) {
        val response =
          noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
        assertEquals(expected, response.status, "[$path] did not answer as the page this case is about")
        val shellHeader = headerOf(response.bodyAsText())
        assertTrue(
          shellHeader.contains("action=\"/sign-out\""),
          "[$path] renders no sign-out control for a signed-in family: they cannot sign out from this page",
        )
        assertTrue(
          !shellHeader.contains("href=\"/sign-in\""),
          "[$path] offers the sign-in door to a family who is already signed in",
        )
      }
    }

  /**
   * The first production caller of the [ed.unicoach.web.render.ShellWidth.WIDE]
   * seam RFC 210 opened — whose own KDoc named this page.
   */
  @Test
  fun `the account page renders at the wide measure`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedStudentUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.bodyAsText().contains("<body class=\"wide-measure\">"),
        "the signed-in home is not at the wide measure",
      )
    }

  /**
   * The pages that existed before RFC 224 render the SAME body they did. The
   * shell's new anchor is shared chrome and is asserted above; what must not
   * have happened is a page's own content changing because a nav parameter was
   * threaded through it.
   */
  @Test
  fun `a page that existed before RFC 224 renders its own content unchanged`() =
    testApplication {
      application { testPublicWebModule() }

      val body = client.get("/").bodyAsText()
      val main = body.substringAfter("<main>").substringBefore("</main>")

      assertTrue(main.contains("unicoach"), "the home page body no longer renders: [$main]")
      assertTrue(!main.contains("/sign-in"), "the door leaked out of the shared header and into the page body")
      assertTrue(body.contains("<body>"), "the default shell width no longer renders a bare <body>")
    }

  /**
   * The shared header element only. Every assertion here is about the SHELL's
   * navigation, and a page's own body may legitimately link to the same places.
   */
  private fun headerOf(body: String): String =
    body
      .substringAfter("<header class=\"site-header\">", "")
      .substringBefore("</header>")
      .also { check(it.isNotBlank()) { "no site-header in the rendered page: [$body]" } }
}
