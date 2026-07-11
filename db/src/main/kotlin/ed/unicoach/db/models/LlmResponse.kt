package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `llm_responses` log (RFC 106): the classified
 * terminal of one LLM provider call, 1:1 with its `llm_requests` row.
 * [outcome] is the sealed [LlmCallOutcome] ADT — a `Completed` carries
 * content/model/stop-reason, a `Failed` the kind + reason. The four token
 * columns and [providerRequestId] are orthogonal to the outcome (a failed or
 * cancelled call can still carry partial usage), so they stay flat; [latencyMs]
 * is always recorded. The DAO reconstructs [outcome] from the flat `outcome`
 * column plus its dependent columns.
 */
data class LlmResponse(
  override val id: LlmResponseId,
  override val createdAt: Instant,
  val requestId: LlmRequestId,
  val outcome: LlmCallOutcome,
  val providerRequestId: String?,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
  val latencyMs: Int,
) : Identifiable<LlmResponseId>,
  Created
