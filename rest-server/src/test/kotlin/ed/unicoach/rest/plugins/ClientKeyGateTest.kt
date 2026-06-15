package ed.unicoach.rest.plugins

import ed.unicoach.rest.config.ClientKeyGateConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientKeyGateTest {
  private fun testApp(
    config: ClientKeyGateConfig,
    block: suspend (io.ktor.client.HttpClient) -> Unit,
  ) = testApplication {
    application {
      configureSerialization()
      configureClientKeyGate(config)
    }
    routing {
      get("/api/v1/ping") {
        call.respondText("pong")
      }
      get("/healthz") {
        call.respondText("healthz")
      }
      get("/healthzextra") {
        call.respondText("healthzextra")
      }
    }
    block(client)
  }

  @Test
  fun `valid key accepted reaches route`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("secret"), allowlistPaths = setOf("/healthz"))) { client ->
      val response =
        client.get("/api/v1/ping") {
          header(CLIENT_KEY_HEADER, "secret")
        }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("pong", response.bodyAsText())
    }

  @Test
  fun `each key in the set is accepted`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("kA", "kB"), allowlistPaths = setOf("/healthz"))) { client ->
      val responseA =
        client.get("/api/v1/ping") {
          header(CLIENT_KEY_HEADER, "kA")
        }
      assertEquals(HttpStatusCode.OK, responseA.status)

      val responseB =
        client.get("/api/v1/ping") {
          header(CLIENT_KEY_HEADER, "kB")
        }
      assertEquals(HttpStatusCode.OK, responseB.status)
    }

  @Test
  fun `invalid key rejected with 403 and does not reach route`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("secret"), allowlistPaths = setOf("/healthz"))) { client ->
      val response =
        client.get("/api/v1/ping") {
          header(CLIENT_KEY_HEADER, "wrong")
        }
      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertTrue(response.bodyAsText().contains("\"forbidden\""))
    }

  @Test
  fun `missing header rejected with identical 403 body`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("secret"), allowlistPaths = setOf("/healthz"))) { client ->
      val missing = client.get("/api/v1/ping")
      val invalid =
        client.get("/api/v1/ping") {
          header(CLIENT_KEY_HEADER, "wrong")
        }
      assertEquals(HttpStatusCode.Forbidden, missing.status)
      assertEquals(invalid.bodyAsText(), missing.bodyAsText())
    }

  @Test
  fun `health-check path bypasses the gate with no header`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("secret"), allowlistPaths = setOf("/healthz"))) { client ->
      val response = client.get("/healthz")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("healthz", response.bodyAsText())
    }

  @Test
  fun `non-allowlisted lookalike path is not bypassed`() =
    testApp(ClientKeyGateConfig(validKeys = setOf("secret"), allowlistPaths = setOf("/healthz"))) { client ->
      val response = client.get("/healthzextra")
      assertEquals(HttpStatusCode.Forbidden, response.status)
    }

  @Test
  fun `disabled gate fails open when key set is empty`() =
    testApp(ClientKeyGateConfig(validKeys = emptySet(), allowlistPaths = setOf("/healthz"))) { client ->
      val response = client.get("/api/v1/ping")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("pong", response.bodyAsText())
    }
}
