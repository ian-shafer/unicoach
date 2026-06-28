package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * Cap on the per-convo Turns panel, mirroring `STUDENT_PANEL_LIMIT` on the
 * coaching-memory panels. A conversation with more turns shows the first page
 * only, with a trailing disclosure row; full enumeration is via `/convo-request`.
 */
private const val TURNS_PANEL_LIMIT = 50

/**
 * The `convos` entity (RFC 32), surfaced read-only (RFC 81): a conversation's
 * fields plus a panel of its turns. `convos` is mutable in the domain
 * (rename/archive/soft-delete) but the admin exposes no writes — all four write
 * handlers are null, so the engine registers no create/edit/delete routes.
 *
 * The `ROW` is [ConvoWithActivity], carrying the derived `lastActivityAt`. Admin
 * reads pass [SoftDeleteScope.ALL], so deleted convos and their turns are
 * visible; [isDeleted] drives the deleted marker. The turns panel rows link to
 * each turn's `/convo-request/{id}` detail page (canonical routing).
 */
object ConvosResource : AdminResource<ConvoWithActivity, ConvoId> {
  override val slug = "convo"
  override val title = "Conversations"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "convo"),
      AdminField("studentId", "Student ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "student"),
      AdminField("name", "Name", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("lastActivityAt", "Last Activity", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("archivedAt", "Archived", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("deletedAt", "Deleted", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.HasMany("Turns", targetSlug = "convo-request"))

  override fun rowId(row: ConvoWithActivity): ConvoId = row.convo.id

  override fun parseId(raw: String): ConvoId? = runCatching { ConvoId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: ConvoId): String = id.value.toString()

  override fun isDeleted(row: ConvoWithActivity): Boolean = row.convo.deletedAt != null

  override fun cells(row: ConvoWithActivity): Map<String, String> =
    mapOf(
      "id" to
        row.convo.id.value
          .toString(),
      "studentId" to
        row.convo.studentId.value
          .toString(),
      "name" to row.convo.name.value,
      "lastActivityAt" to (row.lastActivityAt?.toString() ?: ""),
      "createdAt" to row.convo.createdAt.toString(),
      "updatedAt" to row.convo.updatedAt.toString(),
      "archivedAt" to (row.convo.archivedAt?.toString() ?: ""),
      "deletedAt" to (row.convo.deletedAt?.toString() ?: ""),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<ConvoWithActivity>> = db.withConnection { session -> ConvosDao.listWithActivity(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: ConvoId,
    includeDeleted: Boolean,
  ): Result<ConvoWithActivity> = db.withConnection { session -> ConvosDao.findByIdWithActivity(session, id, SoftDeleteScope.ALL) }

  override val create: (suspend (Database, Map<String, String>) -> Result<ConvoId>)? = null
  override val update: (suspend (Database, ConvoId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, ConvoId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, ConvoId) -> Result<Unit>)? = null

  /** One panel listing this convo's turns; the Request cell links to `/convo-request/{id}`. */
  override suspend fun resolveEdges(
    db: Database,
    row: ConvoWithActivity,
  ): Result<List<EdgePanel>> {
    val turns =
      db
        .withConnection { session ->
          ConvosDao.listTurns(session, row.convo.id, SoftDeleteScope.ALL, TURNS_PANEL_LIMIT, 0)
        }.getOrElse { return Result.failure(it) }
    return Result.success(listOf(turnsPanel(turns)))
  }

  /**
   * Pure builder: the "Turns" panel (first [TURNS_PANEL_LIMIT]); the Request cell
   * links to `/convo-request/{id}`. A trailing disclosure row is appended when the
   * page filled to the cap, pointing at the canonical `/convo-request` list.
   */
  private fun turnsPanel(turns: List<ConvoTurn>): EdgePanel.Table {
    val columns =
      listOf(
        EdgePanel.Table.Column("Request", refSlug = "convo-request"),
        EdgePanel.Table.Column("Sent", FieldType.TIMESTAMP),
        EdgePanel.Table.Column("Model"),
        EdgePanel.Table.Column("Stop Reason"),
        EdgePanel.Table.Column("In", FieldType.INT),
        EdgePanel.Table.Column("Out", FieldType.INT),
      )
    val rows =
      turns.map { turn ->
        EdgePanel.Table.Row(
          cells =
            listOf(
              turn.request.id.value
                .toString(),
              turn.request.createdAt.toString(),
              turn.request.modelRequested,
              turn.response?.stopReason ?: "",
              turn.response?.inputTokens?.toString() ?: "",
              turn.response?.outputTokens?.toString() ?: "",
            ),
        )
      } + listOfNotNull(truncationRow(turns.size, TURNS_PANEL_LIMIT, columns.size, "convo-request"))
    return EdgePanel.Table(label = "Turns", columns = columns, rows = rows)
  }
}
