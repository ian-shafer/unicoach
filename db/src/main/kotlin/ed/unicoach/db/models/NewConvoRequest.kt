package ed.unicoach.db.models

/**
 * Insert input for the `convo_requests` coaching-extension log (RFC 106); omits
 * the DB-generated id and `created_at`. Carries only the coaching columns plus
 * [llmRequestId] — the FK into the generic `llm_requests` call log that holds
 * the request I/O envelope. The caller obtains [llmRequestId] from
 * `LlmCallLog.recordStreaming` before stamping this row.
 */
data class NewConvoRequest(
  val convoId: ConvoId,
  val systemPromptId: SystemPromptId,
  val llmRequestId: LlmRequestId,
  val turnId: ConvoTurnId,
  val kind: ConvoRequestKind = ConvoRequestKind.USER,
)
