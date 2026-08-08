package ed.unicoach.coaching.budget

/**
 * The decision every coaching gate asks [BudgetService] for before spending
 * (RFC 109): may this student's next turn or pass make an LLM call?
 *
 * Both outcomes are named. The alternative — handing back an [Entitlement] and
 * letting each gate re-derive [Entitlement.exhausted], or handing back a
 * nullable whose presence means "blocked" — spreads one policy decision across
 * every call site and inverts the polarity of the answer. Here the gate decides
 * once and each caller only chooses how to refuse.
 *
 * [Exhausted] carries the [Entitlement] the refusal was decided on, because
 * every refusal downstream states the spend and allowance it refused at.
 * [Entitled] carries nothing: a caller that proceeds has no use for the meters,
 * and a caller that wants them for their own sake reads
 * [BudgetService.entitlement] directly.
 */
sealed interface BudgetVerdict {
  /** Under the allowance: the turn or pass proceeds. */
  data object Entitled : BudgetVerdict

  /** At or past the allowance: the caller refuses, in its own refusal shape. */
  data class Exhausted(
    val entitlement: Entitlement,
  ) : BudgetVerdict
}
