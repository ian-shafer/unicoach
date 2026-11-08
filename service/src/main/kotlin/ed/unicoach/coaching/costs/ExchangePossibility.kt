package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.ServedFigures
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CollegeTuitionExchange
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.SeparateApplication
import ed.unicoach.db.models.TuitionExchange
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The most an exchange would leave in tuition, per year, before fees: our
 * arithmetic over the school's published figures at [academicYear], never a
 * published price. One value because a figure with no year is not sayable.
 */
data class CappedTuition(
  val perYearUsd: Int,
  val academicYear: AcademicYear,
)

/** The school's stated deadline as of today, one closed fact so "passed" cannot exist without its date. */
sealed interface ExchangeDeadline {
  data object NotHeld : ExchangeDeadline

  data class Upcoming(
    val applyBy: LocalDate,
  ) : ExchangeDeadline

  data class Passed(
    val applyBy: LocalDate,
  ) : ExchangeDeadline
}

/**
 * A regional tuition exchange this student could use at this school, if admitted
 * under it (RFC 277). A labelled possibility: it carries no `CostField`, so it
 * takes no part in any total or vintage label.
 */
data class ExchangePossibility(
  val exchange: TuitionExchange,
  val maxPercentOfResidentTuition: Int,
  val selection: ExchangeSelection,
  val separateApplication: SeparateApplication,
  val minimumUnweightedGpa: BigDecimal?,
  val percentOfEligibleOffered: Int?,
  /** Null where a figure is unpublished or the ceiling does not bind; the percentage is what is said then. */
  val cappedTuition: CappedTuition?,
  val deadline: ExchangeDeadline,
  val sourceUrl: String,
  val checkedOn: LocalDate,
) {
  val cappedTuitionPerYearUsd: Int? get() = cappedTuition?.perYearUsd
}

/**
 * Which of [rows] this student can be told about.
 *
 * A row yields a possibility only for a student whose state is on file, is one
 * of the exchange's states, and is not the school's own, while our reading of
 * the school's page is still current. Every other case yields nothing, the same
 * result as no row, so an ineligible student's answer says nothing at all.
 */
fun exchangePossibilitiesOf(
  rows: List<CollegeTuitionExchange>,
  residencyState: String?,
  collegeState: String,
  figures: ServedFigures,
  today: LocalDate,
): List<ExchangePossibility> {
  if (residencyState == null || residencyState == collegeState) return emptyList()
  return rows
    .filter { residencyState in it.memberStates && today <= it.expiresOn }
    .map { row ->
      ExchangePossibility(
        exchange = row.exchange,
        maxPercentOfResidentTuition = row.maxPercentOfResidentTuition,
        selection = row.selection,
        separateApplication = row.separateApplication,
        minimumUnweightedGpa = row.minimumUnweightedGpa,
        percentOfEligibleOffered = row.percentOfEligibleOffered,
        cappedTuition = cappedTuitionOf(row.maxPercentOfResidentTuition, figures),
        deadline = deadlineOf(row.applyBy, today),
        sourceUrl = row.sourceUrl,
        checkedOn = row.checkedOn,
      )
    }
}

private fun deadlineOf(
  applyBy: LocalDate?,
  today: LocalDate,
): ExchangeDeadline =
  when {
    applyBy == null -> ExchangeDeadline.NotHeld
    today > applyBy -> ExchangeDeadline.Passed(applyBy)
    else -> ExchangeDeadline.Upcoming(applyBy)
  }

/**
 * The ceiling in dollars, or null when it cannot be said or does not bind.
 *
 * An exchange caps a percentage of resident TUITION and the store holds tuition
 * and fees plus a fees-only figure, so tuition is the difference of the two, on
 * each side, at the one served year. Where out-of-state tuition is already at or
 * under the cap, the exchange saves nothing and no figure is offered.
 */
private fun cappedTuitionOf(
  maxPercent: Int,
  figures: ServedFigures,
): CappedTuition? {
  val academicYear = figures.academicYear ?: return null
  val residentTuition =
    tuitionOnlyOf(figures, CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD)
      ?: return null
  val capped = (residentTuition * maxPercent + HALF_A_PERCENT_POINT) / PERCENT
  val outOfStateTuition =
    tuitionOnlyOf(figures, CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD)
  if (outOfStateTuition != null && outOfStateTuition <= capped) return null
  return CappedTuition(capped, academicYear)
}

/** Tuition with the fees taken out, or null when either figure is unpublished or fees are not a part of the total. */
private fun tuitionOnlyOf(
  figures: ServedFigures,
  tuitionAndFees: CostField,
  feesOnly: CostField,
): Int? {
  val combined = figures.amountOf(tuitionAndFees) ?: return null
  val fees = figures.amountOf(feesOnly) ?: return null
  return (combined - fees).takeIf { it > 0 }
}

private const val PERCENT = 100

/** Added before the integer division by [PERCENT], so the figure rounds to the nearest whole dollar. */
private const val HALF_A_PERCENT_POINT = 50
