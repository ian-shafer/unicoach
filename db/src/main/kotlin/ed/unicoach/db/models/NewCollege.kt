package ed.unicoach.db.models

/**
 * Input for upserting a [College] on the natural key `unitId`. Carries no `id`
 * (DB-generated) and no timestamps (DB-managed); every Scorecard-derived
 * optional column is nullable so a blank source cell maps to `null`.
 */
data class NewCollege(
  val unitId: Int,
  val opeid: String?,
  val name: String,
  val city: String,
  val state: String,
  val region: Int?,
  val locale: Int?,
  val latitude: Double?,
  val longitude: Double?,
  val control: Int,
  val undergradEnrollment: Int?,
  val admissionRate: Double?,
  val satAvg: Int?,
  val costAttendance: Int?,
  val netPrice: Int?,
  // Average annual net price by household income bracket (RFC 133, Scorecard
  // NPT41..NPT45 keyed on control): q1 = $0-30k, q2 = $30,001-48k,
  // q3 = $48,001-75k, q4 = $75,001-110k, q5 = $110k+. Negative values are
  // legitimate (aid exceeding cost, 0022); null = not reported/suppressed.
  val netPriceQ1: Int?,
  val netPriceQ2: Int?,
  val netPriceQ3: Int?,
  val netPriceQ4: Int?,
  val netPriceQ5: Int?,
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  // Median cumulative federal debt of completers (Scorecard GRAD_DEBT_MDN, RFC 133).
  val medianDebt: Int?,
  val pctPell: Double?,
  val website: String?,
)
