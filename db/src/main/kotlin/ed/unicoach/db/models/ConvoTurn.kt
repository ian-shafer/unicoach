package ed.unicoach.db.models

/**
 * The replay unit: one logged coaching request paired with its logged LLM call
 * (RFC 106). [call] is nullable because the joined `llm_responses` row can be
 * absent — the request committed but the terminal has not (provider in flight,
 * or a crash before the response write). The response side of an exchange is the
 * joined `LlmCall.response` (an `LlmResponse`); this replaces the former
 * `ConvoResponse`.
 */
data class ConvoTurn(
  val request: ConvoRequest,
  val call: LlmCall?,
)
