package ed.unicoach.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class HealthzRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      // Boot up the server matching application bootstrapping precisely using common config.
      // port = 0 binds an ephemeral port so concurrent test runs never collide on a fixed port.
      testServer = startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) {
        testServer.stop(1000, 5000)
      }
      if (::client.isInitialized) {
        client.close()
      }
    }
  }

  @Test
  fun healthzReturns200WithStatusOkBody() {
    runBlocking {
      val response = client.get("http://localhost:$boundPort/healthz")

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("application/json", response.headers[HttpHeaders.ContentType])
      assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }
  }

  @Test
  fun helloIsGone() {
    runBlocking {
      val response = client.get("http://localhost:$boundPort/hello")

      assertEquals(HttpStatusCode.NotFound, response.status)
    }
  }
}
