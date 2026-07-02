package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId

sealed interface UpdateEntryResult {
  data class Success(
    val entry: CollegeListEntry,
    val supportingObservations: List<Observation>,
  ) : UpdateEntryResult

  data object NotFound : UpdateEntryResult

  data object VersionConflict : UpdateEntryResult

  /** `reasons` violated the DB's length/non-empty CHECK. */
  data object InvalidReasons : UpdateEntryResult

  data class ObservationNotFound(
    val observationId: ObservationId,
  ) : UpdateEntryResult
}
