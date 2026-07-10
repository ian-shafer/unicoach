package ed.unicoach.web.common.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestLoggingRoutingTest {
  private data class Payload(
    val ok: Boolean,
  )

  /**
   * Captures every log line the run emits by attaching a [ListAppender] to the
   * Logback root logger, then returns the single line CallLogging wrote — the one
   * shaped `METHOD uri -> status`. Root-level capture avoids depending on the
   * exact logger name CallLogging targets.
   */
  private fun captureLine(
    config: RequestLoggingConfig,
    exercise: suspend (io.ktor.client.HttpClient) -> Unit,
  ): String {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
      testApplication {
        application {
          configureRequestLogging(config, nanoTime = sequenceNanos())
          install(ContentNegotiation) { jackson() }
        }
        routing {
          route("/reset") {
            secretQueryParams("token")
            get {
              call.respond(Payload(true))
            }
          }
          get("/plain") {
            call.respond(Payload(true))
          }
        }
        exercise(client)
      }
    } finally {
      root.detachAppender(appender)
    }
    return appender.list
      .map { it.formattedMessage }
      .single { it.contains(" -> ") }
  }

  /** Two calls to nanoTime per request (start stamp, completion sample) 3ms apart. */
  private fun sequenceNanos(): () -> Long {
    var i = 0L
    return {
      val v = if (i % 2 == 0L) 0L else 3_000_000L
      i++
      v
    }
  }

  @Test
  fun `pre-handler 406 on an opted-in route redacts the token and reaches the formatter with the real status`() {
    val config =
      RequestLoggingConfig(
        secretHeaders = setOf("cookie", "authorization", "x-unicoach-client-key"),
        headers = HeaderSelection.Allowlist(setOf("Accept")),
        detail = Detail.FAILURE,
      )
    val line =
      captureLine(config) { client ->
        val response =
          client.get("/reset?token=abc&status=open") {
            header(HttpHeaders.Accept, "application/xml")
          }
        assertEquals(HttpStatusCode.NotAcceptable, response.status)
      }
    assertContains(line, "/reset?token=[redacted]&status=open")
    assertFalse(line.contains("token=abc"), "secret query value leaked: $line")
    assertContains(line, "-> 406")
    assertContains(line, "Accept=[application/xml]")
  }

  @Test
  fun `a 200 on a non-opted-in route logs the raw query unchanged`() {
    val config =
      RequestLoggingConfig(
        secretHeaders = setOf("cookie", "authorization", "x-unicoach-client-key"),
        headers = HeaderSelection.Allowlist(setOf("Accept")),
        detail = Detail.ALWAYS,
      )
    val line =
      captureLine(config) { client ->
        val response = client.get("/plain?token=abc&status=open")
        assertEquals(HttpStatusCode.OK, response.status)
      }
    assertContains(line, "/plain?token=abc&status=open")
    assertTrue(line.contains("-> 200"), "expected 200: $line")
  }
}
