package ed.unicoach.worker

import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.VerificationEmailRenderer
import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionHandler
import ed.unicoach.coaching.extraction.ExtractionService
import ed.unicoach.coaching.fitlens.FitLensConfig
import ed.unicoach.coaching.fitlens.FitLensHandler
import ed.unicoach.coaching.fitlens.FitLensService
import ed.unicoach.coaching.fitlens.FitLensSweepHandler
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.coaching.synthesis.SynthesisHandler
import ed.unicoach.coaching.synthesis.SynthesisService
import ed.unicoach.coaching.synthesis.SynthesisSweepHandler
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailProviderFactory
import ed.unicoach.email.EmailSendHandler
import ed.unicoach.email.EmailService
import ed.unicoach.net.NetConfig
import ed.unicoach.net.handlers.SessionExpiryHandler
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.QueueConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.QueueWorker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "service.conf", "chat.conf", "queue.conf", "queue-worker.conf", "net.conf", "email.conf")
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
  val fitLensConfig = FitLensConfig.from(config).getOrThrow()

  // The worker is the sole transmitter of outbound email (RFC 96), so it is the
  // only process that constructs an EmailProvider/EmailService. verifyUrlBase
  // derives from service.conf, already loaded here.
  val emailConfig = EmailConfig.from(config).getOrThrow()
  val emailProvider = EmailProviderFactory.fromConfig(emailConfig).getOrThrow()
  val emailService = EmailService(database, emailProvider, emailConfig)
  val verifyUrlBase = EmailVerificationConfig.from(config).getOrThrow().verifyUrlBase

  val handlers =
    buildList<JobHandler> {
      add(SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold))

      // Registered unconditionally (no `enabled` gate, alongside SessionExpiryHandler):
      // email verification is not an optional feature — registration is broken
      // without it. When EMAIL_PROVIDER is unset the packaged `provider = "log"`
      // default is used, so the handler still runs (logging instead of transmitting).
      add(EmailSendHandler(emailService, listOf(VerificationEmailRenderer(verifyUrlBase))))

      // The worker is the only place a ChatProvider is built for the LLM job
      // handlers; build it once when extraction (RFC 66), synthesis (RFC 93), or
      // fit-lens (RFC 98) is enabled, wrap it in the LlmCallLog seam (RFC 106) so
      // no handler receives the raw provider, then register each under its switch.
      if (extractionConfig.enabled || synthesisConfig.enabled || fitLensConfig.enabled) {
        val llmCallLog =
          LlmCallLog(
            ChatProviderFactory
              .fromConfig(ChatConfig.from(config).getOrThrow())
              .getOrThrow(),
            database,
          )

        if (extractionConfig.enabled) {
          add(ExtractionHandler(ExtractionService(database, llmCallLog, extractionConfig)))
        }
        if (synthesisConfig.enabled) {
          add(SynthesisHandler(SynthesisService(database, llmCallLog, synthesisConfig)))
          // The daily dispatcher (RFC 97) is gated by the same switch as the
          // per-student handler, so the SYNTHESIS_SWEEP producer and the
          // SYNTHESIZE_STUDENT consumer are present together or absent together —
          // a fired sweep never fans out into unhandled per-student jobs.
          add(SynthesisSweepHandler(database, QueueService(database, jobsDao)))
        }
        if (fitLensConfig.enabled) {
          val fitLensService = FitLensService(database, llmCallLog, CollegeSearchService(database), fitLensConfig)
          add(FitLensHandler(fitLensService))
          // The weekly dispatcher (RFC 98), gated by the same switch as the
          // per-student handler so the FIT_LENS_SWEEP producer and the FIT_LENS
          // consumer are present together or absent together.
          add(FitLensSweepHandler(database, QueueService(database, jobsDao)))
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
    (emailProvider as? AutoCloseable)?.close()
    database.close()
  }
}
