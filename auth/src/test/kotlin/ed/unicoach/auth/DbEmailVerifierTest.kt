package ed.unicoach.auth

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertTrue

/**
 * The verify-outcome matrix, relocated from `EmailVerificationServiceTest` and
 * run against a real Postgres via the `bin/test` harness. `verification_tokens`
 * stores only the SHA-256 hash, so each case inserts a known raw token (and its
 * hash) for a registered user, then calls [DbEmailVerifier.verify].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DbEmailVerifierTest {
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

  private fun verifier(): DbEmailVerifier = DbEmailVerifier(database)

  private var userCounter = 0

  private fun createUser(): User {
    val local = "dev-${userCounter++}"
    val email = (EmailAddress.create("$local@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("DEV User") as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(sqlSession, NewUser(email = email, name = name, displayName = null, passwordHash = pass))
      .getOrThrow()
  }

  private fun seedToken(
    user: User,
    raw: String,
    expiresAt: Instant,
  ) {
    VerificationTokensDao
      .create(sqlSession, NewVerificationToken(user.id, TokenHash.fromRawToken(raw), expiresAt))
      .getOrThrow()
  }

  @Test
  fun `verify happy path marks the user verified and burns sibling tokens`() =
    runTest {
      val user = createUser()
      val raw = "fresh-raw"
      seedToken(user, raw, Instant.now().plus(1, ChronoUnit.DAYS))
      // A sibling outstanding token that must be burned by verify.
      val siblingHash = TokenHash.fromRawToken("sibling-raw")
      VerificationTokensDao
        .create(sqlSession, NewVerificationToken(user.id, siblingHash, Instant.now().plus(1, ChronoUnit.DAYS)))
        .getOrThrow()

      val result = verifier().verify(raw).getOrThrow()
      assertTrue(result is VerifyEmailResult.Success, "Expected Success, got $result")
      assertTrue(result.user.emailVerifiedAt != null)

      val sibling = VerificationTokensDao.findByTokenHash(sqlSession, siblingHash).getOrThrow()
      assertTrue(sibling.consumedAt != null, "Sibling token must be burned")
    }

  @Test
  fun `verify with an unknown token yields InvalidToken`() =
    runTest {
      val result = verifier().verify("never-issued").getOrThrow()
      assertTrue(result is VerifyEmailResult.InvalidToken, "Expected InvalidToken, got $result")
    }

  @Test
  fun `verify with an expired token yields Expired`() =
    runTest {
      val user = createUser()
      val raw = "expired-raw"
      seedToken(user, raw, Instant.now().minus(1, ChronoUnit.DAYS))

      val result = verifier().verify(raw).getOrThrow()
      assertTrue(result is VerifyEmailResult.Expired, "Expected Expired, got $result")
    }

  @Test
  fun `verify with a consumed token yields AlreadyConsumed`() =
    runTest {
      val user = createUser()
      val raw = "consumed-raw"
      seedToken(user, raw, Instant.now().plus(1, ChronoUnit.DAYS))

      val first = verifier().verify(raw).getOrThrow()
      assertTrue(first is VerifyEmailResult.Success, "Expected Success on first verify, got $first")

      val second = verifier().verify(raw).getOrThrow()
      assertTrue(second is VerifyEmailResult.AlreadyConsumed, "Expected AlreadyConsumed, got $second")
    }
}
