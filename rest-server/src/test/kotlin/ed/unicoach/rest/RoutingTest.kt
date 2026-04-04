package ed.unicoach.rest

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutingTest {
  @Test
  fun testHelloWorldEndpoint() =
    testApplication {
      environment {
        config = ApplicationConfig("rest-server.conf")
      }

      val response = client.get("/hello")

      // TestCase 1: HTTP response is 200 OK
      assertEquals(HttpStatusCode.OK, response.status)
      // TestCase 2: Content-Type header mandates utf-8
      assertEquals("text/plain; charset=utf-8", response.headers[HttpHeaders.ContentType])
      // TestCase 3: Body matches unicode string
      assertEquals("Hello, Ian. I love you 😘", response.bodyAsText())
    }
}
