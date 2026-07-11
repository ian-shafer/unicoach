package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.admin.render.adminPage
import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.models.LlmCall
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.SoftDeleteScope
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr
import java.time.Duration

/**
 * The minimum age a logged call must reach before the unlinked-call list will
 * surface it (RFC 106). An in-flight or just-written call whose owning domain row
 * has not yet committed is not an orphan; only a call this old with no referencing
 * row is a genuine crash-window orphan. Parameterized here rather than inlined.
 */
private val UNLINKED_MIN_AGE: Duration = Duration.ofHours(1)

/** The page cap for the on-demand unlinked-call anti-join. Parameterized, not inline. */
private const val UNLINKED_LIMIT = 200

/**
 * The provider-agnostic LLM call log (RFC 106), surfaced read-only as a top-level
 * [AdminKind.LOG] resource at `/llm-request/{id}`. The `ROW` is an [LlmCall]: one
 * `llm_requests` row paired with its 1:1 `llm_responses` (nullable — the request
 * committed but the terminal has not) and its 0..1 `llm_responses_raw` verbatim
 * payload. This is the single rendering path for every logged call, chat and
 * structured alike; each run and each convo-request links here by `llm_request_id`.
 * All four write handlers are null, so the engine registers no create/edit/delete
 * routes.
 *
 * The response sub-fields are blank when `row.response == null`; the raw payload
 * is blank when `row.raw == null`. On the response, the [LlmCallOutcome] ADT is
 * destructured by an exhaustive `when`: a `Completed` sets content/model/stop and
 * leaves `reason` blank; a `Failed` sets `reason` and leaves those blank. Each
 * nullable sibling (tokens, `providerRequestId`) maps to `""` via
 * `?.toString() ?: ""`, so [cells] never NPEs.
 */
object LlmRequestsResource : AdminResource<LlmCall, LlmRequestId> {
  override val slug = "llm-request"
  override val title = "LLM Requests"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      // `llm_requests.id` is a BIGINT identity PK — stays TEXT and renders raw.
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "llm-request"),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("provider", "Provider", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("modelRequested", "Model Requested", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("outcome", "Outcome", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("inputTokens", "Input Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("outputTokens", "Output Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("latencyMs", "Latency (ms)", FieldType.INT, editable = false, sensitive = false),
      AdminField("system", "System", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
      AdminField("maxTokens", "Max Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("content", "Request Content", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("tools", "Tools", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("toolChoice", "Tool Choice", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("params", "Params", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("responseContent", "Response Content", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("stopReason", "Stop Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("modelResolved", "Model Resolved", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("cacheReadTokens", "Cache Read Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("cacheWriteTokens", "Cache Write Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField(
        "providerRequestId",
        "Provider Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
      ),
      AdminField("reason", "Failure Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("rawPayload", "Raw Payload", FieldType.JSON, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: LlmCall): LlmRequestId = row.request.id

  override fun parseId(raw: String): LlmRequestId? = raw.toLongOrNull()?.let { LlmRequestId(it) }

  override fun idToPath(id: LlmRequestId): String = id.value.toString()

  override fun isDeleted(row: LlmCall): Boolean = false

  override fun cells(row: LlmCall): Map<String, String> {
    val request = row.request
    val response = row.response
    // Destructure the response's outcome ADT into the flat column-per-field
    // projection: content/model/stop set on Completed (reason ""), reason set on
    // Failed (those ""). The exhaustive `when` forces every variant to be handled,
    // so a future third outcome fails to compile rather than rendering defaults.
    // Blank on `response == null` — a request committed with no terminal yet.
    val outcomeCells =
      when (val outcome = response?.outcome) {
        is LlmCallOutcome.Completed -> {
          ResponseOutcomeCells(
            responseContent = outcome.content.toString(),
            modelResolved = outcome.modelResolved,
            stopReason = outcome.stopReason,
            reason = "",
          )
        }

        is LlmCallOutcome.Failed -> {
          ResponseOutcomeCells(
            responseContent = "",
            modelResolved = "",
            stopReason = "",
            reason = outcome.reason,
          )
        }

        null -> {
          ResponseOutcomeCells(responseContent = "", modelResolved = "", stopReason = "", reason = "")
        }
      }
    return mapOf(
      "id" to request.id.value.toString(),
      "createdAt" to request.createdAt.toString(),
      "provider" to request.provider,
      "modelRequested" to request.modelRequested,
      "outcome" to (response?.outcome?.value ?: ""),
      "inputTokens" to (response?.inputTokens?.toString() ?: ""),
      "outputTokens" to (response?.outputTokens?.toString() ?: ""),
      "latencyMs" to (response?.latencyMs?.toString() ?: ""),
      "system" to (request.system ?: ""),
      "maxTokens" to request.maxTokens.toString(),
      "content" to request.content.toString(),
      "tools" to (request.tools?.toString() ?: ""),
      "toolChoice" to (request.toolChoice?.toString() ?: ""),
      "params" to (request.params?.toString() ?: ""),
      "responseContent" to outcomeCells.responseContent,
      "stopReason" to outcomeCells.stopReason,
      "modelResolved" to outcomeCells.modelResolved,
      "cacheReadTokens" to (response?.cacheReadTokens?.toString() ?: ""),
      "cacheWriteTokens" to (response?.cacheWriteTokens?.toString() ?: ""),
      "providerRequestId" to (response?.providerRequestId ?: ""),
      "reason" to outcomeCells.reason,
      "rawPayload" to (row.raw?.payload?.toString() ?: ""),
    )
  }

  /** The response-outcome-discriminated cell strings for one logged call. */
  private data class ResponseOutcomeCells(
    val responseContent: String,
    val modelResolved: String,
    val stopReason: String,
    val reason: String,
  )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<LlmCall>> = db.withConnection { session -> LlmCallsDao.listCalls(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: LlmRequestId,
    includeDeleted: Boolean,
  ): Result<LlmCall> = db.withConnection { session -> LlmCallsDao.findCallByRequestId(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<LlmRequestId>)? = null
  override val update: (suspend (Database, LlmRequestId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, LlmRequestId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, LlmRequestId) -> Result<Unit>)? = null

  /**
   * The dedicated unlinked-call report (RFC 106 best-effort linkage). A single GET
   * at `/llm-request/unlinked` whose one backing query is the anti-join
   * [LlmCallsDao.listUnlinkedCalls] — calls older than [UNLINKED_MIN_AGE] that no
   * domain row (`convo_requests`, `extraction_runs`, `synthesis_runs`,
   * `fit_lens_runs`) references. Orphans are rare and permanent (only a hard crash
   * between the response write and the domain-row write produces one), so the list
   * is near-empty and runs only on demand. The literal `/unlinked` segment wins
   * over the parameterized `/{id}` route in Ktor's matcher, so `parseId` never sees
   * it. Rendered as a plain table whose Request cell links to the call detail.
   */
  override fun registerExtraRoutes(
    scope: Route,
    db: Database,
  ) {
    scope.get("/$slug/unlinked") {
      val calls =
        db
          .withConnection { session -> LlmCallsDao.listUnlinkedCalls(session, UNLINKED_MIN_AGE, UNLINKED_LIMIT, 0) }
          .getOrElse { return@get call.respondDaoError(it) }
      call.respondHtml {
        adminPage("Unlinked LLM Calls") {
          h1 { +"Unlinked LLM Calls" }
          p {
            +(
              "Logged calls older than $UNLINKED_MIN_AGE that no domain row references " +
                "(crash-window orphans — fully logged but attributed to no student)."
            )
          }
          if (calls.isEmpty()) {
            p { +"(none)" }
          } else {
            table {
              tr {
                th { +"Request" }
                th { +"Provider" }
                th { +"Model" }
                th { +"Outcome" }
              }
              calls.forEach { call ->
                val id =
                  call.request.id.value
                    .toString()
                tr {
                  td { a(href = "/$slug/$id") { +id } }
                  td { +call.request.provider }
                  td { +call.request.modelRequested }
                  td { +(call.response?.outcome?.value ?: "") }
                }
              }
            }
          }
        }
      }
    }
  }
}
