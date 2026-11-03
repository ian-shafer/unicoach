package ed.unicoach.rest

import ed.unicoach.account.AccountClosureService
import ed.unicoach.account.AccountErasureConfig
import ed.unicoach.account.AccountErasureScheduler
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
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.LlmPriceBook
import ed.unicoach.coaching.StudentToolRegistry
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionScheduler
import ed.unicoach.coaching.report.CostReportConfig
import ed.unicoach.coaching.report.CostReportShareService
import ed.unicoach.coaching.report.RevokeCostReportShareChatTool
import ed.unicoach.coaching.report.ShareCostReportChatTool
import ed.unicoach.coaching.report.ShareTokenDeriver
import ed.unicoach.coaching.report.StopCostReportOffersChatTool
import ed.unicoach.coaching.testing.AdmissionTestTimelineChatTool
import ed.unicoach.coaching.testing.AutomaticAwardChatTool
import ed.unicoach.coaching.testing.AutomaticAwardService
import ed.unicoach.coaching.testing.NationalMeritChatTool
import ed.unicoach.coaching.testing.NationalMeritService
import ed.unicoach.coaching.testing.TestScoreService
import ed.unicoach.coaching.testing.TestTimelineService
import ed.unicoach.coaching.testing.UpdateTestScoresChatTool
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.LinkRequestedNotice
import ed.unicoach.family.SupporterCoachingConfig
import ed.unicoach.family.SupporterCoachingService
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterLinkRequestService
import ed.unicoach.family.SupporterService
import ed.unicoach.family.SupporterToolRegistry
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
import io.ktor.server.application.log
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
      .load("common.conf", "db.conf", "session.conf", "service.conf", "chat.conf", "appstore.conf", "rest-server.conf", "queue.conf")
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

  val costReportConfig =
    CostReportConfig
      .from(config)
      .getOrThrow()

  val supporterInviteConfig =
    SupporterInviteConfig
      .from(config)
      .getOrThrow()

  val supporterCoachingConfig =
    SupporterCoachingConfig
      .from(config)
      .getOrThrow()

  // RFC 267: the close schedules the erasure, delayed by this grace.
  val accountErasureConfig =
    AccountErasureConfig
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
    .requireExplicitlyPriced(listOf(coachingConfig.model, supporterCoachingConfig.model))
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
            costReportConfig,
            supporterInviteConfig,
            supporterCoachingConfig,
            accountErasureConfig,
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
  costReportConfig: CostReportConfig,
  supporterInviteConfig: SupporterInviteConfig,
  supporterCoachingConfig: SupporterCoachingConfig,
  accountErasureConfig: AccountErasureConfig,
) {
  // Must stay first so the request-logging interceptor wraps the whole pipeline.
  configureRequestLogging(requestLoggingConfig)
  configureSerialization()
  configureClientKeyGate(clientKeyGateConfig)
  configureStatusPages()
  configureRequestSizeLimit(requestSizeConfig)

  val argon2Hasher = Argon2Hasher()
  val tokenGenerator = TokenGenerator()
  // ONE SupporterService (RFC 241): accepting an invite and signing up as a
  // parent both mint the `supporters` row, and they mint it through the same
  // writer rather than through two copies of a get-or-create.
  // ONE notice for every severance (RFC 252): the two disconnect doors and the
  // account close tell the other party through the same enqueue and text.
  val linkEndedNotice = LinkEndedNotice(queueService)
  val supporterService = SupporterService(database, linkEndedNotice)
  val accountClosureService = AccountClosureService(database, linkEndedNotice, AccountErasureScheduler(queueService, accountErasureConfig))
  // The one writer of a parent's request to connect (RFC 254). rest-server serves
  // the student's approve and decline, and releases a waiting request when its
  // claimant verifies their address here.
  val supporterLinkRequestService =
    SupporterLinkRequestService(database, supporterService, LinkRequestedNotice(queueService))
  val supporterInviteService =
    SupporterInviteService(database, supporterInviteConfig, tokenGenerator, supporterService)
  val emailVerificationService =
    EmailVerificationService(database, queueService, tokenGenerator, emailVerificationConfig)
  val emailVerifier: EmailVerifier = DbEmailVerifier(database, supporterLinkRequestService::releaseOnVerified)
  val authService =
    AuthService(
      database,
      argon2Hasher,
      tokenGenerator,
      emailVerificationService,
      googleTokenVerifier,
      appleTokenVerifier,
      supporterLinkRequestService::releaseOnVerified,
    )
  val studentService = ed.unicoach.student.StudentService(database)
  val moneyProfileService =
    ed.unicoach.coaching.moneyprofile
      .MoneyProfileService(database)
  // The REST list doors' service (RFC 136). The chat tool reads its own
  // instance through StudentToolRegistry (RFC 262); the service holds no state,
  // so both read and write the one table the same way.
  val collegeListService = CollegeListService(database)
  // The two REST doors' pair of services (RFC 205). The intended-subjects chat
  // tool builds the same stateless pair over the same database (RFC 262), so
  // the taxonomy the picker offers is still the taxonomy the coach resolves
  // against.
  val subjectVocabularyService =
    ed.unicoach.coaching
      .SubjectVocabularyService(database)
  val intendedSubjectsService =
    ed.unicoach.coaching
      .IntendedSubjectsService(database, subjectVocabularyService)
  // The student's sharing permissions (RFC 248).
  val sharingPermissionService =
    ed.unicoach.coaching
      .SharingPermissionService(database)
  // The subject seam's cost read (RFC 250). The cost chat tool builds its own
  // instance of this stateless service (RFC 262).
  val collegeCostService =
    ed.unicoach.coaching.costs
      .CollegeCostService(database)
  // The subject seam (RFC 250): the one service that reads a student a linked
  // adult names, over the same list, cost and permission services the
  // student's own doors use.
  val subjectReadService =
    ed.unicoach.family.SubjectReadService(database, collegeListService, collegeCostService, sharingPermissionService)
  // The REST search endpoint's service (RFC 137). The chat tools read their
  // own instance through StudentToolRegistry; the service is stateless, so
  // both doors still answer from the same clamp and SQL.
  val collegeSearchService = CollegeSearchService(database)
  // The Family Cost Report share door (RFC 155): one service behind both tools,
  // so minting and revoking cannot drift apart. Its token is DERIVED from the
  // share row's id, so re-sharing hands back the same link and the raw token is
  // stored nowhere. An unset share-token secret is not a boot failure: the
  // service starts and the two tools decline with a sentence the coach can say.
  // The deriver is built HERE rather than inside the service: a collaborator a
  // service builds from its own config cannot be substituted, and "no secret
  // configured" reads as the absent collaborator it is.
  val shareTokenDeriver = costReportConfig.secret?.let(::ShareTokenDeriver)
  if (shareTokenDeriver == null) {
    // SAID ONCE, AT BOOT. An unset secret disables sharing for every student
    // forever, and the only other evidence was a sentence spoken to a student —
    // so "why can no one share?" started by reading source. The config path is
    // named from its one home rather than re-typed here.
    log.warn(
      "cost report sharing is disabled: config key [{}] is unset, so tool [{}] declines every call; " +
        "viewing an already-shared report and tool [{}] are unaffected",
      CostReportConfig.SHARE_TOKEN_SECRET_PATH,
      ShareCostReportChatTool.TOOL_NAME,
      RevokeCostReportShareChatTool.TOOL_NAME,
    )
  }
  val costReportShareService = CostReportShareService(database, costReportConfig, shareTokenDeriver)
  // One FAFSA calendar read behind both coaches' `fafsa_deadlines` (RFC 237,
  // RFC 258), so the student and the parent are told the same dates.
  val fafsaDeadlineService =
    ed.unicoach.coaching.aid
      .FafsaDeadlineService(database)
  // The student coach's tools (RFC 94): ONE list, shared with public-web's
  // in-process chat (RFC 262), so the two doors cannot advertise different
  // coaches. The invite tool mints through the same invite service as the REST
  // doors here.
  val toolRegistry =
    kotlinx.coroutines.runBlocking {
      StudentToolRegistry.standard(database, supporterInviteService, costReportShareService)
    }
  // One BudgetService serves both the chat gate and the usage endpoint, so the
  // verdict a student is blocked on is the verdict their usage bar renders.
  val budgetService = BudgetService(database, budgetConfig, subscriptionPlans)
  // The extraction enqueue rides the student turn itself (RFC 262), so this root
  // hands the coach its scheduler rather than a route enqueueing after the fact.
  val coachingService =
    CoachingService(database, llmCallLog, coachingConfig, budgetService, ExtractionScheduler(queueService, extractionConfig), toolRegistry)
  // The supporter's own coach (RFC 258): the same loop and call log, its own
  // read-only registry (a SEPARATE instance, brief 0014 D15 -- nothing in it
  // writes), its own prompt, and the parent's own account as its budget subject.
  val supporterCoachingService =
    SupporterCoachingService(
      database,
      llmCallLog,
      supporterCoachingConfig,
      budgetService,
      subjectReadService,
      supporterService,
      SupporterToolRegistry.standard(
        subjectReadService,
        fafsaDeadlineService,
        ed.unicoach.college.FindCollegeTool(collegeSearchService),
        database,
      ),
    )
  val subscriptionService = SubscriptionService(database, appStoreServerApi, subscriptionPlans)
  // The served vocabularies (RFC 165): one registry behind
  // GET /api/v1/vocabularies, built from the very enum and validator set the
  // write paths use, so a value a picker offers is a value the server accepts.
  val vocabularyService =
    ed.unicoach.vocabulary
      .VocabularyService(database)

  configureEmailVerificationGate(authService, sessionConfig)

  configureRouting(
    authService,
    studentService,
    coachingService,
    sessionConfig,
    emailVerificationService,
    emailVerifier,
    queueService,
    collegeListService,
    collegeSearchService,
    moneyProfileService,
    budgetService,
    subscriptionService,
    appleNotificationVerifier,
    vocabularyService,
    subjectVocabularyService,
    intendedSubjectsService,
    sharingPermissionService,
    supporterInviteService,
    supporterService,
    accountClosureService,
    subjectReadService,
    supporterLinkRequestService,
    supporterCoachingService,
  )
}
