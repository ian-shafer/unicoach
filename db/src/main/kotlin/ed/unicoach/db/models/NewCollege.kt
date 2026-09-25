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
  /**
   * The six published percentile score bands (RFC 195): Scorecard SATVR25/75,
   * SATMT25/75 and ACTCM25/75, read with their published domains (an SAT
   * section is 200-800, an ACT composite 1-36). Nullable like every other
   * optional Scorecard cell, and each pair arrives present or absent together
   * -- a value outside its domain is coerced to null by the loader and a half
   * band is refused by the `colleges` CHECK.
   */
  val satReading25thPercentileScore: Int?,
  val satReading75thPercentileScore: Int?,
  val satMath25thPercentileScore: Int?,
  val satMath75thPercentileScore: Int?,
  val actComposite25thPercentileScore: Int?,
  val actComposite75thPercentileScore: Int?,
  val completionRate150pct4yrShare: Double?,
  /**
   * RFC 242's four completion figures: Scorecard `OMAWDP8_ALL`,
   * `OMAWDP8_PELL_ALL`, `OMAWDP8_NOPELL_ALL` and `TRANS_4`, each a share in
   * [0, 1].
   *
   * The three eight-year figures count everyone who ENTERED the school in the
   * publisher's `AY 2016-17` cohort -- full-time and part-time, new to college
   * and transferring in -- and had an award within eight years, split by
   * federal Pell Grant receipt. They are a different measure over a different
   * population and a different entering cohort from
   * [completionRate150pct4yrShare], never a later version of it.
   *
   * INDEPENDENTLY nullable, all four. The two Pell arms are not a pair:
   * measured on the pinned snapshot, 112 institutions carry one without the
   * other. And nothing here or above may state an ordering between them -- the
   * Pell figure is the lower of the two at only 65.8% of institutions.
   *
   * [transferOutRate4yrShare] belongs to [completionRate150pct4yrShare]'s
   * cohort, not to the eight-year one, so it is not subtractable from the
   * figures above it.
   */
  val completionRate8yrShare: Double?,
  val completionRate8yrPellShare: Double?,
  val completionRate8yrNonPellShare: Double?,
  val transferOutRate4yrShare: Double?,
  val website: String?,
)
