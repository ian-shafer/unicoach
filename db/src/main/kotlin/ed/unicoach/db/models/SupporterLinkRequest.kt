package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress
import java.time.Instant

/**
 * One row of `supporter_link_requests` (RFC 254): a parent who opened a
 * student's Family Cost Report link asked to connect with that student.
 */
data class SupporterLinkRequest(
  override val id: SupporterLinkRequestId,
  val supporterId: SupporterId,
  val studentId: StudentId,
  val costReportShareId: CostReportShareId,
  /**
   * When the student's notice was DECIDED — an email enqueued, or deliberately
   * not sent (an opted-out, unverified or closed student). Not proof an email
   * went out.
   */
  val notifiedAt: Instant?,
  val state: SupporterLinkRequestState,
  override val createdAt: Instant,
) : Identifiable<SupporterLinkRequestId>,
  Created

/**
 * Where a request stands. Mirrors the table's resolution CHECKs: a resolved row
 * has its `resolved_at`, and only an approval names the link it made.
 */
sealed interface SupporterLinkRequestState {
  data object Pending : SupporterLinkRequestState

  data class Approved(
    val resolvedAt: Instant,
    val linkId: SupporterStudentLinkId,
  ) : SupporterLinkRequestState

  data class Declined(
    val resolvedAt: Instant,
  ) : SupporterLinkRequestState

  data class Cancelled(
    val resolvedAt: Instant,
  ) : SupporterLinkRequestState
}

/** What a new, pending request is made from. */
data class NewSupporterLinkRequest(
  val supporterId: SupporterId,
  val studentId: StudentId,
  val costReportShareId: CostReportShareId,
)

/**
 * One PENDING request as the STUDENT reads it (RFC 254): the request to act on,
 * and the claimant's name and email from their own live, verified account.
 *
 * The email is here on purpose, unlike [LinkedSupporter]: the link is not proof
 * of who forwarded it, so the student decides with the address in front of them.
 */
data class PendingSupporterLinkRequest(
  val requestId: SupporterLinkRequestId,
  val supporterId: SupporterId,
  val name: PersonName,
  val email: EmailAddress,
  val requestedAt: Instant,
)
