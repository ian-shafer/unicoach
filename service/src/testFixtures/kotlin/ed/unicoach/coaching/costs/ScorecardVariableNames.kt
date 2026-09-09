package ed.unicoach.coaching.costs

import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure

/**
 * The College Scorecard's OWN column name for each cohort measure this repo's
 * fixtures seed (RFC 179) -- one home, shared by every fake that builds a
 * Scorecard row.
 *
 * The string stopped being decoration when an assurance tier became a function
 * of `(source, source_variable)`: the resolver refuses a Scorecard variable it
 * does not know rather than guessing a tier, so a fixture writing `FIXTURE` or a
 * wire field name into that column is seeding a row the production read would
 * refuse. Every fixture therefore needs a REAL published name, and they need the
 * same one -- this mapping was typed out twice, byte for byte, in `:service`'s
 * `CostsTestDb` and `:public-web`'s `FakeCostReportSource`, which is two places
 * for one publisher's naming rule to be corrected in.
 *
 * In a test FIXTURE source set for [UcsdScorecardRow]'s reason, and beside it:
 * `:public-web`'s report-page test already reads this module's fixtures.
 *
 * A `when` with an explicit `else`, not an exhaustive one: [MoneyMeasure] is the
 * STORE's vocabulary and grows with every source, while the Scorecard publishes
 * a column for only some of it. A measure with no Scorecard column fails here,
 * naming the way out -- pass the variable explicitly -- rather than at the tier
 * resolver with a string nobody recognises.
 */
object ScorecardVariableNames {
  /**
   * The Scorecard column one cohort measure is published under.
   *
   * [incomeBand] selects within the NPT4 band series and is ignored elsewhere.
   * The `_PRIV` arm is the private-college one: the net-price family is
   * control-keyed, and a fixture that does not say otherwise is a private
   * school's row.
   */
  fun cohortOf(
    measure: MoneyMeasure,
    incomeBand: IncomeBand?,
  ): String {
    // The band is part of the ADDRESS, not a hint: a measure the Scorecard does
    // not publish by household income has no banded column, so a band handed in
    // for one is a MIS-ADDRESSED row rather than a surplus argument to discard.
    // `CanonicalMoneyLoader.cohortStat` refuses the same pairing; a fixture home
    // that swallowed it would seed rows the fill could never write.
    require(incomeBand == null || measure.bandable) {
      "[${measure.value}] does not band by household income, so no Scorecard column answers for " +
        "[${incomeBand?.value}] (RFC 158, RFC 179)"
    }
    return when (measure) {
      MoneyMeasure.PUBLISHED_COST_BLEND -> {
        "COSTT4_A"
      }

      MoneyMeasure.AVG_NET_PRICE -> {
        "NPT4${incomeBand?.bandDigit ?: ""}_PRIV"
      }

      MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> {
        "GRAD_DEBT_MDN"
      }

      MoneyMeasure.MEDIAN_EARNINGS_10Y -> {
        "MD_EARN_WNE_P10"
      }

      MoneyMeasure.PELL_SHARE -> {
        "PCTPELL"
      }

      else -> {
        error(
          "the Scorecard publishes no column named here for [${measure.value}], so a fixture row for it would " +
            "carry a variable no assurance tier answers for: name the column, or pass `sourceVariable` " +
            "explicitly (RFC 179)",
        )
      }
    }
  }

  /**
   * The Scorecard PRICE column one cost field is published under -- the
   * `price_figures` twin of [cohortOf], and the one home of the eight
   * columns the real Scorecard price fill writes.
   *
   * EXHAUSTIVE over [CostField], with no `else`: this vocabulary is closed and
   * it is the fill's own, so a thirteenth price field wired into the Scorecard
   * arm must decide here and fails the BUILD -- not a later test run that
   * happens to seed it. The nine members that fall through are named one by one
   * for exactly that reason.
   */
  fun priceOf(field: CostField): String =
    when (field) {
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD -> {
        "TUITIONFEE_IN"
      }

      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD -> {
        "TUITIONFEE_OUT"
      }

      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD -> {
        "ROOMBOARD_ON"
      }

      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD -> {
        "ROOMBOARD_OFF"
      }

      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD -> {
        "BOOKSUPPLY"
      }

      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD -> {
        "OTHEREXPENSE_ON"
      }

      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD -> {
        "OTHEREXPENSE_OFF"
      }

      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD -> {
        "OTHEREXPENSE_FAM"
      }

      // The Scorecard publishes no PRICE column for these. Four are cohort
      // statistics with their own columns ([cohortOf]), one is ours
      // alone, and the rest the Scorecard simply does not publish.
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
      CostField.NET_PRICE,
      CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD,
      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
      -> {
        error(
          "the Scorecard publishes no price column for [${field.wireName}], so a price row for it would " +
            "carry a variable no assurance tier answers for: name the column, or pass `sourceVariable` " +
            "explicitly (RFC 179)",
        )
      }
    }
}
