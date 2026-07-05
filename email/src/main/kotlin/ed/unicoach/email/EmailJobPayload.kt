package ed.unicoach.email

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The generic payload of a [JobType.SEND_EMAIL] job (RFC 96): intent, not
 * rendered bytes. The worker resolves the renderer for [template], renders
 * [context], and sends to [to].
 *
 * The recipient travels in the payload rather than a `userId`/`version`: the job
 * must serve recipients who are not users, verification binds to the
 * point-in-time address the token was issued for (not the user's current
 * address), and the worker stays free of domain lookups.
 */
@Serializable
data class EmailJobPayload(
  val to: String,
  val template: EmailTemplate,
  val context: JsonObject,
)
