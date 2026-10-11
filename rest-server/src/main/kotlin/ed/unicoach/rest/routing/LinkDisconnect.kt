package ed.unicoach.rest.routing

import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.error.FieldError
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.respondValidationFailed
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/** The path parameter both disconnect doors (RFC 249) name a link by. */
const val LINK_ID_PARAM = "linkId"

/**
 * The disconnect, after the caller is resolved — ONE answer for both ends'
 * doors (RFC 249): `400` for a [linkId] the door could not read as a link id,
 * then `204`, or `404 supporter_link_not_found` for every link the caller does
 * not hold live.
 */
internal suspend fun RoutingContext.respondDisconnect(
  linkId: SupporterStudentLinkId?,
  disconnect: suspend (SupporterStudentLinkId) -> Result<UnlinkOutcome>,
) {
  if (linkId == null) {
    return respondValidationFailed(listOf(FieldError(LINK_ID_PARAM, "Not a link id: [${call.pathParameters[LINK_ID_PARAM]}]")))
  }

  when (disconnect(linkId).getOrThrow()) {
    is UnlinkOutcome.Unlinked -> {
      call.respond(HttpStatusCode.NoContent)
    }

    UnlinkOutcome.NotFound -> {
      call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.SUPPORTER_LINK_NOT_FOUND, "No such link"))
    }
  }
}
