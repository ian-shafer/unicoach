package ed.unicoach.worker

import ed.unicoach.appstore.AppStoreConfig
import ed.unicoach.appstore.AppStoreServerApiFactory
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.VerificationEmailRenderer
import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.LlmPriceBook
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
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
import ed.unicoach.college.Codebook
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
import ed.unicoach.subscriptions.SubscriptionPlans
import ed.unicoach.subscriptions.SubscriptionRefreshHandler
import ed.unicoach.subscriptions.SubscriptionService
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() {
  val config =
    AppConfig
      .load(
        "common.conf",
        "db.conf",
        "service.conf",
        "chat.conf",
        "queue.conf",
        "queue-worker.conf",
        "net.conf",
        "email.conf",
        "appstore.conf",
      ).getOrThrow()

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

  // ONE plans table for the whole process (RFC 112): the budget gate's
  // subscribed branch and the refresh path's plan check must agree about which
  // products exist, or a refresh would record a row the gate then fails closed on.
  val subscriptionPlans = SubscriptionPlans.from(config).getOrThrow()

  // One BudgetService for all three passes (RFC 109): they gate on the same
  // meter against the same allowance, so a student blocked from one is blocked
  // from all. SubscriptionPlans (RFC 110) rides beside BudgetConfig so the
  // worker's gate reads the same subscribed branch as the chat gate. Built
  // beside the sibling configs, OUTSIDE the enabled-pass gate, so a malformed
  // budget or plans block fails boot even when every pass is off.
  val budgetService = BudgetService(database, BudgetConfig.from(config).getOrThrow(), subscriptionPlans)

  // The worker is the sole transmitter of outbound email (RFC 96), so it is the
  // only process that constructs an EmailProvider/EmailService. verifyUrlBase
  // derives from service.conf, already loaded here.
  val emailConfig = EmailConfig.from(config).getOrThrow()
  val emailProvider = EmailProviderFactory.fromConfig(emailConfig).getOrThrow()
  val emailService = EmailService(database, emailProvider, emailConfig)
  val verifyUrlBase = EmailVerificationConfig.from(config).getOrThrow().verifyUrlBase

  // The App Store client the refresh handler reads authoritative state through
  // (RFC 112). Built unconditionally: absent credentials are a valid
  // unconfigured state whose lookups answer Unavailable, and in dev and test no
  // notification arrives, so no job ever runs one. The client's lifetime belongs
  // to this root — closed in the `finally` below, beside emailProvider, and by
  // the boot guard below if it is never reached.
  val appStore = AppStoreServerApiFactory.fromConfig(AppStoreConfig.from(config).getOrThrow())

  // Everything from here until the `try` below can still throw — an unpriced
  // model, a rejected chat config — and the `finally` that closes the client only
  // becomes live once that `try` is entered. So the client's cleanup travels with
  // its allocation rather than depending on reaching the `try`: any failure in the
  // rest of the boot closes it here. Closing twice (this path plus a `finally`
  // that did run) is harmless — HttpClient.close is idempotent.
  val worker =
    runCatching {
      // The frozen-cost price book (RFC 108). Built OUTSIDE the enabled-pass gate so
      // a malformed llmPricing block fails boot even when every LLM pass is disabled;
      // the explicit-pricing check on the enabled models runs inside the gate below,
      // where the enabled subset is known.
      val priceBook = LlmPriceBook.from(config).getOrThrow()

      val handlers =
        buildList<JobHandler> {
          add(SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold))

          // Registered unconditionally (no `enabled` gate, alongside SessionExpiryHandler):
          // email verification is not an optional feature — registration is broken
          // without it. When EMAIL_PROVIDER is unset the packaged `provider = "log"`
          // default is used, so the handler still runs (logging instead of transmitting).
          add(EmailSendHandler(emailService, listOf(VerificationEmailRenderer(verifyUrlBase))))

          // Registered unconditionally too (RFC 112): subscriptions are not an
          // optional coaching feature, so this sits outside the enabled-pass gate
          // below. Without it a REFRESH_SUBSCRIPTION the webhook enqueued would sit
          // unhandled and every paying subscriber's row would go stale on renewal.
          add(SubscriptionRefreshHandler(SubscriptionService(database, appStore.api, subscriptionPlans)))

          // The worker is the only place a ChatProvider is built for the LLM job
          // handlers; build it once when extraction (RFC 66), synthesis (RFC 93), or
          // fit-lens (RFC 98) is enabled, wrap it in the LlmCallLog seam (RFC 106) so
          // no handler receives the raw provider, then register each under its switch.
          if (extractionConfig.enabled || synthesisConfig.enabled || fitLensConfig.enabled) {
            // Only the enabled passes' models must be explicitly priced; an env
            // override to an unpriced id fails boot rather than metering at the
            // default rate (RFC 108).
            priceBook.requireExplicitlyPriced(enabledModels(extractionConfig, synthesisConfig, fitLensConfig)).getOrThrow()

            val llmCallLog =
              LlmCallLog(
                ChatProviderFactory
                  .fromConfig(ChatConfig.from(config).getOrThrow())
                  .getOrThrow(),
                database,
                priceBook,
              )

            if (extractionConfig.enabled) {
              add(ExtractionHandler(ExtractionService(database, llmCallLog, extractionConfig, budgetService)))
            }
            if (synthesisConfig.enabled) {
              add(SynthesisHandler(SynthesisService(database, llmCallLog, synthesisConfig, budgetService)))
              // The daily dispatcher (RFC 97) is gated by the same switch as the
              // per-student handler, so the SYNTHESIS_SWEEP producer and the
              // SYNTHESIZE_STUDENT consumer are present together or absent together —
              // a fired sweep never fans out into unhandled per-student jobs.
              add(SynthesisSweepHandler(database, QueueService(database, jobsDao)))
            }
            if (fitLensConfig.enabled) {
              // The published codebook, read once at wiring time (RFC 147): the
              // fit lens's query tool advertises the region words it carries.
              val codebook = runBlocking { Codebook.loadOrEmpty(database) }
              val fitLensService =
                FitLensService(database, llmCallLog, CollegeSearchService(database), fitLensConfig, budgetService, codebook)
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

      worker
    }.onFailure { appStore.client.close() }
      .getOrThrow()

  try {
    runBlocking {
      worker.start(this)
      awaitCancellation()
    }
  } finally {
    (emailProvider as? AutoCloseable)?.close()
    appStore.client.close()
    database.close()
  }
}

// Which LLM models are currently enabled, across the extraction, synthesis, and
// fit-lens passes — the subset that must be explicitly priced (RFC 108).
private fun enabledModels(
  extraction: ExtractionConfig,
  synthesis: SynthesisConfig,
  fitLens: FitLensConfig,
): List<String> =
  buildList {
    if (extraction.enabled) add(extraction.model)
    if (synthesis.enabled) add(synthesis.model)
    if (fitLens.enabled) add(fitLens.model)
  }
