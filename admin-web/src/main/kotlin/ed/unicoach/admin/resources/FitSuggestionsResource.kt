package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.models.FitSuggestion
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The mutable `fit_suggestions` entity (RFC 98), surfaced read-only (RFC 77).
 * Mirrors [CommitmentsResource]: a status-based mutable entity with no
 * `deleted_at` and no admin writes — all four write handlers are null, so the
 * engine registers no create/edit/delete routes and the detail page renders no
 * Edit/Delete/New affordance. `scope`/`includeDeleted` are ignored (no
 * `deleted_at` column).
 *
 * `rationale` (up to 2048 chars) is `inList = false` — kept out of the list table
 * but shown in full on detail. No edges.
 */
object FitSuggestionsResource : AdminResource<FitSuggestion, FitSuggestionId> {
  override val slug = "fit-suggestion"
  override val title = "Fit Suggestion"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "fit-suggestion"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("collegeId", "College ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "college"),
      AdminField("status", "Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("rationale", "Rationale", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
      AdminField("surfacedAt", "Surfaced", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField(
        "surfacedInConvoId",
        "Surfaced In Convo",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "convo",
      ),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: FitSuggestion): FitSuggestionId = row.id

  override fun parseId(raw: String): FitSuggestionId? = runCatching { FitSuggestionId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: FitSuggestionId): String = id.value.toString()

  override fun isDeleted(row: FitSuggestion): Boolean = false

  override fun cells(row: FitSuggestion): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "collegeId" to row.collegeId.value.toString(),
      "status" to row.status.value,
      "createdAt" to row.createdAt.toString(),
      "rationale" to row.rationale,
      "surfacedAt" to (row.surfacedAt?.toString() ?: ""),
      "surfacedInConvoId" to (row.surfacedInConvoId?.value?.toString() ?: ""),
      "updatedAt" to row.updatedAt.toString(),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<FitSuggestion>> = db.withConnection { session -> FitSuggestionsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: FitSuggestionId,
    includeDeleted: Boolean,
  ): Result<FitSuggestion> = db.withConnection { session -> FitSuggestionsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<FitSuggestionId>)? = null
  override val update: (suspend (Database, FitSuggestionId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, FitSuggestionId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, FitSuggestionId) -> Result<Unit>)? = null
}
