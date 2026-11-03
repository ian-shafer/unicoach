package ed.unicoach.account

import ed.unicoach.common.json.asJson
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.User
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService

/**
 * Schedules the erasure of one close (RFC 267 §3): one ERASE_ACCOUNT job,
 * delayed by [AccountErasureConfig.grace], naming the account and the close by
 * its `deleted_at`, so a reopened and re-closed account is erased on the later
 * close's schedule, not this one's.
 *
 * The one home of that job for every writer of a close -- the app's
 * [AccountClosureService] and admin-web's Delete -- so no `users.deleted_at` is
 * set without its erasure. The enqueue is REQUIRED (ASYNC_WORK.md rule 2): it
 * runs on the closing transaction's [SqlSession] and throws when it fails, so a
 * close that cannot schedule its erasure does not close.
 */
class AccountErasureScheduler(
  private val queueService: QueueService,
  private val config: AccountErasureConfig,
) {
  /** [closed] is the row the close returned; its `deletedAt` is the close the job is for. */
  fun schedule(
    session: SqlSession,
    closed: User,
  ) {
    val closedAt = checkNotNull(closed.deletedAt) { "Asked to schedule the erasure of [${closed.id.asString}], which is not closed" }
    val payload = AccountErasurePayload(userId = closed.id.asString, closedAt = closedAt.toString())
    queueService.enqueueOrThrow(session, JobType.ERASE_ACCOUNT, payload.asJson(), delay = config.grace)
  }
}
