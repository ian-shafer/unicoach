package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
  fun `findById ACTIVE scope correctly emits NotFound for softly deleted rows`() {
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
        ed.unicoach.db.models.SoftDeleteScope.ACTIVE,
      )
    assertTrue(resultDeleted.isFailure && resultDeleted.exceptionOrNull() is NotFoundException)

    val resultIncluded =
      UsersDao.findById(
        session1,
        ed.unicoach.db.models
          .UserId(rawId),
        ed.unicoach.db.models.SoftDeleteScope.ALL,
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
        passwordHash = newPass,
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
        passwordHash = pass,
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
    val validUpdateResult = UsersDao.update(session1, editOf(nextVersionUser))
    assertTrue(validUpdateResult.isSuccess)

    // Attempt update with original stale model
    val staleUpdateResult =
      UsersDao.update(
        session1,
        editOf(
          createdUser.copy(
            name =
              (
                ed.unicoach.db.models.PersonName
                  .create("Stale Edit") as ValidationResult.Valid
              ).value,
          ),
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
        passwordHash = pass,
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
        passwordHash = pass,
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
    val updateResult = UsersDao.update(session1, editOf(v1User.copy(name = nameV2)))
    assertTrue(updateResult.isSuccess)
    val v2User = updateResult.getOrNull()!!

    // V3
    val nameV3 =
      (
        ed.unicoach.db.models.PersonName
          .create("Final Mistake") as ValidationResult.Valid
      ).value
    val updateResult2 = UsersDao.update(session1, editOf(v2User.copy(name = nameV3)))
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

  @Test
  fun `revertToVersion to a missing version preserves the lookup keys and the cause`() {
    val created = UsersDao.create(session, newPasswordUser("revert-missing")).getOrThrow()

    // Version 999 was never written, so the historical lookup must miss.
    val result =
      UsersDao.revertToVersion(
        session,
        created.id,
        targetHistoricalVersion = 999,
        currentVersion = created.version,
      )

    assertTrue(result.isFailure)
    val error = assertIs<TargetVersionMissingException>(result.exceptionOrNull())
    assertEquals(created.id, error.entityId, "The failing entity id must survive the mapping")
    assertEquals(999, error.targetVersion, "The requested version must survive the mapping")
    assertIs<NotFoundException>(error.cause, "The originating lookup failure must remain the cause")
    assertTrue(
      error.message!!.contains(created.id.asString) && error.message!!.contains("999"),
      "The log message must name both lookup keys, was: ${error.message}",
    )
  }

  @Test
  fun `create and findByEmail round-trip a password user and an SSO-only user`() {
    val pwUser =
      UsersDao
        .create(session, newPasswordUser("pw-roundtrip"))
        .getOrThrow()
    assertTrue(pwUser.passwordHash != null, "Password user must carry a passwordHash")

    val ssoOnly =
      UsersDao
        .create(
          session,
          NewUser(
            email = (EmailAddress.create("sso-roundtrip@example.com") as ValidationResult.Valid).value,
            name = (PersonName.create("SSO Only") as ValidationResult.Valid).value,
            displayName = null,
            passwordHash = null,
          ),
        ).getOrThrow()
    assertTrue(ssoOnly.passwordHash == null, "SSO-only user must have a null passwordHash")

    val reloadedPw = UsersDao.findById(session, pwUser.id).getOrThrow()
    assertTrue(reloadedPw.passwordHash == pwUser.passwordHash, "findById must round-trip the passwordHash")

    val byEmail = UsersDao.findByEmail(session, ssoOnly.email).getOrThrow()
    assertTrue(byEmail.id == ssoOnly.id && byEmail.passwordHash == null, "findByEmail must round-trip the null passwordHash")
  }

  @Test
  fun `version history reconstructs an SSO-only user without sso_provider_id`() {
    val ssoOnly =
      UsersDao
        .create(
          session,
          NewUser(
            email = (EmailAddress.create("sso-history@example.com") as ValidationResult.Valid).value,
            name = (PersonName.create("SSO History") as ValidationResult.Valid).value,
            displayName = null,
            passwordHash = null,
          ),
        ).getOrThrow()

    val v1 = UsersDao.findVersion(session, ssoOnly.id, targetVersion = ssoOnly.version).getOrThrow()
    assertTrue(v1.passwordHash == null, "Historical SSO-only row must reconstruct with a null passwordHash")
  }

  /**
   * Projects a [User] into the [UserEdit] the public `update` now accepts. The OCC
   * version, identity, and mutable business fields carry through; the password
   * credential and timestamps are intentionally absent from the edit surface.
   */
  private fun editOf(user: ed.unicoach.db.models.User): ed.unicoach.db.models.UserEdit =
    ed.unicoach.db.models.UserEdit(
      id = user.id,
      version = user.version,
      email = user.email,
      name = user.name,
      displayName = user.displayName,
      isAdmin = user.isAdmin,
    )

  private fun newPasswordUser(
    emailLocal: String,
    nameText: String = "Admin Test",
    isAdmin: Boolean = false,
  ): NewUser {
    val email = (EmailAddress.create("$emailLocal@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create(nameText) as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return NewUser(
      email = email,
      name = name,
      displayName = null,
      passwordHash = pass,
      isAdmin = isAdmin,
    )
  }

  @Test
  fun `update via UserEdit leaves password_hash and createdAt untouched`() {
    val created = UsersDao.create(session, newPasswordUser("edit-immutable")).getOrThrow()
    assertTrue(created.passwordHash != null, "Precondition: created with a password credential")

    val newName = (PersonName.create("Renamed Via Edit") as ValidationResult.Valid).value
    val edited =
      UsersDao
        .update(
          session,
          ed.unicoach.db.models.UserEdit(
            id = created.id,
            version = created.version,
            email = created.email,
            name = newName,
            displayName = created.displayName,
            isAdmin = created.isAdmin,
          ),
        ).getOrThrow()

    val reloaded = UsersDao.findById(session, created.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()
    assertTrue(reloaded.name == newName, "Expected the mutable name to change")
    assertTrue(reloaded.version == created.version + 1, "Expected OCC version increment")
    assertTrue(reloaded.version == edited.version, "Returned and reloaded versions must agree")
    assertTrue(reloaded.passwordHash == created.passwordHash, "password_hash must be untouched by UserEdit update")
    assertTrue(reloaded.createdAt == created.createdAt, "createdAt must be untouched by UserEdit update")
  }

  @Test
  fun `create persists is_admin true and default false round-trips`() {
    val adminCreate = UsersDao.create(session, newPasswordUser("admin-roundtrip", isAdmin = true))
    assertTrue(adminCreate.isSuccess)
    assertTrue(adminCreate.getOrNull()!!.isAdmin, "Expected isAdmin = true to round-trip")

    val plainCreate = UsersDao.create(session, newPasswordUser("plain-roundtrip", isAdmin = false))
    assertTrue(plainCreate.isSuccess)
    assertTrue(!plainCreate.getOrNull()!!.isAdmin, "Expected default isAdmin = false to round-trip")
  }

  @Test
  fun `updating is_admin bumps version and is captured in history`() {
    val created = UsersDao.create(session, newPasswordUser("grant-history", isAdmin = false)).getOrThrow()
    assertTrue(!created.isAdmin)

    val granted = UsersDao.update(session, editOf(created.copy(isAdmin = true))).getOrThrow()
    assertTrue(granted.isAdmin, "Expected isAdmin = true after update")
    assertTrue(granted.version == created.version + 1, "Expected version bump on is_admin grant")

    val versions = UsersDao.listVersions(session, created.id).getOrThrow()
    assertTrue(versions.size >= 2, "Expected at least two history rows, got ${versions.size}")
    val v1 = versions.first { it.version == created.version }
    val v2 = versions.first { it.version == granted.version }
    assertTrue(!v1.isAdmin, "Historical v1 should reflect isAdmin = false")
    assertTrue(v2.isAdmin, "Historical v2 should reflect isAdmin = true")
  }

  @Test
  fun `list honours scope ordering and paging`() {
    val first = UsersDao.create(session, newPasswordUser("list-1")).getOrThrow()
    Thread.sleep(5)
    val second = UsersDao.create(session, newPasswordUser("list-2")).getOrThrow()
    Thread.sleep(5)
    val third = UsersDao.create(session, newPasswordUser("list-3")).getOrThrow()

    // Soft-delete the second user.
    UsersDao.delete(session, second.id, second.version).getOrThrow()

    val all = UsersDao.list(session, scope = ed.unicoach.db.models.SoftDeleteScope.ALL, limit = 50, offset = 0).getOrThrow()
    assertTrue(all.map { it.id }.containsAll(listOf(first.id, second.id, third.id)), "ALL must include the soft-deleted row")

    val active = UsersDao.list(session, scope = ed.unicoach.db.models.SoftDeleteScope.ACTIVE, limit = 50, offset = 0).getOrThrow()
    assertTrue(active.none { it.id == second.id }, "ACTIVE must exclude the soft-deleted row")

    // created_at DESC: newest (third) first.
    assertTrue(all.first().id == third.id, "Expected newest-first ordering, got ${all.first().id}")

    // Paging: first page of size 1 returns the newest, offset 1 the next.
    val page0 = UsersDao.list(session, scope = ed.unicoach.db.models.SoftDeleteScope.ALL, limit = 1, offset = 0).getOrThrow()
    val page1 = UsersDao.list(session, scope = ed.unicoach.db.models.SoftDeleteScope.ALL, limit = 1, offset = 1).getOrThrow()
    assertTrue(page0.size == 1 && page1.size == 1)
    assertTrue(page0.first().id != page1.first().id, "Paging must advance the cursor")
  }

  @Test
  fun `markEmailVerified flips email_verified_at bumps version and writes history`() {
    val created = UsersDao.create(session, newPasswordUser("mark-verified")).getOrThrow()
    assertTrue(created.emailVerifiedAt == null, "Precondition: new user is unverified")

    val verified = UsersDao.markEmailVerified(session, created.id).getOrThrow()
    assertTrue(verified.emailVerifiedAt != null, "Expected email_verified_at to be set")
    assertTrue(verified.version == created.version + 1, "Expected version bump on verify")

    val versions = UsersDao.listVersions(session, created.id).getOrThrow()
    val v1 = versions.first { it.version == created.version }
    val v2 = versions.first { it.version == verified.version }
    assertTrue(v1.emailVerifiedAt == null, "Historical v1 should reflect unverified state")
    assertTrue(v2.emailVerifiedAt != null, "Historical v2 should carry the verification timestamp")
  }

  @Test
  fun `markEmailVerified is idempotent on an already-verified user`() {
    val created = UsersDao.create(session, newPasswordUser("mark-verified-idem")).getOrThrow()
    val first = UsersDao.markEmailVerified(session, created.id).getOrThrow()
    val firstTimestamp = first.emailVerifiedAt
    assertTrue(firstTimestamp != null)

    val second = UsersDao.markEmailVerified(session, created.id).getOrThrow()
    assertTrue(second.emailVerifiedAt == firstTimestamp, "Idempotent: original timestamp preserved")
    assertTrue(second.version == first.version, "Idempotent: no second version bump")
  }

  @Test
  fun `markEmailVerified fails NotFound for an absent user`() {
    val result = UsersDao.markEmailVerified(session, UserId(java.util.UUID.randomUUID()))
    assertTrue(result.isFailure && result.exceptionOrNull() is NotFoundException, "Expected NotFound, got $result")
  }

  @Test
  fun `findById and mapUser round-trip emailVerifiedAt null and non-null`() {
    val created = UsersDao.create(session, newPasswordUser("verified-roundtrip")).getOrThrow()
    val reloadedNull = UsersDao.findById(session, created.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()
    assertTrue(reloadedNull.emailVerifiedAt == null, "Null email_verified_at must round-trip as null")

    UsersDao.markEmailVerified(session, created.id).getOrThrow()
    val reloadedSet = UsersDao.findById(session, created.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()
    assertTrue(reloadedSet.emailVerifiedAt != null, "Non-null email_verified_at must round-trip")
  }

  @Test
  fun `changeEmail rewrites email and clears verification for a verified user`() {
    val created = UsersDao.create(session, newPasswordUser("change-verified")).getOrThrow()
    UsersDao.markEmailVerified(session, created.id).getOrThrow()
    val verified = UsersDao.findById(session, created.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()
    assertTrue(verified.emailVerifiedAt != null, "Precondition: user is verified")

    val newEmail = (EmailAddress.create("changed-verified@example.com") as ValidationResult.Valid).value
    val updated = UsersDao.changeEmail(session, created.id, newEmail).getOrThrow()

    assertTrue(updated.email == newEmail, "Expected the email to be rewritten")
    assertTrue(updated.emailVerifiedAt == null, "Expected verification to be cleared")
    assertTrue(updated.version == verified.version + 1, "Expected version bump")
  }

  @Test
  fun `changeEmail clears verification for an unverified user`() {
    val created = UsersDao.create(session, newPasswordUser("change-unverified")).getOrThrow()
    assertTrue(created.emailVerifiedAt == null, "Precondition: user is unverified")

    val newEmail = (EmailAddress.create("changed-unverified@example.com") as ValidationResult.Valid).value
    val updated = UsersDao.changeEmail(session, created.id, newEmail).getOrThrow()

    assertTrue(updated.email == newEmail, "Expected the email to be rewritten")
    assertTrue(updated.emailVerifiedAt == null, "Expected the user to remain unverified")
    assertTrue(updated.version == created.version + 1, "Expected version bump")
  }

  @Test
  fun `changeEmail captures the new email and null verification into history`() {
    val created = UsersDao.create(session, newPasswordUser("change-history")).getOrThrow()
    UsersDao.markEmailVerified(session, created.id).getOrThrow()

    val newEmail = (EmailAddress.create("changed-history@example.com") as ValidationResult.Valid).value
    val updated = UsersDao.changeEmail(session, created.id, newEmail).getOrThrow()

    val versions = UsersDao.listVersions(session, created.id).getOrThrow()
    val latest = versions.first { it.version == updated.version }
    assertTrue(latest.email == newEmail, "History row must carry the new email")
    assertTrue(latest.emailVerifiedAt == null, "History row must reflect cleared verification")
  }

  @Test
  fun `changeEmail into an active user's email raises DuplicateEmail and leaves the row unchanged`() {
    val userA = UsersDao.create(session, newPasswordUser("change-collide-a")).getOrThrow()
    val userB = UsersDao.create(session, newPasswordUser("change-collide-b")).getOrThrow()

    val result = UsersDao.changeEmail(session, userA.id, userB.email)
    assertTrue(
      result.isFailure && result.exceptionOrNull() is DuplicateEmailException,
      "Expected DuplicateEmailException, got $result",
    )

    val reloaded = UsersDao.findById(session, userA.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()
    assertTrue(reloaded.email == userA.email, "A's email must be unchanged after a collision")
    assertTrue(reloaded.version == userA.version, "A's version must be unchanged after a collision")
  }

  @Test
  fun `changeEmail into a soft-deleted user's email succeeds`() {
    val userA = UsersDao.create(session, newPasswordUser("change-softdel-a")).getOrThrow()
    val userB = UsersDao.create(session, newPasswordUser("change-softdel-b")).getOrThrow()
    UsersDao.delete(session, userB.id, userB.version).getOrThrow()

    val updated = UsersDao.changeEmail(session, userA.id, userB.email).getOrThrow()
    assertTrue(updated.email == userB.email, "Active uniqueness must not collide with a soft-deleted row")
  }

  @Test
  fun `changeEmail to the same email re-arms verification`() {
    val created = UsersDao.create(session, newPasswordUser("change-same")).getOrThrow()
    UsersDao.markEmailVerified(session, created.id).getOrThrow()
    val verified = UsersDao.findById(session, created.id, ed.unicoach.db.models.SoftDeleteScope.ALL).getOrThrow()

    val updated = UsersDao.changeEmail(session, created.id, created.email).getOrThrow()
    assertTrue(updated.email == created.email, "Same-email re-arm keeps the address")
    assertTrue(updated.emailVerifiedAt == null, "Same-email re-arm clears verification")
    assertTrue(updated.version == verified.version + 1, "Same-email re-arm bumps version")
  }

  @Test
  fun `changeEmail fails NotFound for an absent user`() {
    val newEmail = (EmailAddress.create("absent@example.com") as ValidationResult.Valid).value
    val result = UsersDao.changeEmail(session, UserId(java.util.UUID.randomUUID()), newEmail)
    assertTrue(result.isFailure && result.exceptionOrNull() is NotFoundException, "Expected NotFound, got $result")
  }

  @Test
  fun `listVersions returns ascending version order`() {
    val v1 = UsersDao.create(session, newPasswordUser("versions-order")).getOrThrow()
    val v2 = UsersDao.update(session, editOf(v1.copy(name = (PersonName.create("Edit Two") as ValidationResult.Valid).value))).getOrThrow()
    UsersDao.update(session, editOf(v2.copy(name = (PersonName.create("Edit Three") as ValidationResult.Valid).value))).getOrThrow()

    val versions = UsersDao.listVersions(session, v1.id).getOrThrow()
    val orderedVersions = versions.map { it.version }
    assertTrue(orderedVersions == orderedVersions.sorted(), "Expected ascending version order, got $orderedVersions")
    assertTrue(orderedVersions == listOf(1, 2, 3), "Expected versions 1,2,3, got $orderedVersions")
  }
}
