package ed.unicoach.db.models

import ed.unicoach.common.util.CalendarYear

/**
 * What a `program_money_stats` row measures and the calendar year of its dollars
 * (RFC 274), as ONE closed value: one arm per [ProgramMoneyMeasure], so a debt
 * measure with a year, or earnings with none, does not compile. The
 * [FigureReading] precedent; `program_money_stats_amount_usd_year_earnings_check`
 * is the backstop for any writer that bypasses this type.
 */
sealed interface ProgramMoneyDollars {
  val measure: ProgramMoneyMeasure

  /** The calendar year of the dollars, as the publisher states it; null on the arms that state none. */
  val amountUsdYear: CalendarYear?

  /** Treasury earnings: the publisher always states the year of these dollars. */
  data class MedianEarnings4YrAfterEntry(
    override val amountUsdYear: CalendarYear,
  ) : ProgramMoneyDollars {
    override val measure: ProgramMoneyMeasure get() = ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY
  }

  /** NSLDS debt at completion: the publisher names no dollar year. */
  data object MedianDebtAtCompletion : ProgramMoneyDollars {
    override val measure: ProgramMoneyMeasure get() = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION
    override val amountUsdYear: CalendarYear? get() = null
  }

  /** The publisher's ten-year monthly payment on that debt: no dollar year either. */
  data object MedianDebt10YrMonthlyPayment : ProgramMoneyDollars {
    override val measure: ProgramMoneyMeasure get() = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT
    override val amountUsdYear: CalendarYear? get() = null
  }

  companion object {
    /**
     * The one door from a separately held measure and year, for the two
     * boundaries that hold them apart: the publisher's dictionary cell and the
     * stored row. Null where the two disagree; each boundary says what that
     * means there.
     */
    fun ofOrNull(
      measure: ProgramMoneyMeasure,
      amountUsdYear: CalendarYear?,
    ): ProgramMoneyDollars? =
      when (measure) {
        ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> amountUsdYear?.let(::MedianEarnings4YrAfterEntry)
        ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> MedianDebtAtCompletion.takeIf { amountUsdYear == null }
        ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> MedianDebt10YrMonthlyPayment.takeIf { amountUsdYear == null }
      }
  }
}
