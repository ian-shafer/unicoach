package ed.unicoach.db.models

/**
 * The way-of-living axis a published price is on (RFC 158, D4), backing the
 * `arrangements` vocabulary table: [LivingArrangement]'s three ways of living
 * plus [NOT_APPLICABLE], the explicit key an arrangement-invariant concept
 * pairs with (P3).
 *
 * Named `FigureArrangement`, not `Arrangement`, so a read site says which
 * vocabulary it speaks; the living members' values are pinned to
 * [LivingArrangement] by test, because the two enums must never spell one way
 * of living two ways.
 */
enum class FigureArrangement(
  val value: String,
  /** TRUE for a way a student actually lives; FALSE for [NOT_APPLICABLE] and [UNKNOWN]. */
  val isLivingArrangement: Boolean,
) {
  ON_CAMPUS("on_campus", true),
  OFF_CAMPUS("off_campus", true),
  WITH_FAMILY("with_family", true),

  /** The concept does not vary by where the student lives -- explicit, never a NULL default. */
  NOT_APPLICABLE("not_applicable", false),

  /**
   * The source counted students whose living arrangement it did not determine
   * (IPEDS GIS4UN/GRN4UN) -- a published category with a real headcount, not
   * a way of living and not an absence. No price may key on it: a price with
   * an unknown arrangement is a price about nobody.
   */
  UNKNOWN("unknown", false),
  ;

  companion object {
    fun fromValue(value: String): FigureArrangement? = entries.find { it.value == value }
  }
}
