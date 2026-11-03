package ed.unicoach.web

import ed.unicoach.coaching.DeleteConvoResult
import ed.unicoach.db.models.ConvoId
import ed.unicoach.web.ChatTestSupport.coach
import ed.unicoach.web.ChatTestSupport.eventsOf
import ed.unicoach.web.ChatTestSupport.message
import ed.unicoach.web.ChatTestSupport.sendMessage
import ed.unicoach.web.ChatTestSupport.signedInStudent
import ed.unicoach.web.ChatTestSupport.textReplay
import ed.unicoach.web.render.CHAT_EMPTY_PROMPT
import ed.unicoach.web.render.NOSCRIPT_LINE
import ed.unicoach.web.render.ROLE_CHOICE_QUESTION
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The chat's pages, its door and its student gate (RFC 262 §4), over the real
 * database and the real auth stack. Every "where was I sent" assertion uses
 * [noFollowClient], so a redirect is never mistaken for the page it led to.
 */
class ChatRoutingTest {
  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private suspend fun ApplicationTestBuilder.getAs(
    path: String,
    token: String?,
  ) = noFollowClient().get {
    // The ENCODED path, as sent: `//chat` and `/%63hat` must reach the server
    // spelled that way, not be read as a host or decoded by the client.
    url { encodedPath = path }
    token?.let { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(it)) }
  }

  /** Test 9: the chat is VERIFIED, by the route's own declaration, however the path is spelled. */
  @Test
  fun `a signed-out or unverified caller is refused the chat, by every spelling of its path`() {
    testApplication {
      application { testPublicWebModule() }
      val unverified = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), verified = false)
      WebAuthTestSupport.seedStudent(unverified)
      val unverifiedToken = WebAuthTestSupport.login(unverified.email.value)

      for (path in listOf("/chat", "//chat", "/%63hat", "/chat/${UUID.randomUUID()}")) {
        val signedOut = getAs(path, token = null)
        assertEquals(HttpStatusCode.Found, signedOut.status, "[$path] signed out")
        assertEquals("/sign-in", signedOut.headers[HttpHeaders.Location], "[$path] signed out")

        val held = getAs(path, unverifiedToken)
        assertEquals(HttpStatusCode.Found, held.status, "[$path] unverified")
        assertEquals("/verify-email", held.headers[HttpHeaders.Location], "[$path] unverified")
      }
    }
  }

  /** Test 10: the signed-in root is the chat, and the header names it on the chat page itself. */
  @Test
  fun `the signed-in root lands on the chat and the header carries Chat`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      for (path in listOf("/", "/sign-in", "/register")) {
        assertEquals("/chat", getAs(path, token).headers[HttpHeaders.Location], "[$path]")
      }
      val header = Jsoup.parse(getAs("/chat", token).bodyAsText()).selectFirst("header.site-header")!!
      assertEquals(listOf("/chat", "/account"), header.select("a[href=/chat], a[href=/account]").map { it.attr("href") })
    }
  }

  /** Test 11: a new conversation is the empty state and a composer posting to `/chat`, with the no-JS line. */
  @Test
  fun `GET chat renders the empty state and a composer posting to chat`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      val response = getAs("/chat", token)
      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(CHAT_EMPTY_PROMPT, page.selectFirst(".chat-transcript .chat-empty")?.text())
      val composer = assertNotNull(page.selectFirst("form.chat-composer"), "no composer")
      assertEquals("/chat", composer.attr("action"))
      assertEquals("post", composer.attr("method"))
      assertNotNull(composer.selectFirst("textarea[name=message]"), "no message field")
      assertEquals(NOSCRIPT_LINE, composer.selectFirst("noscript")?.text())
      assertTrue(page.body().hasClass("chat-shell"), "the chat is not at its own shell width")
      assertLoadsBundleAsItsOnlyScript("/chat", response.bodyAsText())
    }
  }

  /** Test 12: a conversation's page shows what was said, the reply rendered, and the latest anchor. */
  @Test
  fun `GET chat id shows the exchange with the reply rendered, anchored at the latest`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Here is **one** idea."))) }
      val (_, token) = signedInStudent()

      val started = eventsOf(sendMessage(client, "/chat", token, message("Where do I start?\nPlease")).bodyAsText()).first()
      val url =
        Json
          .parseToJsonElement(started.data)
          .jsonObject
          .getValue("conversationUrl")
          .jsonPrimitive.content

      val page = Jsoup.parse(getAs(url, token).bodyAsText())
      val latest = assertNotNull(page.getElementById("latest"), "no #latest anchor")
      assertEquals("Where do I start?\nPlease", latest.selectFirst(".message.student")!!.wholeText())
      assertEquals("one", latest.selectFirst(".message.coach .markdown strong")?.text(), "the reply is not rendered Markdown")
      assertEquals(url, page.selectFirst("form.chat-composer")!!.attr("action"), "the composer does not continue this conversation")
    }
  }

  /** Test 13: a conversation that is not this student's to read is the branded 404, whatever the reason. */
  @Test
  fun `another student's, a deleted and a non-UUID conversation are the branded 404`() {
    testApplication {
      val coachingService = coach(textReplay("A."), textReplay("B."))
      application { testPublicWebModule(coachingService = coachingService) }
      val (_, ownerToken) = signedInStudent()
      val (_, otherToken) = signedInStudent()
      val (deleter, deleterToken) = signedInStudent()

      val othersUrl = conversationUrlOf(sendMessage(client, "/chat", ownerToken, message("Mine")).bodyAsText())
      val deletedUrl = conversationUrlOf(sendMessage(client, "/chat", deleterToken, message("Gone")).bodyAsText())
      val deleted = ConvoId(UUID.fromString(deletedUrl.substringAfterLast('/')))
      val deleterStudent = assertNotNull(runBlocking { WebAuthTestSupport.studentService.getStudentForUser(deleter.id).getOrThrow() })
      assertEquals(DeleteConvoResult.Success, runBlocking { coachingService.deleteConvo(deleterStudent.id, deleted).getOrThrow() })

      for ((path, token) in listOf(othersUrl to otherToken, deletedUrl to deleterToken, "/chat/not-a-uuid" to otherToken)) {
        val response = getAs(path, token)
        assertEquals(HttpStatusCode.NotFound, response.status, "[$path]")
        assertTrue(response.bodyAsText().contains("404 Not Found"), "[$path] is not the branded 404")
      }
    }
  }

  /** Test 14: the student gate — no role is sent to onboarding, a parent to their account. */
  @Test
  fun `a verified user with no student row is sent to get-started, before any chat handler runs`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach()) }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      for (path in listOf("/chat", "//chat", "/chat/${UUID.randomUUID()}")) {
        val response = getAs(path, token)
        assertEquals(HttpStatusCode.Found, response.status, "[$path]")
        assertEquals("/get-started", response.headers[HttpHeaders.Location], "[$path]")
      }

      val post = sendMessage(noFollowClient(), "/chat", token, message("Hello"))
      assertEquals(HttpStatusCode.SeeOther, post.status, "a refused POST must answer 303")
      assertEquals("/get-started", post.headers[HttpHeaders.Location])
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM convos"), "no turn may run for a caller with no student")

      // Where they are sent is onboarding's role choice (RFC 268).
      val onboarding = Jsoup.parse(getAs("/get-started", token).bodyAsText())
      assertEquals(ROLE_CHOICE_QUESTION, onboarding.selectFirst("h2")?.text())
    }
  }

  @Test
  fun `a supporter-only account is sent to its account page, and a student past get-started`() {
    testApplication {
      application { testPublicWebModule() }
      val parent = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      runBlocking { WebAuthTestSupport.supporterService.ensure(parent.id).getOrThrow() }
      val parentToken = WebAuthTestSupport.login(parent.email.value)
      assertEquals("/account", getAs("/chat", parentToken).headers[HttpHeaders.Location])

      // An account holding both rows is a student, as on iOS.
      val (both, bothToken) = signedInStudent()
      runBlocking { WebAuthTestSupport.supporterService.ensure(both.id).getOrThrow() }
      assertEquals(HttpStatusCode.OK, getAs("/chat", bothToken).status)
      assertEquals("/chat", getAs("/get-started", bothToken).headers[HttpHeaders.Location])
    }
  }

  /** Test 15: the same-origin guard covers the chat's POST like every other state change. */
  @Test
  fun `a cross-site POST to chat is refused and runs no turn`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach()) }
      val (_, token) = signedInStudent()

      val response =
        noFollowClient().post("/chat") {
          header(HttpHeaders.Origin, "https://evil.example")
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
          setBody(FormDataContent(message("Hello")))
        }
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM convos"))
    }
  }

  private fun conversationUrlOf(streamBody: String): String =
    Json
      .parseToJsonElement(eventsOf(streamBody).first { it.name == "started" }.data)
      .jsonObject
      .getValue("conversationUrl")
      .jsonPrimitive.content
}
