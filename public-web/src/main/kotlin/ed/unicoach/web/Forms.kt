package ed.unicoach.web

import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/**
 * The form helpers' own log. An unreadable body is a malformed CLIENT request,
 * re-rendered by its route — not a public-web failure — so it is a debug line
 * here, not an entry in the error log.
 */
private val formLog = LoggerFactory.getLogger("ed.unicoach.web.Forms")

/**
 * The submitted form, or `null` when the body was not one.
 *
 * `receiveParameters` throws for a body that is not form-encoded, and an
 * uncaught throw here is answered by the branded 503 — a server fault page for
 * a malformed client request. The CALLER re-renders its own form instead,
 * because which form to show is the route's knowledge and not this helper's.
 *
 * Read ONCE per request and kept on the call (RFC 265): the CSRF check in front
 * of a signed-in POST reads `_csrf` from this body, and the handler behind it
 * then reads its own fields from the same body, which the request stream can
 * deliver only once. A body that was not a form is kept as that answer too.
 */
internal suspend fun ApplicationCall.formParametersOrNull(): Parameters? {
  attributes.getOrNull(FormBodyKey)?.let { return it.parameters }
  val parameters =
    runCatching { receiveParameters() }
      .onFailure { cause -> formLog.debug("unreadable form body on [{}]", request.path(), cause) }
      .getOrNull()
  attributes.put(FormBodyKey, FormBody(parameters))
  return parameters
}

/** One request's form body as [formParametersOrNull] read it: `null` parameters for a body that was not a form. */
private class FormBody(
  val parameters: Parameters?,
)

private val FormBodyKey = AttributeKey<FormBody>("ed.unicoach.web.FormBody")

/**
 * The one value [name] carries in this form body.
 *
 * `null` for a field that is absent OR repeated — neither is a submission of the
 * form this module rendered, and `.orEmpty()` used to make both
 * indistinguishable from a family leaving the box blank. A blank box posts a
 * blank VALUE, which is a real submission and is passed through to `:service`;
 * an absent or repeated key is a request shape, and is re-rendered instead.
 */
internal fun Parameters.formField(name: String): String? = getAll(name)?.singleOrNull()

/** Why a posted control is not one the page's forms could have sent. */
internal enum class UnreadableMode {
  /** The control is absent, or posted more than once ([formField]). */
  NOT_ONE_VALUE,

  /** A value none of the control's options carries. */
  NOT_AN_OPTION,
}

/**
 * A posted [control] that is not a submission of the form this module rendered,
 * and why. Its value is never kept: these forms carry a family's answers, so a
 * route logs the control and the mode and nothing else.
 */
internal data class UnreadableControl(
  val control: String,
  val mode: UnreadableMode,
)

/** The one value [control] posted; an absent or repeated control is handed to [unreadable], which ends the reading. */
internal inline fun Parameters.oneValue(
  control: String,
  unreadable: (UnreadableControl) -> Nothing,
): String = formField(control) ?: unreadable(UnreadableControl(control, UnreadableMode.NOT_ONE_VALUE))
