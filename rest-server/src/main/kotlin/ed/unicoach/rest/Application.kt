package ed.unicoach.rest

import ed.unicoach.appstore.AppStoreConfig
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreServerApiFactory
import ed.unicoach.appstore.AppleJwsVerifier
import ed.unicoach.appstore.AppleNotificationVerifier
import ed.unicoach.appstore.AppleTrustAnchorLoader
import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.IdTokenVerifierFactory
import ed.unicoach.auth.SsoProviderConfig
import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.CollegeChatTool
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.LlmPriceBook
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.QueueConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.plugins.SessionExpiryPlugin
import ed.unicoach.rest.plugins.configureClientKeyGate
import ed.unicoach.rest.plugins.configureEmailVerificationGate
import ed.unicoach.rest.plugins.configureRequestSizeLimit
import ed.unicoach.rest.plugins.configureSerialization
import ed.unicoach.rest.plugins.configureStatusPages
import ed.unicoach.subscriptions.SubscriptionPlans
import ed.unicoach.subscriptions.SubscriptionService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import ed.unicoach.web.common.logging.RequestLoggingConfig
import ed.unicoach.web.common.logging.configureRequestLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Boots the rest-server.
 *
 * [port] overrides the configured `server.port`. Production callers leave it
 * null to honour config; tests pass `0` to bind an ephemeral port and then read
 * the resolved port via `server.engine.resolvedConnectors()`, so concurrent test
 * runs across worktrees never collide on a fixed port.
 */
fun startServer(
  wait: Boolean = true,
  port: Int? = null,
): EmbeddedServer<*, *> {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "service.conf", "chat.conf", "appstore.conf", "rest-server.conf", "queue.conf")
      .getOrThrow()

  val dbConfig =
    DatabaseConfig
      .from(config)
      .getOrThrow()

  val sessionConfig =
    SessionConfig
      .from(config)
      .getOrThrow()

  val requestSizeConfig =
    RequestSizeConfig
      .from(config)
      .getOrThrow()

  val queueConfig =
    QueueConfig
      .from(config)
      .getOrThrow()

  val coachingConfig =
    CoachingConfig
      .from(config)
      .getOrThrow()

  val extractionConfig =
    ExtractionConfig
      .from(config)
      .getOrThrow()

  val budgetConfig =
    BudgetConfig
      .from(config)
      .getOrThrow()

  val clientKeyGateConfig =
    ClientKeyGateConfig
      .from(config)
      .getOrThrow()

  val requestLoggingConfig =
    RequestLoggingConfig
      .from(config)
      .getOrThrow()

  val emailVerificationConfig =
    EmailVerificationConfig
      .from(config)
      .getOrThrow()

  val googleTokenVerifier =
    IdTokenVerifierFactory
      .googleFromConfig(SsoProviderConfig.from(config, "auth.google").getOrThrow())
      .getOrThrow()

  val appleTokenVerifier =
    IdTokenVerifierFactory
      .appleFromConfig(SsoProviderConfig.from(config, "auth.apple").getOrThrow())
      .getOrThrow()

  val database = Database(dbConfig)

  // The frozen-cost price book (RFC 108). Built beside the sibling *Config.from
  // calls; requireExplicitlyPriced fails boot if the wired coaching model has no
  // explicit entry (an env override to an unpriced id), rather than silently
  // metering it at the default rate.
  val priceBook =
    LlmPriceBook
      .from(config)
      .getOrThrow()
  priceBook
    .requireExplicitlyPriced(listOf(coachingConfig.model))
    .getOrThrow()

  // The raw ChatProvider is named only here, wrapped immediately in the
  // LlmCallLog seam (RFC 106); no caller receives the pure provider.
  val llmCallLog =
    LlmCallLog(
      ChatProviderFactory
        .fromConfig(ChatConfig.from(config).getOrThrow())
        .getOrThrow(),
      database,
      priceBook,
    )

  val queueService = QueueService(database)

  // Hoisted out of the factory call below (RFC 112): the notification verifier
  // holds an inbound notification's bundleId and environment against this same
  // config, so both readers of it name one value.
  val appStoreConfig =
    AppStoreConfig
      .from(config)
      .getOrThrow()

  // The App Store Server API client (RFC 110): the factory owns its Ktor client's
  // construction, this root owns the client's lifetime — closed on
  // ApplicationStopped, mirroring the chat client. Null credentials are a valid
  // unconfigured state — verify answers 503.
  val appStore = AppStoreServerApiFactory.fromConfig(appStoreConfig)

  // The notifications endpoint's entire authentication (RFC 112). The anchor set
  // is bundled, so a broken one fails boot here rather than 401-ing every
  // notification in production.
  val appleNotificationVerifier =
    AppleNotificationVerifier(
      AppleJwsVerifier(AppleTrustAnchorLoader().load().getOrThrow()),
      appStoreConfig,
    )

  // Everything from here until Netty is bound can still throw — a rejected
  // subscriptions/server config, or a port already taken — and the
  // ApplicationStopped hook that closes the client only becomes live once the
  // module below has loaded. So the client's cleanup travels with its
  // allocation rather than depending on a hook registered further down: any
  // failure in the rest of the boot closes it here. Closing twice (this path
  // plus a hook that did fire) is harmless — HttpClient.close is idempotent.
  val server =
    runCatching {
      val subscriptionPlans =
        SubscriptionPlans
          .from(config)
          .getOrThrow()

      val ignorePathPrefixes =
        config
          .getStringList("sessionExpiry.ignorePathPrefixes")
          .toSet()

      val hostStr = config.getString("server.host")
      val portInt = port ?: config.getInt("server.port")

      val server =
        embeddedServer(Netty, port = portInt, host = hostStr) {
          val applicationConfig = environment.config as? MapApplicationConfig
          applicationConfig?.apply {
          }

          environment.monitor.subscribe(ApplicationStopped) {
            appStore.client.close()
            database.close()
          }

          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            llmCallLog,
            coachingConfig,
            clientKeyGateConfig,
            emailVerificationConfig,
            googleTokenVerifier,
            appleTokenVerifier,
            queueService,
            extractionConfig,
            requestLoggingConfig,
            budgetConfig,
            appStore.api,
            subscriptionPlans,
            appleNotificationVerifier,
          )

          install(SessionExpiryPlugin) {
            this.sessionConfig = sessionConfig
            this.queueService = queueService
            this.ignorePathPrefixes = ignorePathPrefixes
          }
        }

      // Start non-blocking and wait for Netty to bind.
      server.start(wait = false)
      @Suppress("DEPRECATION")
      kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors() }
      server
    }.onFailure { appStore.client.close() }
      .getOrThrow()

  if (wait) {
    Thread.currentThread().join()
  }

  return server
}

fun main() {
  startServer(wait = true)
}

fun Application.appModule(
  database: Database,
  sessionConfig: SessionConfig,
  requestSizeConfig: RequestSizeConfig,
  llmCallLog: LlmCallLog,
  coachingConfig: CoachingConfig,
  clientKeyGateConfig: ClientKeyGateConfig,
  emailVerificationConfig: EmailVerificationConfig,
  googleTokenVerifier: GoogleIdTokenVerifier,
  appleTokenVerifier: AppleIdTokenVerifier,
  queueService: QueueService,
  extractionConfig: ExtractionConfig,
  requestLoggingConfig: RequestLoggingConfig,
  budgetConfig: BudgetConfig,
  appStoreServerApi: AppStoreServerApi,
  subscriptionPlans: SubscriptionPlans,
  appleNotificationVerifier: AppleNotificationVerifier,
) {
  // Must stay first so the request-logging interceptor wraps the whole pipeline.
  configureRequestLogging(requestLoggingConfig)
  configureSerialization()
  configureClientKeyGate(clientKeyGateConfig)
  configureStatusPages()
  configureRequestSizeLimit(requestSizeConfig)

  val argon2Hasher = Argon2Hasher()
  val tokenGenerator = TokenGenerator()
  val emailVerificationService =
    EmailVerificationService(database, queueService, tokenGenerator, emailVerificationConfig)
  val emailVerifier: EmailVerifier = DbEmailVerifier(database)
  val authService =
    AuthService(database, argon2Hasher, tokenGenerator, emailVerificationService, googleTokenVerifier, appleTokenVerifier)
  val studentService = ed.unicoach.student.StudentService(database)
  val moneyProfileService =
    ed.unicoach.coaching.moneyprofile
      .MoneyProfileService(database)
  // Hoisted above the tool registry (RFC 136): the chat tool and the REST
  // routes share this one instance.
  val collegeListService = CollegeListService(database)
  // The tool registry advertised on every coaching turn (RFC 94). New tools
  // append here; nothing else in the loop changes.
  // One CollegeSearchService serves both the chat tool and the REST search
  // endpoint (RFC 137), so the two doors answer from the same clamp and SQL.
  val collegeSearchService = CollegeSearchService(database)
  // The published codebook, read once at wiring time (RFC 147): the search tool
  // advertises its region words and renders results in the same vocabulary.
  val codebook =
    kotlinx.coroutines.runBlocking {
      ed.unicoach.college.Codebook
        .loadOrEmpty(database)
    }
  val toolRegistry =
    ToolRegistry(
      listOf(
        CollegeChatTool(CollegeSearchTool(collegeSearchService, codebook)),
        // The name door beside the filter door (RFC 154): the same
        // collegeSearchService instance, so a school the student NAMED resolves
        // over the very path the iOS picker uses.
        ed.unicoach.coaching.FindCollegeChatTool(
          ed.unicoach.college.FindCollegeTool(collegeSearchService),
        ),
        // "Schools like Bowdoin" (RFC 153): the same service and the same
        // codebook the search tool reads, so the three doors speak one vocabulary.
        ed.unicoach.coaching
          .SimilarCollegesChatTool(
            ed.unicoach.college
              .SimilarCollegesTool(collegeSearchService, codebook),
          ),
        ed.unicoach.coaching.MoneyProfileChatTool(moneyProfileService),
        ed.unicoach.coaching.costs
          .CollegeCostChatTool(
            ed.unicoach.coaching.costs
              .CollegeCostService(database),
          ),
        ed.unicoach.coaching.collegelist
          .CollegeListChatTool(collegeListService),
        ed.unicoach.coaching.admissions
          .CollegeAdmissionsChatTool(
            ed.unicoach.coaching.admissions
              .CollegeAdmissionsService(database),
          ),
      ),
    )
  // One BudgetService serves both the chat gate and the usage endpoint, so the
  // verdict a student is blocked on is the verdict their usage bar renders.
  val budgetService = BudgetService(database, budgetConfig, subscriptionPlans)
  val coachingService = CoachingService(database, llmCallLog, coachingConfig, budgetService, toolRegistry)
  val subscriptionService = SubscriptionService(database, appStoreServerApi, subscriptionPlans)

  configureEmailVerificationGate(authService, sessionConfig)

  configureRouting(
    authService,
    studentService,
    coachingService,
    sessionConfig,
    emailVerificationService,
    emailVerifier,
    queueService,
    extractionConfig,
    collegeListService,
    collegeSearchService,
    moneyProfileService,
    budgetService,
    subscriptionService,
    appleNotificationVerifier,
  )
}
