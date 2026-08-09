package ed.unicoach.coaching.budget

import ed.unicoach.common.money.Nanodollars
import java.time.Instant

/**
 * Which meter an [Entitlement] reports: the lifetime free allowance, or a
 * subscription period (RFC 110).
 *
 * Sealed, carrying the reset point on the one basis that has one, rather than an
 * enum beside a nullable `resetsAt`: the pairing is then the type itself, so the
 * inconsistent combination has no constructor to call and needs no runtime check.
 */
sealed interface EntitlementBasis {
  /** RFC 109's lifetime meter: one window over the student's whole history, so no reset point. */
  data object FreeAllowance : EntitlementBasis

  /** RFC 110's period meter, windowed over `[period_start, period_end)`. */
  data class Subscription(
    /** The period's end — where this meter resets, and the next window begins. */
    val resetsAt: Instant,
  ) : EntitlementBasis
}

/**
 * One student's coaching entitlement (RFC 109; subscription basis RFC 110): the
 * two meters — [spent] off the `student_llm_cost` ledger (lifetime, or windowed
 * over the subscription period per [basis]) and the [allowance] it is measured
 * against — and the two facts derived from them. Both consumers read the same
 * value (the gate takes [exhausted], the usage endpoint takes [usedPercent]),
 * so a blocked student and a 100%-used bar can never disagree.
 *
 * Both derivations are integer arithmetic over [Nanodollars.value] — no floating
 * point, so no rounding can put the two facts on opposite sides of the boundary.
 */
class Entitlement(
  val spent: Nanodollars,
  val allowance: Nanodollars,
  val basis: EntitlementBasis,
) {
  /**
   * The meter's reset point, for the consumers that want it without unpacking
   * [basis]: null on [EntitlementBasis.FreeAllowance], whose lifetime meter never
   * resets. The exhaustive `when` is the point — a third basis is a compile error
   * here, where a cast-and-null would quietly report "never resets" for it.
   */
  val resetsAt: Instant?
    get() =
      when (basis) {
        is EntitlementBasis.FreeAllowance -> null
        is EntitlementBasis.Subscription -> basis.resetsAt
      }

  /**
   * At or past the allowance: the pre-call block condition. `>=` rather than
   * `>` because the check is made BEFORE a call whose cost is not yet known, and
   * because it makes a zero allowance (the kill switch) exhaust at zero spend.
   */
  val exhausted: Boolean = spent.value >= allowance.value

  /**
   * The abstract meter the client renders: `floor(spent / allowance × 100)`,
   * capped at 100. Flooring means a sub-allowance ratio can never round up to a
   * false block signal, so `usedPercent == 100` holds exactly when [exhausted]
   * does. Reading it off [exhausted] also keeps a zero allowance from dividing.
   *
   * The multiplication is [Math.multiplyExact], not `*`. It cannot overflow for
   * an allowance that came from config: this branch has `spent < allowance`, and
   * every config reader of a percentage-checked amount ([BudgetConfig], and
   * `SubscriptionPlans` for the subscription basis) refuses one above
   * [Nanodollars.MAX_FOR_PERCENTAGE] — the ceiling this line's `× 100` is what
   * defines, held in one place so the readers cannot drift from it. But
   * [Entitlement] takes a bare [Nanodollars], so the guard is restated at the
   * arithmetic itself — an allowance reaching here by any other path throws
   * (failing the gate read closed) rather than wrapping to a garbage percentage.
   */
  val usedPercent: Int =
    if (exhausted) 100 else (Math.multiplyExact(spent.value, 100L) / allowance.value).toInt()
}
