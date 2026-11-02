package ed.unicoach.account

import ed.unicoach.db.Database
import ed.unicoach.db.dao.AccountErasureDao
import ed.unicoach.db.dao.AccountNotClosedException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.models.UserId
import org.slf4j.LoggerFactory

/**
 * Erases one closed account (RFC 266, slice deletion/03/the-walk, brief 0002
 * D1/D3/D10): every personal value NULL, every key onto the account moved to
 * its deleted sibling, D10's rows deleted. The walk is `erase_account` in the
 * schema; this service runs it in ONE transaction, so nothing is ever
 * half-erased, and names the two refusals as outcomes.
 *
 * Used by the operator's door in admin-web; deletion/04's sweep job calls the
 * same method.
 */
class AccountErasureService(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(AccountErasureService::class.java)

  suspend fun erase(userId: UserId): Result<ErasureOutcome> {
    val erased = database.withConnectionCatching { session -> AccountErasureDao.erase(session, userId).getOrThrow() }
    return erased.fold(
      onSuccess = { tables ->
        // One line per erasure, the user id and the counts only -- no personal
        // value -- so the first production erasure reads back from the log.
        logger.info(
          "Erased account [{}]: {}",
          userId.asString,
          tables
            .joinToString(
              ", ",
            ) { "${it.table} moved=${it.moved} erased=${it.erased} deleted=${it.deleted}" }
            .ifEmpty { "nothing left to erase" },
        )
        Result.success(ErasureOutcome.Erased(tables))
      },
      onFailure = { failure ->
        when (failure) {
          is AccountNotClosedException -> Result.success(ErasureOutcome.NotClosed)
          is NotFoundException -> Result.success(ErasureOutcome.NotFound)
          else -> Result.failure(failure)
        }
      },
    )
  }
}
