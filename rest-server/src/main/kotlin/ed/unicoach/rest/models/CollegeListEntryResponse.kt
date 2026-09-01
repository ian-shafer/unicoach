package ed.unicoach.rest.models

import java.time.Instant
import java.util.UUID

data class CollegeListEntryResponse(
  val entry: PublicCollegeListEntry,
)

data class PublicCollegeListEntry(
  val id: UUID,
  val collegeId: UUID,
  val collegeName: String,
  val status: String,
  val reasons: String?,
  /** This school's own living plan, or null: "no override, use the usual plan" (RFC 152). */
  val livingPlan: String?,
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
