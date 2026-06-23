package ed.unicoach.rest.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.appModule
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.VerifyEmailRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the full server (with the gate wired in `appModule`) and exercises the
 * email-verification gate against a gated route, the exempt routes, and the
 * client-key/forced-failure edge cases.
 */
class EmailVerificationGateTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection
    private lateinit var appConfig: com.typesafe.config.Config

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = ed.unicoach.rest.startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)

      appConfig =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(appConfig).getOrThrow()
      dbConnection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "gate${java.util.UUID.randomUUID()}@company.com"

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private data class Registered(
    val email: String,
    val cookie: String,
  )

  private suspend fun register(): Registered {
    val email = uniqueEmail()
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Gate User")))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    val cookie =
      response.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    return Registered(email, cookie)
  }

  /** Reads the verification email body for [email] and extracts the raw token. */
  private fun verificationTokenFor(email: String): String {
    dbConnection
      .prepareStatement("SELECT body FROM email_sends WHERE recipient_email = ? ORDER BY id DESC LIMIT 1")
      .use { stmt ->
        stmt.setString(1, email)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "Expected a verification email_sends row for $email")
          val body = rs.getString("body")
          val match = Regex("token=([^\\s]+)").find(body)
          return match!!.groupValues[1]
        }
      }
  }

  @Test
  fun `unverified user on a gated route gets 403 email_not_verified`() =
    runBlocking {
      val (_, cookie) = register()
      val response =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("email_not_verified"))
    }

  @Test
  fun `verified user passes the gate on the same route`() =
    runBlocking {
      val (email, cookie) = register()
      markEmailVerified(email)
      val response =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      // No profile yet — but it reached the handler (404), not 403 at the gate.
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("student_not_found"))
    }

  @Test
  fun `no cookie on a gated route is 401 not 403`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/students/me"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"))
    }

  @Test
  fun `garbage cookie on a gated route is 401 not 403`() =
    runBlocking {
      val response =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, "session=not-a-real-token")
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"))
    }

  @Test
  fun `unverified user reaches every exempt route`() =
    runBlocking {
      val (_, cookie) = register()

      val me =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, me.status)

      // resend-verification needs a fresh registration: a logout below revokes the
      // session, so do it before logout.
      val resend =
        client.post(buildUrl("/api/v1/auth/resend-verification")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NoContent, resend.status)

      val verifyBogus =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(VerifyEmailRequest("bogus-token")))
        }
      assertEquals(HttpStatusCode.BadRequest, verifyBogus.status)
      assertTrue(verifyBogus.bodyAsText().contains("invalid_token"))

      val logout =
        client.post(buildUrl("/api/v1/auth/logout")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NoContent, logout.status)

      val health = client.get(buildUrl("/healthz"))
      assertEquals(HttpStatusCode.OK, health.status)
    }

  @Test
  fun `unverified user on a second gated family also gets 403 email_not_verified`() =
    runBlocking {
      val (_, cookie) = register()
      val response =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"message":"hi","name":null}""")
        }
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("email_not_verified"))
    }

  @Test
  fun `after verifying the email the gated route passes`() =
    runBlocking {
      val (email, cookie) = register()

      // Pre-condition: gated route is 403 while unverified.
      val before =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Forbidden, before.status)

      // Consume the real issued token through the exempt verify-email route.
      val token = verificationTokenFor(email)
      val verify =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(VerifyEmailRequest(token)))
        }
      assertEquals(HttpStatusCode.OK, verify.status)

      val after =
        client.get(buildUrl("/api/v1/students/me")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, after.status)
      assertTrue(after.bodyAsText().contains("student_not_found"))
    }

  @Test
  fun `client-key gate fires before the verification gate`() {
    // Boot a dedicated server whose client-key gate has a non-empty key set, so
    // an absent client key is rejected by the client-key gate first.
    val config =
      AppConfig
        .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
        .getOrThrow()
    val database = Database(DatabaseConfig.from(config).getOrThrow())
    val keyedClientGate = ClientKeyGateConfig(validKeys = setOf("secret-key"), allowlistPaths = setOf("/healthz"))

    val server =
      embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        environment.monitor.subscribe(ApplicationStopped) { database.close() }
        moduleWith(config, database, keyedClientGate)
      }
    server.start(wait = false)
    val port =
      runBlocking {
        server.engine
          .resolvedConnectors()
          .first()
          .port
      }
    val localClient = HttpClient(CIO)
    try {
      runBlocking {
        // Register through the keyed server with the valid client key.
        val email = uniqueEmail()
        val reg =
          localClient.post("http://localhost:$port/api/v1/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(CLIENT_KEY_HEADER, "secret-key")
            setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Keyed User")))
          }
        assertEquals(HttpStatusCode.Created, reg.status)
        val cookie =
          reg.headers[HttpHeaders.SetCookie]!!
            .split(";")
            .first()
            .trim()

        // Unverified + no client key on a gated route -> client-key gate's 403
        // forbidden wins, NOT email_not_verified.
        val response =
          localClient.get("http://localhost:$port/api/v1/students/me") {
            header(HttpHeaders.Cookie, cookie)
          }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("forbidden"), "Expected client-key gate's 'forbidden', got: $body")
        assertTrue(!body.contains("email_not_verified"), "Verification gate must not fire ahead of client-key gate")
      }
    } finally {
      localClient.close()
      server.stop(1000, 5000)
    }
  }

  @Test
  fun `forced resolveSession failure on a gated route is 500 internal_error`() =
    testApplication {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val database = Database(DatabaseConfig.from(config).getOrThrow())

      // Seed a real user + session so the gate gets past the no-caller branch and
      // actually performs a lookup — then close the pool to fault that lookup.
      val rawToken = "forced-fail-token-${java.util.UUID.randomUUID()}"
      runBlocking {
        database.withConnection { session ->
          val email =
            (
              ed.unicoach.common.models.EmailAddress
                .create("forcedfail${java.util.UUID.randomUUID()}@example.com")
                as ed.unicoach.common.models.ValidationResult.Valid
            ).value
          val name =
            (
              ed.unicoach.db.models.PersonName
                .create("Forced Fail") as ed.unicoach.common.models.ValidationResult.Valid
            ).value
          val pwd =
            (
              ed.unicoach.db.models.PasswordHash
                .create("\$argon2id\$v=19\$m=65536,t=3,p=1\$dummysalt\$dummyhash")
                as ed.unicoach.common.models.ValidationResult.Valid
            ).value
          val user =
            ed.unicoach.db.dao.UsersDao
              .create(
                session,
                ed.unicoach.db.models.NewUser(
                  email = email,
                  name = name,
                  displayName = null,
                  passwordHash = pwd,
                ),
              ).getOrThrow()
          ed.unicoach.db.dao.SessionsDao
            .create(
              session,
              ed.unicoach.db.models.NewSession(
                userId = user.id,
                tokenHash =
                  ed.unicoach.db.models.TokenHash
                    .fromRawToken(rawToken),
                userAgent = "test",
                initialIp = "127.0.0.1",
                metadata = null,
                expiration = java.time.Duration.ofDays(7),
                loginMethod = ed.unicoach.db.models.LoginMethod.PASSWORD,
              ),
            ).getOrThrow()
        }
      }

      application {
        moduleWith(config, database, ClientKeyGateConfig(validKeys = emptySet(), allowlistPaths = setOf("/healthz")))
      }

      // Close the pool: the gate's resolveSession faults at connection acquisition.
      database.close()

      val sessionCookieName = SessionConfig.from(config).getOrThrow().cookieName
      val response =
        client.get("/api/v1/students/me") {
          header(HttpHeaders.Cookie, "$sessionCookieName=$rawToken")
        }
      assertEquals(HttpStatusCode.InternalServerError, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("internal_error"), "Expected internal_error, got: $body")
      assertTrue(!body.contains("permanent_error"), "Closed-pool fault must not map to permanent_error")
    }
}

/**
 * Assembles `appModule` with an injected [ClientKeyGateConfig], reusing the
 * standard config for all other components. Mirrors the production wiring in
 * `startServer` but lets tests vary the client-key gate.
 */
private fun io.ktor.server.application.Application.moduleWith(
  config: com.typesafe.config.Config,
  database: Database,
  clientKeyGateConfig: ClientKeyGateConfig,
) {
  val sessionConfig = SessionConfig.from(config).getOrThrow()
  val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
  val chatProvider =
    ed.unicoach.chat.ChatProviderFactory
      .fromConfig(
        ed.unicoach.chat.ChatConfig
          .from(config)
          .getOrThrow(),
      ).getOrThrow()
  val coachingConfig =
    ed.unicoach.coaching.CoachingConfig
      .from(config)
      .getOrThrow()
  val emailConfig =
    ed.unicoach.email.EmailConfig
      .from(config)
      .getOrThrow()
  val emailProvider =
    ed.unicoach.email.EmailProviderFactory
      .fromConfig(emailConfig)
      .getOrThrow()
  val emailService = ed.unicoach.email.EmailService(database, emailProvider, emailConfig)
  val emailVerificationConfig =
    ed.unicoach.auth.EmailVerificationConfig
      .from(config)
      .getOrThrow()
  val queueService = ed.unicoach.queue.QueueService(database)
  val extractionConfig =
    ed.unicoach.coaching.extraction.ExtractionConfig
      .from(config)
      .getOrThrow()

  appModule(
    database,
    sessionConfig,
    requestSizeConfig,
    chatProvider,
    coachingConfig,
    clientKeyGateConfig,
    emailService,
    emailVerificationConfig,
    ed.unicoach.auth.StubGoogleTokenVerifier(),
    queueService,
    extractionConfig,
  )
}
