package ed.unicoach.account

import com.typesafe.config.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Typed reader for the `accountErasure` block of service.conf (RFC 267, brief
 * 0002 D2). [grace] is how long after its close an account is erased: the
 * delay of the ERASE_ACCOUNT job the close enqueues. The key/env contract is
 * an integer day count (`accountErasure.graceDays`); the unit is attached HERE,
 * once. A negative count fails `from`, so a wrong override fails boot rather
 * than scheduling an erasure in the past; so does a count above
 * [ErasureDisclosure.ERASURE_WINDOW_DAYS], which would break the deletion
 * copy's promise that everything is erased within that many days.
 */
class AccountErasureConfig private constructor(
  val grace: Duration,
) {
  companion object {
    private const val GRACE_DAYS_PATH = "accountErasure.graceDays"

    fun from(config: Config): Result<AccountErasureConfig> =
      runCatching {
        val graceDays = config.getInt(GRACE_DAYS_PATH)
        require(graceDays >= 0) { "[$GRACE_DAYS_PATH] must be 0 or more days; got [$graceDays]" }
        require(graceDays <= ErasureDisclosure.ERASURE_WINDOW_DAYS) {
          "[$GRACE_DAYS_PATH] must be at most ${ErasureDisclosure.ERASURE_WINDOW_DAYS} days; got [$graceDays]. The deletion copy " +
            "promises everything is erased within ${ErasureDisclosure.ERASURE_WINDOW_DAYS} days and is not derived from this setting; " +
            "change that copy first or lower the value"
        }
        AccountErasureConfig(grace = graceDays.days)
      }
  }
}
