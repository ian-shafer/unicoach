package ed.unicoach.db.models

/**
 * Closed enum backing `tuition_exchange_states.exchange` and
 * `college_tuition_exchanges.exchange` (RFC 277).
 *
 * The two names are what a family is told; [value] is never spoken.
 * [ceilingPercentOfResidentTuition] is the highest cap the exchange publishes: a
 * corpus row above it is a typing error, which the seed loader refuses by line.
 */
enum class TuitionExchange(
  val value: String,
  val displayName: String,
  val shortName: String,
  val ceilingPercentOfResidentTuition: Int,
) {
  WUE("wue", "Western Undergraduate Exchange", "WUE", 150),
  ;

  companion object {
    fun fromValue(value: String): TuitionExchange? = entries.find { it.value == value }
  }
}
