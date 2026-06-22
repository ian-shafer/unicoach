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
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  val pctPell: Double?,
  val website: String?,
)
