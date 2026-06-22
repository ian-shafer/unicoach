package ed.unicoach.worker

import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionHandler
import ed.unicoach.coaching.extraction.ExtractionService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.net.NetConfig
import ed.unicoach.net.handlers.SessionExpiryHandler
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.QueueConfig
import ed.unicoach.queue.QueueWorker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "service.conf", "chat.conf", "queue.conf", "queue-worker.conf", "net.conf")
      .getOrThrow()

  QueueConfig.from(config).getOrThrow()
  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)
  val jobsDao =
    ed.unicoach.queue.dao
      .JobsDao()

  val netConfig = NetConfig.from(config).getOrThrow()
  val extractionConfig = ExtractionConfig.from(config).getOrThrow()

  val handlers =
    buildList<JobHandler> {
      add(SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold))

      // Extraction (RFC 66): registered only when enabled. The worker is the only
      // place a ChatProvider is built for extraction.
      if (extractionConfig.enabled) {
        val chatProvider =
          ChatProviderFactory
            .fromConfig(ChatConfig.from(config).getOrThrow())
            .getOrThrow()
        val extractionService = ExtractionService(database, chatProvider, extractionConfig)
        add(ExtractionHandler(extractionService))
      }
    }

  val worker = QueueWorker(database, jobsDao, handlers)

  Runtime.getRuntime().addShutdownHook(
    Thread {
      worker.stop(timeout = 30.seconds)
    },
  )

  try {
    runBlocking {
      worker.start(this)
      awaitCancellation()
    }
  } finally {
    database.close()
  }
}
