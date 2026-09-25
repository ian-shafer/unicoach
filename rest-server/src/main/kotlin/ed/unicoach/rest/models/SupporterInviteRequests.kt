package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue
import ed.unicoach.db.models.SupporterRelationship
import java.time.Instant

/**
 * `POST /api/v1/supporter-invites` body (RFC 232).
 *
 * [relationship] is optional and defaults to `supporter` (RFC 240). Nullable
 * with no default on the wire field so a missing value is an ordinary absence
 * rather than a silently invented relationship.
 */
data class CreateSupporterInviteRequest(
  val relationship: String? = null,
)

/**
 * The relationship this body asks for, or `supporter` when it names none
 * (RFC 232, reworded by RFC 240). `null` means the body named a word the
 * vocabulary has no reading of, which is the one case the door refuses.
 *
 * On the BODY and not in the route handler: decoding a wire field, and knowing
 * what its absence means, is the request model's own business — the ONE place
 * the product default is applied.
 *
 * The default is decided on the FIELD's absence and NOT on the decode result.
 * `relationship?.let(::fromValue) ?: PARENT` folds the two answers into one
 * elvis, so `{"relationship":"uncle"}` minted a link labelled `parent` and the
 * door's 400 arm was unreachable dead code. Those two answers stay separately
 * decided here.
 *
 * The absence arm reads [SupporterRelationship.UNSTATED] — the ONE home of "the
 * word a door mints when nobody stated one" (RFC 240) — and not `PARENT`. No
 * door states a relationship today — iOS sends nothing and the coach has no
 * honest way to ask — so `PARENT` told every recipient they had been named a
 * parent when nobody had named them anything. A word nobody stated must not
 * read as a claim about a family. The chat door reads the same value, so the
 * `/invite` page's label cannot be true of one door and false of the other.
 */
fun CreateSupporterInviteRequest.relationshipOrDefault(): SupporterRelationship? =
  when (relationship) {
    null -> SupporterRelationship.UNSTATED
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
