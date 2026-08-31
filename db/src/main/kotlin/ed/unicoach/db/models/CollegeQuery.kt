package ed.unicoach.db.models

/**
 * Typed filter for [ed.unicoach.db.dao.CollegesDao.search] (RFC 150). Every
 * field except [limit] and [includeAdministrativeUnits] is nullable; an absent
 * field is an unconstrained axis. List fields are OR-sets (any member matches).
 * [limit] is mandatory and clamped to `1..25` by the service boundary before
 * reaching the DAO.
 *
 * **Every coded value here is OUR WORD, not a publisher's number** (RFC 150
 * D61). `college_search_index` stores the slug, so [region], [locales],
 * [testPolicy], [religiousAffiliation], [carnegieClass], [carnegieSize] and
 * [athleticAssociation] are bound straight into SQL as the word the model
 * said. [control] carries [InstitutionControl] itself — the closed authority
 * for that axis, with no codebook table behind it — so a caller cannot write a
 * word the vocabulary never approved; its label is produced at the bind, which
 * is the only place the SQL word is needed. There is no code lookup at query
 * time and nothing to render back into words on the way out.
 *
 * The DEFAULT UNIVERSE is a default, not a wall (RFC 150 D56). [isActive]
 * defaults to `true`, [isFourYear] defaults to null meaning `IS NOT FALSE` —
 * an unknown level is INCLUDED, an inactive school is not — and
 * [includeAdministrativeUnits] defaults to false, which is what keeps a
 * university system's central office out of a search for its campuses.
 * [isActive] and [isFourYear] are overridable BY THE MODEL, through the
 * `is_active` and `is_four_year` words the tool schema offers;
 * [includeAdministrativeUnits] is not — see its own doc.
 *
 * [sortBy] (RFC 139) selects the result ordering; it never filters — rows NULL
 * on the sort key sink to the end (`NULLS LAST`), they do not vanish (brief
 * 0004 D11). Every ordering ends with the `ipeds_unit_id ASC` tiebreak, so the
 * order is total and deterministic.
 *
 * `credentialLevel` is GONE (RFC 150 D53). The index derives its programs from
 * `college_programs_census`, which the ingest filters to bachelor's first
 * majors, so the column is constant there and the filter would have been a
 * tautology for "bachelors" and a falsehood for everything else. It returns
 * when the census carries more than one award level.
 */
data class CollegeQuery(
  val cipPrefix: String? = null,
  val subject: String? = null,
  val states: List<String>? = null,
  val region: String? = null,
  val locales: List<String>? = null,
  val control: List<InstitutionControl>? = null,
  val minUndergradEnrollmentHeadcount: Int? = null,
  val maxUndergradEnrollmentHeadcount: Int? = null,
  val minAdmissionRateShare: Double? = null,
  val maxAdmissionRateShare: Double? = null,
  val maxNetPricePerYearUsd: Int? = null,
  val minCompletionRate150pct4yrShare: Double? = null,
  val testPolicy: String? = null,
  val religiousAffiliation: String? = null,
  val carnegieClass: String? = null,
  val carnegieSize: String? = null,
  val athleticAssociation: String? = null,
  val hasRotc: Boolean? = null,
  val hasStudyAbroad: Boolean? = null,
  val hasHousing: Boolean? = null,
  val isActive: Boolean? = true,
  val isFourYear: Boolean? = null,
  /**
   * TEST-ONLY, despite reading as a per-call option: `CollegeQueryVocabulary`
   * offers the model no word for it, so no production caller can pass anything
   * but the `false` default and the sector clause of the default universe is,
   * in practice, unconditional.
   *
   * It exists so a test can prove that clause is what excludes a system central
   * office — the assertion is "flip this and the office appears" — which a
   * hard-coded predicate could not be tested against without seeding a second
   * database. Wiring it to the vocabulary would be a new student-facing filter,
   * which this slice was not approved to add.
   */
  val includeAdministrativeUnits: Boolean = false,
  val sortBy: SortBy = SortBy.ENROLLMENT_DESC,
  val limit: Int,
) {
  /** Result orderings for [ed.unicoach.db.dao.CollegesDao.search] (RFC 139). */
  enum class SortBy {
    /** Today's default: biggest undergraduate enrollment first. */
    ENROLLMENT_DESC,

    /** Most selective first (lowest admission rate). */
    ADMISSION_RATE_SHARE_ASC,

    /** Cheapest first (lowest average annual net price). */
    NET_PRICE_PER_YEAR_USD_ASC,

    /** Best completion first (highest 6-year graduation rate). */
    COMPLETION_RATE_150PCT_4YR_SHARE_DESC,

    /** Alphabetical by institution name. */
    NAME_ASC,
  }
}
