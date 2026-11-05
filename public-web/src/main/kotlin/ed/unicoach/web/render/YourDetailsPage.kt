package ed.unicoach.web.render

import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.session.MoneyProfileDraft
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.FormSlot
import ed.unicoach.web.auth.CsrfToken
import ed.unicoach.web.auth.YOUR_DETAILS_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span

// Your details (RFC 270): the four money answers a student can give, decline or
// remove at any time, each saved on its own. Copy is the iOS app's (RFC 171),
// verbatim, except where marked new.

/** The page's name: its heading, its title, and the header link that opens it. */
const val YOUR_DETAILS_TITLE = "Your details"

/** The intro, after the iOS Your details screen's (RFC 171); "these answers" holds for however many this page shows. */
const val YOUR_DETAILS_INTRO =
  "What unicoach knows about your family's money. Change any of it here — every price it shows you follows " +
    "from these answers."

/** The intro's second line (RFC 270, new). */
const val YOUR_DETAILS_OPTIONAL_LINE = "Every answer is optional, and each one saves as soon as you choose it."

/** The answer line of a field with no answer. */
const val NOT_ANSWERED_YET = "Not answered yet"

/** The answer line of a declined field. */
const val DECLINED_ANSWER = "You chose not to say"

/** What a select's blank option reads: no answer chosen, never a decline. */
const val SELECT_BLANK_LABEL = "Select"

const val DECLINE_LABEL = "Prefer not to say"

const val DECLINE_CAPTION = "The coach stops asking about this."

const val REMOVE_LABEL = "Remove my answer"

const val REMOVE_CAPTION = "Returns it to not answered. The coach may invite it again."

/** The receipt beside the answer a redirect just saved. */
const val SAVED_RECEIPT = "Saved"

/** A drafted pick: one whose save did not succeed (RFC 270, new). */
const val NOT_SAVED_YET = "Not saved yet."

/** The button that retries a drafted pick (RFC 270, new). */
const val SAVE_LABEL = "Save"

/** A `save` posted with the blank option (RFC 270, new). */
const val CHOOSE_AN_ANSWER_MESSAGE = "Choose an answer to save."

/** A write that failed (RFC 270, new). */
const val NOT_SAVED_MESSAGE = "That didn't save. Try again."

/** The class the page's section carries. */
const val YOUR_DETAILS_CLASS = "your-details"

/** The class each answer's fieldset carries: its select, answer line and buttons are laid out by it. */
const val DETAILS_FIELD_CLASS = "details-field"

/** The class the answer line carries. */
const val DETAILS_ANSWER_CLASS = "details-answer"

/** The class the "Saved" receipt carries. */
const val DETAILS_RECEIPT_CLASS = "details-receipt"

/** The class a drafted pick's "Not saved yet." line carries. */
const val DETAILS_PENDING_CLASS = "details-pending"

/** The class the decline and remove forms' row carries. */
const val DETAILS_ACTIONS_CLASS = "details-actions"

/**
 * The attribute that marks a pick form for `ts/autosave.ts`, which submits the
 * form when its select changes to a real option. Restated there; the routing
 * test reads it off the served page.
 */
const val AUTOSAVE_ATTRIBUTE = "data-autosave"

/** The hidden field naming which answer a POST is about. */
const val DETAILS_FIELD_PARAM = "field"

/** The hidden field naming what a POST does to it. */
const val DETAILS_ACTION_PARAM = "action"

/** The query key the post-redirect-get names the saved field in. */
const val SAVED_QUERY_PARAM = "saved"

// The two new answers' control names; income and state reuse onboarding's.
const val LIVING_PLAN_FIELD = "living_plan"
const val DEPENDENCY_FIELD = "dependency"

/**
 * The four answers, in page order. [key] is the name the field's select posts
 * under, the value its hidden `field` carries, the `saved` key and the
 * fragment its redirect returns to — one string, so the four cannot disagree.
 */
enum class DetailsField(
  val key: String,
) {
  INCOME_BAND(INCOME_BAND_FIELD),
  RESIDENCY_STATE(RESIDENCY_STATE_FIELD),
  LIVING_PLAN(LIVING_PLAN_FIELD),
  DEPENDENCY(DEPENDENCY_FIELD),
  ;

  /**
   * This field's drafted pick, or `null` for none. The draft's strings are
   * proto3's, whose unset value is `""`; this is the one place that reads it.
   */
  fun draftedIn(draft: MoneyProfileDraft): String? =
    when (this) {
      INCOME_BAND -> draft.incomeBand
      RESIDENCY_STATE -> draft.residencyState
      LIVING_PLAN -> draft.livingPlan
      DEPENDENCY -> draft.dependency
    }.takeIf(String::isNotEmpty)

  /** [draft] with this field's pick set to [pick]; `null` empties it. */
  fun withPick(
    draft: MoneyProfileDraft,
    pick: String?,
  ): MoneyProfileDraft {
    val builder = draft.toBuilder()
    when (this) {
      INCOME_BAND -> pick?.let(builder::setIncomeBand) ?: builder.clearIncomeBand()
      RESIDENCY_STATE -> pick?.let(builder::setResidencyState) ?: builder.clearResidencyState()
      LIVING_PLAN -> pick?.let(builder::setLivingPlan) ?: builder.clearLivingPlan()
      DEPENDENCY -> pick?.let(builder::setDependency) ?: builder.clearDependency()
    }
    return builder.build()
  }

  /** This field's answer in [profile] — no row at all is every field unanswered. */
  fun answerIn(profile: MoneyProfile?): StoredAnswer {
    if (profile == null) return StoredAnswer.Unanswered
    val (status, value) =
      when (this) {
        INCOME_BAND -> profile.incomeBandStatus to profile.incomeBand?.value
        RESIDENCY_STATE -> profile.residencyStatus to profile.residencyState
        LIVING_PLAN -> profile.livingPlanStatus to profile.livingPlan?.value
        DEPENDENCY -> profile.dependencyStatus to profile.dependency?.value
      }
    return when (status) {
      AnswerStatus.UNANSWERED -> {
        StoredAnswer.Unanswered
      }

      AnswerStatus.DECLINED -> {
        StoredAnswer.Declined
      }

      AnswerStatus.ANSWERED -> {
        StoredAnswer.Answered(
          checkNotNull(value) { "money profile [${profile.id.value}] has [$key] answered with no value, which its CHECK forbids" },
        )
      }

      // The money_profiles CHECKs refuse this status on every money column.
      AnswerStatus.UNDECIDED -> {
        error("money profile [${profile.id.value}] has [$key] undecided, which the money_profiles CHECKs forbid")
      }
    }
  }

  companion object {
    /** The field [key] names, or `null` for an absent or unknown one. */
    fun fromKey(key: String?): DetailsField? = entries.firstOrNull { it.key == key }
  }
}

/** What a POST does to one answer. [key] is the value its hidden `action` carries. */
enum class DetailsAction(
  val key: String,
) {
  SAVE("save"),
  DECLINE("decline"),
  REMOVE("remove"),
  ;

  companion object {
    /** The action [key] names, or `null` for an absent or unknown one. */
    fun fromKey(key: String?): DetailsAction? = entries.firstOrNull { it.key == key }
  }
}

/** One answer as it is stored: the three states a money column holds. */
sealed interface StoredAnswer {
  data object Unanswered : StoredAnswer

  data object Declined : StoredAnswer

  /** [value] is the stored value, the one its select's option posts. */
  data class Answered(
    val value: String,
  ) : StoredAnswer
}

/**
 * The page's place in the session's drafts (RFC 265): the field
 * `session_metadata.proto` gives it, `FormDrafts.money_profile`. Each answer
 * saves on its own, so a field here is a pick whose save did not succeed.
 */
val MONEY_PROFILE_DRAFT =
  FormSlot<MoneyProfileDraft>(
    read = { drafts -> if (drafts.hasMoneyProfile()) drafts.moneyProfile else null },
    write = { drafts, draft -> drafts.setMoneyProfile(draft) },
    clear = { drafts -> drafts.clearMoneyProfile() },
  )

/** The drafted picks in [draft], by field; a field with none is absent. */
fun MoneyProfileDraft.picks(): Map<DetailsField, String> =
  DetailsField.entries
    .mapNotNull { field -> field.draftedIn(this)?.let { pick -> field to pick } }
    .toMap()

/** The states the page is rendered in, each owning its status. */
sealed interface YourDetailsState {
  val status: HttpStatusCode

  /** A GET. [saved] is the field a redirect just saved, for its receipt. */
  data class Open(
    val saved: DetailsField?,
  ) : YourDetailsState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /** A POST that is not a submission of this page: the page as it stands, and nothing said. */
  data object Unreadable : YourDetailsState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** A `save` posted with the blank option: nothing to save, said under [field]. */
  data class BlankSave(
    val field: DetailsField,
  ) : YourDetailsState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /**
   * [field]'s write failed. [draftNotKept] says why a failed pick was not kept
   * in the session, when it was not.
   */
  data class NotSaved(
    val field: DetailsField,
    val draftNotKept: DraftNotKept?,
  ) : YourDetailsState {
    override val status: HttpStatusCode get() = HttpStatusCode.ServiceUnavailable
  }
}

/** The served vocabularies' options for the two answers that come from one. */
data class DetailsOptions(
  val income: List<SelectOption>,
  val residency: List<SelectOption>,
) {
  fun of(field: DetailsField): List<SelectOption> =
    when (field) {
      DetailsField.INCOME_BAND -> income
      DetailsField.RESIDENCY_STATE -> residency
      DetailsField.LIVING_PLAN -> LIVING_PLAN_OPTIONS
      DetailsField.DEPENDENCY -> DEPENDENCY_OPTIONS
    }
}

/**
 * `/account/details`: the four money answers (RFC 270).
 *
 * [profile] is the stored row, `null` when there is none. [pending] holds the
 * picks whose save did not succeed — the session's draft, plus the pick a
 * failed POST is rendering back — and each starts its select on that pick with a
 * "Save" button to retry it, because re-choosing the same option fires no
 * change event.
 */
suspend fun ApplicationCall.respondYourDetailsPage(
  nav: ShellNav.SignedIn,
  state: YourDetailsState,
  profile: MoneyProfile?,
  pending: Map<DetailsField, String>,
  options: DetailsOptions,
) {
  respondHtml(state.status) {
    siteLayout(YOUR_DETAILS_TITLE, width = ShellWidth.WIDE, nav = nav) {
      section(YOUR_DETAILS_CLASS) {
        h1 { +YOUR_DETAILS_TITLE }
        p { +YOUR_DETAILS_INTRO }
        p { +YOUR_DETAILS_OPTIONAL_LINE }
        draftNotKeptNotice((state as? YourDetailsState.NotSaved)?.draftNotKept)
        for (field in DetailsField.entries) {
          detailsField(
            field = field,
            answer = field.answerIn(profile),
            pending = pending[field],
            options = options.of(field),
            receipt = state is YourDetailsState.Open && state.saved == field,
            errors = state.errorsFor(field),
            csrf = nav.csrf,
          )
        }
      }
    }
  }
}

/** What is said under [field]'s select in this state. */
private fun YourDetailsState.errorsFor(field: DetailsField): List<String> =
  when (this) {
    is YourDetailsState.Open, YourDetailsState.Unreadable -> emptyList()
    is YourDetailsState.BlankSave -> if (this.field == field) listOf(CHOOSE_AN_ANSWER_MESSAGE) else emptyList()
    is YourDetailsState.NotSaved -> if (this.field == field) listOf(NOT_SAVED_MESSAGE) else emptyList()
  }

/**
 * One answer: its pick form (the select the script submits on change), the
 * answer line, and a one-button form each to decline it and to remove it.
 */
private fun FlowContent.detailsField(
  field: DetailsField,
  answer: StoredAnswer,
  pending: String?,
  options: List<SelectOption>,
  receipt: Boolean,
  errors: List<String>,
  csrf: CsrfToken,
) {
  fieldSet(DETAILS_FIELD_CLASS) {
    signedInForm(YOUR_DETAILS_PATH, csrf, attributes = mapOf(AUTOSAVE_ATTRIBUTE to "")) {
      hiddenInput(name = DETAILS_FIELD_PARAM) { value = field.key }
      hiddenInput(name = DETAILS_ACTION_PARAM) { value = DetailsAction.SAVE.key }
      val selected = pending ?: (answer as? StoredAnswer.Answered)?.value
      when (field) {
        DetailsField.INCOME_BAND -> incomeSelect(field.key, options, selected, SELECT_BLANK_LABEL, INCOME_CAPTION, errors)
        DetailsField.RESIDENCY_STATE -> residencySelect(field.key, options, selected, SELECT_BLANK_LABEL, STATE_CAPTION, errors)
        DetailsField.LIVING_PLAN -> livingPlanSelect(field.key, selected, SELECT_BLANK_LABEL, LIVING_PLAN_CAPTION, errors)
        DetailsField.DEPENDENCY -> dependencySelect(field.key, selected, SELECT_BLANK_LABEL, DEPENDENCY_CAPTION, errors)
      }
      if (pending != null) {
        p(DETAILS_PENDING_CLASS) {
          +NOT_SAVED_YET
          button(type = ButtonType.submit) { +SAVE_LABEL }
        }
      }
    }
    p(DETAILS_ANSWER_CLASS) {
      +answer.line(field, options)
      if (receipt) span(DETAILS_RECEIPT_CLASS) { +SAVED_RECEIPT }
    }
    div(DETAILS_ACTIONS_CLASS) {
      if (answer != StoredAnswer.Declined) actionForm(field, DetailsAction.DECLINE, DECLINE_LABEL, DECLINE_CAPTION, csrf)
      if (answer != StoredAnswer.Unanswered) actionForm(field, DetailsAction.REMOVE, REMOVE_LABEL, REMOVE_CAPTION, csrf)
    }
  }
}

/** The answer line: the chosen option's own words, or what the state says. */
private fun StoredAnswer.line(
  field: DetailsField,
  options: List<SelectOption>,
): String =
  when (this) {
    StoredAnswer.Unanswered -> {
      NOT_ANSWERED_YET
    }

    StoredAnswer.Declined -> {
      DECLINED_ANSWER
    }

    is StoredAnswer.Answered -> {
      checkNotNull(options.firstOrNull { it.value == value }) {
        "[${field.key}] is answered [$value], which none of its ${options.size} options carries"
      }.label
    }
  }

/** A one-button form: [action] on [field], its [caption] under the button. */
private fun FlowContent.actionForm(
  field: DetailsField,
  action: DetailsAction,
  label: String,
  caption: String,
  csrf: CsrfToken,
) {
  signedInForm(YOUR_DETAILS_PATH, csrf) {
    hiddenInput(name = DETAILS_FIELD_PARAM) { value = field.key }
    hiddenInput(name = DETAILS_ACTION_PARAM) { value = action.key }
    button(type = ButtonType.submit) { +label }
    p(FIELD_CAPTION_CLASS) { +caption }
  }
}
