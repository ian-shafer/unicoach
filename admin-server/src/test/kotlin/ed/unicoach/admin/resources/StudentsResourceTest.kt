package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudentsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  @Test
  fun `embedded student panel offers create then renders edit and delete inline`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())

      // No profile: a create panel is offered.
      val before = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(before.contains("No Student yet"), "Expected an empty-profile create prompt")
      assertTrue(before.contains("/user/${user.id.value}/student"), "Expected the nested create action endpoint")

      // Create the embedded student.
      val created =
        client().submitForm(
          url = "/user/${user.id.value}/student",
          formParameters = parameters { append("expectedHighSchoolGraduationDate", "2028-06") },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, created.status)

      val afterCreate = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(afterCreate.contains("2028-06"), "Created grad date should render in the panel")
      assertTrue(afterCreate.contains("/user/${user.id.value}/student/update"), "Edit action present")
      assertTrue(afterCreate.contains("/user/${user.id.value}/student/delete"), "Delete action present")

      // Update inline.
      val updated =
        client().submitForm(
          url = "/user/${user.id.value}/student/update",
          formParameters = parameters { append("expectedHighSchoolGraduationDate", "2030-09-15") },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, updated.status)
      val afterUpdate = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(afterUpdate.contains("2030-09-15"), "Updated grad date should render")

      // Soft-delete inline.
      val deleted =
        client().submitForm(
          url = "/user/${user.id.value}/student/delete",
          formParameters = parameters {},
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, deleted.status)
    }

  @Test
  fun `no standalone student route resolves`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)

      // EMBEDDED_ENTITY has no /student list or detail page.
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/student/${student.id.value}") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/student") { header(HttpHeaders.Cookie, cookie) }.status,
      )
    }

  @Test
  fun `user page renders the three coaching-memory panels each linking to canonical detail URLs`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val claim = AdminTestSupport.seedClaim(student.id)
      val obs = AdminTestSupport.seedObservation(student.id, convo.id, req.id)
      val run = AdminTestSupport.seedExtractionRun(student.id, convo.id, req.id)

      val body = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()

      assertTrue(body.contains("Claims"), "Claims panel must render")
      assertTrue(body.contains("Observations"), "Observations panel must render")
      assertTrue(body.contains("Extraction runs"), "Extraction runs panel must render")

      assertTrue(body.contains("/claim/${claim.id.value}"), "Claim row links to the canonical /claim/{id} path")
      assertTrue(body.contains("/observation/${obs.id.value}"), "Observation row links to the canonical /observation/{id} path")
      assertTrue(body.contains("/extraction-run/${run.id.value}"), "Run row links to the canonical /extraction-run/{id} path")
    }

  @Test
  fun `coaching-memory and embedded panels apply the display conventions`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val claim = AdminTestSupport.seedClaim(student.id)

      val body = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()

      // The Claims edge-panel id cell carries a glyph link to the canonical claim.
      assertTrue(body.contains("/claim/${claim.id.value}"), "Claim id cell must link to canonical detail")
      assertTrue(body.contains("🔗"), "Claim id cell must carry the link glyph")

      // The claim's Created cell renders as a formatted date carrying the source ISO title.
      val claimCreatedIso = claim.createdAt.toString()
      assertTrue(body.contains("title=\"$claimCreatedIso\""), "Created cell must carry the source ISO as a hover title")

      // The embedded student panel's Created field (a TIMESTAMP LabeledCell) renders
      // as a formatted date with the source ISO title, exercising the embedded path.
      val studentCreatedIso = student.createdAt.toString()
      assertTrue(
        body.contains("title=\"$studentCreatedIso\""),
        "Embedded student timestamp field must render as a formatted date with the source ISO title",
      )
    }

  @Test
  fun `a student with no coaching memory renders the three panels empty without error`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      AdminTestSupport.seedStudent(user.id)

      val res = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, res.status, "An empty-memory student page must render, not error")
      val body = res.bodyAsText()
      assertTrue(body.contains("Claims"), "Claims panel still renders when empty")
      assertTrue(body.contains("Observations"), "Observations panel still renders when empty")
      assertTrue(body.contains("Extraction runs"), "Extraction runs panel still renders when empty")
    }

  @Test
  fun `claims panel discloses truncation when the student has more than the panel limit`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      // One more than STUDENT_PANEL_LIMIT (50) so the page fills and a row remains.
      repeat(51) { AdminTestSupport.seedClaim(student.id) }

      val body = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(
        body.contains("Showing first 50 — see /claim for full list"),
        "Truncated claims panel must disclose the cap and point at the canonical /claim list",
      )
    }

  @Test
  fun `user page renders both the users and the embedded students version history`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      AdminTestSupport.seedStudent(user.id)

      val body = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      // The users_versions history panel (the user's own) and the embedded
      // students_versions history panel both render under the user page.
      val historyCount = Regex("Version history").findAll(body).count()
      assertTrue(historyCount >= 2, "Expected user and student version-history panels, found $historyCount")
    }
}
