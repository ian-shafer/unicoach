package ed.unicoach.db.models

/**
 * WHAT KIND OF DATE a `fafsa_filing_deadlines` row carries (RFC 215, brief 0012
 * D4): the day the form opens, the day a family should file by to be considered
 * for the money that runs out first, the last day a filing is accepted at all,
 * and the last day an error on a filed form may be corrected.
 *
 * Our OWN enumeration, so the schema is `TEXT` + `CHECK IN (...)` and this is
 * the one Kotlin class that names the members, with a [fromValue] companion
 * (CLAUDE.md, the [AidForm] / [SchoolDayParticipation] precedent).
 *
 * THE PUBLISHERS' WORDS ARE NOT IN HERE, and that is the whole point of the
 * enum being four long. Fifty agencies name the same event fifty ways -- a
 * "priority filing date", a "first consideration date", a "recommended date" --
 * and an arm per wording would be a vocabulary nobody owns. The publisher's own
 * sentence rides on `deadline_as_published`
 * ([PublishedDeadline.deadlineAsPublished]) and is shown beside our code. A
 * caller branching on THIS to fix up its own wording has read the wrong field.
 *
 * It stays a per-table CHECK until a SECOND table asks the same question, at
 * which point it becomes `CREATE DOMAIN aid_deadline_event` and both columns
 * take it -- the rule RFC 213 states for `link_status`, and the DDL's column
 * comment records the trigger.
 */
enum class AidDeadlineEvent(
  /** The stored code, and the `CHECK IN (...)` the schema admits. */
  val value: String,
) {
  /** The day the form for this cycle starts accepting filings. */
  FORM_OPENS(value = "form_opens"),

  /**
   * The day the publisher asks families to file BY, for aid it awards in some
   * order it controls. Missing it is not the end of filing; it is the end of
   * being first, which is a different sentence and the reason this is not
   * [FINAL_DEADLINE].
   */
  PRIORITY_DEADLINE(value = "priority_deadline"),

  /** The last day a filing is accepted at all for this cycle. */
  FINAL_DEADLINE(value = "final_deadline"),

  /**
   * The last day an error on an ALREADY FILED form may be fixed -- 11 September
   * 2027 for the 2026-27 cycle per the Federal Register, which `studentaid.gov`
   * prints as 12 September. That one-day disagreement is two rows with two
   * sources and is never resolved on the publishers' behalf.
   */
  CORRECTIONS_DEADLINE(value = "corrections_deadline"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): AidDeadlineEvent? = entries.find { it.value == value }
  }
}
