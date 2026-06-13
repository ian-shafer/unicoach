package ed.unicoach.chat

import com.typesafe.config.Config

// Typed reader for the chat.anthropic block, mirroring SesConfig. `apiKey` is
// read via hasPath -> nullable (absent when the env-var override is unset) so
// "log" deployments need no Anthropic secret; the remaining keys are fail-fast
// getString/getLong reads against their packaged defaults. No value validation
// (URL or key shape): a bad value surfaces at the first call as a classified
// terminal, consistent with the SesConfig stance.
class AnthropicConfig private constructor(
  val apiKey: String?,
  val baseUrl: String,
  val connectTimeoutMs: Long,
  val socketTimeoutMs: Long,
) {
  companion object {
    fun from(config: Config): Result<AnthropicConfig> =
      runCatching {
        AnthropicConfig(
          apiKey = config.takeIf { it.hasPath("chat.anthropic.apiKey") }?.getString("chat.anthropic.apiKey"),
          baseUrl = config.getString("chat.anthropic.baseUrl"),
          connectTimeoutMs = config.getLong("chat.anthropic.connectTimeoutMs"),
          socketTimeoutMs = config.getLong("chat.anthropic.socketTimeoutMs"),
        )
      }
  }
}
