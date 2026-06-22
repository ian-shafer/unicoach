package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerificationTokensDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
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
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var userCounter = 0

  private fun createUser(): User {
    val local = "vt-user-${userCounter++}"
    val email = (EmailAddress.create("$local@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("VT User") as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(
        session,
        NewUser(email = email, name = name, displayName = null, passwordHash = pass),
      ).getOrThrow()
  }

  private fun hashOf(raw: String): TokenHash = TokenHash.fromRawToken(raw)

  private fun future(): Instant = Instant.now().plus(1, ChronoUnit.DAYS)

  private fun past(): Instant = Instant.now().minus(1, ChronoUnit.DAYS)

  @Test
  fun `create inserts a row with null consumedAt and the supplied expiresAt`() {
    val user = createUser()
    val expiry = future()
    val token =
      VerificationTokensDao
        .create(session, NewVerificationToken(user.id, hashOf("raw-1"), expiry))
        .getOrThrow()

    assertTrue(token.consumedAt == null, "Fresh token must be unconsumed")
    assertEquals(user.id, token.userId)
    // Round to seconds to tolerate sub-second JDBC timestamp differences.
    assertEquals(expiry.truncatedTo(ChronoUnit.SECONDS), token.expiresAt.truncatedTo(ChronoUnit.SECONDS))
  }

  @Test
  fun `create enforces the unique token_hash index`() {
    val user = createUser()
    val hash = hashOf("dup-hash")
    val first = VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, future()))
    assertTrue(first.isSuccess)

    val second = VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, future()))
    assertTrue(second.isFailure, "Second insert of the same hash must violate the unique index")
    // The default error mapper folds a 23505 unique violation into DatabaseException.
    assertTrue(second.exceptionOrNull() is DatabaseException, "Expected DatabaseException, got $second")
  }

  @Test
  fun `consume on a fresh unexpired token returns the row and stamps consumed_at`() {
    val user = createUser()
    val hash = hashOf("consume-fresh")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, future())).getOrThrow()

    val consumed = VerificationTokensDao.consume(session, hash).getOrThrow()
    assertTrue(consumed.consumedAt != null, "Consume must stamp consumed_at")

    val reread = VerificationTokensDao.findByTokenHash(session, hash).getOrThrow()
    assertTrue(reread.consumedAt != null, "Persisted row must carry consumed_at")
  }

  @Test
  fun `consume on an already-consumed token returns NotFound`() {
    val user = createUser()
    val hash = hashOf("consume-twice")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, future())).getOrThrow()

    val first = VerificationTokensDao.consume(session, hash)
    assertTrue(first.isSuccess)
    val second = VerificationTokensDao.consume(session, hash)
    assertTrue(second.isFailure && second.exceptionOrNull() is NotFoundException, "Second consume must be NotFound, got $second")
  }

  @Test
  fun `consume on an expired token returns NotFound`() {
    val user = createUser()
    val hash = hashOf("consume-expired")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, past())).getOrThrow()

    val result = VerificationTokensDao.consume(session, hash)
    assertTrue(result.isFailure && result.exceptionOrNull() is NotFoundException, "Expired consume must be NotFound, got $result")
  }

  @Test
  fun `consume is single-use across two sequential calls`() {
    val user = createUser()
    val hash = hashOf("single-use")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hash, future())).getOrThrow()

    val first = VerificationTokensDao.consume(session, hash)
    val second = VerificationTokensDao.consume(session, hash)
    assertTrue(first.isSuccess, "First consume should succeed")
    assertTrue(second.isFailure, "Second consume should fail")
  }

  @Test
  fun `findByTokenHash returns rows regardless of state and fails for an unknown hash`() {
    val user = createUser()

    val consumedHash = hashOf("find-consumed")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, consumedHash, future())).getOrThrow()
    VerificationTokensDao.consume(session, consumedHash).getOrThrow()
    assertTrue(VerificationTokensDao.findByTokenHash(session, consumedHash).isSuccess, "Consumed token must be findable")

    val expiredHash = hashOf("find-expired")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, expiredHash, past())).getOrThrow()
    assertTrue(VerificationTokensDao.findByTokenHash(session, expiredHash).isSuccess, "Expired token must be findable")

    val unknown = VerificationTokensDao.findByTokenHash(session, hashOf("never-inserted"))
    assertTrue(unknown.isFailure && unknown.exceptionOrNull() is NotFoundException, "Unknown hash must be NotFound, got $unknown")
  }

  @Test
  fun `consumeAllForUser stamps unconsumed tokens and returns the count leaving consumed untouched`() {
    val user = createUser()
    val hashA = hashOf("all-a")
    val hashB = hashOf("all-b")
    val hashC = hashOf("all-c")
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hashA, future())).getOrThrow()
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hashB, future())).getOrThrow()
    VerificationTokensDao.create(session, NewVerificationToken(user.id, hashC, future())).getOrThrow()

    // Pre-consume one; consumeAllForUser must leave it untouched and count the rest.
    val preConsumed = VerificationTokensDao.consume(session, hashC).getOrThrow()

    val count = VerificationTokensDao.consumeAllForUser(session, user.id).getOrThrow()
    assertEquals(2, count, "Only the two still-unconsumed tokens should be affected")

    val rereadC = VerificationTokensDao.findByTokenHash(session, hashC).getOrThrow()
    assertEquals(
      preConsumed.consumedAt!!.truncatedTo(ChronoUnit.SECONDS),
      rereadC.consumedAt!!.truncatedTo(ChronoUnit.SECONDS),
      "Already-consumed row must keep its original consumed_at",
    )
  }
}
