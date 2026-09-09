package ed.unicoach.db.models

/**
 * Input for upserting a [College] on the natural key `ipedsUnitId`. Carries no `id`
 * (DB-generated) and no timestamps (DB-managed); every Scorecard-derived
 * optional column is nullable so a blank source cell maps to `null`.
 *
 * Carries NO money (RFC 176): the eighteen publisher-shaped money columns left
 * `colleges` with migration `0094`, and every figure the product speaks is read
 * from the canonical layer -- `price_figures` and `cohort_money_stats` -- which
 * the same ingest run fills from the same Scorecard CSV.
 */
data class NewCollege(
  val ipedsUnitId: Int,
  val opeid: String?,
  val name: String,
  val city: String,
  val state: String,
  val region: Int?,
  val locale: Int?,
  val latitude: Double?,
  val longitude: Double?,
  val control: Int,
  val undergradEnrollmentHeadcount: Int?,
  val admissionRateShare: Double?,
  val satAverageEquivalentScore: Int?,
  val completionRate150pct4yrShare: Double?,
  val website: String?,
)
