package ed.unicoach.email

import ed.unicoach.db.models.UserId
import ed.unicoach.error.PermanentError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * The generic payload of a [JobType.SEND_EMAIL] job (RFC 96): intent, not
 * rendered bytes. The worker resolves the renderer for [template], renders
 * [context], and sends to [to].
 *
 * The recipient's address travels in the payload rather than being looked up
 * from [account]: the job must serve recipients who are not users,
 * verification binds to the point-in-time address the token was issued for
 * (not the user's current address), and the worker stays free of domain
 * lookups.
 *
 * [account] is the account the send is recorded against on its `email_sends`
 * row, so erasure can reach it (RFC 263): a live account, or the erased account
 * a send is the receipt of (RFC 267, the deletion confirmation); null for a
 * non-account recipient. One value, so a payload naming two accounts cannot be
 * built. It has no default, so every producer states it: omitting it does not
 * compile.
 *
 * A NULL `email_sends.user_id` therefore means only "no account is known to own
 * this send", and does not say why: a non-account recipient, a job queued before
 * RFC 263, or a send logged before RFC 263 whose address the migration's
 * backfill matched to no account or to more than one
 * (`db/schema/classify-email-sends.sql`).
 *
 * The wire shape is [EmailJobPayloadSerializer]'s, unchanged by RFC 267 so the
 * jobs already queued still decode.
 */
@Serializable(with = EmailJobPayloadSerializer::class)
data class EmailJobPayload(
  val to: String,
  val template: EmailTemplate,
  val context: JsonObject,
  val account: EmailAccountKey?,
)

/**
 * [EmailJobPayload] on the wire: the account as two keys, `userId` (live,
 * RFC 263) and `deletedUserId` (erased, RFC 267), as every queued job already
 * carries it. `userId` is always written (null for no account); `deletedUserId`
 * only when set, so a payload without one reads exactly as before RFC 267. A job
 * queued before RFC 263 has no `userId` key at all; the worker's decoder
 * ([EmailSendHandler]) reads that as null, so it is still sent and logged with a
 * NULL key.
 *
 * A wire payload naming both keys, or an id that is not a UUID, does not decode
 * (an [InvalidAccountKeyException] naming it): a send is recorded against one account
 * or none, and a malformed id would log mail erasure cannot reach.
 */
@Serializable
private class EmailJobPayloadWire(
  val to: String,
  val template: EmailTemplate,
  val context: JsonObject,
  val userId: String?,
  val deletedUserId: String? = null,
)

internal object EmailJobPayloadSerializer : KSerializer<EmailJobPayload> {
  private val wire = EmailJobPayloadWire.serializer()

  override val descriptor: SerialDescriptor = wire.descriptor

  override fun serialize(
    encoder: Encoder,
    value: EmailJobPayload,
  ) {
    val (userId, deletedUserId) =
      when (val account = value.account) {
        is EmailAccountKey.Live -> account.userId.asString to null
        is EmailAccountKey.Deleted -> null to account.userId.asString
        null -> null to null
      }
    encoder.encodeSerializableValue(wire, EmailJobPayloadWire(value.to, value.template, value.context, userId, deletedUserId))
  }

  override fun deserialize(decoder: Decoder): EmailJobPayload {
    val payload = decoder.decodeSerializableValue(wire)
    return EmailJobPayload(payload.to, payload.template, payload.context, account(payload))
  }

  private fun account(payload: EmailJobPayloadWire): EmailAccountKey? {
    val live = payload.userId
    val deleted = payload.deletedUserId
    return when {
      live != null && deleted != null -> {
        throw InvalidAccountKeyException.NamesBothAccounts(payload.to, payload.template, live, deleted)
      }

      live != null -> {
        EmailAccountKey.Live(accountId(payload, live))
      }

      deleted != null -> {
        EmailAccountKey.Deleted(accountId(payload, deleted))
      }

      else -> {
        null
      }
    }
  }

  private fun accountId(
    payload: EmailJobPayloadWire,
    raw: String,
  ): UserId =
    try {
      UserId(UUID.fromString(raw))
    } catch (e: IllegalArgumentException) {
      throw InvalidAccountKeyException.NotAUuid(payload.to, payload.template, raw, e)
    }
}

/**
 * A wire payload whose account key does not decode, its parts kept as
 * properties. A [SerializationException], so a decoder sees it as the
 * malformed payload it is; a [PermanentError], because the same payload never
 * decodes on a retry, and sending without the key would log mail erasure
 * cannot reach.
 */
internal sealed class InvalidAccountKeyException(
  val to: String,
  val template: EmailTemplate,
  message: String,
  cause: Throwable? = null,
) : SerializationException(message, cause),
  PermanentError {
  /** Both `userId` and `deletedUserId` are set: a send is recorded against one account or none. */
  class NamesBothAccounts(
    to: String,
    template: EmailTemplate,
    val live: String,
    val deleted: String,
  ) : InvalidAccountKeyException(
      to,
      template,
      "Recipient [$to] for template [$template] names both account [$live] and erased account [$deleted]",
    )

  /** The account id [raw] is not a UUID. */
  class NotAUuid(
    to: String,
    template: EmailTemplate,
    val raw: String,
    cause: IllegalArgumentException,
  ) : InvalidAccountKeyException(to, template, "Invalid recipient account id [$raw] for [$to] and template [$template]", cause)
}
