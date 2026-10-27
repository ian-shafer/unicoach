package ed.unicoach.family

import com.typesafe.config.Config

/**
 * Typed reader for the supporter coach's config surface (the `parentCoaching`
 * block of service.conf, RFC 258), mirroring [ed.unicoach.coaching.CoachingConfig]:
 * `from` fails when a key is absent or unreadable and performs no value
 * validation.
 *
 * - [systemPromptName] / [systemPromptVersion] select the `parent-coach` catalog
 *   row each turn resolves and pins by id.
 * - [model], [maxTokens] and [maxToolRounds] fall back to `coaching.*` in the
 *   conf itself (HOCON substitution), so the two coaches run on one model
 *   unless an operator splits them.
 */
class SupporterCoachingConfig private constructor(
  val model: String,
  val maxTokens: Int,
  val maxToolRounds: Int,
  val systemPromptName: String,
  val systemPromptVersion: String,
) {
  companion object {
    fun from(config: Config): Result<SupporterCoachingConfig> =
      runCatching {
        SupporterCoachingConfig(
          model = config.getString("parentCoaching.model"),
          maxTokens = config.getInt("parentCoaching.maxTokens"),
          maxToolRounds = config.getInt("parentCoaching.maxToolRounds"),
          systemPromptName = config.getString("parentCoaching.systemPromptName"),
          systemPromptVersion = config.getString("parentCoaching.systemPromptVersion"),
        )
      }
  }
}
