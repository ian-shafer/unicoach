package ed.unicoach.db.dao

import ed.unicoach.db.models.NewCollege

/**
 * A fully-populated `colleges` row for DAO tests. Suites that only need a
 * college to exist (so a dependent table's FK resolves) call this instead of
 * restating every column; suites asserting on college fields vary only what
 * they assert on.
 */
internal fun newCollegeFixture(
  unitId: Int,
  name: String = "Test College",
): NewCollege =
  NewCollege(
    unitId = unitId,
    opeid = null,
    name = name,
    city = "Townsville",
    state = "CA",
    region = 8,
    locale = 13,
    latitude = 34.0,
    longitude = -118.0,
    control = 1,
    undergradEnrollment = 5000,
    admissionRate = 0.5,
    satAvg = 1200,
    costAttendance = 40000,
    netPrice = 20000,
    netPriceQ1 = null,
    netPriceQ2 = null,
    netPriceQ3 = null,
    netPriceQ4 = null,
    netPriceQ5 = null,
    tuitionInState = 12000,
    tuitionOutState = 30000,
    graduationRate = 0.7,
    medianEarnings = 55000,
    medianDebt = null,
    pctPell = 0.4,
    website = null,
  )
