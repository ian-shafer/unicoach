package ed.unicoach.web

import ed.unicoach.queue.JobType
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.NAME_FIELD
import ed.unicoach.web.render.PASSWORD_FIELD
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
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registration on the web (RFC 224).
 *
 * Registration is the one route in this slice with a side effect outside the
 * two tables it writes: it must ENQUEUE the verification email. `public-web`
 * became a queue producer for exactly this, and an enqueue that silently stops
 * happening looks like a successful registration from every other angle — the
 * family simply never receives the mail. So the job count is asserted.
 */
class RegistrationRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `GET register renders the form and the way back to sign-in`() =
    testApplication {
      application { testPublicWebModule() }

      val body = client.get("/register").bodyAsText()

      assertTrue(body.contains("Create your account"), "missing the registration heading")
      assertTrue(body.contains("action=\"/register\""), "the form does not post to /register")
      assertTrue(body.contains("name=\"name\""), "no name control")
      assertTrue(body.contains("name=\"email\""), "no email control")
      assertTrue(body.contains("name=\"password\""), "no password control")
      assertTrue(body.contains("href=\"/sign-in\""), "no way back to the sign-in form")
    }

  @Test
  fun `a valid registration mints a session, sets the cookie and redirects to the verification screen`() =
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()

      val response = register(email)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/verify-email", response.headers[HttpHeaders.Location], "a new account must land on the hold screen, not /account")
      assertTrue(
        response.headers[HttpHeaders.SetCookie].orEmpty().startsWith(WebAuthTestSupport.sessionConfig.cookieName),
        "registration did not sign the family in",
      )
      assertEquals(1, WebAuthTestSupport.sessionRowCount(), "registration did not mint exactly one session row")
    }

  @Test
  fun `a valid registration enqueues exactly one verification email`() =
    testApplication {
      application { testPublicWebModule() }

      register(WebAuthTestSupport.uniqueEmail())

      assertEquals(
        1,
        WebAuthTestSupport.jobCount(JobType.SEND_EMAIL.value),
        "registration must enqueue exactly one SEND_EMAIL job; the worker is what actually sends it",
      )
    }

  @Test
  fun `a validation failure re-renders the form at 400 with the typed name and email`() =
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()

      val response = register(email, name = "Someone Real", password = "short")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("value=\"Someone Real\""), "the typed name was not re-rendered: [$body]")
      assertTrue(body.contains("value=\"$email\""), "the typed email was not re-rendered")
      assertTrue(!body.contains("short"), "the typed PASSWORD was re-rendered into the HTML")
      assertTrue(
        body.contains("Password must be at least 8 characters long"),
        "the rejected registration renders NO validation message: the field errors :service produced were dropped: [$body]",
      )
    }

  /**
   * THE D12 HONESTY TEST, registration half. Same rule, same reason as the
   * sign-in one: registration is a PRE-SESSION form, so the typed values in the
   * re-rendered HTML are ordinary form handling and there is no session anywhere
   * that could have stored them.
   */
  @Test
  fun `after a failed registration the typed values are in the HTML and no session row exists`() =
    testApplication {
      application { testPublicWebModule() }

      val body = register(WebAuthTestSupport.uniqueEmail(), name = "Someone Real", password = "short").bodyAsText()

      assertTrue(body.contains("value=\"Someone Real\""), "the re-render did not carry the typed value")
      assertEquals(
        0,
        WebAuthTestSupport.sessionRowCount(),
        "a failed registration created a session row: the re-render would then be session storage, and D12 would apply",
      )
    }

  /**
   * A body carrying two `name` fields is not this form's submission, so it is
   * re-rendered at 400 and NO account is created. The old `.orEmpty()` read
   * silently took the first name and registered the family.
   */
  @Test
  fun `a register body carrying two name fields is re-rendered and creates no account`() =
    testApplication {
      application { testPublicWebModule() }
      val email = WebAuthTestSupport.uniqueEmail()

      val response =
        client.post("/register") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("name=First&name=Second&email=$email&password=${WebAuthTestSupport.DEFAULT_PASSWORD}")
        }

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("Create your account"), "the form was not re-rendered")
      assertEquals(0, WebAuthTestSupport.sessionRowCount(), "a repeated field was handed to :service and registered somebody")
      assertEquals(0, WebAuthTestSupport.jobCount(JobType.SEND_EMAIL.value), "a repeated field enqueued a verification email")
    }

  /**
   * An address already in use answers 409 and says nothing about the account
   * that holds it — the same enumeration oracle the sign-in form refuses to be,
   * reached through the other door.
   */
  @Test
  fun `a duplicate address is refused without confirming the account exists`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }

      val response = register(email)

      assertEquals(HttpStatusCode.Conflict, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("If you already have an account, sign in instead."), "the duplicate message changed: [$body]")
      assertTrue(
        !body.contains("already registered") && !body.contains("already exists"),
        "the page confirms the address is registered, which is the enumeration oracle the sign-in form refuses to be",
      )
      assertEquals(0, WebAuthTestSupport.sessionRowCount(), "a refused registration signed somebody in")
    }

  private suspend fun ApplicationTestBuilder.register(
    email: String,
    name: String = "New Family",
    password: String = WebAuthTestSupport.DEFAULT_PASSWORD,
  ): HttpResponse =
    formPost(
      "/register",
      parameters {
        append(NAME_FIELD, name)
        append(EMAIL_FIELD, email)
        append(PASSWORD_FIELD, password)
      },
    )
}
