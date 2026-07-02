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
)
