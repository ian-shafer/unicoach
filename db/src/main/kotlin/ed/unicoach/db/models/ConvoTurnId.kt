package ed.unicoach.db.models

/**
 * The grouping key for one logical user turn on `convo_requests.turn_id`: all
 * rows of a tool-use excursion (the `kind='user'` opener and each
 * `kind='tool_result'` continuation) share one value, minted once per turn from
 * `convo_turn_id_seq`. It lives in its own namespace — a grouping key, never
 * compared to a [ConvoRequestId]. Matches the [ConvoRequestId] value-class
 * convention.
 */
@JvmInline
value class ConvoTurnId(
  val value: Long,
) {
  val asString get() = value.toString()
}
