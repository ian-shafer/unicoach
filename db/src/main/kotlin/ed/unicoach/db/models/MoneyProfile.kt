package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the versioned mutable `money_profiles` entity (RFC 134): one per
 * student, two tri-state profile fields. The schema's value-iff-answered CHECKs
 * guarantee [incomeBand] is non-null exactly when [incomeBandStatus] is
 * [AnswerStatus.ANSWERED], and likewise for [residencyState] /
 * [residencyStatus]. Mirrors [CollegeListEntry]'s shape (OCC [version],
 * soft-delete via [deletedAt]).
 */
data class MoneyProfile(
  override val id: MoneyProfileId,
  val studentId: StudentId,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<MoneyProfileId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
