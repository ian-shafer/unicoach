package ed.unicoach.db.models

/**
 * What a student may allow the people they share with to see of their own data
 * (RFC 248). Persisted as the lowercase [value] string matching the
 * `sharing_permissions_permission_check` CHECK.
 *
 * A permission names the DATA, not the surface it shows on: every surface that
 * shows that data to someone else reads the same answer. Adding an entry is a
 * migration that widens the CHECK, so every new permission is reviewed.
 */
enum class SharingPermission(
  val value: String,
) {
  /** "People I share with may see what I want to study." First reader: the Family Cost Report. */
  INTENDED_SUBJECTS("intended_subjects"),
  ;

  companion object {
    fun fromValue(value: String): SharingPermission? = entries.find { it.value == value }
  }
}
