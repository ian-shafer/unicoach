package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SynthesisRun
import ed.unicoach.db.models.SynthesisRunId

/**
 * The append-only `synthesis_runs` log (RFC 93), surfaced read-only (RFC 77):
 * one billed synthesis LLM call, with its outcome, provenance, write/drop
 * counts, and the four-column token ledger. Mirrors [ExtractionRunsResource]:
 * all four write handlers are null, so the engine registers no
 * create/edit/delete routes. The table carries no `deleted_at`, so
 * `scope`/`includeDeleted` are ignored.
 *
 * The token and count columns are on the list (`inList = true`) so per-student
 * LLM spend is eyeballable; the secondary provenance columns are detail-only.
 * No edges.
 */
object SynthesisRunsResource : AdminResource<SynthesisRun, SynthesisRunId> {
  override val slug = "synthesis-run"
  override val title = "Synthesis Run"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "synthesis-run"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("outcome", "Outcome", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("modelResolved", "Model", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("commitmentsWritten", "Commitments Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("commitmentsDropped", "Commitments Dropped", FieldType.INT, editable = false, sensitive = false),
      AdminField("inputTokens", "Input Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("outputTokens", "Output Tokens", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField(
        "systemPromptId",
        "System Prompt ID",
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

  override fun rowId(row: SynthesisRun): SynthesisRunId = row.id

  override fun parseId(raw: String): SynthesisRunId? = raw.toLongOrNull()?.let { SynthesisRunId(it) }

  override fun idToPath(id: SynthesisRunId): String = id.value.toString()

  override fun isDeleted(row: SynthesisRun): Boolean = false

  override fun cells(row: SynthesisRun): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "outcome" to row.outcome.value,
      "modelResolved" to (row.modelResolved ?: ""),
      "commitmentsWritten" to row.commitmentsWritten.toString(),
      "commitmentsDropped" to row.commitmentsDropped.toString(),
      "inputTokens" to (row.inputTokens?.toString() ?: ""),
      "outputTokens" to (row.outputTokens?.toString() ?: ""),
      "createdAt" to row.createdAt.toString(),
      "systemPromptId" to row.systemPromptId.value.toString(),
      "provider" to row.provider,
      "cacheReadTokens" to (row.cacheReadTokens?.toString() ?: ""),
      "cacheWriteTokens" to (row.cacheWriteTokens?.toString() ?: ""),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<SynthesisRun>> = db.withConnection { session -> SynthesisRunsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: SynthesisRunId,
    includeDeleted: Boolean,
  ): Result<SynthesisRun> = db.withConnection { session -> SynthesisRunsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<SynthesisRunId>)? = null
  override val update: (suspend (Database, SynthesisRunId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, SynthesisRunId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, SynthesisRunId) -> Result<Unit>)? = null
}
