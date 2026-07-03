package ed.unicoach.db.models

/**
 * Why a `convo_requests` row exists. `USER` is real student input (visible and
 * extractable); `TOOL_RESULT` is a synthetic chat tool-use loop continuation
 * carrying tool_result blocks (excluded from every projection). Persisted as the
 * lowercase [value] string matching the `convo_requests_kind_valid_check` CHECK.
 */
enum class ConvoRequestKind(
  val value: String,
) {
  USER("user"),
  TOOL_RESULT("tool_result"),
  ;

  companion object {
    fun fromValue(value: String): ConvoRequestKind? = entries.find { it.value == value }
  }
}
