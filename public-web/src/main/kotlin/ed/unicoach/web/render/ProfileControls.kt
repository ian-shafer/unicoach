package ed.unicoach.web.render

import ed.unicoach.common.util.UsDate
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.legend
import kotlinx.html.p
import java.time.Month

// The profile controls (RFC 268): the answers a student gives about themselves,
// each a function taking its current value and its errors, so the onboarding
// form and `web/07`'s Your details screen render one control and not two.

/** The class a partial-date group carries: its selects sit in one row that wraps on a narrow phone. */
const val DATE_GROUP_CLASS = "date-group"

/** The three parts of a date entered as year, month and day selects: `null` is a part left unanswered. */
data class DateParts(
  val year: String?,
  val month: String?,
  val day: String?,
) {
  companion object {
    val BLANK = DateParts(null, null, null)
  }
}

/** The form field names a partial-date group posts its three parts under. */
data class DatePartNames(
  val year: String,
  val month: String,
  val day: String,
)

/**
 * What one partial-date group asks: its [legend], the field names, the years
 * it offers in display order, and the label each part reads.
 */
data class PartialDateControl(
  val legend: String,
  val names: DatePartNames,
  val years: List<Int>,
  val yearLabel: String,
  val monthLabel: String,
  val dayLabel: String,
  val required: RequiredDateParts,
)

/** Which parts of a partial-date group must be chosen before the browser submits it. */
enum class RequiredDateParts {
  /** The year alone: a month and a day narrow the date and are optional (a graduation date). */
  YEAR,

  /** All three: the date is a whole date or no answer (a date of birth). */
  ALL,
}

/**
 * Every month, as its number (the posted value) and its name in the tree's one
 * date shape ([UsDate], RFC 227): `Jan` … `Dec`. No `<input type="date">`.
 */
val MONTH_OPTIONS: List<SelectOption> =
  Month.entries.map { month -> SelectOption(month.value.toString(), UsDate.monthDay(month, day = null)) }

/**
 * Every day a month can have. A form with no script cannot shorten the list to
 * the chosen month, so 31 February is offered and refused by whoever owns the
 * date's validity — `:service` for both dates on the student form.
 */
val DAY_OPTIONS: List<SelectOption> = (1..31).map { day -> SelectOption(day.toString(), day.toString()) }

/**
 * A date as three selects in one `fieldset` — year, month, day, all starting
 * blank — headed by its legend, with [caption] saying what the answer is for
 * and [errors] said under the group they are about.
 */
fun FlowContent.partialDateGroup(
  control: PartialDateControl,
  value: DateParts,
  caption: String? = null,
  errors: List<String> = emptyList(),
) {
  fieldSet(DATE_GROUP_CLASS) {
    legend { +control.legend }
    if (caption != null) p(FIELD_CAPTION_CLASS) { +caption }
    div("$DATE_GROUP_CLASS-parts") {
      selectField(
        name = control.names.year,
        labelText = control.yearLabel,
        options = control.years.map { year -> SelectOption(year.toString(), year.toString()) },
        selected = value.year,
        required = true,
      )
      selectField(
        name = control.names.month,
        labelText = control.monthLabel,
        options = MONTH_OPTIONS,
        selected = value.month,
        required = control.required == RequiredDateParts.ALL,
      )
      selectField(
        name = control.names.day,
        labelText = control.dayLabel,
        options = DAY_OPTIONS,
        selected = value.day,
        required = control.required == RequiredDateParts.ALL,
      )
    }
    formErrors(errors)
  }
}

/**
 * The state of residence, one select over the `residency_states` vocabulary
 * ([options], from `VocabularyService`). Blank is UNANSWERED, never declined
 * (RFC 163), and reads [blankLabel].
 */
fun FlowContent.residencySelect(
  name: String,
  options: List<SelectOption>,
  selected: String?,
  blankLabel: String,
  caption: String? = null,
  errors: List<String> = emptyList(),
) = selectField(
  name = name,
  labelText = "State of residence",
  options = options,
  selected = selected,
  blankLabel = blankLabel,
  caption = caption,
  errors = errors,
)

/**
 * The household income bracket, one select over the `income_bands` vocabulary
 * ([options] — `IncomeBand.bracket`'s words, through `VocabularyService`).
 * Blank is UNANSWERED, never declined (RFC 163), and reads [blankLabel].
 */
fun FlowContent.incomeSelect(
  name: String,
  options: List<SelectOption>,
  selected: String?,
  blankLabel: String,
  caption: String? = null,
  errors: List<String> = emptyList(),
) = selectField(
  name = name,
  labelText = "Household income",
  options = options,
  selected = selected,
  blankLabel = blankLabel,
  caption = caption,
  errors = errors,
)
