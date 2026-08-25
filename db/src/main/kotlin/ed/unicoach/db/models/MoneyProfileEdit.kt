package ed.unicoach.db.models

/**
 * Update-input record for [MoneyProfile], sibling of the [NewMoneyProfile]
 * creation input. Carries the entity identity, the expected OCC [version], and
 * only the mutable business fields.
 */
data class MoneyProfileEdit(
  val id: MoneyProfileId,
  val version: Int,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
)
