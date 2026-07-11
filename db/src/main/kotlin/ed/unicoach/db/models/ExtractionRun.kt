package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `extraction_runs` log (RFC 66): one billed extraction
 * LLM call over a conversation. Serves as the conversation watermark (highest
 * [throughRequestId] over `applied` rows) and the provenance of the pass's writes
 * (prompt + the referenced [llmRequestId] logged call). Since RFC 106 the
 * provider/model and per-call token spend live in the generic call log the
 * [llmRequestId] references — not on this row. [outcome] is the sealed
 * [ExtractionOutcome] ADT (RFC 101): an `Applied` carries the write counts, a
 * `Failed` names why the LLM output was unparseable.
 */
data class ExtractionRun(
  override val id: ExtractionRunId,
  override val createdAt: Instant,
  val convoId: ConvoId,
  val studentId: StudentId,
  val throughRequestId: ConvoRequestId,
  val outcome: ExtractionOutcome,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
) : Identifiable<ExtractionRunId>,
  Created
