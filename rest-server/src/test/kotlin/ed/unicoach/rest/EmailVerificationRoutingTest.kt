package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration coverage for the verify-email and resend-verification routes. The
 * verification_tokens table stores only the SHA-256 hash, so the tests insert a
 * known raw token (and its hash) directly for a registered user, then drive the
 * POST endpoints with that raw token.
 */
class EmailVerificationRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)

      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "verify-${UUID.randomUUID()}@company.com"

  private fun sha256(raw: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))

  /** Registers a user and returns the session cookie pair plus the user id. */
  private fun registerUser(email: String): Pair<String, UUID> {
    val req = RegisterRequest(email, "Password123!", "Verify User")
    val response =
      runBlocking {
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }
      }
    assertEquals(HttpStatusCode.Created, response.status)
    val cookiePair =
      response.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    val userId =
      connection.prepareStatement("SELECT id FROM users WHERE email = ?").use { stmt ->
        stmt.setString(1, email)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next())
          UUID.fromString(rs.getString("id"))
        }
      }
    return cookiePair to userId
  }

  private fun insertToken(
    userId: UUID,
    rawToken: String,
    expiresAt: Instant,
  ) {
    connection
      .prepareStatement("INSERT INTO verification_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setBytes(2, sha256(rawToken))
        stmt.setTimestamp(3, Timestamp.from(expiresAt))
        stmt.executeUpdate()
      }
  }

  private fun outstandingTokenCount(userId: UUID): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM verification_tokens WHERE user_id = ? AND consumed_at IS NULL").use { stmt ->
      stmt.setObject(1, userId)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun emailSendsCount(): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM email_sends").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /**
   * Recovers the raw verification token from the most recent verification email
   * sent to [recipient]. Only the token hash is persisted, so the raw token is
   * read back out of the delivered email body, the same way a real recipient
   * would follow the verify link.
   */
  private fun latestTokenSentTo(recipient: String): String =
    connection
      .prepareStatement(
        "SELECT body FROM email_sends WHERE recipient_email = ? ORDER BY created_at DESC LIMIT 1",
      ).use { stmt ->
        stmt.setString(1, recipient)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "Expected a delivered email for $recipient")
          val body = rs.getString("body")
          Regex("[?&]token=([^\\s&]+)").find(body)?.groupValues?.get(1)
            ?: error("No ?token= found in delivered email body: $body")
        }
      }

  @Test
  fun `full loop register insert-token verify-email then me reports verified`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, userId) = registerUser(email)
      val raw = "loop-raw-${UUID.randomUUID()}"
      insertToken(userId, raw, Instant.now().plus(1, ChronoUnit.DAYS))

      val verifyResponse =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$raw"}""")
        }
      assertEquals(HttpStatusCode.OK, verifyResponse.status)
      assertTrue(
        verifyResponse.bodyAsText().replace(" ", "").contains("\"emailVerified\":true"),
        "verify-email must report emailVerified=true",
      )

      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertTrue(
        meResponse.bodyAsText().replace(" ", "").contains("\"emailVerified\":true"),
        "me must report emailVerified=true after verify",
      )
    }

  @Test
  fun `verify-email with a bogus token returns 400 invalid_token`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"definitely-not-a-real-token"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("invalid_token"), "Expected invalid_token, got ${response.bodyAsText()}")
    }

  @Test
  fun `verify-email re-using a consumed token returns 400 token_already_used`() =
    runBlocking {
      val email = uniqueEmail()
      val (_, userId) = registerUser(email)
      val raw = "reuse-raw-${UUID.randomUUID()}"
      insertToken(userId, raw, Instant.now().plus(1, ChronoUnit.DAYS))

      val first =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$raw"}""")
        }
      assertEquals(HttpStatusCode.OK, first.status)

      val second =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$raw"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, second.status)
      assertTrue(second.bodyAsText().contains("token_already_used"), "Expected token_already_used, got ${second.bodyAsText()}")
    }

  @Test
  fun `verify-email with an expired token returns 400 token_expired`() =
    runBlocking {
      val email = uniqueEmail()
      val (_, userId) = registerUser(email)
      val raw = "expired-raw-${UUID.randomUUID()}"
      insertToken(userId, raw, Instant.now().minus(1, ChronoUnit.DAYS))

      val response =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$raw"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("token_expired"), "Expected token_expired, got ${response.bodyAsText()}")
    }

  @Test
  fun `resend-verification authenticated returns 204 and issues a fresh token burning the prior`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, userId) = registerUser(email)
      // Registration already issued one outstanding token.
      assertTrue(outstandingTokenCount(userId) >= 1, "Registration should leave an outstanding token")

      val response =
        client.post(buildUrl("/api/v1/auth/resend-verification")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.NoContent, response.status)

      // Resend invalidates prior tokens and issues exactly one fresh one.
      assertEquals(1, outstandingTokenCount(userId), "Resend must leave exactly one outstanding token")
    }

  @Test
  fun `resend-verification with no session returns 401 unauthorized`() =
    runBlocking {
      val response = client.post(buildUrl("/api/v1/auth/resend-verification"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"))
    }

  @Test
  fun `resend-verification for an already-verified user returns 204 with no new email_sends row`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, userId) = registerUser(email)
      // Verify the user first.
      val raw = "preverify-raw-${UUID.randomUUID()}"
      insertToken(userId, raw, Instant.now().plus(1, ChronoUnit.DAYS))
      val verify =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$raw"}""")
        }
      assertEquals(HttpStatusCode.OK, verify.status)

      val before = emailSendsCount()
      val response =
        client.post(buildUrl("/api/v1/auth/resend-verification")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(before, emailSendsCount(), "An already-verified resend must not record a new email send")
    }

  /** Polls for the verification email delivered to [recipient] (post-commit, best-effort send). */
  private fun awaitTokenSentTo(recipient: String): String {
    repeat(50) {
      val present =
        connection
          .prepareStatement("SELECT COUNT(*) FROM email_sends WHERE recipient_email = ?")
          .use { stmt ->
            stmt.setString(1, recipient)
            stmt.executeQuery().use { rs ->
              rs.next()
              rs.getInt(1) > 0
            }
          }
      if (present) return latestTokenSentTo(recipient)
      Thread.sleep(100)
    }
    error("No verification email arrived for $recipient")
  }

  @Test
  fun `change-email authenticated with a valid new email returns 200 unverified`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, _) = registerUser(email)
      val newEmail = uniqueEmail()

      val response =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.Cookie, cookiePair)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$newEmail"}""")
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val collapsed = response.bodyAsText().replace(" ", "")
      assertTrue(collapsed.contains("\"email\":\"$newEmail\""), "Body must carry the new email, got ${response.bodyAsText()}")
      assertTrue(collapsed.contains("\"emailVerified\":false"), "New email must be unverified, got ${response.bodyAsText()}")
    }

  @Test
  fun `change-email with no session returns 401 unauthorized`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"${uniqueEmail()}"}""")
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"))
    }

  @Test
  fun `change-email with a stale session cookie returns 401 unauthorized`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.Cookie, "UNICOACH_SESSION=definitely-not-a-real-token")
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"${uniqueEmail()}"}""")
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.bodyAsText().contains("unauthorized"))
    }

  @Test
  fun `change-email with an invalid email returns 400 validation_failed`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, _) = registerUser(email)

      val response =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.Cookie, cookiePair)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"bogus"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "Expected validation_failed, got $body")
      assertTrue(body.contains("\"email\""), "Expected a field error on email, got $body")
    }

  @Test
  fun `change-email to an address held by another active user returns 409 conflict`() =
    runBlocking {
      val takenEmail = uniqueEmail()
      registerUser(takenEmail)

      val email = uniqueEmail()
      val (cookiePair, _) = registerUser(email)

      val response =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.Cookie, cookiePair)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$takenEmail"}""")
        }
      assertEquals(HttpStatusCode.Conflict, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("conflict"), "Expected conflict, got $body")
      assertTrue(body.contains("\"email\""), "Expected a field error on email, got $body")
    }

  @Test
  fun `GET on change-email returns 405 with Allow POST`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/auth/change-email"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("POST", response.headers[HttpHeaders.Allow])
    }

  @Test
  fun `change-email then verify the new address end-to-end reports verified`() =
    runBlocking {
      val email = uniqueEmail()
      val (cookiePair, _) = registerUser(email)
      val newEmail = uniqueEmail()

      val change =
        client.post(buildUrl("/api/v1/auth/change-email")) {
          header(HttpHeaders.Cookie, cookiePair)
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$newEmail"}""")
        }
      assertEquals(HttpStatusCode.OK, change.status)

      // Recover the new raw token from the delivered email (post-commit send).
      val rawToken = awaitTokenSentTo(newEmail)

      val verify =
        client.post(buildUrl("/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"token":"$rawToken"}""")
        }
      assertEquals(HttpStatusCode.OK, verify.status)
      assertTrue(
        verify.bodyAsText().replace(" ", "").contains("\"emailVerified\":true"),
        "verify-email after change must report emailVerified=true, got ${verify.bodyAsText()}",
      )
      assertTrue(
        verify.bodyAsText().replace(" ", "").contains("\"email\":\"$newEmail\""),
        "verify-email must report the new address, got ${verify.bodyAsText()}",
      )
    }

  @Test
  fun `GET on verify-email returns 405 with Allow POST`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/auth/verify-email"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("POST", response.headers[HttpHeaders.Allow])
    }

  @Test
  fun `GET on resend-verification returns 405 with Allow POST`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/auth/resend-verification"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("POST", response.headers[HttpHeaders.Allow])
    }
}
