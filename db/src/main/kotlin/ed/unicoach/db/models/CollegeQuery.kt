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
  /**
   * A maximum price on the ACTIVE ruler ([priceRuler]), in dollars per year.
   *
   * ONE field, not one per ruler, because one query is on one ruler: the two
   * wire fields (`max_in_state_net_price_per_year_usd` and
   * `max_published_price_on_campus_per_year_usd`) are resolved to this single
   * bound at the vocabulary boundary, which REFUSES the inactive ruler's field
   * by name rather than silently ignoring it. A second field here would be a
   * second chance to build the query that reads two price measures.
   */
  val maxPricePerYearUsd: Int? = null,
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
   * WHICH price this query ranks, filters and sorts on (RFC 169 D1) — the
   * RESOLVED ruler, carried, not a residency code the readers re-resolve.
   *
   * It used to be the raw `familyResidencyState`, with [PriceRuler] derived by a
   * getter. That getter re-allocated a `Published` ruler and re-ran its USPS
   * regex at every one of the six read sites a single search touches, and it put
   * the resolution rule in a place a caller could not see. The vocabulary
   * boundary already holds the answer, so it hands it over.
   *
   * The residency behind it is NOT [states], which means where the COLLEGE is,
   * and it never arrives from the model: it is read from `money_profiles` by the
   * student-scoped chat tool, so it cannot contradict what the family said.
   *
   * The default is the NET ruler because that is the honest reading of "no
   * residency on file" — today's behaviour column for column, which is what
   * makes the whole feature configuration-free to roll back.
   */
  val priceRuler: PriceRuler = PriceRuler.NetPrice,
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
  init {
    // A sort word belonging to the INACTIVE ruler is refused by name at the
    // vocabulary boundary, with a sentence saying which ruler this query is on.
    // This is the backstop for any caller that bypasses it: an inconsistent
    // query is not a state this type can be in, so `orderBy` has nothing left
    // to re-check and cannot quietly fall back to the other measure.
    require(sortBy != SortBy.PUBLISHED_PRICE_ON_CAMPUS_ASC || priceRuler is PriceRuler.Published) {
      "sorting on the published on-campus price needs the family's state of residency"
    }
    require(sortBy != SortBy.IN_STATE_NET_PRICE_ASC || priceRuler is PriceRuler.NetPrice) {
      "this query is on the published-price ruler, so it cannot sort on the in-state net price"
    }
  }

  /** Result orderings for [ed.unicoach.db.dao.CollegesDao.search] (RFC 139). */
  enum class SortBy {
    /** Today's default: biggest undergraduate enrollment first. */
    ENROLLMENT_DESC,

    /** Most selective first (lowest admission rate). */
    ADMISSION_RATE_SHARE_ASC,

    /**
     * Cheapest first on the NET-PRICE ruler: the blended after-federal-aid
     * average, which at a public school is the in-state figure — hence the word.
     * Only offerable while [priceRuler] is [PriceRuler.NetPrice].
     */
    IN_STATE_NET_PRICE_ASC,

    /**
     * Cheapest first on the PUBLISHED ruler: each row's residency-correct
     * published on-campus total, with no aid in it. Only offerable while
     * [priceRuler] is [PriceRuler.Published].
     */
    PUBLISHED_PRICE_ON_CAMPUS_ASC,

    /** Best completion first (highest 6-year graduation rate). */
    COMPLETION_RATE_150PCT_4YR_SHARE_DESC,

    /** Alphabetical by institution name. */
    NAME_ASC,
  }
}
