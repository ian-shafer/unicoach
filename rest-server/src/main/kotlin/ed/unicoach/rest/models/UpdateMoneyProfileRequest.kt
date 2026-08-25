package ed.unicoach.rest.models

/**
 * The PUT body for the money profile (RFC 134): an idempotent create-or-update
 * of any subset of fields. Per field, exactly one of value / declined / clear
 * may be supplied; an omitted field is untouched. Value and declined (or
 * clear) together for the same field is a validation failure.
 */
data class UpdateMoneyProfileRequest(
  val incomeBand: String? = null,
  val incomeBandDeclined: Boolean = false,
  val incomeBandClear: Boolean = false,
  val residencyState: String? = null,
  val residencyDeclined: Boolean = false,
  val residencyClear: Boolean = false,
)
