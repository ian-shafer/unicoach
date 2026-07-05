package ed.unicoach.coaching.fitlens

import com.typesafe.config.Config

/**
 * Typed reader for the fit-lens domain's config surface (the `fitLens` block of
 * service.conf), mirroring SynthesisConfig: `from` fails when a key is absent or
 * unreadable (Result.failure carrying the underlying ConfigException) and
 * performs no value validation.
 *
 * - [enabled] is the master switch for the worker's handler registration.
 * - [model] shapes both LLM calls; [queryMaxTokens] / [reasonMaxTokens] cap them
 *   independently.
 * - [searchLimit] is the result cap handed to `CollegeSearchService`, coerced
 *   into its `1..25` band (a value outside it is silently clamped there).
 * - [minClaims] is the floor below which the pass no-ops (too little signal).
 * - [maxClaims] caps the active claims assembled into the prompts (mirrors
 *   `synthesis.maxClaims`).
 * - [maxConsecutiveFailures] bounds the failure circuit breaker.
 * - [queryPromptName] / [queryPromptVersion] and
 *   [reasonPromptName] / [reasonPromptVersion] select the two catalog rows the
 *   pass resolves and pins by id.
 */
class FitLensConfig private constructor(
  val enabled: Boolean,
  val model: String,
  val queryMaxTokens: Int,
  val reasonMaxTokens: Int,
  val searchLimit: Int,
  val minClaims: Int,
  val maxClaims: Int,
  val maxConsecutiveFailures: Int,
  val queryPromptName: String,
  val queryPromptVersion: String,
  val reasonPromptName: String,
  val reasonPromptVersion: String,
) {
  companion object {
    fun from(config: Config): Result<FitLensConfig> =
      runCatching {
        FitLensConfig(
          enabled = config.getBoolean("fitLens.enabled"),
          model = config.getString("fitLens.model"),
          queryMaxTokens = config.getInt("fitLens.queryMaxTokens"),
          reasonMaxTokens = config.getInt("fitLens.reasonMaxTokens"),
          searchLimit = config.getInt("fitLens.searchLimit"),
          minClaims = config.getInt("fitLens.minClaims"),
          maxClaims = config.getInt("fitLens.maxClaims"),
          maxConsecutiveFailures = config.getInt("fitLens.maxConsecutiveFailures"),
          queryPromptName = config.getString("fitLens.queryPromptName"),
          queryPromptVersion = config.getString("fitLens.queryPromptVersion"),
          reasonPromptName = config.getString("fitLens.reasonPromptName"),
          reasonPromptVersion = config.getString("fitLens.reasonPromptVersion"),
        )
      }
  }
}
