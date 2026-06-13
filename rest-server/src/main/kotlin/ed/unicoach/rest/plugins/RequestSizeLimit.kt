package ed.unicoach.rest.plugins

import ed.unicoach.rest.config.RequestSizeConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.path

fun Application.configureRequestSizeLimit(config: RequestSizeConfig) {
  install(RequestBodyLimit) {
    bodyLimit { call ->
      resolveLimit(config, call.request.path())
    }
  }
}

/** Resolution order: exact override, then longest matching prefix, then the default. */
internal fun resolveLimit(
  config: RequestSizeConfig,
  path: String,
): Long {
  config.routeOverrides[path]?.let { return it.bytes }
  val prefixMatch =
    config.routePrefixOverrides.entries
      .filter { path.startsWith(it.key) }
      .maxByOrNull { it.key.length }
  return prefixMatch?.value?.bytes ?: config.defaultMax.bytes
}
