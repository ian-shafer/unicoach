package ed.unicoach.chat

// Selector mapping the config `chat.provider` string to exactly one concrete
// ChatProvider, mirroring EmailProviderFactory. An unknown selector is a
// failure, never a silent fallback. RFC 44 adds the "anthropic" branch. The
// module stays unwired: no production main() calls this; it is exercised by
// tests only — production construction is the coaching-service RFC's
// responsibility.
object ChatProviderFactory {
  fun fromConfig(config: ChatConfig): Result<ChatProvider> =
    when (config.provider) {
      LogOnlyChatProvider.PROVIDER_ID -> Result.success(LogOnlyChatProvider())
      else -> Result.failure(IllegalArgumentException("unknown chat.provider [${config.provider}]"))
    }
}
