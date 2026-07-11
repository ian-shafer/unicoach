package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvosResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user/student/convo chain plus one request+response turn. */
  private fun seedConvoWithTurn(name: String): SeededConvo {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val convo = AdminTestSupport.seedConvo(student.id, name)
    val llmRequestId = AdminTestSupport.seedLlmRequest()
    val req = AdminTestSupport.seedConvoRequest(convo.id, llmRequestId = llmRequestId)
    AdminTestSupport.seedLlmResponse(llmRequestId)
    return SeededConvo(
      convoId = convo.id.value.toString(),
      studentId = student.id.value.toString(),
      requestId = req.id.value.toString(),
    )
  }

  private data class SeededConvo(
    val convoId: String,
    val studentId: String,
    val requestId: String,
  )

  private data class EnqueuedJob(
    val jobType: String,
    val payload: String,
  )

  /** The single row in `jobs`, or null when the table is empty. Fails if more than one row exists. */
  private fun findSoleJob(): EnqueuedJob? =
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        session.prepareStatement("SELECT job_type, payload FROM jobs").use { stmt ->
          stmt.executeQuery().use { rs ->
            if (!rs.next()) {
              return@withConnection null
            }
            val job = EnqueuedJob(rs.getString("job_type"), rs.getString("payload"))
            assertTrue(!rs.next(), "Expected exactly one enqueued job")
            job
          }
        }
      }
    }

  @Test
  fun `GET convo lists conversations`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = "CONVO_MARKER_${UUID.randomUUID()}"
      val seeded = seedConvoWithTurn(name)

      val list = client().get("/convo") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(name), "List must render the convo name")
      assertTrue(body.contains("/convo/${seeded.convoId}"), "List must link to the convo detail page")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/convo"), "Dashboard must link to /convo")
    }

  @Test
  fun `GET convo id compacts the id and studentId UUID fields`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Compact convo ${UUID.randomUUID()}")

      val detail = client().get("/convo/${seeded.convoId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()

      // id (refSlug "convo") and studentId (refSlug "student") are now FieldType.UUID:
      // each compacts to ellipsis + last 8 chars while keeping the full value reachable
      // and its navigation glyph intact.
      AdminTestSupport.assertCompactUuid(body, seeded.convoId, "/convo/${seeded.convoId}")
      AdminTestSupport.assertCompactUuid(body, seeded.studentId, "/student/${seeded.studentId}")
    }

  @Test
  fun `GET convo id renders fields and turns panel`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Detail convo ${UUID.randomUUID()}")

      val detail = client().get("/convo/${seeded.convoId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("/student/${seeded.studentId}"), "Detail must link to the owning student")
      assertTrue(body.contains("Last Activity"), "Detail must render the derived last-activity field")
      assertTrue(body.contains("Turns"), "Detail must render the Turns panel")
      assertTrue(body.contains("/convo-request/${seeded.requestId}"), "A Turns row must link to /convo-request/{id}")
    }

  @Test
  fun `GET convo id Turns panel is capped and shows a truncation disclosure`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id, "Big convo ${UUID.randomUUID()}")
      // One more than the panel cap so the disclosure row appears.
      val requestIds =
        (1..51).map {
          AdminTestSupport
            .seedConvoRequest(convo.id)
            .id.value
            .toString()
        }

      val detail = client().get("/convo/${convo.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(
        body.contains("Showing first 50 — see /convo-request for full list"),
        "An over-cap Turns panel must show the truncation disclosure",
      )
      // The 51st (oldest-ordered last) turn is past the cap and must not render.
      val renderedTurnLinks = requestIds.count { body.contains("/convo-request/$it") }
      assertEquals(50, renderedTurnLinks, "The Turns panel must render at most TURNS_PANEL_LIMIT rows")
    }

  @Test
  fun `GET convo id marks a deleted conversation`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Deleted convo ${UUID.randomUUID()}")
      runBlocking {
        AdminTestSupport.database
          .withConnection { session ->
            ConvosDao.delete(
              session,
              ed.unicoach.db.models
                .ConvoId(UUID.fromString(seeded.convoId)),
            )
          }.getOrThrow()
      }

      val detail = client().get("/convo/${seeded.convoId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "A deleted convo must still be reachable")
      val body = detail.bodyAsText().lowercase()
      assertTrue(body.contains("deleted"), "Detail must mark the convo deleted")
    }

  @Test
  fun `GET convo id returns 404 for missing id`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val res = client().get("/convo/${UUID.randomUUID()}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status)
    }

  @Test
  fun `GET convo id returns 404 for malformed id`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val res = client().get("/convo/not-a-uuid") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status, "parseId must reject non-UUID segments")
    }

  @Test
  fun `convo has no create edit or delete controls`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Read only ${UUID.randomUUID()}")

      assertEquals(HttpStatusCode.NotFound, client().get("/convo/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/convo/${seeded.convoId}/edit") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      val detail = client().get("/convo/${seeded.convoId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/convo/${seeded.convoId}/edit"), "No edit control")
      assertFalse(detail.contains("/convo/${seeded.convoId}/delete"), "No delete control")
    }

  @Test
  fun `trigger-extraction with at least one request enqueues EXTRACT_CONVERSATION through the latest request`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Trigger convo ${UUID.randomUUID()}")

      val res =
        client().submitForm(
          url = "/convo/${seeded.convoId}/trigger-extraction",
          formParameters = parameters {},
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/convo/${seeded.convoId}", res.headers[HttpHeaders.Location])

      val job = findSoleJob()
      assertTrue(job != null, "A job row must be inserted")
      assertEquals("EXTRACT_CONVERSATION", job.jobType)
      // Normalize whitespace: Postgres re-serializes the stored JSONB with spaces after colons.
      val payload = job.payload.replace(" ", "")
      assertTrue(payload.contains("\"convoId\":\"${seeded.convoId}\""), "Payload carries the convo id: ${job.payload}")
      // throughRequestId is the convo's latest request id (a BIGINT, serialized unquoted).
      assertTrue(
        payload.contains("\"throughRequestId\":${seeded.requestId}"),
        "Payload's throughRequestId must be the latest request id: ${job.payload}",
      )
    }

  @Test
  fun `trigger-extraction with zero requests enqueues nothing and responds with an error`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      // A convo with no requests: findLatestRequestIdForConvo is null.
      val convo = AdminTestSupport.seedConvo(student.id, "Empty convo ${UUID.randomUUID()}")

      val res =
        client().submitForm(
          url = "/convo/${convo.id.value}/trigger-extraction",
          formParameters = parameters {},
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status, "A convo with no requests must respond with an error, not a redirect")
      assertNull(findSoleJob(), "No job may be enqueued for a request-less convo")
    }

  @Test
  fun `convo detail renders the extraction trigger button with its help caption`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedConvoWithTurn("Help caption convo ${UUID.randomUUID()}")

      val body = client().get("/convo/${seeded.convoId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(body.contains("/convo/${seeded.convoId}/trigger-extraction"), "Extraction trigger action present")
      assertTrue(body.contains("watermark"), "The extraction help caption renders")
      assertNull(findSoleJob(), "Rendering the detail page must not enqueue a job")
    }

  /**
   * A [JobsDao] whose insert always fails at the DB, so a [QueueService] built on
   * it returns [ed.unicoach.queue.EnqueueResult.DatabaseFailure] — the seam the
   * constructor-injected [QueueService] (RFC 100 F7) opens for exercising the
   * enqueue-failure branch of a trigger handler.
   */
  private class FailingJobsDao : JobsDao() {
    override fun insert(
      session: SqlSession,
      newJob: NewJob,
    ): JobInsertResult = JobInsertResult.DatabaseFailure(Exception("enqueue failed"))
  }

  @Test
  fun `trigger-extraction renders the DAO-error page and enqueues nothing when the queue insert fails`() =
    testApplication {
      // Inject a QueueService whose enqueue fails at the DB — the branch that is
      // only reachable now that ConvosResource takes QueueService via its
      // constructor rather than instantiating it inline.
      val failingQueue = QueueService(AdminTestSupport.database, FailingJobsDao())
      val resource = ConvosResource(failingQueue)
      application {
        routing { resource.registerExtraRoutes(this, AdminTestSupport.database) }
      }
      val seeded = seedConvoWithTurn("Enqueue failure convo ${UUID.randomUUID()}")

      val res =
        client().submitForm(
          url = "/convo/${seeded.convoId}/trigger-extraction",
          formParameters = parameters {},
        )
      assertEquals(
        HttpStatusCode.InternalServerError,
        res.status,
        "An enqueue DatabaseFailure must render the shared DAO-error page (500), not a redirect",
      )
      assertNull(findSoleJob(), "No job may be enqueued when the queue insert fails")
    }
}
