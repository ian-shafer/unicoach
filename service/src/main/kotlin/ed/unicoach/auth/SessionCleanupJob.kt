package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SessionDeleteResult
import ed.unicoach.db.dao.SessionsDao
import kotlin.system.exitProcess

class SessionCleanupJob(
  private val database: Database,
) {
  fun execute() {
    System.err.println("[INFO] Initiating zombie session purge...")

    try {
      database.withConnection { session ->
        when (val result = SessionsDao.expireZombieSessions(session)) {
          is SessionDeleteResult.Success -> {
            System.err.println("[INFO] Successfully completed zombie session purge.")
          }
          is SessionDeleteResult.DatabaseFailure -> {
            System.err.println("[ERROR] Database failure during zombie session purge: [${result.error.exception.message}]")
          }
        }
      }
    } catch (e: Exception) {
      System.err.println("[FATAL] Unexpected application failure during zombie session purge: [${e.message}]")
      exitProcess(1)
    }
  }
}
