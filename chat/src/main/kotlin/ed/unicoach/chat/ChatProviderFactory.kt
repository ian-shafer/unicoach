package ed.unicoach.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

// Selector mapping the config `chat.provider` string to exactly one concrete
// ChatProvider, mirroring EmailProviderFactory. An unknown selector is a
// failure, never a silent fallback. The module stays unwired: no production
// main() calls this; it is exercised by tests only — production construction is
// the coaching-service RFC's responsibility.
object ChatProviderFactory {
  fun fromConfig(config: ChatConfig): Result<ChatProvider> =
    when (config.provider) {
      LogOnlyChatProvider.PROVIDER_ID -> Result.success(LogOnlyChatProvider())
      AnthropicChatProvider.PROVIDER_ID -> anthropicProvider(config.anthropic)
      else -> Result.failure(IllegalArgumentException("unknown chat.provider [${config.provider}]"))
    }

  // The missing-key case is the factory's single misconfiguration gate: a keyless
  // adapter would emit Rejected on every turn at runtime. Otherwise construct an
  // HttpClient(CIO) with connect + socket timeouts (no whole-request timeout —
  // the stream is bounded by maxTokens and a stall trips the socket timeout),
  // wrap it in the transport, and transfer the client's lifetime to the provider
  // (closed via its close()), mirroring EmailProviderFactory's SES branch.
  private fun anthropicProvider(config: AnthropicConfig): Result<ChatProvider> {
    if (config.apiKey == null) {
      return Result.failure(
        IllegalArgumentException(
          "chat.provider [anthropic] requires [chat.anthropic.apiKey] (CHAT_ANTHROPIC_API_KEY)",
        ),
      )
    }
    val client =
      HttpClient(CIO) {
        install(HttpTimeout) {
          connectTimeoutMillis = config.connectTimeoutMs
          socketTimeoutMillis = config.socketTimeoutMs
        }
      }
    return Result.success(AnthropicChatProvider(KtorAnthropicStreamTransport(client, config), client))
  }
}
