package ed.unicoach.admin

import ed.unicoach.admin.auth.adminAuthRoutes
import ed.unicoach.admin.auth.installAdminGate
import ed.unicoach.admin.engine.AdminRegistry
import ed.unicoach.admin.engine.registerAdminRoutes
import ed.unicoach.admin.render.configureAdminStatusPages
import ed.unicoach.admin.render.toAdminDisplay
import ed.unicoach.admin.resources.ClaimsResource
import ed.unicoach.admin.resources.CollegesResource
import ed.unicoach.admin.resources.ConvoRequestsResource
import ed.unicoach.admin.resources.ConvosResource
import ed.unicoach.admin.resources.ExtractionRunsResource
import ed.unicoach.admin.resources.ObservationsResource
import ed.unicoach.admin.resources.SessionsResource
import ed.unicoach.admin.resources.StudentsResource
import ed.unicoach.admin.resources.SystemPromptsResource
import ed.unicoach.admin.resources.UsersResource
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.StubGoogleTokenVerifier
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailProviderFactory
import ed.unicoach.email.EmailService
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
      .load("common.conf", "db.conf", "admin-web.conf", "service.conf", "email.conf")
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
  // The admin gate only authenticates via AuthService; it never registers users
  // or sends verification mail. The EmailVerificationService is wired purely to
  // satisfy the AuthService constructor (RFC 65); the log provider is inert here.
  // The StubGoogleTokenVerifier (RFC 64) is likewise inert: the admin gate never
  // exercises the Google login path.
  val emailConfig = EmailConfig.from(config).getOrThrow()
  val emailProvider = EmailProviderFactory.fromConfig(emailConfig).getOrThrow()
  val emailService = EmailService(database, emailProvider, emailConfig)
  val emailVerificationConfig = EmailVerificationConfig.from(config).getOrThrow()
  val emailVerificationService =
    EmailVerificationService(database, emailService, tokenGenerator, emailVerificationConfig)
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
        ConvosResource,
        ConvoRequestsResource,
        CollegesResource,
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
