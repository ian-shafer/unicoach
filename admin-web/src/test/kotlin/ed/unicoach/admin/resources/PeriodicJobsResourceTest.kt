package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.cron.PeriodicJobName
import ed.unicoach.cron.dao.PeriodicFindResult
import ed.unicoach.cron.dao.PeriodicJobsDao
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodicJobsResourceTest {
  private val periodicJobsDao = PeriodicJobsDao()

  @BeforeTest
  fun reset() {
    AdminTestSupport.resetDatabase()
    // periodic_jobs is not cascade-reachable from users/colleges, so clear it
    // (and the migration's seeded `synthesis` row) explicitly.
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE periodic_jobs").use { it.executeUpdate() }
      }
    }
  }

  /** Seeds one periodic_jobs row and returns its name. */
  private fun seedJob(
    name: String = "synthesis",
    enabled: Boolean = false,
  ): PeriodicJobName {
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        session
          .prepareStatement(
            """
            INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
            VALUES (?, 'SYNTHESIS_SWEEP', '{}'::jsonb, '0 3 * * *', 'UTC', ?, ?)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setString(1, name)
            stmt.setTimestamp(2, java.sql.Timestamp.from(Instant.parse("2030-01-01T03:00:00Z")))
            stmt.setBoolean(3, enabled)
            stmt.executeUpdate()
          }
      }
    }
    return PeriodicJobName(name)
  }

  /** Seeds one corrupt periodic_jobs row (unknown job_type) and returns its name. */
  private fun seedCorruptJob(
    name: String = "corrupt-job",
    enabled: Boolean = true,
  ): PeriodicJobName {
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        session
          .prepareStatement(
            """
            INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
            VALUES (?, 'NO_SUCH_JOB_TYPE', '{}'::jsonb, '0 3 * * *', 'UTC', ?, ?)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setString(1, name)
            stmt.setTimestamp(2, java.sql.Timestamp.from(Instant.parse("2030-01-01T03:00:00Z")))
            stmt.setBoolean(3, enabled)
            stmt.executeUpdate()
          }
      }
    }
    return PeriodicJobName(name)
  }

  private fun enabledOf(name: PeriodicJobName): Boolean =
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        (periodicJobsDao.findByName(session, name) as PeriodicFindResult.Success).job.enabled
      }
    }

  /** Reads the raw enabled flag (findByName reports Corrupt for a corrupt row, not its flag). */
  private fun rawEnabledOf(name: PeriodicJobName): Boolean =
    runBlocking {
      AdminTestSupport.database.withConnection { session ->
        session.prepareStatement("SELECT enabled FROM periodic_jobs WHERE name = ?").use { stmt ->
          stmt.setString(1, name.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean("enabled")
          }
        }
      }
    }

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  @Test
  fun `list and detail render the schedule fields and no edit or delete affordance`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedJob(name = "synthesis", enabled = false)

      val list = client().get("/periodic-job") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val listBody = list.bodyAsText()
      assertTrue(listBody.contains("synthesis"), "List renders the row name")
      assertTrue(listBody.contains("0 3 * * *"), "List renders the schedule")

      val detail = client().get("/periodic-job/${name.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("0 3 * * *"), "Detail renders schedule")
      assertTrue(body.contains("2030-01-01"), "Detail renders next_run_at")
      assertFalse(body.contains("/periodic-job/${name.value}/edit"), "No edit affordance")
      assertFalse(body.contains("/periodic-job/${name.value}/delete"), "No delete affordance")
    }

  @Test
  fun `edit route is 404 - the resource is read-only`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedJob()

      val edit = client().get("/periodic-job/${name.value}/edit") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, edit.status, "No edit form for a read-only resource")
    }

  @Test
  fun `a disabled row offers Enable active and Disable disabled, and enabling flips them`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedJob(name = "synthesis", enabled = false)

      val before = client().get("/periodic-job/${name.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(before.contains("Enable"), "Detail offers the Enable action")
      assertTrue(before.contains("Disable"), "Detail offers the Disable action")
      assertTrue(before.contains("Already disabled."), "The Disable button is disabled with its reason")

      val enable =
        client().submitForm(url = "/periodic-job/${name.value}/enable", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, enable.status)
      assertEquals("/periodic-job/${name.value}", enable.headers[HttpHeaders.Location])
      assertTrue(enabledOf(name), "The row re-reads enabled = true after the enable POST")

      val after = client().get("/periodic-job/${name.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(after.contains("Already enabled."), "Now the Enable button is disabled with its reason")
    }

  @Test
  fun `disabling an enabled row flips it back`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedJob(name = "synthesis", enabled = true)

      val disable =
        client().submitForm(url = "/periodic-job/${name.value}/disable", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, disable.status)
      assertEquals("/periodic-job/${name.value}", disable.headers[HttpHeaders.Location])
      assertFalse(enabledOf(name), "The row re-reads enabled = false after the disable POST")
    }

  @Test
  fun `enable on an unknown name returns 404`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val enable =
        client().submitForm(url = "/periodic-job/does-not-exist/enable", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, enable.status, "An unknown name -> setEnabled NotFound -> 404")
    }

  @Test
  fun `the list renders healthy rows with a corrupt row present - no 500`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      seedJob(name = "synthesis", enabled = false)
      seedCorruptJob(name = "corrupt-job")

      val list = client().get("/periodic-job") { header(HttpHeaders.Cookie, cookie) }
      // One corrupt row must not 500 the whole list (which would hide every healthy row).
      assertEquals(HttpStatusCode.OK, list.status, "A corrupt row must not 500 the admin list")
      val body = list.bodyAsText()
      assertTrue(body.contains("synthesis"), "The healthy row still renders alongside a corrupt row")
    }

  @Test
  fun `a corrupt row's detail responds 404 (named), not a 500`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedCorruptJob(name = "corrupt-job")

      val detail = client().get("/periodic-job/${name.value}") { header(HttpHeaders.Cookie, cookie) }
      // A corrupt row cannot render as a PeriodicJob detail; it must degrade to a
      // 404 (not a bare 500) so the operator learns the row is corrupt, not that
      // the server broke.
      assertEquals(HttpStatusCode.NotFound, detail.status, "A corrupt row's detail is a named 404, not a 500")
    }

  @Test
  fun `disable works against a corrupt row's name (quarantine)`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = seedCorruptJob(name = "corrupt-job", enabled = true)

      val disable =
        client().submitForm(url = "/periodic-job/${name.value}/disable", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, disable.status, "Disabling a corrupt row redirects (Success)")
      assertEquals("/periodic-job/${name.value}", disable.headers[HttpHeaders.Location])
      assertFalse(rawEnabledOf(name), "The corrupt row re-reads enabled = false after the disable POST")
    }

  @Test
  fun `parseId allowlists a lowercase slug and rejects out-of-allowlist names`() {
    // The regex allowlist is what keeps CR/LF//?/# out of the redirect Location
    // header; assert it directly on the resource's parseId.
    val resource = PeriodicJobsResource(periodicJobsDao)
    // Accepted: a plain lowercase slug.
    assertEquals(PeriodicJobName("synthesis"), resource.parseId("synthesis"))
    assertEquals(PeriodicJobName("daily-sweep-2"), resource.parseId("daily-sweep-2"))
    // Rejected: empty, uppercase, leading hyphen, and header-injection payloads.
    assertEquals(null, resource.parseId(""), "empty rejected")
    assertEquals(null, resource.parseId("Synthesis"), "uppercase rejected")
    assertEquals(null, resource.parseId("-lead"), "leading hyphen rejected")
    assertEquals(null, resource.parseId("a/b"), "slash rejected (path/redirect)")
    assertEquals(null, resource.parseId("a?b"), "query char rejected")
    assertEquals(null, resource.parseId("a#b"), "fragment char rejected")
    assertEquals(null, resource.parseId("a\r\nSet-Cookie: x"), "CR/LF header injection rejected")
    assertEquals(null, resource.parseId("a".repeat(PeriodicJobName.MAX_LENGTH + 1)), "over-length rejected")
  }
}
