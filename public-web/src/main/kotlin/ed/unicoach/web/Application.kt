package ed.unicoach.web

import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.common.logging.RequestLoggingConfig
import ed.unicoach.web.common.logging.configureRequestLogging
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
  val config =
    AppConfig
      .load("common.conf", "db.conf", "public-web.conf")
      .getOrThrow()

  val publicWebConfig =
    PublicWebConfig
      .from(config)
      .getOrThrow()

  val requestLoggingConfig =
    RequestLoggingConfig
      .from(config)
      .getOrThrow()

  val database = Database(DatabaseConfig.from(config).getOrThrow())
  val emailVerifier: EmailVerifier = DbEmailVerifier(database)
  val costReportSource: CostReportSource = ServiceCostReportSource(database, CollegeCostService(database))

  val server =
    embeddedServer(Netty, port = publicWebConfig.port, host = publicWebConfig.host) {
      environment.monitor.subscribe(ApplicationStopped) { database.close() }
      publicWebModule(emailVerifier, costReportSource, publicWebConfig.openInAppUrl, requestLoggingConfig)
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
  costReportSource: CostReportSource,
  openInAppUrl: String?,
  requestLoggingConfig: RequestLoggingConfig,
  bundleUrl: () -> String = { WebAssets.MAIN },
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
  installPublicWebRouting(emailVerifier, costReportSource, openInAppUrl)
}
