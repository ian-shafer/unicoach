package ed.unicoach.rest.models

import java.util.UUID

data class CreateCollegeListEntryRequest(
  val collegeId: UUID,
  val status: String = "considering",
  val reasons: String? = null,
  val livingPlan: String? = null,
  val observationIds: List<Long> = emptyList(),
)
