package ed.unicoach.coaching.synthesis

import com.typesafe.config.Config

/**
 * Typed reader for the synthesis domain's config surface (the `synthesis` block
 * of service.conf), mirroring ExtractionConfig: `from` fails when a key is absent
 * or unreadable (Result.failure carrying the underlying ConfigException) and
 * performs no value validation.
 *
 * - [enabled] is the master switch for the worker's handler registration.
 * - [promptName] / [promptVersion] select the catalog row the pass resolves and
 *   pins by id.
 * - [model] / [maxTokens] shape the reflection call.
 * - [maxClaims] caps the active claims assembled into one prompt.
 * - [maxOpenCommitments] is the open-set cap; a student at the cap no-ops until
 *   some resolve.
 * - [maxNewCommitmentsPerRun] is the ceiling on commitments created by one pass.
 */
class SynthesisConfig private constructor(
  val enabled: Boolean,
  val promptName: String,
  val promptVersion: String,
  val model: String,
  val maxTokens: Int,
  val maxClaims: Int,
  val maxOpenCommitments: Int,
  val maxNewCommitmentsPerRun: Int,
) {
  companion object {
    fun from(config: Config): Result<SynthesisConfig> =
      runCatching {
        SynthesisConfig(
          enabled = config.getBoolean("synthesis.enabled"),
          promptName = config.getString("synthesis.promptName"),
          promptVersion = config.getString("synthesis.promptVersion"),
          model = config.getString("synthesis.model"),
          maxTokens = config.getInt("synthesis.maxTokens"),
          maxClaims = config.getInt("synthesis.maxClaims"),
          maxOpenCommitments = config.getInt("synthesis.maxOpenCommitments"),
          maxNewCommitmentsPerRun = config.getInt("synthesis.maxNewCommitmentsPerRun"),
        )
      }
  }
}
