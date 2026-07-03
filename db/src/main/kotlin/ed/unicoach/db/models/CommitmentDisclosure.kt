package ed.unicoach.db.models

/**
 * Whether a commitment is announced to the student as a promise (`EXPLICIT`) or
 * kept as a coaching note (`INTERNAL`). `INTERNAL` means "not announced," not
 * "hidden": an internal commitment persists in the record and feeds the next
 * synthesis pass, but is never surfaced as an opener and is excluded from the
 * promise-kept metric. Persisted as the lowercase [value] string matching the
 * `commitments_disclosure_check` CHECK.
 */
enum class CommitmentDisclosure(
  val value: String,
) {
  EXPLICIT("explicit"),
  INTERNAL("internal"),
  ;

  companion object {
    fun fromValue(value: String): CommitmentDisclosure? = entries.find { it.value == value }
  }
}
