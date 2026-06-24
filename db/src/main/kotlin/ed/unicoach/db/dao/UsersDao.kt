package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.DisplayName
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserEdit
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.UserVersion
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

object UsersDao :
  SoftDeleteFindable<User, UserId>,
  SoftDeleteListable<User>,
  Creatable<NewUser, User>,
  Updatable<UserEdit, User>,
  OccDeletable<User, UserId>,
  VersionHistory<UserId, UserVersion> {
  private fun mapUser(rs: ResultSet): User {
    val id = UserId(UUID.fromString(rs.getString("id")))
    val version = rs.getInt("version")
    val createdAt = rs.getInstant("created_at")
    val updatedAt = rs.getInstant("updated_at")
    val deletedAt = rs.getInstantOrNull("deleted_at")

    val email = (EmailAddress.create(rs.getString("email")) as ValidationResult.Valid).value
    val name = (PersonName.create(rs.getString("name")) as ValidationResult.Valid).value
    val displayNameStr = rs.getString("display_name")
    val displayName = displayNameStr?.let { (DisplayName.create(it) as ValidationResult.Valid).value }

    return User(
      id = id,
      version = version,
      createdAt = createdAt,
      updatedAt = updatedAt,
      deletedAt = deletedAt,
      email = email,
      name = name,
      displayName = displayName,
      passwordHash = readPasswordHash(rs),
      isAdmin = rs.getBoolean("is_admin"),
      emailVerifiedAt = rs.getInstantOrNull("email_verified_at"),
    )
  }

  private fun mapUserVersion(rs: ResultSet): UserVersion {
    val id = UserId(UUID.fromString(rs.getString("id")))
    val version = rs.getInt("version")
    val createdAt = rs.getInstant("created_at")
    val updatedAt = rs.getInstant("updated_at")
    val deletedAt = rs.getInstantOrNull("deleted_at")

    val email = (EmailAddress.create(rs.getString("email")) as ValidationResult.Valid).value
    val name = (PersonName.create(rs.getString("name")) as ValidationResult.Valid).value
    val displayNameStr = rs.getString("display_name")
    val displayName = displayNameStr?.let { (DisplayName.create(it) as ValidationResult.Valid).value }

    return UserVersion(
      id = id,
      version = version,
      createdAt = createdAt,
      updatedAt = updatedAt,
      deletedAt = deletedAt,
      email = email,
      name = name,
      displayName = displayName,
      passwordHash = readPasswordHash(rs),
      isAdmin = rs.getBoolean("is_admin"),
      emailVerifiedAt = rs.getInstantOrNull("email_verified_at"),
    )
  }

  /** Reconstructs the nullable password credential directly from `password_hash`. */
  private fun readPasswordHash(rs: ResultSet): PasswordHash? =
    rs.getString("password_hash")?.let { (PasswordHash.create(it) as ValidationResult.Valid).value }

  override fun findById(
    session: SqlSession,
    id: UserId,
    scope: SoftDeleteScope,
  ): Result<User> =
    session
      .queryOne(
        "SELECT * FROM users WHERE id = ?",
        bind = { it.setObject(1, id.value) },
        map = ::mapUser,
      ).mapCatching { user ->
        if (!scope.admits(user.deletedAt)) throw NotFoundException()
        user
      }

  fun findByIdForUpdate(
    session: SqlSession,
    id: UserId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<User> {
    return try {
      session.prepareStatement("SELECT * FROM users WHERE id = ? FOR UPDATE NOWAIT").use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          val user = mapUser(rs)
          if (!scope.admits(user.deletedAt)) {
            return Result.failure(NotFoundException())
          }
          Result.success(user)
        }
      }
    } catch (e: SQLException) {
      if (e.sqlState == "55P03") {
        return Result.failure(LockAcquisitionFailureException())
      }
      Result.failure(mapDatabaseError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }
  }

  /** Whether a [SoftDeleteScope] admits a row with the given `deletedAt`. */
  private fun SoftDeleteScope.admits(deletedAt: java.time.Instant?): Boolean =
    when (this) {
      SoftDeleteScope.ACTIVE -> deletedAt == null
      SoftDeleteScope.DELETED -> deletedAt != null
      SoftDeleteScope.ALL -> true
    }

  fun findByEmail(
    session: SqlSession,
    email: EmailAddress,
  ): Result<User> =
    session.queryOne(
      "SELECT * FROM users WHERE email = ? AND deleted_at IS NULL",
      bind = { it.setString(1, email.value) },
      map = ::mapUser,
    )

  fun findVersion(
    session: SqlSession,
    id: UserId,
    targetVersion: Int,
  ): Result<UserVersion> =
    session.queryOne(
      "SELECT * FROM users_versions WHERE id = ? AND version = ?",
      bind = {
        it.setObject(1, id.value)
        it.setInt(2, targetVersion)
      },
      map = ::mapUserVersion,
    )

  override fun create(
    session: SqlSession,
    input: NewUser,
  ): Result<User> =
    session.insertReturning(
      table = "users",
      columns =
        linkedMapOf<String, Bind>(
          "email" to { stmt, i -> stmt.setString(i, input.email.value) },
          "name" to { stmt, i -> stmt.setString(i, input.name.value) },
          "display_name" to { stmt, i -> stmt.setStringOrNull(i, input.displayName?.value) },
          "password_hash" to { stmt, i -> stmt.setStringOrNull(i, input.passwordHash?.value) },
          "is_admin" to { stmt, i -> stmt.setBoolean(i, input.isAdmin) },
        ),
      map = ::mapUser,
      mapError = ::mapCreateUpdateError,
    )

  override fun update(
    session: SqlSession,
    edit: UserEdit,
  ): Result<User> =
    session.updateColumnsReturning(
      table = "users",
      id = edit.id.value,
      currentVersion = edit.version,
      columns =
        linkedMapOf<String, Bind>(
          "email" to { stmt, i -> stmt.setString(i, edit.email.value) },
          "name" to { stmt, i -> stmt.setString(i, edit.name.value) },
          "display_name" to { stmt, i -> stmt.setStringOrNull(i, edit.displayName?.value) },
          "is_admin" to { stmt, i -> stmt.setBoolean(i, edit.isAdmin) },
        ),
      map = ::mapUser,
      mapError = ::mapCreateUpdateError,
    )

  /**
   * Marks a user's email verified: a versioned conditional update stamping
   * `email_verified_at = NOW()` only while it is still NULL, bumping `version`,
   * and returning the updated row. When no row matches (already verified), falls
   * back to [findById] with [SoftDeleteScope.ACTIVE] and returns the existing
   * user unchanged — idempotent, no second version bump — failing only if the
   * user is truly absent. Written only by the dedicated verification path, never
   * the generic [update] surface (the same isolation `password_hash` has).
   */
  fun markEmailVerified(
    session: SqlSession,
    id: UserId,
  ): Result<User> {
    val sql =
      """
      UPDATE users
      SET version = version + 1, email_verified_at = NOW()
      WHERE id = ? AND email_verified_at IS NULL AND deleted_at IS NULL
      RETURNING *
      """.trimIndent()
    val updated =
      session.mutateReturning(
        sql,
        bind = { it.setObject(1, id.value) },
        map = ::mapUser,
        mapError = ::mapCreateUpdateError,
        onNoRow = { NotFoundException() },
      )
    if (updated.isSuccess) {
      return updated
    }
    // No row matched: either the user is already verified (so return it unchanged)
    // or it is truly absent (so propagate NotFound).
    if (updated.exceptionOrNull() is NotFoundException) {
      return findById(session, id, SoftDeleteScope.ACTIVE)
    }
    return updated
  }

  /**
   * Rewrites a user's email and re-arms verification in a single versioned
   * conditional update: sets `email`, resets `email_verified_at` back to NULL,
   * bumps `version`, and returns the updated row. A `23505` on
   * `users_email_unique_active_idx` maps to [DuplicateEmailException]; an absent
   * (or soft-deleted) user yields [NotFoundException]. Written only by the
   * dedicated change-email path, never the generic [update] surface (the same
   * isolation [markEmailVerified] has). It uses `version = version + 1` rather
   * than an OCC `WHERE version = ?` write: the caller holds a freshly-read
   * session user and concurrent double-submits are self-correcting, so an OCC
   * lost-update rejection would add a failure mode with no correctness benefit.
   */
  fun changeEmail(
    session: SqlSession,
    id: UserId,
    newEmail: EmailAddress,
  ): Result<User> {
    val sql =
      """
      UPDATE users
      SET version = version + 1, email = ?, email_verified_at = NULL
      WHERE id = ? AND deleted_at IS NULL
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = {
        it.setString(1, newEmail.value)
        it.setObject(2, id.value)
      },
      map = ::mapUser,
      mapError = ::mapCreateUpdateError,
      onNoRow = { NotFoundException() },
    )
  }

  /**
   * Restores a full historical row (auth columns included) under the bypass GUC
   * so the logical-timestamp trigger does not advance `created_at`. Two
   * statements in the caller's transaction: the `SET LOCAL` bypass, then the
   * full-row OCC update.
   */
  fun updatePhysicalRecord(
    session: SqlSession,
    user: User,
  ): Result<User> {
    val bypass = session.execute("SET LOCAL unicoach.bypass_logical_timestamp = 'true'")
    if (bypass.isFailure) {
      return Result.failure(bypass.exceptionOrNull()!!)
    }
    return updateFullRow(
      session,
      user.id,
      user.version,
      user.email,
      user.name,
      user.displayName,
      user.passwordHash,
      user.isAdmin,
      user.emailVerifiedAt,
    )
  }

  override fun delete(
    session: SqlSession,
    id: UserId,
    currentVersion: Int,
  ): Result<User> =
    session.softDeleteReturning(
      table = "users",
      id = id.value,
      currentVersion = currentVersion,
      deleted = true,
      map = ::mapUser,
    )

  override fun undelete(
    session: SqlSession,
    id: UserId,
    currentVersion: Int,
  ): Result<User> =
    session.softDeleteReturning(
      table = "users",
      id = id.value,
      currentVersion = currentVersion,
      deleted = false,
      map = ::mapUser,
      mapError = ::mapCreateUpdateError,
    )

  fun revertToVersion(
    session: SqlSession,
    id: UserId,
    targetHistoricalVersion: Int,
    currentVersion: Int,
  ): Result<User> =
    findVersion(session, id, targetHistoricalVersion).fold(
      onSuccess = { target ->
        updateFullRow(
          session,
          id,
          currentVersion,
          target.email,
          target.name,
          target.displayName,
          target.passwordHash,
          target.isAdmin,
          target.emailVerifiedAt,
        )
      },
      onFailure = { cause ->
        if (cause is NotFoundException) {
          Result.failure(TargetVersionMissingException(id, targetHistoricalVersion, cause))
        } else {
          Result.failure(cause)
        }
      },
    )

  /**
   * Full-row OCC writer restoring every mutable column (the password credential
   * included). Shared by [revertToVersion] and [updatePhysicalRecord]; not
   * exposed through `update`, whose narrowed [UserEdit] path never touches
   * `password_hash`.
   */
  private fun updateFullRow(
    session: SqlSession,
    id: UserId,
    currentVersion: Int,
    email: EmailAddress,
    name: PersonName,
    displayName: DisplayName?,
    passwordHash: PasswordHash?,
    isAdmin: Boolean,
    emailVerifiedAt: java.time.Instant?,
  ): Result<User> {
    val sql =
      """
      UPDATE users
      SET version = ?, email = ?, name = ?, display_name = ?, password_hash = ?, is_admin = ?, email_verified_at = ?
      WHERE id = ? AND version = ?
      RETURNING *
      """.trimIndent()
    return session.occUpdate(
      table = "users",
      sql = sql,
      bind = { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setString(2, email.value)
        stmt.setString(3, name.value)
        stmt.setStringOrNull(4, displayName?.value)
        stmt.setStringOrNull(5, passwordHash?.value)
        stmt.setBoolean(6, isAdmin)
        if (emailVerifiedAt != null) {
          stmt.setTimestamp(7, java.sql.Timestamp.from(emailVerifiedAt))
        } else {
          stmt.setNull(7, java.sql.Types.TIMESTAMP)
        }
        stmt.setObject(8, id.value)
        stmt.setInt(9, currentVersion)
      },
      idValue = id.value,
      map = ::mapUser,
      mapError = ::mapCreateUpdateError,
    )
  }

  /**
   * Admin read surface: page the full user table newest-first. The [scope]
   * filter is a fixed SQL fragment (no caller data); admin lists default to
   * [SoftDeleteScope.ALL] so soft-deleted rows stay visible.
   */
  override fun list(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<User>> {
    val sql =
      """
      SELECT * FROM users
      WHERE ${scope.predicate()}
      ORDER BY created_at DESC, id
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = {
        it.setInt(1, limit)
        it.setInt(2, offset)
      },
      map = ::mapUser,
    )
  }

  /** Admin read surface: a user's full version history, ascending by version. */
  override fun listVersions(
    session: SqlSession,
    id: UserId,
  ): Result<List<UserVersion>> =
    session.queryList(
      "SELECT * FROM users_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = ::mapUserVersion,
    )

  /**
   * Shared SQLSTATE discrimination for create/update operations that may
   * produce DuplicateEmail or ConstraintViolation.
   */
  private fun mapCreateUpdateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23505" -> {
        if (e.message?.contains("users_email_unique_active_idx") == true) {
          DuplicateEmailException()
        } else {
          ConstraintViolationException(e)
        }
      }

      "23514" -> {
        ConstraintViolationException(e)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
