package ed.unicoach.db.models

data class NewStudent(
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
)
