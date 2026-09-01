package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegeListEntrySupportDao
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The `college_list_entries` entity (RFC 91), surfaced read-only-plus-history.
 * `college_list_entries` carries `deleted_at` and OCC `version`, making it
 * structurally comparable to `convos` (RFC 32/81) rather than `claims` (no
 * `deleted_at`): [ConvosResource] is the precedent this follows exactly.
 * `create`/`update`/`delete`/`undelete` are all null because the entity is
 * user-writable through its own domain surface (REST for `college_list_entries`,
 * same as `convos`) and admin here is read-only-plus-history, not a parallel
 * write path. `list`/`get` forward the engine's [SoftDeleteScope] rather than
 * ignoring it, so soft-deleted entries stay visible for audit.
 */
object CollegeListEntriesResource : AdminResource<CollegeListEntry, CollegeListEntryId> {
  override val slug = "college-list-entry"
  override val title = "College List Entries"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "college-list-entry"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("collegeId", "College ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "college"),
      AdminField("status", "Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("reasons", "Reasons", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
      // The per-college living-plan override (RFC 152). Sensitive like its
      // money-profile twin: where a family plans to live is a family fact, and
      // one column of it must not be redacted in one resource and printed in
      // another.
      AdminField("livingPlan", "Living Plan", FieldType.TEXT, editable = false, sensitive = true, inList = false),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("deletedAt", "Deleted", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.HasMany("Supporting observations", targetSlug = "observation"))

  override fun rowId(row: CollegeListEntry): CollegeListEntryId = row.id

  override fun parseId(raw: String): CollegeListEntryId? = runCatching { CollegeListEntryId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: CollegeListEntryId): String = id.value.toString()

  override fun isDeleted(row: CollegeListEntry): Boolean = row.deletedAt != null

  override fun cells(row: CollegeListEntry): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "collegeId" to row.collegeId.value.toString(),
      "status" to row.status.value,
      "reasons" to (row.reasons ?: ""),
      "livingPlan" to (row.livingPlan?.value ?: ""),
      "version" to row.version.toString(),
      "createdAt" to row.createdAt.toString(),
      "updatedAt" to row.updatedAt.toString(),
      "deletedAt" to (row.deletedAt?.toString() ?: ""),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<CollegeListEntry>> = db.withConnection { session -> CollegeListEntriesDao.list(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: CollegeListEntryId,
    includeDeleted: Boolean,
  ): Result<CollegeListEntry> = db.withConnection { session -> CollegeListEntriesDao.findById(session, id, SoftDeleteScope.ALL) }

  override val create: (suspend (Database, Map<String, String>) -> Result<CollegeListEntryId>)? = null
  override val update: (suspend (Database, CollegeListEntryId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, CollegeListEntryId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, CollegeListEntryId) -> Result<Unit>)? = null

  /** One panel listing the observations backing this entry; rows link to `/observation/{id}`. */
  override suspend fun resolveEdges(
    db: Database,
    row: CollegeListEntry,
  ): Result<List<EdgePanel>> {
    val observations =
      db
        .withConnection { session -> CollegeListEntrySupportDao.listObservationsForEntry(session, row.id) }
        .getOrElse { return Result.failure(it) }
    return Result.success(listOf(supportingObservationsPanel(observations)))
  }

  /** Pure builder: the "Supporting observations" panel, mirroring [ClaimsResource]'s panel. */
  private fun supportingObservationsPanel(observations: List<Observation>): EdgePanel.Table =
    EdgePanel.Table(
      label = "Supporting observations",
      columns =
        listOf(
          EdgePanel.Table.Column("ID", refSlug = "observation"),
          EdgePanel.Table.Column("Convo", refSlug = "convo"),
          EdgePanel.Table.Column("Source Request", refSlug = "convo-request"),
          EdgePanel.Table.Column("Uttered", FieldType.TIMESTAMP),
          EdgePanel.Table.Column("Quote"),
        ),
      rows =
        observations.map { o ->
          EdgePanel.Table.Row(
            cells =
              listOf(
                o.id.value.toString(),
                o.convoId.value.toString(),
                o.sourceRequestId.value.toString(),
                o.utteredAt.toString(),
                o.quote,
              ),
          )
        },
    )
}
