package ed.unicoach.web

import ed.unicoach.auth.SsoLoginResult
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.User
import ed.unicoach.web.CollegeListTestSupport.getAs
import ed.unicoach.web.render.ID_TOKEN_FIELD
import ed.unicoach.web.render.NAME_FIELD
import ed.unicoach.web.render.SSO_FORM_ATTRIBUTE
import ed.unicoach.web.render.SsoDoor
import ed.unicoach.web.render.SsoRefusal
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Google and Apple sign-in on the web (RFC 275): `POST /sign-in/google` and
 * `POST /sign-in/apple`, through the real `AuthService.loginWithSso` and the
 * real database, with the `stub` verifier the test configuration selects. A
 * stub token is `stub:sub=…;email=…;email_verified=true[;name=…]`.
 *
 * The headline is the first test: an account made on the iPhone with "Sign in
 * with Apple" has no password, and the website must let it in — to the same
 * user, with the same data.
 */
class SsoSignInRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.module() = application { testPublicWebModule(ssoButtons = WebAuthTestSupport.bothSsoButtons) }

  private fun stubToken(
    sub: String,
    email: String,
    verified: Boolean = true,
    name: String? = null,
  ): String = "stub:sub=$sub;email=$email;email_verified=$verified" + (name?.let { ";name=$it" } ?: "")

  /** A provider door's POST, same-origin, redirects not followed. */
  private suspend fun ApplicationTestBuilder.postDoor(
    door: SsoDoor,
    form: Parameters,
    sessionToken: String? = null,
  ): HttpResponse = formPost(door.path, form, sessionToken = sessionToken, client = noFollowClient(), csrf = null)

  /**
   * A user made exactly as the iPhone makes one: `loginWithSso` through the
   * same [ed.unicoach.auth.AuthService] — `rest-server`'s `POST /auth/apple`
   * calls nothing else — so the identity row is the iPhone's, not a seed of it.
   */
  private fun iPhoneUser(
    provider: AuthProvider,
    sub: String,
    email: String,
  ): User =
    runBlocking {
      val result =
        WebAuthTestSupport.authService
          .loginWithSso(
            provider,
            stubToken(sub, email),
            "iPhone Name",
            null,
            WebAuthTestSupport.sessionConfig.expiration,
            "ios",
            "10.0.0.1",
          ).getOrThrow()
      assertIs<SsoLoginResult.Success>(result).user
    }

  @Test
  fun `an iPhone Apple user signs in on the web as the same user and sees their data`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()
      val user = iPhoneUser(AuthProvider.APPLE, "apple-1", email)
      val studentId = WebAuthTestSupport.seedStudent(user)
      val collegeName = "${CollegeListTestSupport.uniqueWord()} College"
      CollegeListTestSupport.addEntry(studentId, CollegeListTestSupport.seedCollege(collegeName))

      val response = postDoor(SsoDoor.APPLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("apple-1", email)) })

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/chat", response.headers[HttpHeaders.Location])
      val cookie = WebAuthTestSupport.tokenFromSetCookie(assertNotNull(response.headers[HttpHeaders.SetCookie]))
      val session = assertNotNull(WebAuthTestSupport.resolveSession(cookie), "the web cookie resolves no session")
      assertEquals(user.id, session.user.id, "the web signed the iPhone's Apple user in as a different user")
      assertEquals(
        listOf("apple", "apple"),
        WebAuthTestSupport.liveLoginMethods(user.id.value),
        "the iPhone's session and the web's, both Apple",
      )

      val colleges = getAs("/colleges", cookie)
      assertEquals(HttpStatusCode.OK, colleges.status)
      assertTrue(colleges.bodyAsText().contains(collegeName), "the web session does not see the iPhone user's college list")
    }

  @Test
  fun `a first Apple sign-in names the account with the posted name`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()

      val response =
        postDoor(
          SsoDoor.APPLE,
          Parameters.build {
            append(ID_TOKEN_FIELD, stubToken("apple-new", email))
            append(NAME_FIELD, "Ada Lovelace")
          },
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("Ada Lovelace", WebAuthTestSupport.storedUserName(email))
    }

  @Test
  fun `a first Apple sign-in with no name falls back to the email local part, never an empty name`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()

      postDoor(SsoDoor.APPLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("apple-noname", email)) })

      assertEquals(email.substringBefore('@'), WebAuthTestSupport.storedUserName(email))
    }

  @Test
  fun `a blank posted name falls through to the email local part`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()

      postDoor(
        SsoDoor.APPLE,
        Parameters.build {
          append(ID_TOKEN_FIELD, stubToken("apple-blank", email))
          append(NAME_FIELD, "   ")
        },
      )

      assertEquals(email.substringBefore('@'), WebAuthTestSupport.storedUserName(email))
    }

  @Test
  fun `a returning Apple user is not renamed by a posted name`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()
      iPhoneUser(AuthProvider.APPLE, "apple-returning", email)

      val response =
        postDoor(
          SsoDoor.APPLE,
          Parameters.build {
            append(ID_TOKEN_FIELD, stubToken("apple-returning", email))
            append(NAME_FIELD, "Someone Else")
          },
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("iPhone Name", WebAuthTestSupport.storedUserName(email))
    }

  @Test
  fun `Google sign-in creates an account named by the token, and a returning one gets back in`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()

      val first = postDoor(SsoDoor.GOOGLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("google-1", email, name = "Grace Hopper")) })
      assertEquals(HttpStatusCode.SeeOther, first.status)
      assertEquals("/chat", first.headers[HttpHeaders.Location])
      assertEquals("Grace Hopper", WebAuthTestSupport.storedUserName(email))

      val again = postDoor(SsoDoor.GOOGLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("google-1", email)) })
      assertEquals(HttpStatusCode.SeeOther, again.status)
      val userId =
        WebAuthTestSupport
          .resolveSession(
            WebAuthTestSupport.tokenFromSetCookie(again.headers[HttpHeaders.SetCookie]!!),
          )!!
          .user.id
      assertEquals(listOf("google", "google"), WebAuthTestSupport.liveLoginMethods(userId.value))
    }

  /** One refusal: its status, its sentence for [door], and no session made by it. */
  private suspend fun ApplicationTestBuilder.assertRefused(
    door: SsoDoor,
    form: Parameters,
    refusal: SsoRefusal,
  ): String {
    val sessionsBefore = WebAuthTestSupport.sessionRowCount()
    val response = postDoor(door, form)
    assertEquals(refusal.status, response.status, "[$refusal] answered the wrong status")
    val body = response.bodyAsText()
    assertTrue(proseOf(body).contains(refusal.sentence(door)), "[$refusal] does not render its sentence: [${proseOf(body)}]")
    assertTrue(body.contains("action=\"/sign-in\""), "[$refusal] did not re-render the sign-in page")
    assertEquals(sessionsBefore, WebAuthTestSupport.sessionRowCount(), "[$refusal] created a session")
    return body
  }

  @Test
  fun `a token the verifier rejects is refused with 401`() =
    testApplication {
      module()
      assertRefused(SsoDoor.GOOGLE, Parameters.build { append(ID_TOKEN_FIELD, "stub:invalid") }, SsoRefusal.TOKEN_REJECTED)
    }

  @Test
  fun `a post with no id_token is refused with 400`() =
    testApplication {
      module()
      assertRefused(SsoDoor.APPLE, Parameters.Empty, SsoRefusal.TOKEN_MISSING)
    }

  @Test
  fun `a provider account with an unverified email is refused with 403`() =
    testApplication {
      module()
      assertRefused(
        SsoDoor.GOOGLE,
        Parameters.build { append(ID_TOKEN_FIELD, stubToken("google-unverified", WebAuthTestSupport.uniqueEmail(), verified = false)) },
        SsoRefusal.EMAIL_NOT_VERIFIED,
      )
    }

  @Test
  fun `an unverified password account at the address blocks the link with 403 and writes no identity`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email, verified = false)

      assertRefused(
        SsoDoor.APPLE,
        Parameters.build { append(ID_TOKEN_FIELD, stubToken("apple-hijack", email)) },
        SsoRefusal.LINK_BLOCKED_UNVERIFIED_EMAIL,
      )
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM user_auth_identities"), "a blocked link wrote an identity row")
    }

  @Test
  fun `a closed account's identity is refused with 403`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()
      val user = iPhoneUser(AuthProvider.APPLE, "apple-closed", email)
      runBlocking {
        WebAuthTestSupport.database.withConnection { session ->
          val loaded = UsersDao.findById(session, user.id).getOrThrow()
          UsersDao.delete(session, user.id, loaded.version).getOrThrow()
        }
      }

      assertRefused(SsoDoor.APPLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("apple-closed", email)) }, SsoRefusal.ACCOUNT_CLOSED)
    }

  @Test
  fun `an unreachable provider is refused with 503`() =
    testApplication {
      module()
      assertRefused(SsoDoor.GOOGLE, Parameters.build { append(ID_TOKEN_FIELD, "stub:unavailable") }, SsoRefusal.UNAVAILABLE)
    }

  /**
   * Apple sends the name on a user's FIRST authorization only, so a retry's
   * token carries none: the refused page puts the posted name back in the
   * Apple form, and the retry sends it again.
   */
  @Test
  fun `a refused Apple post keeps its name for the retry`() =
    testApplication {
      module()
      val body =
        assertRefused(
          SsoDoor.APPLE,
          Parameters.build {
            append(ID_TOKEN_FIELD, "stub:unavailable")
            append(NAME_FIELD, "Ada Lovelace")
          },
          SsoRefusal.UNAVAILABLE,
        )

      val appleForm = assertNotNull(Jsoup.parse(body).selectFirst("form[$SSO_FORM_ATTRIBUTE=${SsoDoor.APPLE.segment}]"))
      assertEquals(SsoDoor.APPLE.path, appleForm.attr("action"))
      assertEquals("Ada Lovelace", appleForm.selectFirst("input[name=$NAME_FIELD]")?.attr("value"))
    }

  @Test
  fun `the same-origin guard covers the provider doors`() =
    testApplication {
      module()
      val response =
        client.post(SsoDoor.APPLE.path) {
          header(HttpHeaders.Origin, "https://attacker.example")
          setBody(FormDataContent(Parameters.build { append(ID_TOKEN_FIELD, stubToken("apple-csrf", WebAuthTestSupport.uniqueEmail())) }))
        }

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("403 Request blocked"), "the refusal is not the branded 403 page")
      assertEquals(0, WebAuthTestSupport.sessionRowCount(), "a cross-site post signed someone in")
    }

  /** Fixation, as `mintSession` handles it: the session a browser held before signing in is revoked by the sign-in. */
  @Test
  fun `a provider sign-in replaces the session the browser already held`() =
    testApplication {
      module()
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      val oldToken = WebAuthTestSupport.login(email)

      val response =
        postDoor(SsoDoor.GOOGLE, Parameters.build { append(ID_TOKEN_FIELD, stubToken("google-fixation", email)) }, sessionToken = oldToken)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals(null, WebAuthTestSupport.resolveSession(oldToken), "the old session still resolves after a provider sign-in")
      assertEquals(1, WebAuthTestSupport.liveSessionCount(user.id.value))
    }

  @Test
  fun `GET on a provider door is the site's 404`() =
    testApplication {
      module()
      for (door in SsoDoor.entries) {
        val response = client.get(door.path)
        assertEquals(HttpStatusCode.NotFound, response.status, "[${door.path}] answered a GET")
        assertTrue(response.bodyAsText().contains("class=\"site-header\""), "[${door.path}] is not the branded 404")
      }
    }
}
