package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.SystemPromptId
import java.util.UUID

/**
 * The `system_prompts` IMMUTABLE_ENTITY (RFC 63): the first instance of
 * [AdminKind.IMMUTABLE_ENTITY]. The table forbids `UPDATE`/`DELETE` and has no
 * soft-delete column, so [update]/[delete]/[undelete] are null and the engine
 * registers no edit/delete routes and renders no Edit/Delete actions. The result
 * is a create + list/detail surface — the full useful authoring surface for an
 * immutable catalog, where a "new version" is a new immutable row.
 *
 * `body` is up to 1 MB and so is `inList = false` (omitted from the list table)
 * but `editable = true` (shown as a create-form textarea and in full on detail).
 * Since [update] is null, no edit form is ever served, so `body` is never
 * re-editable.
 */
object SystemPromptsResource : AdminResource<SystemPrompt, SystemPromptId> {
  override val slug = "system-prompt"
  override val title = "System Prompt"
  override val kind = AdminKind.IMMUTABLE_ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("name", "Name", FieldType.TEXT, editable = true, sensitive = false),
      AdminField("version", "Version", FieldType.TEXT, editable = true, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("body", "Body", FieldType.MULTILINE, editable = true, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: SystemPrompt): SystemPromptId = row.id

  override fun parseId(raw: String): SystemPromptId? = runCatching { SystemPromptId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: SystemPromptId): String = id.value.toString()

  override fun isDeleted(row: SystemPrompt): Boolean = false

  override fun cells(row: SystemPrompt): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "name" to row.name,
      "version" to row.version,
      "createdAt" to row.createdAt.toString(),
      "body" to row.body,
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<SystemPrompt>> = db.withConnection { session -> SystemPromptsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: SystemPromptId,
    includeDeleted: Boolean,
  ): Result<SystemPrompt> = db.withConnection { session -> SystemPromptsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<SystemPromptId>) = ::createPrompt

  // The table forbids UPDATE/DELETE and has no soft-delete column.
  override val update: (suspend (Database, SystemPromptId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, SystemPromptId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, SystemPromptId) -> Result<Unit>)? = null

  /**
   * Trims `name`/`version` (identifiers whose surrounding whitespace is never
   * intended and which the table's `*_trimmed_check` would otherwise reject) but
   * passes `body` verbatim (trailing whitespace/newlines in a prompt body are
   * significant; the schema exempts `body` from a trimmed check). Rejects blank
   * fields client-side with an [IllegalArgumentException] field message; all
   * other validity (length, size, uniqueness) is DB-enforced and surfaces as
   * `ConstraintViolationException`.
   */
  private suspend fun createPrompt(
    db: Database,
    form: Map<String, String>,
  ): Result<SystemPromptId> {
    val name = form["name"].orEmpty().trim()
    if (name.isBlank()) return Result.failure(IllegalArgumentException("Name is required."))
    val version = form["version"].orEmpty().trim()
    if (version.isBlank()) return Result.failure(IllegalArgumentException("Version is required."))
    val body = form["body"].orEmpty()
    if (body.isBlank()) return Result.failure(IllegalArgumentException("Body is required."))

    return db
      .withConnection { session -> SystemPromptsDao.create(session, NewSystemPrompt(name, version, body)) }
      .map { it.id }
  }
}
