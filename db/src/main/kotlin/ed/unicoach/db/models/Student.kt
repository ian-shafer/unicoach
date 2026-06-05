package ed.unicoach.db.models

import java.time.Instant

data class Student(
  override val id: StudentId,
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  override val versionId: StudentVersionId,
  override val createdAt: Instant,
  override val rowCreatedAt: Instant,
  override val updatedAt: Instant,
  override val rowUpdatedAt: Instant,
  val deletedAt: Instant?,
) : BaseEntity<StudentId, StudentVersionId>,
  AdvancedEntity
