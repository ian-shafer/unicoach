package ed.unicoach.db.models

import java.time.Instant

data class StudentVersion(
  override val id: StudentId,
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  override val versionId: StudentVersionId,
  override val createdAt: Instant,
  val rowCreatedAt: Instant,
  override val updatedAt: Instant,
  val rowUpdatedAt: Instant,
  val deletedAt: Instant?,
) : BaseVersionEntity<StudentId, StudentVersionId>
