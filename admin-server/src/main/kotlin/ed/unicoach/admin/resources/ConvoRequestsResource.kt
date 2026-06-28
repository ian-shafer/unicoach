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
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `convo_requests` log (RFC 32), surfaced read-only (RFC 81):
 * one request paired with its 1:1 response, so the reply's content, stop reason,
 * token counts, and latency render on the request's detail page. The `ROW` is a
 * [ConvoTurn]; nothing foreign-keys a `convo_responses` row, so no separate
 * response resource exists. All four write handlers are null, so the engine
 * registers no create/edit/delete routes.
 *
 * Response cells are blank when `row.response == null` (a mid-flight or failed
 * turn), and an individual response sub-field is blank when its own value is null
 * even though `row.response` is present — a transport-error turn carries a
 * non-null `row.response` with null `content` and null token counts. Each
 * nullable sub-field maps to `""` via `?.toString() ?: ""` (never `.toString()`
 * on null), so [cells] never NPEs; the render layer blank-suppresses the blanks.
 */
object ConvoRequestsResource : AdminResource<ConvoTurn, ConvoRequestId> {
  override val slug = "convo-request"
  override val title = "Requests"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "convo-request"),
      AdminField("convoId", "Convo", FieldType.TEXT, editable = false, sensitive = false, refSlug = "convo"),
      AdminField("createdAt", "Sent", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("provider", "Provider", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("modelRequested", "Model Requested", FieldType.TEXT, editable = false, sensitive = false),
      AdminField(
        "systemPromptId",
        "System Prompt",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
      AdminField("requestParams", "Request Params", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("content", "Request Content", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("responseStopReason", "Response Stop Reason", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("responseModelResolved", "Response Model", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("responseInputTokens", "Input Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("responseOutputTokens", "Output Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("responseCacheReadTokens", "Cache Read Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("responseCacheWriteTokens", "Cache Write Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("responseLatencyMs", "Latency (ms)", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField(
        "responseProviderRequestId",
        "Provider Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
      ),
      AdminField("responseContent", "Response Content", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("responseCreatedAt", "Replied", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: ConvoTurn): ConvoRequestId = row.request.id

  override fun parseId(raw: String): ConvoRequestId? = raw.toLongOrNull()?.let { ConvoRequestId(it) }

  override fun idToPath(id: ConvoRequestId): String = id.value.toString()

  override fun isDeleted(row: ConvoTurn): Boolean = false

  override fun cells(row: ConvoTurn): Map<String, String> {
    val request = row.request
    val response = row.response
    return mapOf(
      "id" to request.id.value.toString(),
      "convoId" to request.convoId.value.toString(),
      "createdAt" to request.createdAt.toString(),
      "provider" to request.provider,
      "modelRequested" to request.modelRequested,
      "systemPromptId" to request.systemPromptId.value.toString(),
      "requestParams" to (request.requestParams?.toString() ?: ""),
      "content" to request.content.toString(),
      "responseStopReason" to (response?.stopReason ?: ""),
      "responseModelResolved" to (response?.modelResolved ?: ""),
      "responseInputTokens" to (response?.inputTokens?.toString() ?: ""),
      "responseOutputTokens" to (response?.outputTokens?.toString() ?: ""),
      "responseCacheReadTokens" to (response?.cacheReadTokens?.toString() ?: ""),
      "responseCacheWriteTokens" to (response?.cacheWriteTokens?.toString() ?: ""),
      "responseLatencyMs" to (response?.latencyMs?.toString() ?: ""),
      "responseProviderRequestId" to (response?.providerRequestId ?: ""),
      "responseContent" to (response?.content?.toString() ?: ""),
      "responseCreatedAt" to (response?.createdAt?.toString() ?: ""),
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
