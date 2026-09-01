package ed.unicoach.coaching.report

import com.typesafe.config.Config
import ed.unicoach.common.config.normalizeUrlBase
import ed.unicoach.common.config.optionalString

/**
 * A configured share-token secret: LONG ENOUGH TO BE A KEY, by construction.
 *
 * The type is the guarantee, not a `require` inside the one class that consumes
 * it. Absence is the null [CostReportConfig.secret] and nothing else,
 * so "unset" has exactly one representation and an empty value of this type does
 * not exist. It also stops the share URL base — a `String` sitting beside it —
 * from type-checking as the HMAC key.
 *
 * "Not blank" was never the boundary this type claimed to hold: a one-character
 * value, or a stray shell fragment, type-checked as the key authorising every
 * family's report link. A PRESENT-BUT-TOO-SHORT value is a misconfiguration, not
 * an unset deployment, so it is refused loudly — [CostReportConfig.from] folds
 * the throw into `Result.failure` and the process fails to boot rather than
 * signing links with a toy secret.
 */
@JvmInline
value class ShareTokenSecret private constructor(
  val value: String,
) {
  companion object {
    /**
     * The shortest key we accept. `openssl rand -base64 48` is the seeding
     * recipe (`infra/ssm.tf`), which yields 64 characters — this floor refuses
     * a hand-typed placeholder without refusing the recipe's own output.
     */
    const val MIN_LENGTH = 32

    /**
     * The secret, or null when the key is absent or blank — an unset secret is a
     * deployment state. A present value shorter than [MIN_LENGTH] is neither:
     * it is a key nobody meant to ship, and it throws.
     */
    fun of(raw: String?): ShareTokenSecret? {
      val value = raw?.takeIf { it.isNotBlank() } ?: return null
      require(value.length >= MIN_LENGTH) {
        "[${CostReportConfig.SHARE_TOKEN_SECRET_PATH}] must be at least [$MIN_LENGTH] characters, " +
          "got [${value.length}]"
      }
      return ShareTokenSecret(value)
    }
  }
}

/**
 * Typed reader for the Family Cost Report config surface (the `costReport`
 * block of service.conf), mirroring [ed.unicoach.auth.EmailVerificationConfig]:
 * `from` fails when a required key is absent or unreadable (Result.failure
 * carrying the underlying ConfigException) and performs no value validation.
 *
 * - [shareUrlBase] is the link prefix the coach speaks to the student; the raw
 *   share token is appended as a `?token=` query parameter, exactly as the
 *   verification link does (RFC 155 D-D — a query parameter is the shape
 *   public-web can redact from its request log). Its DEFAULT is derived from the
 *   one public-web origin (`publicWeb.urlBase`, RFC 155 D-J) and
 *   `COST_REPORT_SHARE_URL_BASE` still overrides it; either way the read value
 *   passes through [normalizeUrlBase], the single trailing-slash rule shared
 *   with the verification link.
 * - [secret] keys the HMAC that derives a share token from its row id
 *   ([ShareTokenDeriver]). It is a SECRET: it is never committed, and in a
 *   deployed environment it arrives from SSM. It is also OPTIONAL, and null when
 *   unset — an environment without it still boots and serves every other
 *   surface; only the two share tools decline, with a sentence the coach can
 *   speak. A page already shared keeps working, because viewing needs no secret.
 */
class CostReportConfig private constructor(
  val shareUrlBase: String,
  val secret: ShareTokenSecret?,
) {
  companion object {
    private const val BLOCK = "costReport"

    /** Named like its sibling: one config path per key, never one named and one inline. */
    private const val SHARE_URL_BASE_PATH = "$BLOCK.shareUrlBase"

    /**
     * PUBLIC because the composition root names this key in the warning it logs
     * when the secret is unset (RFC 155): the operator is told which key to seed,
     * from the key's one home rather than from a re-typed string.
     */
    const val SHARE_TOKEN_SECRET_PATH = "$BLOCK.shareTokenSecret"

    fun from(config: Config): Result<CostReportConfig> =
      runCatching {
        CostReportConfig(
          shareUrlBase = normalizeUrlBase(config.getString(SHARE_URL_BASE_PATH)),
          // An env var exported as "" is a key that is present and says nothing,
          // which is the shared reading of "unset" [optionalString] owns.
          secret = ShareTokenSecret.of(config.optionalString(SHARE_TOKEN_SECRET_PATH)),
        )
      }
  }
}
