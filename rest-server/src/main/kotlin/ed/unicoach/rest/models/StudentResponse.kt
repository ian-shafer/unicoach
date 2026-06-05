package ed.unicoach.rest.models

import java.time.Instant
import java.util.UUID

data class StudentResponse(
  val student: PublicStudent,
)

data class PublicStudent(
  val id: UUID,
  val expectedHighSchoolGraduationDate: String,
  val version: Int,
  val createdAt: Instant,
  val updatedAt: Instant,
)
