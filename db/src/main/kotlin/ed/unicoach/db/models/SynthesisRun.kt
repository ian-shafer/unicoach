package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `synthesis_runs` log (RFC 93): one billed synthesis
 * LLM call over a student. Serves as the student's synthesis freshness marker
 * (latest `created_at` over `applied` rows), the provenance of the pass's writes
 * (prompt/provider/model), and the per-student token ledger (the four token
 * columns, recorded for every billed call including failures). [outcome] is the
 * sealed [SynthesisOutcome] ADT (RFC 101): an `Applied` carries the write
 * counts, a `Failed` names why the LLM output was unparseable — the DAO
 * reconstructs it from the flat outcome/count/failure columns.
 */
data class SynthesisRun(
  override val id: SynthesisRunId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val outcome: SynthesisOutcome,
  val systemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
) : Identifiable<SynthesisRunId>,
  Created
