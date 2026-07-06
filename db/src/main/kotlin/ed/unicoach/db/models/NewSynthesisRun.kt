package ed.unicoach.db.models

/**
 * Insert input for the `synthesis_runs` log; omits the DB-generated id and
 * `created_at`. [outcome] is the sealed [SynthesisOutcome] ADT (RFC 101): an
 * `Applied` carries the two write counts, a `Failed` the parse-failure
 * category/reason — an `applied`-with-a-reason or a `failed`-with-counts cannot
 * be constructed. The four token fields are nullable (recorded when the provider
 * reports usage) and vary independently of the outcome, so they stay flat.
 */
data class NewSynthesisRun(
  val studentId: StudentId,
  val outcome: SynthesisOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
