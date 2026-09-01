package ed.unicoach.rest.models

data class UpdateCollegeListEntryRequest(
  val version: Int,
  val status: String,
  val reasons: String?,
  /**
   * This school's living-plan override (RFC 152 D2a), written wholesale like
   * [reasons] and REQUIRED on the wire for the same reason [status] and
   * [reasons] are: `null` clears the override back to the family's usual plan,
   * so a client that could omit the key would delete a fact the family stated
   * without ever mentioning it. Clearing is an act a caller performs, never one
   * an absent key performs for it.
   */
  val livingPlan: String?,
  val addObservationIds: List<Long> = emptyList(),
)
