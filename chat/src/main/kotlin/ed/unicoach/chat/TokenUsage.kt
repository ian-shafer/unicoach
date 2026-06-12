package ed.unicoach.chat

data class TokenUsage(
  val inputTokens: Int?,
  val outputTokens: Int?,
  // Anthropic: cache_read_input_tokens.
  val cacheReadTokens: Int?,
  // Anthropic: cache_creation_input_tokens.
  val cacheWriteTokens: Int?,
)
