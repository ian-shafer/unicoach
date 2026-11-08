package ed.unicoach.db.models

/** Closed enum backing `college_tuition_exchanges.selection` (RFC 277): does meeting the criteria get the rate? */
enum class ExchangeSelection(
  val value: String,
) {
  GUARANTEED("guaranteed"),
  SELECTIVE("selective"),

  /** The school's page does not say. */
  UNSTATED("unstated"),
  ;

  companion object {
    fun fromValue(value: String): ExchangeSelection? = entries.find { it.value == value }
  }
}
