package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SynthesisRun
import ed.unicoach.db.models.SynthesisRunId

/**
 * The append-only `synthesis_runs` log (RFC 93), surfaced read-only (RFC 77):
 * one billed synthesis pass, with its outcome, provenance, and write/drop counts.
 * Since RFC 106 the provider/model and per-call token spend live in the generic
 * call log; this row links there via `llmRequestId` (refSlug `llm-request`).
 * Mirrors [ExtractionRunsResource]: all four write handlers are null, so the
 * engine registers no create/edit/delete routes. The table carries no
 * `deleted_at`, so `scope`/`includeDeleted` are ignored.
 *
 * The count columns are on the list (`inList = true`); the secondary provenance
 * columns are detail-only. `failureCategory` is on the list too (a
 * triage-at-a-glance "what's failing" column, null on `applied` rows, RFC 101);
 * `failureReason`'s free-text diagnostic is detail-only. No edges.
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
      AdminField("failureCategory", "Failure Category", FieldType.TEXT, editable = false, sensitive = false),
      // BIGINT id — stays TEXT; links to the generic call log (RFC 106)
      AdminField(
        "llmRequestId",
        "LLM Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        refSlug = "llm-request",
      ),
      AdminField("commitmentsWritten", "Commitments Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("commitmentsDropped", "Commitments Dropped", FieldType.INT, editable = false, sensitive = false),
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
      AdminField("failureReason", "Failure Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: SynthesisRun): SynthesisRunId = row.id

  override fun parseId(raw: String): SynthesisRunId? = raw.toLongOrNull()?.let { SynthesisRunId(it) }

  override fun idToPath(id: SynthesisRunId): String = id.value.toString()

  override fun isDeleted(row: SynthesisRun): Boolean = false

  override fun cells(row: SynthesisRun): Map<String, String> {
    // Destructure the outcome ADT back into the flat column-per-field projection:
    // real counts on Applied (0 on Failed), the failure category/reason on Failed
    // ("" on Applied). The exhaustive `when` forces every variant to be handled,
    // so a future third outcome fails to compile rather than rendering defaults.
    val cells =
      when (val outcome = row.outcome) {
        is SynthesisOutcome.Applied -> {
          OutcomeCells(
            commitmentsWritten = outcome.commitmentsWritten.toString(),
            commitmentsDropped = outcome.commitmentsDropped.toString(),
            failureCategory = "",
            failureReason = "",
          )
        }

        is SynthesisOutcome.Failed -> {
          OutcomeCells(
            commitmentsWritten = "0",
            commitmentsDropped = "0",
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
      "llmRequestId" to row.llmRequestId.value.toString(),
      "commitmentsWritten" to cells.commitmentsWritten,
      "commitmentsDropped" to cells.commitmentsDropped,
      "createdAt" to row.createdAt.toString(),
      "systemPromptId" to row.systemPromptId.value.toString(),
      "failureReason" to cells.failureReason,
    )
  }

  /** The outcome-discriminated cell strings for one synthesis-run row. */
  private data class OutcomeCells(
    val commitmentsWritten: String,
    val commitmentsDropped: String,
    val failureCategory: String,
    val failureReason: String,
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
