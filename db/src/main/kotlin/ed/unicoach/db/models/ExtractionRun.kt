package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `extraction_runs` log (RFC 66): one billed extraction
 * LLM call over a conversation. Serves as the conversation watermark (highest
 * [throughRequestId] over `applied` rows), the provenance of the pass's writes
 * (prompt/provider/model), and the per-pass token ledger (the four token
 * columns, recorded for every billed call including failures). [outcome] is the
 * sealed [ExtractionOutcome] ADT (RFC 101): an `Applied` carries the write
 * counts, a `Failed` names why the LLM output was unparseable — the DAO
 * reconstructs it from the flat outcome/count/failure columns.
 */
data class ExtractionRun(
  override val id: ExtractionRunId,
  override val createdAt: Instant,
  val convoId: ConvoId,
  val studentId: StudentId,
  val throughRequestId: ConvoRequestId,
  val outcome: ExtractionOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
) : Identifiable<ExtractionRunId>,
  Created
