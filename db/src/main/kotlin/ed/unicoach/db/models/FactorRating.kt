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
  /**
   * The words a coach says out loud. The stored code is source shape; this is
   * the advice surface's vocabulary, and it lives here so the copy has exactly
   * one home and no renderer can put `very_important` in front of a family
   * (RFC 143 / RFC 148 D6). Nothing accepts a [FactorRating] back as input, so
   * the code itself is never the contract on the wire.
   */
  val label: String,
) {
  VERY_IMPORTANT("very_important", "very important"),
  IMPORTANT("important", "important"),
  CONSIDERED("considered", "considered"),
  NOT_CONSIDERED("not_considered", "not considered"),
  ;

  companion object {
    fun fromValue(value: String): FactorRating? = entries.find { it.value == value }
  }
}
