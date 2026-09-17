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
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

    // Unique email counter to prevent cross-test collision on the users table
    private val emailCounter = AtomicInteger(0)

    /** The iOS session lifetime `session.conf` declares. */
    private val IOS_SESSION_LIFETIME: Duration = Duration.ofDays(7)

    /**
     * The row's remaining life is measured against `NOW()` AFTER the insert, so
     * it is always a little under the nominal figure. "Within a minute of 7
     * days" catches a 30 and catches a 0 without failing on a slow machine.
     */
    private val CLOCK_SLACK: Duration = Duration.ofMinutes(1)

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
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) {
        testServer.stop(1000, 5000)
      }
      if (::client.isInitialized) {
        client.close()
      }
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "testuser${java.util.UUID.randomUUID()}@company.com"

  @Test
  fun `test valid registration state simulation`() =
    runBlocking {
      val req = RegisterRequest(uniqueEmail(), "Password123!", "Test User")

      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }

      assertEquals(HttpStatusCode.Created, response.status)
      val body = response.bodyAsText()
      assertTrue(response.headers[HttpHeaders.SetCookie] != null, "Missing Set-Cookie header")
      assertTrue(body.contains(req.email))
    }

  @Test
  fun `register login and me report emailVerified false before verification`() =
    runBlocking {
      val email = uniqueEmail()
      val password = "Password123!"
      val req = RegisterRequest(email, password, "Unverified User")

      val registerResponse =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }
      assertEquals(HttpStatusCode.Created, registerResponse.status)
      assertTrue(
        registerResponse.bodyAsText().replace(" ", "").contains("\"emailVerified\":false"),
        "register must report emailVerified=false",
      )

      val cookiePair =
        registerResponse.headers[HttpHeaders.SetCookie]!!
          .split(";")
          .first()
          .trim()

      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertTrue(
        meResponse.bodyAsText().replace(" ", "").contains("\"emailVerified\":false"),
        "me must report emailVerified=false",
      )

      val loginReq =
        ed.unicoach.rest.models
          .LoginRequest(email, password)
      val loginResponse =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(loginReq))
        }
      assertTrue(
        loginResponse.bodyAsText().replace(" ", "").contains("\"emailVerified\":false"),
        "login must report emailVerified=false",
      )
    }

  @Test
  fun `test CORS configuration validation hooks`() =
    runBlocking {
      val response =
        client.options(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.Origin, "http://localhost:3000")
          header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
      val allowedStatus = listOf(HttpStatusCode.OK, HttpStatusCode.NoContent, HttpStatusCode.MethodNotAllowed)
      assertTrue(response.status in allowedStatus)
    }

  @Test
  fun `test header structure verification constraints`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, "text/plain")
          setBody("some raw text")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString()) == true,
        "415 rewrite must respond application/json",
      )
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `register with non-string name returns 400`() =
    runBlocking {
      val email = uniqueEmail()
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$email","password":"Password123","name":false}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString()) == true,
        "Coercion rejection must respond application/json",
      )
      assertTrue(response.bodyAsText().contains("bad_request"))

      // No user was created: a follow-up registration with the same email succeeds.
      val followUp =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$email","password":"Password123","name":"Real Name"}""")
        }
      assertEquals(HttpStatusCode.Created, followUp.status)
    }

  @Test
  fun `register with a NUL character in name returns 400 not 500`() =
    runBlocking {
      // PostgreSQL refuses NUL bytes in UTF-8 text (SQLSTATE 22021). The bytes
      // are the caller's data, so the contract answer is a 400, not the 500
      // this used to produce — found by the pre-commit fuzzer (RFC 137).
      val email = uniqueEmail()
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"$email","password":"Password123","name":"Nul\u0000Name"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("cannot be stored"))
    }

  @Test
  fun `register with malformed email returns 400`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email":"useratexample.com","password":"Password123","name":"Real Name"}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("email"), "Body should carry an email field error")
    }

  @Test
  fun `login with null body returns 400 json`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("null")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString()) == true,
        "Null body rejection must respond application/json",
      )
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `PUT register returns 405 with Allow`() =
    runBlocking {
      val response =
        client.put(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("{}")
        }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("POST", response.headers[HttpHeaders.Allow])
    }

  @Test
  fun `test unique invariant and malicious vector rejection`() =
    runBlocking {
      val email = uniqueEmail()
      val req1 = RegisterRequest(email, "Password123!", "Test User")
      val req2 = RegisterRequest(email, "Password123!", "Test User")

      val res1 =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req1))
        }
      assertEquals(HttpStatusCode.Created, res1.status)

      val res2 =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req2))
        }
      assertEquals(HttpStatusCode.Conflict, res2.status)
    }

  @Test
  fun `test large buffer mitigation rejection`() =
    runBlocking {
      val paddedBody = "{" + " ".repeat(10000) + "\"email\":\"valid@company.com\"}"
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.ContentLength, paddedBody.length.toString())
          setBody(paddedBody)
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

  @Test
  fun `test StatusPages deserialization boundaries`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("""{"email": 123}""")
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("bad_request"))
    }

  @Test
  fun `test timing attack mitigation`() =
    runBlocking {
      val email = uniqueEmail()
      val req = RegisterRequest(email, "Password123!", "Test User")

      val t1 =
        measureTimeMillis {
          client.post(buildUrl("/api/v1/auth/register")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val t2 =
        measureTimeMillis {
          client.post(buildUrl("/api/v1/auth/register")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(req))
          }
        }

      val diff = Math.abs(t1 - t2)
      assertTrue(diff < 1500)
    }

  // --- /me endpoint tests ---

  @Test
  fun `authenticated me returns 200 with user`() =
    runBlocking {
      val email = uniqueEmail()
      val req = RegisterRequest(email, "Password123!", "Me Test User")

      val registerResponse =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }
      assertEquals(HttpStatusCode.Created, registerResponse.status)

      val setCookie = registerResponse.headers[HttpHeaders.SetCookie]
      assertTrue(setCookie != null, "Missing Set-Cookie header from register")

      // Extract the cookie name=value from the Set-Cookie header
      val cookiePair = setCookie.split(";").first().trim()

      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.OK, meResponse.status)
      val body = meResponse.bodyAsText()
      assertTrue(body.contains(email), "Response should contain the user's email")
      assertTrue(body.contains("Me Test User"), "Response should contain the user's name")
    }

  @Test
  fun `missing cookie returns 401`() =
    runBlocking {
      val meResponse =
        client.get(buildUrl("/api/v1/auth/me"))
      assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

  @Test
  fun `invalid cookie returns 401`() =
    runBlocking {
      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, "UNICOACH_SESSION=garbage-token-value-12345")
        }
      assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

  @Test
  fun `POST to me returns 405`() =
    runBlocking {
      val meResponse =
        client.post(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("{}")
        }
      assertEquals(HttpStatusCode.MethodNotAllowed, meResponse.status)
    }

  // --- /login endpoint tests ---

  @Test
  fun `valid login simulation returns 200 and sets cookie`() =
    runBlocking {
      val email = uniqueEmail()
      val password = "Password123!"
      val req = RegisterRequest(email, password, "Login Test User")

      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }

      val loginReq =
        ed.unicoach.rest.models
          .LoginRequest(email, password)
      val loginResponse =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(loginReq))
        }

      assertEquals(HttpStatusCode.OK, loginResponse.status)
      val setCookie = loginResponse.headers[HttpHeaders.SetCookie]
      assertTrue(setCookie != null, "Missing Set-Cookie header from login")

      val body = loginResponse.bodyAsText()
      assertTrue(body.contains(email))
    }

  @Test
  fun `session overwrite verification returns new cookie and invalidates old`() =
    runBlocking {
      val email = uniqueEmail()
      val password = "Password123!"
      val req = RegisterRequest(email, password, "Login Session Overwrite User")

      val registerResponse =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }

      val oldSetCookie = registerResponse.headers[HttpHeaders.SetCookie]!!
      val oldCookiePair = oldSetCookie.split(";").first().trim()

      val loginReq =
        ed.unicoach.rest.models
          .LoginRequest(email, password)
      val loginResponse =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, oldCookiePair)
          setBody(mapper.writeValueAsString(loginReq))
        }

      assertEquals(HttpStatusCode.OK, loginResponse.status)
      val newSetCookie = loginResponse.headers[HttpHeaders.SetCookie]!!
      val newCookiePair = newSetCookie.split(";").first().trim()

      assertTrue(oldCookiePair != newCookiePair, "A new session cookie should be issued")

      // The old cookie should no longer be valid for /me
      val oldMeResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, oldCookiePair)
        }
      assertEquals(HttpStatusCode.Unauthorized, oldMeResponse.status)

      // The new cookie should be valid
      val newMeResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, newCookiePair)
        }
      assertEquals(HttpStatusCode.OK, newMeResponse.status)
    }

  @Test
  fun `malformed credentials returns 401`() =
    runBlocking {
      val email = uniqueEmail()
      val password = "Password123!"
      val req = RegisterRequest(email, password, "Login Bad Pwd User")

      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }

      val loginReq =
        ed.unicoach.rest.models
          .LoginRequest(email, "WrongPassword123")
      val loginResponse =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(loginReq))
        }

      assertEquals(HttpStatusCode.Unauthorized, loginResponse.status)
      val body = loginResponse.bodyAsText()
      assertTrue(body.contains("unauthorized"))
    }

  /**
   * The other half of RFC 224's deliberate 30/7 split, asserted where the mint
   * actually happens.
   *
   * `public-web` mints 30-day sessions; `rest-server` must still mint 7-day
   * ones, because lengthening every iOS session to match the web would widen the
   * window in which a stolen token is still good — a security change nobody
   * asked for. The assertion reads the `sessions` ROW the login inserted rather
   * than a configuration value, so a wiring change that pointed this server at
   * `publicWeb.session.expiration` fails here.
   */
  @Test
  fun `a REST login mints a session that expires seven days out, not thirty`() =
    runBlocking {
      val email = uniqueEmail()
      val password = "Password123!"
      val req = RegisterRequest(email, password, "Seven Day Session User")

      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }

      val loginReq =
        ed.unicoach.rest.models
          .LoginRequest(email, password)
      val loginResponse =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(loginReq))
        }
      assertEquals(HttpStatusCode.OK, loginResponse.status)

      val lifetimeSeconds = newestSessionLifetimeSeconds(email)
      assertTrue(
        lifetimeSeconds in (IOS_SESSION_LIFETIME - CLOCK_SLACK).seconds..IOS_SESSION_LIFETIME.seconds,
        "the login minted a [$lifetimeSeconds]s session, not the [${IOS_SESSION_LIFETIME.seconds}]s iOS lifetime: " +
          "the web tier's 30-day lifetime has leaked into the API",
      )
    }

  /**
   * How many whole seconds from now the most recently minted session row for
   * [email] expires. Measured against `NOW()` after the insert, so it is always
   * a little under the nominal figure.
   */
  private fun newestSessionLifetimeSeconds(email: String): Long {
    val config =
      ed.unicoach.common.config.AppConfig
        .load("common.conf", "db.conf")
        .getOrThrow()
    val dbConfig =
      ed.unicoach.db.DatabaseConfig
        .from(config)
        .getOrThrow()
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use { connection ->
      connection
        .prepareStatement(
          """
          SELECT extract(epoch FROM (s.expires_at - NOW()))::bigint
          FROM sessions s
          JOIN users u ON u.id = s.user_id
          WHERE u.email = ? AND s.is_revoked = false
          ORDER BY s.created_at DESC
          LIMIT 1
          """.trimIndent(),
        ).use { statement ->
          statement.setString(1, email)
          statement.executeQuery().use { rows ->
            check(rows.next()) { "the login left no live session row for [$email]" }
            return rows.getLong(1)
          }
        }
    }
  }

  // --- /logout endpoint tests ---

  @Test
  fun `logout with valid cookie returns 204 and clears cookie`() =
    runBlocking {
      val email = uniqueEmail()
      val req = RegisterRequest(email, "Password123!", "Logout Test User")

      val registerResponse =
        client.post(buildUrl("/api/v1/auth/register")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(req))
        }
      assertEquals(HttpStatusCode.Created, registerResponse.status)

      val setCookie = registerResponse.headers[HttpHeaders.SetCookie]
      assertTrue(setCookie != null)
      val cookiePair = setCookie.split(";").first().trim()

      val logoutResponse =
        client.post(buildUrl("/api/v1/auth/logout")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
      val clearedCookie = logoutResponse.headers[HttpHeaders.SetCookie]
      assertTrue(clearedCookie != null)
      assertTrue(clearedCookie.contains("Max-Age=0") || clearedCookie.contains("max-age=0"))

      val meResponse =
        client.get(buildUrl("/api/v1/auth/me")) {
          header(HttpHeaders.Cookie, cookiePair)
        }
      assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }

  @Test
  fun `logout without cookie returns 204`() =
    runBlocking {
      val logoutResponse = client.post(buildUrl("/api/v1/auth/logout"))
      assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
    }

  @Test
  fun `logout with invalid cookie returns 204`() =
    runBlocking {
      val logoutResponse =
        client.post(buildUrl("/api/v1/auth/logout")) {
          header(HttpHeaders.Cookie, "UNICOACH_SESSION=garbage-token-value")
        }
      assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
    }

  @Test
  fun `GET to logout returns 405`() =
    runBlocking {
      val logoutResponse = client.get(buildUrl("/api/v1/auth/logout"))
      assertEquals(HttpStatusCode.MethodNotAllowed, logoutResponse.status)
    }
}
