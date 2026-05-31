package ed.unicoach.rest.plugins

import ed.unicoach.rest.config.RequestSizeConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.path

fun Application.configureRequestSizeLimit(config: RequestSizeConfig) {
  install(RequestBodyLimit) {
    bodyLimit { call ->
      config.routeOverrides[call.request.path()]?.bytes ?: config.defaultMax.bytes
    }
  }
}
