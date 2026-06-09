package ed.unicoach.db.models

import java.time.Instant

data class Student(
  override val id: StudentId,
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<StudentId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
