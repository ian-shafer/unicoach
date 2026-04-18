package ed.unicoach.rest

import ed.unicoach.auth.AuthService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.plugins.configureSerialization
import ed.unicoach.rest.plugins.configureStatusPages
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "service.conf", "rest-server.conf")
      .getOrThrow()

  val dbConfig =
    DatabaseConfig
      .from(config)
      .getOrThrow()

  val sessionConfig =
    SessionConfig
      .from(config)
      .getOrThrow()

  val database = Database(dbConfig)

  val hostStr = config.getString("server.host")
  val portInt = config.getInt("server.port")

  val server =
    embeddedServer(Netty, port = portInt, host = hostStr) {
      val applicationConfig = environment.config as? MapApplicationConfig
      applicationConfig?.apply {
      }

      environment.monitor.subscribe(ApplicationStopped) {
        database.close()
      }

      appModule(database, sessionConfig)
    }

  server.start(wait = wait)
  return server
}

fun main() {
  startServer(wait = true)
}

fun Application.appModule(
  database: Database,
  sessionConfig: SessionConfig,
) {
  configureSerialization()
  configureStatusPages()

  val argon2Hasher = Argon2Hasher()
  val tokenGenerator = TokenGenerator()
  val authService = AuthService(database, argon2Hasher)

  configureRouting(authService, database, sessionConfig, tokenGenerator)
}
