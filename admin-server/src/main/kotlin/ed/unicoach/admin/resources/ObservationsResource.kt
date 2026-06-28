package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ClaimSupportDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `observations` log (RFC 66), surfaced read-only (RFC 77). All
 * four write handlers are null (the log is insert-only in the domain and the
 * admin exposes no writes), so the engine registers no create/edit/delete routes.
 * The table carries no `deleted_at`, so `scope`/`includeDeleted` are ignored.
 *
 * `quote` (up to 4096 chars) is `inList = false` — kept out of the list table but
 * shown in full on detail.
 */
object ObservationsResource : AdminResource<Observation, ObservationId> {
  override val slug = "observation"
  override val title = "Observation"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "observation"),
      AdminField("studentId", "Student ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "student"),
      AdminField("convoId", "Convo ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "convo"),
      AdminField("utteredAt", "Uttered", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField(
        "sourceRequestId",
        "Source Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "convo-request",
      ),
      AdminField("quote", "Quote", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.HasMany("Supported claims", targetSlug = "claim"))

  override fun rowId(row: Observation): ObservationId = row.id

  override fun parseId(raw: String): ObservationId? = raw.toLongOrNull()?.let { ObservationId(it) }

  override fun idToPath(id: ObservationId): String = id.value.toString()

  override fun isDeleted(row: Observation): Boolean = false

  override fun cells(row: Observation): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "convoId" to row.convoId.value.toString(),
      "utteredAt" to row.utteredAt.toString(),
      "createdAt" to row.createdAt.toString(),
      "sourceRequestId" to row.sourceRequestId.value.toString(),
      "quote" to row.quote,
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<Observation>> = db.withConnection { session -> ObservationsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: ObservationId,
    includeDeleted: Boolean,
  ): Result<Observation> = db.withConnection { session -> ObservationsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<ObservationId>)? = null
  override val update: (suspend (Database, ObservationId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, ObservationId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, ObservationId) -> Result<Unit>)? = null

  /** One panel listing the claims this observation supports; rows link to `/claim/{id}`. */
  override suspend fun resolveEdges(
    db: Database,
    row: Observation,
  ): Result<List<EdgePanel>> {
    val claims =
      db
        .withConnection { session -> ClaimSupportDao.listClaimsForObservation(session, row.id) }
        .getOrElse { return Result.failure(it) }
    return Result.success(listOf(supportedClaimsPanel(claims)))
  }

  /** Pure builder: the "Supported claims" panel; rows link to `/claim/{id}`. */
  private fun supportedClaimsPanel(claims: List<Claim>): EdgePanel.Table =
    EdgePanel.Table(
      label = "Supported claims",
      columns =
        listOf(
          EdgePanel.Table.Column("ID", refSlug = "claim"),
          EdgePanel.Table.Column("Status"),
          EdgePanel.Table.Column("Topic"),
          EdgePanel.Table.Column("Statement"),
        ),
      rows =
        claims.map { c ->
          EdgePanel.Table.Row(
            cells = listOf(c.id.value.toString(), c.status.value, c.topic.value, c.statement),
          )
        },
    )
}
