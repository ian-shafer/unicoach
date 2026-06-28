package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.models.ExtractionRun
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `extraction_runs` log (RFC 66), surfaced read-only (RFC 77):
 * one billed extraction LLM call, with its outcome, provenance, write counts, and
 * the four-column token ledger. All four write handlers are null, so the engine
 * registers no create/edit/delete routes. The table carries no `deleted_at`, so
 * `scope`/`includeDeleted` are ignored.
 *
 * The token and write-count columns are on the list (`inList = true`) so
 * per-student LLM spend is eyeballable; the secondary provenance columns are
 * detail-only. No edges.
 */
object ExtractionRunsResource : AdminResource<ExtractionRun, ExtractionRunId> {
  override val slug = "extraction-run"
  override val title = "Extraction Run"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "extraction-run"),
      AdminField("studentId", "Student ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "student"),
      AdminField("outcome", "Outcome", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("modelResolved", "Model", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("claimsWritten", "Claims Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("inputTokens", "Input Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("outputTokens", "Output Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("convoId", "Convo ID", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("throughRequestId", "Through Request ID", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField(
        "systemPromptId",
        "System Prompt ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
      AdminField("provider", "Provider", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("observationsWritten", "Observations Written", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("claimsSuperseded", "Claims Superseded", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("cacheReadTokens", "Cache Read Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("cacheWriteTokens", "Cache Write Tokens", FieldType.INT, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: ExtractionRun): ExtractionRunId = row.id

  override fun parseId(raw: String): ExtractionRunId? = raw.toLongOrNull()?.let { ExtractionRunId(it) }

  override fun idToPath(id: ExtractionRunId): String = id.value.toString()

  override fun isDeleted(row: ExtractionRun): Boolean = false

  override fun cells(row: ExtractionRun): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "outcome" to row.outcome.value,
      "modelResolved" to (row.modelResolved ?: ""),
      "claimsWritten" to row.claimsWritten.toString(),
      "inputTokens" to (row.inputTokens?.toString() ?: ""),
      "outputTokens" to (row.outputTokens?.toString() ?: ""),
      "createdAt" to row.createdAt.toString(),
      "convoId" to row.convoId.value.toString(),
      "throughRequestId" to row.throughRequestId.value.toString(),
      "systemPromptId" to row.systemPromptId.value.toString(),
      "provider" to row.provider,
      "observationsWritten" to row.observationsWritten.toString(),
      "claimsSuperseded" to row.claimsSuperseded.toString(),
      "cacheReadTokens" to (row.cacheReadTokens?.toString() ?: ""),
      "cacheWriteTokens" to (row.cacheWriteTokens?.toString() ?: ""),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<ExtractionRun>> = db.withConnection { session -> ExtractionRunsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: ExtractionRunId,
    includeDeleted: Boolean,
  ): Result<ExtractionRun> = db.withConnection { session -> ExtractionRunsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<ExtractionRunId>)? = null
  override val update: (suspend (Database, ExtractionRunId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, ExtractionRunId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, ExtractionRunId) -> Result<Unit>)? = null
}
