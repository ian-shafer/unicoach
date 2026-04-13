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

class RoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      // Boot up the server matching application bootstrapping precisely using common config
      testServer = startServer(wait = false)
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
  fun testHelloWorldEndpoint() =
    runBlocking {
      val response = client.get("http://localhost:$boundPort/hello")

      // TestCase 1: HTTP response is 200 OK
      assertEquals(HttpStatusCode.OK, response.status)
      // TestCase 2: Content-Type header mandates utf-8
      assertEquals("text/plain; charset=UTF-8", response.headers[HttpHeaders.ContentType])
      // TestCase 3: Body matches unicode string
      assertEquals("Hello, Ian. I love you 😘", response.bodyAsText())
    }
}
