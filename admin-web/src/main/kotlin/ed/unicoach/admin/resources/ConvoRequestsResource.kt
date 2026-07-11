package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `convo_requests` coaching-extension log (RFC 32/106), surfaced
 * read-only (RFC 81): one coaching turn's identity and its link to the logged LLM
 * call. Since RFC 106 the request I/O envelope (provider / model / params /
 * content) and the reply (content / tokens / stop reason / latency) live in the
 * generic call log; this page shows the coaching columns plus an `llmRequestId`
 * link (refSlug `llm-request`) — the call detail is one click away. The `ROW` is a
 * [ConvoTurn]; all four write handlers are null, so the engine registers no
 * create/edit/delete routes.
 *
 * `responseStopReason` is a convenience at-a-glance column read from the joined
 * call's terminal: blank when the call is absent (`row.call == null`, a mid-flight
 * or crashed turn) or when the terminal is not `Completed` (a failure carries no
 * stop reason). It maps to `""` via a safe cast, so [cells] never NPEs.
 */
object ConvoRequestsResource : AdminResource<ConvoTurn, ConvoRequestId> {
  override val slug = "convo-request"
  override val title = "Requests"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      // `convo_requests.id` is the BIGINT GENERATED ALWAYS AS IDENTITY primary key (not a UUID),
      // so it stays FieldType.TEXT and renders raw rather than compacting like the UUID siblings.
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "convo-request"),
      AdminField("convoId", "Convo", FieldType.UUID, editable = false, sensitive = false, refSlug = "convo"),
      AdminField("createdAt", "Sent", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("kind", "Kind", FieldType.TEXT, editable = false, sensitive = false),
      // BIGINT id — stays TEXT; links to the generic call log (RFC 106) for the full I/O envelope.
      AdminField("llmRequestId", "LLM Request ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "llm-request"),
      AdminField("responseStopReason", "Response Stop Reason", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("turnId", "Turn", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField(
        "systemPromptId",
        "System Prompt",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: ConvoTurn): ConvoRequestId = row.request.id

  override fun parseId(raw: String): ConvoRequestId? = raw.toLongOrNull()?.let { ConvoRequestId(it) }

  override fun idToPath(id: ConvoRequestId): String = id.value.toString()

  override fun isDeleted(row: ConvoTurn): Boolean = false

  override fun cells(row: ConvoTurn): Map<String, String> {
    val request = row.request
    // The response side is the joined call's terminal. A stop reason exists only
    // on a `Completed` terminal; a failure or an absent call leaves it blank.
    val stopReason = (row.call?.response?.outcome as? LlmCallOutcome.Completed)?.stopReason ?: ""
    return mapOf(
      "id" to request.id.value.toString(),
      "convoId" to request.convoId.value.toString(),
      "createdAt" to request.createdAt.toString(),
      "kind" to request.kind.value,
      "llmRequestId" to request.llmRequestId.value.toString(),
      "responseStopReason" to stopReason,
      "turnId" to request.turnId.value.toString(),
      "systemPromptId" to request.systemPromptId.value.toString(),
    )
  }

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<ConvoTurn>> = db.withConnection { session -> ConvosDao.listTurns(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: ConvoRequestId,
    includeDeleted: Boolean,
  ): Result<ConvoTurn> = db.withConnection { session -> ConvosDao.findTurnByRequestId(session, id, SoftDeleteScope.ALL) }

  override val create: (suspend (Database, Map<String, String>) -> Result<ConvoRequestId>)? = null
  override val update: (suspend (Database, ConvoRequestId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, ConvoRequestId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, ConvoRequestId) -> Result<Unit>)? = null
}
