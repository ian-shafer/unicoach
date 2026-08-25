package ed.unicoach.db.models

data class NewMoneyProfile(
  val studentId: StudentId,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
)
