package ed.unicoach.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.studentSentence
import ed.unicoach.db.models.ConvoId
import ed.unicoach.web.ChatTestSupport.Event
import ed.unicoach.web.ChatTestSupport.coach
import ed.unicoach.web.ChatTestSupport.eventsOf
import ed.unicoach.web.ChatTestSupport.failingReplay
import ed.unicoach.web.ChatTestSupport.message
import ed.unicoach.web.ChatTestSupport.sendMessage
import ed.unicoach.web.ChatTestSupport.signedInStudent
import ed.unicoach.web.ChatTestSupport.textReplay
import ed.unicoach.web.WebAuthTestSupport.count
import ed.unicoach.web.auth.CSRF_HEADER
import ed.unicoach.web.render.CHAT_MESSAGE_FIELD
import ed.unicoach.web.render.CONVERSATION_UNAVAILABLE
import ed.unicoach.web.render.MESSAGE_NOT_SENT
import ed.unicoach.web.render.MalformedSend
import ed.unicoach.web.render.OUT_OF_ALLOWANCE_NEXT_STEP
import ed.unicoach.web.render.OUT_OF_ALLOWANCE_TITLE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat's own stream and its pre-stream notices (RFC 262 §5, §6), driven
 * through the real coach — the production Anthropic provider over a scripted
 * transport — against the real database.
 */
class ChatStreamTest {
  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private fun dataOf(event: Event): JsonObject = Json.parseToJsonElement(event.data).jsonObject

  private fun string(
    event: Event,
    key: String,
  ): String = dataOf(event).getValue(key).jsonPrimitive.content

  private suspend fun assertIsStream(response: HttpResponse) {
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  /** Tests 16 and 17: a new conversation streams, renders what a reload renders, and is extracted once. */
  @Test
  fun `POST chat streams started, the deltas and completed, and the completed html is the page's own`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Start with ", "**three** ", "schools."))) }
      val (_, token) = signedInStudent()

      val response = sendMessage(client, "/chat", token, message("Where do I start?"))
      assertIsStream(response)
      val events = eventsOf(response.bodyAsText())

      assertEquals("started", events.first().name)
      assertEquals(listOf("delta", "delta", "delta"), events.drop(1).dropLast(1).map { it.name })
      assertEquals("completed", events.last().name, "exactly one terminal, last")
      assertEquals("Start with **three** schools.", events.filter { it.name == "delta" }.joinToString("") { string(it, "text") })

      val url = string(events.first(), "conversationUrl")
      val convoId = url.removePrefix("/chat/")
      assertEquals(1, count("SELECT count(*) FROM convos WHERE id = '$convoId'::uuid"), "started names the new conversation")

      val page = Jsoup.parse(client.get(url) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText())
      val rendered = page.selectFirst("#latest .message.coach")!!
      val streamed = Jsoup.parseBodyFragment(string(events.last(), "html")).body().child(0)
      assertEquals(normalized(rendered), normalized(streamed), "completed.html is not the fragment the page renders")
      assertEquals("three", streamed.selectFirst("strong")?.text())

      assertEquals(
        1,
        count("SELECT count(*) FROM jobs WHERE job_type = 'EXTRACT_CONVERSATION' AND payload->>'convoId' = '$convoId'"),
        "a web turn must be extracted, exactly as an iOS one is",
      )
    }
  }

  /** Test 18: a conversation's own URL continues it. */
  @Test
  fun `POST chat id appends a second exchange`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("First."), textReplay("Second."))) }
      val (_, token) = signedInStudent()

      val url = string(eventsOf(sendMessage(client, "/chat", token, message("One")).bodyAsText()).first(), "conversationUrl")
      val second = sendMessage(client, url, token, message("Two"))
      assertIsStream(second)
      val events = eventsOf(second.bodyAsText())
      assertEquals(url, string(events.first(), "conversationUrl"))
      assertEquals("completed", events.last().name)

      val page = Jsoup.parse(client.get(url) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText())
      assertEquals(listOf("One", "Two"), page.select(".exchange .message.student").map { it.text() })
      assertEquals(listOf("First.", "Second."), page.select(".exchange .message.coach").map { it.text() })
    }
  }

  /** Test 19: a failure after deltas is one `failed` event with `:service`'s sentence and nothing of the reason. */
  @Test
  fun `deltas then a provider failure end in failed, retriable or not, and say nothing of the reason`() {
    for ((errorType, retriable) in listOf("overloaded_error" to true, "invalid_request_error" to false)) {
      WebAuthTestSupport.resetDatabase()
      testApplication {
        application { testPublicWebModule(coachingService = coach(failingReplay(errorType, "Half an ", "answer"))) }
        val (_, token) = signedInStudent()

        val response = sendMessage(client, "/chat", token, message("Hi"))
        assertIsStream(response)
        val body = response.bodyAsText()
        val events = eventsOf(body)

        assertEquals(listOf("started", "delta", "delta", "failed"), events.map { it.name }, "[$errorType]")
        val failed = dataOf(events.last())
        assertEquals(retriable, failed.getValue("retriable").jsonPrimitive.boolean, "[$errorType]")
        assertEquals(
          studentSentence(retriable),
          failed.getValue("message").jsonPrimitive.content,
          "[$errorType]",
        )
        assertFalse(body.contains("SECRET-PROVIDER-REASON"), "[$errorType] the provider's reason reached the browser")
        assertFalse(body.contains(errorType), "[$errorType] the error type reached the browser")
        assertEquals(0, count("SELECT count(*) FROM convos WHERE deleted_at IS NULL"), "[$errorType] a failed first turn leaves no convo")
        assertEquals(0, count("SELECT count(*) FROM jobs WHERE job_type = 'EXTRACT_CONVERSATION'"), "[$errorType]")
      }
    }
  }

  /** Test 20: every pre-flight refusal is a notice at its status, with no event bytes and nothing persisted. */
  @Test
  fun `a blank or absent message is a 400 notice and runs no turn`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach()) }
      val (_, token) = signedInStudent()

      val blank = sendMessage(client, "/chat", token, message("   "))
      assertNotice(blank, HttpStatusCode.BadRequest)
      assertTrue(blank.bodyAsText().contains("Message must not be blank"), "the notice does not carry :service's sentence")

      val absent = sendMessage(client, "/chat", token, Parameters.Empty)
      assertNotice(absent, HttpStatusCode.BadRequest)
      assertTrue(absent.bodyAsText().contains(MESSAGE_NOT_SENT), "a malformed send's notice must say the message did not go")
      assertEquals(0, count("SELECT count(*) FROM convos"))
    }
  }

  /** Test 31: two `message` fields are not a submission of the page's form; the composer sends one. */
  @Test
  fun `a repeated message field is a 400 notice and runs no turn`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Never sent."))) }
      val (_, token) = signedInStudent()
      val llmRequestsBefore = count("SELECT count(*) FROM llm_requests")

      val repeated =
        Parameters.build {
          append(CHAT_MESSAGE_FIELD, "One")
          append(CHAT_MESSAGE_FIELD, "Two")
        }
      val response = sendMessage(client, "/chat", token, repeated)
      assertNotice(response, HttpStatusCode.BadRequest)
      assertTrue(response.bodyAsText().contains(MESSAGE_NOT_SENT), "a malformed send's notice must say the message did not go")
      assertEquals(0, count("SELECT count(*) FROM convos"), "a refused send persists no conversation")
      assertEquals(llmRequestsBefore, count("SELECT count(*) FROM llm_requests"), "a refused send makes no provider call")
    }
  }

  /** The student reads one sentence for every malformed send; the log keeps which shape each one was. */
  @Test
  fun `each malformed send is logged with its own cause`() {
    val chatLog = LoggerFactory.getLogger("ed.unicoach.web.Chat") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    chatLog.addAppender(appender)
    try {
      testApplication {
        application { testPublicWebModule(coachingService = coach()) }
        val (_, token) = signedInStudent()

        val repeated =
          Parameters.build {
            append(CHAT_MESSAGE_FIELD, "One")
            append(CHAT_MESSAGE_FIELD, "Two")
          }
        val unreadable =
          client.post("/chat") {
            header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
            header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
            header(CSRF_HEADER, WebAuthTestSupport.csrfTokenFor(token)!!)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"message\": \"Hi\"}")
          }
        for (response in listOf(
          sendMessage(client, "/chat", token, Parameters.Empty),
          sendMessage(client, "/chat", token, repeated),
          unreadable,
        )) {
          assertNotice(response, HttpStatusCode.BadRequest)
          assertTrue(response.bodyAsText().contains(MESSAGE_NOT_SENT), "every malformed send reads the one sentence")
        }
      }
    } finally {
      chatLog.detachAppender(appender)
    }
    val logged = appender.list.map { it.formattedMessage }
    for (cause in listOf(MalformedSend.MISSING_FIELD, MalformedSend.REPEATED_FIELD, MalformedSend.UNREADABLE_BODY)) {
      assertTrue(logged.any { it.contains("[$cause]") }, "no log line names [$cause]: $logged")
    }
  }

  /** Test 32: `:service`'s length rule reaches the web as its own sentence, before any turn runs. */
  @Test
  fun `a message one character over the limit is a 400 notice carrying service's sentence and runs no turn`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Never sent."))) }
      val (_, token) = signedInStudent()
      val llmRequestsBefore = count("SELECT count(*) FROM llm_requests")

      val response = sendMessage(client, "/chat", token, message("x".repeat(CoachingService.MESSAGE_MAX_LENGTH + 1)))
      assertNotice(response, HttpStatusCode.BadRequest)
      assertTrue(
        response.bodyAsText().contains("Message exceeds ${CoachingService.MESSAGE_MAX_LENGTH} characters"),
        "the notice does not carry :service's sentence",
      )
      assertEquals(0, count("SELECT count(*) FROM convos"), "a refused send persists no conversation")
      assertEquals(0, count("SELECT count(*) FROM convo_requests"), "a refused send persists no turn")
      assertEquals(llmRequestsBefore, count("SELECT count(*) FROM llm_requests"), "a refused send makes no provider call")
    }
  }

  @Test
  fun `a spent allowance is a 402 notice naming the basis and the way on, never a figure`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(freeAllowanceUsd = "0.00")) }
      val (_, token) = signedInStudent()
      // `llm_requests` hangs off no user, so the reset leaves other suites' rows: count the change.
      val llmRequestsBefore = count("SELECT count(*) FROM llm_requests")

      val response = sendMessage(client, "/chat", token, message("Hello"))
      assertNotice(response, HttpStatusCode.PaymentRequired)
      val notice = Jsoup.parseBodyFragment(response.bodyAsText()).body()
      assertEquals(OUT_OF_ALLOWANCE_TITLE, notice.selectFirst("h2")?.text())
      assertTrue(notice.text().contains("You've used your free coaching."))
      assertTrue(notice.text().contains(OUT_OF_ALLOWANCE_NEXT_STEP))
      assertFalse(Regex("""\$|\d""").containsMatchIn(notice.text()), "a dollar figure or count reached the notice: ${notice.text()}")
      assertEquals(0, count("SELECT count(*) FROM convos"))
      assertEquals(llmRequestsBefore, count("SELECT count(*) FROM llm_requests"), "a refused turn makes no provider call")
    }
  }

  @Test
  fun `another student's, a deleted and a non-UUID conversation are a 404 notice`() {
    testApplication {
      val coachingService = coach(textReplay("A."), textReplay("B."))
      application { testPublicWebModule(coachingService = coachingService) }
      val (_, ownerToken) = signedInStudent()
      val (deleter, deleterToken) = signedInStudent()
      val (_, otherToken) = signedInStudent()

      val othersUrl = string(eventsOf(sendMessage(client, "/chat", ownerToken, message("Mine")).bodyAsText()).first(), "conversationUrl")
      val deletedUrl = string(eventsOf(sendMessage(client, "/chat", deleterToken, message("Gone")).bodyAsText()).first(), "conversationUrl")
      val deleterStudent = runBlocking { WebAuthTestSupport.studentService.getStudentForUser(deleter.id).getOrThrow()!! }
      runBlocking {
        coachingService.deleteConvo(deleterStudent.id, ConvoId(UUID.fromString(deletedUrl.removePrefix("/chat/"))))
      }
      val requestsBefore = count("SELECT count(*) FROM convo_requests")

      for ((path, token) in listOf(othersUrl to otherToken, deletedUrl to deleterToken, "/chat/not-a-uuid" to otherToken)) {
        val response = sendMessage(client, path, token, message("Again"))
        assertNotice(response, HttpStatusCode.NotFound)
        val body = response.bodyAsText()
        assertTrue(Jsoup.parseBodyFragment(body).text().contains(CONVERSATION_UNAVAILABLE), "[$path] $body")
      }
      assertEquals(requestsBefore, count("SELECT count(*) FROM convo_requests"), "a refused send persists nothing")
    }
  }

  private suspend fun assertNotice(
    response: HttpResponse,
    status: HttpStatusCode,
  ) {
    assertEquals(status, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertTrue(
      response.headers[ChatClientContract.noticeHeader] != null,
      "the notice is not marked as ours, so chat.ts would treat it as an unexpected answer",
    )
    assertFalse(response.bodyAsText().contains("event:"), "a refusal must never be a stream")
  }

  /** An element's structure and text, with the whitespace page pretty-printing adds removed. */
  private fun normalized(element: Element): String = element.outerHtml().replace(Regex(">\\s+<"), "><")
}
