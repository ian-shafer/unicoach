package ed.unicoach.coaching.report

import ed.unicoach.db.models.CostReportShareId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token shape the READER may accept (RFC 155).
 *
 * The mint side produces exactly one shape — 43 base64url characters, the 32
 * bytes of an HMAC-SHA256 digest — so a string of any other shape is one this
 * server could never have issued. The view path used to accept anything
 * non-blank, which turned `?token=` plus a megabyte of newlines into a SHA-256
 * and an indexed lookup.
 */
class ShareTokenTest {
  private val minted: String = ShareTokenDeriver(requireNotNull(ShareTokenSecret.of(SECRET))).derive(ID)

  @Test
  fun `the shape a deriver actually mints is accepted`() {
    assertTrue(ShareToken.isWellFormed(minted), "the reader must accept what the mint produces: [$minted]")
  }

  @Test
  fun `a blank, a short, a long and an out-of-alphabet token are all refused`() {
    assertFalse(ShareToken.isWellFormed(""), "an empty string is not a credential")
    assertFalse(ShareToken.isWellFormed(" ".repeat(43)), "whitespace is not the base64url alphabet")
    assertFalse(ShareToken.isWellFormed("a".repeat(42)), "one character short of the digest length")
    assertFalse(ShareToken.isWellFormed("a".repeat(44)), "one character long of the digest length")
    assertFalse(ShareToken.isWellFormed("a".repeat(42) + "="), "padding is never emitted, so it is never accepted")
    assertFalse(ShareToken.isWellFormed("' OR 1=1"), "a SQL fragment is refused before it is ever hashed")
  }

  @Test
  fun `a token carrying a newline is refused rather than trimmed into shape`() {
    assertFalse(ShareToken.isWellFormed(minted + "\n"), "a trailing newline is a different string, and it is refused")
  }

  private companion object {
    const val SECRET = "a-share-token-secret-long-enough-to-be-a-key"

    /** One fixed row id, so the minted shape is reproducible without a database. */
    val ID = CostReportShareId(UUID.fromString("0190f000-0000-7000-8000-000000000001"))
  }
}
