package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue
import ed.unicoach.db.models.SupporterRelationship
import java.time.Instant

/**
 * `POST /api/v1/supporter-invites` body (RFC 232).
 *
 * [relationship] is optional and defaults to `parent`. Nullable with no default
 * on the wire field so a missing value is an ordinary absence rather than a
 * silently invented relationship.
 */
data class CreateSupporterInviteRequest(
  val relationship: String? = null,
)

/**
 * The relationship this body asks for, or `parent` when it names none (RFC 232).
 * `null` means the body named a word the vocabulary has no reading of, which is
 * the one case the door refuses.
 *
 * On the BODY and not in the route handler: decoding a wire field, and knowing
 * what its absence means, is the request model's own business — the ONE place
 * the product default is applied.
 *
 * The default is decided on the FIELD's absence and NOT on the decode result.
 * `relationship?.let(::fromValue) ?: PARENT` folds the two answers into one
 * elvis, so `{"relationship":"uncle"}` minted a link labelled `parent` and the
 * door's 400 arm was unreachable dead code.
 */
fun CreateSupporterInviteRequest.relationshipOrDefault(): SupporterRelationship? =
  when (relationship) {
    null -> SupporterRelationship.PARENT
    else -> SupporterRelationship.fromValue(relationship)
  }

/** `POST /api/v1/supporter-invites/accept` body (RFC 232). */
data class AcceptSupporterInviteRequest(
  val token: String,
)

/**
 * The invite the mint returns; the raw token rides only in [inviteUrl].
 *
 * [expiresAt] is the INSTANT, not a sentence about one. Jackson writes it as
 * ISO-8601 here exactly as it does for `SubscriptionVerifyResponse.currentPeriodEnd`,
 * so the wire bytes are unchanged and no client has to re-parse a string this
 * side formatted eagerly.
 */
data class SupporterInviteResponse(
  val id: String,
  val inviteUrl: String,
  val inviterRole: String,
  val relationship: String,
  val expiresAt: Instant,
)

/**
 * The outcomes `POST /api/v1/supporter-invites/accept` publishes (RFC 232).
 *
 * A TYPE and not a bare `String`: the one legal word used to live as a literal
 * in the handler body and a second literal in the suite that pinned it, so two
 * copies of one wire value could only disagree by a green test asserting the
 * old word. `@get:JsonValue` keeps the wire bytes what they were.
 */
enum class AcceptSupporterInviteStatus(
  @get:JsonValue val value: String,
) {
  ACCEPTED("accepted"),
}

/** The accept confirmation (RFC 232). */
data class AcceptSupporterInviteResponse(
  val status: AcceptSupporterInviteStatus,
  val linkId: String,
)
