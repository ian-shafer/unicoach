package ed.unicoach.db.models

/**
 * The composite read model over one logged LLM call (RFC 106): the
 * [LlmRequest], its 1:1 [LlmResponse] (null when no response has been written
 * yet — the request committed but the terminal has not), and its 0..1
 * [LlmResponseRaw] verbatim payload (null when the terminal carried no body).
 * The sibling of `ConvoTurn`, assembled by `LlmCallsDao` from the joined tables.
 */
data class LlmCall(
  val request: LlmRequest,
  val response: LlmResponse?,
  val raw: LlmResponseRaw?,
)
