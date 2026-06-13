package ed.unicoach.rest.plugins

import ed.unicoach.common.util.DataSize
import ed.unicoach.rest.config.RequestSizeConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestSizeLimitTest {
  @Test
  fun `global default rejects oversized body on a route with no override`() =
    testApplication {
      application {
        configureSerialization()
        configureStatusPages()
        configureRequestSizeLimit(
          RequestSizeConfig(defaultMax = DataSize.ofBytes(8192), routeOverrides = emptyMap()),
        )
      }
      routing {
        post("/probe") {
          call.receive<String>()
          call.respond(HttpStatusCode.OK)
        }
      }

      val oversizedBody = "x".repeat(8193)
      val response =
        client.post("/probe") {
          header(HttpHeaders.ContentLength, oversizedBody.length.toString())
          setBody(oversizedBody)
        }

      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertTrue(response.bodyAsText().contains("payload_too_large"))
    }

  @Test
  fun `route override tightens limit and is path-specific`() =
    testApplication {
      application {
        configureSerialization()
        configureStatusPages()
        configureRequestSizeLimit(
          RequestSizeConfig(
            defaultMax = DataSize.ofBytes(8192),
            routeOverrides = mapOf("/probe-override" to DataSize.ofBytes(1024)),
          ),
        )
      }
      routing {
        post("/probe-override") {
          call.receive<String>()
          call.respond(HttpStatusCode.OK)
        }
        post("/probe-default") {
          call.receive<String>()
          call.respond(HttpStatusCode.OK)
        }
      }

      val body = "x".repeat(2048)

      val overrideResponse =
        client.post("/probe-override") {
          header(HttpHeaders.ContentLength, body.length.toString())
          setBody(body)
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, overrideResponse.status)
      assertTrue(overrideResponse.bodyAsText().contains("payload_too_large"))

      val defaultResponse =
        client.post("/probe-default") {
          header(HttpHeaders.ContentLength, body.length.toString())
          setBody(body)
        }
      assertEquals(HttpStatusCode.OK, defaultResponse.status)
    }

  @Test
  fun `prefix override applies with longest-prefix resolution and exact wins`() {
    val config =
      RequestSizeConfig(
        defaultMax = DataSize.ofBytes(8192),
        routeOverrides = mapOf("/api/v1/conversations/special" to DataSize.ofBytes(2048)),
        routePrefixOverrides =
          mapOf(
            "/api/v1" to DataSize.ofBytes(16384),
            "/api/v1/conversations" to DataSize.ofBytes(524288),
          ),
      )

    // Longest matching prefix wins over a shorter one.
    assertEquals(524288L, resolveLimit(config, "/api/v1/conversations/abc/messages"))
    assertEquals(16384L, resolveLimit(config, "/api/v1/students"))
    // Exact override wins over any prefix.
    assertEquals(2048L, resolveLimit(config, "/api/v1/conversations/special"))
  }

  @Test
  fun `non-matching paths use the default`() {
    val config =
      RequestSizeConfig(
        defaultMax = DataSize.ofBytes(8192),
        routeOverrides = emptyMap(),
        routePrefixOverrides = mapOf("/api/v1/conversations" to DataSize.ofBytes(524288)),
      )
    assertEquals(8192L, resolveLimit(config, "/api/v1/auth/login"))
    assertEquals(8192L, resolveLimit(config, "/unrelated"))
  }
}
