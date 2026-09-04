package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the versioned mutable `money_profiles` entity (RFC 134, extended by
 * RFCs 152 and 159): one per student, four tri-state profile fields. The
 * schema's value-iff-answered CHECKs guarantee [incomeBand] is non-null exactly
 * when [incomeBandStatus] is [AnswerStatus.ANSWERED], and likewise for
 * [residencyState] / [residencyStatus], [livingPlan] / [livingPlanStatus] and
 * [dependency] / [dependencyStatus].
 * Mirrors [CollegeListEntry]'s shape (OCC [version], soft-delete via
 * [deletedAt]).
 *
 * [livingPlan] is the family's DEFAULT plan -- where the student would live
 * when they have the choice. A school the family has decided differently about
 * carries its own `CollegeListEntry.livingPlan` override; resolution is
 * override -> default -> no plan (RFC 152 D2a), and it lives in exactly one
 * helper in `:service`.
 */
data class MoneyProfile(
  override val id: MoneyProfileId,
  val studentId: StudentId,
  val incomeBand: IncomeBand?,
  val incomeBandStatus: AnswerStatus,
  val residencyState: String?,
  val residencyStatus: AnswerStatus,
  val livingPlan: LivingArrangement?,
  val livingPlanStatus: AnswerStatus,
  val dependency: DependencyStatus?,
  val dependencyStatus: AnswerStatus,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<MoneyProfileId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
