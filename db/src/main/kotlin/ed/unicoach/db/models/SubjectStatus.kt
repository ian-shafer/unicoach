package ed.unicoach.db.models

/**
 * Whether the authored taxonomy still carries a `subjects` row (RFC 205 D40).
 *
 * A status column rather than a `retired_at` timestamp or a boolean: a timestamp
 * would claim an event history this table cannot honestly keep — a subject can
 * be dropped from `subjects.json` and later restored, and there is no "retired
 * at" moment worth recording in that round trip — while a timestamp and a
 * boolean are both two-valued. A status leaves room for a third state without a
 * migration. Spelled the way [CollegeListEntryStatus] is spelled.
 */
enum class SubjectStatus(
  val value: String,
) {
  /** The taxonomy file carries this subject. It is offered, and it matches colleges. */
  ACTIVE("active"),

  /**
   * The taxonomy file no longer carries this subject, but a student already
   * chose it, so the row stays and their answer stays resolvable to its name.
   *
   * A deprecated subject is offered to NOBODY: it is absent from the picker and
   * absent from the search index, so it matches no college. It resolves only for
   * the student who already chose it.
   */
  DEPRECATED("deprecated"),
  ;

  companion object {
    fun fromValue(value: String): SubjectStatus? = entries.find { it.value == value }
  }
}
