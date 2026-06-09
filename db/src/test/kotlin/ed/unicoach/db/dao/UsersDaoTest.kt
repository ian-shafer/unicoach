package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertTrue

class UsersDaoTest {
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
      // TRUNCATE CASCADE structurally guarantees pristine isolation limits between discrete test bounds natively
      stmt.execute("TRUNCATE TABLE users CASCADE")
    }
  }

  // A real implementation of SqlSession for tests
  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @Test
  fun `findByIdForUpdate throws LockAcquisitionFailure when locked by another connection`() {
    val rawId = java.util.UUID.randomUUID()
    connection.createStatement().use { stmt ->
      // Our schema requires auth method, so provide a password hash
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$rawId', 'test-$rawId@test.com', 'Test User', 'ahash')",
      )
    }

    // Connection 1 will lock the row
    connection.autoCommit = false
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }

    val result1 =
      UsersDao.findByIdForUpdate(
        session1,
        ed.unicoach.db.models
          .UserId(rawId),
      )
    assertTrue(result1.isSuccess)

    // Connection 2 attempts to lock the same row and should fail immediately with NOWAIT
    val config =
      ed.unicoach.common.config.AppConfig
        .load("common.conf", "db.conf")
        .getOrThrow()
    val dbConfig =
      ed.unicoach.db.DatabaseConfig
        .from(config)
        .getOrThrow()
    val conn2 = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    conn2.autoCommit = false
    val session2 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = conn2.prepareStatement(sql)
      }

    val result2 =
      UsersDao.findByIdForUpdate(
        session2,
        ed.unicoach.db.models
          .UserId(rawId),
      )
    assertTrue(
      result2.isFailure && result2.exceptionOrNull() is LockAcquisitionFailureException,
      "Expected LockAcquisitionFailureException, got $result2",
    )

    conn2.rollback()
    conn2.close()

    connection.rollback()
    connection.autoCommit = true
  }

  @Test
  fun `findById includeDeleted false correctly emits NotFound for softly deleted rows`() {
    val rawId = java.util.UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash, deleted_at) VALUES ('$rawId', 'test2-$rawId@test.com', 'Deleted', 'ahash', NOW())",
      )
    }

    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }

    val resultDeleted =
      UsersDao.findById(
        session1,
        ed.unicoach.db.models
          .UserId(rawId),
        includeDeleted = false,
      )
    assertTrue(resultDeleted.isFailure && resultDeleted.exceptionOrNull() is NotFoundException)

    val resultIncluded =
      UsersDao.findById(
        session1,
        ed.unicoach.db.models
          .UserId(rawId),
        includeDeleted = true,
      )
    assertTrue(resultIncluded.isSuccess)
  }

  @Test
  fun `create routes duplicate emails directly into DuplicateEmail`() {
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }
    val emailProvider = ed.unicoach.common.models.EmailAddress.Companion
    val nameProvider = ed.unicoach.db.models.PersonName.Companion
    val passProvider = ed.unicoach.db.models.PasswordHash.Companion

    val newEmail = (emailProvider.create("dup@example.com") as ValidationResult.Valid).value
    val newName = (nameProvider.create("Dup Name") as ValidationResult.Valid).value
    val newPass = (passProvider.create("dupHash") as ValidationResult.Valid).value

    val newUser =
      ed.unicoach.db.models.NewUser(
        email = newEmail,
        name = newName,
        displayName = null,
        authMethod =
          ed.unicoach.db.models.AuthMethod
            .Password(newPass),
      )

    val createResult1 = UsersDao.create(session1, newUser)
    assertTrue(createResult1.isSuccess)

    val createResult2 = UsersDao.create(session1, newUser)
    assertTrue(
      createResult2.isFailure && createResult2.exceptionOrNull() is DuplicateEmailException,
      "Expected DuplicateEmailException, got $createResult2",
    )
  }

  @Test
  fun `update trips into ConcurrentModification with stale version`() {
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }
    val email =
      (
        ed.unicoach.common.models.EmailAddress
          .create("occ@example.com") as ValidationResult.Valid
      ).value
    val name =
      (
        ed.unicoach.db.models.PersonName
          .create("OCC Name") as ValidationResult.Valid
      ).value
    val pass =
      (
        ed.unicoach.db.models.PasswordHash
          .create("occHash") as ValidationResult.Valid
      ).value

    val newUser =
      ed.unicoach.db.models.NewUser(
        email = email,
        name = name,
        displayName = null,
        authMethod =
          ed.unicoach.db.models.AuthMethod
            .Password(pass),
      )

    val createResult = UsersDao.create(session1, newUser)
    assertTrue(createResult.isSuccess)
    val createdUser = createResult.getOrNull()!!

    // Emulate an update from another process bounds
    val nextVersionUser =
      createdUser.copy(
        name =
          (
            ed.unicoach.db.models.PersonName
              .create("OCC Next") as ValidationResult.Valid
          ).value,
      )
    val validUpdateResult = UsersDao.update(session1, nextVersionUser)
    assertTrue(validUpdateResult.isSuccess)

    // Attempt update with original stale model
    val staleUpdateResult =
      UsersDao.update(
        session1,
        createdUser.copy(
          name =
            (
              ed.unicoach.db.models.PersonName
                .create("Stale Edit") as ValidationResult.Valid
            ).value,
        ),
      )
    assertTrue(
      staleUpdateResult.isFailure && staleUpdateResult.exceptionOrNull() is ConcurrentModificationException,
      "Expected ConcurrentModificationException, got $staleUpdateResult",
    )
  }

  @Test
  fun `undelete conflict parses DuplicateEmail natively for overlapping active streams`() {
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }
    val targetEmailText = "overlap@example.com"
    val email =
      (
        ed.unicoach.common.models.EmailAddress
          .create(targetEmailText) as ValidationResult.Valid
      ).value
    val name =
      (
        ed.unicoach.db.models.PersonName
          .create("Target") as ValidationResult.Valid
      ).value
    val pass =
      (
        ed.unicoach.db.models.PasswordHash
          .create("apass") as ValidationResult.Valid
      ).value

    val newUser =
      ed.unicoach.db.models.NewUser(
        email = email,
        name = name,
        displayName = null,
        authMethod =
          ed.unicoach.db.models.AuthMethod
            .Password(pass),
      )

    // 1. Create first user
    val firstCreate = UsersDao.create(session1, newUser)
    assertTrue(firstCreate.isSuccess)
    val firstUser = firstCreate.getOrNull()!!

    // 2. Delete first user
    val deleteResult = UsersDao.delete(session1, firstUser.id, firstUser.version)
    assertTrue(deleteResult.isSuccess)
    val deletedUser = deleteResult.getOrNull()!!

    // 3. Create second user using the same email (allowed because first is logically deleted)
    val secondCreate =
      UsersDao.create(
        session1,
        newUser.copy(
          name =
            (
              ed.unicoach.db.models.PersonName
                .create("Imposter") as ValidationResult.Valid
            ).value,
        ),
      )
    assertTrue(secondCreate.isSuccess)

    // 4. Attempt undelete on the first user, which should trigger a domain uniqueness failure
    val undeleteResult = UsersDao.undelete(session1, firstUser.id, deletedUser.version)
    assertTrue(
      undeleteResult.isFailure && undeleteResult.exceptionOrNull() is DuplicateEmailException,
      "Expected DuplicateEmailException, got $undeleteResult",
    )
  }

  @Test
  fun `revertToVersion extracts historical bounds and accurately restores previous data values`() {
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
      }
    val email =
      (
        ed.unicoach.common.models.EmailAddress
          .create("revert@example.com") as ValidationResult.Valid
      ).value
    val pass =
      (
        ed.unicoach.db.models.PasswordHash
          .create("apass") as ValidationResult.Valid
      ).value
    val nameV1 =
      (
        ed.unicoach.db.models.PersonName
          .create("Original Name") as ValidationResult.Valid
      ).value

    val newUser =
      ed.unicoach.db.models.NewUser(
        email = email,
        name = nameV1,
        displayName = null,
        authMethod =
          ed.unicoach.db.models.AuthMethod
            .Password(pass),
      )

    // V1
    val createResult = UsersDao.create(session1, newUser)
    assertTrue(createResult.isSuccess)
    val v1User = createResult.getOrNull()!!

    // V2
    val nameV2 =
      (
        ed.unicoach.db.models.PersonName
          .create("Edited Name") as ValidationResult.Valid
      ).value
    val updateResult = UsersDao.update(session1, v1User.copy(name = nameV2))
    assertTrue(updateResult.isSuccess)
    val v2User = updateResult.getOrNull()!!

    // V3
    val nameV3 =
      (
        ed.unicoach.db.models.PersonName
          .create("Final Mistake") as ValidationResult.Valid
      ).value
    val updateResult2 = UsersDao.update(session1, v2User.copy(name = nameV3))
    assertTrue(updateResult2.isSuccess)
    val v3User = updateResult2.getOrNull()!!

    // Revert to V1 (from current bounds V3)
    val revertResult =
      UsersDao.revertToVersion(
        session1,
        v3User.id,
        targetHistoricalVersion = v1User.version,
        currentVersion = v3User.version,
      )
    assertTrue(revertResult.isSuccess)
    val v4User = revertResult.getOrNull()!!

    // Validate V1 restored cleanly into V4
    assertTrue(v4User.name == nameV1, "Name was safely restored to V1 configuration")
    assertTrue(v4User.version == v3User.version + 1, "Version mathematically incremented accurately")
  }
}
