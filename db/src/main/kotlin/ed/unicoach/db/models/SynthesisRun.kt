package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `synthesis_runs` log (RFC 93): one billed synthesis
 * LLM call over a student. Serves as the student's synthesis freshness marker
 * (latest `created_at` over `applied` rows) and the provenance of the pass's
 * writes (prompt + the referenced [llmRequestId] logged call). Since RFC 106 the
 * provider/model and per-call token spend live in the generic call log the
 * [llmRequestId] references — not on this row. [outcome] is the sealed
 * [SynthesisOutcome] ADT (RFC 101): an `Applied` carries the write counts, a
 * `Failed` names why the LLM output was unparseable.
 */
data class SynthesisRun(
  override val id: SynthesisRunId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val outcome: SynthesisOutcome,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
) : Identifiable<SynthesisRunId>,
  Created
