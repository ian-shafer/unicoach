package ed.unicoach.web

import ed.unicoach.web.ChatTestSupport.coach
import ed.unicoach.web.ChatTestSupport.eventsOf
import ed.unicoach.web.ChatTestSupport.failingReplay
import ed.unicoach.web.ChatTestSupport.message
import ed.unicoach.web.ChatTestSupport.sendMessage
import ed.unicoach.web.ChatTestSupport.signedInStudent
import ed.unicoach.web.ChatTestSupport.textReplay
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The server's half of the chat client contract (RFC 262 §6, §10): what the
 * server SERVES — the chat page and its stream — against
 * `chat-client-contract.json`, the fixture `chat_test.ts` holds the client's
 * constants and `decodeEvent` to.
 *
 * Without it nothing fails when one side renames a hook: the composer never
 * mounts and the form does a full-page POST, or every event decodes to nothing
 * and is dropped as unknown. Neither is visible to a suite that tests each
 * language against its own literals.
 */
class ChatClientContractTest {
  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  @Test
  fun `the served chat page carries every hook chat_ts mounts on`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Start with ", "three schools."))) }
      val (_, token) = signedInStudent()
      val cookie = WebAuthTestSupport.cookieHeader(token)

      val empty = Jsoup.parse(client.get("/chat") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
      val composer = assertNotNull(empty.selectFirst(ChatClientContract.composerSelector), "no composer for chat.ts to mount on")
      assertTrue(
        composer.attr(ChatClientContract.retriableFailureAttribute).isNotBlank(),
        "the composer carries no retriable failure sentence for chat.ts to read",
      )
      val transcript = assertNotNull(empty.selectFirst(ChatClientContract.transcriptSelector), "no transcript for chat.ts to append to")
      assertNotNull(transcript.selectFirst(ChatClientContract.emptyStateSelector), "no empty state for chat.ts to remove")

      val events = eventsOf(sendMessage(client, "/chat", token, message("Where do I start?")).bodyAsText())
      val url =
        Json
          .parseToJsonElement(events.first().data)
          .jsonObject
          .getValue("conversationUrl")
          .jsonPrimitive.content
      val conversation = Jsoup.parse(client.get(url) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
      val exchange =
        assertNotNull(
          conversation.selectFirst("${ChatClientContract.transcriptSelector} .${ChatClientContract.exchangeClass}"),
          "the page renders no exchange of the shape chat.ts builds",
        )
      assertEquals(
        listOf(ChatClientContract.studentMessageClasses, ChatClientContract.coachMessageClasses),
        exchange.children().map { it.className() },
        "the page's exchange is not the student message and the coach message chat.ts builds",
      )
    }
  }

  /**
   * The CSRF half of the contract (RFC 265): the served composer carries the
   * session's token in the field `chat.ts` reads, and a send carrying it in the
   * header `chat.ts` names is admitted.
   */
  @Test
  fun `the composer carries the token chat_ts reads and the server admits it in the header chat_ts sends`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Hello."))) }
      val (_, token) = signedInStudent()
      val csrf = assertNotNull(WebAuthTestSupport.csrfTokenFor(token))

      val page = Jsoup.parse(client.get("/chat") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText())
      val field =
        assertNotNull(
          page.selectFirst("${ChatClientContract.composerSelector} input[name=${ChatClientContract.csrfField}]"),
          "the composer carries no CSRF field for chat.ts to read",
        )
      assertEquals(csrf, field.attr("value"), "the composer's CSRF field is not the session's token")

      val sent =
        client.post("/chat") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
          header(HttpHeaders.Accept, "text/event-stream, text/html")
          header(ChatClientContract.csrfHeader, csrf)
          setBody(FormDataContent(message("Hi")))
        }
      assertEquals(HttpStatusCode.OK, sent.status, "a send carrying the token in the contract's header was refused")
      assertTrue(sent.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
    }
  }

  @Test
  fun `every event the served stream writes is one chat_ts decodes, field for field`() {
    testApplication {
      application {
        testPublicWebModule(
          coachingService = coach(textReplay("Start with ", "three schools."), failingReplay("overloaded_error", "Half an ")),
        )
      }
      val (_, token) = signedInStudent()

      val written =
        listOf("Where do I start?", "And then?").flatMap { text ->
          eventsOf(sendMessage(client, "/chat", token, message(text)).bodyAsText())
        }

      for (event in written) {
        val expected = assertNotNull(ChatClientContract.events[event.name], "chat.ts decodes no [${event.name}] event")
        assertEquals(
          expected,
          ChatClientContract.fieldKinds(Json.parseToJsonElement(event.data).jsonObject),
          "the served [${event.name}] event's fields are not the ones chat.ts reads",
        )
      }
      assertEquals(
        ChatClientContract.events.keys,
        written.map { it.name }.toSet(),
        "the contract records an event the server never writes, or the server writes one it does not record",
      )
    }
  }
}
