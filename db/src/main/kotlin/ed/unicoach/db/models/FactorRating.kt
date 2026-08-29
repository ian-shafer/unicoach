package ed.unicoach.db.models

/**
 * Closed enum backing the 18 CDS C7 factor-rating columns on
 * `college_admission_factors` (RFC 140): the literal CDS vocabulary ("Very
 * Important" ... "Not Considered") stored as snake_case codes. A NULL column is
 * "not reported" -- including extraction junk dropped by the fetcher's
 * whitelist -- and has no enum member.
 */
enum class FactorRating(
  val value: String,
) {
  VERY_IMPORTANT("very_important"),
  IMPORTANT("important"),
  CONSIDERED("considered"),
  NOT_CONSIDERED("not_considered"),
  ;

  companion object {
    fun fromValue(value: String): FactorRating? = entries.find { it.value == value }
  }
}
