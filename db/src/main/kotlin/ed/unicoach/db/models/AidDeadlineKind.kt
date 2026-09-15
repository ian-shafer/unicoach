package ed.unicoach.db.models

/**
 * WHAT THE PUBLISHER ACTUALLY PUBLISHED for one `fafsa_filing_deadlines` row
 * (RFC 215, brief 0012 D4) -- a day, a rule, or nothing at all.
 *
 * Our OWN enumeration, so `TEXT` + `CHECK IN (...)` in the schema and exactly
 * one Kotlin class here, with a [fromValue] companion (CLAUDE.md).
 *
 * D4 IS THE REASON THE COLUMN EXISTS. Unicoach consolidates dry federal and
 * state documents so a family does not have to read them; a consolidation that
 * flattens "as soon as possible after the form opens" into a made-up 1 March is
 * worse than the document it replaced, because the family can act on it. So the
 * three answers are stored as three DIFFERENT things and the value-iff-kind
 * CHECK stops any of them wearing another's clothes.
 *
 * A row is never the absence of knowledge: a MISSING row is "nobody checked"
 * (D9). [NONE] is the verified absence, with a citation and a date of reading,
 * and the public page prints the two differently because a family must be able
 * to tell them apart.
 *
 * The typed form is [PublishedDeadline], which carries the day on the arm that
 * has one, so a kind without its date does not typecheck.
 */
enum class AidDeadlineKind(
  /** The stored code, and the `CHECK IN ('date', 'wording', 'none')` the schema admits. */
  val value: String,
) {
  /** The publisher names a day, and it is in `deadline_date`. */
  DATE(value = "date"),

  /**
   * The publisher names a RULE and no day -- "as soon as possible after October
   * 1", "until funds are exhausted". Carried as those words, and NEVER
   * flattened into a date somebody guessed.
   */
  WORDING(value = "wording"),

  /**
   * We read the publisher's page on `checked_on` and it publishes no deadline
   * for this program at all. A verified absence WITH a citation -- not our
   * silence.
   */
  NONE(value = "none"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): AidDeadlineKind? = entries.find { it.value == value }
  }
}
