package ed.unicoach.db.models

/**
 * Closed enum backing `supporter_link_requests.resolution` (RFC 254): how a
 * parent's request to connect ended. A pending request has no resolution at
 * all, so pending is not a member — it is the column's `NULL`.
 *
 * The ONE Kotlin spelling of the vocabulary `supporter_link_requests_resolution_check`
 * admits (the TEXT + CHECK + one enum convention).
 */
enum class SupporterLinkRequestResolution(
  val value: String,
) {
  /** The student approved, and a live link was made. */
  APPROVED("approved"),

  /** The student declined. Final for the pair. */
  DECLINED("declined"),

  /** Withdrawn while pending: either account closed, or the report link it came from was turned off. */
  CANCELLED("cancelled"),
  ;

  companion object {
    fun fromValue(value: String): SupporterLinkRequestResolution? = entries.find { it.value == value }
  }
}
