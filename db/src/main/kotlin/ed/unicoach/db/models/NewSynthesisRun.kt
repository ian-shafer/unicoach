package ed.unicoach.db.models

/**
 * Insert input for the `synthesis_runs` log; omits the DB-generated id and
 * `created_at`. The write counts default to 0 (a `failed` run records zero
 * writes; the DB CHECK enforces this), and all four token fields are nullable
 * (recorded when the provider reports usage).
 */
data class NewSynthesisRun(
  val studentId: StudentId,
  val outcome: SynthesisOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val commitmentsWritten: Int = 0,
  val commitmentsDropped: Int = 0,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
