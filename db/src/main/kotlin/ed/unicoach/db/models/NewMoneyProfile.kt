package ed.unicoach.db.models

/**
 * Creation input for [MoneyProfile]. No default values, deliberately: adding a
 * field must make the compiler list every call site rather than let a
 * positional construction silently write `unanswered` (RFC 152).
 */
data class NewMoneyProfile(
  val studentId: StudentId,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
  val livingPlan: LivingArrangement?,
  val livingPlanStatus: AnswerStatus,
)
