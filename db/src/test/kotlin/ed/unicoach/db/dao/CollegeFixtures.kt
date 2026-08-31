package ed.unicoach.db.dao

import ed.unicoach.db.models.NewCollege

/**
 * A fully-populated `colleges` row for DAO tests. Suites that only need a
 * college to exist (so a dependent table's FK resolves) call this instead of
 * restating every column; suites asserting on college fields vary only what
 * they assert on.
 */
internal fun newCollegeFixture(
  ipedsUnitId: Int,
  name: String = "Test College",
): NewCollege =
  NewCollege(
    housingAndFoodOnCampusPerYearUsd = null,
    housingAndFoodOffCampusPerYearUsd = null,
    booksAndSuppliesPerYearUsd = null,
    otherExpensesOnCampusPerYearUsd = null,
    otherExpensesOffCampusPerYearUsd = null,
    otherExpensesWithFamilyPerYearUsd = null,
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = name,
    city = "Townsville",
    state = "CA",
    region = 8,
    locale = 13,
    latitude = 34.0,
    longitude = -118.0,
    control = 1,
    undergradEnrollmentHeadcount = 5000,
    admissionRateShare = 0.5,
    satAverageEquivalentScore = 1200,
    costOfAttendancePerYearUsd = 40000,
    netPricePerYearUsd = 20000,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = 12000,
    tuitionAndFeesOutOfStatePerYearUsd = 30000,
    completionRate150pct4yrShare = 0.7,
    medianEarnings10yAfterEntryUsd = 55000,
    medianDebtAtCompletionUsd = null,
    pellShare = 0.4,
    website = null,
  )
