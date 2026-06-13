package ed.unicoach.chat

// A non-2xx response from the Messages API: status, the `request-id` response
// header, and the verbatim body text (read before throwing — the error body is
// load-bearing for Rejected.rawPayload and the error.type classification). The
// provider classifies this into a terminal; it is never surfaced to the caller.
class AnthropicHttpException(
  val status: Int,
  val requestId: String?,
  val body: String,
) : Exception("anthropic http [$status] [${requestId ?: ""}]")
