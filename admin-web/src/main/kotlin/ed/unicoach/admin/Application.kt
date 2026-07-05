package ed.unicoach.admin

import ed.unicoach.admin.auth.adminAuthRoutes
import ed.unicoach.admin.auth.installAdminGate
import ed.unicoach.admin.engine.AdminRegistry
import ed.unicoach.admin.engine.registerAdminRoutes
import ed.unicoach.admin.render.configureAdminStatusPages
import ed.unicoach.admin.render.toAdminDisplay
import ed.unicoach.admin.resources.ClaimsResource
import ed.unicoach.admin.resources.CollegeListEntriesResource
import ed.unicoach.admin.resources.CollegesResource
import ed.unicoach.admin.resources.CommitmentsResource
import ed.unicoach.admin.resources.ConvoRequestsResource
import ed.unicoach.admin.resources.ConvosResource
import ed.unicoach.admin.resources.ExtractionRunsResource
import ed.unicoach.admin.resources.FitLensRunsResource
import ed.unicoach.admin.resources.FitSuggestionsResource
import ed.unicoach.admin.resources.ObservationsResource
import ed.unicoach.admin.resources.PeriodicJobsResource
import ed.unicoach.admin.resources.SessionsResource
import ed.unicoach.admin.resources.StudentsResource
import ed.unicoach.admin.resources.SynthesisRunsResource
import ed.unicoach.admin.resources.SystemPromptsResource
import ed.unicoach.admin.resources.UsersResource
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.StubGoogleTokenVerifier
import ed.unicoach.common.config.AppConfig
import ed.unicoach.cron.dao.PeriodicJobsDao
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing

fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "admin-web.conf", "service.conf")
      .getOrThrow()

  val dbConfig =
    DatabaseConfig
      .from(config)
      .getOrThrow()

  val adminConfig =
    AdminConfig
      .from(config)
      .getOrThrow()

  val database = Database(dbConfig)
  val argon2Hasher = Argon2Hasher()
  val tokenGenerator = TokenGenerator()
  // The admin "send verification email" action enqueues a SEND_EMAIL job via
  // EmailVerificationService.resend (RFC 96); the worker transmits it. admin-web
  // is now a pure enqueue-only producer — it constructs a QueueService, never an
  // EmailService/provider. The StubGoogleTokenVerifier (RFC 64) stays inert: the
  // admin gate never exercises the Google login path.
  val queueService = QueueService(database)
  val emailVerificationConfig = EmailVerificationConfig.from(config).getOrThrow()
  val emailVerificationService =
    EmailVerificationService(database, queueService, tokenGenerator, emailVerificationConfig)
  val authService =
    AuthService(database, argon2Hasher, tokenGenerator, emailVerificationService, StubGoogleTokenVerifier())

  val server =
    embeddedServer(Netty, port = adminConfig.port, host = adminConfig.host) {
      environment.monitor.subscribe(ApplicationStopped) {
        database.close()
      }
      adminModule(database, authService, argon2Hasher, emailVerificationService, adminConfig)
    }

  server.start(wait = false)
  @Suppress("DEPRECATION")
  kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors() }

  if (wait) {
    Thread.currentThread().join()
  }

  return server
}

fun main() {
  startServer(wait = true)
}

fun Application.adminModule(
  database: Database,
  authService: AuthService,
  argon2Hasher: Argon2Hasher,
  emailVerificationService: EmailVerificationService,
  adminConfig: AdminConfig,
) {
  configureAdminStatusPages()

  installAdminGate(authService, adminConfig)

  val registry =
    AdminRegistry(
      listOf(
        UsersResource(argon2Hasher, emailVerificationService),
        StudentsResource,
        SessionsResource,
        SystemPromptsResource,
        ClaimsResource,
        ObservationsResource,
        ExtractionRunsResource,
        CommitmentsResource,
        SynthesisRunsResource,
        FitSuggestionsResource,
        FitLensRunsResource,
        ConvosResource,
        ConvoRequestsResource,
        CollegesResource,
        CollegeListEntriesResource,
        PeriodicJobsResource(PeriodicJobsDao()),
      ),
    )

  val display =
    adminConfig.display.toAdminDisplay { slug -> registry.bySlug(slug) != null }

  routing {
    healthRoute()
    adminAuthRoutes(authService, adminConfig)
    registerAdminRoutes(registry, database, display)
  }
}
