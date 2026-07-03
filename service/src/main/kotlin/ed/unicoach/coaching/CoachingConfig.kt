package ed.unicoach.coaching

import com.typesafe.config.Config

/**
 * Typed reader for the coaching domain's config surface (the `coaching` block of
 * service.conf), mirroring SessionConfig/ChatConfig: `from` fails when a key is
 * absent or unreadable (Result.failure carrying the underlying ConfigException)
 * and performs no value validation.
 *
 * - [model] pins `convo_requests.model_requested` for every turn.
 * - [maxTokens] is the per-turn response ceiling.
 * - [systemPromptName] / [systemPromptVersion] select the catalog row each turn
 *   resolves and pins by id.
 * - [surfaceCommitments] gates the next-session opener injection at
 *   `startConvo`: when true, open explicit commitments (RFC 93) are composed into
 *   the coach system prompt and marked fulfilled on a successful first reply.
 * - [maxToolRounds] bounds the chat tool-use loop's tool-dispatch rounds; on
 *   reaching it the loop makes one forced no-tools call to extract a text answer.
 */
class CoachingConfig private constructor(
  val model: String,
  val maxTokens: Int,
  val systemPromptName: String,
  val systemPromptVersion: String,
  val surfaceCommitments: Boolean,
  val maxToolRounds: Int,
) {
  companion object {
    fun from(config: Config): Result<CoachingConfig> =
      runCatching {
        CoachingConfig(
          model = config.getString("coaching.model"),
          maxTokens = config.getInt("coaching.maxTokens"),
          systemPromptName = config.getString("coaching.systemPromptName"),
          systemPromptVersion = config.getString("coaching.systemPromptVersion"),
          surfaceCommitments = config.getBoolean("coaching.surfaceCommitments"),
          maxToolRounds = config.getInt("coaching.maxToolRounds"),
        )
      }
  }
}
