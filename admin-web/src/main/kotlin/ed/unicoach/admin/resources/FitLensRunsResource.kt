package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.models.FitLensRun
import ed.unicoach.db.models.FitLensRunId
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `fit_lens_runs` log (RFC 98), surfaced read-only (RFC 77): one
 * completed fit-lens pass, with its outcome, provenance (two prompt pins,
 * provider/model), suggestion/matches counts, and the four-column token ledger
 * (summing the pass's two billed calls). Mirrors [SynthesisRunsResource]: all
 * four write handlers are null, so the engine registers no create/edit/delete
 * routes. The table carries no `deleted_at`, so `scope`/`includeDeleted` are
 * ignored.
 *
 * The token and count columns are on the list (`inList = true`) so per-student
 * LLM spend is eyeballable; the secondary provenance columns are detail-only.
 * No edges.
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
      AdminField("modelResolved", "Model", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("suggestionsWritten", "Suggestions Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("matchesConsidered", "Matches Considered", FieldType.INT, editable = false, sensitive = false),
      AdminField("inputTokens", "Input Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("outputTokens", "Output Tokens", FieldType.INT, editable = false, sensitive = false),
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
      AdminField("provider", "Provider", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("cacheReadTokens", "Cache Read Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("cacheWriteTokens", "Cache Write Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: FitLensRun): FitLensRunId = row.id

  override fun parseId(raw: String): FitLensRunId? = raw.toLongOrNull()?.let { FitLensRunId(it) }

  override fun idToPath(id: FitLensRunId): String = id.value.toString()

  override fun isDeleted(row: FitLensRun): Boolean = false

  override fun cells(row: FitLensRun): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "outcome" to row.outcome.value,
      "modelResolved" to (row.modelResolved ?: ""),
      "suggestionsWritten" to row.suggestionsWritten.toString(),
      "matchesConsidered" to (row.matchesConsidered?.toString() ?: ""),
      "inputTokens" to (row.inputTokens?.toString() ?: ""),
      "outputTokens" to (row.outputTokens?.toString() ?: ""),
      "createdAt" to row.createdAt.toString(),
      "querySystemPromptId" to row.querySystemPromptId.value.toString(),
      "reasonSystemPromptId" to row.reasonSystemPromptId.value.toString(),
      "provider" to row.provider,
      "cacheReadTokens" to (row.cacheReadTokens?.toString() ?: ""),
      "cacheWriteTokens" to (row.cacheWriteTokens?.toString() ?: ""),
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
