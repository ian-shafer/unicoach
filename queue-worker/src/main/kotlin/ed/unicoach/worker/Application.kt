package ed.unicoach.worker

import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionHandler
import ed.unicoach.coaching.extraction.ExtractionService
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.coaching.synthesis.SynthesisHandler
import ed.unicoach.coaching.synthesis.SynthesisService
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
  val synthesisConfig = SynthesisConfig.from(config).getOrThrow()

  val handlers =
    buildList<JobHandler> {
      add(SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold))

      // The worker is the only place a ChatProvider is built for the LLM job
      // handlers; build it once when either extraction (RFC 66) or synthesis
      // (RFC 93) is enabled, then register each handler under its own switch.
      if (extractionConfig.enabled || synthesisConfig.enabled) {
        val chatProvider =
          ChatProviderFactory
            .fromConfig(ChatConfig.from(config).getOrThrow())
            .getOrThrow()

        if (extractionConfig.enabled) {
          add(ExtractionHandler(ExtractionService(database, chatProvider, extractionConfig)))
        }
        if (synthesisConfig.enabled) {
          add(SynthesisHandler(SynthesisService(database, chatProvider, synthesisConfig)))
        }
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
