package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.FitLensRun
import ed.unicoach.db.models.FitLensRunId
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `fit_lens_runs` log (RFC 98), surfaced read-only (RFC 77): one
 * completed fit-lens pass, with its outcome, provenance (two prompt pins), and
 * suggestion/matches counts. Since RFC 106 the provider/model and per-call token
 * spend live in the generic call log; a pass makes up to two billed calls, linked
 * via `queryLlmRequestId` and `reasonLlmRequestId` (both refSlug `llm-request`;
 * `query` is always present, `reason` blank when the pass bailed before the
 * reason call). Mirrors
 * [SynthesisRunsResource]: all four write handlers are null, so the engine
 * registers no create/edit/delete routes. The table carries no `deleted_at`, so
 * `scope`/`includeDeleted` are ignored.
 *
 * The count columns are on the list (`inList = true`); the secondary provenance
 * columns are detail-only. `failureCategory` is on the list too (a
 * triage-at-a-glance "what's failing" column, null on `applied` rows);
 * `failureReason`'s free-text diagnostic is detail-only. No edges.
 */
object FitLensRunsResource : AdminResource<FitLensRun, FitLensRunId> {
  override val slug = "fit-lens-run"
  override val title = "Fit Lens Run"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "fit-lens-run"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("outcome", "Outcome", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("failureCategory", "Failure Category", FieldType.TEXT, editable = false, sensitive = false),
      // BIGINT ids — stay TEXT; link to the generic call log (RFC 106). A pass makes
      // up to two calls; `reason` is blank when the pass bailed before the reason call.
      AdminField(
        "queryLlmRequestId",
        "Query LLM Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        refSlug = "llm-request",
      ),
      AdminField(
        "reasonLlmRequestId",
        "Reason LLM Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        refSlug = "llm-request",
      ),
      AdminField("suggestionsWritten", "Suggestions Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("matchesConsidered", "Matches Considered", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField(
        "querySystemPromptId",
        "Query Prompt ID",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
      AdminField(
        "reasonSystemPromptId",
        "Reason Prompt ID",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
      AdminField("failureReason", "Failure Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: FitLensRun): FitLensRunId = row.id

  override fun parseId(raw: String): FitLensRunId? = raw.toLongOrNull()?.let { FitLensRunId(it) }

  override fun idToPath(id: FitLensRunId): String = id.value.toString()

  override fun isDeleted(row: FitLensRun): Boolean = false

  override fun cells(row: FitLensRun): Map<String, String> {
    // Destructure the outcome ADT back into the flat column-per-field projection:
    // real suggestions count on Applied (0 on Failed), the failure category/reason
    // on Failed ("" on Applied). The exhaustive `when` forces every variant to be
    // handled, so a future third outcome fails to compile rather than rendering
    // defaults. matches_considered stays a flat field.
    val cells =
      when (val outcome = row.outcome) {
        is FitLensOutcome.Applied -> {
          OutcomeCells(
            suggestionsWritten = outcome.suggestionsWritten.toString(),
            failureCategory = "",
            failureReason = "",
          )
        }

        is FitLensOutcome.Failed -> {
          OutcomeCells(
            suggestionsWritten = "0",
            failureCategory = outcome.category.value,
            failureReason = outcome.reason,
          )
        }
      }
    return mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "outcome" to row.outcome.value,
      "failureCategory" to cells.failureCategory,
      "queryLlmRequestId" to row.queryLlmRequestId.value.toString(),
      "reasonLlmRequestId" to (row.reasonLlmRequestId?.value?.toString() ?: ""),
      "suggestionsWritten" to cells.suggestionsWritten,
      "matchesConsidered" to (row.matchesConsidered?.toString() ?: ""),
      "createdAt" to row.createdAt.toString(),
      "querySystemPromptId" to row.querySystemPromptId.value.toString(),
      "reasonSystemPromptId" to row.reasonSystemPromptId.value.toString(),
      "failureReason" to cells.failureReason,
    )
  }

  /** The outcome-discriminated cell strings for one fit-lens-run row. */
  private data class OutcomeCells(
    val suggestionsWritten: String,
    val failureCategory: String,
    val failureReason: String,
  )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<FitLensRun>> = db.withConnection { session -> FitLensRunsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: FitLensRunId,
    includeDeleted: Boolean,
  ): Result<FitLensRun> = db.withConnection { session -> FitLensRunsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<FitLensRunId>)? = null
  override val update: (suspend (Database, FitLensRunId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, FitLensRunId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, FitLensRunId) -> Result<Unit>)? = null
}
