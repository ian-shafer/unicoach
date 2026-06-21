package ed.unicoach.rest

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.chat.ChatConfig
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatProviderFactory
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.CoachingService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailProviderFactory
import ed.unicoach.email.EmailService
import ed.unicoach.queue.QueueConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.plugins.SessionExpiryPlugin
import ed.unicoach.rest.plugins.configureClientKeyGate
import ed.unicoach.rest.plugins.configureRequestSizeLimit
import ed.unicoach.rest.plugins.configureSerialization
import ed.unicoach.rest.plugins.configureStatusPages
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
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
      .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
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

  val chatProvider =
    ChatProviderFactory
      .fromConfig(ChatConfig.from(config).getOrThrow())
      .getOrThrow()

  val coachingConfig =
    CoachingConfig
      .from(config)
      .getOrThrow()

  val clientKeyGateConfig =
    ClientKeyGateConfig
      .from(config)
      .getOrThrow()

  val emailConfig =
    EmailConfig
      .from(config)
      .getOrThrow()

  val emailVerificationConfig =
    EmailVerificationConfig
      .from(config)
      .getOrThrow()

  val database = Database(dbConfig)

  val emailProvider =
    EmailProviderFactory
      .fromConfig(emailConfig)
      .getOrThrow()
  val emailService = EmailService(database, emailProvider, emailConfig)

  val queueService = QueueService(database)

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
        database.close()
      }

      appModule(
        database,
        sessionConfig,
        requestSizeConfig,
        chatProvider,
        coachingConfig,
        clientKeyGateConfig,
        emailService,
        emailVerificationConfig,
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
  chatProvider: ChatProvider,
  coachingConfig: CoachingConfig,
  clientKeyGateConfig: ClientKeyGateConfig,
  emailService: EmailService,
  emailVerificationConfig: EmailVerificationConfig,
) {
  configureSerialization()
  configureClientKeyGate(clientKeyGateConfig)
  configureStatusPages()
  configureRequestSizeLimit(requestSizeConfig)

  val argon2Hasher = Argon2Hasher()
  val tokenGenerator = TokenGenerator()
  val emailVerificationService =
    EmailVerificationService(database, emailService, tokenGenerator, emailVerificationConfig)
  val authService = AuthService(database, argon2Hasher, tokenGenerator, emailVerificationService)
  val studentService = ed.unicoach.student.StudentService(database)
  val coachingService = CoachingService(database, chatProvider, coachingConfig)

  configureRouting(authService, studentService, coachingService, sessionConfig, emailVerificationService)
}
