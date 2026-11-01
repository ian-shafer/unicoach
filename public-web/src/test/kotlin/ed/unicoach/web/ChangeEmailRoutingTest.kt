package ed.unicoach.web

import ed.unicoach.auth.MetadataWrite
import ed.unicoach.db.dao.SessionMetadataFixtures
import ed.unicoach.db.session.ChangeEmailDraft
import ed.unicoach.db.session.FormDrafts
import ed.unicoach.web.render.DRAFT_NOT_KEPT_NOTICE
import ed.unicoach.web.render.DRAFT_NOT_SAVED_NOTICE
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.FORM_ERROR_CLASS
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
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Changing the account's email address (RFC 224 D-E), and the draft rail's
 * first user (RFC 265 D-C).
 *
 * A session exists here, so brief decision D12 reaches this form: a refused
 * address is kept in the session as a draft and comes back on the next GET,
 * a successful change clears it, and an address too long to keep says so
 * without losing a character on screen. Every assertion about the draft reads
 * the session row the production resolver reads, never the page alone.
 */
class ChangeEmailRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `the form names the current address and warns that verification is cleared`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val body = client.get("/account/email") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText()

      assertTrue(body.contains("Change your email address"), "missing the heading")
      assertTrue(body.contains(email), "the form does not name the address it is about to replace")
      assertTrue(body.contains("verification email"), "the form does not warn that the new address must be verified")
      assertTrue(body.contains("action=\"/account/email\""), "the form does not post to itself")
    }

  /**
   * The family must actually SEE the confirmation.
   *
   * Changing the address clears verification in `:service`, so `/account` is the
   * one page the gate then refuses — a `303` to `/account?notice=...` would be
   * bounced to `/verify-email` with the query string dropped, and the
   * confirmation would be unreachable rather than merely unseen. So the
   * assertion is not about the first hop: it follows the redirect and reads the
   * rendered sentence off the page the family lands on.
   */
  @Test
  fun `a valid change lands the family on the hold screen with the confirmation rendered`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val newEmail = WebAuthTestSupport.uniqueEmail()

      val response = changeEmail(token, newEmail)

      assertEquals(HttpStatusCode.SeeOther, response.status, "a successful POST must answer 303")
      val location = response.headers[HttpHeaders.Location].orEmpty()
      assertTrue(
        location.startsWith("/verify-email?"),
        "the redirect does not go to the page the gate will actually let them reach: [$location]",
      )

      val landed = noFollowClient().get(location) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
      assertEquals(HttpStatusCode.OK, landed.status, "the confirmation target is itself a redirect: [$location]")
      val body = landed.bodyAsText()
      assertTrue(
        body.contains("Your email address has been changed"),
        "the confirmation was never rendered to the family: [$body]",
      )
      assertTrue(body.contains(newEmail), "the hold screen does not name the NEW address the verification link went to")
    }

  @Test
  fun `an unparseable address re-renders the form at 400 with the service's own message`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = changeEmail(token, "not-an-address")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("value=\"not-an-address\""), "the typed value was not re-rendered: [$body]")
      assertTrue(body.contains("Email must be of the form"), "the message is not the one :service returned: [$body]")
    }

  @Test
  fun `an address already in use is refused at 409`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val taken = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      WebAuthTestSupport.seedUser(taken)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = changeEmail(token, taken)

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(response.bodyAsText().contains("cannot be used"))
    }

  @Test
  fun `a refused address is kept as a draft and a later GET shows it with no error`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val taken = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      WebAuthTestSupport.seedUser(taken)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      // Both of :service's refusals keep the typing: an invalid address and a taken one.
      for (refused in listOf("not-an-address", taken)) {
        changeEmail(token, refused)

        assertEquals(refused, draftedEmail(token), "the refused address was not kept in the session")
        val page = getForm(token)
        assertEquals(HttpStatusCode.OK, page.status, "a drafted form is an ordinary form, not a refusal")
        val body = page.bodyAsText()
        assertTrue(body.contains("value=\"$refused\""), "the GET did not render the draft back: [$body]")
        assertFalse(body.contains(FORM_ERROR_CLASS), "the draft brought its error back with it: [$body]")
      }
    }
  }

  @Test
  fun `a refused POST is one session UPDATE and a success with no draft is none`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val minted = sessionVersion(token)

      changeEmail(token, "not-an-address")
      assertEquals(minted + 1, sessionVersion(token), "a refused POST saves its draft in exactly one UPDATE")

      val other = WebAuthTestSupport.login(WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail()).email.value)
      val otherMinted = sessionVersion(other)
      changeEmail(other, WebAuthTestSupport.uniqueEmail())
      assertEquals(otherMinted, sessionVersion(other), "a success with no draft to clear writes nothing")
    }
  }

  @Test
  fun `a successful change clears the draft`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      changeEmail(token, "not-an-address")
      assertEquals("not-an-address", draftedEmail(token))

      val response = changeEmail(token, WebAuthTestSupport.uniqueEmail())

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertNull(draftedEmail(token), "the draft outlived the submission that succeeded")
      assertFalse(
        WebAuthTestSupport
          .resolveSession(token)!!
          .session.metadata
          .hasDrafts(),
        "an empty drafts message was left behind",
      )
    }
  }

  @Test
  fun `an address too long to keep renders the failed page with the address and the notice, and stores nothing`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val huge = SessionMetadataFixtures.textPastTheCap()
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val response = changeEmail(token, huge)

      assertEquals(HttpStatusCode.BadRequest, response.status, "the failed POST's own status, as without a draft")
      val body = response.bodyAsText()
      assertTrue(body.contains("value=\"$huge\""), "the family's typing is not still on the page")
      assertTrue(body.contains(DRAFT_NOT_KEPT_NOTICE), "no overflow notice")
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "an overflow wrote something")
    }
  }

  /**
   * A NUL is text a form can post and the session's `jsonb` cannot hold. It is
   * the family's input, not a server fault: the refused form still renders
   * with its own status, saying the typing was not kept, and nothing is stored.
   */
  @Test
  fun `an address the session cannot store renders the failed page with the not-saved notice, and stores nothing`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      // No `@`, so `:service` refuses it as an address before any write of its own.
      val response = changeEmail(token, "not-an-address\u0000")

      assertEquals(HttpStatusCode.BadRequest, response.status, "the failed POST's own status, not a fault page")
      assertTrue(response.bodyAsText().contains(DRAFT_NOT_SAVED_NOTICE), "no not-saved notice")
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "unstorable text wrote something")
    }
  }

  /**
   * The draft is a side effect of the POST, never its answer. With the
   * metadata writer's database gone, a refused address still renders the
   * refused form with everything typed in it (and says it was not kept), and a
   * change `:service` committed is still reported as the change it was.
   */
  @Test
  fun `a draft write that faults still answers the POST with the page it earned`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule(sessionMetadataService = WebAuthTestSupport.unreachableSessionMetadataService()) }
      val token = WebAuthTestSupport.login(email)
      seedDraft(token, "kept-before@example.com")

      val refused = changeEmail(token, "not-an-address")

      assertEquals(HttpStatusCode.BadRequest, refused.status, "a failed draft save replaced the refused form")
      val body = refused.bodyAsText()
      assertTrue(body.contains("value=\"not-an-address\""), "the family's typing is not still on the page")
      assertTrue(body.contains(DRAFT_NOT_SAVED_NOTICE), "no not-saved notice")

      val newEmail = WebAuthTestSupport.uniqueEmail()
      val changed = changeEmail(token, newEmail)

      assertEquals(HttpStatusCode.SeeOther, changed.status, "a failed draft clear turned a committed change into a fault")
      assertEquals(
        newEmail,
        WebAuthTestSupport
          .resolveSession(token)!!
          .user.email.value,
      )
    }
  }

  @Test
  fun `a GET with no draft renders the ordinary empty form`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = getForm(token)

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(
        Regex("""<input type="email" name="$EMAIL_FIELD"(?![^>]*\bvalue=)""").containsMatchIn(body),
        "the empty form carries a value: [$body]",
      )
      assertFalse(body.contains(DRAFT_NOT_KEPT_NOTICE), "a fresh form carries the overflow notice")
    }
  }

  @Test
  fun `a body that is not this form saves nothing`() {
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val absent = formPost("/account/email", sessionToken = token)
      val repeated =
        formPost(
          "/account/email",
          parameters {
            append(EMAIL_FIELD, "one@example.com")
            append(EMAIL_FIELD, "two@example.com")
          },
          sessionToken = token,
        )

      assertEquals(HttpStatusCode.BadRequest, absent.status)
      assertEquals(HttpStatusCode.BadRequest, repeated.status)
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a request that is not the form wrote a draft")
    }
  }

  /** The address the session behind [token] holds as the change-email draft, or `null`. */
  private fun draftedEmail(token: String): String? {
    val drafts =
      WebAuthTestSupport
        .resolveSession(token)!!
        .session.metadata.drafts
    return if (drafts.hasChangeEmail()) drafts.changeEmail.newEmail else null
  }

  /** Leaves [newEmail] as the change-email draft of the session behind [token], through the production writer. */
  private fun seedDraft(
    token: String,
    newEmail: String,
  ) {
    val seen = WebAuthTestSupport.resolveSession(token)!!.session
    val outcome =
      runBlocking {
        WebAuthTestSupport.sessionMetadataService
          .update(seen) { stored ->
            stored
              .toBuilder()
              .setDrafts(FormDrafts.newBuilder().setChangeEmail(ChangeEmailDraft.newBuilder().setNewEmail(newEmail)))
              .build()
          }.getOrThrow()
      }
    assertIs<MetadataWrite.Written>(outcome, "the draft was not seeded")
  }

  /** The session row's version: each UPDATE that reached it moved it by exactly one. */
  private fun sessionVersion(token: String): Int = WebAuthTestSupport.resolveSession(token)!!.session.version

  private suspend fun ApplicationTestBuilder.getForm(token: String): HttpResponse =
    client.get("/account/email") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

  private suspend fun ApplicationTestBuilder.changeEmail(
    token: String,
    newEmail: String,
  ): HttpResponse =
    formPost(
      "/account/email",
      parameters { append(EMAIL_FIELD, newEmail) },
      sessionToken = token,
    )
}
