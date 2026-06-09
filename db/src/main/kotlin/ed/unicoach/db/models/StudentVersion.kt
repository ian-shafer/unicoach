package ed.unicoach.db.models

import java.time.Instant

data class StudentVersion(
  override val id: StudentId,
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  override val version: Int,
  override val createdAt: Instant,
  val updatedAt: Instant,
  val deletedAt: Instant?,
) : Identifiable<StudentId>,
  Created,
  Versioned
