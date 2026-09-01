package ed.unicoach.db.models

/**
 * The outcome of one [ed.unicoach.db.dao.CollegesDao.findSimilar] call — the
 * same three-channel shape [CollegeSearchOutcome] uses, so a domain refusal
 * never arrives as a database failure.
 */
sealed interface CollegeSimilarityOutcome {
  /** The ranked peers, their counts and their vintages. */
  data class Page(
    val page: CollegeSimilarityPage,
  ) : CollegeSimilarityOutcome

  /**
   * `college_search_index` has never been built. A named refusal, never a page
   * of zero: "nothing is similar" out of a full database is the one answer no
   * reader can tell from a real one (RFC 150).
   */
  data object IndexNotBuilt : CollegeSimilarityOutcome

  /**
   * A program word the loaded vocabulary cannot expand, carried verbatim from
   * the shared expansion so the tool renders ONE refusal sentence for both
   * entry points (D69: the vocabulary is reused wholesale, refusals included).
   */
  data class UnresolvableProgramFilter(
    val refusal: CollegeSearchOutcome.UnresolvableProgramFilter,
  ) : CollegeSimilarityOutcome
}

/**
 * One page of peers (RFC 153 D70).
 *
 * [totalCandidates] counts every college the constraints admit that shares at
 * least one axis with the anchor — the honest population behind the capped
 * [matches], exactly as `total_matches` is for search. [excludedUnknown] is the
 * per-axis, per-filter count of colleges that could not be judged, so a coach
 * can say how many schools the silence cost.
 */
data class CollegeSimilarityPage(
  val matches: List<SimilarityMatch>,
  val totalCandidates: Int,
  val excludedUnknown: Map<String, Int>,
  val sourceYears: Map<String, IntRange>,
)

/**
 * One ranked peer: the ordinary [CollegeMatch] payload, its [distance] in
 * `[0, 1]`, and the axes it was actually SCORED on.
 *
 * [axesScored] is D67's honesty in the result row: a college matched on one
 * axis out of three is visibly not making the same claim as one matched on
 * three, and no missing axis was ever scored as a zero.
 */
data class SimilarityMatch(
  val match: CollegeMatch,
  val distance: Double,
  val axesScored: List<SimilarityAxis>,
)

/**
 * The outcome of one [ed.unicoach.db.dao.CollegesDao.findSimilarityAnchor] call
 * (RFC 153 D63): three states, none of them a null.
 *
 * "This database holds no college with that id" and "this database has no
 * search index at all" used to arrive as the same `null` and were answered with
 * the same sentence — the false zero RFC 150 forbids, stated about a college
 * the student did name.
 */
sealed interface SimilarityAnchorOutcome {
  /** The anchor's index row. */
  data class Found(
    val anchor: SimilarityAnchor,
  ) : SimilarityAnchorOutcome

  /** This database holds no index row for that id. */
  data object NoSuchCollege : SimilarityAnchorOutcome

  /** `college_search_index` has never been built: a named refusal, never "no such college". */
  data object IndexNotBuilt : SimilarityAnchorOutcome
}
