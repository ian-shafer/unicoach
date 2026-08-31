package ed.unicoach.db.models

/**
 * A single result row from [ed.unicoach.db.dao.CollegesDao.search] (RFC 67): the
 * curated college fields plus [programTitles] — the `cip_title`s of programs
 * matched by the query's `cipPrefix` (empty when no program filter was applied).
 *
 * [netPricePerYearIncomeQ1Usd]..[netPricePerYearIncomeQ5Usd] are the average annual net price by household
 * income band ($0-30k / 30,001-48k / 48,001-75k / 75,001-110k / 110k+) and
 * [medianDebtAtCompletionUsd] the median cumulative federal debt of completers (RFC 133) --
 * returned context only, never filters.
 *
 * [completionRate150pct4yrShare], [medianEarnings10yAfterEntryUsd], and [pellShare] are returned context for the
 * coach to reason over in prose; only [completionRate150pct4yrShare] is also a filter
 * (`minCompletionRate150pct4yrShare`). Earnings and Pell share are surfaced, never thresholded
 * on, because filtering on them is value-laden.
 *
 * [control], [region] and [locale] are the PUBLISHED codes exactly as the source
 * stores them. No code leaves this type as a number: the boundary that speaks to
 * a model resolves each one to its codebook word (RFC 147 D45), the way
 * `InstitutionControl` already did for [control].
 */
data class CollegeMatch(
  val id: CollegeId,
  val ipedsUnitId: Int,
  val name: String,
  val city: String,
  val state: String,
  val control: Int,
  val region: Int?,
  val locale: Int?,
  val undergradEnrollmentHeadcount: Int?,
  val admissionRateShare: Double?,
  val netPricePerYearUsd: Int?,
  val netPricePerYearIncomeQ1Usd: Int?,
  val netPricePerYearIncomeQ2Usd: Int?,
  val netPricePerYearIncomeQ3Usd: Int?,
  val netPricePerYearIncomeQ4Usd: Int?,
  val netPricePerYearIncomeQ5Usd: Int?,
  val completionRate150pct4yrShare: Double?,
  val medianEarnings10yAfterEntryUsd: Int?,
  val medianDebtAtCompletionUsd: Int?,
  val pellShare: Double?,
  val website: String?,
  val programTitles: List<String>,
)
