package ed.unicoach.chat

import com.typesafe.config.Config

// Typed reader for the chat module's config surface (chat.conf), mirroring
// EmailConfig. `from` fails when the key is absent or unreadable —
// Result.failure carrying the underlying typesafe ConfigException unmapped.
// It does not validate the value — the factory is the single place an unknown
// selector is rejected.
class ChatConfig private constructor(
  val provider: String,
) {
  companion object {
    // Reads chat.provider verbatim (packaged default "log").
    fun from(config: Config): Result<ChatConfig> =
      runCatching {
        ChatConfig(provider = config.getString("chat.provider"))
      }
  }
}
