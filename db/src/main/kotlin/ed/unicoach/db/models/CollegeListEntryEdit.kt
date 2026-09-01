package ed.unicoach.db.models

/**
 * Update-input record for [CollegeListEntry], sibling of the
 * [NewCollegeListEntry] creation input. Carries the entity identity, the
 * expected OCC [version], and only the mutable business fields.
 */
data class CollegeListEntryEdit(
  val id: CollegeListEntryId,
  val version: Int,
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
