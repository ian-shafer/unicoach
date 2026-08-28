package ed.unicoach.db.models

/**
 * One page of structured college search results (RFC 139): the returned
 * [matches] (capped by the query's clamped `limit`) plus [totalMatches], the
 * unclamped count of every college satisfying the same filters — so a consumer
 * can honestly say "312 match; showing 25" instead of passing the slice size
 * off as the population.
 */
data class CollegeSearchPage(
  val matches: List<CollegeMatch>,
  val totalMatches: Int,
)
