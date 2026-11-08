package ed.unicoach.auth

import com.typesafe.config.Config
import ed.unicoach.common.config.normalizeUrlBase
import java.time.Duration

/**
 * Typed reader for the `passwordReset` block of service.conf (RFC 276), shaped
 * like [EmailVerificationConfig]: `from` fails when a key is absent or
 * unreadable and performs no value validation.
 *
 * - [tokenTtl] bounds how long a mailed reset link stays usable.
 * - [resetUrlBase] is the link prefix the email points at; the raw token is
 *   appended as a `?token=` query parameter. It passes through
 *   [normalizeUrlBase] so the composed link never carries a doubled separator.
 */
class PasswordResetConfig private constructor(
  val tokenTtl: Duration,
  val resetUrlBase: String,
) {
  companion object {
    fun from(config: Config): Result<PasswordResetConfig> =
      runCatching {
        PasswordResetConfig(
          tokenTtl = config.getDuration("passwordReset.tokenTtl"),
          resetUrlBase = normalizeUrlBase(config.getString("passwordReset.resetUrlBase")),
        )
      }
  }
}
