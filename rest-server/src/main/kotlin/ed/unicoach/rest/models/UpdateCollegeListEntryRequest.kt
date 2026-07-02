package ed.unicoach.rest.models

data class UpdateCollegeListEntryRequest(
  val version: Int,
  val status: String,
  val reasons: String?,
  val addObservationIds: List<Long> = emptyList(),
)
