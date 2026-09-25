package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth

/**
 * The codec between a [PartialDate] and the two columns that store it: a real
 * `DATE` normalised to the START of the period, plus a [DatePrecision]
 * (RFC 209, brief 0010 D23).
 *
 * One home, so the next table to store a partial date reuses this instead of
 * re-deriving it — that duplication is the whole reason this RFC exists. Every
 * caller is a DAO: the `(date, precision)` pair never escapes the data-access
 * layer, which is what makes a date whose value and precision disagree
 * unrepresentable above storage.
 *
 * ## Why [decode] refuses rather than shortens
 *
 * A stored `2026-01-01` at `year` precision LOOKS like a precise date, and
 * RFC 186's whole lesson is that the coach must never state a month we did not
 * record. The schema's `is_period_start` CHECK makes a disagreeing pair
 * unwritable; if one is ever READ anyway — a hand-written row, a restored dump,
 * a dropped constraint — [decode] returns `Invalid` and the DAO raises
 * `CorruptPersistedValueException`, exactly as a corrupt decomposed date did
 * before. Silently shortening `2026-03-05` to "2026" would throw away a month
 * somebody really did record, and nothing would ever say so (approved by Ian,
 * 2026-09-14).
 */
object PartialDateColumns {
  /** The `DATE` column: the first day of the period the date names. */
  fun periodStart(date: PartialDate): LocalDate =
    // On the SEALED type, not on its nullable month/day projections: a fourth
    // variant is then a compile error here rather than a silent fall-through.
    when (date) {
      is PartialDate.YearOnly -> {
        LocalDate.of(date.year.value, 1, 1)
      }

      is PartialDate.YearAndMonth -> {
        LocalDate.of(date.year.value, date.monthOf, 1)
      }

      is PartialDate.FullDate -> {
        date.date
      }
    }

  /**
   * The LAST day of the period the date names -- `2026-12-31` for `2026`,
   * `2026-12-31` for `2026-12`, the day itself for a full date.
   *
   * BESIDE [periodStart] because it is the same fact read from the other end,
   * and because a caller that needs it has a PUBLISHED BOUND to honour:
   * "through December 2026" admits every December sitting, so an end computed
   * one caller at a time silently shortens somebody's window. It is not a
   * column -- nothing stores it -- but it is the same codec's other half, and
   * the first caller that needed it (`AutomaticAward.lastSittingThroughDay`)
   * had written its own `when` over [PartialDate] to get it.
   */
  fun periodEnd(date: PartialDate): LocalDate =
    when (date) {
      is PartialDate.YearOnly -> {
        LocalDate.of(date.year.value, Month.DECEMBER, 31)
      }

      is PartialDate.YearAndMonth -> {
        YearMonth.of(date.year.value, date.monthOf).atEndOfMonth()
      }

      is PartialDate.FullDate -> {
        date.date
      }
    }

  /** The `date_precision` column. */
  fun precision(date: PartialDate): DatePrecision =
    when (date) {
      is PartialDate.YearOnly -> {
        DatePrecision.YEAR
      }

      is PartialDate.YearAndMonth -> {
        DatePrecision.MONTH
      }

      is PartialDate.FullDate -> {
        DatePrecision.DAY
      }
    }

  /**
   * Columns -> domain. Mirrors the schema's `date_precision` domain and its
   * `is_period_start` CHECK on the read path: an unknown precision, or a date
   * that is not the start of its own period, is row corruption and is reported
   * as such, never truncated.
   */
  fun decode(
    periodStart: LocalDate,
    precision: String,
  ): ValidationResult<PartialDate> {
    val decoded =
      DatePrecision.fromValue(precision)
        ?: return ValidationResult.Invalid(
          ValidationError.InvalidFormat(expected = DatePrecision.EXPECTED_FORM),
        )

    return when (decoded) {
      DatePrecision.YEAR -> {
        if (periodStart.monthValue != 1 || periodStart.dayOfMonth != 1) {
          notPeriodStart(DatePrecision.YEAR)
        } else {
          ValidationResult.Valid(PartialDate.YearOnly(Year.of(periodStart.year)))
        }
      }

      DatePrecision.MONTH -> {
        if (periodStart.dayOfMonth != 1) {
          notPeriodStart(DatePrecision.MONTH)
        } else {
          ValidationResult.Valid(
            PartialDate.YearAndMonth(Year.of(periodStart.year), periodStart.month),
          )
        }
      }

      DatePrecision.DAY -> {
        ValidationResult.Valid(PartialDate.FullDate(periodStart))
      }
    }
  }

  private fun notPeriodStart(precision: DatePrecision): ValidationResult<PartialDate> =
    ValidationResult.Invalid(
      ValidationError.InvalidFormat(
        expected = "a date at the start of its ${precision.value} (the is_period_start CHECK)",
      ),
    )
}
