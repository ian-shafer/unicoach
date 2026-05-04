package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.DaoResult
import ed.unicoach.db.dao.SessionsDao
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class SessionCleanupJob(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(SessionCleanupJob::class.java)

  fun execute() {
    logger.info("Initiating zombie session purge...")

    try {
      database.withConnection { session ->
        when (val result = SessionsDao.expireZombieSessions(session)) {
          is DaoResult.Success -> {
            logger.info("Successfully completed zombie session purge.")
          }
          is DaoResult.TransientError -> {
            val msg = (result as? DaoResult.TransientError.DatabaseError)?.error?.exception?.message ?: "transient error"
            logger.error("Transient database failure during zombie session purge: [$msg]")
          }
          is DaoResult.PermanentError -> {
            val msg = (result as? DaoResult.PermanentError.DatabaseError)?.error?.exception?.message ?: "permanent error"
            logger.error("Database failure during zombie session purge: [$msg]")
          }
        }
      }
    } catch (e: Exception) {
      logger.error("Unexpected application failure during zombie session purge: [${e.message}]", e)
      exitProcess(1)
    }
  }
}

