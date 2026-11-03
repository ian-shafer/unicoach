package ed.unicoach.account

import ed.unicoach.common.json.asJson
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AccountErasureDao
import ed.unicoach.db.dao.AccountNotClosedException
import ed.unicoach.db.dao.CloseMatch
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailAccountKey
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Erases one closed account (RFC 266, slice deletion/03/the-walk, brief 0002
 * D1/D3/D10) and tells the person (RFC 267, deletion/04/the-sweep, D5): every
 * personal value NULL, every key onto the account moved to its deleted
 * sibling, D10's rows deleted, and one `ACCOUNT_ERASED` email queued to the
 * account's verified address. The walk is `erase_account` in the schema.
 *
 * Each call is ONE transaction, in this order: the account's `users` row is
 * locked; an erased account answers [ErasureOutcome.AlreadyErased] and nothing
 * else runs, so a duplicate job or a re-run sends no second email;
 * [eraseClose] then checks the close it was scheduled for; the address is read
 * before the walk NULLs it; the walk; and, last, the email enqueue. The
 * enqueue is REQUIRED (ASYNC_WORK.md rule 2): a failed one throws, so the
 * erasure rolls back whole with it.
 *
 * Two doors: the operator's ([erase], admin-web) and the erase job's
 * ([eraseClose], [AccountErasureHandler]). Both confirm (decision D-G). Each
 * answers only what it can: [CloseErasureOutcome.CloseSuperseded] is the job's
 * alone.
 */
class AccountErasureService(
  private val database: Database,
  private val queueService: QueueService,
) {
  private val logger = LoggerFactory.getLogger(AccountErasureService::class.java)

  /**
   * The admin door: erases [userId] whatever close it is under. A live account
   * is the walk's own refusal, answered as [ErasureOutcome.NotClosed].
   */
  suspend fun erase(userId: UserId): Result<ErasureOutcome> =
    transaction(userId) { session -> settled(session, userId) ?: walk(session, userId) }
      .recoverCatching { failure -> if (failure is AccountNotClosedException) ErasureOutcome.NotClosed else throw failure }

  /**
   * The job's door: erases [userId] only while it is still closed by the close
   * at [closedAt] -- the one the job was scheduled for. Reopened answers
   * [ErasureOutcome.NotClosed]; closed again, [CloseErasureOutcome.CloseSuperseded].
   */
  suspend fun eraseClose(
    userId: UserId,
    closedAt: Instant,
  ): Result<CloseErasureOutcome> =
    transaction(userId) { session ->
      settled(session, userId) ?: when (AccountErasureDao.closedAt(session, userId, closedAt).getOrThrow()) {
        CloseMatch.NO_ACCOUNT -> ErasureOutcome.NotFound
        CloseMatch.LIVE -> ErasureOutcome.NotClosed
        CloseMatch.ANOTHER_CLOSE -> CloseErasureOutcome.CloseSuperseded
        CloseMatch.THIS_CLOSE -> walk(session, userId)
      }
    }

  /** One erase transaction; an erasure is logged once it commits. */
  private suspend fun <O : CloseErasureOutcome> transaction(
    userId: UserId,
    block: (SqlSession) -> O,
  ): Result<O> =
    database.withConnectionCatching(block).onSuccess { outcome ->
      if (outcome is ErasureOutcome.Erased) log(userId, outcome)
    }

  /**
   * The first steps of either door: locks the account's `users` row, then
   * answers when this call has nothing to erase -- no account, or one already
   * erased. Null when the erasure goes on.
   */
  private fun settled(
    session: SqlSession,
    userId: UserId,
  ): ErasureOutcome? =
    when {
      !AccountErasureDao.lock(session, userId).getOrThrow() -> ErasureOutcome.NotFound
      AccountErasureDao.isErased(session, userId).getOrThrow() -> ErasureOutcome.AlreadyErased
      else -> null
    }

  /** The erasure itself: the address read before the walk NULLs it, the walk, then the confirmation. */
  private fun walk(
    session: SqlSession,
    userId: UserId,
  ): ErasureOutcome.Erased {
    val address = verifiedAddress(session, userId)
    val tables = AccountErasureDao.erase(session, userId).getOrThrow()
    val confirmation = if (address != null) confirm(session, userId, address) else ErasureConfirmation.NO_VERIFIED_ADDRESS
    return ErasureOutcome.Erased(tables, confirmation)
  }

  /**
   * The closed, not yet erased account's address when it is verified; null when
   * it is not (it was never shown to belong to the person, decision D-B) or
   * there is no such user. Read through [UsersDao], the one home of the
   * `users` row's mapping and of what "verified" means.
   */
  private fun verifiedAddress(
    session: SqlSession,
    userId: UserId,
  ): EmailAddress? =
    UsersDao
      .findByIdOrNull(session, userId, SoftDeleteScope.ALL)
      .getOrThrow()
      ?.takeIf { it.emailVerifiedAt != null }
      ?.email

  /** The last statement of the erasure: the email that says the account is deleted, keyed on the erased id. */
  private fun confirm(
    session: SqlSession,
    userId: UserId,
    address: EmailAddress,
  ): ErasureConfirmation {
    val payload =
      EmailJobPayload(
        to = address.value,
        template = EmailTemplate.ACCOUNT_ERASED,
        context = JsonObject(emptyMap()),
        account = EmailAccountKey.Deleted(userId),
      )
    queueService.enqueueOrThrow(session, JobType.SEND_EMAIL, payload.asJson())
    return ErasureConfirmation.ENQUEUED
  }

  // One line per erasure, the user id, the counts and the confirmation only --
  // no personal value -- so the first production erasure reads back from the log.
  private fun log(
    userId: UserId,
    erased: ErasureOutcome.Erased,
  ) {
    logger.info(
      "Erased account [{}] ({}): {}",
      userId.asString,
      erased.confirmation,
      erased.tables.joinToString(", ") { "${it.table} moved=${it.moved} erased=${it.erased} deleted=${it.deleted}" },
    )
  }
}
