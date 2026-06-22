package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.NewAuthIdentity
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserAuthIdentitiesDaoTest {
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

  private fun createUser(): UserId {
    val rawId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$rawId', 'ident-$rawId@test.com', 'Ident User', 'ahash')",
      )
    }
    return UserId(rawId)
  }

  private fun subject(value: String): ProviderSubject = (ProviderSubject.create(value) as ValidationResult.Valid).value

  private fun email(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun newIdentity(
    userId: UserId,
    subject: String,
    emailStr: String = "ident-$subject@test.com",
    emailVerified: Boolean = true,
  ) = NewAuthIdentity(
    userId = userId,
    provider = AuthProvider.GOOGLE,
    subject = subject(subject),
    email = email(emailStr),
    emailVerified = emailVerified,
  )

  @Test
  fun `create inserts and returns a populated identity`() {
    val user = createUser()
    val created = UserAuthIdentitiesDao.create(session, newIdentity(user, "sub-1")).getOrThrow()

    assertEquals(user, created.userId)
    assertEquals(AuthProvider.GOOGLE, created.provider)
    assertEquals("sub-1", created.subject.value)
    assertTrue(created.emailVerified)
    assertTrue(
      created.id.value
        .toString()
        .isNotBlank(),
    )
  }

  @Test
  fun `create twice with same provider and subject fails with constraint violation`() {
    val user = createUser()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "dup-sub")).getOrThrow()

    val second = UserAuthIdentitiesDao.create(session, newIdentity(user, "dup-sub"))
    assertTrue(
      second.isFailure && second.exceptionOrNull() is ConstraintViolationException,
      "Expected ConstraintViolationException, got $second",
    )
  }

  @Test
  fun `create allows two distinct subjects for one user`() {
    val user = createUser()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "sub-a")).getOrThrow()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "sub-b")).getOrThrow()

    val all = UserAuthIdentitiesDao.listByUser(session, user).getOrThrow()
    assertEquals(setOf("sub-a", "sub-b"), all.map { it.subject.value }.toSet())
  }

  @Test
  fun `findByProviderAndSubject returns the row when present and NotFound when absent`() {
    val user = createUser()
    val created = UserAuthIdentitiesDao.create(session, newIdentity(user, "find-sub")).getOrThrow()

    val found =
      UserAuthIdentitiesDao
        .findByProviderAndSubject(session, AuthProvider.GOOGLE, subject("find-sub"))
        .getOrThrow()
    assertEquals(created.id, found.id)

    val missing = UserAuthIdentitiesDao.findByProviderAndSubject(session, AuthProvider.GOOGLE, subject("nope"))
    assertTrue(missing.isFailure && missing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `findByProviderAndSubject is unaffected by users soft-delete`() {
    val user = createUser()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "softdel-sub")).getOrThrow()

    // Soft-delete through the versioned DAO path (a raw UPDATE trips OCC versioning).
    val loaded = UsersDao.findById(session, user, ed.unicoach.db.models.SoftDeleteScope.ACTIVE).getOrThrow()
    UsersDao.delete(session, user, loaded.version).getOrThrow()

    val found = UserAuthIdentitiesDao.findByProviderAndSubject(session, AuthProvider.GOOGLE, subject("softdel-sub"))
    assertTrue(found.isSuccess, "Identity must persist regardless of the user's soft-delete state")
  }

  @Test
  fun `listByUser returns all for a user and none for an unknown user`() {
    val user = createUser()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "list-1")).getOrThrow()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "list-2")).getOrThrow()

    val mine = UserAuthIdentitiesDao.listByUser(session, user).getOrThrow()
    assertEquals(2, mine.size)

    val none = UserAuthIdentitiesDao.listByUser(session, UserId(UUID.randomUUID())).getOrThrow()
    assertTrue(none.isEmpty())
  }

  @Test
  fun `UPDATE against a row is rejected by the append-only trigger`() {
    val user = createUser()
    val created = UserAuthIdentitiesDao.create(session, newIdentity(user, "noupdate")).getOrThrow()

    val threw =
      try {
        connection.prepareStatement("UPDATE user_auth_identities SET email = 'changed@test.com' WHERE id = ?").use { stmt ->
          stmt.setObject(1, created.id.value)
          stmt.executeUpdate()
        }
        false
      } catch (e: java.sql.SQLException) {
        true
      }
    assertTrue(threw, "Append-only trigger must reject UPDATE")
  }

  @Test
  fun `DELETE against a row is rejected by the append-only trigger`() {
    val user = createUser()
    val created = UserAuthIdentitiesDao.create(session, newIdentity(user, "nodelete")).getOrThrow()

    val threw =
      try {
        connection.prepareStatement("DELETE FROM user_auth_identities WHERE id = ?").use { stmt ->
          stmt.setObject(1, created.id.value)
          stmt.executeUpdate()
        }
        false
      } catch (e: java.sql.SQLException) {
        true
      }
    assertTrue(threw, "Append-only trigger must reject DELETE")
  }

  @Test
  fun `ON DELETE CASCADE removes identities when a user is hard-deleted`() {
    val user = createUser()
    UserAuthIdentitiesDao.create(session, newIdentity(user, "cascade-sub")).getOrThrow()

    // Hard-delete the user directly. Application code never hard-deletes a user
    // (prevent_physical_delete) and never deletes a log row (prevent_log_delete),
    // so the CASCADE is normally inert. To exercise the FK's hypothetical
    // hard-delete path we disable both guards for the duration of the DELETE.
    connection.createStatement().use { it.execute("ALTER TABLE users DISABLE TRIGGER trigger_00_prevent_physical_delete") }
    connection
      .createStatement()
      .use { it.execute("ALTER TABLE user_auth_identities DISABLE TRIGGER trigger_01_prevent_user_auth_identities_delete") }
    try {
      // users_versions references users via ON DELETE RESTRICT, so clear history first.
      connection.prepareStatement("DELETE FROM users_versions WHERE id = ?").use { stmt ->
        stmt.setObject(1, user.value)
        stmt.executeUpdate()
      }
      connection.prepareStatement("DELETE FROM users WHERE id = ?").use { stmt ->
        stmt.setObject(1, user.value)
        stmt.executeUpdate()
      }
    } finally {
      connection.createStatement().use { it.execute("ALTER TABLE users ENABLE TRIGGER trigger_00_prevent_physical_delete") }
      connection
        .createStatement()
        .use { it.execute("ALTER TABLE user_auth_identities ENABLE TRIGGER trigger_01_prevent_user_auth_identities_delete") }
    }

    val remaining = UserAuthIdentitiesDao.listByUser(session, user).getOrThrow()
    assertTrue(remaining.isEmpty(), "CASCADE must remove identities for a hard-deleted user")
  }
}
