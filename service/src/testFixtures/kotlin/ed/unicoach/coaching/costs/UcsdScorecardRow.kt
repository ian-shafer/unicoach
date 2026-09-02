package ed.unicoach.coaching.costs

/**
 * UC San Diego (IPEDS 110680) as the College Scorecard actually publishes it --
 * the ONE row RFC 157 argues from, typed once for the whole repo.
 *
 * A REAL row rather than a round-numbered one, because the whole defect is
 * arithmetic: `COSTT4_A` = 38,701 sits inside the in-state span and BELOW the
 * out-of-state minimum of 59,923, and a weighted average cannot fall below its
 * own smallest input. Fixture numbers chosen for readability would have proved
 * nothing about it.
 *
 * In a test FIXTURE source set, and not in either test that needs it, because
 * both need it: the service test seeds the row into Postgres and the report-page
 * test builds the same row as a cost answer. Typed twice, a corrected figure
 * would have to be found by grep, and a reader could not tell whether a bare
 * `16758` was the same fact as the named ones beside it.
 *
 * Every constant is named for the Scorecard field it comes from, so the row and
 * the source stay legible against each other.
 */
object UcsdScorecardRow {
  /** UC San Diego's own state, so "in-state" in these cases is a fact rather than a coincidence of literals. */
  const val STATE = "CA"

  /** `COSTT4_A`: below the out-of-state minimum of 59,923, which is what makes the in-state basis provable. */
  const val COSTT4_A = 38701

  /** `NPT4_PUB`: the overall average net price, in-state only. */
  const val NPT4_PUB = 12470

  /** `NPT41_PUB`..`NPT44_PUB`: the four lower income bands, in-state only. */
  const val NPT41_PUB = 7525
  const val NPT42_PUB = 8155
  const val NPT43_PUB = 9942
  const val NPT44_PUB = 15199

  /** `NPT45_PUB`: the top band, and the figure a WA family read as theirs against a real ~77,102. */
  const val NPT45_PUB = 28785

  /** `TUITIONFEE_IN` / `TUITIONFEE_OUT`: the published pair the family's residency selects between. */
  const val TUITIONFEE_IN = 16758
  const val TUITIONFEE_OUT = 50958

  /** The residency-free component allowances: the same figures whichever state the family lives in. */
  const val ROOMBOARD_ON_CAMPUS = 18970
  const val ROOMBOARD_OFF_CAMPUS = 18404
  const val BOOKSUPPLY = 1286
  const val OTHEREXPENSE_ON_CAMPUS = 5888
  const val OTHEREXPENSE_OFF_CAMPUS = 7011
  const val OTHEREXPENSE_WITH_FAMILY = 7679
}
