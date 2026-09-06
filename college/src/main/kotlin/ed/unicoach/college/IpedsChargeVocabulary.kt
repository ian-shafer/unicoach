package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis

/**
 * The ONE IC_AY charge vocabulary (RFC 161): which twelve variable stems
 * unicoach stages, which canonical cell each one fills, and which academic year
 * each year suffix means.
 *
 * [IpedsChargesLoader] used to hand-type the stems and [CanonicalMoneyLoader]
 * the cells, policed by a mid-run `error()` that fired only once a staged row
 * reached the canonical fill — a list that had already been half-edited, on the
 * far side of a committed staging phase. There is one list here instead: the
 * stems are DERIVED from the cell map's own keys, so the two cannot disagree
 * and the sync check has nothing left to catch.
 *
 * The academic-year window is derived from [SURVEY_YEAR] rather than typed out,
 * because IC_AY's suffix is a POSITION in the file's own four-year window and
 * not a year: `CHG2AY3` is the 2023-24 in-state price in `IC2023_AY.csv` and
 * the 2024-25 one in `IC2024_AY.csv`. A run whose `--survey-year` disagrees
 * with [SURVEY_YEAR] is FATAL ([assertSurveyYear]) — the same guard
 * `bin/fetch-ipeds` already applies on the download side — because the
 * alternative is stamping every staged row and every derived price one year
 * stale and exiting green.
 */
object IpedsChargeVocabulary {
  /**
   * The IPEDS survey year this vocabulary is pinned to — the `IC2023_AY.csv`
   * whose suffix window is decoded below. Bumping the pinned file edits THIS
   * constant (and the fixtures); [assertSurveyYear] refuses every other year
   * rather than mis-dating the rows.
   */
  const val SURVEY_YEAR = 2023

  /** IC_AY carries the survey year and the three before it, as suffixes `0`-`3`. */
  const val YEARS_CARRIED = 4

  /**
   * WHERE one IC_AY charge variable stem lands in the canonical price grid:
   * the concept/residency/arrangement address of the cell it fills (RFC 161's
   * mapping table).
   */
  data class PriceCoordinate(
    val concept: PriceConcept,
    val residency: ResidencyBasis,
    val arrangement: FigureArrangement,
  )

  /**
   * The IC_AY mapping table (RFC 161): one charge variable stem to the
   * concept/residency/arrangement cell it fills.
   *
   * Deliberately NOT here, each for a stated reason:
   * - `CHG*AT` (tuition only) is `CHG*AY` minus `CHG*AF` exactly, and a derived
   *   figure is never stored (schema conventions; RFC 158 P6). Storing all
   *   three would let a rounding disagreement contradict itself.
   * - `TUITION1-3`, `FEE1-3`, `HRCHG1-3` are AVERAGE per-student charges, a
   *   different concept from a published price; they belong to a cohort layer.
   * - `CHG*TGTD`/`CHG*FGTD` (guaranteed-increase percents) are a policy fact,
   *   not a price, and have no X-flag partner at all.
   *
   * Note `CHG7AY`/`CHG8AY` are off campus **NOT with family**. The with-family
   * arrangement has exactly ONE published variable, `CHG9AY` (other expenses):
   * IPEDS assumes zero food and housing for a student living at home. No source
   * publishes a with-family food-and-housing figure, so none is loaded and none
   * is fabricated.
   */
  val CELLS: Map<String, PriceCoordinate> =
    linkedMapOf(
      "CHG1AY" to PriceCoordinate(PriceConcept.TUITION_AND_FEES, ResidencyBasis.IN_DISTRICT, FigureArrangement.NOT_APPLICABLE),
      "CHG2AY" to PriceCoordinate(PriceConcept.TUITION_AND_FEES, ResidencyBasis.IN_STATE, FigureArrangement.NOT_APPLICABLE),
      "CHG3AY" to PriceCoordinate(PriceConcept.TUITION_AND_FEES, ResidencyBasis.OUT_OF_STATE, FigureArrangement.NOT_APPLICABLE),
      "CHG1AF" to PriceCoordinate(PriceConcept.FEES_ONLY, ResidencyBasis.IN_DISTRICT, FigureArrangement.NOT_APPLICABLE),
      "CHG2AF" to PriceCoordinate(PriceConcept.FEES_ONLY, ResidencyBasis.IN_STATE, FigureArrangement.NOT_APPLICABLE),
      "CHG3AF" to PriceCoordinate(PriceConcept.FEES_ONLY, ResidencyBasis.OUT_OF_STATE, FigureArrangement.NOT_APPLICABLE),
      "CHG4AY" to PriceCoordinate(PriceConcept.BOOKS_AND_SUPPLIES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.NOT_APPLICABLE),
      "CHG5AY" to PriceCoordinate(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
      "CHG6AY" to PriceCoordinate(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
      "CHG7AY" to PriceCoordinate(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS),
      "CHG8AY" to PriceCoordinate(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS),
      "CHG9AY" to PriceCoordinate(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.WITH_FAMILY),
    )

  /** The stems the loader stages, DERIVED from [CELLS] — never a second list to keep in step. */
  val STEMS: List<String> = CELLS.keys.toList()

  /**
   * The canonical price CELLS this fill writes, DERIVED from [CELLS] — the
   * write side of the address grid, exported for the one consumer that must
   * agree with it.
   *
   * `:service` reads the same grid from the other end
   * (`CostField.figureAddress`), and the two lists cannot be derived from each
   * other: this one is keyed by a publisher's variable stem, that one by this
   * repo's wire field. So the agreement is ENFORCED instead — the object is
   * public and this set exists for `CanonicalAddressContractTest`, which fails
   * the moment either side is edited alone. A read address no fill writes is a
   * figure we hold and tell a family we do not; a fill that moves serves a
   * different cell's number under the same label. Both shipped once already
   * (RFC 166 tier-0 blocker 1) with every test green.
   */
  val PRICE_CELLS: Set<PriceCoordinate> = CELLS.values.toSet()

  /**
   * IC_AY's year suffix decoder for [SURVEY_YEAR]: suffix `3` is the survey
   * year itself, `0` the three-years-back edge of the window. The suffix is a
   * POSITION, not a year, so the academic year rides on the row (RFC 158 P5) —
   * never in a schema comment.
   */
  val ACADEMIC_YEAR_BY_SUFFIX: Map<String, AcademicYear> =
    (0 until YEARS_CARRIED).associate { position ->
      position.toString() to AcademicYear(SURVEY_YEAR - (YEARS_CARRIED - 1) + position)
    }

  /**
   * The reverse decoder, built from [ACADEMIC_YEAR_BY_SUFFIX] rather than
   * retyped, so `source_variable` names the real published column (`CHG2AY3`)
   * and the two directions can never disagree.
   */
  val SUFFIX_BY_ACADEMIC_YEAR: Map<AcademicYear, String> =
    ACADEMIC_YEAR_BY_SUFFIX.entries.associate { (suffix, year) -> year to suffix }

  /**
   * Refuses a run whose IPEDS survey year is not the one this vocabulary
   * decodes. Without it an `IC2024_AY.csv` handed to a 2023 window would stamp
   * every staged row — and every price derived from it — one year stale, and
   * the run would exit green.
   */
  fun assertSurveyYear(surveyYear: Int) {
    check(surveyYear == SURVEY_YEAR) {
      "IPEDS [--survey-year=$surveyYear] does not match the pinned IC_AY window [$SURVEY_YEAR] " +
        "([${ACADEMIC_YEAR_BY_SUFFIX.values.first().label}]..[${ACADEMIC_YEAR_BY_SUFFIX.values.last().label}]): " +
        "IC_AY's [0-$LAST_SUFFIX] suffix is a POSITION in the file's own window, so decoding a " +
        "[$surveyYear] file against it would date every staged charge and every derived price " +
        "[${SURVEY_YEAR - surveyYear}] year(s) wrong; update IpedsChargeVocabulary.SURVEY_YEAR with the file"
    }
  }

  /** The highest year suffix IC_AY publishes — the survey year's own column. */
  private const val LAST_SUFFIX = YEARS_CARRIED - 1
}
