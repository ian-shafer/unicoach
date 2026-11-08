package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AccountToken
import ed.unicoach.db.models.AccountTokenType
import ed.unicoach.db.models.NewAccountToken
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserEdit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountTokensDaoTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var dbConfig: ed.unicoach.db.DatabaseConfig

    private val TYPE = AccountTokenType.PASSWORD_RESET

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      dbConfig =
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

  private fun address(local: String): EmailAddress = (EmailAddress.create("$local@example.com") as ValidationResult.Valid).value

  private fun createUser(): User {
    val name = (PersonName.create("AT User") as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(session, NewUser(email = address("at-user-${userCounter++}"), name = name, displayName = null, passwordHash = pass))
      .getOrThrow()
  }

  private fun hashOf(raw: String): TokenHash = TokenHash.fromRawToken(raw)

  private fun future(): Instant = Instant.now().plus(1, ChronoUnit.DAYS)

  private fun past(): Instant = Instant.now().minus(1, ChronoUnit.DAYS)

  private fun issue(
    user: User,
    raw: String,
    expiresAt: Instant = future(),
  ): AccountToken =
    AccountTokensDao
      .create(session, NewAccountToken(user.id, TYPE, user.version, hashOf(raw), expiresAt))
      .getOrThrow()

  private fun assertNoClaim(raw: String) {
    assertIs<NotFoundException>(AccountTokensDao.claim(session, TYPE, hashOf(raw)).exceptionOrNull())
  }

  @Test
  fun `create records the type and the version the link was mailed under`() {
    val user = createUser()

    val token = issue(user, "created")

    assertEquals(user.id, token.userId)
    assertEquals(TYPE, token.type)
    assertEquals(user.version, token.userVersion)
    assertNull(token.consumedAt)
  }

  @Test
  fun `claim succeeds once and a second claim is no row`() {
    val user = createUser()
    issue(user, "once")

    val claimed = AccountTokensDao.claim(session, TYPE, hashOf("once")).getOrThrow()

    assertNotNull(claimed.consumedAt)
    assertNoClaim("once")
  }

  @Test
  fun `an expired token is no row`() {
    issue(createUser(), "expired", past())

    assertNoClaim("expired")
  }

  @Test
  fun `an unknown hash is no row`() {
    assertNoClaim("never-issued")
  }

  @Test
  fun `a changed users email is no row`() {
    val user = createUser()
    issue(user, "moved")
    UsersDao.changeEmail(session, user.id, address("somewhere-else")).getOrThrow()

    assertNoClaim("moved")
  }

  @Test
  fun `a rename which bumps the version and keeps the address still claims`() {
    val user = createUser()
    issue(user, "renamed")
    val renamed = (PersonName.create("A New Name") as ValidationResult.Valid).value
    val updated =
      UsersDao
        .update(session, UserEdit(user.id, user.version, user.email, renamed, user.displayName, user.isAdmin))
        .getOrThrow()
    assertEquals(user.version + 1, updated.version)

    assertNotNull(AccountTokensDao.claim(session, TYPE, hashOf("renamed")).getOrThrow().consumedAt)
  }

  @Test
  fun `a soft-deleted user is no row`() {
    val user = createUser()
    issue(user, "closed")
    UsersDao.delete(session, user.id, user.version).getOrThrow()

    assertNoClaim("closed")
  }

  @Test
  fun `a version row that does not exist is no row`() {
    val user = createUser()
    AccountTokensDao
      .create(session, NewAccountToken(user.id, TYPE, user.version + 99, hashOf("no-version"), future()))
      .getOrThrow()

    assertNoClaim("no-version")
  }

  private fun openConnection(): Connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

  /** True once a backend of this database is waiting on a lock; false if [claim] finished without waiting. */
  private fun blockedBeforeFinishing(
    observer: Connection,
    claim: CompletableFuture<*>,
  ): Boolean {
    val deadline = Instant.now().plusSeconds(10)
    while (Instant.now().isBefore(deadline) && !claim.isDone) {
      val waiting =
        RawSql.count(
          observer,
          "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
        )
      if (waiting > 0) return true
      Thread.sleep(10)
    }
    return false
  }

  /**
   * The race the lock closes: an address change in flight when the claim runs.
   * Without the lock the claim reads the old address and wins, and the reset goes
   * on to mark the NEW address verified.
   */
  @Test
  fun `a claim waits for an address change in flight and then sees the new address`() {
    val user = createUser()
    issue(user, "racing")
    openConnection().use { observer ->
      openConnection().use { writer ->
        writer.autoCommit = false
        val writerSession =
          object : SqlSession {
            override fun prepareStatement(sql: String): PreparedStatement = writer.prepareStatement(sql)
          }
        UsersDao.changeEmail(writerSession, user.id, address("moved-mid-claim")).getOrThrow()

        val claim = CompletableFuture.supplyAsync { AccountTokensDao.claim(session, TYPE, hashOf("racing")) }

        assertEquals(true, blockedBeforeFinishing(observer, claim), "the claim did not wait for the address change")
        writer.commit()
        assertIs<NotFoundException>(claim.get(10, TimeUnit.SECONDS).exceptionOrNull())
      }
    }
  }

  @Test
  fun `findSpendable answers as a claim would and changes nothing`() {
    val user = createUser()
    issue(user, "peek")
    issue(user, "peek-expired", past())

    assertNull(AccountTokensDao.findSpendable(session, TYPE, hashOf("peek")).getOrThrow().consumedAt)
    assertIs<NotFoundException>(AccountTokensDao.findSpendable(session, TYPE, hashOf("peek-expired")).exceptionOrNull())
    assertNotNull(AccountTokensDao.claim(session, TYPE, hashOf("peek")).getOrThrow().consumedAt)
    assertIs<NotFoundException>(AccountTokensDao.findSpendable(session, TYPE, hashOf("peek")).exceptionOrNull())
  }

  @Test
  fun `findByTokenHash returns rows in any state and fails for an unknown hash`() {
    val user = createUser()
    issue(user, "consumed")
    AccountTokensDao.claim(session, TYPE, hashOf("consumed")).getOrThrow()
    issue(user, "stale", past())

    assertNotNull(AccountTokensDao.findByTokenHash(session, TYPE, hashOf("consumed")).getOrThrow().consumedAt)
    assertNull(AccountTokensDao.findByTokenHash(session, TYPE, hashOf("stale")).getOrThrow().consumedAt)
    assertIs<NotFoundException>(AccountTokensDao.findByTokenHash(session, TYPE, hashOf("unknown")).exceptionOrNull())
  }

  @Test
  fun `consumeAllForUser burns live tokens, leaves consumed rows alone and touches no other account`() {
    val user = createUser()
    val other = createUser()
    issue(user, "a")
    issue(user, "b")
    issue(user, "already")
    issue(other, "other")
    val alreadyAt = AccountTokensDao.claim(session, TYPE, hashOf("already")).getOrThrow().consumedAt

    assertEquals(2, AccountTokensDao.consumeAllForUser(session, TYPE, user.id).getOrThrow())

    assertNoClaim("a")
    assertNoClaim("b")
    assertEquals(alreadyAt, AccountTokensDao.findByTokenHash(session, TYPE, hashOf("already")).getOrThrow().consumedAt)
    assertNotNull(AccountTokensDao.claim(session, TYPE, hashOf("other")).getOrThrow().consumedAt)
  }

  @Test
  fun `fromValue round-trips every entry against the CHECK`() {
    val user = createUser()
    for (type in AccountTokenType.entries) {
      val stored =
        AccountTokensDao
          .create(session, NewAccountToken(user.id, type, user.version, hashOf("type-${type.value}"), future()))
          .getOrThrow()

      assertEquals(type, stored.type)
      assertEquals(type, AccountTokenType.fromValue(type.value))
    }
    assertNull(AccountTokenType.fromValue("not-a-type"))
  }
}
