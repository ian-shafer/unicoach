package ed.unicoach.cron

import ed.unicoach.common.config.AppConfig
import ed.unicoach.cron.dao.PeriodicJobsDao
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * The `:cron` process (RFC 97). Loads config, builds [Database], [JobsDao],
 * [PeriodicJobsDao], and [PeriodicScheduler], starts the scheduler, and awaits —
 * the `queue-worker` `main` pattern. It registers no handlers; it only claims due
 * `periodic_jobs` rows and enqueues them. All domain work runs in the
 * `queue-worker` process.
 */
fun main() {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "queue.conf", "cron.conf")
      .getOrThrow()

  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)
  val jobsDao = JobsDao()
  val periodicJobsDao = PeriodicJobsDao()
  val cronSchedule = CronSchedule()

  val scheduler = PeriodicScheduler(database, jobsDao, periodicJobsDao, cronSchedule)

  Runtime.getRuntime().addShutdownHook(
    Thread {
      scheduler.stop(timeout = 30.seconds)
    },
  )

  try {
    runBlocking {
      scheduler.start(this)
      awaitCancellation()
    }
  } finally {
    database.close()
  }
}
