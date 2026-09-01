package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyProfileEdit
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.NewMoneyProfile
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

class MoneyProfilesResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  private data class SeededProfile(
    val profileId: String,
    val studentId: String,
  )

  private fun seedProfile(): SeededProfile =
    runBlocking {
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val profile =
        AdminTestSupport.database
          .withConnection { session ->
            MoneyProfilesDao.create(
              session,
              NewMoneyProfile(
                studentId = student.id,
                incomeBand = IncomeBand.K48_TO_75K,
                incomeBandStatus = AnswerStatus.ANSWERED,
                residencyState = "CA",
                residencyStatus = AnswerStatus.ANSWERED,
                livingPlan = LivingArrangement.WITH_FAMILY,
                livingPlanStatus = AnswerStatus.ANSWERED,
              ),
            )
          }.getOrThrow()
      SeededProfile(profileId = profile.id.value.toString(), studentId = student.id.value.toString())
    }

  @Test
  fun `GET money-profile lists profiles and the dashboard links to the resource`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedProfile()

      val list = client().get("/money-profile") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains("/money-profile/${seeded.profileId}"), "List must link to the profile detail page")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/money-profile"), "Dashboard must link to /money-profile")
    }

  @Test
  fun `GET money-profile id renders statuses and redacts the sensitive values`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedProfile()

      val detail = client().get("/money-profile/${seeded.profileId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("/student/${seeded.studentId}"), "Detail must link to the owning student")
      assertTrue(body.contains("answered"), "Detail must render the field statuses")
      assertTrue(body.contains("(redacted)"), "Sensitive value columns must be redacted")
      assertFalse(body.contains("48k_to_75k"), "The income band value must not render")
      assertFalse(body.contains(">CA<"), "The residency state value must not render")
      assertFalse(
        body.contains(LivingArrangement.WITH_FAMILY.value),
        "The living plan value must not render (RFC 152: family finances are sensitive alike)",
      )
    }

  @Test
  fun `the version-history panel renders one row per version with statuses only`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedProfile()

      runBlocking {
        val id = MoneyProfileId(UUID.fromString(seeded.profileId))
        AdminTestSupport.database
          .withConnection { session ->
            MoneyProfilesDao.update(
              session,
              MoneyProfileEdit(
                id = id,
                version = 1,
                incomeBand = null,
                incomeBandStatus = AnswerStatus.DECLINED,
                residencyState = "CA",
                residencyStatus = AnswerStatus.ANSWERED,
                livingPlan = LivingArrangement.WITH_FAMILY,
                livingPlanStatus = AnswerStatus.ANSWERED,
              ),
            )
          }.getOrThrow()
      }

      val body = client().get("/money-profile/${seeded.profileId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(body.contains("Version history"), "Detail must render the history panel")
      assertTrue(body.contains("declined"), "History must show the declined version's status")
      assertFalse(body.contains("48k_to_75k"), "History must not leak the redacted income band value")
      // RFC 152: the third value column joins the same rule. The history panel
      // ships STATUSES only, so the plan the family stated never reaches it.
      assertTrue(body.contains("Living Plan Status"), "History must show the living-plan status column")
      assertFalse(
        body.contains(LivingArrangement.WITH_FAMILY.value),
        "History must not leak the redacted living plan value",
      )
    }

  @Test
  fun `no create edit delete or undelete affordance is registered`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedProfile()

      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/money-profile/new") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/money-profile/${seeded.profileId}/edit") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      val detail = client().get("/money-profile/${seeded.profileId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/money-profile/${seeded.profileId}/edit"), "No edit control")
      assertFalse(detail.contains("/money-profile/${seeded.profileId}/delete"), "No delete control")
      assertFalse(detail.contains("/money-profile/${seeded.profileId}/undelete"), "No undelete control")
    }

  @Test
  fun `a soft-deleted profile remains visible and marked deleted at its admin detail route`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedProfile()

      runBlocking {
        val id = MoneyProfileId(UUID.fromString(seeded.profileId))
        AdminTestSupport.database
          .withConnection { session -> MoneyProfilesDao.delete(session, id, 1) }
          .getOrThrow()
      }

      val detail = client().get("/money-profile/${seeded.profileId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "A soft-deleted profile must still be reachable")
      val body = detail.bodyAsText().lowercase()
      assertTrue(body.contains("deleted"), "Detail must mark the profile deleted")
    }
}
