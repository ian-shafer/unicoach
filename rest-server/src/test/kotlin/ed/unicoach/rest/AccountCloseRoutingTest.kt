package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `DELETE /api/v1/auth/me` (RFC 252): every account shape closes through one
 * door, the old cookie stops working, and the same Apple or Google sign-in can
 * come back as a NEW account. What the close writes is covered by
 * `AccountClosureServiceTest`; this suite covers the door.
 */
class AccountCloseRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

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
      val dbConfig = DatabaseConfig.from(AppConfig.load("common.conf", "db.conf").getOrThrow()).getOrThrow()
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
  private val baseUrl get() = "http://localhost:$boundPort"

  private suspend fun closeAccount(cookie: String?): HttpResponse =
    client.delete("$baseUrl/api/v1/auth/me") {
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun me(cookie: String): HttpResponse = client.get("$baseUrl/api/v1/auth/me") { header(HttpHeaders.Cookie, cookie) }

  /** The door's whole contract for one caller: 204, the cookie cleared, and the old cookie dead. */
  private suspend fun assertCloses(cookie: String) {
    val response = closeAccount(cookie)
    assertEquals(HttpStatusCode.NoContent, response.status, "got ${response.bodyAsText()}")
    val cleared = assertNotNull(response.headers[HttpHeaders.SetCookie], "the session cookie must be cleared")
    assertTrue(cleared.contains("Max-Age=0", ignoreCase = true), "got [$cleared]")
    assertEquals(HttpStatusCode.Unauthorized, me(cookie).status, "the old cookie no longer resolves")
  }

  private suspend fun becomeSupporter(cookie: String) {
    val response = client.post("$baseUrl/api/v1/supporters") { header(HttpHeaders.Cookie, cookie) }
    assertEquals(HttpStatusCode.Created, response.status, "got ${response.bodyAsText()}")
  }

  @Test
  fun `a student closes their account`() =
    runBlocking {
      assertCloses(registerStudentSession(client, baseUrl, dbConnection))
    }

  @Test
  fun `a supporter closes their account`() =
    runBlocking {
      val cookie = registerVerifiedUser(client, baseUrl, dbConnection)
      becomeSupporter(cookie)
      assertCloses(cookie)
    }

  @Test
  fun `a dual-role account closes`() =
    runBlocking {
      val cookie = registerStudentSession(client, baseUrl, dbConnection)
      becomeSupporter(cookie)
      assertCloses(cookie)
    }

  @Test
  fun `an account with no role closes`() =
    runBlocking {
      assertCloses(registerVerifiedUser(client, baseUrl, dbConnection))
    }

  /** Under `/api/v1/auth/`, so the verification gate does not hold an unverified account at the door that ends it. */
  @Test
  fun `an unverified account closes`() =
    runBlocking {
      assertCloses(sessionCookie(registerUser(client, baseUrl, "close-${UUID.randomUUID()}@company.com")))
    }

  @Test
  fun `no session is 401`() =
    runBlocking {
      assertEquals(HttpStatusCode.Unauthorized, closeAccount(null).status)
    }

  // --- coming back (RFC 252 §1b) ----------------------------------------------------

  private suspend fun signIn(
    provider: String,
    sub: String,
    email: String,
  ): HttpResponse =
    client.post("$baseUrl/api/v1/auth/$provider") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody(mapper.writeValueAsString(mapOf("idToken" to "stub:sub=$sub;email=$email;email_verified=true")))
    }

  private suspend fun userIdOf(response: HttpResponse): String = mapper.readTree(response.bodyAsText())["user"]["id"].asText()

  private suspend fun assertComesBackAsANewAccount(provider: String) {
    val sub = "$provider-return-${UUID.randomUUID()}"
    val email = "$provider-return-${UUID.randomUUID()}@example.com"
    val first = signIn(provider, sub, email)
    assertEquals(HttpStatusCode.OK, first.status, "got ${first.bodyAsText()}")
    assertCloses(sessionCookie(first))

    val again = signIn(provider, sub, email)

    assertEquals(HttpStatusCode.OK, again.status, "not 403 account_disabled; got ${again.bodyAsText()}")
    assertNotEquals(userIdOf(first), userIdOf(again), "the same sign-in comes back as a NEW account")
  }

  @Test
  fun `the same Google sign-in comes back as a new account after a close`() =
    runBlocking {
      assertComesBackAsANewAccount("google")
    }

  @Test
  fun `the same Apple sign-in comes back as a new account after a close`() =
    runBlocking {
      assertComesBackAsANewAccount("apple")
    }
}
