package ed.unicoach.db.models

/**
 * How precisely a [PartialDate] was recorded: the Kotlin half of the
 * `date_precision` domain (RFC 209, brief 0010 D23).
 *
 * The house pattern for an enumeration we own: `TEXT` + a CHECK in the schema —
 * here written once as a DOMAIN so a second table cannot spell it differently —
 * plus exactly one Kotlin `enum class` with a `fromValue` companion.
 *
 * This type is a STORAGE detail and belongs to the data-access layer. Nothing
 * above a DAO ever holds one: a caller holds a [PartialDate], whose three
 * variants already carry the precision in the type system, so a date whose
 * value and precision disagree is unrepresentable above storage.
 */
enum class DatePrecision(
  val value: String,
) {
  YEAR("year"),
  MONTH("month"),
  DAY("day"),
  ;

  companion object {
    /** The vocabulary, for an error message that names what was expected. */
    const val EXPECTED_FORM = "year | month | day"

    fun fromValue(value: String): DatePrecision? = entries.find { it.value == value }
  }
}
