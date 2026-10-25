package ed.unicoach.coaching.collegelist

import kotlinx.serialization.json.JsonPrimitive

/**
 * The college-list block every coach turn carries (RFC 257): the student's
 * active list as it stood when the turn began, so the coach can talk about
 * "your list" without guessing and can pass a listed school's `college_id` to a
 * tool without looking it up again.
 *
 * The copy is named constants and one pure function, so the tests assert the
 * words the coach reads rather than retyping them (the `TestScoreCopy`
 * precedent).
 */
object CollegeListBlock {
  /** The header over a non-empty list. */
  const val HEADER: String =
    "College list (the student's own, as it stands at the start of this turn, in the order they added each school):"

  /**
   * The whole block when the list is empty. A line, not nothing: an absent
   * block cannot be told apart from "the coach was not told", and the prompt
   * has rules that turn on an empty list.
   */
  const val EMPTY_LINE: String = "College list: empty — the student has not added any school yet."

  /** Lead-in for a school's own living plan; the money block already gives the usual one. */
  const val LIVING_PLAN_LEAD: String = "living plan at this school: "

  /**
   * Lead-in for the student's reasons, which follow verbatim as one JSON string
   * literal, so a quote or a line break in their words stays inside the quotes.
   */
  const val REASONS_LEAD: String = "reasons: "

  /**
   * Renders [list] in the order given, which is the order the student added
   * each school. No trailing terminator, like every other system-text block:
   * the assembler owns the blank line between blocks.
   */
  fun render(list: List<EntryWithCollegeName>): String =
    if (list.isEmpty()) EMPTY_LINE else (listOf(HEADER) + list.map(::schoolLine)).joinToString("\n")

  /** One school's line: name, status and id, then its own living plan and the student's reasons when present. */
  private fun schoolLine(named: EntryWithCollegeName): String {
    val (entry, collegeName) = named
    return buildString {
      append("- $collegeName: ${entry.status.value}; college_id ${entry.collegeId.value}")
      // The label, never the `with_family` wire value (RFC 142/152): whatever
      // the coach reads here it may say aloud.
      entry.livingPlan?.let { append("; $LIVING_PLAN_LEAD${it.label}") }
      // A JSON string literal, not hand-placed quotes: a quote or a line break
      // in the student's own words can neither close the quotes early nor start
      // a line that reads as another school.
      entry.reasons?.let { append("; $REASONS_LEAD${JsonPrimitive(it)}") }
    }
  }
}
