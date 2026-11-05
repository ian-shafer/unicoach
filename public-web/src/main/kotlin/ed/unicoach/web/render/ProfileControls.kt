package ed.unicoach.web.render

import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.vocabulary.Vocabulary
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.legend
import kotlinx.html.p
import java.time.Month

// The profile controls (RFC 268): the answers a student gives about themselves,
// each a function taking its current value and its errors, so the onboarding
// form and `web/07`'s Your details screen render one control and not two.

/**
 * The options of the one published vocabulary named [name], for the income and
 * state selects; anything but exactly one is a fault, said with what was
 * published.
 */
fun List<Vocabulary>.optionsOf(name: String): List<SelectOption> {
  val matches = filter { it.name == name }
  check(matches.size == 1) {
    "expected exactly one published vocabulary [$name], found ${matches.size} among ${map(Vocabulary::name)}"
  }
  return matches.single().entries.map { entry -> SelectOption(entry.value, entry.label) }
}

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
 * What the state of residence improves, as the iOS onboarding says it: read by
 * the onboarding form and by Your details (RFC 270), so both say one sentence.
 */
const val STATE_CAPTION =
  "Public colleges publish two prices. Knowing your state lets me show the one you'd actually pay — " +
    "a median \$6,300 a year difference."

/** What the household income improves, as the iOS onboarding says it; read where [STATE_CAPTION] is. */
const val INCOME_CAPTION =
  "Net price varies a lot by income. With a bracket I can show your family's figure instead of the " +
    "all-family average — about \$1,376 a year for a middle bracket."

/** What the living plan improves (RFC 270, new). */
const val LIVING_PLAN_CAPTION =
  "Housing and food is part of every college's price. Knowing where you'd live lets me use the right figure for it."

/** What the FAFSA dependency improves (RFC 270, new). */
const val DEPENDENCY_CAPTION =
  "On the FAFSA, a dependent student reports their parents' income. Knowing which you'll be lets me use the " +
    "right federal loan limits and Pell Grant rules."

/**
 * Each living arrangement as the web offers it (brief 0013 D16): web copy at the
 * edge, not the enum's spoken [LivingArrangement.label]. Exhaustive, so a new
 * arrangement does not compile until it has words here.
 */
fun LivingArrangement.optionCaption(): String =
  when (this) {
    LivingArrangement.ON_CAMPUS -> "Living on campus"
    LivingArrangement.OFF_CAMPUS -> "Renting off campus"
    LivingArrangement.WITH_FAMILY -> "Living at home with family"
  }

/** Each dependency status as the web offers it (brief 0013 D16); exhaustive, as [optionCaption] over arrangements is. */
fun DependencyStatus.optionCaption(): String =
  when (this) {
    DependencyStatus.DEPENDENT -> "I will be a dependent on the FAFSA"
    DependencyStatus.INDEPENDENT -> "I will file as independent"
  }

/** The living-plan select's options, in the enum's order, each posting the stored value. */
val LIVING_PLAN_OPTIONS: List<SelectOption> =
  LivingArrangement.entries.map { plan -> SelectOption(plan.value, plan.optionCaption()) }

/** The dependency select's options, in the enum's order, each posting the stored value. */
val DEPENDENCY_OPTIONS: List<SelectOption> =
  DependencyStatus.entries.map { status -> SelectOption(status.value, status.optionCaption()) }

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

/**
 * Where the student would live (RFC 270), one select over
 * [LIVING_PLAN_OPTIONS]. Blank is UNANSWERED, never declined, and reads
 * [blankLabel].
 */
fun FlowContent.livingPlanSelect(
  name: String,
  selected: String?,
  blankLabel: String,
  caption: String? = null,
  errors: List<String> = emptyList(),
) = selectField(
  name = name,
  labelText = "Where you'd live",
  options = LIVING_PLAN_OPTIONS,
  selected = selected,
  blankLabel = blankLabel,
  caption = caption,
  errors = errors,
)

/**
 * The FAFSA dependency (RFC 270), one select over [DEPENDENCY_OPTIONS]. Blank
 * is UNANSWERED, never declined, and reads [blankLabel].
 */
fun FlowContent.dependencySelect(
  name: String,
  selected: String?,
  blankLabel: String,
  caption: String? = null,
  errors: List<String> = emptyList(),
) = selectField(
  name = name,
  labelText = "FAFSA dependency",
  options = DEPENDENCY_OPTIONS,
  selected = selected,
  blankLabel = blankLabel,
  caption = caption,
  errors = errors,
)
