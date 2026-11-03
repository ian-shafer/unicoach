package ed.unicoach.web.render

import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.auth.CsrfToken
import io.ktor.http.encodeURLParameter
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.SECTION
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.ul

/** The class the CSS styles a form-level error with, and tests look for. */
const val FORM_ERROR_CLASS = "form-error"

/** The class the CSS styles a field-level error list with. */
const val FIELD_ERROR_CLASS = "field-error"

/** The class a one-off confirmation message carries, so CSS and tests agree on it. */
const val NOTICE_CLASS = "notice"

/** The class an auth form card carries: the CSS width, padding and tap-target rules all target it. */
const val AUTH_FORM_CLASS = "auth-form"

/** The class one labelled control carries. */
const val FIELD_CLASS = "field"

/** The class the sentence under a control carries: what the answer is for (RFC 268). */
const val FIELD_CAPTION_CLASS = "field-caption"

/**
 * The form field names, written ONCE.
 *
 * Each is half of a contract the compiler cannot see: a page renders the control
 * and a route handler reads the posted value back under the same key. Renaming
 * one side alone leaves a form that posts a key nothing reads — silently, with a
 * `200` and an empty field — which is exactly the hazard
 * [ed.unicoach.web.REPORT_TOKEN_PARAM] is named for. They live beside the
 * controls rather than beside the routes because the control is what renders
 * them.
 */
const val EMAIL_FIELD = "email"

const val PASSWORD_FIELD = "password"

const val NAME_FIELD = "name"

const val TOKEN_FIELD = "token"

/**
 * The key a Family Cost Report token rides on through signup and
 * `POST /report/connect` (RFC 254). NEVER [TOKEN_FIELD]: on `/register` and
 * `/sign-in` that key already means an INVITE token, and a report token read as
 * one would be refused as a dead invite.
 */
const val REPORT_FIELD = "report"

/**
 * The hidden field every signed-in form echoes the session's CSRF token in
 * (brief 0013 D22, RFC 265). Underscored so it cannot collide with a field a
 * form names for the family's own input.
 */
const val CSRF_FIELD = "_csrf"

/**
 * The credential keys a signup door's URL may carry (RFC 232, RFC 254), all
 * secret in the request log. Read by both doors' `secretQueryParams` AND by the
 * signed-in landing, which answers before routing and stamps the set itself.
 */
val SIGNUP_DOOR_SECRET_PARAMS: Set<String> = setOf(TOKEN_FIELD, REPORT_FIELD)

/**
 * [path], carrying [token] on the one query key every invite door reads and
 * [report] on its own key, or [path] alone when neither is in flight (RFC 232,
 * RFC 254).
 *
 * The render-side sibling of `ed.unicoach.common.config.tokenLink`, which joins
 * the same key onto an ABSOLUTE minted base. Written once because four render
 * sites spliced it by hand, and percent-encoded because the value arrives from
 * a URL a human may have hand-edited: a token carrying `&` or `#` would
 * otherwise silently become a different query string.
 */
fun pathCarryingToken(
  path: String,
  token: String?,
  report: String? = null,
): String {
  val query =
    listOfNotNull(
      token?.let { "$TOKEN_FIELD=${it.encodeURLParameter()}" },
      report?.let { "$REPORT_FIELD=${it.encodeURLParameter()}" },
    )
  return if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
}

/** The hidden field that carries [token] through a form POST, or nothing when there is none. */
fun FlowContent.tokenField(token: String?) {
  if (token == null) return
  hiddenInput(name = TOKEN_FIELD) { value = token }
}

/**
 * A `POST` form on a signed-in page, opened with the hidden field that echoes
 * the session's CSRF [csrf] token (RFC 265) under [CSRF_FIELD], where the route
 * check reads it back.
 *
 * The ONE way a signed-in page builds a form: the token field is written here
 * and nowhere else, so a signed-in form cannot be built without it and no form
 * author has to remember it. [attributes] are set on the `form` element before
 * any child is written, because the streaming HTML builder refuses an attribute
 * once the element's content has begun.
 */
fun FlowContent.signedInForm(
  action: String,
  csrf: CsrfToken,
  classes: String? = null,
  attributes: Map<String, String> = emptyMap(),
  block: FORM.() -> Unit,
) {
  form(action = action, method = FormMethod.post, classes = classes) {
    attributes.forEach { (name, value) -> this.attributes[name] = value }
    hiddenInput(name = CSRF_FIELD) { value = csrf.value }
    block()
  }
}

/**
 * What a family is told when a failed submission was too big to keep as a draft
 * (RFC 265): everything they typed is still on the page, and nothing is lost
 * unless they leave it.
 */
const val DRAFT_NOT_KEPT_NOTICE =
  "Some of what you typed is too long for us to keep if you leave this page. " +
    "It is all still here — shorten your longest answer, then save."

/**
 * What a family is told when a failed submission could not be kept as a draft
 * for any reason but its length (RFC 265): text the session cannot store, or a
 * fault saving it. There is nothing for them to fix, so it asks for nothing.
 */
const val DRAFT_NOT_SAVED_NOTICE =
  "We could not keep what you typed if you leave this page. It is all still here."

/** The notice for why the draft was not kept, or nothing when it was. */
fun FlowContent.draftNotKeptNotice(notKept: DraftNotKept?) {
  val notice =
    when (notKept) {
      null -> return
      DraftNotKept.TOO_LONG -> DRAFT_NOT_KEPT_NOTICE
      DraftNotKept.NOT_SAVED -> DRAFT_NOT_SAVED_NOTICE
    }
  p(FORM_ERROR_CLASS) { +notice }
}

/** The hidden field that carries a report token through a form POST (RFC 254), or nothing. */
fun FlowContent.reportField(report: String?) {
  if (report == null) return
  hiddenInput(name = REPORT_FIELD) { value = report }
}

// The shared form controls for every public-web form (RFC 224).
//
// They exist so the markup contract -- which classes carry which meaning, which
// control is labelled how -- is written once. Sign-in, registration and
// change-email all render a label+input pair, and three hand-written copies
// would be three chances to ship a control with no label, which is a control a
// screen reader cannot announce and a thumb cannot reliably hit.
//
// Nothing here is inline, and nothing here can be: the content-security policy
// carries no 'unsafe-inline', and ShellMechanismTest's source sweep fails the
// build over an inline handler or an inline presentation attribute.

/** One form-level message, or nothing at all when [message] is null. */
fun FlowContent.formError(message: String?) {
  if (message == null) return
  p(FORM_ERROR_CLASS) { +message }
}

/**
 * Several field-level messages as a list, or nothing when there are none.
 *
 * A LIST rather than a joined sentence: `AuthService.register` returns its
 * validation failures as separate strings because they are separate problems,
 * and joining them with commas makes two problems read as one.
 */
fun FlowContent.formErrors(messages: List<String>) {
  if (messages.isEmpty()) return
  ul(FIELD_ERROR_CLASS) {
    for (message in messages) {
      li { +message }
    }
  }
}

/**
 * The one-off confirmations a post-redirect-get can ask for (RFC 224).
 *
 * An enum and not a sentence, because the message travels through a URL a
 * stranger can write: the KEY selects from this closed set and an unknown key
 * resolves to nothing, so no text of theirs can be rendered inside our shell.
 * The key and the sentence live on ONE value, so a key with no message — or a
 * message no redirect can ask for — is unrepresentable.
 */
enum class WebNotice(
  val key: String,
  val message: String,
) {
  EMAIL_CHANGED("email-changed", EMAIL_CHANGED_CONFIRMATION),
  RESENT("resent", RESEND_CONFIRMATION),
  INVITE_ACCEPTED("invite-accepted", INVITE_ACCEPTED_CONFIRMATION),
  INVITE_NOT_ACCEPTED("invite-not-accepted", INVITE_NOT_ACCEPTED_NOTICE),
  CLAIM_PENDING("claim-pending", CLAIM_PENDING_NOTICE),
  CLAIM_SENT("claim-sent", CLAIM_SENT_NOTICE),
  CLAIM_NOT_SENT("claim-not-sent", CLAIM_NOT_SENT_NOTICE),
  ;

  companion object {
    /** The notice [key] names, or `null` for an absent or unknown one. */
    fun fromKey(key: String?): WebNotice? = entries.firstOrNull { it.key == key }
  }
}

/**
 * One post-redirect-get confirmation, or nothing at all when [notice] is null.
 *
 * The same null-or-render shape as [formError] and a primitive for the same
 * reason: it is the mechanism every successful POST in this slice ends in, and
 * it had more call sites than [formError] while being hand-written at each.
 */
fun FlowContent.notice(notice: WebNotice?) {
  if (notice == null) return
  p(NOTICE_CLASS) { +notice.message }
}

/**
 * One auth form card: the heading, the page's own intro, the form-level error,
 * the POST form, and whatever the page adds after it.
 *
 * The ORDER is the contract — a form-level error rendered BELOW its controls is
 * an error a family submits past — so it is stated once here rather than once
 * per page, exactly as [respondErrorPage] states the error-page shape for the
 * three error pages. Sign-in, registration and change-email had three verbatim
 * copies of this block.
 *
 * [csrf] has no default, so every card says which kind it is: a signed-in card
 * (change-email) passes its session's token and is built by [signedInForm]; a
 * signed-out door (sign-in, registration) passes `null`, having no session to
 * echo.
 */
fun FlowContent.authFormCard(
  heading: String,
  action: String,
  csrf: CsrfToken?,
  error: String? = null,
  intro: SECTION.() -> Unit = {},
  after: SECTION.() -> Unit = {},
  fields: FORM.() -> Unit,
) {
  section(AUTH_FORM_CLASS) {
    h1 { +heading }
    intro()
    formError(error)
    if (csrf == null) {
      form(action = action, method = FormMethod.post) { fields() }
    } else {
      signedInForm(action, csrf) { fields() }
    }
    after()
  }
}

/**
 * The labelled-control markup, written once: the explicit `for`/`id` pair, the
 * [FIELD_CLASS] wrapper the CSS targets, the `required` flag and the
 * autocomplete token.
 *
 * Private, because the CALLERS are the contract: [textField] carries a value and
 * [passwordField] has no `value` parameter at all.
 */
private fun FlowContent.labelledField(
  name: String,
  labelText: String,
  type: InputType,
  autocompleteToken: String,
  value: String?,
) {
  div(FIELD_CLASS) {
    label {
      htmlFor = name
      +labelText
    }
    input(type = type, name = name) {
      id = name
      if (value != null) this.value = value
      required = true
      attributes["autocomplete"] = autocompleteToken
    }
  }
}

/**
 * One labelled text-like control.
 *
 * The label is explicit (`for`/`id`) rather than wrapping, so the association
 * survives the CSS moving either element, and [autocompleteToken] is passed
 * through verbatim so a browser and a password manager can fill the field —
 * a sign-in form a password manager cannot recognise is one families type by
 * hand every time.
 *
 * [value] is `null` when the field has no prior value, so no `value` attribute
 * is rendered at all — distinct from a value the family submitted as empty. It
 * is never rendered for a password: [passwordField] below has no value
 * parameter AT ALL rather than one defaulted to null, because a password
 * re-rendered into the HTML reaches the browser's back/forward cache and any
 * "view source" on a shared machine.
 */
fun FlowContent.textField(
  name: String,
  labelText: String,
  type: InputType,
  autocompleteToken: String,
  value: String? = null,
) = labelledField(name, labelText, type, autocompleteToken, value)

/** A labelled password control. It has no `value` parameter on purpose; see [textField]. */
fun FlowContent.passwordField(
  name: String,
  labelText: String,
  autocompleteToken: String,
) = labelledField(name, labelText, InputType.password, autocompleteToken, value = null)

/** One choice of a [selectField]: the [value] the form posts and the [label] a person reads. */
data class SelectOption(
  val value: String,
  val label: String,
)

/**
 * One labelled `<select>` (RFC 268), in [labelledField]'s markup: the explicit
 * `for`/`id` pair inside the [FIELD_CLASS] wrapper.
 *
 * It always opens with a BLANK option, posting `""` and reading [blankLabel],
 * so "nothing chosen yet" is a state the control can show and the form can
 * receive — a select that started on its first real option would answer a
 * question the family never did. [selected] is the posted or drafted value; a
 * value no option carries selects the blank one.
 *
 * [errors] render inside the wrapper, under the control they are about, and
 * [caption] says what the answer is for.
 */
fun FlowContent.selectField(
  name: String,
  labelText: String,
  options: List<SelectOption>,
  selected: String?,
  blankLabel: String = "",
  required: Boolean = false,
  caption: String? = null,
  errors: List<String> = emptyList(),
) {
  div(FIELD_CLASS) {
    label {
      htmlFor = name
      +labelText
    }
    select {
      this.name = name
      id = name
      this.required = required
      option {
        value = ""
        this.selected = options.none { it.value == selected }
        +blankLabel
      }
      for (choice in options) {
        option {
          value = choice.value
          this.selected = choice.value == selected
          +choice.label
        }
      }
    }
    if (caption != null) p(FIELD_CAPTION_CLASS) { +caption }
    formErrors(errors)
  }
}
