package ed.unicoach.db.models

/**
 * Insert input for the `extraction_runs` log; omits the DB-generated id and
 * `created_at`. [outcome] is the sealed [ExtractionOutcome] ADT (RFC 101): an
 * `Applied` carries the three write counts, a `Failed` the parse-failure
 * category/reason — an `applied`-with-a-reason or a `failed`-with-counts cannot
 * be constructed. The four token fields are nullable (recorded when the provider
 * reports usage) and vary independently of the outcome, so they stay flat.
 */
data class NewExtractionRun(
  val convoId: ConvoId,
  val studentId: StudentId,
  val throughRequestId: ConvoRequestId,
  val outcome: ExtractionOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
