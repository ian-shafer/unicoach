package ed.unicoach.rest.models

data class UpdateStudentRequest(
  val expectedHighSchoolGraduationDate: String,
  val version: Int,
)
