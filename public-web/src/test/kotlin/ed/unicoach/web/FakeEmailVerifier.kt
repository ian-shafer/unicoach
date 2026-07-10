package ed.unicoach.web

import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.web.common.logging.Detail
import ed.unicoach.web.common.logging.HeaderSelection
import ed.unicoach.web.common.logging.RequestLoggingConfig
import java.util.concurrent.atomic.AtomicInteger

/** A test open-in-app URL used by the route tests' `publicWebModule` wiring. */
const val TEST_OPEN_IN_APP_URL = "https://unicoach.test/app"

/**
 * A careful [RequestLoggingConfig] the route tests pass to `publicWebModule`,
 * mirroring public-web.conf's shipped defaults (allowlist headers, enrich only on
 * failure, secret Cookie/Authorization subtracted). Shared so every call-site
 * installs the same request log without rebuilding it.
 */
val TEST_REQUEST_LOG_CONFIG =
  RequestLoggingConfig(
    secretHeaders = setOf("cookie", "authorization"),
    headers = HeaderSelection.Allowlist(setOf("Accept", "Content-Type", "User-Agent", "Expect", "Content-Length")),
    detail = Detail.FAILURE,
  )

/**
 * A hand-written fake [EmailVerifier] (a real class, not a mock) returning a
 * scripted [result] and counting calls, so route tests can assert both the
 * rendered outcome and that the side-effect-free `GET` issues zero verify calls.
 *
 * The default result is never rendered by the non-verify route tests (they hit
 * other pages), so it carries no domain `User`; the verify-matrix test supplies
 * a specific result (including a `Result.failure` for the `Unavailable` branch).
 */
class FakeEmailVerifier(
  private val result: Result<VerifyEmailResult> = Result.success(VerifyEmailResult.InvalidToken),
) : EmailVerifier {
  private val calls = AtomicInteger(0)

  val callCount: Int
    get() = calls.get()

  override suspend fun verify(rawToken: String): Result<VerifyEmailResult> {
    calls.incrementAndGet()
    return result
  }
}
