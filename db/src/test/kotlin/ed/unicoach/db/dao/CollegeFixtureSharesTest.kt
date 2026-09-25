package ed.unicoach.db.dao

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four shared completion fixtures, held to the two claims their one home
 * makes about them (RFC 242).
 *
 * A FILE-only suite: no database, because both claims are about the values
 * themselves. They used to be prose beside seven copies of the numbers, which
 * is a claim nothing can check and any one copy can stop making.
 */
class CollegeFixtureSharesTest {
  /**
   * The Pell arm is ABOVE the non-Pell arm.
   *
   * Measured over the pinned snapshot, the Pell figure is the LOWER of the two
   * at only 65.8% of institutions, so it is the MINORITY ordering that every
   * fixture row must carry: ordered the common way round, a surface that
   * assumed "Pell is lower" would look right in every suite that reads this
   * row.
   */
  @Test
  fun `the fixture row keeps the Pell arm above the non-Pell one, so no ordering assumption can look true`() {
    assertTrue(
      FIXTURE_COMPLETION_8YR_PELL_SHARE > FIXTURE_COMPLETION_8YR_NON_PELL_SHARE,
      "measured, the Pell arm is the LOWER of the two at only 65.8% of institutions, so the fixture carries the " +
        "other ordering: pell=[$FIXTURE_COMPLETION_8YR_PELL_SHARE] " +
        "nonPell=[$FIXTURE_COMPLETION_8YR_NON_PELL_SHARE]",
    )
  }

  /** All four DISTINCT, so a mapper that reads a neighbour's column fails on the number rather than passing. */
  @Test
  fun `the four fixture shares are four different numbers`() {
    val shares =
      listOf(
        FIXTURE_COMPLETION_8YR_SHARE,
        FIXTURE_COMPLETION_8YR_PELL_SHARE,
        FIXTURE_COMPLETION_8YR_NON_PELL_SHARE,
        FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE,
      )
    assertEquals(shares.size, shares.toSet().size, "two of the four share a number: [$shares]")
  }

  /** Every one of them is a share, in the `[0, 1]` domain the four `colleges` columns CHECK. */
  @Test
  fun `every fixture share is in the zero-to-one share domain`() {
    for (
    share in
    listOf(
      FIXTURE_COMPLETION_8YR_SHARE,
      FIXTURE_COMPLETION_8YR_PELL_SHARE,
      FIXTURE_COMPLETION_8YR_NON_PELL_SHARE,
      FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE,
    )
    ) {
      assertTrue(share in 0.0..1.0, "[$share] is not a share, and the `colleges` CHECK would refuse the insert")
    }
  }
}
