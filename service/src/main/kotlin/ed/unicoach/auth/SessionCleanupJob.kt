package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SessionsDao
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class SessionCleanupJob(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(SessionCleanupJob::class.java)

  suspend fun execute() {
    logger.info("Initiating zombie session purge...")

    try {
      database.withConnection { session ->
        val result = SessionsDao.expireZombieSessions(session)
        if (result.isSuccess) {
          logger.info("Successfully completed zombie session purge.")
        } else {
          val ex = result.exceptionOrNull()
          val msg = ex?.message ?: "unknown error"
          logger.error("Database failure during zombie session purge: [$msg]", ex)
        }
      }
    } catch (e: Exception) {
      logger.error("Unexpected application failure during zombie session purge: [${e.message}]", e)
      exitProcess(1)
    }
  }
}
