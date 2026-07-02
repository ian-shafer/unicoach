package ed.unicoach.db.models

data class NewCollegeListEntry(
  val studentId: StudentId,
  val collegeId: CollegeId,
  val status: CollegeListEntryStatus,
  val reasons: String?,
)
