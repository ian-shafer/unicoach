package ed.unicoach.auth

import com.typesafe.config.Config
import ed.unicoach.common.config.normalizeUrlBase
import java.time.Duration

/**
 * Typed reader for the email-verification config surface (the `emailVerification`
 * block of service.conf), mirroring CoachingConfig: `from` fails when a key is
 * absent or unreadable (Result.failure carrying the underlying ConfigException)
 * and performs no value validation.
 *
 * - [tokenTtl] bounds how long an issued verification token stays consumable.
 * - [verifyUrlBase] is the link prefix the email points at; the raw token is
 *   appended as a `?token=` query parameter. Its DEFAULT is derived from the one
 *   public-web origin (`publicWeb.urlBase`, RFC 155 D-J) and
 *   `EMAIL_VERIFICATION_VERIFY_URL_BASE` still overrides it; either way the read
 *   value passes through [normalizeUrlBase], the single trailing-slash rule, so
 *   the composed link never carries a doubled separator.
 */
class EmailVerificationConfig private constructor(
  val tokenTtl: Duration,
  val verifyUrlBase: String,
) {
  companion object {
    fun from(config: Config): Result<EmailVerificationConfig> =
      runCatching {
        EmailVerificationConfig(
          tokenTtl = config.getDuration("emailVerification.tokenTtl"),
          verifyUrlBase = normalizeUrlBase(config.getString("emailVerification.verifyUrlBase")),
        )
      }
  }
}
