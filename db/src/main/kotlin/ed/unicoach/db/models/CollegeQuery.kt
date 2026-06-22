package ed.unicoach.db.models

/**
 * Typed filter for [ed.unicoach.db.dao.CollegesDao.search] (RFC 67). Every field
 * except [limit] is nullable; an absent field is an unconstrained axis. List
 * fields are OR-sets (any member matches). [limit] is mandatory and clamped to
 * `1..25` by the service boundary before reaching the DAO.
 */
data class CollegeQuery(
  val cipPrefix: String? = null,
  val states: List<String>? = null,
  val region: Int? = null,
  val locales: List<Int>? = null,
  val control: List<Int>? = null,
  val minUndergradEnrollment: Int? = null,
  val maxUndergradEnrollment: Int? = null,
  val minAdmissionRate: Double? = null,
  val maxAdmissionRate: Double? = null,
  val maxNetPrice: Int? = null,
  val minGraduationRate: Double? = null,
  val limit: Int,
)
