package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Observation

sealed interface GetEntryResult {
  data class Found(
    val entry: CollegeListEntry,
    val supportingObservations: List<Observation>,
  ) : GetEntryResult

  data object NotFound : GetEntryResult
}
