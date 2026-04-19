package ed.unicoach.worker

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.QueueConfig
import ed.unicoach.queue.QueueWorker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "service.conf", "queue.conf", "queue-worker.conf")
      .getOrThrow()

  QueueConfig.from(config).getOrThrow()
  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)
  val jobsDao =
    ed.unicoach.queue.dao
      .JobsDao()

  val handlers =
    listOf<JobHandler>(
      // Concrete handlers registered here as they are implemented
      // in future specs.
    )

  val worker = QueueWorker(database, jobsDao, handlers)

  Runtime.getRuntime().addShutdownHook(
    Thread {
      worker.stop(timeout = 30.seconds)
    },
  )

  try {
    runBlocking {
      worker.start(this)
      // Block until cancelled by shutdown hook
      awaitCancellation()
    }
  } finally {
    database.close()
  }
}
