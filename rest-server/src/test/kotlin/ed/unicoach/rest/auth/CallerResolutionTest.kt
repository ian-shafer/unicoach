package ed.unicoach.rest.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.StubGoogleTokenVerifier
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallerResolutionTest {
  companion object {
    private lateinit var config: com.typesafe.config.Config
    private lateinit var database: Database

    private val sessionConfig =
      SessionConfig(
        expiration = Duration.ofDays(7),
        cookieName = "test_session",
        cookieDomain = "localhost",
        cookieSecure = false,
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "email.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  private fun authServiceOver(db: Database): AuthService {
    val emailConfig =
      ed.unicoach.email.EmailConfig
        .from(config)
        .getOrThrow()
    val provider =
      ed.unicoach.email.EmailProviderFactory
        .fromConfig(emailConfig)
        .getOrThrow()
    val emailService = ed.unicoach.email.EmailService(db, provider, emailConfig)
    val evConfig =
      ed.unicoach.auth.EmailVerificationConfig
        .from(config)
        .getOrThrow()
    val evService = ed.unicoach.auth.EmailVerificationService(db, emailService, TokenGenerator(), evConfig)
    return AuthService(db, Argon2Hasher(), TokenGenerator(), evService, StubGoogleTokenVerifier())
  }

  private fun freshClosedDatabase(): Database {
    val db = Database(DatabaseConfig.from(config).getOrThrow())
    db.close()
    return db
  }

  /** Seeds a user and a live session for [rawToken], returning the seeded caller. */
  private fun seedCaller(rawToken: String): Pair<Session, User> =
    runBlocking {
      database.withConnection { session ->
        val email =
          (EmailAddress.create("caller${java.util.UUID.randomUUID()}@example.com") as ValidationResult.Valid).value
        val name = (PersonName.create("Caller User") as ValidationResult.Valid).value
        val pwd =
          (
            PasswordHash.create(
              "\$argon2id\$v=19\$m=65536,t=3,p=1\$dummysalt\$dummyhash",
            ) as ValidationResult.Valid
          ).value
        val user =
          UsersDao
            .create(session, NewUser(email = email, name = name, displayName = null, passwordHash = pwd))
            .getOrThrow()
        val tokenHash = TokenHash.fromRawToken(rawToken)
        val sessionRow =
          SessionsDao
            .create(
              session,
              NewSession(
                userId = user.id,
                tokenHash = tokenHash,
                userAgent = "test",
                initialIp = "127.0.0.1",
                metadata = null,
                expiration = Duration.ofDays(7),
                loginMethod = LoginMethod.PASSWORD,
              ),
            ).getOrThrow()
        sessionRow to user
      }
    }

  @Test
  fun `cache hit short-circuits resolution`() =
    testApplication {
      val (seededSession, seededUser) = seedCaller("cache-hit-token")
      val cached = ResolvedCaller(TokenHash.fromRawToken("cache-hit-token"), seededSession, seededUser)
      val closedPoolAuth = authServiceOver(freshClosedDatabase())

      routing {
        get("/probe") {
          call.attributes.put(ResolvedCallerKey, cached)
          // Backed by a closed pool: a real lookup would throw. The cache hit must
          // short-circuit so this returns the cached user without re-resolving.
          val resolved = call.resolveCaller(closedPoolAuth, sessionConfig)
          call.respondText(
            resolved
              ?.user
              ?.id
              ?.value
              ?.toString() ?: "null",
          )
        }
      }

      val response = client.get("/probe")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(seededUser.id.value.toString(), response.bodyAsText())
    }

  @Test
  fun `miss resolves and caches`() =
    testApplication {
      seedCaller("miss-token")
      val auth = authServiceOver(database)

      routing {
        get("/probe") {
          val resolved = call.resolveCaller(auth, sessionConfig)
          val present = call.attributes.contains(ResolvedCallerKey)
          call.respondText("${resolved != null}:$present")
        }
      }

      val response =
        client.get("/probe") {
          header(HttpHeaders.Cookie, "test_session=miss-token")
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("true:true", response.bodyAsText())
    }

  @Test
  fun `reuse within a call returns the same value even after the pool closes`() =
    testApplication {
      seedCaller("reuse-token")
      // A dedicated Database this test owns and may close, without poisoning the
      // shared one used by the other cases.
      val ownDb = Database(DatabaseConfig.from(config).getOrThrow())
      val auth = authServiceOver(ownDb)

      routing {
        get("/probe") {
          val first = call.resolveCaller(auth, sessionConfig)
          // The second call must read the cache, unaffected by a closed pool.
          ownDb.close()
          val second = call.resolveCaller(auth, sessionConfig)
          call.respondText("${first?.user?.id?.value == second?.user?.id?.value}")
        }
      }

      val response =
        client.get("/probe") {
          header(HttpHeaders.Cookie, "test_session=reuse-token")
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("true", response.bodyAsText())
    }

  @Test
  fun `no cookie returns null and caches nothing`() =
    testApplication {
      val auth = authServiceOver(database)

      routing {
        get("/probe") {
          val resolved = call.resolveCaller(auth, sessionConfig)
          val present = call.attributes.contains(ResolvedCallerKey)
          call.respondText("${resolved == null}:$present")
        }
      }

      val response = client.get("/probe")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("true:false", response.bodyAsText())
    }

  @Test
  fun `present but invalid cookie caches nothing`() =
    testApplication {
      val auth = authServiceOver(database)

      routing {
        get("/probe") {
          val resolved = call.resolveCaller(auth, sessionConfig)
          val present = call.attributes.contains(ResolvedCallerKey)
          call.respondText("${resolved == null}:$present")
        }
      }

      val response =
        client.get("/probe") {
          header(HttpHeaders.Cookie, "test_session=no-such-session-token")
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.startsWith("true"), "resolveCaller must return null for an unmatched cookie")
      assertEquals("true:false", body, "ResolvedCallerKey must be absent after a null resolution")
    }
}
