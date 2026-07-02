package ed.unicoach.web

import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Boots the public-web server: loads config from the classpath plus the local
 * overlay, binds Netty to the configured host/port, and installs the dynamic
 * render module. Unlike `rest-server` it installs no client-key gate, and
 * unlike `admin-web` it installs no auth gate — every route is public.
 *
 * It reuses the shared `db.conf` `database` block (the same block `rest-server`
 * and `admin-web` load) to build a [Database], wraps it in a [DbEmailVerifier]
 * for the in-process verify flow, and closes the `Database` on server stop via
 * the `ApplicationStopped` hook in the module lambda — matching the
 * `rest-server` / `admin-web` precedent.
 */
fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "public-web.conf")
      .getOrThrow()

  val publicWebConfig =
    PublicWebConfig
      .from(config)
      .getOrThrow()

  val database = Database(DatabaseConfig.from(config).getOrThrow())
  val emailVerifier: EmailVerifier = DbEmailVerifier(database)

  val server =
    embeddedServer(Netty, port = publicWebConfig.port, host = publicWebConfig.host) {
      environment.monitor.subscribe(ApplicationStopped) { database.close() }
      publicWebModule(emailVerifier, publicWebConfig.openInAppUrl)
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

fun Application.publicWebModule(
  emailVerifier: EmailVerifier,
  openInAppUrl: String,
) {
  installPublicWebRouting(emailVerifier, openInAppUrl)
}
