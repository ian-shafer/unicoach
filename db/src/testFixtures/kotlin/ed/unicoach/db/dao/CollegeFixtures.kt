package ed.unicoach.db.dao

import ed.unicoach.db.models.NewCollege

/**
 * RFC 242's four completion figures as every suite's fixture row carries them,
 * stated ONCE.
 *
 * All four DISTINCT, value by value: a fixture that gave two of them one
 * number would let a mapper reading a neighbour's column pass every assertion.
 *
 * [FIXTURE_COMPLETION_8YR_PELL_SHARE] is deliberately ABOVE
 * [FIXTURE_COMPLETION_8YR_NON_PELL_SHARE] -- measured over the pinned
 * snapshot, the Pell arm is the LOWER of the two at only 65.8% of
 * institutions, so a fixture that always ordered them the common way round
 * would let an ordering assumption look true. That claim is not left to this
 * paragraph: `CollegeFixtureSharesTest` asserts the ordering, so a fixture
 * edited the other way fails by name.
 *
 * One home because these four were typed out at seven declaration sites across
 * four modules, with the ordering carried only in prose -- and a claim
 * re-typed per suite is a claim one suite can quietly stop making.
 */
const val FIXTURE_COMPLETION_8YR_SHARE: Double = 0.61

const val FIXTURE_COMPLETION_8YR_PELL_SHARE: Double = 0.58

const val FIXTURE_COMPLETION_8YR_NON_PELL_SHARE: Double = 0.55

const val FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE: Double = 0.12

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
    satReading25thPercentileScore = null,
    satReading75thPercentileScore = null,
    satMath25thPercentileScore = null,
    satMath75thPercentileScore = null,
    actComposite25thPercentileScore = null,
    actComposite75thPercentileScore = null,
    completionRate150pct4yrShare = 0.7,
    // RFC 242's four, from the ONE home of these values -- including the
    // ordering claim they carry, which is asserted rather than described.
    completionRate8yrShare = FIXTURE_COMPLETION_8YR_SHARE,
    completionRate8yrPellShare = FIXTURE_COMPLETION_8YR_PELL_SHARE,
    completionRate8yrNonPellShare = FIXTURE_COMPLETION_8YR_NON_PELL_SHARE,
    transferOutRate4yrShare = FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE,
    website = null,
  )
