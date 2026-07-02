package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry

sealed interface RemoveEntryResult {
  data class Success(
    val entry: CollegeListEntry,
  ) : RemoveEntryResult

  data object NotFound : RemoveEntryResult

  data object VersionConflict : RemoveEntryResult
}
