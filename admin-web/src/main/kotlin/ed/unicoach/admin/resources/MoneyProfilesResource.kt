package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The `money_profiles` entity (RFC 134), surfaced read-only-plus-history --
 * [CollegeListEntriesResource] is the precedent this follows exactly.
 * `create`/`update`/`delete`/`undelete` are all null: the entity is
 * student-writable through its own domain surfaces (REST PUT and the
 * `update_money_profile` chat tool), and admin here is read-only, not a
 * parallel write path. The two value columns (income band, residency state)
 * are `sensitive = true`: family finances get the admin UI's existing
 * sensitive-field redaction, in list, detail, and the history panel alike.
 */
object MoneyProfilesResource : AdminResource<MoneyProfile, MoneyProfileId> {
  override val slug = "money-profile"
  override val title = "Money Profiles"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "money-profile"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("incomeBandStatus", "Income Band Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("incomeBand", "Income Band", FieldType.TEXT, editable = false, sensitive = true),
      AdminField("residencyStatus", "Residency Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("residencyState", "Residency State", FieldType.TEXT, editable = false, sensitive = true),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("deletedAt", "Deleted", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.History("Version history"))

  override fun rowId(row: MoneyProfile): MoneyProfileId = row.id

  override fun parseId(raw: String): MoneyProfileId? = runCatching { MoneyProfileId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: MoneyProfileId): String = id.value.toString()

  override fun isDeleted(row: MoneyProfile): Boolean = row.deletedAt != null

  override fun cells(row: MoneyProfile): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "incomeBandStatus" to row.incomeBandStatus.value,
      "incomeBand" to (row.incomeBand?.value ?: ""),
      "residencyStatus" to row.residencyStatus.value,
      "residencyState" to (row.residencyState ?: ""),
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
  ): Result<List<MoneyProfile>> = db.withConnection { session -> MoneyProfilesDao.list(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: MoneyProfileId,
    includeDeleted: Boolean,
  ): Result<MoneyProfile> = db.withConnection { session -> MoneyProfilesDao.findById(session, id, SoftDeleteScope.ALL) }

  override val create: (suspend (Database, Map<String, String>) -> Result<MoneyProfileId>)? = null
  override val update: (suspend (Database, MoneyProfileId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, MoneyProfileId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, MoneyProfileId) -> Result<Unit>)? = null

  /**
   * The version-history panel ([CollegesResource]'s precedent). The sensitive
   * value columns render their per-version STATUS only, never the value: the
   * redaction that [AdminField.sensitive] applies to list/detail must not leak
   * back through history rows.
   */
  override suspend fun resolveEdges(
    db: Database,
    row: MoneyProfile,
  ): Result<List<EdgePanel>> {
    val versions =
      db
        .withConnection { session -> MoneyProfilesDao.listVersions(session, row.id) }
        .getOrElse { return Result.failure(it) }
    val historyPanel =
      EdgePanel.Table(
        label = "Version history",
        columns =
          listOf(
            EdgePanel.Table.Column("Version", FieldType.INT),
            EdgePanel.Table.Column("Income Band Status"),
            EdgePanel.Table.Column("Residency Status"),
            EdgePanel.Table.Column("Updated", FieldType.TIMESTAMP),
            EdgePanel.Table.Column("Deleted", FieldType.TIMESTAMP),
          ),
        rows =
          versions.map { v ->
            EdgePanel.Table.Row(
              cells =
                listOf(
                  v.entity.version.toString(),
                  v.entity.incomeBandStatus.value,
                  v.entity.residencyStatus.value,
                  v.entity.updatedAt.toString(),
                  v.entity.deletedAt?.toString() ?: "",
                ),
            )
          },
      )
    return Result.success(listOf(historyPanel))
  }
}
