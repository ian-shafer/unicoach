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
}
