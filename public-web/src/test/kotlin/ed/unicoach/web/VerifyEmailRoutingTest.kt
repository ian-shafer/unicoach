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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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

      application { publicWebModule(DbEmailVerifier(database), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=$raw")
        }

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
      application { publicWebModule(verifier, TEST_OPEN_IN_APP_URL) }

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

  @Test
  fun `GET verify-email with no token renders InvalidToken and makes no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.InvalidToken))
      application { publicWebModule(verifier, TEST_OPEN_IN_APP_URL) }

      val response = client.get("/verify-email")

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Link not valid"), "missing InvalidToken marker")
      assertSharedChrome(body)
      assertEquals(0, verifier.callCount)
    }

  // --- Result (POST) outcome matrix --------------------------------------------

  @Test
  fun `POST verify-email Success renders success and the iPhone open-in-app link`() =
    testApplication {
      application { publicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, iPhoneUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

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
      application { publicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, desktopUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

      val body = response.bodyAsText()
      assertTrue(body.contains("Email verified"), "missing success marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present for non-iPhone UA")
    }

  @Test
  fun `POST verify-email AlreadyConsumed renders already-verified with the iPhone open-in-app link`() =
    testApplication {
      application { publicWebModule(fake(Result.success(VerifyEmailResult.AlreadyConsumed)), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, iPhoneUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

      val body = response.bodyAsText()
      assertTrue(body.contains("Already verified"), "missing already-verified marker")
      assertTrue(body.contains(TEST_OPEN_IN_APP_URL), "missing open-in-app link for iPhone UA")
    }

  @Test
  fun `POST verify-email InvalidToken omits the open-in-app link even for iPhone`() =
    testApplication {
      application { publicWebModule(fake(Result.success(VerifyEmailResult.InvalidToken)), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, iPhoneUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

      val body = response.bodyAsText()
      assertTrue(body.contains("Link not valid"), "missing InvalidToken marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on InvalidToken")
    }

  @Test
  fun `POST verify-email Expired omits the open-in-app link even for iPhone`() =
    testApplication {
      application { publicWebModule(fake(Result.success(VerifyEmailResult.Expired)), TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, iPhoneUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

      val body = response.bodyAsText()
      assertTrue(body.contains("Link expired"), "missing Expired marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on Expired")
    }

  @Test
  fun `POST verify-email failure renders the unavailable page with no open-in-app link`() =
    testApplication {
      application {
        publicWebModule(fake(Result.failure(RuntimeException("db fault"))), TEST_OPEN_IN_APP_URL)
      }

      val response =
        client.post("/verify-email") {
          header(HttpHeaders.UserAgent, iPhoneUa)
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=abc")
        }

      val body = response.bodyAsText()
      assertTrue(body.contains("Verification unavailable"), "missing Unavailable marker")
      assertFalse(body.contains(TEST_OPEN_IN_APP_URL), "open-in-app link present on Unavailable")
    }

  @Test
  fun `POST verify-email with a blank token short-circuits to InvalidToken with no verify call`() =
    testApplication {
      val verifier = fake(Result.success(VerifyEmailResult.Success(stubUser())))
      application { publicWebModule(verifier, TEST_OPEN_IN_APP_URL) }

      val response =
        client.post("/verify-email") {
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("token=")
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("Link not valid"), "missing InvalidToken marker")
      assertEquals(0, verifier.callCount)
    }

  @Test
  fun `every verify page renders through the shared layout`() =
    testApplication {
      application { publicWebModule(fake(Result.success(VerifyEmailResult.Success(stubUser()))), TEST_OPEN_IN_APP_URL) }

      assertSharedChrome(client.get("/verify-email?token=abc").bodyAsText())
      assertSharedChrome(
        client
          .post("/verify-email") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("token=abc")
          }.bodyAsText(),
      )
    }
}
