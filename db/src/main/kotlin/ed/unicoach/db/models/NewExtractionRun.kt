package ed.unicoach.db.models

/**
 * Insert input for the `extraction_runs` log; omits the DB-generated id and
 * `created_at`. The write counts default to 0 (a `failed` run records zero
 * writes; the DB CHECK enforces this), and all four token fields are nullable
 * (recorded when the provider reports usage).
 */
data class NewExtractionRun(
  val convoId: ConvoId,
  val studentId: StudentId,
  val throughRequestId: ConvoRequestId,
  val outcome: ExtractionOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val observationsWritten: Int = 0,
  val claimsWritten: Int = 0,
  val claimsSuperseded: Int = 0,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
