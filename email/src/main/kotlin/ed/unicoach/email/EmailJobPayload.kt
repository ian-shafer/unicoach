package ed.unicoach.email

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The generic payload of a [JobType.SEND_EMAIL] job (RFC 96): intent, not
 * rendered bytes. The worker resolves the renderer for [template], renders
 * [context], and sends to [to].
 *
 * The recipient's address travels in the payload rather than being looked up
 * from [userId]: the job must serve recipients who are not users, verification
 * binds to the point-in-time address the token was issued for (not the user's
 * current address), and the worker stays free of domain lookups.
 *
 * [userId] is the account the mail is sent to, recorded on the `email_sends`
 * row so erasure can reach it (RFC 263); null for a non-account recipient. It
 * has no default, so every producer states it: omitting it does not compile. A
 * job queued before RFC 263 deployed has no `userId` key at all; the worker's
 * decoder ([EmailSendHandler]) reads that as null, so it is still sent and
 * logged with a NULL key.
 *
 * A NULL `email_sends.user_id` therefore means only "no account is known to own
 * this send", and does not say why: a non-account recipient, a job queued before
 * RFC 263, or a send logged before RFC 263 whose address the migration's
 * backfill matched to no account or to more than one
 * (`db/schema/classify-email-sends.sql`).
 */
@Serializable
data class EmailJobPayload(
  val to: String,
  val template: EmailTemplate,
  val context: JsonObject,
  val userId: String?,
)
