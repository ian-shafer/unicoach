package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRun
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The append-only `extraction_runs` log (RFC 66), surfaced read-only (RFC 77):
 * one billed extraction pass, with its outcome, provenance, and write counts.
 * Since RFC 106 the provider/model and per-call token spend live in the generic
 * call log; this row links there via `llmRequestId` (refSlug `llm-request`), one
 * click away. All four write handlers are null, so the engine registers no
 * create/edit/delete routes. The table carries no `deleted_at`, so
 * `scope`/`includeDeleted` are ignored.
 *
 * The write-count columns are on the list (`inList = true`); the secondary
 * provenance columns are detail-only. `failureCategory` is on the list too (a
 * triage-at-a-glance "what's failing" column, null on `applied` rows, RFC 101);
 * `failureReason`'s free-text diagnostic is detail-only. No edges.
 */
object ExtractionRunsResource : AdminResource<ExtractionRun, ExtractionRunId> {
  override val slug = "extraction-run"
  override val title = "Extraction Run"
  override val kind = AdminKind.LOG
  override val topLevel = true

  override val fields =
    listOf(
      // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false, refSlug = "extraction-run"),
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
      AdminField("claimsWritten", "Claims Written", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("convoId", "Convo ID", FieldType.UUID, editable = false, sensitive = false, inList = false, refSlug = "convo"),
      // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
      AdminField(
        "throughRequestId",
        "Through Request ID",
        FieldType.TEXT,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "convo-request",
      ),
      AdminField(
        "systemPromptId",
        "System Prompt ID",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "system-prompt",
      ),
      AdminField("observationsWritten", "Observations Written", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("claimsSuperseded", "Claims Superseded", FieldType.INT, editable = false, sensitive = false, inList = false),
      AdminField("failureReason", "Failure Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: ExtractionRun): ExtractionRunId = row.id

  override fun parseId(raw: String): ExtractionRunId? = raw.toLongOrNull()?.let { ExtractionRunId(it) }

  override fun idToPath(id: ExtractionRunId): String = id.value.toString()

  override fun isDeleted(row: ExtractionRun): Boolean = false

  override fun cells(row: ExtractionRun): Map<String, String> {
    // Destructure the outcome ADT back into the flat column-per-field projection:
    // real counts on Applied (0 on Failed), the failure category/reason on Failed
    // ("" on Applied). The exhaustive `when` forces every variant to be handled,
    // so a future third outcome fails to compile rather than rendering defaults.
    val cells =
      when (val outcome = row.outcome) {
        is ExtractionOutcome.Applied -> {
          OutcomeCells(
            observationsWritten = outcome.observationsWritten.toString(),
            claimsWritten = outcome.claimsWritten.toString(),
            claimsSuperseded = outcome.claimsSuperseded.toString(),
            failureCategory = "",
            failureReason = "",
          )
        }

        is ExtractionOutcome.Failed -> {
          OutcomeCells(
            observationsWritten = "0",
            claimsWritten = "0",
            claimsSuperseded = "0",
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
      "claimsWritten" to cells.claimsWritten,
      "createdAt" to row.createdAt.toString(),
      "convoId" to row.convoId.value.toString(),
      "throughRequestId" to row.throughRequestId.value.toString(),
      "systemPromptId" to row.systemPromptId.value.toString(),
      "observationsWritten" to cells.observationsWritten,
      "claimsSuperseded" to cells.claimsSuperseded,
      "failureReason" to cells.failureReason,
    )
  }

  /** The outcome-discriminated cell strings for one extraction-run row. */
  private data class OutcomeCells(
    val observationsWritten: String,
    val claimsWritten: String,
    val claimsSuperseded: String,
    val failureCategory: String,
    val failureReason: String,
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
