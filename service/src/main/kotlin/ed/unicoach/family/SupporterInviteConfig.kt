package ed.unicoach.family

import com.typesafe.config.Config
import ed.unicoach.common.config.normalizeUrlBase
import java.time.Duration

/**
 * The supporter-invite link settings (RFC 232), read from the `supporterInvite`
 * block of `service.conf`.
 *
 * Neither value has a Kotlin default, the `emailVerification` sibling's rule:
 * `service.conf` is the one place the lifetime is stated, so an operator who
 * renames or drops the key meets a loud failure at boot instead of a fourteen
 * day lifetime nobody declared. The TTL is a HOCON duration for the same reason
 * the verification token's is — `14 days` says what it is, where `14` needs a
 * unit remembered per reader.
 */
data class SupporterInviteConfig(
  val inviteUrlBase: String,
  val tokenTtl: Duration,
) {
  companion object {
    private const val BLOCK = "supporterInvite"
    private const val INVITE_URL_BASE_PATH = "$BLOCK.inviteUrlBase"
    private const val TOKEN_TTL_PATH = "$BLOCK.tokenTtl"

    fun from(config: Config): Result<SupporterInviteConfig> =
      runCatching {
        SupporterInviteConfig(
          inviteUrlBase = normalizeUrlBase(config.getString(INVITE_URL_BASE_PATH)),
          tokenTtl = config.getDuration(TOKEN_TTL_PATH),
        )
      }
  }
}
