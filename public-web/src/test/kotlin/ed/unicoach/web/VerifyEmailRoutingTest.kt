package ed.unicoach.web

import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.web.render.TOKEN_FIELD
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyEmailRoutingTest {
  private val iPhoneUa =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
  private val desktopUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15"

  companion object {
    private lateinit var database: Database
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE users CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun assertSharedChrome(body: String) {
    assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
    assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    assertTrue(body.contains("href=\"/site.css\""), "missing /site.css link")
  }

  private fun createUser(): User {
    val email = (EmailAddress.create("verifyroute@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("Route User") as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(sqlSession, NewUser(email = email, name = name, displayName = null, passwordHash = pass))
      .getOrThrow()
  }

  private fun stubUser(): User =
    User(
      id = UserId(UUID.randomUUID()),
      email = (EmailAddress.create("stub@example.com") as ValidationResult.Valid).value,
      name = (PersonName.create("Stub User") as ValidationResult.Valid).value,
      displayName = null,
      passwordHash = null,
      isAdmin = false,
      emailVerifiedAt = Instant.now(),
      version = 1,
      createdAt = Instant.now(),
      updatedAt = Instant.now(),
      deletedAt = null,
    )

  private fun fake(result: Result<VerifyEmailResult>) = FakeEmailVerifier(result)

  // --- Real-DB wiring anchor ---------------------------------------------------

  @Test
  fun `POST verify-email through a real DbEmailVerifier consumes a seeded token and renders success`() =
    testApplication {
      val user = createUser()
      val raw = "real-anchor-raw"
      VerificationTokensDao
        .create(sqlSession, NewVerificationToken(user.id, TokenHash.fromRawToken(raw), Instant.now().plus(1, ChronoUnit.DAYS)))
        .getOrThrow()

      application { testPublicWebModule(DbEmailVerifier(database)) }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, raw) })

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Email verified"), "real verifier must render the success marker")
      assertSharedChrome(body)
    }

  // --- Confirm (GET) -----------------------------------------------------------

  @Test
  fun `GET verify-email with a token renders the confirm form and makes no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.InvalidToken))
      application { testPublicWebModule(verifier) }

      val response = client.get("/verify-email?token=abc")

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Confirm your email"), "missing confirm-page heading marker")
      assertTrue(body.contains("method=\"post\""), "missing POST form")
      assertTrue(body.contains("action=\"/verify-email\""), "missing form action")
      assertTrue(body.contains("name=\"token\""), "missing hidden token input")
      assertTrue(body.contains("value=\"abc\""), "missing token value")
      assertSharedChrome(body)
      assertEquals(0, verifier.callCount)
    }

  /**
   * Exactly one token, never the first of several: a URL carrying two tokens is
   * a request we have no reading of, so a signed-out visitor goes to sign-in
   * exactly as they would with none — the surplus is not silently discarded.
   */
  @Test
  fun `GET verify-email with two tokens is treated as having none rather than the first`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.InvalidToken))
      application { testPublicWebModule(verifier) }

      val response = noFollowClient().get("/verify-email?token=first&token=second")

      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/sign-in", response.headers[HttpHeaders.Location])
      assertEquals(0, verifier.callCount)
    }

  /**
   * CHANGED BY RFC 224, deliberately. `GET /verify-email` with no token used to
   * render "Link not valid" to everyone, because there was no session to ask
   * about. There is now: for a signed-in caller the same URL is the verification
   * HOLD screen the auth gate redirects an unverified family to (asserted in
   * `WebAuthGateTest`), and for a signed-out visitor there is nothing to verify
   * and nothing to hold, so the sign-in form is the honest answer. Either way
   * the verifier is still never called.
   */
  @Test
  fun `GET verify-email with no token sends a signed-out visitor to sign-in and makes no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.InvalidToken))
      application { testPublicWebModule(verifier) }

      val response = noFollowClient().get("/verify-email")

      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/sign-in", response.headers[HttpHeaders.Location])
      assertEquals(0, verifier.callCount)
    }

  // --- Result (POST) outcome matrix --------------------------------------------

  @Test
  fun `POST verify-email Success renders success and the iPhone open-in-app link`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, iPhoneUa) }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Email verified"), "missing success marker")
      assertTrue(body.contains(TEST_OPEN_IN_APP_URL), "missing open-in-app link for iPhone UA")
      assertTrue(body.contains("Open in app"), "missing open-in-app link text")
      assertSharedChrome(body)
    }

  @Test
  fun `POST verify-email Success with a non-iPhone UA omits the open-in-app link`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, desktopUa) }

      val body = response.bodyAsText()
      assertTrue(body.contains("Email verified"), "missing success marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present for non-iPhone UA")
    }

  @Test
  fun `POST verify-email AlreadyConsumed renders already-verified with the iPhone open-in-app link`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.AlreadyConsumed)))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, iPhoneUa) }

      val body = response.bodyAsText()
      assertTrue(body.contains("Already verified"), "missing already-verified marker")
      assertTrue(body.contains(TEST_OPEN_IN_APP_URL), "missing open-in-app link for iPhone UA")
    }

  @Test
  fun `POST verify-email InvalidToken omits the open-in-app link even for iPhone`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.InvalidToken)))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, iPhoneUa) }

      val body = response.bodyAsText()
      assertTrue(body.contains("Link not valid"), "missing InvalidToken marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on InvalidToken")
    }

  @Test
  fun `POST verify-email Expired omits the open-in-app link even for iPhone`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.Expired)))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, iPhoneUa) }

      val body = response.bodyAsText()
      assertTrue(body.contains("Link expired"), "missing Expired marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on Expired")
    }

  @Test
  fun `POST verify-email failure renders the unavailable page with no open-in-app link`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.failure(RuntimeException("db fault"))))
      }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }) { header(HttpHeaders.UserAgent, iPhoneUa) }

      val body = response.bodyAsText()
      assertTrue(body.contains("Verification unavailable"), "missing Unavailable marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on Unavailable")
    }

  /**
   * The Operation Boundary rule, verify-email half: a body that is not a form at
   * all is a bad token for the purpose of this single-use route, and is answered
   * as such rather than landing in the `exception<Throwable>` arm as a branded
   * 503.
   */
  @Test
  fun `POST verify-email whose body is not a form renders invalid, never the branded 503`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.Success(stubUser())))
      application { testPublicWebModule(verifier) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          contentType(ContentType.Application.Json)
          setBody("""{"token":"abc"}""")
        }

      assertEquals(HttpStatusCode.OK, response.status, "a malformed body must answer a client answer, not the branded 503")
      assertTrue(response.bodyAsText().contains("Link not valid"), "a non-form body was not answered as a bad token")
      assertEquals(0, verifier.callCount, "a non-form body reached the verifier")
    }

  /**
   * The POST side of the same rule: a single-use-token route must consume
   * EXACTLY one token. A body carrying two burns neither, and answers the same
   * page as any bad token without calling the verifier.
   */
  @Test
  fun `POST verify-email with two token fields renders invalid and makes no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.Success(stubUser())))
      application { testPublicWebModule(verifier) }

      val response =
        formPost(
          "/verify-email",
          parameters {
            append(TOKEN_FIELD, "first")
            append(TOKEN_FIELD, "second")
          },
        )

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("Link not valid"), "a repeated token was not treated as no token")
      assertEquals(0, verifier.callCount, "the first of two tokens was consumed")
    }

  @Test
  fun `POST verify-email with a blank token short-circuits to InvalidToken with no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.Success(stubUser())))
      application { testPublicWebModule(verifier) }

      val response =
        formPost("/verify-email", parameters { append(TOKEN_FIELD, "") })

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Link not valid"), "missing InvalidToken marker")
      assertEquals(0, verifier.callCount)
    }

  @Test
  fun `every verify page renders through the shared layout`() =
    testApplication {
      application {
        testPublicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))))
      }

      assertSharedChrome(client.get("/verify-email?token=abc").bodyAsText())
      assertSharedChrome(formPost("/verify-email", parameters { append(TOKEN_FIELD, "abc") }).bodyAsText())
    }
}
