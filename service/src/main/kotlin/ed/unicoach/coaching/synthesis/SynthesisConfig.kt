package ed.unicoach.coaching.synthesis

import com.typesafe.config.Config
import java.time.Duration

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
 * - [shareNudgeEnabled] gates the deterministic share-nudge step (RFC 160).
 * - [shareNudgeCooldown] is the minimum age of the last `share_report`
 *   commitment before a re-nudge may fire. The config key/env contract stays
 *   an integer day count (`synthesis.shareNudgeCooldownDays`); the unit is
 *   attached HERE, once, so domain logic never converts a raw Int.
 * - [inviteNudgeEnabled] gates the deterministic invite-nudge step (RFC 240).
 * - [inviteNudgeCooldown] is the same measure for the last `invite_supporter`
 *   commitment, read from `synthesis.inviteNudgeCooldownDays` and given its unit
 *   here for the same reason. A SECOND pair and not the share nudge's: the two
 *   offers roll back independently, and one knob would make the rollback of
 *   either the rollback of both.
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
  val shareNudgeEnabled: Boolean,
  val shareNudgeCooldown: Duration,
  val inviteNudgeEnabled: Boolean,
  val inviteNudgeCooldown: Duration,
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
          shareNudgeEnabled = config.getBoolean("synthesis.shareNudgeEnabled"),
          shareNudgeCooldown = Duration.ofDays(config.getInt("synthesis.shareNudgeCooldownDays").toLong()),
          inviteNudgeEnabled = config.getBoolean("synthesis.inviteNudgeEnabled"),
          inviteNudgeCooldown = Duration.ofDays(config.getInt("synthesis.inviteNudgeCooldownDays").toLong()),
        )
      }
  }
}
