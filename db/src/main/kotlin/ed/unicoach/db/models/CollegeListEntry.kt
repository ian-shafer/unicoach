package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the versioned mutable `college_list_entries` entity (RFC 91): a
 * student's status, free-text reasons and per-college living-plan override
 * for a specific college. Mirrors
 * [Student]'s shape (OCC [version], soft-delete via [deletedAt]).
 */
data class CollegeListEntry(
  override val id: CollegeListEntryId,
  val studentId: StudentId,
  val collegeId: CollegeId,
  val status: CollegeListEntryStatus,
  val reasons: String?,
  /**
   * Where the student plans to live AT THIS SCHOOL -- the per-college override
   * of `money_profiles.living_plan` (RFC 152 D2a).
   *
   * `null` IS "no override, use the family's usual plan": one column, not a
   * tri-state, because an entry has nothing to decline (a decline is a global
   * stance). Preference is global; feasibility -- "he can only live at home if
   * the school is commutable" -- is a fact about this student-college pair, and
   * that is what this column holds.
   */
  val livingPlan: LivingArrangement?,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<CollegeListEntryId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
