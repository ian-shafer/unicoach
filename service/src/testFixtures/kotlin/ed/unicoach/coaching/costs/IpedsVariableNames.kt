package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.college.IpedsChargeVocabulary
import ed.unicoach.common.util.AcademicYear

/**
 * The IC_AY column each cost field is published under -- the IPEDS twin of
 * [ScorecardVariableNames], and the home a fixture names an IPEDS-priced row
 * from.
 *
 * It exists because the IPEDS half of RFC 179 has NO door to be refused at.
 * `PublishedCell.ScorecardCell.of` reads a Scorecard tier off the variable, so
 * a Scorecard name a fixture invents is refused at construction; a
 * `PublishedCell.Surveyed` takes its tier from the SOURCE, so ANY string is
 * accepted and nothing validates it. The report-page fixture wrote one literal
 * `CHG2AY3` and applied it to every price row of the school -- a
 * books-and-supplies row carrying the in-state tuition column's name -- and
 * every test stayed green.
 *
 * Every name here is DERIVED from [IpedsChargeVocabulary], never typed. That is
 * the point of the file and not a style preference: IC_AY's year suffix is a
 * POSITION in the survey file's own four-year window, so bumping
 * `IpedsChargeVocabulary.SURVEY_YEAR` re-points every one of these strings at a
 * different academic year. A hand-typed copy would go on compiling, go on
 * passing, and go on naming last year's column -- because, again, nothing
 * refuses an IC_AY variable. Derived, it moves with the vocabulary or fails.
 *
 * In the `:service` test FIXTURE source set beside [ScorecardVariableNames],
 * for the same reason: `:public-web`'s report-page test builds price rows too,
 * and one publisher's naming rule may not be typed out in two modules.
 */
object IpedsVariableNames {
  /**
   * The IC_AY column one cost field is published under, at the survey year the
   * pinned file carries -- stem plus year suffix, exactly as
   * `CanonicalMoneyLoader.mapIpedsCharges` builds the `source_variable` it
   * stores.
   *
   * EXHAUSTIVE over [CostField] with no `else`, as [ScorecardVariableNames.priceOf]
   * is: an eighteenth cost field must decide here and fails the BUILD, rather
   * than reaching a run that seeds it under a column IC_AY never published. The
   * five members that fall through are named one by one for that reason.
   */
  fun priceOf(field: CostField): String =
    when (field) {
      // Every field with a `price_figures` address is an IC_AY charge cell:
      // the twelve stems the survey publishes are exactly the twelve cells the
      // read side addresses, which is what `CanonicalAddressContractTest`
      // holds the two sides to. So these arms name no column -- they ask the
      // vocabulary for the one that answers for this field's address.
      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD,
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      -> {
        chargeColumnOf(field)
      }

      // IC_AY publishes no charge variable for these. Four are cohort
      // statistics -- a number about a population, which this survey of
      // published prices does not collect at all -- and the fifth is the
      // at-home food-and-housing line, whose `$0` is unicoach's own assumption
      // (RFC 166 §7) and no publisher's cell.
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
      CostField.NET_PRICE,
      CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD,
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
      -> {
        error(
          "IPEDS IC_AY publishes no charge variable for [${field.wireName}], so a price row for it could not " +
            "be an IC_AY row at all: seed it under the publisher that does publish it (RFC 179, RFC 184)",
        )
      }
    }

  /**
   * The charge column for [field]'s canonical cell: the vocabulary's own stem
   * for that address, spelled at the SURVEY YEAR by the vocabulary's own naming
   * function.
   *
   * Keyed on the ADDRESS rather than on the wire field, because the address is
   * the one thing the two vocabularies share -- `IpedsChargeVocabulary.CELLS`
   * keys a publisher's stem by it, [CostField.figureAddress] keys this repo's
   * field by it -- so a fixture never has to know which stem is which.
   *
   * Nothing here appends the year suffix and nothing here inverts the cell map:
   * both are the vocabulary's own derivations
   * ([IpedsChargeVocabulary.sourceVariableOf],
   * [IpedsChargeVocabulary.STEM_BY_PRICE_CELL]), and a private copy in a
   * fixture is exactly the drift this file exists to remove. The survey year is
   * asked for BY YEAR rather than by suffix position: the suffix is a position
   * in a moving four-year window, so "this year's column" is only stable if the
   * window is asked which position means this year -- and a stale answer would
   * go on compiling and passing, because nothing refuses an IC_AY variable.
   *
   * The address failure below is impossible today and stated anyway: it is what
   * a changed address grid would look like from here, and a fixture that
   * answered anyway would answer with a name for someone else's cell.
   */
  private fun chargeColumnOf(field: CostField): String {
    val address =
      (field.figureAddress as? FigureAddress.Price)?.address
        ?: error("[${field.wireName}] has no `price_figures` address, so no IC_AY charge cell answers for it")
    val coordinate = IpedsChargeVocabulary.PriceCoordinate(address.concept, address.residency, address.arrangement)
    val stem =
      IpedsChargeVocabulary.STEM_BY_PRICE_CELL[coordinate]
        ?: error(
          "IpedsChargeVocabulary stages no charge variable for [$coordinate], the cell [${field.wireName}] is " +
            "read from, so this fixture cannot name an IC_AY column for it",
        )
    return IpedsChargeVocabulary.sourceVariableOf(stem, AcademicYear(IpedsChargeVocabulary.SURVEY_YEAR))
  }
}
