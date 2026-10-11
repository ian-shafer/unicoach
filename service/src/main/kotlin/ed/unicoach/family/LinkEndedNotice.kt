package ed.unicoach.family

import ed.unicoach.common.json.asJson
import ed.unicoach.common.json.deserialize
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.User
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
 * The render context of [EmailTemplate.LINK_ENDED] (RFC 252): two names and
 * nothing else. No field can carry WHY the link ended, so no render of it can
 * say.
 */
@Serializable
data class LinkEndedContext(
  val recipientName: String,
  val otherName: String,
)

/**
 * Tells the other person that a link ended (RFC 252 §3), on EVERY severance —
 * an account close and both RFC 249 disconnect doors — with one text, so
 * receiving it says nothing about the cause.
 *
 * The enqueue is REQUIRED (ASYNC_WORK.md rule 2): it runs on the severing
 * transaction's [SqlSession] and throws when it fails, so the sever rolls back
 * with it.
 *
 * It goes to the recipient's `users.email` only when that address is VERIFIED:
 * an invite can be accepted unverified (RFC 232, X8), and an unverified
 * address may belong to a stranger. A closed recipient is not written to
 * either. A skipped send writes one INFO line naming the link and the reason.
 */
class LinkEndedNotice(
  private val queueService: QueueService,
) {
  private val logger = LoggerFactory.getLogger(LinkEndedNotice::class.java)

  /** The student ended [link] (or closed their account): tell the supporter. */
  fun tellSupporter(
    session: SqlSession,
    link: SupporterStudentLink,
  ) = tell(session, link, recipient = supporterUser(session, link), other = studentUser(session, link))

  /** The supporter ended [link] (or closed their account): tell the student. */
  fun tellStudent(
    session: SqlSession,
    link: SupporterStudentLink,
  ) = tell(session, link, recipient = studentUser(session, link), other = supporterUser(session, link))

  private fun tell(
    session: SqlSession,
    link: SupporterStudentLink,
    recipient: User,
    other: User,
  ) {
    val skipped =
      when {
        recipient.deletedAt != null -> "the recipient's account is closed"
        recipient.emailVerifiedAt == null -> "the recipient's email is not verified"
        else -> null
      }
    if (skipped != null) {
      logger.info("Link-ended email for link [{}] not sent: {}", link.id.asString, skipped)
      return
    }
    val payload =
      EmailJobPayload(
        to = recipient.email.value,
        template = EmailTemplate.LINK_ENDED,
        context = LinkEndedContext(recipientName = recipient.name.value, otherName = other.name.value).asJson(),
      )
    when (val result = queueService.enqueue(session, JobType.SEND_EMAIL, payload.asJson())) {
      is EnqueueResult.Success -> Unit
      is EnqueueResult.DatabaseFailure -> throw result.error
    }
  }

  // ALL scopes: the severing transaction may already have closed this party's
  // role row, and an absent row is corruption that should fail the sever. The
  // failure names the link, the side and the missing row, since the DAO's own
  // NotFoundException names none of them; the DAO failure rides as the cause.
  private fun studentUser(
    session: SqlSession,
    link: SupporterStudentLink,
  ): User {
    val student =
      StudentsDao.findById(session, link.studentId, SoftDeleteScope.ALL).getOrElse {
        throw IllegalStateException("Link [${link.id.asString}] names student [${link.studentId.asString}], which has no row", it)
      }
    return UsersDao.findById(session, student.userId, SoftDeleteScope.ALL).getOrElse {
      throw IllegalStateException(
        "Link [${link.id.asString}]: student [${student.id.asString}] names user [${student.userId.asString}], which has no row",
        it,
      )
    }
  }

  private fun supporterUser(
    session: SqlSession,
    link: SupporterStudentLink,
  ): User {
    val supporter =
      SupportersDao.findById(session, link.supporterId, SoftDeleteScope.ALL).getOrElse {
        throw IllegalStateException("Link [${link.id.asString}] names supporter [${link.supporterId.asString}], which has no row", it)
      }
    return UsersDao.findById(session, supporter.userId, SoftDeleteScope.ALL).getOrElse {
      throw IllegalStateException(
        "Link [${link.id.asString}]: supporter [${supporter.id.asString}] names user [${supporter.userId.asString}], which has no row",
        it,
      )
    }
  }
}

/**
 * Renders [EmailTemplate.LINK_ENDED] (RFC 252 §3). Registered in the queue
 * worker beside the verification renderer.
 */
class LinkEndedEmailRenderer : EmailTemplateRenderer {
  override val template = EmailTemplate.LINK_ENDED

  override fun render(context: JsonObject): Result<RenderedEmail> {
    val names =
      runCatching { context.deserialize<LinkEndedContext>() }
        .getOrElse { return Result.failure(it) }
    return renderedEmail(
      template = template,
      subject = "A connection on unicoach has ended",
      body =
        "Hi ${names.recipientName},\n\n" +
          "You and ${names.otherName} are no longer connected on unicoach.\n\n" +
          "Nothing about your own account has changed — your conversations and everything " +
          "you have entered are still yours.\n\n" +
          "— unicoach",
    )
  }
}
