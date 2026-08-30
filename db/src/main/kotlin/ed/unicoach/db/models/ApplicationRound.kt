package ed.unicoach.db.models

/**
 * Closed enum backing `college_deadlines.round` (RFC 140): our own taxonomy
 * over the CDS deadline sections (C14 regular/priority, C16 rolling, C21 early
 * decision, C22 early action).
 *
 * DECLARATION ORDER IS COACH-FACING. `college_admissions_profile` sorts a
 * school's rounds by it (RFC 148 D5), so the sequence below is the sequence a
 * family hears their deadlines in -- the ordinary round first, then the binding
 * and early ones, then rolling. Reordering these members is a product change to
 * the answer, not a cosmetic edit, and `CollegeAdmissionsChatToolTest` pins the
 * rendered order so it cannot happen silently.
 */
enum class ApplicationRound(
  val value: String,
  /**
   * The spoken name of the round, emitted beside [value] from one construct so
   * no call site can leave `early_decision_2` in the model's context without
   * the words "Early Decision 2" (RFC 148 D5).
   */
  val label: String,
) {
  REGULAR("regular", "Regular Decision"),
  PRIORITY("priority", "Priority"),
  EARLY_DECISION_1("early_decision_1", "Early Decision 1"),
  EARLY_DECISION_2("early_decision_2", "Early Decision 2"),
  EARLY_ACTION("early_action", "Early Action"),
  ROLLING("rolling", "Rolling Admission"),
  ;

  companion object {
    fun fromValue(value: String): ApplicationRound? = entries.find { it.value == value }
  }
}
