package ed.unicoach.web

import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import java.util.concurrent.atomic.AtomicInteger

/** A test open-in-app URL used by the route tests' `publicWebModule` wiring. */
const val TEST_OPEN_IN_APP_URL = "https://unicoach.test/app"

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
