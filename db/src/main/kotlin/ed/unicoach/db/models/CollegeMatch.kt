package ed.unicoach.db.models

/**
 * A single result row from [ed.unicoach.db.dao.CollegesDao.search] (RFC 67): the
 * curated college fields plus [programTitles] — the `cip_title`s of programs
 * matched by the query's `cipPrefix` (empty when no program filter was applied).
 *
 * [graduationRate], [medianEarnings], and [pctPell] are returned context for the
 * coach to reason over in prose; only [graduationRate] is also a filter
 * (`minGraduationRate`). Earnings and Pell share are surfaced, never thresholded
 * on, because filtering on them is value-laden.
 */
data class CollegeMatch(
  val id: CollegeId,
  val unitId: Int,
  val name: String,
  val city: String,
  val state: String,
  val control: Int,
  val locale: Int?,
  val undergradEnrollment: Int?,
  val admissionRate: Double?,
  val netPrice: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  val pctPell: Double?,
  val website: String?,
  val programTitles: List<String>,
)
