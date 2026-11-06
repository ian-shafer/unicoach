package ed.unicoach.web

import com.typesafe.config.Config
import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.IdTokenVerifierFactory
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.auth.SsoProviderConfig
import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.chat.CloseableChatProvider
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.LlmPriceBook
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.StudentToolRegistry
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionScheduler
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.report.CostReportConfig
import ed.unicoach.coaching.report.CostReportShareService
import ed.unicoach.coaching.report.ShareTokenDeriver
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.UserId
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.LinkRequestedNotice
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterLinkRequestService
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import ed.unicoach.student.AccountRoleService
import ed.unicoach.student.StudentService
import ed.unicoach.subscriptions.SubscriptionPlans
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import ed.unicoach.vocabulary.VocabularyService
import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.auth.installSignedInLanding
import ed.unicoach.web.auth.installWebAuthGate
import ed.unicoach.web.common.http.SiteOrigin
import ed.unicoach.web.common.http.installSameOriginGuard
import ed.unicoach.web.common.logging.RequestLoggingConfig
import ed.unicoach.web.common.logging.configureRequestLogging
import ed.unicoach.web.fafsa.FafsaDeadlineSource
import ed.unicoach.web.fafsa.ServiceFafsaDeadlineSource
import ed.unicoach.web.render.SsoButtons
import ed.unicoach.web.render.respondForbiddenPage
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.ServiceCostReportSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

/**
 * The module's own log: the bundle the build produced — the line that is also
 * the touch that makes [WebAssets] initialise — and which provider buttons are
 * on (RFC 275).
 */
private val publicWebLog = LoggerFactory.getLogger("ed.unicoach.web.PublicWeb")

/**
 * The conf resources `public-web` loads, in precedence order.
 *
 * Named once so the TEST harness loads the same set by splatting it: a suite
 * parsing a configuration the server does not have proves nothing about the
 * server, and a file dropped from the server's list would otherwise leave every
 * test green and the deployed box failing at boot.
 *
 * `service.conf` is in the list because it carries `publicWeb.urlBase` (RFC 155
 * D-J), the `emailVerification` block, and the `auth.google`/`auth.apple`
 * blocks the web's provider sign-in verifies against and renders its buttons
 * from (RFC 275).
 *
 * `chat.conf` is in the list since RFC 262, after `service.conf` as in
 * `rest-server`'s list: the web chat runs the coach in-process, so this root
 * builds the provider, and `CHAT_PROVIDER` is now as required here as it is
 * there.
 */
internal val PUBLIC_WEB_CONF_RESOURCES =
  arrayOf("common.conf", "db.conf", "session.conf", "service.conf", "chat.conf", "public-web.conf")

/**
 * Boots the public-web server: loads config from the classpath plus the local
 * overlay, binds Netty to the configured host/port, and installs the dynamic
 * render module. Unlike `rest-server` it installs no client-key gate, and
 * unlike `admin-web` it installs no auth gate — every route is public.
 *
 * It reuses the shared `db.conf` `database` block (the same block `rest-server`
 * and `admin-web` load) to build a [Database], wraps it in a [DbEmailVerifier]
 * for the in-process verify flow and a [ServiceCostReportSource] over
 * [CollegeCostService] for the tokenized Family Cost Report (RFC 155) — both
 * in-process rather than one service hopping to the other over HTTP — and
 * closes the `Database` on server stop via
 * the `ApplicationStopped` hook in the module lambda — matching the
 * `rest-server` / `admin-web` precedent.
 */

fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
  val config = AppConfig.load(*PUBLIC_WEB_CONF_RESOURCES).getOrThrow()

  // Every typed reader runs BEFORE the pool is opened. The close hook is
  // registered inside the module, so a config refusal thrown after `Database(...)`
  // would leave a pool nothing can close. The one refusal that cannot move
  // before the pool — the coach's build, which needs it — closes it instead.
  val publicWebConfig =
    PublicWebConfig
      .from(config)
      .getOrThrow()

  val requestLoggingConfig =
    RequestLoggingConfig
      .from(config)
      .getOrThrow()

  val databaseConfig = DatabaseConfig.from(config).getOrThrow()
  val emailVerificationConfig = EmailVerificationConfig.from(config).getOrThrow()
  val supporterInviteConfig = SupporterInviteConfig.from(config).getOrThrow()
  val coachConfigs = CoachConfigs.read(config)
  val signInProviders = WebSignInProviders.read(config)
  val ssoButtons = signInProviders.buttons(publicWebConfig.origin)

  val database = Database(databaseConfig)
  // The one writer of a `supporters` row (RFC 241), and the one writer of a
  // parent's request to connect (RFC 254): the accept path, the report's
  // claim, and the verify hook that releases a waiting request all go through
  // these two.
  val supporterService = SupporterService(database, LinkEndedNotice(QueueService(database)))
  val linkRequestService = SupporterLinkRequestService(database, supporterService, LinkRequestedNotice(QueueService(database)))
  val emailVerifier: EmailVerifier = DbEmailVerifier(database, linkRequestService::releaseOnVerified)
  val costReportSource: CostReportSource =
    ServiceCostReportSource(database, CollegeCostService(database), SharingPermissionService(database), linkRequestService)
  // RFC 230 assigned this construction site to `fafsa/04`: the public page and
  // the coach read ONE service, so the two doors cannot disagree about a date.
  val fafsaDeadlines: FafsaDeadlineSource = ServiceFafsaDeadlineSource(FafsaDeadlineService(database))
  // The accept path mints the recipient's `supporters` row, through the one
  // writer of it (RFC 241).
  val supporterInviteService = SupporterInviteService(database, supporterInviteConfig, TokenGenerator(), supporterService)

  val authServices = WebAuthServices.build(emailVerificationConfig, database, signInProviders, linkRequestService::releaseOnVerified)
  // ONE budget read for the whole process (RFC 273 §4): the coach's gate
  // blocks on it and Settings renders it, so the meter a family reads and the
  // meter the coach stops at are one object, not two readers of one config.
  val budgetService = BudgetService(database, coachConfigs.budget, coachConfigs.plans)
  val coach = closingOnFailure(database::close) { WebCoach.build(coachConfigs, database, supporterInviteService, budgetService) }

  val server =
    embeddedServer(Netty, port = publicWebConfig.port, host = publicWebConfig.host) {
      environment.monitor.subscribe(ApplicationStopped) {
        // rest-server never closes its provider; this root does, beside the
        // pool, because the provider owns an HTTP client of its own.
        coach.close()
        database.close()
      }
      publicWebModule(
        emailVerifier,
        costReportSource,
        fafsaDeadlines,
        publicWebConfig.openInAppUrl,
        requestLoggingConfig,
        // `bundleUrl` keeps its default (WebAssets.MAIN); the three auth
        // collaborators are named so their positions cannot drift against the
        // RFC 225 parameter that now sits before them.
        authService = authServices.authService,
        emailVerificationService = authServices.emailVerificationService,
        publicWebConfig = publicWebConfig,
        supporterInviteService = supporterInviteService,
        supporterService = supporterService,
        linkRequestService = linkRequestService,
        coachingService = coach.coachingService,
        budgetService = budgetService,
        studentService = StudentService(database),
        // The one writer of a session's metadata (RFC 265): the draft rail's.
        sessionMetadataService = SessionMetadataService(database),
        // Onboarding (RFC 268): the one read of who an account is here for,
        // and the student form's optional money answers and their options.
        accountRoleService = AccountRoleService(database),
        moneyProfileService = MoneyProfileService(database),
        vocabularyService = VocabularyService(database),
        // My colleges (RFC 271): the student's list and the name search
        // that adds to it, both in-process (brief 0013 D13).
        collegeListService = CollegeListService(database),
        collegeSearchService = CollegeSearchService(database),
        ssoButtons = ssoButtons,
      )
    }

  server.start(wait = false)
  @Suppress("DEPRECATION")
  kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors() }

  if (wait) {
    Thread.currentThread().join()
  }

  return server
}

/**
 * The two `:service` collaborators RFC 224 wires, built together because
 * `AuthService` takes the verification service as a dependency and both take the
 * one [TokenGenerator].
 *
 * A named factory rather than sixteen more lines inside [startServer]: assembling
 * the auth graph is a different decision from "start a server", and it changes
 * for different reasons.
 *
 * The same five collaborators `admin-web` wires, for the same reason: the web
 * tier makes no authentication judgement of its own, it calls `:service`
 * in-process. Registration enqueues a `SEND_EMAIL` job through
 * [EmailVerificationService] (RFC 96), so public-web is an enqueue-only queue
 * producer and never constructs an `EmailService`.
 *
 * Both SSO slots carry the REAL verifiers since RFC 275, built from
 * [WebSignInProviders] exactly as `rest-server` builds its own, so the web's
 * provider doors verify a token the way the iPhone's do.
 */
private data class WebAuthServices(
  val authService: AuthService,
  val emailVerificationService: EmailVerificationService,
) {
  companion object {
    fun build(
      emailVerificationConfig: EmailVerificationConfig,
      database: Database,
      signInProviders: WebSignInProviders,
      afterVerified: (SqlSession, UserId) -> Unit,
    ): WebAuthServices {
      val tokenGenerator = TokenGenerator()
      val emailVerificationService =
        EmailVerificationService(
          database,
          QueueService(database),
          tokenGenerator,
          emailVerificationConfig,
        )
      return WebAuthServices(
        authService =
          AuthService(
            database,
            Argon2Hasher(),
            tokenGenerator,
            emailVerificationService,
            signInProviders.googleVerifier,
            signInProviders.appleVerifier,
            afterVerified,
          ),
        emailVerificationService = emailVerificationService,
      )
    }
  }
}

/**
 * The two provider blocks of `service.conf` (RFC 275), read ONCE and used for
 * both halves of web sign-in: the verifiers, from the same
 * [IdTokenVerifierFactory] `rest-server` builds its own with, and the buttons
 * ([buttons]), from the same blocks' web IDs. The button reads a web ID and the
 * verifier accepts it, so the two cannot disagree about it.
 *
 * Read before the pool opens, beside the other typed readers: building a
 * verifier opens no connection, and a refusal here (an unknown provider, a real
 * provider with no audience at all) must fail the boot with nothing to close.
 */
internal class WebSignInProviders(
  val google: SsoProviderConfig,
  val apple: SsoProviderConfig,
) {
  val googleVerifier: GoogleIdTokenVerifier = IdTokenVerifierFactory.googleFromConfig(google).getOrThrow()

  val appleVerifier: AppleIdTokenVerifier = IdTokenVerifierFactory.appleFromConfig(apple).getOrThrow()

  /** The buttons the configured web IDs turn on; Apple's return URL is derived from the site's own [origin]. */
  fun buttons(origin: SiteOrigin): SsoButtons = SsoButtons.of(google.webClientId, apple.webClientId, origin)

  companion object {
    fun read(config: Config): WebSignInProviders =
      WebSignInProviders(
        google = SsoProviderConfig.from(config, "auth.google").getOrThrow(),
        apple = SsoProviderConfig.from(config, "auth.apple").getOrThrow(),
      )
  }
}

/**
 * Every typed reader the in-process coach needs (RFC 262 §3), read together so
 * [startServer] can run them BEFORE the pool opens, beside its other readers: a
 * refusal here must not leave a pool nothing closes.
 *
 * The price book is checked against the coaching model here too, exactly as
 * `rest-server` checks it: an env override to an unpriced model fails the boot
 * rather than metering every turn at the default rate.
 */
internal class CoachConfigs(
  val coaching: CoachingConfig,
  val budget: BudgetConfig,
  val plans: SubscriptionPlans,
  val priceBook: LlmPriceBook,
  val chat: ChatConfig,
  val extraction: ExtractionConfig,
  val costReport: CostReportConfig,
) {
  companion object {
    fun read(config: Config): CoachConfigs {
      val coaching = CoachingConfig.from(config).getOrThrow()
      val priceBook = LlmPriceBook.from(config).getOrThrow()
      priceBook.requireExplicitlyPriced(listOf(coaching.model)).getOrThrow()
      return CoachConfigs(
        coaching = coaching,
        budget = BudgetConfig.from(config).getOrThrow(),
        plans = SubscriptionPlans.from(config).getOrThrow(),
        priceBook = priceBook,
        chat = ChatConfig.from(config).getOrThrow(),
        extraction = ExtractionConfig.from(config).getOrThrow(),
        costReport = CostReportConfig.from(config).getOrThrow(),
      )
    }
  }
}

/**
 * The student's coach, built in-process (brief 0013 D13): the web chat calls
 * [CoachingService] directly and never `rest-server`'s API.
 *
 * Built from the SAME pieces `rest-server` builds it from — the one tool list
 * ([StudentToolRegistry.standard]) and a [CoachingService] that schedules its
 * own extraction ([ExtractionScheduler]) — so the two doors run one coach. The
 * provider is the one resource here with a lifetime of its own; [close] ends
 * it.
 */
private class WebCoach(
  val coachingService: CoachingService,
  private val provider: CloseableChatProvider,
) : AutoCloseable {
  override fun close() {
    provider.close()
  }

  companion object {
    fun build(
      configs: CoachConfigs,
      database: Database,
      supporterInvites: SupporterInviteService,
      budgetService: BudgetService,
    ): WebCoach {
      val provider = ChatProviderFactory.fromConfig(configs.chat).getOrThrow()
      // A boot-time read (the codebook); a refusal closes the provider it would
      // otherwise strand.
      val tools =
        closingOnFailure(provider::close) {
          kotlinx.coroutines.runBlocking {
            StudentToolRegistry.standard(
              database,
              supporterInvites,
              // The share door's secret, derived exactly as rest-server derives
              // it, so `share_cost_report` answers the same on both doors.
              // rest-server says so at boot when it is unset; the host's one
              // environment file serves both.
              CostReportShareService(
                database,
                configs.costReport,
                configs.costReport.secret?.let(::ShareTokenDeriver),
              ),
            )
          }
        }
      val coachingService =
        CoachingService(
          database,
          LlmCallLog(provider, database, configs.priceBook),
          configs.coaching,
          budgetService,
          ExtractionScheduler(QueueService(database), configs.extraction),
          tools,
        )
      return WebCoach(coachingService, provider)
    }
  }
}

/**
 * [block]'s value; when it throws, [close] runs first and the original failure
 * is rethrown — a failure to close rides along as suppressed, so it never
 * replaces the cause of the refused boot.
 */
private inline fun <T> closingOnFailure(
  close: () -> Unit,
  block: () -> T,
): T =
  runCatching(block)
    .onFailure { cause -> runCatching(close).onFailure(cause::addSuppressed) }
    .getOrThrow()

fun main() {
  startServer(wait = true)
}

fun Application.publicWebModule(
  emailVerifier: EmailVerifier,
  costReportSource: CostReportSource,
  fafsaDeadlines: FafsaDeadlineSource,
  openInAppUrl: String?,
  requestLoggingConfig: RequestLoggingConfig,
  bundleUrl: () -> String = { WebAssets.MAIN },
  authService: AuthService,
  emailVerificationService: EmailVerificationService,
  publicWebConfig: PublicWebConfig,
  supporterInviteService: SupporterInviteService,
  supporterService: SupporterService,
  linkRequestService: SupporterLinkRequestService,
  coachingService: CoachingService,
  budgetService: BudgetService,
  studentService: StudentService,
  sessionMetadataService: SessionMetadataService,
  accountRoleService: AccountRoleService,
  moneyProfileService: MoneyProfileService,
  vocabularyService: VocabularyService,
  collegeListService: CollegeListService,
  collegeSearchService: CollegeSearchService,
  ssoButtons: SsoButtons,
) {
  // READ the bundle name while the module installs (RFC 225). WebAssets is an
  // `object`, so its manifest refusals run at class-init — and nothing touched
  // it before a page rendered, which made a broken or stale manifest boot GREEN
  // and fail the first visitor, the branded 503 page included. One read here
  // moves that failure to the daemon's start and to every `testApplication`
  // boot, which is where the RFC always said it was.
  //
  // [bundleUrl] is a seam, defaulted to the real resolver so no call site has
  // to remember it. A test can hand in a resolver that throws and assert the
  // BOOT fails — behaviour, not a test that reads this file and looks for the
  // name `WebAssets.MAIN` in it.
  publicWebLog.info("client bundle: [{}]", bundleUrl())

  // Must stay first so the request-logging interceptor wraps the whole pipeline.
  configureRequestLogging(requestLoggingConfig)

  // All three are pipeline interceptors and all three run BEFORE routing, so
  // their order here is their order at runtime. The same-origin guard goes first
  // on purpose: a forged cross-site POST must be refused before anything
  // resolves a session for it, and before any handler can act on its body
  // (RFC 224 D-C). The landing rule and the auth gate are independent — the
  // landing rule only fires on paths the gate classes OPEN — and they are two
  // installs rather than one because one answers a navigation question and the
  // other answers an access one.
  installSameOriginGuard(publicWebConfig.origin) {
    // The 403 page renders BECAUSE the request was refused; resolving its caller
    // defensively is the same choice the 503 and 404 handlers make, and for the
    // same reason: a database fault must not replace the branded refusal.
    respondForbiddenPage(shellNavOrSignedOut(authService, publicWebConfig.session))
  }
  logSsoButtons(ssoButtons)
  installSignedInLanding(authService, publicWebConfig.session)
  installWebAuthGate(authService, publicWebConfig.session, accountRoleService)

  installPublicWebRouting(
    emailVerifier,
    costReportSource,
    fafsaDeadlines,
    openInAppUrl,
    authService,
    emailVerificationService,
    publicWebConfig.session,
    supporterInviteService,
    supporterService,
    linkRequestService,
    coachingService,
    budgetService,
    studentService,
    sessionMetadataService,
    accountRoleService,
    moneyProfileService,
    vocabularyService,
    collegeListService,
    collegeSearchService,
    ssoButtons,
  )
}

/**
 * One boot line per provider (RFC 275 §4), beside the same-origin guard's: an
 * operator reading "buttons missing" can then tell an unset web ID from a
 * provider-side misconfiguration, which fails inside the provider's popup and
 * never reaches this process.
 *
 * The env keys are named literally: Typesafe Config does not expose which
 * substitution a value came from, so these spell `service.conf`'s
 * `webClientId = ${?…}` lines. Renaming a key there without touching this
 * makes the hint wrong, not the behaviour.
 */
private fun logSsoButtons(buttons: SsoButtons) {
  val google = buttons.google
  if (google == null) {
    publicWebLog.info("web sign-in with Google: off (GOOGLE_WEB_CLIENT_ID unset)")
  } else {
    publicWebLog.info("web sign-in with Google: on [{}]", google.clientId)
  }
  val apple = buttons.apple
  if (apple == null) {
    publicWebLog.info("web sign-in with Apple: off (APPLE_WEB_SERVICES_ID unset)")
  } else {
    publicWebLog.info("web sign-in with Apple: on [{}] return URL [{}]", apple.servicesId, apple.redirectUri)
  }
}
