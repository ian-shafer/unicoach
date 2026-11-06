package ed.unicoach.web.common.http

import io.ktor.client.request.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
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

  /**
   * The builder renders the default as the bytes it has always been (RFC 275):
   * typed out here, not read from the constant, because a test comparing the
   * constant with itself proves nothing about the string the header carried.
   */
  @Test
  fun `the default policy is the previous literal byte for byte`() {
    assertEquals(
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
        "font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; " +
        "base-uri 'self'; object-src 'none'",
      contentSecurityPolicy(),
    )
    assertEquals(contentSecurityPolicy(), CONTENT_SECURITY_POLICY)
  }

  @Test
  fun `extra sources append to the named directive and a directive the default lacks is added`() {
    val policy =
      contentSecurityPolicy(
        mapOf(
          "script-src" to listOf("https://accounts.google.com/gsi/client", "'self'"),
          "frame-src" to listOf("https://accounts.google.com/gsi/"),
          "child-src" to emptyList(),
        ),
      )

    val directives = policy.split(";").map { it.trim() }
    assertTrue("script-src 'self' https://accounts.google.com/gsi/client" in directives, "script-src not widened: [$policy]")
    assertEquals("frame-src https://accounts.google.com/gsi/", directives.last(), "frame-src not added after the default's")
    assertTrue(directives.none { it.startsWith("child-src") }, "a directive with no sources was written: [$policy]")
    assertTrue("style-src 'self'" in directives, "a directive nobody widened changed: [$policy]")
  }

  /**
   * A nested install REPLACES the root's policy on its route rather than
   * adding a second header (RFC 275 §6). Two `Content-Security-Policy` headers
   * are enforced as their INTERSECTION, so a widened page would silently be
   * the default page again; the count is the claim.
   */
  @Test
  fun `a nested install replaces the root policy with exactly one header`() =
    testApplication {
      val widened = contentSecurityPolicy(mapOf("script-src" to listOf("https://example.test")))
      routing {
        setContentSecurityPolicy()
        get("/plain") { call.respondText("plain") }
        route("/widened") {
          setContentSecurityPolicy(widened)
          get { call.respondText("widened") }
        }
      }

      assertEquals(listOf(widened), client.get("/widened").headers.getAll(CONTENT_SECURITY_POLICY_HEADER))
      assertEquals(listOf(CONTENT_SECURITY_POLICY), client.get("/plain").headers.getAll(CONTENT_SECURITY_POLICY_HEADER))
    }
}
