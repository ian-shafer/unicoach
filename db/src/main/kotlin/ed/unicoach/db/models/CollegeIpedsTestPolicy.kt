package ed.unicoach.db.models

/**
 * One school's IPEDS admission-test policy as it is STORED (RFC 187): the raw
 * `college_ipeds.test_policy` code (`ADMCON7`) and the survey year of the row
 * that reported it.
 *
 * [code] is the RAW published code -- 1, 3 or 5 -- and deliberately not
 * the loaded `ed.unicoach.college.AdmissionTestPolicy` member: `:db` does not
 * and must not depend on `:college`, where the codebook vocabulary lives, so
 * the translation from code to word happens at the service boundary like every
 * other raw IPEDS code. A value outside the published set cannot normally reach
 * here (the column carries its own `CHECK`), and the boundary treats one that
 * does exactly like a missing row.
 *
 * [surveyYear] rides WITH the code rather than being looked up beside it,
 * because no policy claim may be stated without the year it was collected in
 * (RFC 187 D17): a caller holding this row cannot accidentally cite a year
 * belonging to some other row, or to a constant.
 *
 * The row is only ever built for a school whose `test_policy` is non-NULL. A
 * `college_ipeds` row that does not report `ADMCON7` is a NULL value in the
 * read's map, not a row with a sentinel code in it.
 */
data class CollegeTestPolicyRow(
  val code: Int,
  val surveyYear: Int,
)
