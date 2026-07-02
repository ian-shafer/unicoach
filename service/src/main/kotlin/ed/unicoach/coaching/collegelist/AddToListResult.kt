package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId

sealed interface AddToListResult {
  data class Success(
    val entry: CollegeListEntry,
    val supportingObservations: List<Observation>,
  ) : AddToListResult

  data object CollegeNotFound : AddToListResult

  data object AlreadyOnList : AddToListResult

  /** `reasons` violated the DB's length/non-empty CHECK. */
  data object InvalidReasons : AddToListResult

  /** The cited id is absent, or not owned by this student. */
  data class ObservationNotFound(
    val observationId: ObservationId,
  ) : AddToListResult
}
