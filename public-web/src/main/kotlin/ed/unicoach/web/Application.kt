package ed.unicoach.web

import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.DisabledIdTokenVerifier
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.auth.installSignedInLanding
import ed.unicoach.web.auth.installWebAuthGate
import ed.unicoach.web.common.http.installSameOriginGuard
import ed.unicoach.web.common.logging.RequestLoggingConfig
import ed.unicoach.web.common.logging.configureRequestLogging
import ed.unicoach.web.fafsa.FafsaDeadlineSource
import ed.unicoach.web.fafsa.ServiceFafsaDeadlineSource
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
 * The module's own log. It exists for ONE line — the bundle the build produced
 * — because that line is also the touch that makes [WebAssets] initialise.
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
 * D-J) and the `emailVerification` block. It also carries REQUIRED
 * `GOOGLE_AUTH_PROVIDER`/`APPLE_AUTH_PROVIDER` substitutions, so
 * public-web needs both set to boot even though it serves no SSO route until
 * web/11 — the same inheritance `admin-web` and `queue-worker` already have.
 */
internal val PUBLIC_WEB_CONF_RESOURCES =
  arrayOf("common.conf", "db.conf", "session.conf", "service.conf", "public-web.conf")

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
  // would leave a pool nothing can close.
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

  val database = Database(databaseConfig)
  val emailVerifier: EmailVerifier = DbEmailVerifier(database)
  val costReportSource: CostReportSource =
    ServiceCostReportSource(database, CollegeCostService(database), SharingPermissionService(database))
  // RFC 230 assigned this construction site to `fafsa/04`: the public page and
  // the coach read ONE service, so the two doors cannot disagree about a date.
  val fafsaDeadlines: FafsaDeadlineSource = ServiceFafsaDeadlineSource(FafsaDeadlineService(database))
  // The accept path mints the recipient's `supporters` row, through the one
  // writer of it (RFC 241) -- this process serves no supporter surface of its own.
  val supporterInviteService =
    SupporterInviteService(
      database,
      supporterInviteConfig,
      TokenGenerator(),
      SupporterService(database, LinkEndedNotice(QueueService(database))),
    )

  val authServices = WebAuthServices.build(emailVerificationConfig, database)

  val server =
    embeddedServer(Netty, port = publicWebConfig.port, host = publicWebConfig.host) {
      environment.monitor.subscribe(ApplicationStopped) { database.close() }
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
 * Both SSO slots are wired FAIL-CLOSED (RFC 111): public-web serves no SSO route
 * until web/11, and [DisabledIdTokenVerifier] refuses every token rather than a
 * stub silently accepting one.
 */
private data class WebAuthServices(
  val authService: AuthService,
  val emailVerificationService: EmailVerificationService,
) {
  companion object {
    fun build(
      emailVerificationConfig: EmailVerificationConfig,
      database: Database,
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
            GoogleIdTokenVerifier(DisabledIdTokenVerifier),
            AppleIdTokenVerifier(DisabledIdTokenVerifier),
          ),
        emailVerificationService = emailVerificationService,
      )
    }
  }
}

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
  installSignedInLanding(authService, publicWebConfig.session)
  installWebAuthGate(authService, publicWebConfig.session)

  installPublicWebRouting(
    emailVerifier,
    costReportSource,
    fafsaDeadlines,
    openInAppUrl,
    authService,
    emailVerificationService,
    publicWebConfig.session,
    supporterInviteService,
  )
}
