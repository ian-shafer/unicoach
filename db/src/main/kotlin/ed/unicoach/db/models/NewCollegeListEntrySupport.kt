package ed.unicoach.db.models

/** Insert input for a `college_list_entry_support` link; omits the DB-generated `created_at`. */
data class NewCollegeListEntrySupport(
  val entryId: CollegeListEntryId,
  val observationId: ObservationId,
)
