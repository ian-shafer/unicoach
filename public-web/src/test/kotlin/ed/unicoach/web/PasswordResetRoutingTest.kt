package ed.unicoach.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.models.User
import ed.unicoach.web.common.http.CACHE_CONTROL_NO_STORE
import ed.unicoach.web.common.http.REFERRER_POLICY_HEADER
import ed.unicoach.web.common.http.REFERRER_POLICY_SAME_ORIGIN
import ed.unicoach.web.common.http.ROBOTS_NOINDEX
import ed.unicoach.web.common.http.X_ROBOTS_TAG_HEADER
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.PASSWORD_FIELD
import ed.unicoach.web.render.TOKEN_FIELD
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Forgot your password?" through the real routes, service and test database (RFC 276). */
class PasswordResetRoutingTest {
  private companion object {
    const val NEW_PASSWORD = "BrandNew1pass"
    const val FORGOT = "/forgot-password"
    const val RESET = "/reset-password"
  }

  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private suspend fun ApplicationTestBuilder.requestLink(email: String): HttpResponse =
    formPost(FORGOT, parameters { append(EMAIL_FIELD, email) })

  /** The raw token from the one job enqueued for [email]: the only place it exists but the mail. */
  private fun mailedToken(email: String): String =
    runBlocking {
      WebAuthTestSupport.database.withConnection { session ->
        RawSql
          .list(
            session,
            "SELECT payload->'context'->>'resetToken' AS token FROM jobs " +
              "WHERE job_type = 'SEND_EMAIL' AND payload->>'to' = ?",
            email,
          ) { it.getString("token") }
          .single()
      }
    }

  private suspend fun ApplicationTestBuilder.issuedToken(email: String): String {
    requestLink(email)
    return mailedToken(email)
  }

  private suspend fun ApplicationTestBuilder.postReset(
    token: String,
    password: String = NEW_PASSWORD,
    sessionToken: String? = null,
  ): HttpResponse =
    formPost(
      RESET,
      parameters {
        append(TOKEN_FIELD, token)
        append(PASSWORD_FIELD, password)
      },
      client = noFollowClient(),
      // The door is signed-out: a stale cookie rides along without a CSRF token.
      csrf = null,
      sessionToken = sessionToken,
    )

  private fun hasNoLiveToken(user: User): Boolean =
    WebAuthTestSupport.count(
      "SELECT count(*) FROM account_tokens WHERE consumed_at IS NULL AND user_id = '${user.id.value}'",
    ) == 0

  private fun assertSays(
    page: Document,
    sentence: String,
  ) {
    assertTrue(page.select("p").any { it.text() == sentence }, "the page does not say [$sentence]: $page")
  }

  private fun assertDeadPage(
    response: HttpResponse,
    body: String,
    status: HttpStatusCode,
    heading: String,
    sentence: String,
  ) {
    assertEquals(status, response.status)
    val page = Jsoup.parse(body)
    assertEquals(heading, page.selectFirst("h1")?.text())
    assertSays(page, sentence)
    assertNotNull(page.selectFirst("a[href=$FORGOT]"), "the page strands its reader: $body")
  }

  @Test
  fun `the sign-in page links to the request form`() {
    testApplication {
      application { testPublicWebModule() }

      val page = Jsoup.parse(client.get("/sign-in").bodyAsText())

      assertEquals("Forgot your password?", page.selectFirst("a[href=$FORGOT]")?.text())
    }
  }

  @Test
  fun `the request form renders its words, control and way back`() {
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(FORGOT)

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals("Forgot your password?", page.selectFirst("h1")?.text())
      assertSays(page, "Enter your email address and we'll send you a link to choose a new one.")
      assertNotNull(page.selectFirst("form[action=$FORGOT] input[name=$EMAIL_FIELD]"))
      assertEquals("Send link", page.selectFirst("form[action=$FORGOT] button")?.text())
      assertEquals("Back to sign in", page.selectFirst("a[href=/sign-in]:contains(Back)")?.text())
    }
  }

  @Test
  fun `the whole door ends signed in, with the old session dead and the new password live`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedStudentUser(email)
      val oldSession = WebAuthTestSupport.login(email)
      val token = issuedToken(email)

      val form = Jsoup.parse(client.get("$RESET?$TOKEN_FIELD=$token").bodyAsText())
      assertEquals("Choose a new password", form.selectFirst("h1")?.text())
      assertEquals(token, form.selectFirst("form[action=$RESET] input[type=hidden][name=$TOKEN_FIELD]")?.attr("value"))
      assertEquals("new-password", form.selectFirst("input[name=$PASSWORD_FIELD]")?.attr("autocomplete"))
      assertEquals("Save new password", form.selectFirst("form[action=$RESET] button")?.text())

      val done = postReset(token)
      assertEquals(HttpStatusCode.SeeOther, done.status)
      assertEquals("/chat", done.headers[HttpHeaders.Location])
      val newSession = WebAuthTestSupport.tokenFromSetCookie(done.headers[HttpHeaders.SetCookie].orEmpty())
      assertEquals(
        email,
        WebAuthTestSupport
          .resolveSession(newSession)
          ?.user
          ?.email
          ?.value,
      )

      val withOld =
        noFollowClient().get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(oldSession)) }
      assertEquals("/sign-in", withOld.headers[HttpHeaders.Location], "the old session still reaches /account")
      assertNull(WebAuthTestSupport.resolveSession(oldSession))

      assertEquals(HttpStatusCode.SeeOther, formPost("/sign-out", sessionToken = newSession, client = noFollowClient()).status)
      val signIn = { password: String ->
        parameters {
          append(EMAIL_FIELD, email)
          append(PASSWORD_FIELD, password)
        }
      }
      assertEquals(HttpStatusCode.Unauthorized, formPost("/sign-in", signIn(WebAuthTestSupport.DEFAULT_PASSWORD)).status)
      assertEquals(HttpStatusCode.SeeOther, formPost("/sign-in", signIn(NEW_PASSWORD), client = noFollowClient()).status)
    }
  }

  @Test
  fun `a stale cookie on the reset post does not stop the sign-in`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedStudentUser(email)
      val stale = WebAuthTestSupport.login(email)
      val token = issuedToken(email)

      val done = postReset(token, sessionToken = stale)

      assertEquals(HttpStatusCode.SeeOther, done.status)
      assertNull(WebAuthTestSupport.resolveSession(stale))
    }
  }

  @Test
  fun `a known and an unknown address get the same status, headers and bytes`() {
    testApplication {
      application { testPublicWebModule() }
      val known = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(known)

      val forKnown = requestLink(known)
      val forUnknown = requestLink(WebAuthTestSupport.uniqueEmail())

      assertEquals(HttpStatusCode.OK, forKnown.status)
      assertEquals(forKnown.status, forUnknown.status)
      assertEquals(
        forKnown.headers.entries().associate { it.key to it.value },
        forUnknown.headers.entries().associate { it.key to it.value },
      )
      val body = forKnown.bodyAsText()
      assertEquals(body, forUnknown.bodyAsText())
      val page = Jsoup.parse(body)
      assertEquals("Check your email", page.selectFirst("h1")?.text())
      assertSays(
        page,
        "If there's a Unicoach account for that address, we've sent it a link to choose a new password. " +
          "If nothing arrives, check your spam folder or try again in a few minutes.",
      )
      assertEquals(1, WebAuthTestSupport.jobCount("SEND_EMAIL"), "only the known address is mailed")
    }
  }

  @Test
  fun `a malformed address is a 400 with the typed value kept`() {
    testApplication {
      application { testPublicWebModule() }

      val response = requestLink("not-an-address")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("not-an-address", Jsoup.parse(response.bodyAsText()).selectFirst("input[name=$EMAIL_FIELD]")?.attr("value"))
    }
  }

  @Test
  fun `a request post with no single email field is a 400`() {
    testApplication {
      application { testPublicWebModule() }

      assertEquals(HttpStatusCode.BadRequest, formPost(FORGOT).status)
    }
  }

  @Test
  fun `GET does not spend the token`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      val token = issuedToken(email)

      repeat(2) { assertEquals(HttpStatusCode.OK, client.get("$RESET?$TOKEN_FIELD=$token").status) }

      assertEquals(HttpStatusCode.SeeOther, postReset(token).status)
    }
  }

  @Test
  fun `an unknown link renders the not-valid page`() {
    testApplication {
      application { testPublicWebModule() }

      val get = client.get("$RESET?$TOKEN_FIELD=never-issued")
      assertDeadPage(
        get,
        get.bodyAsText(),
        HttpStatusCode.BadRequest,
        "Link not valid",
        "This link isn't valid. You can ask for a new one.",
      )
      val post = postReset("never-issued")
      assertDeadPage(
        post,
        post.bodyAsText(),
        HttpStatusCode.BadRequest,
        "Link not valid",
        "This link isn't valid. You can ask for a new one.",
      )
    }
  }

  @Test
  fun `an expired link renders the expired page`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      val token = issuedToken(email)
      runBlocking {
        WebAuthTestSupport.database.withConnection { session ->
          RawSql.execute(session, "UPDATE account_tokens SET expires_at = NOW() - interval '1 minute'")
        }
      }

      val get = client.get("$RESET?$TOKEN_FIELD=$token")
      assertDeadPage(get, get.bodyAsText(), HttpStatusCode.Gone, "Link expired", "This link has expired. You can ask for a new one.")
      val post = postReset(token)
      assertDeadPage(post, post.bodyAsText(), HttpStatusCode.Gone, "Link expired", "This link has expired. You can ask for a new one.")
    }
  }

  @Test
  fun `a spent link renders the already-used page, offering sign-in too`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      val token = issuedToken(email)
      postReset(token)

      val post = postReset(token, "AnotherPass2")
      val body = post.bodyAsText()

      assertDeadPage(
        post,
        body,
        HttpStatusCode.Gone,
        "Link already used",
        "This link has already been used. You can ask for a new one, or sign in.",
      )
      assertNotNull(Jsoup.parse(body).selectFirst("a[href=/sign-in]:contains(sign in)"))
    }
  }

  @Test
  fun `a weak password re-renders at 400 with its violations, and a corrected post succeeds`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedStudentUser(email)
      val token = issuedToken(email)

      val weak = postReset(token, "short")

      assertEquals(HttpStatusCode.BadRequest, weak.status)
      val body = weak.bodyAsText()
      assertTrue(body.contains("Password must be at least 8 characters long"), "no violation shown: $body")
      assertTrue(body.contains("Password must contain at least 1 digit"), "not every violation shown: $body")
      assertEquals(token, Jsoup.parse(body).selectFirst("input[type=hidden][name=$TOKEN_FIELD]")?.attr("value"))
      assertFalse(body.contains("value=\"short\""), "the password was rendered back")
      assertFalse(hasNoLiveToken(user), "a weak password spent the token")

      assertEquals(HttpStatusCode.SeeOther, postReset(token).status)
    }
  }

  @Test
  fun `two token fields read as not valid`() {
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      val token = issuedToken(email)

      val get = client.get("$RESET?$TOKEN_FIELD=$token&$TOKEN_FIELD=$token")
      assertEquals(HttpStatusCode.BadRequest, get.status)
      val post =
        formPost(
          RESET,
          parameters {
            append(TOKEN_FIELD, token)
            append(TOKEN_FIELD, token)
            append(PASSWORD_FIELD, NEW_PASSWORD)
          },
        )

      assertEquals(HttpStatusCode.BadRequest, post.status)
      assertFalse(hasNoLiveToken(user), "a repeated token field spent the token")
    }
  }

  @Test
  fun `the reset page carries the link-holder headers and keeps the token out of the request log`() {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    val secret = "live-secret-token"
    try {
      testApplication {
        application { testPublicWebModule() }

        val response = client.get("$RESET?$TOKEN_FIELD=$secret")

        assertEquals(CACHE_CONTROL_NO_STORE, response.headers[HttpHeaders.CacheControl])
        assertEquals(ROBOTS_NOINDEX, response.headers[X_ROBOTS_TAG_HEADER])
        assertEquals(REFERRER_POLICY_SAME_ORIGIN, response.headers[REFERRER_POLICY_HEADER])
      }
    } finally {
      root.detachAppender(appender)
    }

    val lines = appender.list.map { it.formattedMessage }
    assertTrue(lines.any { it.contains(RESET) }, "the request was never logged: $lines")
    assertTrue(lines.none { it.contains(secret) }, "the token reached the log: ${lines.filter { it.contains(secret) }}")
  }
}
