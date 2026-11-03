package ed.unicoach.web.signup

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.Student
import ed.unicoach.error.FieldError
import ed.unicoach.family.EnsureSupporterOutcome
import ed.unicoach.family.SupporterService
import ed.unicoach.student.AccountRole
import ed.unicoach.student.AccountRoleService
import ed.unicoach.student.CreateStudentResult
import ed.unicoach.student.StudentService
import ed.unicoach.vocabulary.Vocabulary
import ed.unicoach.vocabulary.VocabularyService
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.DraftSave
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.ACCOUNT_PATH
import ed.unicoach.web.auth.CHAT_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.auth.STUDENT_FORM_PATH
import ed.unicoach.web.auth.STUDENT_SIGNUP_PATH
import ed.unicoach.web.auth.SUPPORTER_CHOICE_PATH
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.accountRole
import ed.unicoach.web.auth.getVerified
import ed.unicoach.web.auth.homePath
import ed.unicoach.web.auth.postVerified
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.clearDraft
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.draftFor
import ed.unicoach.web.formField
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.render.BIRTH_DAY_FIELD
import ed.unicoach.web.render.BIRTH_MONTH_FIELD
import ed.unicoach.web.render.BIRTH_YEAR_FIELD
import ed.unicoach.web.render.DAY_OPTIONS
import ed.unicoach.web.render.DateParts
import ed.unicoach.web.render.GRADUATION_DAY_FIELD
import ed.unicoach.web.render.GRADUATION_MONTH_FIELD
import ed.unicoach.web.render.GRADUATION_YEAR_FIELD
import ed.unicoach.web.render.INCOME_BAND_FIELD
import ed.unicoach.web.render.MONTH_OPTIONS
import ed.unicoach.web.render.RESIDENCY_STATE_FIELD
import ed.unicoach.web.render.RoleChoiceState
import ed.unicoach.web.render.STUDENT_SIGNUP_DRAFT
import ed.unicoach.web.render.SelectOption
import ed.unicoach.web.render.StudentFormErrors
import ed.unicoach.web.render.StudentFormRule
import ed.unicoach.web.render.StudentFormState
import ed.unicoach.web.render.StudentFormValues
import ed.unicoach.web.render.onboardingNav
import ed.unicoach.web.render.respondRoleChoicePage
import ed.unicoach.web.render.respondStudentFormPage
import ed.unicoach.web.saveDraft
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory

/** Onboarding's own log: the one write it swallows is otherwise invisible. */
private val onboardingLog = LoggerFactory.getLogger("ed.unicoach.web.Onboarding")

/** What onboarding reads and writes through (RFC 268), held together so the four routes take one value. */
internal class OnboardingServices(
  val accountRoles: AccountRoleService,
  val students: StudentService,
  val supporters: SupporterService,
  val moneyProfiles: MoneyProfileService,
  val vocabularies: VocabularyService,
)

/**
 * Onboarding (RFC 268, D-E): an account that is neither a student nor a
 * supporter answers "Who are you here for?" here, and nothing else is open to
 * it but sign-out and the pages that never needed an account.
 *
 * | Method + path                  | Does                                                   |
 * | ------------------------------ | ------------------------------------------------------ |
 * | `GET /get-started`             | the role choice                                        |
 * | `POST /get-started/supporter`  | makes the supporter row, then `/account`               |
 * | `GET /get-started/student`     | the student form, prefilled from the session's draft   |
 * | `POST /get-started/student`    | makes the student, then `/chat`                        |
 *
 * Every route is VERIFIED, not ONBOARDED — or no one could onboard — and every
 * one first asks the caller's [AccountRole]: an account that has already
 * answered is sent where that answer lives, so a stale tab or a back button
 * cannot make a second row.
 */
internal fun Route.getStartedRoutes(
  services: OnboardingServices,
  sessionMetadata: SessionMetadataService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  getVerified(STUDENT_SIGNUP_PATH, authService, sessionConfig) { caller ->
    val role = caller.accountRole(services.accountRoles)
    if (role ==
      AccountRole.None
    ) {
      call.respondRoleChoicePage(caller.user, caller.onboardingNav, RoleChoiceState.Open)
    } else {
      call.respondGateRedirect(role.homePath())
    }
  }

  postVerified(SUPPORTER_CHOICE_PATH, authService, sessionConfig) { caller ->
    val role = caller.accountRole(services.accountRoles)
    if (role is AccountRole.Student) return@postVerified call.respondSeeOther(role.homePath())
    // Idempotent: a supporter who posts again lands where the first post did.
    when (services.supporters.ensure(caller.user.id).getOrThrow()) {
      is EnsureSupporterOutcome.Present -> {
        call.respondSeeOther(ACCOUNT_PATH)
      }

      EnsureSupporterOutcome.Closed -> {
        call.respondRoleChoicePage(caller.user, caller.onboardingNav, RoleChoiceState.SupporterClosed)
      }
    }
  }

  getVerified(STUDENT_FORM_PATH, authService, sessionConfig) { caller ->
    val role = caller.accountRole(services.accountRoles)
    if (role != AccountRole.None) return@getVerified call.respondGateRedirect(role.homePath())
    // The draft a refused submission left, values only: its errors belonged
    // to that attempt (RFC 265).
    val values = caller.draftFor(STUDENT_SIGNUP_DRAFT)?.let(StudentFormValues::fromDraft) ?: StudentFormValues.BLANK
    call.respondStudentForm(services, caller, StudentFormState.Open(values))
  }

  postVerified(STUDENT_FORM_PATH, authService, sessionConfig) { caller ->
    when (val role = caller.accountRole(services.accountRoles)) {
      is AccountRole.Student -> return@postVerified call.respondStudentCreated(sessionMetadata, caller)
      is AccountRole.Supporter -> return@postVerified call.respondSeeOther(role.homePath())
      AccountRole.None -> Unit
    }
    // Not this form's submission: re-rendered at 400 with nothing saved, as
    // the change-email form does — there is no reading of it to keep. A body
    // that is not a form at all is logged by `formParametersOrNull`.
    val parameters =
      call.formParametersOrNull()
        ?: return@postVerified call.respondStudentForm(services, caller, StudentFormState.Unreadable)
    when (val reading = parameters.studentFormReading()) {
      is StudentFormReading.Read -> {
        call.createStudent(services, sessionMetadata, caller, reading.values)
      }

      is StudentFormReading.Unreadable -> {
        // The control and the mode, never the value: these are a minor's answers.
        onboardingLog.info("student form unreadable at control [{}]: {}", reading.control, reading.mode)
        call.respondStudentForm(services, caller, StudentFormState.Unreadable)
      }
    }
  }
}

/**
 * The form's own refusals, then `:service`'s: an incomplete date of birth and
 * a day without a month are judged here and never reach `createStudent`, and
 * every other rule about either date is `createStudent`'s (D13).
 */
private suspend fun ApplicationCall.createStudent(
  services: OnboardingServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  values: StudentFormValues,
) {
  val broken = values.brokenRules()
  if (broken.isNotEmpty()) {
    return respondStudentRefused(services, sessionMetadata, caller, StudentFormState.Refused(values, StudentFormErrors(broken = broken)))
  }

  // `createStudent` takes the graduation date as a string, so an unanswered
  // year crosses as `""` and `:service` refuses it with its own message (D13).
  val graduationIso = values.graduation.partialDateIso().orEmpty()
  when (val result = services.students.createStudent(caller.user.id, graduationIso, values.dateOfBirth.wholeDateIso()).getOrThrow()) {
    is CreateStudentResult.Success -> {
      recordMoneyAnswers(services.moneyProfiles, result.student, values)
      respondStudentCreated(sessionMetadata, caller)
    }

    is CreateStudentResult.ValidationFailure -> {
      respondStudentRefused(services, sessionMetadata, caller, StudentFormState.Refused(values, result.fieldErrors.byControl()))
    }

    // Nothing about the attempt is kept (RFC 222): the draft goes, and the
    // form renders blank under :service's sentence.
    CreateStudentResult.UnderMinimumAge -> {
      clearDraft(sessionMetadata, caller, STUDENT_SIGNUP_DRAFT)
      respondStudentForm(services, caller, StudentFormState.UnderMinimumAge)
    }

    CreateStudentResult.AlreadyExists -> {
      respondStudentCreated(sessionMetadata, caller)
    }
  }
}

/**
 * The optional money answers, after the student exists — the order the
 * money-profile writer requires. Nothing is written for a blank answer, and no
 * request at all when both are blank: an unanswered row and no row are the
 * same fact.
 *
 * A failure is logged and SWALLOWED, as the iOS app swallows it: the student
 * exists and the required answers are kept, so failing the page here would
 * trade a required outcome for an optional one. The answers stay unanswered and
 * the coach asks for them.
 */
private suspend fun recordMoneyAnswers(
  moneyProfiles: MoneyProfileService,
  student: Student,
  values: StudentFormValues,
) {
  val income = values.incomeBand?.let(IncomeBand::fromValue)
  val residency = values.residencyState?.let { MoneyProfileService.parseResidencyState(it) }
  if (income == null && residency == null) return
  val update = MoneyProfileUpdate(income = income?.let { FieldUpdate.Set(it) }, residency = residency?.let { FieldUpdate.Set(it) })
  moneyProfiles
    .upsert(student.id, update)
    .onSuccess { outcome ->
      when (outcome) {
        is UpsertMoneyProfileResult.Success -> {
          Unit
        }

        UpsertMoneyProfileResult.StudentNotFound -> {
          onboardingLog.warn(
            "money answers for student [{}] dropped: the writer found no student",
            student.id.value,
          )
        }
      }
    }.onFailure { cause ->
      onboardingLog.warn(
        "money answers for student [{}] dropped by a failed write",
        student.id.value,
        cause,
      )
    }
}

/** The answer to a student form that made (or found) the student: the draft is dropped, and the coach is next. */
private suspend fun ApplicationCall.respondStudentCreated(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
) {
  clearDraft(sessionMetadata, caller, STUDENT_SIGNUP_DRAFT)
  respondSeeOther(CHAT_PATH)
}

/**
 * The answer to a refused student form: everything but the date of birth is
 * kept in the session (D-B, D-D) and the form renders straight back with all of
 * it. A save that did not keep it adds the notice; a session gone since the
 * request resolved goes to sign-in, as on every signed-in form.
 */
private suspend fun ApplicationCall.respondStudentRefused(
  services: OnboardingServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  refused: StudentFormState.Refused,
) {
  when (val saved = saveDraft(sessionMetadata, caller, STUDENT_SIGNUP_DRAFT, refused.values.toDraft())) {
    DraftSave.Kept -> respondStudentForm(services, caller, refused)
    is DraftSave.NotKept -> respondStudentForm(services, caller, refused, saved.why)
    DraftSave.SessionGone -> respondGateRedirect(SIGN_IN_PATH)
  }
}

/** The student form, with the state and income options read from the served vocabularies. */
private suspend fun ApplicationCall.respondStudentForm(
  services: OnboardingServices,
  caller: WebCaller,
  state: StudentFormState,
  draftNotKept: DraftNotKept? = null,
) {
  val vocabularies =
    services.vocabularies
      .publishedVocabularies()
      .getOrThrow()
      .vocabularies

  respondStudentFormPage(
    caller.user,
    caller.onboardingNav,
    state,
    residencyOptions = vocabularies.optionsOf(VocabularyService.RESIDENCY_STATES),
    incomeOptions = vocabularies.optionsOf(VocabularyService.INCOME_BANDS),
    draftNotKept = draftNotKept,
  )
}

/** The options of the one published vocabulary named [name]; anything but exactly one is a fault, said with what was published. */
private fun List<Vocabulary>.optionsOf(name: String): List<SelectOption> {
  val matches = filter { it.name == name }
  check(matches.size == 1) {
    "expected exactly one published vocabulary [$name], found ${matches.size} among ${map(Vocabulary::name)}"
  }
  return matches.single().entries.map { entry -> SelectOption(entry.value, entry.label) }
}

/** A posted student form: its values, or the first control that is not a submission of the form this module renders. */
private sealed interface StudentFormReading {
  data class Read(
    val values: StudentFormValues,
  ) : StudentFormReading

  /** [control] and why it could not be read. Its value is not kept: these are a minor's answers. */
  data class Unreadable(
    val control: String,
    val mode: UnreadableMode,
  ) : StudentFormReading
}

/** Why a posted control is not one this form could have sent. */
private enum class UnreadableMode {
  /** The control is absent, or posted more than once ([formField]). */
  NOT_ONE_VALUE,

  /** A value none of its options carries: a month or day out of range, a year that is not four digits, a state or a band the vocabulary does not hold. */
  NOT_AN_OPTION,
}

/**
 * The posted controls read as answers. A select posts `""` for its blank
 * option, so blank is always a real answer, read as `null` here and nowhere
 * else.
 */
private fun Parameters.studentFormReading(): StudentFormReading {
  val values =
    StudentFormValues(
      dateOfBirth =
        DateParts(
          year = answer(BIRTH_YEAR_FIELD, ::isYearOption) { return it },
          month = answer(BIRTH_MONTH_FIELD, ::isMonthOption) { return it },
          day = answer(BIRTH_DAY_FIELD, ::isDayOption) { return it },
        ),
      graduation =
        DateParts(
          year = answer(GRADUATION_YEAR_FIELD, ::isYearOption) { return it },
          month = answer(GRADUATION_MONTH_FIELD, ::isMonthOption) { return it },
          day = answer(GRADUATION_DAY_FIELD, ::isDayOption) { return it },
        ),
      residencyState = answer(RESIDENCY_STATE_FIELD, ::isResidencyOption) { return it },
      incomeBand = answer(INCOME_BAND_FIELD, ::isIncomeOption) { return it },
    )
  return StudentFormReading.Read(values)
}

/**
 * One control's answer: `null` for its blank option, else the one value it
 * posted. A control that posted no single value, or a value [isOption] does not
 * accept, is handed to [unreadable], which ends the reading.
 */
private inline fun Parameters.answer(
  control: String,
  isOption: (String) -> Boolean,
  unreadable: (StudentFormReading.Unreadable) -> Nothing,
): String? {
  val value = formField(control) ?: unreadable(StudentFormReading.Unreadable(control, UnreadableMode.NOT_ONE_VALUE))
  return when {
    value.isEmpty() -> null
    isOption(value) -> value
    else -> unreadable(StudentFormReading.Unreadable(control, UnreadableMode.NOT_AN_OPTION))
  }
}

/**
 * A year select's value: four digits. The WINDOW is not checked here
 * — whether a year is a plausible birth or graduation year is `:service`'s
 * rule, and a page rendered on 31 December must still post on 1 January.
 */
private fun isYearOption(value: String): Boolean = YEAR_VALUE.matches(value)

private val YEAR_VALUE = Regex("""^\d{4}$""")

private fun isMonthOption(value: String): Boolean = MONTH_OPTIONS.any { it.value == value }

private fun isDayOption(value: String): Boolean = DAY_OPTIONS.any { it.value == value }

private fun isResidencyOption(value: String): Boolean = value in MoneyProfileService.USPS_STATE_CODES

private fun isIncomeOption(value: String): Boolean = IncomeBand.fromValue(value) != null

/** The form's own rules (D13) these answers break; every other rule about either date is `createStudent`'s. */
private fun StudentFormValues.brokenRules(): Set<StudentFormRule> =
  buildSet {
    if (dateOfBirth.wholeDateIso() == null) add(StudentFormRule.DATE_OF_BIRTH_INCOMPLETE)
    if (graduation.hasDayWithoutMonth()) add(StudentFormRule.GRADUATION_DAY_WITHOUT_MONTH)
  }

/** The whole date as `YYYY-MM-DD`, or `null` while any part is unanswered: there is no half a date of birth. */
private fun DateParts.wholeDateIso(): String? = if (year == null || month == null || day == null) null else partialDateIso()

private fun DateParts.hasDayWithoutMonth(): Boolean = day != null && month == null

/**
 * The graduation date as `createStudent` reads it: `YYYY`, `YYYY-MM` or
 * `YYYY-MM-DD`, as far as it was answered, or `null` for a blank year.
 */
private fun DateParts.partialDateIso(): String? =
  when {
    year == null -> null
    month == null -> year
    day == null -> "$year-${month.padStart(2, '0')}"
    else -> "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
  }

/**
 * `:service`'s field errors, each said beside the control it names. One this
 * form does not render is said over the form, and logged with its field,
 * which the page does not show.
 */
private fun List<FieldError>.byControl(): StudentFormErrors {
  val (dateOfBirth, rest) = partition { it.field == StudentService.DATE_OF_BIRTH_FIELD }
  val (graduation, other) = rest.partition { it.field == StudentService.GRADUATION_DATE_FIELD }
  other.forEach { error ->
    onboardingLog.warn("student form refused for a field it does not render [{}]: [{}]", error.field, error.message)
  }
  return StudentFormErrors(
    dateOfBirth = dateOfBirth.map(FieldError::message),
    graduation = graduation.map(FieldError::message),
    form = other.map(FieldError::message),
  )
}
