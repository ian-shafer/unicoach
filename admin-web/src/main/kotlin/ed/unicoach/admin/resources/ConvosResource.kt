package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.CustomAction
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.queue.ExtractionPayload
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
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
class ConvosResource(
  private val queueService: QueueService,
) : AdminResource<ConvoWithActivity, ConvoId> {
  private val logger = LoggerFactory.getLogger(ConvosResource::class.java)

  override val slug = "convo"
  override val title = "Conversations"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "convo"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
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

  /**
   * The manual extraction trigger (RFC 100). Always enabled
   * (`disabledReason = { null }`): the downstream watermark already no-ops a
   * redundant run. The [CustomAction.helpText] is static caption text
   * documenting the no-op condition, not a live per-row gate check.
   */
  override val customActions =
    listOf(
      CustomAction<ConvoWithActivity>(
        label = "Trigger extraction",
        pathSuffix = "trigger-extraction",
        disabledReason = { null },
        helpText =
          "No-ops if this conversation has no turns past its last applied " +
            "extraction (watermark).",
      ),
    )

  /**
   * Registers the extraction trigger route. Parses the id (malformed → redirect
   * to the list, per the extra-route convention), resolves the convo's latest
   * `convo_requests.id` for the window boundary, and enqueues the identical
   * `EXTRACT_CONVERSATION` payload the automatic per-turn path uses — no bypass
   * flag, so the same watermark check applies (the manual-trigger invariant).
   * A convo with zero requests short-circuits to a 404 with no job enqueued
   * (there is nothing to extract through). Enqueue is fire-and-forget (not a
   * required enqueue under ASYNC_WORK.md Rule 2); on success it redirects to the
   * convo's detail page, matching [PeriodicJobsResource] (no confirmation
   * banner). A queue failure renders the shared DAO-error page.
   */
  override fun registerExtraRoutes(
    scope: Route,
    db: Database,
  ) {
    scope.post("/$slug/{id}/trigger-extraction") {
      val id = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      val maxRequestId =
        db
          .withConnection { session -> ConvosDao.findLatestRequestIdForConvo(session, id) }
          .getOrElse { return@post call.respondDaoError(it) }
      if (maxRequestId == null) {
        // respondDaoError's NotFound branch renders a generic 404 without reading
        // the message, so surface the convo id here (mirroring PeriodicJobsResource).
        val message = "Conversation [${id.value}] has no turns to extract."
        logger.warn(message)
        return@post call.respondDaoError(NotFoundException(message))
      }
      val payload = ExtractionPayload(convoId = id.value.toString(), throughRequestId = maxRequestId.value).asJson()
      val result = queueService.enqueue(JobType.EXTRACT_CONVERSATION, payload)
      call.respondEnqueueOutcome(result, "/$slug/${id.value}", "extraction", id.value, logger)
    }
  }

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
        // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
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
