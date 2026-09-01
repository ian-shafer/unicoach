package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegeListEntrySupportDao
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.NewCollegeListEntry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollegeListEntriesResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  private var ipedsUnitIdCounter = 960000

  private data class SeededEntry(
    val entryId: String,
    val studentId: String,
    val collegeId: String,
  )

  private fun seedEntry(reasons: String? = "Great fit"): SeededEntry =
    runBlocking {
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val college = AdminTestSupport.seedCollege(ipedsUnitIdCounter++)
      val entry =
        AdminTestSupport.database
          .withConnection { session ->
            CollegeListEntriesDao.create(
              session,
              NewCollegeListEntry(student.id, college.id, CollegeListEntryStatus.CONSIDERING, reasons, null),
            )
          }.getOrThrow()
      SeededEntry(
        entryId = entry.id.value.toString(),
        studentId = student.id.value.toString(),
        collegeId = college.id.value.toString(),
      )
    }

  @Test
  fun `GET college-list-entry lists entries`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedEntry()

      val list = client().get("/college-list-entry") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains("/college-list-entry/${seeded.entryId}"), "List must link to the entry detail page")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/college-list-entry"), "Dashboard must link to /college-list-entry")
    }

  @Test
  fun `GET college-list-entry id renders persisted fields`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedEntry("Strong academics")

      val detail = client().get("/college-list-entry/${seeded.entryId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("/student/${seeded.studentId}"), "Detail must link to the owning student")
      assertTrue(body.contains("considering"), "Detail must render the status")
      assertTrue(body.contains("Strong academics"), "Detail must render the reasons")
    }

  @Test
  fun `no create edit delete or undelete affordance is registered`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedEntry()

      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/college-list-entry/new") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/college-list-entry/${seeded.entryId}/edit") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      val detail = client().get("/college-list-entry/${seeded.entryId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/college-list-entry/${seeded.entryId}/edit"), "No edit control")
      assertFalse(detail.contains("/college-list-entry/${seeded.entryId}/delete"), "No delete control")
      assertFalse(detail.contains("/college-list-entry/${seeded.entryId}/undelete"), "No undelete control")
    }

  @Test
  fun `a soft-deleted entry remains visible and marked deleted at its admin detail route`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedEntry()

      runBlocking {
        val entryId =
          ed.unicoach.db.models
            .CollegeListEntryId(UUID.fromString(seeded.entryId))
        AdminTestSupport.database
          .withConnection { session -> CollegeListEntriesDao.delete(session, entryId, 1) }
          .getOrThrow()
      }

      val detail = client().get("/college-list-entry/${seeded.entryId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "A soft-deleted entry must still be reachable")
      val body = detail.bodyAsText().lowercase()
      assertTrue(body.contains("deleted"), "Detail must mark the entry deleted")
    }

  @Test
  fun `the supporting-observations edge panel renders linked observations and is empty for an uncited entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedEntry()

      val emptyDetail = client().get("/college-list-entry/${seeded.entryId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(emptyDetail.contains("Supporting observations"), "Detail must render the panel label")

      val quote = "I love the engineering program"
      runBlocking {
        val entryId =
          ed.unicoach.db.models
            .CollegeListEntryId(UUID.fromString(seeded.entryId))
        val studentId =
          ed.unicoach.db.models
            .StudentId(UUID.fromString(seeded.studentId))
        val convo = AdminTestSupport.seedConvo(studentId)
        val req = AdminTestSupport.seedConvoRequest(convo.id)
        val observation = AdminTestSupport.seedObservation(studentId, convo.id, req.id, quote)
        AdminTestSupport.database
          .withConnection { session -> CollegeListEntrySupportDao.link(session, entryId, observation.id) }
          .getOrThrow()
      }

      val citedDetail = client().get("/college-list-entry/${seeded.entryId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(citedDetail.contains(quote), "Detail must render the linked observation's quote")
    }
}
