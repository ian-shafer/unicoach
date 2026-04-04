package ed.unicoach.rest

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting() {
  routing {
    get("/hello") {
      call.respondText(
        text = "Hello, Ian. I love you 😘",
        contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
      )
    }
  }
}
