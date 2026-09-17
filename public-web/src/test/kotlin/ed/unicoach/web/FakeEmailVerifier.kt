package ed.unicoach.web

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.common.logging.Detail
import ed.unicoach.web.common.logging.HeaderSelection
import ed.unicoach.web.common.logging.RequestLoggingConfig
import ed.unicoach.web.report.CostReportSource
import io.ktor.server.application.Application
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
 * The suites' one way to install the module under test.
 *
 * Every parameter is defaulted to the standing test wiring, so a suite names
 * only the collaborator it is actually about: a verify-email test passes a
 * verifier, a report test passes a source, and the twenty call sites that care
 * about neither pass nothing. Adding a parameter to `publicWebModule` is then
 * one edit here rather than twenty mechanical ones, none of which a reader can
 * tell from a deliberate choice. RFC 224 added three, and this is the edit.
 *
 * The auth collaborators default to the REAL ones over the test database
 * ([WebAuthTestSupport]) rather than to fakes. `AuthService` is a final class
 * with five collaborators of its own, so a fake would be a second
 * implementation of the login rules — the very thing brief decision D13 says
 * must have one home — and every suite asserting against it would be asserting
 * against the test's own idea of authentication. The cost is that public-web's
 * whole test suite now needs the test database, which `bin/test` provides for
 * every run anyway.
 */
fun Application.testPublicWebModule(
  emailVerifier: EmailVerifier = FakeEmailVerifier(),
  costReportSource: CostReportSource = FakeCostReportSource(),
  openInAppUrl: String? = TEST_OPEN_IN_APP_URL,
  requestLoggingConfig: RequestLoggingConfig = TEST_REQUEST_LOG_CONFIG,
  bundleUrl: () -> String = { WebAssets.MAIN },
  authService: AuthService = WebAuthTestSupport.authService,
  emailVerificationService: EmailVerificationService = WebAuthTestSupport.emailVerificationService,
  publicWebConfig: PublicWebConfig = WebAuthTestSupport.publicWebConfig,
) {
  publicWebModule(
    emailVerifier,
    costReportSource,
    openInAppUrl,
    requestLoggingConfig,
    bundleUrl,
    authService,
    emailVerificationService,
    publicWebConfig,
  )
}

/**
 * Every `<script>` element in [html], as (attributes, inline body).
 *
 * The page suites' one claim about client-side code is about the RESPONSE, so
 * it is read off the response rather than off any Kotlin source: a page that
 * stopped rendering the tag, grew a second one, or inlined code fails here.
 */
private val SCRIPT_ELEMENTS = Regex("""<script\b([^>]*)>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** The `src` of a script element's attribute text, in any of HTML's three quoting forms. */
private val SCRIPT_SRC = Regex("""\bsrc\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")

/**
 * The one claim EVERY public page makes about client-side code (RFC 225 D-H),
 * asserted in one place instead of re-spelled per page suite.
 *
 * Asserted against the SERVED bytes and nothing else: the page carries exactly
 * ONE script element, that element is EXTERNAL (no inline body — an inline
 * script is what `script-src 'self'` refuses), and its `src` is the url the
 * manifest names. That is the whole server-side contract; everything the
 * enhancement itself inserts is markup no server response contains, and it is
 * owned by the Deno tests that drive the real functions against a document.
 */
fun assertLoadsBundleAsItsOnlyScript(
  label: String,
  body: String,
) {
  val scripts = SCRIPT_ELEMENTS.findAll(body).toList()
  assertEquals(
    1,
    scripts.size,
    "[$label] carries [${scripts.size}] script elements, not the one external module the shell renders",
  )
  val attributes = scripts.single().groupValues[1]
  val inlineBody = scripts.single().groupValues[2]
  assertTrue(
    inlineBody.isBlank(),
    "[$label] carries an INLINE script, which its own policy refuses: [$inlineBody]",
  )
  val src =
    SCRIPT_SRC.find(attributes)?.let { match -> match.groupValues.drop(2).first { it.isNotEmpty() } }
      ?: fail("[$label] renders a script element with no readable src: [<script$attributes>]")
  assertEquals(
    WebAssets.MAIN,
    src,
    "[$label] loads [$src], which is not the bundle the manifest names",
  )
}

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
