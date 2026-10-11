package ed.unicoach.db.models

/**
 * A student's stored answer to one [SharingPermission] (RFC 248). Persisted as
 * the lowercase [value] string matching the `sharing_permissions_status_check`
 * CHECK.
 *
 * There is no "never asked" member: that state is the ABSENCE of a row, and it
 * reads as not granted. Only [GRANTED] grants.
 */
enum class SharingPermissionStatus(
  val value: String,
) {
  GRANTED("granted"),

  /** The student said no, or withdrew an earlier grant. */
  REFUSED("refused"),
  ;

  companion object {
    /**
     * The wire word for "never asked": no row, so no member. Every surface that
     * reports the state (the REST view, the share tool's `your_subjects`) reads it here.
     */
    const val NOT_ASKED_WIRE = "not_asked"

    fun fromValue(value: String): SharingPermissionStatus? = entries.find { it.value == value }
  }
}
