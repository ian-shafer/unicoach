package ed.unicoach.rest.models

data class UpdateCollegeListEntryRequest(
  val version: Int,
  val status: String,
  val reasons: String?,
  /**
   * This school's living-plan override (RFC 152 D2a), in three states rather
   * than two (RFC 164). A value SETS the override; `livingPlanClear` = true
   * CLEARS it back to the family's usual plan; an omitted key -- or an explicit
   * `null`, which Jackson cannot tell apart from an omission -- KEEPS whatever
   * is stored.
   *
   * Keeping is the default because it is what a client that does not manage
   * this field means when it says nothing about it. The two keys together are a
   * 400, exactly as on [UpdateMoneyProfileRequest].
   */
  val livingPlan: String? = null,
  /** Clear this school's override back to the family's usual plan; mutually exclusive with [livingPlan]. */
  val livingPlanClear: Boolean = false,
  val addObservationIds: List<Long> = emptyList(),
)
