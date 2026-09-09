package ed.unicoach.db.dao

import ed.unicoach.db.models.NewCollege

/**
 * A fully-populated `colleges` row for tests. Suites that only need a college
 * to exist (so a dependent table's FK resolves) call this instead of restating
 * every column; suites asserting on college fields vary only what they assert
 * on. A test fixture so consumers in other modules (`:service`) share the one
 * literal.
 *
 * It carries no money since RFC 176 — `colleges` has none. A suite that needs
 * this college to HAVE money seeds it beside the row with
 * [CanonicalCohortFixture.seedMoney], which writes
 * `cohort_money_stats` at the canonical addresses every money reader reads.
 */
fun newCollegeFixture(
  ipedsUnitId: Int,
  name: String = "Test College",
): NewCollege =
  NewCollege(
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
    completionRate150pct4yrShare = 0.7,
    website = null,
  )
