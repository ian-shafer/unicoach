package ed.unicoach.db.models

/**
 * Insert input for the `synthesis_runs` log; omits the DB-generated id and
 * `created_at`. [outcome] is the sealed [SynthesisOutcome] ADT (RFC 101): an
 * `Applied` carries the two write counts, a `Failed` the parse-failure
 * category/reason. [llmRequestId] references the generic call log row that holds
 * this pass's provider/model and token spend (RFC 106).
 */
data class NewSynthesisRun(
  val studentId: StudentId,
  val outcome: SynthesisOutcome,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
)
