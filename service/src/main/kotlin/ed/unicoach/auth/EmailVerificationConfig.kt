package ed.unicoach.auth

import com.typesafe.config.Config
import java.time.Duration

/**
 * Typed reader for the email-verification config surface (the `emailVerification`
 * block of service.conf), mirroring CoachingConfig: `from` fails when a key is
 * absent or unreadable (Result.failure carrying the underlying ConfigException)
 * and performs no value validation.
 *
 * - [tokenTtl] bounds how long an issued verification token stays consumable.
 * - [verifyUrlBase] is the link prefix the email points at; the raw token is
 *   appended as a `?token=` query parameter.
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
          verifyUrlBase = config.getString("emailVerification.verifyUrlBase"),
        )
      }
  }
}
