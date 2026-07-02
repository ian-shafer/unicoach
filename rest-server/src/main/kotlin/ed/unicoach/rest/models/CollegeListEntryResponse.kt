package ed.unicoach.rest.models

import java.time.Instant
import java.util.UUID

data class CollegeListEntryResponse(
  val entry: PublicCollegeListEntry,
)

data class PublicCollegeListEntry(
  val id: UUID,
  val collegeId: UUID,
  val status: String,
  val reasons: String?,
  val version: Int,
  val createdAt: Instant,
  val updatedAt: Instant,
  val supportingObservations: List<ObservationSummary>,
)

data class ObservationSummary(
  val id: Long,
  val quote: String,
  val utteredAt: Instant,
)
