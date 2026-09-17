package ed.unicoach.web

import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.AuthenticatedSession
import ed.unicoach.auth.DisabledIdTokenVerifier
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.LoginResult
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Shared scaffolding for `public-web`'s RFC 224 suites: the real test database,
 * a real [AuthService], and the REAL parsed configuration.
 *
 * "Real config" is load-bearing rather than tidiness. The slice's headline claim
 * is that a session minted by `rest-server` and a session minted by the web are
 * the same row behind the same cookie, and that claim is made by CONFIGURATION —
 * the shared `session.conf` block — not by any Kotlin type. A suite that invented
 * its own cookie name and its own origin would pass while the packaged config
 * said something else entirely.
 *
 * The `AuthService` is the production one, wired exactly as `Application.kt`
 * wires it, so these suites exercise the real login, registration, logout and
 * change-email paths rather than a fake that agrees with the test.
 */
object WebAuthTestSupport {
  /** The SERVER's own list, splatted — not a copy of it. See [PUBLIC_WEB_CONF_RESOURCES]. */
  val config = AppConfig.load(*PUBLIC_WEB_CONF_RESOURCES).getOrThrow()

  private val dbConfig = DatabaseConfig.from(config).getOrThrow()

  val publicWebConfig: PublicWebConfig = PublicWebConfig.from(config).getOrThrow()

  /** The one origin the same-origin guard compares against; the value the server really reads. */
  val urlBase: String get() = publicWebConfig.origin.url

  val sessionConfig get() = publicWebConfig.session

  val database = Database(dbConfig)

  private val argon2Hasher = Argon2Hasher()
  private val tokenGenerator = TokenGenerator()

  val emailVerificationService =
    EmailVerificationService(
      database,
      QueueService(database),
      tokenGenerator,
      EmailVerificationConfig.from(config).getOrThrow(),
    )

  val authService =
    AuthService(
      database,
      argon2Hasher,
      tokenGenerator,
      emailVerificationService,
      GoogleIdTokenVerifier(DisabledIdTokenVerifier),
      AppleIdTokenVerifier(DisabledIdTokenVerifier),
    )

  /** Truncates users (cascading to sessions) and the job queue, for an isolated test. */
  fun resetDatabase() {
    withConnection { connection ->
      connection.createStatement().use { it.execute("TRUNCATE TABLE users, jobs CASCADE") }
    }
  }

  fun uniqueEmail(): String = "web-auth-${UUID.randomUUID()}@example.com"

  /**
   * Seeds a user directly through the DAO.
   *
   * [verified] defaults to true because most suites are about something other
   * than verification; the gate suite passes false and asserts the hold screen.
   */
  fun seedUser(
    email: String,
    password: String = DEFAULT_PASSWORD,
    name: String = "Web Test User",
    verified: Boolean = true,
  ): User {
    val hash = runBlocking { argon2Hasher.hash(password) }
    val created = insertUser(email, name, (PasswordHash.create(hash) as ValidationResult.Valid).value)

    if (!verified) return created

    // Verified through SQL rather than a DAO because nothing in the tree marks
    // a user verified except consuming a token, and seeding a token to burn it
    // would make every suite depend on the verification flow it is not testing.
    execute("UPDATE users SET email_verified_at = NOW(), version = version + 1 WHERE id = ?::uuid") { statement ->
      statement.setString(1, created.id.value.toString())
    }
    return created.copy(emailVerifiedAt = Instant.now())
  }

  /**
   * The one `NewUser` construction both seed helpers use.
   *
   * Private and shared because the DAO call and the three `ValidationResult`
   * casts are the part that changes when `NewUser` gains a required field, and
   * two copies meant two edits — the second of which is easy to miss, because
   * the suite that needs it runs one assertion.
   */
  private fun insertUser(
    email: String,
    name: String,
    passwordHash: PasswordHash?,
  ): User =
    runBlocking {
      database
        .withConnection { session ->
          UsersDao.create(
            session,
            NewUser(
              email = (EmailAddress.create(email) as ValidationResult.Valid).value,
              name = (PersonName.create(name) as ValidationResult.Valid).value,
              displayName = null,
              passwordHash = passwordHash,
            ),
          )
        }.getOrThrow()
    }

  /**
   * A user with NO password hash — an SSO-only account.
   *
   * It exists for one assertion: `LoginResult.PasswordNotSet` is one of the four
   * failure branches the sign-in form must not distinguish, and it is the only
   * one that cannot be produced by typing something wrong.
   */
  fun seedPasswordlessUser(
    email: String,
    name: String = "SSO Only User",
  ): User = insertUser(email, name, passwordHash = null)

  /**
   * A session minted the way `rest-server` mints one: the same [AuthService],
   * through the same `login`. Used by the session-sharing test, which is the
   * slice's headline claim — the cookie this returns must resolve on a web page.
   */
  fun login(
    email: String,
    password: String = DEFAULT_PASSWORD,
  ): String =
    runBlocking {
      val result =
        authService
          .login(
            email = email,
            password = password,
            oldCookieToken = null,
            sessionExpiration = sessionConfig.expiration,
            userAgent = "test",
            initialIp = "127.0.0.1",
          ).getOrThrow()
      (result as LoginResult.Success).token
    }

  /** The `Cookie` request header carrying [token] under the configured cookie name. */
  fun cookieHeader(token: String): String = "${sessionConfig.cookieName}=$token"

  /**
   * The raw session token out of a response's `Set-Cookie` header — exactly the
   * value the browser will send back on the next request.
   *
   * Parsed from the rendered header rather than taken from any server-side
   * variable, because the claim being tested is about what the BROWSER was
   * handed. Ktor's own `Set-Cookie` parser is used rather than splitting on `;`,
   * because the value's encoding is part of the header's grammar: a hand-split
   * returns the ENCODED text and the decoding bug then reports itself as a
   * session that does not resolve.
   */
  fun tokenFromSetCookie(setCookie: String): String {
    val cookie = parseServerSetCookieHeader(setCookie)
    check(cookie.name == sessionConfig.cookieName) {
      "the response set no [${sessionConfig.cookieName}] cookie; got [$setCookie]"
    }
    return cookie.value
  }

  /**
   * [token] resolved through the production [AuthService] — the SAME call the
   * iOS app's requests make through `rest-server`'s gate, hashed the way
   * `resolveWebCaller` hashes it.
   */
  fun resolveSession(token: String): AuthenticatedSession? =
    runBlocking {
      authService.resolveSession(TokenHash.fromRawToken(token)).getOrThrow()
    }

  /** How many `sessions` rows exist at all — the D12 honesty test's whole assertion. */
  fun sessionRowCount(): Int = queryInt("SELECT count(*) FROM sessions")

  /** How many LIVE sessions the given user has. */
  fun liveSessionCount(userId: UUID): Int =
    queryInt(
      "SELECT count(*) FROM sessions WHERE user_id = '$userId'::uuid AND is_revoked = false AND expires_at > NOW()",
    )

  /** The metadata column of the single session row, or null. Used by the D12 honesty test. */
  fun soleSessionMetadata(): String? = soleString("SELECT metadata FROM sessions")

  /**
   * How many whole seconds from now the single live session row expires.
   *
   * Read as a NUMBER rather than compared to a literal timestamp, because the
   * assertion is that the row and the cookie's `Max-Age` agree — and the cookie
   * can only say a number of seconds.
   */
  fun soleSessionLifetimeSeconds(): Long =
    soleLong(
      "SELECT extract(epoch FROM (expires_at - NOW()))::bigint FROM sessions WHERE is_revoked = false",
    )

  /** How many queued jobs of [type] exist — registration must enqueue exactly one SEND_EMAIL. */
  fun jobCount(type: String): Int = queryInt("SELECT count(*) FROM jobs WHERE job_type = '$type'")

  const val DEFAULT_PASSWORD = "Password123!"

  /**
   * The ONE way this fixture reaches the test database.
   *
   * The connect/credential dance was written out five times in this file, so a
   * change to how the suite reaches the DB was five edits. Only the SQL differs
   * between the callers, so only the SQL is theirs.
   */
  private fun <T> withConnection(block: (Connection) -> T): T =
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use(block)

  /** The single `String` column of the one row [sql] must return. */
  private fun soleString(sql: String): String? = soleRow(sql) { it.getString(1) }

  /** The single `Long` column of the one row [sql] must return. */
  private fun soleLong(sql: String): Long = soleRow(sql) { it.getLong(1) }

  /**
   * The one column of the one row [sql] must return; fails loudly on none or
   * several. [read] must EXTRACT a value — the cursor it is handed is closed
   * when this returns, so a lambda that returns the `ResultSet` returns a
   * corpse.
   */
  private fun <T> soleRow(
    sql: String,
    read: (ResultSet) -> T,
  ): T =
    withConnection { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
          check(rows.next()) { "expected exactly one row from [$sql] and found none" }
          val value = read(rows)
          check(!rows.next()) { "expected exactly one row from [$sql] and found several" }
          value
        }
      }
    }

  private fun queryInt(sql: String): Int =
    withConnection { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
          rows.next()
          rows.getInt(1)
        }
      }
    }

  private fun execute(
    sql: String,
    bind: (PreparedStatement) -> Unit,
  ) = withConnection { connection ->
    connection.prepareStatement(sql).use { statement ->
      bind(statement)
      statement.executeUpdate()
    }
  }
}

/**
 * A same-origin form POST: the shape every state-changing request in these
 * suites has.
 *
 * The `Origin` header is PART of that shape, not an extra: the same-origin guard
 * refuses a request without one, so a call site that forgets it is testing the
 * guard rather than the route it named. Five suites each hand-rolled this, and
 * one of them grew the header by hand at nine separate call sites — which is
 * what a missing helper costs the next time the request shape changes.
 *
 * [form] is `Parameters` rather than a pre-encoded string: encoding
 * `application/x-www-form-urlencoded` by hand escapes only the characters
 * whoever wrote the call site happened to type, so a value carrying `&`, `+` or
 * a non-ASCII letter posts something other than what the test names.
 * [FormDataContent] is Ktor's encoder and sets the content type itself.
 *
 * `SameOriginGuardTest` deliberately does NOT use it: that suite varies the
 * headers this one fixes.
 *
 * @param client the client to post with — a suite asserting WHERE a caller was
 *   sent passes [noFollowClient].
 * @param extra anything this one request adds, such as a `User-Agent`.
 */
suspend fun ApplicationTestBuilder.formPost(
  path: String,
  form: Parameters = Parameters.Empty,
  sessionToken: String? = null,
  client: HttpClient = this.client,
  extra: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
  client.post(path) {
    header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
    sessionToken?.let { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(it)) }
    setBody(FormDataContent(form))
    extra()
  }

/**
 * A client that does NOT follow redirects.
 *
 * `testApplication`'s default client follows a `GET` redirect, so an assertion
 * written against `client.get("/account")` sees the `200` of the sign-in form it
 * was sent to and cannot tell that from the account page rendering for a
 * signed-out visitor — the exact failure the gate exists to prevent, reported as
 * a pass. Every suite that asserts WHERE a caller was sent uses this.
 */
fun ApplicationTestBuilder.noFollowClient() = createClient { followRedirects = false }
