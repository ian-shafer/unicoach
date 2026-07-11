package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `convo_requests` log: one coaching turn's identity
 * and its reference to the logged LLM call (RFC 106). The request I/O envelope
 * (provider / model / params / content) lives in the referenced `llm_requests`
 * row; this row keeps only the coaching columns — [convoId], [turnId], [kind],
 * [systemPromptId] — plus [llmRequestId], the FK into the generic call log.
 */
data class ConvoRequest(
  override val id: ConvoRequestId,
  val convoId: ConvoId,
  override val createdAt: Instant,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
  val kind: ConvoRequestKind,
  val turnId: ConvoTurnId,
) : Identifiable<ConvoRequestId>,
  Created
