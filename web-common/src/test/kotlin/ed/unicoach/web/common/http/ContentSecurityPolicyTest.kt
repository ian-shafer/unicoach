package ed.unicoach.web.common.http

import io.ktor.client.request.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The policy CONSTANT, asserted as a property of the string (RFC 210).
 *
 * The point of stating it here rather than only on a served response is that
 * weakening the policy then costs a deliberate edit to a test that says why the
 * directive exists — not a quiet extra token appended to a long string in a
 * hurry, which is exactly how `'unsafe-inline'` gets into a policy and never
 * leaves again. These are MECHANISM assertions: they say what the header
 * promises, not that a browser honoured it.
 */
class ContentSecurityPolicyTest {
  /*
   * Three INDEPENDENT weakenings, so three tests: stated as one method, the
   * first one to fire hides whether the other two also hold, and a run that
   * re-added 'unsafe-inline' and a wildcard together would be reported as one
   * defect.
   */

  @Test
  fun `the policy allows no inline code`() {
    assertTrue(
      !CONTENT_SECURITY_POLICY.contains("unsafe-inline"),
      "'unsafe-inline' is back in the policy: the first inline script would stop failing visibly",
    )
  }

  @Test
  fun `the policy allows no eval`() {
    assertTrue(
      !CONTENT_SECURITY_POLICY.contains("unsafe-eval"),
      "'unsafe-eval' is in the policy: nothing this tier serves needs it",
    )
  }

  @Test
  fun `the policy allows no wildcard source`() {
    assertTrue(
      !CONTENT_SECURITY_POLICY.contains("*"),
      "a wildcard source is in the policy: every directive here is same-origin by design",
    )
  }

  /**
   * The directives the rest of the tree depends on, named one by one. A
   * directive silently dropped from the middle of the string is not visible in
   * a diff review of a wrapped literal.
   */
  @Test
  fun `the policy states every directive the site relies on`() {
    val directives =
      CONTENT_SECURITY_POLICY
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val expected =
      listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self'",
        "img-src 'self' data:",
        "font-src 'self'",
        "connect-src 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'",
        "base-uri 'self'",
        "object-src 'none'",
      )
    assertEquals(expected, directives, "the policy's directive list changed")
  }

  /**
   * The one argument worse than not calling the seam at all: a
   * `Content-Security-Policy:` header with no directives restricts nothing,
   * and it passes every test that asks only whether the header is PRESENT. It
   * is refused where the caller can see what it passed.
   */
  @Test
  fun `a blank policy is refused rather than installed`() {
    val refusal =
      assertFailsWith<IllegalArgumentException> {
        testApplication {
          routing { setContentSecurityPolicy("   ") }
          client.get("/")
        }
      }

    assertTrue(
      refusal.message.orEmpty().contains("blank"),
      "a blank policy was refused, but the message does not say what was wrong: [${refusal.message}]",
    )
  }

  /** ENFORCING, not report-only: the two differ by a header name and by nothing else. */
  @Test
  fun `the header set is the enforcing one`() {
    assertEquals("Content-Security-Policy", CONTENT_SECURITY_POLICY_HEADER, "report-only would log a violation and serve it anyway")
  }
}
