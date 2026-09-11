package ed.unicoach.coaching.admissions

/**
 * The wire vocabulary of `college_admissions_profile` (RFC 148): the section
 * keys the tool renders, which are also the names `data_availability` uses to
 * say a school does not report that section. One home for both, on the
 * [ed.unicoach.coaching.costs.CostField] precedent, so `data_availability` can
 * never name a section the tool does not render.
 *
 * Declaration order IS the wire order (RFC 145): the coach reaches for money
 * first, then what the school weighs, then when it is all due.
 *
 * [TEST_POLICY] (RFC 187) sits inside the "what the school weighs" half, right
 * beside the C7 grid: "does this school want a score" is the same school's own
 * subject as the grid's `test_scores` row, from a different publisher. Its wire
 * name is the one `search_colleges` already uses for this axis, so a student
 * who filtered on it and then asked about one school reads the same word twice.
 * It is TYPED here rather than derived from `CollegeQueryVocabulary`, unlike
 * [MERIT_AID] below, because the two words are not one contract: the search
 * filter's word may not be renamed without a search migration, while this is a
 * key in this tool's own payload, and importing a search-module vocabulary to
 * spell a four-section enum would tie a wire rename in either module to the
 * other. They are held together instead by an assertion in
 * `CollegeAdmissionsChatToolTest`, so the two spellings cannot drift in
 * silence.
 *
 * [MERIT_AID] reads its key from [MeritAidWire], the section's own home, because
 * `college_cost_profile` renders that same sub-object under that same key: two
 * independently typed literals could drift and only one of the two tools would
 * move.
 */
enum class AdmissionsField(
  val wireName: String,
) {
  MERIT_AID(MeritAidWire.KEY),
  ADMISSION_FACTORS("admission_factors"),
  TEST_POLICY("test_policy"),
  DEADLINES("deadlines"),
}
