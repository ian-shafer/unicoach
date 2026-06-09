package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * A variable-precision calendar value: a year, a year+month, or a full date.
 *
 * Domain-agnostic and `java.time`-backed so future RFCs can reuse it for
 * birthdates, test dates, and deadlines. The canonical wire form is zero-padded
 * ISO ([toIso]) and [parse] accepts exactly that form, making the two symmetric.
 */
sealed interface PartialDate {
  val year: Year
  val month: Month?
  val day: Int?

  /** Zero-padded ISO at the stored precision: "2028" | "2028-06" | "2028-06-15". */
  fun toIso(): String

  data class YearOnly(
    override val year: Year,
  ) : PartialDate {
    override val month: Month? = null
    override val day: Int? = null

    override fun toIso(): String = "%04d".format(year.value)
  }

  data class YearAndMonth(
    override val year: Year,
    val monthOf: Month,
  ) : PartialDate {
    override val month: Month = monthOf
    override val day: Int? = null

    override fun toIso(): String = "%04d-%02d".format(year.value, monthOf.value)
  }

  data class FullDate(
    val date: LocalDate,
  ) : PartialDate {
    override val year: Year = Year.of(date.year)
    override val month: Month = date.month
    override val day: Int = date.dayOfMonth

    override fun toIso(): String = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
  }

  companion object {
    // Zero-padded canonical forms only: YYYY, YYYY-MM, YYYY-MM-DD.
    private val CANONICAL = Regex("""^\d{4}(-\d{2}(-\d{2})?)?$""")

    /** Wire string -> domain. Accepts only the zero-padded canonical ISO forms. */
    fun parse(iso: String): ValidationResult<PartialDate> {
      if (!CANONICAL.matches(iso)) {
        return ValidationResult.Invalid(ValidationError.InvalidFormat)
      }
      return try {
        val parsed =
          when (iso.length) {
            4 -> {
              YearOnly(Year.parse(iso))
            }

            7 -> {
              val ym = YearMonth.parse(iso)
              YearAndMonth(Year.of(ym.year), ym.month)
            }

            else -> {
              FullDate(LocalDate.parse(iso))
            }
          }
        ValidationResult.Valid(parsed)
      } catch (e: DateTimeParseException) {
        ValidationResult.Invalid(ValidationError.InvalidFormat)
      } catch (e: DateTimeException) {
        ValidationResult.Invalid(ValidationError.InvalidFormat)
      }
    }

    /** Decomposed columns -> domain. Mirrors the DB constraints on a read path. */
    fun of(
      year: Int,
      month: Int?,
      day: Int?,
    ): ValidationResult<PartialDate> {
      if (day != null && month == null) {
        return ValidationResult.Invalid(ValidationError.InvalidFormat)
      }
      return try {
        val parsed =
          when {
            month == null -> YearOnly(Year.of(year))
            day == null -> YearAndMonth(Year.of(year), Month.of(month))
            else -> FullDate(LocalDate.of(year, month, day))
          }
        ValidationResult.Valid(parsed)
      } catch (e: DateTimeException) {
        ValidationResult.Invalid(ValidationError.InvalidFormat)
      }
    }
  }
}
