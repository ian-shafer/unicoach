package ed.unicoach.web

import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY
import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY_HEADER
import ed.unicoach.web.common.http.REFERRER_POLICY_HEADER
import ed.unicoach.web.common.http.REFERRER_POLICY_SAME_ORIGIN
import ed.unicoach.web.common.http.REFERRER_POLICY_STRICT_ORIGIN_WHEN_CROSS_ORIGIN
import ed.unicoach.web.render.APPLE_SDK_URL
import ed.unicoach.web.render.CONTINUE_WITH_APPLE
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.GOOGLE_SDK_URL
import ed.unicoach.web.render.PASSWORD_FIELD
import ed.unicoach.web.render.SSO_APPLE_ATTRIBUTE
import ed.unicoach.web.render.SSO_DIVIDER
import ed.unicoach.web.render.SSO_GOOGLE_ATTRIBUTE
import ed.unicoach.web.render.SsoButtons
import ed.unicoach.web.render.SsoDoor
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
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sign in and sign out on the web (RFC 224).
 *
 * The suite that matters most here is the FAILURE one. `AuthService.login`
 * returns four distinct failures and the web must not distinguish them: the two
 * halves of "no such user" and "wrong password" are a list of who has an
 * account, and a form that answers them differently hands that list to anyone
 * who asks. So the four responses are compared to EACH OTHER rather than each
 * to an expected string — an edit that gave one branch its own message would
 * pass a per-branch assertion and fail this one.
 */
class SignInRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `GET sign-in renders the form with both controls and the door back to register`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/sign-in")

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "missing the sign-in heading")
      assertTrue(body.contains("action=\"/sign-in\""), "the form does not post to /sign-in")
      assertTrue(body.contains("method=\"post\""), "the form is not a POST")
      assertTrue(body.contains("name=\"email\""), "no email control")
      assertTrue(body.contains("name=\"password\""), "no password control")
      assertTrue(body.contains("href=\"/register\""), "no way to reach registration from the sign-in form")
      assertTrue(body.contains("class=\"site-header\""), "the form does not render in the site shell")
    }

  @Test
  fun `a correct password sets the session cookie and redirects to the chat`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val response = signIn(email, WebAuthTestSupport.DEFAULT_PASSWORD)

      assertEquals(HttpStatusCode.SeeOther, response.status, "a successful POST must answer 303, not 200 or 302")
      // The signed-in root is the chat, as the iOS app opens on it (RFC 262).
      assertEquals("/chat", response.headers[HttpHeaders.Location])
      assertTrue(
        response.headers[HttpHeaders.SetCookie].orEmpty().startsWith(WebAuthTestSupport.sessionConfig.cookieName),
        "no session cookie was set",
      )
    }

  /**
   * All four `LoginResult` failures, compared to one another.
   *
   * `InvalidEmail` (an unparseable address), `UserNotFound`, `PasswordNotSet`
   * (an SSO-only account) and `PasswordMismatch` are four different facts about
   * the account, and the response must carry none of them.
   */
  @Test
  fun `the four login failure branches are indistinguishable in status and body`() =
    testApplication {
      val known = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(known)
      val ssoOnly = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedPasswordlessUser(ssoOnly)
      application { testPublicWebModule() }

      val responses =
        mapOf(
          "InvalidEmail" to signIn("not-an-address", "whatever"),
          "UserNotFound" to signIn(WebAuthTestSupport.uniqueEmail(), WebAuthTestSupport.DEFAULT_PASSWORD),
          "PasswordNotSet" to signIn(ssoOnly, WebAuthTestSupport.DEFAULT_PASSWORD),
          "PasswordMismatch" to signIn(known, "the-wrong-password"),
        )

      val statuses = responses.mapValues { (_, response) -> response.status }
      assertEquals(
        responses.keys.associateWith { HttpStatusCode.Unauthorized },
        statuses,
        "a login failure branch answers its own status, which says which one it was",
      )

      val bodies = responses.mapValues { (_, response) -> response.bodyAsText() }
      val distinct = bodies.values.map { stripTypedEmail(it) }.distinct()
      assertEquals(
        1,
        distinct.size,
        "the four login failures render [${distinct.size}] different bodies: the form is an account-enumeration oracle",
      )
      assertTrue(
        bodies.values.all { it.contains("Email or password is incorrect.") },
        "the generic message is not what a failed sign-in renders",
      )
    }

  @Test
  fun `a failed sign-in re-renders the typed email and never the password`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val body = signIn(email, "the-wrong-password").bodyAsText()

      assertTrue(body.contains("value=\"$email\""), "the typed email was not re-rendered: [$body]")
      assertTrue(!body.contains("the-wrong-password"), "the typed PASSWORD was re-rendered into the HTML")
    }

  /**
   * THE D12 HONESTY TEST (RFC 224, brief 0013 D12).
   *
   * D12 says that wherever a session exists, every form stores its data in it.
   * Sign-in is a PRE-SESSION form — D5 mints nothing for a signed-out visitor —
   * so the rule does not reach it, and the value re-rendered above is ordinary
   * form handling and NOT session storage. This is the test that stops a later
   * reader believing otherwise: after a failed attempt the typed value is in the
   * HTML and there is NO session row at all, anywhere, to have stored it in.
   */
  @Test
  fun `after a failed sign-in the typed value is in the HTML and no session row exists`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val body = signIn(email, "the-wrong-password").bodyAsText()

      assertTrue(body.contains("value=\"$email\""), "the re-render did not carry the typed value")
      assertEquals(
        0,
        WebAuthTestSupport.sessionRowCount(),
        "a failed sign-in created a session row: the re-render would then be session storage, and D12 would apply",
      )
    }

  /**
   * The Operation Boundary rule: `receiveParameters` throws for a body that is
   * not form-encoded, and the old read let that land in the `exception<Throwable>`
   * catch-all as a branded 503 — a server-fault page for a client error. The
   * form must be re-read defensively and re-rendered instead.
   */
  @Test
  fun `a sign-in POST whose body is not a form re-renders the form, never the branded 503`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.post("/sign-in") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.Json)
          setBody("""{"email":"someone@example.com","password":"x"}""")
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status, "a malformed body must answer a client error, not a server fault")
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "the sign-in form was not re-rendered: [$body]")
      assertTrue(!body.contains("503 Service Unavailable"), "the malformed body was answered as a server fault: [$body]")
    }

  /**
   * A body carrying two `email` fields is a request this form did not render,
   * so it is re-rendered rather than the first of several being handed to
   * `:service`. The old `.orEmpty()` read silently took the first.
   */
  @Test
  fun `a sign-in body carrying two email fields is re-rendered without taking the first`() =
    testApplication {
      application { testPublicWebModule() }

      val response =
        client.post("/sign-in") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("email=first@example.com&email=second@example.com&password=${WebAuthTestSupport.DEFAULT_PASSWORD}")
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Sign in"), "the form was not re-rendered: [$body]")
      assertTrue(
        !body.contains("first@example.com"),
        "the first of two email fields was echoed, so the surplus was silently discarded: [$body]",
      )
    }

  @Test
  fun `sign-out revokes the row so the same cookie no longer resolves`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      val user = WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val token = WebAuthTestSupport.login(email)
      assertEquals(1, WebAuthTestSupport.liveSessionCount(user.id.value))

      val response = formPost("/sign-out", sessionToken = token)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/", response.headers[HttpHeaders.Location])
      assertEquals(0, WebAuthTestSupport.liveSessionCount(user.id.value), "sign-out did not revoke the session row")

      // The revoked cookie now behaves exactly as no cookie at all.
      val afterwards =
        noFollowClient().get("/account") {
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
        }
      assertEquals(HttpStatusCode.Found, afterwards.status)
      assertEquals("/sign-in", afterwards.headers[HttpHeaders.Location])
    }

  /**
   * The slice's other headline direction: a session minted the way `rest-server`
   * mints one resolves on a web page. "Signing in on the web and then using the
   * iOS app does not require signing in again, and the reverse holds."
   */
  @Test
  fun `a session minted through AuthService resolves on a web page request`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedStudentUser(email)
      application { testPublicWebModule() }

      val token = WebAuthTestSupport.login(email)

      val response =
        client.get("/account") {
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
        }

      assertEquals(HttpStatusCode.OK, response.status, "a session minted outside the web tier did not resolve on a web page")
      assertTrue(response.bodyAsText().contains(email), "the account page does not name the signed-in address")
    }

  /** The two pages that carry the provider buttons (RFC 275 D-B). */
  private val ssoPages = listOf("/sign-in", "/register")

  /** The page's policy header, asserted to be exactly ONE: two are enforced as their intersection. */
  private fun HttpResponse.soleContentSecurityPolicy(path: String): String {
    val policies = headers.getAll(CONTENT_SECURITY_POLICY_HEADER).orEmpty()
    assertEquals(1, policies.size, "[$path] carries [${policies.size}] Content-Security-Policy headers: $policies")
    return policies.single()
  }

  /** The `src` of every script element the page carries. */
  private fun scriptSources(body: String): List<String> = Jsoup.parse(body).select("script[src]").map { it.attr("src") }

  @Test
  fun `with both providers configured, both pages render both buttons under one widened policy`() =
    testApplication {
      application { testPublicWebModule(ssoButtons = WebAuthTestSupport.bothSsoButtons) }

      for (path in ssoPages) {
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status, "[$path]")
        val page = Jsoup.parse(response.bodyAsText())

        // The bundle's contract (`sso.ts`), pinned as LITERALS rather than
        // through the Kotlin constants: `sso.ts` restates these strings, so a
        // rename on the Kotlin side must fail here, not leave the buttons dead.
        val google = page.selectFirst("[data-sso-google]")
        assertEquals(WebAuthTestSupport.TEST_GOOGLE_WEB_CLIENT_ID, google?.attr("data-client-id"), "[$path] Google host")
        val apple = page.selectFirst("button[data-sso-apple]")
        assertEquals(CONTINUE_WITH_APPLE, apple?.text(), "[$path] Apple button")
        assertEquals(WebAuthTestSupport.TEST_APPLE_SERVICES_ID, apple?.attr("data-client-id"), "[$path] Apple Services ID")
        assertEquals("${WebAuthTestSupport.urlBase}/sign-in", apple?.attr("data-redirect-uri"), "[$path] Apple return URL")
        assertEquals(
          listOf("google", "apple"),
          page.select("form[data-sso-form]").map { it.attr("data-sso-form") },
          "[$path] the provider forms' segments",
        )
        assertEquals(
          SsoDoor.entries.map { it.path },
          page.select("form[data-sso-form]").map { it.attr("action") },
          "[$path] the provider forms",
        )
        for (segment in listOf("google", "apple")) {
          assertNotNull(page.selectFirst("form[data-sso-form=$segment] input[name=id_token]"), "[$path] $segment's token field")
        }
        assertNotNull(page.selectFirst("form[data-sso-form=apple] input[name=name]"), "[$path] Apple's name field")
        assertNotNull(page.selectFirst("[data-sso-status]"), "[$path] the bundle's status line")
        assertTrue(page.text().contains(SSO_DIVIDER), "[$path] no divider")
        assertTrue(scriptSources(response.bodyAsText()).containsAll(listOf(GOOGLE_SDK_URL, APPLE_SDK_URL)), "[$path] provider scripts")

        val directives = response.soleContentSecurityPolicy(path).split(";").map { it.trim() }
        for (expected in listOf(
          "script-src 'self' https://accounts.google.com/gsi/client https://appleid.cdn-apple.com",
          "frame-src https://accounts.google.com/gsi/",
          "connect-src 'self' https://accounts.google.com/gsi/",
          "style-src 'self' https://accounts.google.com/gsi/style 'unsafe-inline'",
        )) {
          assertTrue(expected in directives, "[$path] the policy lacks [$expected]: $directives")
        }
        assertEquals(REFERRER_POLICY_STRICT_ORIGIN_WHEN_CROSS_ORIGIN, response.headers[REFERRER_POLICY_HEADER], "[$path]")
      }
    }

  @Test
  fun `with neither provider configured, both pages are the pages they were`() =
    testApplication {
      application { testPublicWebModule(ssoButtons = SsoButtons.NONE) }

      for (path in ssoPages) {
        val response = client.get(path)
        val body = response.bodyAsText()
        assertTrue(!body.contains("data-sso"), "[$path] renders SSO markup with no provider configured")
        assertTrue(!body.contains(SSO_DIVIDER), "[$path] renders the divider with nothing above it")
        assertLoadsBundleAsItsOnlyScript(path, body)
        assertEquals(CONTENT_SECURITY_POLICY, response.soleContentSecurityPolicy(path), "[$path]")
        assertEquals(REFERRER_POLICY_SAME_ORIGIN, response.headers[REFERRER_POLICY_HEADER], "[$path]")
      }
    }

  @Test
  fun `with only Apple configured, the pages name Apple's source and nothing of Google's`() =
    testApplication {
      val appleOnly = SsoButtons.of(null, WebAuthTestSupport.TEST_APPLE_SERVICES_ID, WebAuthTestSupport.publicWebConfig.origin)
      application { testPublicWebModule(ssoButtons = appleOnly) }

      for (path in ssoPages) {
        val response = client.get(path)
        val body = response.bodyAsText()
        assertTrue(!body.contains(SSO_GOOGLE_ATTRIBUTE), "[$path] renders a Google host with Google unconfigured")
        assertTrue(body.contains(SSO_APPLE_ATTRIBUTE), "[$path] has no Apple button")
        assertEquals(listOf(APPLE_SDK_URL), scriptSources(body).filter { it.startsWith("https://") }, "[$path]")
        val policy = response.soleContentSecurityPolicy(path)
        assertTrue(policy.contains("https://appleid.cdn-apple.com"), "[$path] the policy does not admit Apple: [$policy]")
        assertTrue(!policy.contains("accounts.google.com"), "[$path] the policy admits Google unconfigured: [$policy]")
        assertTrue(!policy.contains("'unsafe-inline'"), "[$path] Apple alone admits inline style: [$policy]")
        assertEquals(REFERRER_POLICY_SAME_ORIGIN, response.headers[REFERRER_POLICY_HEADER], "[$path] Apple alone needs no wider referrer")
      }
    }

  @Test
  fun `with providers configured, every other page keeps the default policy`() =
    testApplication {
      application { testPublicWebModule(ssoButtons = WebAuthTestSupport.bothSsoButtons) }
      val token = WebAuthTestSupport.login(WebAuthTestSupport.seedStudentUser(WebAuthTestSupport.uniqueEmail()).email.value)

      for (path in listOf("/chat", "/account", "/")) {
        val response = client.get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
        assertEquals(HttpStatusCode.OK, response.status, "[$path]")
        assertEquals(CONTENT_SECURITY_POLICY, response.soleContentSecurityPolicy(path), "[$path] widened by the sign-in pages' policy")
        assertTrue(scriptSources(response.bodyAsText()).none { it.startsWith("https://") }, "[$path] loads a provider script")
      }
    }

  /**
   * The typed email is the ONE thing that legitimately differs between the four
   * failure bodies, because each branch is posted with a different address. It
   * is removed before comparison so the comparison is about the MESSAGE.
   */
  private fun stripTypedEmail(body: String): String = body.replace(Regex("value=\"[^\"]*\""), "value=\"\"")

  private suspend fun ApplicationTestBuilder.signIn(
    email: String,
    password: String,
  ): HttpResponse =
    formPost(
      "/sign-in",
      parameters {
        append(EMAIL_FIELD, email)
        append(PASSWORD_FIELD, password)
      },
    )
}
