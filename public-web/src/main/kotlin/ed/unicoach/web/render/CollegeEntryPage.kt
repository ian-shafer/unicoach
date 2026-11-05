package ed.unicoach.web.render

import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.Observation
import ed.unicoach.db.session.CollegeEntryDraft
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.FormSlot
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.blockQuote
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.textArea

// One college on the list (RFC 271): its status and reasons to change, the
// quotes that put it there, and the confirmation that removes it. Copy is the
// iOS app's (`CollegeEntryDetailView`, `CollegeListView`'s remove dialog),
// verbatim, except where marked new.

const val STATUS_LABEL = "Status"

const val REASONS_LABEL = "Why it's on your list"

const val REASONS_PLACEHOLDER = "Add your reasons"

/** The heading over the entry's supporting quotes. */
const val FROM_YOUR_CONVERSATIONS = "From your conversations"

/**
 * A save the reasons CHECK refused: the service's REST wording, naming the
 * ceiling the textarea's `maxlength` also carries.
 */
const val INVALID_REASONS_MESSAGE = "Reasons must be non-empty and at most ${CollegeListService.MAX_REASONS_LENGTH} characters"

const val REMOVE_COLLEGE_HEADING = "Remove this college?"

const val REMOVE_COLLEGE_DETAIL = "Its status and reasons are discarded."

const val CANCEL_LABEL = "Cancel"

/** The hidden field carrying the entry's version as read for the render: the save's and the remove's OCC check. */
const val VERSION_PARAM = "version"

/** The status radios' name. */
const val STATUS_PARAM = "status"

/** The reasons textarea's name. */
const val REASONS_PARAM = "reasons"

/** The class the edit and remove pages' section carries. */
const val COLLEGE_ENTRY_CLASS = "college-entry"

/** The class the status fieldset carries: its radios wrap, each at the tap-target floor. */
const val COLLEGE_STATUS_CLASS = "college-status"

/** The class the supporting quotes' section carries. */
const val COLLEGE_QUOTES_CLASS = "college-quotes"

/** The class the Save/Remove row carries. */
const val COLLEGE_ACTIONS_CLASS = "college-actions"

/** The class a drafted edit's "Not saved yet." line carries. */
const val COLLEGE_PENDING_CLASS = "college-pending"

/**
 * The edit form's place in the session's drafts (RFC 265): the field
 * `session_metadata.proto` gives it, `FormDrafts.college_entry`. One entry's
 * typing at a time; a failed save of another entry replaces it.
 */
val COLLEGE_ENTRY_DRAFT =
  FormSlot<CollegeEntryDraft>(
    read = { drafts -> if (drafts.hasCollegeEntry()) drafts.collegeEntry else null },
    write = { drafts, draft -> drafts.setCollegeEntry(draft) },
    clear = { drafts -> drafts.clearCollegeEntry() },
  )

/** Whether this draft is [entryId]'s: the draft is offered on that entry's page alone. */
fun CollegeEntryDraft.isFor(entryId: CollegeListEntryId): Boolean = this.entryId == entryId.asString

/** The states the edit page is rendered in, each owning its status. */
sealed interface CollegeEntryState {
  val status: HttpStatusCode

  /** A GET: the stored entry, or this entry's drafted edit over it. */
  data object Open : CollegeEntryState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /** A POST that is not a submission of this page: the page as it stands, and nothing said. */
  data object Unreadable : CollegeEntryState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** The reasons CHECK refused the posted reasons. [draftNotKept] says why the typing was not kept, when it was not. */
  data class InvalidReasons(
    val draftNotKept: DraftNotKept?,
  ) : CollegeEntryState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** The write failed. [draftNotKept] as for [InvalidReasons]. */
  data class NotSaved(
    val draftNotKept: DraftNotKept?,
  ) : CollegeEntryState {
    override val status: HttpStatusCode get() = HttpStatusCode.ServiceUnavailable
  }
}

/** What the form shows: the stored values, or typing that has not saved — a draft's, or the failed POST's. */
data class EntryForm(
  val status: CollegeListEntryStatus,
  val reasons: String,
  /** The values are typing that has not saved, so the form says "Not saved yet.". */
  val unsaved: Boolean,
)

/** The entry as the edit and remove pages show it: what the service read, and its college's name. */
data class EntryView(
  val entryId: CollegeListEntryId,
  val collegeName: String,
  val version: Int,
  val supportingObservations: List<Observation>,
)

/**
 * `/colleges/{entryId}`: the status radios and the reasons box, the quotes
 * that put the college on the list, and Save and Remove. The hidden `version`
 * is the one read for this render, so a drafted edit saves over the CURRENT
 * entry (D-A).
 */
suspend fun ApplicationCall.respondCollegeEntryPage(
  nav: ShellNav.SignedIn,
  state: CollegeEntryState,
  entry: EntryView,
  form: EntryForm,
) {
  respondHtml(state.status) {
    siteLayout(entry.collegeName, width = ShellWidth.WIDE, nav = nav) {
      section(COLLEGE_ENTRY_CLASS) {
        h1 { +entry.collegeName }
        backToMyColleges()
        signedInForm(collegeEntryPath(entry.entryId), nav.csrf) {
          hiddenInput(name = VERSION_PARAM) { value = entry.version.toString() }
          formError(if (state is CollegeEntryState.NotSaved) NOT_SAVED_MESSAGE else null)
          draftNotKeptNotice(state.draftNotKept())
          statusField(form.status)
          reasonsField(form.reasons, if (state is CollegeEntryState.InvalidReasons) listOf(INVALID_REASONS_MESSAGE) else emptyList())
          supportingQuotes(entry.supportingObservations)
          if (form.unsaved) p(COLLEGE_PENDING_CLASS) { +NOT_SAVED_YET }
          div(COLLEGE_ACTIONS_CLASS) {
            button(type = ButtonType.submit) { +SAVE_LABEL }
            a(href = collegeRemovePath(entry.entryId)) { +REMOVE_COLLEGE_LABEL }
          }
        }
      }
    }
  }
}

private fun CollegeEntryState.draftNotKept(): DraftNotKept? =
  when (this) {
    CollegeEntryState.Open, CollegeEntryState.Unreadable -> null
    is CollegeEntryState.InvalidReasons -> draftNotKept
    is CollegeEntryState.NotSaved -> draftNotKept
  }

/** "Status": one radio per status, in the enum's order, [checked] checked. */
private fun FlowContent.statusField(checked: CollegeListEntryStatus) {
  fieldSet(COLLEGE_STATUS_CLASS) {
    legend { +STATUS_LABEL }
    for (status in CollegeListEntryStatus.entries) {
      val inputId = "$STATUS_PARAM-${status.value}"
      div {
        input(type = InputType.radio, name = STATUS_PARAM) {
          id = inputId
          value = status.value
          this.checked = status == checked
        }
        label {
          htmlFor = inputId
          +status.label
        }
      }
    }
  }
}

/** "Why it's on your list": the reasons box, [errors] under it. */
private fun FlowContent.reasonsField(
  reasons: String,
  errors: List<String>,
) {
  div(FIELD_CLASS) {
    label {
      htmlFor = REASONS_PARAM
      +REASONS_LABEL
    }
    textArea {
      id = REASONS_PARAM
      name = REASONS_PARAM
      placeholder = REASONS_PLACEHOLDER
      maxLength = CollegeListService.MAX_REASONS_LENGTH.toString()
      rows = "4"
      +reasons
    }
    formErrors(errors)
  }
}

/** "From your conversations": each quote in curly quotes, read-only — or nothing when the entry has none. */
private fun FlowContent.supportingQuotes(observations: List<Observation>) {
  if (observations.isEmpty()) return
  section(COLLEGE_QUOTES_CLASS) {
    h2 { +FROM_YOUR_CONVERSATIONS }
    for (observation in observations) {
      blockQuote { +"“${observation.quote}”" }
    }
  }
}

/** The states the remove confirmation is rendered in. */
enum class RemoveCollegeState(
  val status: HttpStatusCode,
) {
  OPEN(HttpStatusCode.OK),

  /** A POST that is not a submission of this page: the page as it stands, and nothing said. */
  UNREADABLE(HttpStatusCode.BadRequest),

  /** The remove failed. */
  NOT_SAVED(HttpStatusCode.ServiceUnavailable),
}

/**
 * `/colleges/{entryId}/remove`: "Remove this college?", the college's name,
 * and one button that removes it at the version read for this render — or
 * Cancel, back to its edit page.
 */
suspend fun ApplicationCall.respondRemoveCollegePage(
  nav: ShellNav.SignedIn,
  state: RemoveCollegeState,
  entry: EntryView,
) {
  respondHtml(state.status) {
    siteLayout(REMOVE_COLLEGE_HEADING, width = ShellWidth.WIDE, nav = nav) {
      section(COLLEGE_ENTRY_CLASS) {
        h1 { +REMOVE_COLLEGE_HEADING }
        p { +entry.collegeName }
        p { +REMOVE_COLLEGE_DETAIL }
        formError(if (state == RemoveCollegeState.NOT_SAVED) NOT_SAVED_MESSAGE else null)
        signedInForm(collegeRemovePath(entry.entryId), nav.csrf) {
          hiddenInput(name = VERSION_PARAM) { value = entry.version.toString() }
          div(COLLEGE_ACTIONS_CLASS) {
            button(type = ButtonType.submit) { +REMOVE_COLLEGE_LABEL }
            a(href = collegeEntryPath(entry.entryId)) { +CANCEL_LABEL }
          }
        }
      }
    }
  }
}
