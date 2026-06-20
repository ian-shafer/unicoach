package ed.unicoach.web

import ed.unicoach.common.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Boots the public-web server: loads config from the classpath plus the local
 * overlay, binds Netty to the configured host/port, and installs the dynamic
 * render module. Unlike `rest-server` it installs no client-key gate, and
 * unlike `admin-server` it installs no auth gate — every route is public. It
 * loads no database config and constructs no `Database`.
 */
fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
  val config =
    AppConfig
      .load("common.conf", "public-web.conf")
      .getOrThrow()

  val publicWebConfig =
    PublicWebConfig
      .from(config)
      .getOrThrow()

  val server =
    embeddedServer(Netty, port = publicWebConfig.port, host = publicWebConfig.host) {
      publicWebModule()
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

fun Application.publicWebModule() {
  installPublicWebRouting()
}
