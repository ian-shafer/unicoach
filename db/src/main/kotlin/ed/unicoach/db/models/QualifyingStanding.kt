package ed.unicoach.db.models

/**
 * WHICH National Merit standing a school pays
 * (`college_national_merit_awards.qualifying_standing`, RFC 243).
 *
 * Our OWN enumeration (`TEXT` + `CHECK IN (...)`, CLAUDE.md), and the column
 * that made this corpus a new table rather than a widening of
 * `college_automatic_awards`: A STANDING IS NOT A SCORE. NMSC awards it once,
 * after a state-allocated screen we do not hold and will never hold, so it
 * cannot be a band on any published scale.
 *
 * IT IS PART OF THE NATURAL KEY, and Maine is why: it publishes a Semifinalist
 * row AND a Finalist row, and a school that pays one and not the other is the
 * common shape.
 */
enum class QualifyingStanding(
  val value: String,
) {
  /** The standing the large university packages are paid against. */
  FINALIST("finalist"),

  /** Allocated state-representationally; a school may pay it at a different rate, or not at all. */
  SEMIFINALIST("semifinalist"),

  /** Set by one national number, and the stage most of the ~1% who get anywhere reach. */
  COMMENDED("commended"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): QualifyingStanding? = entries.find { it.value == value }
  }
}
