package ed.unicoach.family

import ed.unicoach.common.json.asJson
import ed.unicoach.common.json.deserialize
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SupporterLinkRequest
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.email.EmailTemplate
import ed.unicoach.email.EmailTemplateRenderer
import ed.unicoach.email.RenderedEmail
import ed.unicoach.email.renderedEmail
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * The render context of [EmailTemplate.LINK_REQUESTED] (RFC 254): who asked,
 * by the name and the verified address on their own account. Nothing about the
 * student, who is the one reading it.
 */
@Serializable
data class LinkRequestedContext(
  val claimantName: String,
  val claimantEmail: String,
)

/**
 * Tells a student that somebody asked to connect with them (RFC 254), on the
 * [LinkEndedNotice] pattern.
 *
 * The enqueue is REQUIRED (ASYNC_WORK.md rule 2): it runs on the caller's
 * [SqlSession] and throws when it fails, so the write that asked for it rolls
 * back with it.
 *
 * It goes to the student's `users.email` only when that address is VERIFIED
 * and the account is live — neither its `students` row nor its `users` row closed. A skipped send writes one INFO line naming the
 * request and the reason. Whether the student opted out of share offers is the
 * CALLER's decision ([SupporterLinkRequestService.notifyStudent]), not this
 * class's: it sends what it is asked to send.
 */
class LinkRequestedNotice(
  private val queueService: QueueService,
) {
  private val logger = LoggerFactory.getLogger(LinkRequestedNotice::class.java)

  fun tellStudent(
    session: SqlSession,
    request: SupporterLinkRequest,
  ) {
    val student = StudentsDao.findById(session, request.studentId, SoftDeleteScope.ALL).getOrThrow()
    val recipient = UsersDao.findById(session, student.userId, SoftDeleteScope.ALL).getOrThrow()
    val skipped =
      when {
        // Both closed shapes `ReportShares.lookup` names: the students row, or the users row (RFC 252).
        student.deletedAt != null -> "the student's profile is closed"

        recipient.deletedAt != null -> "the student's account is closed"

        recipient.emailVerifiedAt == null -> "the student's email is not verified"

        else -> null
      }
    if (skipped != null) {
      logger.info("Link-requested email for request [{}] not sent: {}", request.id.asString, skipped)
      return
    }
    val supporter = SupportersDao.findById(session, request.supporterId, SoftDeleteScope.ALL).getOrThrow()
    val claimant = UsersDao.findById(session, supporter.userId, SoftDeleteScope.ALL).getOrThrow()
    val payload =
      EmailJobPayload(
        to = recipient.email.value,
        template = EmailTemplate.LINK_REQUESTED,
        context = LinkRequestedContext(claimantName = claimant.name.value, claimantEmail = claimant.email.value).asJson(),
      )
    when (val result = queueService.enqueue(session, JobType.SEND_EMAIL, payload.asJson())) {
      is EnqueueResult.Success -> Unit

      // Thrown inside the caller's transaction, so it is why that claim or
      // verification rolled back: name the request it was for.
      is EnqueueResult.DatabaseFailure -> throw IllegalStateException(
        "Link-requested email enqueue failed: requestId=[${request.id.asString}] " +
          "studentId=[${request.studentId.value}] supporterId=[${request.supporterId.value}]",
        result.error,
      )
    }
  }
}

/** The subject line of [EmailTemplate.LINK_REQUESTED], named so a test asserts the constant. */
const val LINK_REQUESTED_SUBJECT = "Someone asked to connect with you on unicoach"

/** Renders [EmailTemplate.LINK_REQUESTED] (RFC 254 §2); registered in [ed.unicoach.emailRenderers]. */
class LinkRequestedEmailRenderer : EmailTemplateRenderer {
  override val template = EmailTemplate.LINK_REQUESTED

  override fun render(context: JsonObject): Result<RenderedEmail> {
    val claimant =
      runCatching { context.deserialize<LinkRequestedContext>() }
        .getOrElse { return Result.failure(it) }
    // The body names two things this module does not own: the iOS Settings
    // section titled "Family & Supporters" (SettingsView, FamilyConnectionsSection),
    // and the coach's `revoke_cost_report_share` tool. Renaming that section or
    // removing that tool makes these directions wrong, and no test notices.
    return renderedEmail(
      template = template,
      subject = LINK_REQUESTED_SUBJECT,
      body =
        "${claimant.claimantName} (${claimant.claimantEmail}) opened the Family Cost Report link you shared, " +
          "created a unicoach account, and asked to connect with you as a parent or supporter.\n\n" +
          "Nothing of yours is shared with them unless you approve. To approve or decline, open unicoach " +
          "and go to Settings → Family & Supporters.\n\n" +
          "If you don't know this person, decline. Your report link may have been forwarded. " +
          "You can ask your coach to turn the link off.",
    )
  }
}
