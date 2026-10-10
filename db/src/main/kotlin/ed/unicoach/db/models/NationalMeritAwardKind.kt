package ed.unicoach.db.models

/**
 * What a named school's own page publishes about its National Merit award
 * (`college_national_merit_awards.award_kind`, RFC 243).
 *
 * Our OWN enumeration, so the column is `TEXT` + `CHECK IN (...)` and this is
 * the one Kotlin home for it (CLAUDE.md, the [AutomaticAwardKind] precedent one
 * corpus along).
 *
 * [COMPETITIVE] IS THE MEMBER THIS ENUM EXISTS FOR. A page that says a Finalist
 * is CONSIDERED for a package has not promised it -- UCF's "more than $25,000 a
 * year" is "considered for", and Tulsa adds a 4.0, a campus visit and an
 * interview. Folding that into [AUTOMATIC] would have the coach tell a family
 * that six figures follows from a standing the school merely weighs, which is
 * the costliest error this corpus can make.
 */
enum class NationalMeritAwardKind(
  val value: String,
) {
  /** The page guarantees the award to anyone holding the standing: no committee, no separate contest. */
  AUTOMATIC("automatic"),

  /** The standing is CONSIDERED and not paid. Never spoken as money the student will receive. */
  COMPETITIVE("competitive"),

  /**
   * We READ this school's page on `checked_on` and it publishes no National
   * Merit award at all. A verified absence WITH a citation, which is a
   * different answer from "we have not looked" -- the argument
   * `fafsa_filing_deadlines` makes for `deadline_kind='none'` and RFC 239 makes
   * for its own `none_published`.
   */
  NONE_PUBLISHED("none_published"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): NationalMeritAwardKind? = entries.find { it.value == value }
  }
}
