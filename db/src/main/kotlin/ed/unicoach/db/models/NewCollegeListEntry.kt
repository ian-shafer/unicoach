package ed.unicoach.db.models

data class NewCollegeListEntry(
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
)
