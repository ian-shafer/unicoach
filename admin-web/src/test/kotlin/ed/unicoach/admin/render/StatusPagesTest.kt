package ed.unicoach.admin.render

import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.LockAcquisitionFailureException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the admin server's top-level failure net. The defect this exists to
 * prevent: an arbitrary throwable being rendered as the "transient database
 * error" 503 page with no log entry. A genuine transient error is the only thing
 * that may render 503; everything else is an unexpected 500.
 */
class StatusPagesTest {
  private var capturedStatus: HttpStatusCode? = null
  private var capturedBody: String = ""

  private fun probeFor(throwing: () -> Nothing) =
    testApplication {
      application { configureAdminStatusPages() }
      routing {
        get("/probe") { throwing() }
      }

      val response = client.get("/probe")
      capturedStatus = response.status
      capturedBody = response.bodyAsText()
    }

  @Test
  fun `a genuine transient error renders the 503 retry page`() {
    probeFor { throw LockAcquisitionFailureException() }
    assertEquals(HttpStatusCode.ServiceUnavailable, capturedStatus)
    assertTrue(capturedBody.contains("transient database error"), "body was: $capturedBody")
  }

  @Test
  fun `a permanent database error renders 500, not a transient 503`() {
    probeFor { throw DatabaseException(RuntimeException("disk gone")) }
    assertEquals(HttpStatusCode.InternalServerError, capturedStatus)
    assertTrue(capturedBody.contains("unexpected error"), "body was: $capturedBody")
  }

  @Test
  fun `an arbitrary unexpected throwable renders 500, not a transient 503`() {
    probeFor { throw IllegalStateException("boom") }
    assertEquals(HttpStatusCode.InternalServerError, capturedStatus)
    assertTrue(capturedBody.contains("unexpected error"), "body was: $capturedBody")
  }
}
