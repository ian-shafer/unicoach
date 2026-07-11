package ed.unicoach.db.models

/**
 * Insert input for the `extraction_runs` log; omits the DB-generated id and
 * `created_at`. [outcome] is the sealed [ExtractionOutcome] ADT (RFC 101): an
 * `Applied` carries the three write counts, a `Failed` the parse-failure
 * category/reason. [llmRequestId] references the generic call log row that holds
 * this pass's provider/model and token spend (RFC 106).
 */
data class NewExtractionRun(
  val convoId: ConvoId,
  val studentId: StudentId,
  val throughRequestId: ConvoRequestId,
  val outcome: ExtractionOutcome,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
)
