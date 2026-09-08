package ed.unicoach.db.models

/**
 * WHICH PRICE a search is ranked, filtered and sorted on (RFC 169 D1) — the one
 * value a query resolves ONCE, before any SQL is built, and the only thing
 * `CollegesDao` asks about a price after that.
 *
 * `college_search_index` carries two price measures, and they are not
 * interchangeable. The net-price measure is the Scorecard blend: at a public
 * school it is the in-state, after-federal-aid figure, so it ranks a family from
 * another state on a price it will never pay (Kendall tau 0.561 against the
 * out-of-state published ladder — about 22% of all school pairs order the
 * opposite way). The published measure is the school's published on-campus
 * total at the family's OWN tuition tier, with no aid in it at all.
 *
 * **One query is on exactly one ruler.** Mixing them is not a cosmetic
 * inconsistency: a private's position moves when the publics around it move, so
 * two ladders in one distance term, one filter or one sentence order schools by
 * an arithmetic nobody could defend. That rule is enforceable only because it is
 * a VALUE — every read site (`createSearchPlan`, `orderBy`, `createFilters`,
 * `AnchoredAxis.Price`, the anchor read) takes its column names from here, so a
 * query that reads two price measures cannot be constructed.
 *
 * Under [Published] only the RATE TIER varies per row — never the measure. Both
 * tier columns are positions on ONE ladder (the out-of-state totals of the
 * default universe), which is what makes "the rule is per ROW, not per result
 * set" implementable without swapping measures between schools.
 *
 * There is deliberately no out-of-state NET price and there never can be:
 * subtracting an in-state average grant from an out-of-state total is exactly
 * the arithmetic RFC 149 forbids. So a result on the published ruler SAYS that
 * aid is not in it, in one sentence, rather than leaving a reader to assume the
 * ranking is a price a family would be billed after aid.
 */
sealed interface PriceRuler {
  /**
   * The two tuition tiers a published ranking applies, and the only two
   * [resultKey] will name.
   *
   * A narrower type than [ResidencyBasis] on purpose: three of that enum's five
   * members are tiers this ruler never ranks on, and `in_district` in particular
   * is a cost answer and never a search ranking (brief 0006 D19).
   */
  enum class PublishedTier(
    val basis: ResidencyBasis,
  ) {
    IN_STATE(ResidencyBasis.IN_STATE),
    OUT_OF_STATE(ResidencyBasis.OUT_OF_STATE),
  }

  /**
   * The FOUR wire names one measure has, all owned here so a single price cannot
   * acquire four independent spellings — and they are four rather than one
   * because they answer four different questions:
   *
   * - [sortWord] is what a caller ASKS for (`sort_by`), and the inactive one is
   *   refused by name, so both words must exist as words.
   * - [filterField] is the bound a caller STATES, in the tool schema's own
   *   camel-case field convention.
   * - [excludedUnknownKey] is per-AXIS: a page mixes rows on both tuition tiers,
   *   and two keys would ask a reader to add two numbers about one question.
   * - the per-row result key ([NET_PRICE_RESULT_KEY], [resultKey]) is
   *   per-TIER, because brief 0006 D14(b) puts the basis in the metric NAME and
   *   the tier applied to THAT row is the basis.
   *
   * Collapsing them would either lose the refusal (one sort word cannot be
   * refused by name) or lose the basis (one result key cannot say which tier
   * produced the number).
   */
  val sortWord: String

  /** The tool input field this ruler's maximum-price bound arrives on. */
  val filterField: String

  /** The `excluded_unknown` key a bound on this ruler reports its unjudgeable rows under. */
  val excludedUnknownKey: String

  /** The `similar_colleges` axis word — unchanged between rulers, because the AXIS is the same question. */
  val axisWord: String get() = SimilarityAxis.PRICE.word

  /** Today's ruler: the blended net price, column for column what search ranked on before RFC 169. */
  data object NetPrice : PriceRuler {
    override val sortWord: String get() = NET_PRICE_SORT_WORD

    override val filterField: String get() = NET_PRICE_FILTER_FIELD

    /**
     * NOT the column name. `net_price_per_year_usd` is the SCHEMA name and stays
     * exactly as it is (the column-shape end state belongs to a later slice);
     * the wire says `in_state_` because that is what the number IS at a public
     * school, and a key that does not say so is the whole defect RFC 169 fixes.
     */
    override val excludedUnknownKey: String get() = NET_PRICE_RESULT_KEY
  }

  /**
   * The family's residency-correct published on-campus total, for a family whose
   * state is on file.
   *
   * [familyResidencyState] is a USPS two-letter code and is checked to be one
   * here. That check is what lets the code be written INTO the SQL rather than
   * bound: the residency-correct expression is needed in five places — a filter
   * clause, an `ORDER BY` key, an `excluded_unknown` condition, a similarity
   * distance term and the bind-free "shares an axis with the anchor" clause —
   * and the last two are read by statements that deliberately bind nothing at
   * all. One expression used in five places beats five expressions that must be
   * kept in step, and a value constrained to `^[A-Z]{2}$` at construction is a
   * closed shape rather than caller text. It is the [DefaultUniverse] and
   * `CONTROL_CASE` treatment of a closed vocabulary, not an unbound filter value.
   */
  data class Published(
    val familyResidencyState: String,
  ) : PriceRuler {
    init {
      // MEMBERSHIP, not shape. `^[A-Z]{2}$` admits 676 strings of which 59 are
      // real, so a shape check mostly passes what it should refuse — and this
      // value is WRITTEN INTO SQL, which makes "it looked like a state" the
      // wrong bar. Checked against the one list the served vocabulary and the
      // profile write boundary also use.
      require(familyResidencyState in UsStateCodes.ALL) {
        "a family residency state must be a published USPS code, not [$familyResidencyState]"
      }
    }

    override val sortWord: String get() = PUBLISHED_PRICE_SORT_WORD

    override val filterField: String get() = PUBLISHED_PRICE_FILTER_FIELD

    /**
     * ONE key, not the two column names, because a count is about the AXIS: a
     * page mixes rows on both tiers, and two keys would ask a reader to add two
     * numbers about the same question.
     */
    override val excludedUnknownKey: String get() = PUBLISHED_PRICE_EXCLUDED_UNKNOWN_KEY

    /**
     * WHICH tuition tier this ruler applies to one row: the out-of-state tier at
     * a PUBLIC school outside the family's state, the in-state tier everywhere
     * else. A private publishes ONE price, so both of its columns hold the same
     * number and the answer is [ResidencyBasis.IN_STATE] by convention rather
     * than by a second rule.
     *
     * It is the Kotlin twin of the SQL `CASE`, and it exists so the result
     * key a row is reported under names the tier that was actually applied to
     * THAT row.
     */
    fun tierFor(
      controlLabel: String,
      collegeState: String,
    ): PublishedTier =
      if (controlLabel == InstitutionControl.PUBLIC.label && collegeState != familyResidencyState) {
        PublishedTier.OUT_OF_STATE
      } else {
        PublishedTier.IN_STATE
      }
  }

  companion object {
    /**
     * The ruler ONE query is on: the published price when the family's state is
     * on file, today's net price when it is not.
     *
     * An unanswered residency, a declined one and no money profile at all all
     * arrive here as null and all resolve to [NetPrice] — the same behaviour
     * search has always had, column for column, which is what makes the residency
     * read configuration-free to roll back.
     */
    fun of(familyResidencyState: String?): PriceRuler = familyResidencyState?.let(PriceRuler::Published) ?: NetPrice

    /**
     * The result key one row's price is reported under on the published ruler.
     *
     * [PublishedTier], not [ResidencyBasis]: the basis enum has five members and
     * three of them — `in_district`, `not_applicable` and any later addition —
     * are tiers this ruler never applies. Taking the wider type let a caller ask
     * for `published_price_in_district_on_campus_per_year_usd`, a wire key no
     * payload has ever carried, and get a plausible string back.
     */
    fun resultKey(tier: PublishedTier): String = "published_price_${tier.basis.value}_on_campus_per_year_usd"

    /** The result key the net ruler reports every row under, and the key its `excluded_unknown` arm uses. */
    const val NET_PRICE_RESULT_KEY: String = "in_state_net_price_per_year_usd"

    /**
     * The two rulers' WIRE WORDS, as constants rather than only as members, so a
     * caller can name the ruler it is NOT on — to refuse it, to describe it, or
     * to list both in a schema — without fabricating a `Published` instance and
     * a residency state to go with it.
     */
    const val NET_PRICE_SORT_WORD: String = "in_state_net_price"

    const val PUBLISHED_PRICE_SORT_WORD: String = "published_price_on_campus"

    const val NET_PRICE_FILTER_FIELD: String = "maxInStateNetPricePerYearUsd"

    const val PUBLISHED_PRICE_FILTER_FIELD: String = "maxPublishedPriceOnCampusPerYearUsd"

    const val PUBLISHED_PRICE_EXCLUDED_UNKNOWN_KEY: String = "published_price_on_campus_per_year_usd"
  }
}
