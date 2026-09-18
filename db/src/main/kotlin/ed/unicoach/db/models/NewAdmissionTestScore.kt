package ed.unicoach.db.models

/**
 * Creation input for [AdmissionTestScore]. No default values, deliberately
 * ([NewMoneyProfile]'s reason): adding a field must make the compiler list
 * every call site rather than let a positional construction silently write a
 * wrong scale or precision.
 */
data class NewAdmissionTestScore(
  val studentId: StudentId,
  val test: AdmissionTest,
  val component: AdmissionTestScoreComponent,
  val score: Int,
  val sitting: PartialDate,
)
