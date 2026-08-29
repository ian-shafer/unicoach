package ed.unicoach.db.models

/**
 * Closed enum backing `college_deadlines.round` (RFC 140): our own taxonomy
 * over the CDS deadline sections (C14 regular/priority, C16 rolling, C21 early
 * decision, C22 early action).
 */
enum class ApplicationRound(
  val value: String,
) {
  REGULAR("regular"),
  PRIORITY("priority"),
  EARLY_DECISION_1("early_decision_1"),
  EARLY_DECISION_2("early_decision_2"),
  EARLY_ACTION("early_action"),
  ROLLING("rolling"),
  ;

  companion object {
    fun fromValue(value: String): ApplicationRound? = entries.find { it.value == value }
  }
}
