package ed.unicoach.web.render

import ed.unicoach.db.models.User
import ed.unicoach.db.session.StudentSignupDraft
import ed.unicoach.family.EnsureSupporterOutcome
import ed.unicoach.student.StudentService
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.FormSlot
import ed.unicoach.web.auth.STUDENT_FORM_PATH
import ed.unicoach.web.auth.STUDENT_SIGNUP_PATH
import ed.unicoach.web.auth.SUPPORTER_CHOICE_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.legend
import kotlinx.html.p
import kotlinx.html.section
import java.time.LocalDate
import java.time.ZoneOffset

// Onboarding (RFC 268): "Who are you here for?", then — for a student — one
// form. Copy is the iOS app's (RFC 241, RFC 163, RFC 222), verbatim, except the
// three strings a web control needs that iOS does not.

/** The heading both onboarding pages open on, as the iOS screens do. */
fun onboardingHeading(user: User): String = "Welcome, ${user.name.value}"

/** The role choice's question (RFC 241). */
const val ROLE_CHOICE_QUESTION = "Who are you here for?"

const val STUDENT_CHOICE_LABEL = "I'm the student"

const val SUPPORTER_CHOICE_LABEL = "I'm supporting a student"

/** The class the role choice's section carries. */
const val ROLE_CHOICE_CLASS = "role-choice"

/** The class BOTH role controls carry — one link, one button, one look: neither is the default (RFC 241). */
const val ROLE_BUTTON_CLASS = "role-button"

/** The form's own refusal of an incomplete date of birth, as iOS words it. */
const val DATE_OF_BIRTH_REQUIRED_MESSAGE = "Select your date of birth."

/** The form's own refusal of a graduation day with no month (RFC 268, new). */
const val DAY_WITHOUT_MONTH_MESSAGE = "Choose a month to give a day."

/** What a blank state or income select reads (RFC 268, new): unanswered, never declined. */
const val NOT_ANSWERED_LABEL = "Not answered"

const val CREATE_PROFILE_LABEL = "Create Profile"

const val NOT_THE_STUDENT_LABEL = "I'm not the student"

// The student form's field names: each the key its control posts and its
// handler reads back, written once (see EMAIL_FIELD).
const val BIRTH_YEAR_FIELD = "birth_year"
const val BIRTH_MONTH_FIELD = "birth_month"
const val BIRTH_DAY_FIELD = "birth_day"
const val GRADUATION_YEAR_FIELD = "graduation_year"
const val GRADUATION_MONTH_FIELD = "graduation_month"
const val GRADUATION_DAY_FIELD = "graduation_day"
const val RESIDENCY_STATE_FIELD = "residency_state"
const val INCOME_BAND_FIELD = "income_band"

/** How far back the birth-year select reaches: a century, the iOS window. */
private const val BIRTH_YEAR_WINDOW_BACK = 100

/** The graduation-year window around this year, as iOS offers it: four back, eight forward. */
private const val GRADUATION_YEAR_WINDOW_BACK = 4
private const val GRADUATION_YEAR_WINDOW_FORWARD = 8

/** "This year", for the two windows: UTC, the zone `StudentService` judges the age floor in. */
private fun currentYear(): Int = LocalDate.now(ZoneOffset.UTC).year

/** The date of birth: three selects starting blank, no caption (RFC 222's neutral age screen). */
private fun dateOfBirthControl(): PartialDateControl {
  val year = currentYear()
  return PartialDateControl(
    legend = "When were you born?",
    names = DatePartNames(BIRTH_YEAR_FIELD, BIRTH_MONTH_FIELD, BIRTH_DAY_FIELD),
    // Most recent first: a reading order, not a default — no year is selected.
    years = (year downTo year - BIRTH_YEAR_WINDOW_BACK).toList(),
    yearLabel = "Year",
    monthLabel = "Month",
    dayLabel = "Day",
    required = RequiredDateParts.ALL,
  )
}

/** The graduation date: a year, then optionally a month and a day — the "(optional)" labels replace iOS's precision picker. */
private fun graduationControl(): PartialDateControl {
  val year = currentYear()
  return PartialDateControl(
    legend = "When do you graduate?",
    names = DatePartNames(GRADUATION_YEAR_FIELD, GRADUATION_MONTH_FIELD, GRADUATION_DAY_FIELD),
    years = (year - GRADUATION_YEAR_WINDOW_BACK..year + GRADUATION_YEAR_WINDOW_FORWARD).toList(),
    yearLabel = "Year",
    monthLabel = "Month (optional)",
    dayLabel = "Day (optional)",
    required = RequiredDateParts.YEAR,
  )
}

private const val GRADUATION_CAPTION = "Sets your application deadlines and the award years I price against."

private const val OPTIONAL_OVERLINE = "Optional — you can add these any time"

private const val OPTIONAL_BODY = "Answer now or later; just ask me in chat. I'll work without them."

private const val STATE_CAPTION =
  "Public colleges publish two prices. Knowing your state lets me show the one you'd actually pay — " +
    "a median \$6,300 a year difference."

private const val INCOME_CAPTION =
  "Net price varies a lot by income. With a bracket I can show your family's figure instead of the " +
    "all-family average — about \$1,376 a year for a middle bracket."

/**
 * The student form's place in the session's drafts (RFC 265): the field
 * `session_metadata.proto` gives it, `FormDrafts.student_signup`. It has no
 * date of birth to hold (D-B).
 */
val STUDENT_SIGNUP_DRAFT =
  FormSlot<StudentSignupDraft>(
    read = { drafts -> if (drafts.hasStudentSignup()) drafts.studentSignup else null },
    write = { drafts, draft -> drafts.setStudentSignup(draft) },
    clear = { drafts -> drafts.clearStudentSignup() },
  )

/** Every control of the student form: `null` is a control left unanswered. */
data class StudentFormValues(
  val dateOfBirth: DateParts,
  val graduation: DateParts,
  val residencyState: String?,
  val incomeBand: String?,
) {
  /**
   * What the session keeps of these values (D-B): everything but the date of
   * birth, which has no field to go in. The draft holds each control as posted
   * (RFC 265), so an unanswered one is stored as the `""` its blank option posts.
   */
  fun toDraft(): StudentSignupDraft =
    StudentSignupDraft
      .newBuilder()
      .setGraduationYear(graduation.year.orEmpty())
      .setGraduationMonth(graduation.month.orEmpty())
      .setGraduationDay(graduation.day.orEmpty())
      .setResidencyState(residencyState.orEmpty())
      .setIncomeBand(incomeBand.orEmpty())
      .build()

  companion object {
    val BLANK = StudentFormValues(DateParts.BLANK, DateParts.BLANK, null, null)

    /** The form a draft prefills: its values, a stored `""` read back as unanswered, and a blank date of birth. */
    fun fromDraft(draft: StudentSignupDraft): StudentFormValues =
      StudentFormValues(
        dateOfBirth = DateParts.BLANK,
        graduation = DateParts(draft.graduationYear.asAnswer(), draft.graduationMonth.asAnswer(), draft.graduationDay.asAnswer()),
        residencyState = draft.residencyState.asAnswer(),
        incomeBand = draft.incomeBand.asAnswer(),
      )

    /** A draft field as an answer: the `""` a blank option posted is no answer. */
    private fun String.asAnswer(): String? = ifEmpty { null }
  }
}

/**
 * A rule the student form judges itself, before `:service` is asked (D13),
 * with the sentence the page says beside the control it is about.
 */
enum class StudentFormRule(
  val message: String,
) {
  /** Year, month and day of birth are not all chosen: there is no half a date of birth. */
  DATE_OF_BIRTH_INCOMPLETE(DATE_OF_BIRTH_REQUIRED_MESSAGE),

  /** A graduation day was chosen with no month. */
  GRADUATION_DAY_WITHOUT_MONTH(DAY_WITHOUT_MONTH_MESSAGE),
}

/** A refused submission's reasons, each said where its answer is. */
data class StudentFormErrors(
  /** The form's own rules the submission broke. When any is, `:service` was never asked. */
  val broken: Set<StudentFormRule> = emptySet(),
  /** `:service`'s messages about the date of birth, verbatim. */
  val dateOfBirth: List<String> = emptyList(),
  /** `:service`'s messages about the graduation date, verbatim. */
  val graduation: List<String> = emptyList(),
  /** Messages about no one control: `:service` naming a field this form does not render. */
  val form: List<String> = emptyList(),
) {
  /** Everything said under the date of birth. */
  val dateOfBirthMessages: List<String>
    get() = said(StudentFormRule.DATE_OF_BIRTH_INCOMPLETE) + dateOfBirth

  /** Everything said under the graduation date. */
  val graduationMessages: List<String>
    get() = said(StudentFormRule.GRADUATION_DAY_WITHOUT_MONTH) + graduation

  private fun said(rule: StudentFormRule): List<String> = if (rule in broken) listOf(rule.message) else emptyList()
}

/**
 * The states the student form is rendered in. Sealed and status-owning, as
 * [ChangeEmailFormState] is, so a status and the values it renders cannot be
 * paired wrongly at a call site.
 */
sealed interface StudentFormState {
  val status: HttpStatusCode
  val values: StudentFormValues

  /** Nobody has submitted it on this page: blank, or prefilled from the session's draft. */
  data class Open(
    override val values: StudentFormValues,
  ) : StudentFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /** A submission refused for its answers, rendered back with them and the reasons. */
  data class Refused(
    override val values: StudentFormValues,
    val errors: StudentFormErrors,
  ) : StudentFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /**
   * Below the 13+ floor (RFC 222): `:service`'s sentence over a BLANK form.
   * Nothing about the attempt is kept, on the page or in the session.
   */
  data object UnderMinimumAge : StudentFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.Forbidden
    override val values: StudentFormValues get() = StudentFormValues.BLANK
  }

  /**
   * A body that is not a submission of this form — not a form at all, a
   * missing or repeated control, a value no option carries. Re-rendered blank,
   * as the change-email form is: there is no reading of it to give back.
   */
  data object Unreadable : StudentFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
    override val values: StudentFormValues get() = StudentFormValues.BLANK
  }
}

/** What the role choice is rendering, and the status that says it. */
sealed interface RoleChoiceState {
  val status: HttpStatusCode

  /** The question, unanswered. */
  data object Open : RoleChoiceState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /** The caller's supporter row was closed: `:service`'s sentence over the question. */
  data object SupporterClosed : RoleChoiceState {
    override val status: HttpStatusCode get() = HttpStatusCode.Conflict
  }
}

/**
 * `/get-started`: "Who are you here for?" (RFC 241's shape, RFC 268).
 *
 * TWO EQUAL PATHS: the student's is a link to the form and the supporter's is
 * a `POST`, because only the second changes anything — but both carry
 * [ROLE_BUTTON_CLASS] and neither is styled as the default, because there is no
 * wrong answer.
 */
suspend fun ApplicationCall.respondRoleChoicePage(
  user: User,
  nav: ShellNav.Onboarding,
  state: RoleChoiceState,
) {
  val heading = onboardingHeading(user)
  // `:service`'s sentence, verbatim: the page states no rule of its own.
  val refusal = if (state is RoleChoiceState.SupporterClosed) EnsureSupporterOutcome.Closed.MESSAGE else null
  respondHtml(state.status) {
    siteLayout(heading, nav = nav) {
      section(ROLE_CHOICE_CLASS) {
        h1 { +heading }
        h2 { +ROLE_CHOICE_QUESTION }
        formError(refusal)
        div("$ROLE_CHOICE_CLASS-paths") {
          a(href = STUDENT_FORM_PATH, classes = ROLE_BUTTON_CLASS) { +STUDENT_CHOICE_LABEL }
          signedInForm(SUPPORTER_CHOICE_PATH, nav.csrf) {
            button(type = ButtonType.submit, classes = ROLE_BUTTON_CLASS) { +SUPPORTER_CHOICE_LABEL }
          }
        }
      }
    }
  }
}

/**
 * `/get-started/student`: the student's one form (RFC 268).
 *
 * The date of birth and the graduation year are required; the state and the
 * income bracket are optional, and blank means unanswered (RFC 163).
 * [residencyOptions] and [incomeOptions] are the served vocabularies, so the
 * form offers exactly what the writer accepts. [draftNotKept] says why a
 * refused submission's answers were not kept, when they were not.
 */
suspend fun ApplicationCall.respondStudentFormPage(
  user: User,
  nav: ShellNav.Onboarding,
  state: StudentFormState,
  residencyOptions: List<SelectOption>,
  incomeOptions: List<SelectOption>,
  draftNotKept: DraftNotKept? = null,
) {
  val heading = onboardingHeading(user)
  val values = state.values
  val errors = (state as? StudentFormState.Refused)?.errors ?: StudentFormErrors()
  respondHtml(state.status) {
    siteLayout(heading, nav = nav) {
      authFormCard(
        heading = heading,
        action = STUDENT_FORM_PATH,
        csrf = nav.csrf,
        // `:service`'s sentence, verbatim: the page states no rule of its own.
        error = if (state is StudentFormState.UnderMinimumAge) StudentService.UNDER_MINIMUM_AGE_MESSAGE else null,
        intro = {
          formErrors(errors.form)
          draftNotKeptNotice(draftNotKept)
        },
        after = {
          p { a(href = STUDENT_SIGNUP_PATH) { +NOT_THE_STUDENT_LABEL } }
        },
      ) {
        partialDateGroup(dateOfBirthControl(), values.dateOfBirth, errors = errors.dateOfBirthMessages)
        partialDateGroup(graduationControl(), values.graduation, caption = GRADUATION_CAPTION, errors = errors.graduationMessages)
        fieldSet {
          legend { +OPTIONAL_OVERLINE }
          p { +OPTIONAL_BODY }
          residencySelect(
            name = RESIDENCY_STATE_FIELD,
            options = residencyOptions,
            selected = values.residencyState,
            blankLabel = NOT_ANSWERED_LABEL,
            caption = STATE_CAPTION,
          )
          incomeSelect(
            name = INCOME_BAND_FIELD,
            options = incomeOptions,
            selected = values.incomeBand,
            blankLabel = NOT_ANSWERED_LABEL,
            caption = INCOME_CAPTION,
          )
        }
        button(type = ButtonType.submit) { +CREATE_PROFILE_LABEL }
      }
    }
  }
}
