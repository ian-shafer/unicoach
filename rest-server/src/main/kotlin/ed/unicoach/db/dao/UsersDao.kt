package ed.unicoach.db.dao

import ed.unicoach.db.models.*
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

object UsersDao {

    private fun mapUser(rs: ResultSet): User {
        val id = UserId(UUID.fromString(rs.getString("id")))
        val versionId = UserVersionId(rs.getInt("version"))
        val createdAt = rs.getTimestamp("created_at").toInstant()
        val rowCreatedAt = rs.getTimestamp("row_created_at").toInstant()
        val updatedAt = rs.getTimestamp("updated_at").toInstant()
        val rowUpdatedAt = rs.getTimestamp("row_updated_at").toInstant()
        val deletedAt = rs.getTimestamp("deleted_at")?.toInstant()

        val email = (EmailAddress.create(rs.getString("email")) as ValidationResult.Valid).value
        val name = (PersonName.create(rs.getString("name")) as ValidationResult.Valid).value
        val displayNameStr = rs.getString("display_name")
        val displayName = displayNameStr?.let { (DisplayName.create(it) as ValidationResult.Valid).value }

        val passwordHashStr = rs.getString("password_hash")
        val ssoStr = rs.getString("sso_provider_id")

        val authMethod = when {
            passwordHashStr != null && ssoStr != null -> AuthMethod.Both(
                (PasswordHash.create(passwordHashStr) as ValidationResult.Valid).value,
                (SsoProviderId.create(ssoStr) as ValidationResult.Valid).value
            )
            passwordHashStr != null -> AuthMethod.Password(
                (PasswordHash.create(passwordHashStr) as ValidationResult.Valid).value
            )
            ssoStr != null -> AuthMethod.SSO(
                (SsoProviderId.create(ssoStr) as ValidationResult.Valid).value
            )
            else -> throw SQLException("Invalid AuthMethod state in database")
        }

        return User(
            id = id,
            versionId = versionId,
            createdAt = createdAt,
            rowCreatedAt = rowCreatedAt,
            updatedAt = updatedAt,
            rowUpdatedAt = rowUpdatedAt,
            deletedAt = deletedAt,
            email = email,
            name = name,
            displayName = displayName,
            authMethod = authMethod
        )
    }

    private fun mapUserVersion(rs: ResultSet): UserVersion {
        val id = UserId(UUID.fromString(rs.getString("id")))
        val versionId = UserVersionId(rs.getInt("version"))
        val createdAt = rs.getTimestamp("created_at").toInstant()
        val rowCreatedAt = rs.getTimestamp("row_created_at").toInstant()
        val updatedAt = rs.getTimestamp("updated_at").toInstant()
        val rowUpdatedAt = rs.getTimestamp("row_updated_at").toInstant()
        val deletedAt = rs.getTimestamp("deleted_at")?.toInstant()

        val email = (EmailAddress.create(rs.getString("email")) as ValidationResult.Valid).value
        val name = (PersonName.create(rs.getString("name")) as ValidationResult.Valid).value
        val displayNameStr = rs.getString("display_name")
        val displayName = displayNameStr?.let { (DisplayName.create(it) as ValidationResult.Valid).value }

        val passwordHashStr = rs.getString("password_hash")
        val ssoStr = rs.getString("sso_provider_id")

        val authMethod = when {
            passwordHashStr != null && ssoStr != null -> AuthMethod.Both(
                (PasswordHash.create(passwordHashStr) as ValidationResult.Valid).value,
                (SsoProviderId.create(ssoStr) as ValidationResult.Valid).value
            )
            passwordHashStr != null -> AuthMethod.Password(
                (PasswordHash.create(passwordHashStr) as ValidationResult.Valid).value
            )
            ssoStr != null -> AuthMethod.SSO(
                (SsoProviderId.create(ssoStr) as ValidationResult.Valid).value
            )
            else -> throw SQLException("Invalid AuthMethod state in database")
        }

        return UserVersion(
            id = id,
            versionId = versionId,
            createdAt = createdAt,
            rowCreatedAt = rowCreatedAt,
            updatedAt = updatedAt,
            rowUpdatedAt = rowUpdatedAt,
            deletedAt = deletedAt,
            email = email,
            name = name,
            displayName = displayName,
            authMethod = authMethod
        )
    }

    fun findById(session: SqlSession, id: UserId, includeDeleted: Boolean = false): FindResult {
        return try {
            session.prepareStatement("SELECT * FROM users WHERE id = ?").use { stmt ->
                stmt.setObject(1, id.value)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return FindResult.NotFound
                    }
                    val user = mapUser(rs)
                    if (!includeDeleted && user.deletedAt != null) {
                        return FindResult.NotFound
                    }
                    FindResult.Success(user)
                }
            }
        } catch (e: SQLException) {
            FindResult.DatabaseFailure(e.message ?: "Unknown database error (state: ${e.sqlState})")
        } catch (e: Exception) {
            FindResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun findByIdForUpdate(session: SqlSession, id: UserId, includeDeleted: Boolean = false): FindResult {
        return try {
            session.prepareStatement("SELECT * FROM users WHERE id = ? FOR UPDATE NOWAIT").use { stmt ->
                stmt.setObject(1, id.value)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return FindResult.NotFound
                    }
                    val user = mapUser(rs)
                    if (!includeDeleted && user.deletedAt != null) {
                        return FindResult.NotFound
                    }
                    FindResult.Success(user)
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "55P03") {
                return FindResult.LockAcquisitionFailure
            }
            FindResult.DatabaseFailure(e.message ?: "Unknown database error (state: ${e.sqlState})")
        } catch (e: Exception) {
            FindResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun findByEmail(session: SqlSession, email: EmailAddress): FindResult {
        return try {
            session.prepareStatement("SELECT * FROM users WHERE email = ? AND deleted_at IS NULL").use { stmt ->
                stmt.setString(1, email.value)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return FindResult.NotFound
                    }
                    FindResult.Success(mapUser(rs))
                }
            }
        } catch (e: SQLException) {
            FindResult.DatabaseFailure(e.message ?: "Unknown database error (state: ${e.sqlState})")
        } catch (e: Exception) {
            FindResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun findVersion(session: SqlSession, id: UserId, targetVersion: UserVersionId): FindVersionResult {
        return try {
            session.prepareStatement("SELECT * FROM users_versions WHERE id = ? AND version = ?").use { stmt ->
                stmt.setObject(1, id.value)
                stmt.setInt(2, targetVersion.value)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return FindVersionResult.NotFound
                    }
                    FindVersionResult.Success(mapUserVersion(rs))
                }
            }
        } catch (e: SQLException) {
            FindVersionResult.DatabaseFailure(e.message ?: "Unknown database error (state: ${e.sqlState})")
        } catch (e: Exception) {
            FindVersionResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun create(session: SqlSession, user: NewUser): CreateResult {
        return try {
            val sql = """
                INSERT INTO users (email, name, display_name, password_hash, sso_provider_id)
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setString(1, user.email.value)
                stmt.setString(2, user.name.value)
                if (user.displayName != null) stmt.setString(3, user.displayName.value) else stmt.setNull(3, java.sql.Types.VARCHAR)
                
                when (val method = user.authMethod) {
                    is AuthMethod.Both -> {
                        stmt.setString(4, method.hash.value)
                        stmt.setString(5, method.providerId.value)
                    }
                    is AuthMethod.Password -> {
                        stmt.setString(4, method.hash.value)
                        stmt.setNull(5, java.sql.Types.VARCHAR)
                    }
                    is AuthMethod.SSO -> {
                        stmt.setNull(4, java.sql.Types.VARCHAR)
                        stmt.setString(5, method.providerId.value)
                    }
                }

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        CreateResult.Success(mapUser(rs))
                    } else {
                        CreateResult.DatabaseFailure("Insert succeeded but returning failed")
                    }
                }
            }
        } catch (e: SQLException) {
            when (e.sqlState) {
                "23505" -> if (e.message?.contains("users_email_unique_active_idx") == true) {
                    CreateResult.DuplicateEmail
                } else {
                    CreateResult.ConstraintViolation(e.message ?: "Duplicate key violation")
                }
                "23514" -> CreateResult.ConstraintViolation(e.message ?: "Check constraint violation")
                else -> CreateResult.DatabaseFailure(e.message ?: "State: ${e.sqlState}")
            }
        } catch (e: Exception) {
            CreateResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    private fun doUpdate(session: SqlSession, user: User): UpdateResult {
        return try {
            val sql = """
                UPDATE users 
                SET version = ?, email = ?, name = ?, display_name = ?, password_hash = ?, sso_provider_id = ?
                WHERE id = ? AND version = ?
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                val nextVersion = user.versionId.value + 1
                stmt.setInt(1, nextVersion)
                stmt.setString(2, user.email.value)
                stmt.setString(3, user.name.value)
                if (user.displayName != null) stmt.setString(4, user.displayName.value) else stmt.setNull(4, java.sql.Types.VARCHAR)

                when (val method = user.authMethod) {
                    is AuthMethod.Both -> {
                        stmt.setString(5, method.hash.value)
                        stmt.setString(6, method.providerId.value)
                    }
                    is AuthMethod.Password -> {
                        stmt.setString(5, method.hash.value)
                        stmt.setNull(6, java.sql.Types.VARCHAR)
                    }
                    is AuthMethod.SSO -> {
                        stmt.setNull(5, java.sql.Types.VARCHAR)
                        stmt.setString(6, method.providerId.value)
                    }
                }
                stmt.setObject(7, user.id.value)
                stmt.setInt(8, user.versionId.value)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        UpdateResult.Success(mapUser(rs))
                    } else {
                        // Because we could not find it, check if id exists to distinguish NotFound vs ConcurrentModification
                        session.prepareStatement("SELECT version FROM users WHERE id = ?").use { checkStmt ->
                            checkStmt.setObject(1, user.id.value)
                            checkStmt.executeQuery().use { checkRs ->
                                if (checkRs.next()) {
                                    UpdateResult.ConcurrentModification
                                } else {
                                    UpdateResult.NotFound
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            when (e.sqlState) {
                "23505" -> if (e.message?.contains("users_email_unique_active_idx") == true) {
                    UpdateResult.DuplicateEmail
                } else {
                    UpdateResult.ConstraintViolation(e.message ?: "Duplicate key violation")
                }
                "23514" -> UpdateResult.ConstraintViolation(e.message ?: "Check constraint violation")
                else -> UpdateResult.DatabaseFailure(e.message ?: "State: ${e.sqlState}")
            }
        } catch (e: Exception) {
            UpdateResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun update(session: SqlSession, user: User): UpdateResult {
        return doUpdate(session, user)
    }

    fun updatePhysicalRecord(session: SqlSession, user: User): UpdateResult {
        return try {
            session.prepareStatement("SET LOCAL unicoach.bypass_logical_timestamp = 'true'").use { it.execute() }
            doUpdate(session, user)
        } catch (e: SQLException) {
            UpdateResult.DatabaseFailure(e.message ?: "Error setting local variable")
        }
    }

    fun delete(session: SqlSession, id: UserId, currentVersion: UserVersionId): DeleteResult {
        return try {
            val sql = """
                UPDATE users 
                SET version = ?, deleted_at = NOW()
                WHERE id = ? AND version = ?
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                val nextVersion = currentVersion.value + 1
                stmt.setInt(1, nextVersion)
                stmt.setObject(2, id.value)
                stmt.setInt(3, currentVersion.value)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        DeleteResult.Success(mapUser(rs))
                    } else {
                        session.prepareStatement("SELECT version FROM users WHERE id = ?").use { checkStmt ->
                            checkStmt.setObject(1, id.value)
                            checkStmt.executeQuery().use { checkRs ->
                                if (checkRs.next()) {
                                    DeleteResult.ConcurrentModification
                                } else {
                                    DeleteResult.NotFound
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            DeleteResult.DatabaseFailure(e.message ?: "State: ${e.sqlState}")
        } catch (e: Exception) {
            DeleteResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun undelete(session: SqlSession, id: UserId, currentVersion: UserVersionId): UpdateResult {
        return try {
            val sql = """
                UPDATE users 
                SET version = ?, deleted_at = NULL
                WHERE id = ? AND version = ?
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, currentVersion.value + 1)
                stmt.setObject(2, id.value)
                stmt.setInt(3, currentVersion.value)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        UpdateResult.Success(mapUser(rs))
                    } else {
                        session.prepareStatement("SELECT version FROM users WHERE id = ?").use { checkStmt ->
                            checkStmt.setObject(1, id.value)
                            checkStmt.executeQuery().use { checkRs ->
                                if (checkRs.next()) {
                                    UpdateResult.ConcurrentModification
                                } else {
                                    UpdateResult.NotFound
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            when (e.sqlState) {
                "23505" -> if (e.message?.contains("users_email_unique_active_idx") == true) {
                    UpdateResult.DuplicateEmail
                } else {
                    UpdateResult.ConstraintViolation(e.message ?: "Duplicate key violation")
                }
                "23514" -> UpdateResult.ConstraintViolation(e.message ?: "Check constraint violation")
                else -> UpdateResult.DatabaseFailure(e.message ?: "State: ${e.sqlState}")
            }
        } catch (e: Exception) {
            UpdateResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }

    fun revertToVersion(session: SqlSession, id: UserId, targetHistoricalVersion: UserVersionId, currentVersion: UserVersionId): UpdateResult {
        val versionResult = findVersion(session, id, targetHistoricalVersion)
        if (versionResult is FindVersionResult.NotFound) {
            return UpdateResult.TargetVersionMissing
        }
        if (versionResult !is FindVersionResult.Success) {
            return UpdateResult.DatabaseFailure("Failed to extract target historical bounds.")
        }
        val target = versionResult.version

        return try {
            val sql = """
                UPDATE users 
                SET version = ?, email = ?, name = ?, display_name = ?, password_hash = ?, sso_provider_id = ?
                WHERE id = ? AND version = ?
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, currentVersion.value + 1)
                stmt.setString(2, target.email.value)
                stmt.setString(3, target.name.value)
                if (target.displayName != null) stmt.setString(4, target.displayName.value) else stmt.setNull(4, java.sql.Types.VARCHAR)

                when (val method = target.authMethod) {
                    is AuthMethod.Both -> {
                        stmt.setString(5, method.hash.value)
                        stmt.setString(6, method.providerId.value)
                    }
                    is AuthMethod.Password -> {
                        stmt.setString(5, method.hash.value)
                        stmt.setNull(6, java.sql.Types.VARCHAR)
                    }
                    is AuthMethod.SSO -> {
                        stmt.setNull(5, java.sql.Types.VARCHAR)
                        stmt.setString(6, method.providerId.value)
                    }
                }
                stmt.setObject(7, id.value)
                stmt.setInt(8, currentVersion.value)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        UpdateResult.Success(mapUser(rs))
                    } else {
                        session.prepareStatement("SELECT version FROM users WHERE id = ?").use { checkStmt ->
                            checkStmt.setObject(1, id.value)
                            checkStmt.executeQuery().use { checkRs ->
                                if (checkRs.next()) {
                                    UpdateResult.ConcurrentModification
                                } else {
                                    UpdateResult.NotFound
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            when (e.sqlState) {
                "23505" -> if (e.message?.contains("users_email_unique_active_idx") == true) {
                    UpdateResult.DuplicateEmail
                } else {
                    UpdateResult.ConstraintViolation(e.message ?: "Duplicate key violation")
                }
                "23514" -> UpdateResult.ConstraintViolation(e.message ?: "Check constraint violation")
                else -> UpdateResult.DatabaseFailure(e.message ?: "State: ${e.sqlState}")
            }
        } catch (e: Exception) {
            UpdateResult.DatabaseFailure(e.message ?: "Mapping error")
        }
    }
}
