package ed.unicoach.coaching.extraction

import com.typesafe.config.Config
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Typed reader for the extraction domain's config surface (the `extraction` block
 * of service.conf), mirroring CoachingConfig: `from` fails when a key is absent
 * or unreadable (Result.failure carrying the underlying ConfigException) and
 * performs no value validation.
 *
 * - [enabled] is the master switch for the chat-path enqueue and the worker's
 *   handler registration.
 * - [debounce] is the job delay coalescing rapid turns into one effective pass.
 * - [promptName] / [promptVersion] select the catalog row the pass resolves and
 *   pins by id.
 * - [model] / [maxTokens] shape the distillation call.
 * - [windowMaxTurns] caps the turns assembled into one pass.
 * - [confidenceHalfLifeDays] is the decay half-life for the confidence formula.
 */
class ExtractionConfig private constructor(
  val enabled: Boolean,
  val debounce: Duration,
  val promptName: String,
  val promptVersion: String,
  val model: String,
  val maxTokens: Int,
  val windowMaxTurns: Int,
  val confidenceHalfLifeDays: Double,
) {
  companion object {
    fun from(config: Config): Result<ExtractionConfig> =
      runCatching {
        ExtractionConfig(
          enabled = config.getBoolean("extraction.enabled"),
          debounce = config.getDuration("extraction.debounce").toKotlinDuration(),
          promptName = config.getString("extraction.promptName"),
          promptVersion = config.getString("extraction.promptVersion"),
          model = config.getString("extraction.model"),
          maxTokens = config.getInt("extraction.maxTokens"),
          windowMaxTurns = config.getInt("extraction.windowMaxTurns"),
          confidenceHalfLifeDays = config.getDouble("extraction.confidenceHalfLifeDays"),
        )
      }
  }
}
