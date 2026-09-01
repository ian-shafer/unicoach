package ed.unicoach.db.models

/**
 * One similarity axis (RFC 153 D65): a named way two colleges can be alike,
 * chosen per call rather than baked into a stored method. The word is what the
 * tool schema offers and what the response reports, so a caller and a reader
 * name the axis identically.
 *
 * Two kinds live here side by side. [SIZE], [SELECTIVITY] and [PRICE] read the
 * percentile columns RFC 150 landed, which are relative by construction and so
 * commensurable without a scaling constant anybody would have to defend.
 * [SETTING] and [SUBJECTS] are categorical: an equality test on the locale
 * slug, and a Jaccard distance over the subject slugs.
 */
enum class SimilarityAxis(
  val word: String,
) {
  /** Undergraduate enrollment, as a percentile of the default universe. */
  SIZE("size"),

  /**
   * Selectivity, as the mean of the INVERTED admission-rate percentile and the
   * SAT-average percentile over whichever of the two the college reports. The
   * inversion makes both inputs point the same way (higher = harder to get
   * into) before they are averaged; without it a school with a high admit rate
   * and a high SAT would average to a meaningless middle.
   */
  SELECTIVITY("selectivity"),

  /** Average annual net price, as a percentile of the default universe. */
  PRICE("price"),

  /** The locale slug: 0 when the two colleges share it, 1 when they do not. */
  SETTING("setting"),

  /** Jaccard distance over the subject slugs: `1 - |A n B| / |A u B|`. */
  SUBJECTS("subjects"),
  ;

  companion object {
    /**
     * The bare "schools like X" ask (RFC 153 D65, brief 0004 D20): character,
     * not budget. PRICE is deliberately absent — silently ranking on it would
     * turn a question about what a school IS into a budget question the student
     * did not ask.
     */
    val DEFAULTS: List<SimilarityAxis> = listOf(SIZE, SELECTIVITY, SETTING)

    /** The default weight of an axis the caller did not weight: every axis equal. */
    const val DEFAULT_WEIGHT: Double = 1.0

    /** Weights are RATIOS, clamped so no single axis can be zeroed or made to swamp the rest. */
    const val MIN_WEIGHT: Double = 0.1
    const val MAX_WEIGHT: Double = 10.0
  }
}

/**
 * The anchor of a similarity query, read from `college_search_index` (RFC 153
 * D63/D64): the row "schools like Bowdoin" is measured against.
 *
 * Every axis value the distance expression binds is carried here, so the SQL
 * takes the anchor as PARAMETERS and never as interpolated text. The two
 * anchor-relative constraints (D68) read [netPricePerYearUsd] and
 * [admissionRateShare], which is why raw figures ride beside the percentiles.
 *
 * [inDefaultUniverse] is the fact D64 refuses on: percentiles are only computed
 * inside the default universe, so a closed or two-year anchor carries NULL on
 * every one of them and a distance query against it would rank the whole
 * database by nothing.
 */
data class SimilarityAnchor(
  val id: CollegeId,
  val name: String,
  val state: String,
  /**
   * The anchor's control, or null when its stored label names no category
   * [InstitutionControl] defines — the state D65's default same-control
   * constraint cannot be built from. Resolved ONCE, where the row is read, so
   * no consumer re-parses a raw string and infers the unknown state two
   * functions away.
   */
  val control: InstitutionControl?,
  /** The label exactly as `college_search_index.control` stores it: what a response prints and a refusal quotes. */
  val controlLabel: String,
  val locale: String?,
  val subjectSlugs: List<String>?,
  val netPricePerYearUsd: Int?,
  val admissionRateShare: Double?,
  val sizePercentile: Double?,
  /**
   * The anchor's SELECTIVITY position, computed by the ONE expression that also
   * positions every candidate (`CollegesDao.SELECTIVITY_POSITION`) and READ
   * here, never recomputed: the two sides of a distance are two colleges, never
   * two formulas. Null when the anchor reports neither input, which DROPS the
   * axis for the whole query (D67) rather than substituting a median nobody
   * measured.
   */
  val selectivityPercentile: Double?,
  val pricePercentile: Double?,
  val inDefaultUniverse: Boolean,
) {
  /**
   * The anchor ON [axis], or null when it cannot be measured there — which
   * DROPS the axis (D67).
   *
   * This is the ONE place the drop rule is stated: the value and the axis are
   * resolved together, so a query can only name an axis the anchor actually
   * carries a figure for and the SQL has nothing left to re-check.
   */
  fun anchoredOn(axis: SimilarityAxis): AnchoredAxis? =
    when (axis) {
      SimilarityAxis.SIZE -> sizePercentile?.let(AnchoredAxis::Size)

      SimilarityAxis.SELECTIVITY -> selectivityPercentile?.let(AnchoredAxis::Selectivity)

      SimilarityAxis.PRICE -> pricePercentile?.let(AnchoredAxis::Price)

      SimilarityAxis.SETTING -> locale?.let(AnchoredAxis::Setting)

      // `{}` is NOT the NULL state: schema 0064 stores NULL for "no program
      // census at all" and `{}` for "the programs are known and none of them is
      // a taxonomy subject". Neither can be Jaccard-compared against, so both
      // drop the axis -- and the CANDIDATE side rejects `{}` by the same rule,
      // so one college is never unjudgeable as an anchor and judged as a peer.
      SimilarityAxis.SUBJECTS -> subjectSlugs?.takeIf { it.isNotEmpty() }?.let(AnchoredAxis::Subjects)
    }
}

/**
 * One axis the ANCHOR can actually be measured on (RFC 153 D67), carrying the
 * anchor's own value on it.
 *
 * An axis the anchor cannot express has no member here, so a dropped axis
 * cannot reach the distance expression at all: the DAO reads the figure off the
 * axis it was handed rather than trusting a drop rule computed in another
 * module and re-asserting it with `checkNotNull`.
 */
sealed interface AnchoredAxis {
  /** The axis this value measures — the word a caller wrote and a response reports. */
  val axis: SimilarityAxis

  data class Size(
    val percentile: Double,
  ) : AnchoredAxis {
    override val axis: SimilarityAxis get() = SimilarityAxis.SIZE
  }

  data class Selectivity(
    val percentile: Double,
  ) : AnchoredAxis {
    override val axis: SimilarityAxis get() = SimilarityAxis.SELECTIVITY
  }

  data class Price(
    val percentile: Double,
  ) : AnchoredAxis {
    override val axis: SimilarityAxis get() = SimilarityAxis.PRICE
  }

  data class Setting(
    val locale: String,
  ) : AnchoredAxis {
    override val axis: SimilarityAxis get() = SimilarityAxis.SETTING
  }

  data class Subjects(
    val slugs: List<String>,
  ) : AnchoredAxis {
    override val axis: SimilarityAxis get() = SimilarityAxis.SUBJECTS
  }
}

/**
 * One "schools like X" query (RFC 153): the resolved [anchor], the axes that
 * survived D67's drops with their clamped weights, the ordinary
 * [CollegeQueryVocabulary]-shaped hard constraints, and D68's two
 * anchor-relative FIGURES.
 *
 * It is a sibling of [CollegeQuery], not a replacement: [filters] IS a
 * [CollegeQuery], so this slice adds no second filter vocabulary, no second
 * predicate builder and no second page size — the distance is the only new SQL.
 *
 * The anchor-relative constraints carry the anchor's own NUMBER rather than a
 * flag beside it: a constraint that exists has its figure and one that does not
 * is null, so "constrained, but with nothing to compare against" is not a state
 * this type can be in and neither the model nor the DAO has to guard for it.
 */
data class SimilarityQuery(
  val anchor: SimilarityAnchor,
  val axes: Map<AnchoredAxis, Double>,
  val filters: CollegeQuery,
  /** D68: `net_price_per_year_usd < <this>`, strictly, with no margin; null when unasked. */
  val cheaperThanUsd: Int? = null,
  /** D68: `admission_rate_share > <this>`, strictly, with no margin; null when unasked. */
  val easierToAdmitThanShare: Double? = null,
) {
  init {
    require(axes.isNotEmpty()) { "a similarity query must rank on at least one axis" }
  }

  /** The result cap, owned by [filters] so one page size cannot be stated twice. */
  val limit: Int get() = filters.limit
}
