package ed.unicoach.rest.config

import com.typesafe.config.Config

/**
 * Which request headers a diagnostic log line attaches. [All] selects every
 * header the request sent (minus the secret set); [Allowlist] selects only the
 * configured names, rendering an unsent one as `(absent)`.
 */
sealed interface HeaderSelection {
  object All : HeaderSelection

  data class Allowlist(
    val names: Set<String>,
  ) : HeaderSelection
}

/**
 * When to attach the diagnostic context (headers + body sizes + latency) to a
 * log line: [FAILURE] enriches only failures (`>= 400` or no response),
 * [ALWAYS] enriches every line.
 */
enum class Detail { FAILURE, ALWAYS }

/**
 * Typed `requestLogging {}` config, parsed once so no sentinel string (`"*"`) or
 * free-form status word survives to a use-site. Mirrors [ClientKeyGateConfig].
 *
 * - [secretHeaders] are stored lowercase because header-name matching is
 *   case-insensitive; their values are never logged in any mode.
 * - [headers] is the enriched-line selection (allowlist or wildcard).
 * - [detail] gates whether a successful line is enriched.
 */
data class RequestLoggingConfig(
  val secretHeaders: Set<String>,
  val headers: HeaderSelection,
  val detail: Detail,
) {
  companion object {
    fun from(config: Config): Result<RequestLoggingConfig> {
      return try {
        if (!config.hasPath("requestLogging")) {
          return Result.failure(IllegalArgumentException("Missing configuration section: requestLogging"))
        }

        val section = config.getConfig("requestLogging")

        val secretHeaders =
          section
            .getString("secretHeaders")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .toSet()

        val headersRaw = section.getString("headers").trim()
        val headers =
          if (headersRaw == "*") {
            HeaderSelection.All
          } else {
            HeaderSelection.Allowlist(
              headersRaw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
            )
          }

        val detailRaw = section.getString("detail").trim()
        val detail =
          when (detailRaw) {
            "failure" -> {
              Detail.FAILURE
            }

            "always" -> {
              Detail.ALWAYS
            }

            else -> {
              return Result.failure(
                IllegalArgumentException("Invalid requestLogging.detail=[$detailRaw] (expected \"failure\" or \"always\")"),
              )
            }
          }

        Result.success(RequestLoggingConfig(secretHeaders, headers, detail))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }
}
