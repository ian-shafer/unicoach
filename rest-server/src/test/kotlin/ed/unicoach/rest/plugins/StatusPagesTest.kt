package ed.unicoach.rest.plugins

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.StudentAlreadyExistsException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPagesTest {
  private fun probeFor(throwing: () -> Nothing) =
    testApplication {
      application {
        configureSerialization()
        configureStatusPages()
      }
      routing {
        get("/probe") {
          throwing()
        }
      }

      val response = client.get("/probe")
      // The block records both status and body for the caller via the captured vars.
      capturedStatus = response.status
      capturedBody = response.bodyAsText()
    }

  private var capturedStatus: HttpStatusCode? = null
  private var capturedBody: String = ""

  @Test
  fun `DatabaseException maps to 500`() {
    probeFor { throw DatabaseException(RuntimeException("disk gone")) }
    assertEquals(HttpStatusCode.InternalServerError, capturedStatus)
    assertTrue(capturedBody.contains("permanent_error"), "body was: $capturedBody")
  }

  @Test
  fun `CorruptPersistedValueException maps to 500`() {
    probeFor {
      throw CorruptPersistedValueException(
        value = "year=2028 month=null day=15",
        error = ValidationError.InvalidFormat(expected = "YYYY | YYYY-MM | YYYY-MM-DD"),
      )
    }
    assertEquals(HttpStatusCode.InternalServerError, capturedStatus)
    assertTrue(capturedBody.contains("permanent_error"), "body was: $capturedBody")
  }

  @Test
  fun `a client-fault PermanentError still maps to 400`() {
    probeFor { throw StudentAlreadyExistsException() }
    assertEquals(HttpStatusCode.BadRequest, capturedStatus)
    assertTrue(capturedBody.contains("permanent_error"), "body was: $capturedBody")
  }
}
