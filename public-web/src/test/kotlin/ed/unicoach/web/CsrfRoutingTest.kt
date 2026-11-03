package ed.unicoach.web

import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.captureWarns
import ed.unicoach.util.TokenGenerator
import ed.unicoach.web.ChatTestSupport.coach
import ed.unicoach.web.ChatTestSupport.eventsOf
import ed.unicoach.web.ChatTestSupport.message
import ed.unicoach.web.ChatTestSupport.sendMessage
import ed.unicoach.web.ChatTestSupport.signedInStudent
import ed.unicoach.web.ChatTestSupport.textReplay
import ed.unicoach.web.auth.CsrfRefusal
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.EMAIL_FIELD
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CSRF synchronizer tokens (brief 0013 D22, RFC 265), over the real database and
 * the real auth stack.
 *
 * Every session-bearing POST is refused with the branded 403 when it presents
 * no token or the wrong one, and does its work when it presents the session's;
 * each refusal is checked for having done NOTHING, not only for its status.
 * Chat presents the token in the header its client sends, every other form in
 * the hidden field its page renders. POSTs with no session carry none.
 */
class CsrfRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  /** What a forged request sends: a well-formed token that is not this session's. */
  private val wrongToken = "A".repeat(43)

  @Test
  fun `a session with no CSRF token is no caller and is sent to sign-in`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      val token = WebAuthTestSupport.login(email)
      // A row minted before RFC 265: no metadata at all.
      clearSessionMetadata()

      val response = noFollowClient().get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/sign-in", response.headers[HttpHeaders.Location])
    }
  }

  /**
   * Every session is minted with a token of exactly the generator's size, so a
   * stored token of any other size is one no code minted, and the session that
   * holds it is no caller — the same answer as a session holding none.
   */
  @Test
  fun `a session whose stored token is not the minted size is no caller`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      val token = WebAuthTestSupport.login(email)

      for (size in listOf(1, TokenGenerator.TOKEN_BYTES - 1, TokenGenerator.TOKEN_BYTES + 1)) {
        storeSessionMetadata("""{"csrfToken":"${Base64.getEncoder().encodeToString(ByteArray(size) { 9 })}"}""")

        val response = noFollowClient().get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

        assertEquals(HttpStatusCode.Found, response.status, "a [$size]-byte token resolved a caller")
        assertEquals("/sign-in", response.headers[HttpHeaders.Location])
      }
    }
  }

  /**
   * The refusal's WARN says WHY — a request that never carried the form and a
   * stale page's wrong token are different problems — and never carries a
   * token, the session's or the one presented.
   */
  @Test
  fun `a refusal is logged with its reason and without any token`() {
    lateinit var sessionCsrf: String
    val warns =
      captureWarns {
        testApplication {
          application { testPublicWebModule() }
          val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
          val token = WebAuthTestSupport.login(user.email.value)
          sessionCsrf = WebAuthTestSupport.csrfTokenFor(token)!!

          formPost("/sign-out", sessionToken = token, client = noFollowClient(), csrf = null)
          formPost("/sign-out", sessionToken = token, client = noFollowClient(), csrf = wrongToken)
          noFollowClient().post("/sign-out") {
            header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
            header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
            setBody(TextContent("{}", ContentType.Application.Json))
          }
        }
      }

    val refusals = warns.filter { it.startsWith("CSRF token refused") }
    assertEquals(
      listOf(CsrfRefusal.FIELD_ABSENT_OR_REPEATED, CsrfRefusal.MISMATCH, CsrfRefusal.NOT_A_FORM).map { it.name },
      refusals.map { it.substringAfterLast(": ") },
      "the refusals do not each name their reason: $refusals",
    )
    for (line in refusals) {
      assertFalse(line.contains(sessionCsrf) || line.contains(wrongToken), "a refusal logged a token: [$line]")
    }
  }

  @Test
  fun `sign-out is refused without the session's token and signs out with it`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      assertRefusedWithoutToken { csrf -> formPost("/sign-out", sessionToken = token, client = noFollowClient(), csrf = csrf) }
      assertEquals(1, WebAuthTestSupport.liveSessionCount(user.id.value), "a refused sign-out revoked the session")

      val accepted = formPost("/sign-out", sessionToken = token, client = noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, accepted.status)
      assertEquals(0, WebAuthTestSupport.liveSessionCount(user.id.value))
    }
  }

  @Test
  fun `resend is refused without the session's token and sends with it`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email, verified = false)
      val token = WebAuthTestSupport.login(email)
      val before = WebAuthTestSupport.jobCount("SEND_EMAIL")

      assertRefusedWithoutToken { csrf -> formPost("/verify-email/resend", sessionToken = token, client = noFollowClient(), csrf = csrf) }
      assertEquals(before, WebAuthTestSupport.jobCount("SEND_EMAIL"), "a refused resend queued an email")

      val accepted = formPost("/verify-email/resend", sessionToken = token, client = noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, accepted.status)
      assertEquals(before + 1, WebAuthTestSupport.jobCount("SEND_EMAIL"))
    }
  }

  @Test
  fun `change email is refused without the session's token and changes with it`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedStudentUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)
      val newEmail = WebAuthTestSupport.uniqueEmail()
      val form = parameters { append(EMAIL_FIELD, newEmail) }

      assertRefusedWithoutToken { csrf -> formPost("/account/email", form, sessionToken = token, client = noFollowClient(), csrf = csrf) }
      assertEquals(user.email, WebAuthTestSupport.resolveSession(token)!!.user.email, "a refused change changed the address")

      val accepted = formPost("/account/email", form, sessionToken = token, client = noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, accepted.status)
      assertEquals(
        newEmail,
        WebAuthTestSupport
          .resolveSession(token)!!
          .user.email.value,
      )
    }
  }

  @Test
  fun `chat start and turn are refused without the token header and stream with it`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach(textReplay("Hello."), textReplay("Again."))) }
      val (_, token) = signedInStudent()
      val client = noFollowClient()

      assertRefusedWithoutToken { csrf -> sendMessage(client, "/chat", token, message("Hi"), csrf = csrf) }
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM convos"), "a refused start began a conversation")

      val started = sendMessage(client, "/chat", token, message("Hi"))
      assertEquals(HttpStatusCode.OK, started.status)
      val conversation =
        Json
          .parseToJsonElement(eventsOf(started.bodyAsText()).first { it.name == "started" }.data)
          .jsonObject
          .getValue("conversationUrl")
          .jsonPrimitive.content
      val turns = WebAuthTestSupport.count("SELECT count(*) FROM convo_requests")

      assertRefusedWithoutToken { csrf -> sendMessage(client, conversation, token, message("More"), csrf = csrf) }
      assertEquals(turns, WebAuthTestSupport.count("SELECT count(*) FROM convo_requests"), "a refused turn was recorded")

      val continued = sendMessage(client, conversation, token, message("More"))
      assertEquals(HttpStatusCode.OK, continued.status)
      assertTrue(continued.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
    }
  }

  @Test
  fun `sign-in and registration need no token`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)

      val signedIn =
        formPost(
          "/sign-in",
          parameters {
            append(EMAIL_FIELD, email)
            append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
          },
          client = noFollowClient(),
        )
      val registered =
        formPost(
          "/register",
          parameters {
            append("name", "New Family")
            append(EMAIL_FIELD, WebAuthTestSupport.uniqueEmail())
            append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
          },
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, signedIn.status)
      assertEquals(HttpStatusCode.SeeOther, registered.status)
    }
  }

  /**
   * Every signed-in page that renders a form renders the session's token in
   * each of its POST forms — read off the served page, as the browser gets it.
   */
  @Test
  fun `every form on a signed-in page carries the session's token`() {
    testApplication {
      application { testPublicWebModule(coachingService = coach()) }
      val (_, student) = signedInStudent()
      val unverifiedEmail = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(unverifiedEmail, verified = false)
      val unverified = WebAuthTestSupport.login(unverifiedEmail)
      val noStudent = WebAuthTestSupport.login(WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail()).email.value)

      // The header's sign-out, change email, the hold screen's resend, the chat
      // composer, and the header alone on the two pages that have no form of their own.
      val pages =
        listOf(
          "/account" to student,
          "/account/email" to student,
          "/chat" to student,
          "/verify-email" to unverified,
          "/get-started" to noStudent,
        )
      for ((path, token) in pages) {
        val response = noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
        assertEquals(HttpStatusCode.OK, response.status, "[$path] did not render")
        val forms = Jsoup.parse(response.bodyAsText()).select("form[method=post]")
        assertTrue(forms.isNotEmpty(), "[$path] renders no form, so it proves nothing")
        val csrf = WebAuthTestSupport.csrfTokenFor(token)
        for (form in forms) {
          val field =
            assertNotNull(form.selectFirst("input[type=hidden][name=$CSRF_FIELD]"), "[$path] form [${form.attr("action")}] has no token")
          assertEquals(csrf, field.attr("value"), "[$path] form [${form.attr("action")}] carries another token")
        }
      }
    }
  }

  /**
   * [post], with no token and then with a wrong one: both are the branded 403.
   * [post] is handed the token to present, `null` meaning none.
   */
  private suspend fun ApplicationTestBuilder.assertRefusedWithoutToken(post: suspend ApplicationTestBuilder.(String?) -> HttpResponse) {
    for (presented in listOf(null, wrongToken)) {
      val response = post(presented)
      assertEquals(HttpStatusCode.Forbidden, response.status, "token [${presented ?: "absent"}] was not refused")
      assertTrue(response.bodyAsText().contains("403 Request blocked"), "token [${presented ?: "absent"}] did not get the branded 403")
    }
  }

  /** Overwrites every session's metadata with [json], as a row no codec wrote. */
  private fun storeSessionMetadata(json: String) {
    WebAuthTestSupport.database.createRawConnection().use { connection ->
      RawSql.execute(connection, "UPDATE sessions SET metadata = ?::jsonb, version = version + 1", json)
    }
  }

  private fun clearSessionMetadata() {
    WebAuthTestSupport.database.createRawConnection().use { connection ->
      RawSql.execute(connection, "UPDATE sessions SET metadata = NULL, version = version + 1")
    }
  }
}
