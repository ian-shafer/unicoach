package ed.unicoach.web.render

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
import kotlinx.html.p
import kotlinx.html.section
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
 * [path], carrying [token] on the one query key every invite door reads, or
 * [path] alone when no invite is in flight (RFC 232).
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
): String = if (token == null) path else "$path?$TOKEN_FIELD=${token.encodeURLParameter()}"

/** The hidden field that carries [token] through a form POST, or nothing when there is none. */
fun FlowContent.tokenField(token: String?) {
  if (token == null) return
  hiddenInput(name = TOKEN_FIELD) { value = token }
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
 */
fun FlowContent.authFormCard(
  heading: String,
  action: String,
  error: String? = null,
  intro: SECTION.() -> Unit = {},
  after: SECTION.() -> Unit = {},
  fields: FORM.() -> Unit,
) {
  section(AUTH_FORM_CLASS) {
    h1 { +heading }
    intro()
    formError(error)
    form(action = action, method = FormMethod.post) { fields() }
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
