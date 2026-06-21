package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the immutable `system_prompts` catalog (RFC 33). The
 * table's triggers forbid `UPDATE`/`DELETE`, so a "new version" is a new
 * immutable row; this DAO therefore exposes [Findable]/[Listable]/[Creatable]
 * (read + insert) but no update or delete. Rows are authored by migration or the
 * admin tool (RFC 63), never mutated. Stateless `object`, one [SqlSession] per
 * call.
 */
object SystemPromptsDao :
  Findable<SystemPrompt, SystemPromptId>,
  Listable<SystemPrompt>,
  Creatable<NewSystemPrompt, SystemPrompt> {
  private fun mapPrompt(rs: ResultSet): SystemPrompt =
    SystemPrompt(
      id = SystemPromptId(UUID.fromString(rs.getString("id"))),
      name = rs.getString("name"),
      version = rs.getString("version"),
      body = rs.getString("body"),
      createdAt = rs.getInstant("created_at"),
    )

  override fun findById(
    session: SqlSession,
    id: SystemPromptId,
  ): Result<SystemPrompt> =
    session.queryOne(
      "SELECT * FROM system_prompts WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapPrompt,
    )

  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<SystemPrompt>> =
    session.queryList(
      // created_at DESC is a deterministic final tie-breaker, redundant under
      // UNIQUE (name, version) but kept so the page order is total and stable.
      """
      SELECT * FROM system_prompts
      ORDER BY name ASC, version ASC, created_at DESC
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapPrompt,
    )

  override fun create(
    session: SqlSession,
    input: NewSystemPrompt,
  ): Result<SystemPrompt> =
    session.insertReturning(
      table = "system_prompts",
      columns =
        linkedMapOf<String, Bind>(
          "name" to { stmt, i -> stmt.setString(i, input.name) },
          "version" to { stmt, i -> stmt.setString(i, input.version) },
          "body" to { stmt, i -> stmt.setString(i, input.body) },
        ),
      map = ::mapPrompt,
      mapError = ::mapPromptError,
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
    session.queryOne(
      "SELECT * FROM system_prompts WHERE name = ? AND version = ?",
      bind = { stmt ->
        stmt.setString(1, name)
        stmt.setString(2, version)
      },
      map = ::mapPrompt,
    )

  /**
   * SQLSTATE discrimination for the insert path. A duplicate `(name, version)`
   * (`23505`) and any bound/CHECK violation (`23514`) both surface as
   * [ConstraintViolationException], which the admin form layer renders as a
   * field error. The table's immutability triggers raise `P0001` only on
   * `UPDATE`/`DELETE`, unreachable from this insert-only path, so they fall
   * through to [mapDatabaseError]. Mirrors `ConvosDao.mapConvoError`.
   */
  private fun mapPromptError(e: SQLException): Exception =
    when (e.sqlState) {
      "23505", "23514" -> ConstraintViolationException(e)
      else -> mapDatabaseError(e)
    }
}
