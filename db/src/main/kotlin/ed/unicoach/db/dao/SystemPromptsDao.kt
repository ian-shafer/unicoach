package ed.unicoach.db.dao

import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.util.UUID

/**
 * Read-only reader over the immutable `system_prompts` catalog (RFC 33). Rows
 * are authored by migration, never by the application, so this DAO exposes no
 * writes. Stateless `object`, one [SqlSession] per call.
 */
object SystemPromptsDao {
  private fun mapPrompt(rs: ResultSet): SystemPrompt =
    SystemPrompt(
      id = SystemPromptId(UUID.fromString(rs.getString("id"))),
      name = rs.getString("name"),
      version = rs.getString("version"),
      body = rs.getString("body"),
      createdAt = rs.getTimestamp("created_at").toInstant(),
    )

  /**
   * Resolves the catalog row for a `(name, version)` pair (the UNIQUE key).
   * [NotFoundException] when no row matches.
   */
  fun findByNameAndVersion(
    session: SqlSession,
    name: String,
    version: String,
  ): Result<SystemPrompt> =
    try {
      session
        .prepareStatement("SELECT * FROM system_prompts WHERE name = ? AND version = ?")
        .use { stmt ->
          stmt.setString(1, name)
          stmt.setString(2, version)
          stmt.executeQuery().use { rs ->
            if (rs.next()) {
              Result.success(mapPrompt(rs))
            } else {
              Result.failure(NotFoundException())
            }
          }
        }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }
}
