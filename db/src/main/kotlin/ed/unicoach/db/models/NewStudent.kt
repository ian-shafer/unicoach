package ed.unicoach.db.models

import java.time.LocalDate

data class NewStudent(
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  val dateOfBirth: LocalDate,
)
