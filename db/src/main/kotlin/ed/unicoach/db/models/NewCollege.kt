package ed.unicoach.db.models

/**
 * Input for upserting a [College] on the natural key `ipedsUnitId`. Carries no `id`
 * (DB-generated) and no timestamps (DB-managed); every Scorecard-derived
 * optional column is nullable so a blank source cell maps to `null`.
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
  val costOfAttendancePerYearUsd: Int?,
  val netPricePerYearUsd: Int?,
  // Average annual net price by household income bracket (RFC 133, Scorecard
  // NPT41..NPT45 keyed on control): q1 = $0-30k, q2 = $30,001-48k,
  // q3 = $48,001-75k, q4 = $75,001-110k, q5 = $110k+. Negative values are
  // legitimate (aid exceeding cost, 0022); null = not reported/suppressed.
  val netPricePerYearIncomeQ1Usd: Int?,
  val netPricePerYearIncomeQ2Usd: Int?,
  val netPricePerYearIncomeQ3Usd: Int?,
  val netPricePerYearIncomeQ4Usd: Int?,
  val netPricePerYearIncomeQ5Usd: Int?,
  val tuitionAndFeesInStatePerYearUsd: Int?,
  val tuitionAndFeesOutOfStatePerYearUsd: Int?,
  val completionRate150pct4yrShare: Double?,
  val medianEarnings10yAfterEntryUsd: Int?,
  // Median cumulative federal debt of completers (Scorecard GRAD_DEBT_MDN, RFC 133).
  val medianDebtAtCompletionUsd: Int?,
  // The six published cost components (RFC 149, Scorecard ROOMBOARD_ON /
  // ROOMBOARD_OFF / BOOKSUPPLY / OTHEREXPENSE_ON / OTHEREXPENSE_OFF /
  // OTHEREXPENSE_FAM), whole USD per academic year: what the school publishes
  // as its allowance for each living arrangement. Gross costs, so a negative is
  // a loader bug and the schema rejects it; null = not reported, never zero.
  // There is no with-family housing-and-food figure -- the Scorecard publishes
  // none -- so that arrangement renders no housing line rather than a $0 one.
  val housingAndFoodOnCampusPerYearUsd: Int?,
  val housingAndFoodOffCampusPerYearUsd: Int?,
  val booksAndSuppliesPerYearUsd: Int?,
  val otherExpensesOnCampusPerYearUsd: Int?,
  val otherExpensesOffCampusPerYearUsd: Int?,
  val otherExpensesWithFamilyPerYearUsd: Int?,
  val pellShare: Double?,
  val website: String?,
)
