package ed.unicoach.db.models

/**
 * Update-input record for [MoneyProfile], sibling of the [NewMoneyProfile]
 * creation input. Carries the entity identity, the expected OCC [version], and
 * only the mutable business fields.
 *
 * No default values, for [NewMoneyProfile]'s reason: a positional construction
 * that compiles against a field it never named would write `unanswered` and
 * report nothing.
 */
data class MoneyProfileEdit(
  val id: MoneyProfileId,
  val version: Int,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
  val livingPlan: LivingArrangement?,
  val livingPlanStatus: AnswerStatus,
)
