package ed.unicoach.web

import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
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
 */
internal suspend fun ApplicationCall.formParametersOrNull(): Parameters? =
  runCatching { receiveParameters() }
    .onFailure { cause -> formLog.debug("unreadable form body on [{}]", request.path(), cause) }
    .getOrNull()

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
