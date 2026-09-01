package ed.unicoach.coaching.report

import ed.unicoach.db.models.CostReportShareId
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives a share link's raw token from the share row's id (RFC 155).
 *
 * The database stores only `SHA-256(rawToken)`, so a raw token cannot be read
 * back out of a row — yet the student must be handed the SAME link when they
 * ask to share twice, or a parent's saved link would silently stop working.
 * Both hold because the token is a pure function of the row id and one server
 * secret:
 *
 * ```
 * rawToken = base64url-no-padding( HMAC-SHA256(secret, id.toString()) )
 * ```
 *
 * The id is public-ish and useless on its own; the secret never leaves
 * `:service`. The VIEW path needs none of this — public-web hashes whatever
 * token it is presented and looks the hash up — so the secret lives in exactly
 * one process.
 *
 * Rotating the secret invalidates every link ever issued, which is honest
 * rather than silent: the re-derived hash stops matching the stored one, and
 * [CostReportShareService] revokes the stale row and says the old link no
 * longer works.
 */
class ShareTokenDeriver(
  private val secret: ShareTokenSecret,
) {
  fun derive(id: CostReportShareId): String {
    // A Mac PER CALL, never a field. Mac carries the running digest state, so one
    // shared instance across concurrent share requests interleaves two
    // derivations and yields a token neither row's hash matches. The cost is one
    // cheap object per share; hoisting it to a field is a correctness bug.
    val mac = Mac.getInstance(ALGORITHM)
    mac.init(SecretKeySpec(secret.value.toByteArray(Charsets.UTF_8), ALGORITHM))
    val digest = mac.doFinal(id.value.toString().toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  private companion object {
    const val ALGORITHM = "HmacSHA256"
  }
}

/**
 * The SHAPE the mint side produces, published beside the mint (RFC 155).
 *
 * The reader may accept a token of this shape and nothing else: a string this
 * process could never have issued is refused before it is hashed, rather than
 * after it misses.
 */
object ShareToken {
  /**
   * The EXACT shape [ShareTokenDeriver.derive] produces: 43 base64url
   * characters, no padding — the 32 bytes of an HMAC-SHA256 digest.
   */
  private val SHAPE = Regex("[A-Za-z0-9_-]{43}")

  /**
   * Whether [raw] is a string THIS PROCESS could have issued.
   *
   * It lives beside the deriver because it is the deriver's own fact. The view
   * path used to accept anything non-blank, so `?token=` plus a megabyte of
   * newlines became a SHA-256 and an indexed lookup — work done to learn
   * something the mint shape already answers. A token of any other shape is
   * refused BEFORE it is hashed and before any connection is taken.
   */
  fun isWellFormed(raw: String): Boolean = SHAPE.matches(raw)
}
