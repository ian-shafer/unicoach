package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The `colleges` reference entity (RFC 67), surfaced read-only (RFC 82) with a
 * version-history panel showing how a school's facts changed across Scorecard
 * ingests. [kind] is the honest classification — `colleges` is a mutable
 * versioned entity — while read-only is expressed through handler nullability
 * (the engine branches on handler nullability, not [kind]): `create`, `update`,
 * `delete`, `undelete` are all null. The table carries no `deleted_at`, so
 * [isDeleted] is always false and `scope`/`includeDeleted` are ignored.
 *
 * The list stays narrow (`name`/`city`/`state`/`control`/`admissionRate`/
 * `netPrice`); the detail page shows the full curated row plus `version` and the
 * timestamps. `college_programs` is intentionally not surfaced as an edge.
 */
object CollegesResource : AdminResource<College, CollegeId> {
  override val slug = "college"
  override val title = "College"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, inList = false, refSlug = "college"),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("unitId", "Unit ID", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("opeid", "OPEID", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("name", "Name", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("city", "City", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("state", "State", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("region", "Region", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("locale", "Locale", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("latitude", "Latitude", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("longitude", "Longitude", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("control", "Control", FieldType.INT, editable = false, sensitive = false),
      AdminField("undergradEnrollment", "Undergrad Enrollment", FieldType.INT, editable = false, sensitive = false, inList = false),
      // admissionRate/graduationRate/pctPell are 0.0-1.0 decimal ratios; TEXT is used
      // rather than a numeric/INT FieldType, which would discard the fraction.
      AdminField("admissionRate", "Admission Rate", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("satAvg", "SAT Avg", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("costAttendance", "Cost of Attendance", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("netPrice", "Net Price", FieldType.INT, editable = false, sensitive = false),
      AdminField("tuitionInState", "Tuition In-State", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("tuitionOutState", "Tuition Out-of-State", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("graduationRate", "Graduation Rate", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("medianEarnings", "Median Earnings", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("pctPell", "Pct Pell", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("website", "Website", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = listOf(AdminEdge.History("Version history"))

  override fun rowId(row: College): CollegeId = row.id

  override fun parseId(raw: String): CollegeId? = runCatching { CollegeId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: CollegeId): String = id.value.toString()

  override fun isDeleted(row: College): Boolean = false

  override fun cells(row: College): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "version" to row.version.toString(),
      "unitId" to row.unitId.toString(),
      "opeid" to (row.opeid ?: ""),
      "name" to row.name,
      "city" to row.city,
      "state" to row.state,
      "region" to (row.region?.toString() ?: ""),
      "locale" to (row.locale?.toString() ?: ""),
      "latitude" to (row.latitude?.toString() ?: ""),
      "longitude" to (row.longitude?.toString() ?: ""),
      "control" to row.control.toString(),
      "undergradEnrollment" to (row.undergradEnrollment?.toString() ?: ""),
      "admissionRate" to (row.admissionRate?.toString() ?: ""),
      "satAvg" to (row.satAvg?.toString() ?: ""),
      "costAttendance" to (row.costAttendance?.toString() ?: ""),
      "netPrice" to (row.netPrice?.toString() ?: ""),
      "tuitionInState" to (row.tuitionInState?.toString() ?: ""),
      "tuitionOutState" to (row.tuitionOutState?.toString() ?: ""),
      "graduationRate" to (row.graduationRate?.toString() ?: ""),
      "medianEarnings" to (row.medianEarnings?.toString() ?: ""),
      "pctPell" to (row.pctPell?.toString() ?: ""),
      "website" to (row.website ?: ""),
      "createdAt" to row.createdAt.toString(),
      "updatedAt" to row.updatedAt.toString(),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope, // ignored: colleges has no deleted_at
  ): Result<List<College>> = db.withConnection { session -> CollegesDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: CollegeId,
    includeDeleted: Boolean, // ignored: colleges has no deleted_at
  ): Result<College> = db.withConnection { session -> CollegesDao.findById(session, id) }

  override suspend fun resolveEdges(
    db: Database,
    row: College,
  ): Result<List<EdgePanel>> {
    val versions =
      db
        .withConnection { session -> CollegesDao.listVersions(session, row.id) }
        .getOrElse { return Result.failure(it) }
    val historyPanel =
      EdgePanel.Table(
        label = "Version history",
        columns =
          listOf(
            EdgePanel.Table.Column("Version", FieldType.INT),
            EdgePanel.Table.Column("Name"),
            EdgePanel.Table.Column("City"),
            EdgePanel.Table.Column("State"),
            EdgePanel.Table.Column("Control", FieldType.INT),
            EdgePanel.Table.Column("Admission Rate"),
            EdgePanel.Table.Column("Net Price", FieldType.INT),
            EdgePanel.Table.Column("Updated", FieldType.TIMESTAMP),
          ),
        rows =
          versions.map { v ->
            EdgePanel.Table.Row(
              cells =
                listOf(
                  v.entity.version.toString(),
                  v.entity.name,
                  v.entity.city,
                  v.entity.state,
                  v.entity.control.toString(),
                  v.entity.admissionRate?.toString() ?: "",
                  v.entity.netPrice?.toString() ?: "",
                  v.entity.updatedAt.toString(),
                ),
            )
          },
      )
    return Result.success(listOf(historyPanel))
  }

  override val create: (suspend (Database, Map<String, String>) -> Result<CollegeId>)? = null
  override val update: (suspend (Database, CollegeId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, CollegeId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, CollegeId) -> Result<Unit>)? = null
}
