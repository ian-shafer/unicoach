package ed.unicoach.coaching.budget

import ed.unicoach.common.money.Nanodollars

/**
 * One student's coaching entitlement (RFC 109): the two meters — lifetime
 * [spent] off the `student_llm_cost` ledger and the configured free
 * [allowance] — and the two facts derived from them. Both consumers read the
 * same value (the gate takes [exhausted], the usage endpoint takes
 * [usedPercent]), so a blocked student and a 100%-used bar can never disagree.
 *
 * Both derivations are integer arithmetic over [Nanodollars.value] — no floating
 * point, so no rounding can put the two facts on opposite sides of the boundary.
 */
class Entitlement(
  val spent: Nanodollars,
  val allowance: Nanodollars,
) {
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
   * [BudgetConfig] refuses an allowance whose ×100 would leave a [Long]. But
   * [Entitlement] takes a bare [Nanodollars], so the guard is restated at the
   * arithmetic itself — an allowance reaching here by any other path throws
   * (failing the gate read closed) rather than wrapping to a garbage percentage.
   */
  val usedPercent: Int =
    if (exhausted) 100 else (Math.multiplyExact(spent.value, 100L) / allowance.value).toInt()
}
