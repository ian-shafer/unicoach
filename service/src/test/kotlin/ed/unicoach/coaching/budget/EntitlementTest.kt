package ed.unicoach.coaching.budget

import ed.unicoach.common.money.Nanodollars
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [Entitlement]'s pure verdict (RFC 109): the `>=` boundary the gate blocks
 * on, and the floored, capped percentage the usage endpoint publishes — including
 * the tie between them (`usedPercent == 100` exactly when `exhausted`). RFC 110
 * adds the basis, and with it the reset point [EntitlementBasis.Subscription]
 * carries; both derivations are unchanged.
 *
 * There are no cases here for a basis paired with the wrong reset point: the
 * sealed [EntitlementBasis] leaves that combination with no constructor to call,
 * so it is a compile error rather than a behaviour a test could reach.
 */
class EntitlementTest {
  private val fiveDollars = Nanodollars.of(5_000_000_000)
  private val periodEnd = Instant.parse("2026-09-01T00:00:00Z")

  private fun entitlement(
    spentNanodollars: Long,
    allowanceNanodollars: Long = fiveDollars.value,
  ) = Entitlement(
    spent = Nanodollars.of(spentNanodollars),
    allowance = Nanodollars.of(allowanceNanodollars),
    basis = EntitlementBasis.FreeAllowance,
  )

  @Test
  fun `below the allowance is entitled`() {
    val verdict = entitlement(spentNanodollars = 4_999_999_999)
    assertFalse(verdict.exhausted)
  }

  @Test
  fun `exactly at the allowance is exhausted — the boundary is inclusive`() {
    val verdict = entitlement(spentNanodollars = fiveDollars.value)
    assertTrue(verdict.exhausted, "the check runs BEFORE a call whose cost is not yet known")
    assertEquals(100, verdict.usedPercent)
  }

  @Test
  fun `past the allowance is exhausted and the percentage caps at 100`() {
    val verdict = entitlement(spentNanodollars = 500_000_000_000)
    assertTrue(verdict.exhausted)
    assertEquals(100, verdict.usedPercent, "overspend never reports above 100")
  }

  @Test
  fun `zero spend reports zero percent`() {
    val verdict = entitlement(spentNanodollars = 0)
    assertFalse(verdict.exhausted)
    assertEquals(0, verdict.usedPercent)
  }

  @Test
  fun `the percentage floors, never rounding up to a false block signal`() {
    assertEquals(50, entitlement(spentNanodollars = 2_500_000_000).usedPercent)
    // $4.999999999 of $5.00 is 99.99...%: it must floor to 99 while still entitled.
    val nearlySpent = entitlement(spentNanodollars = 4_999_999_999)
    assertEquals(99, nearlySpent.usedPercent)
    assertFalse(nearlySpent.exhausted, "99% and entitled must agree — the client never sees 100 before the block")
  }

  @Test
  fun `a zero allowance exhausts at zero spend without dividing`() {
    val verdict = entitlement(spentNanodollars = 0, allowanceNanodollars = 0)
    assertTrue(verdict.exhausted, "the kill switch blocks every student at once")
    assertEquals(100, verdict.usedPercent)
  }

  @Test
  fun `the free-allowance basis reports no reset point — the lifetime meter never resets`() {
    assertEquals(null, entitlement(spentNanodollars = 0).resetsAt)
  }

  @Test
  fun `a subscription entitlement reports its period end and derives unchanged`() {
    val verdict =
      Entitlement(
        spent = Nanodollars.of(2_500_000_000),
        allowance = fiveDollars,
        basis = EntitlementBasis.Subscription(resetsAt = periodEnd),
      )
    assertEquals(EntitlementBasis.Subscription(periodEnd), verdict.basis)
    assertEquals(periodEnd, verdict.resetsAt)
    assertFalse(verdict.exhausted)
    assertEquals(50, verdict.usedPercent, "the derivations do not depend on the basis")
  }

  @Test
  fun `an allowance past the percentage ceiling throws, never wrapping to a garbage percentage`() {
    // BudgetConfig rejects such an allowance at load, but Entitlement takes a bare
    // Nanodollars, so the arithmetic refuses on its own rather than trusting that.
    assertFailsWith<ArithmeticException> {
      entitlement(spentNanodollars = Long.MAX_VALUE / 2, allowanceNanodollars = Long.MAX_VALUE)
    }
  }
}
