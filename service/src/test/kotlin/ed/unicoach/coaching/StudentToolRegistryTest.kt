package ed.unicoach.coaching

import ed.unicoach.coaching.report.CostReportConfig
import ed.unicoach.coaching.report.CostReportShareService
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The student coach's ONE tool list (RFC 262): both roots — `rest-server` and
 * `public-web` — advertise exactly what [StudentToolRegistry.standard] builds,
 * so this is the list a student meets through either door.
 */
class StudentToolRegistryTest {
  @Test
  fun `the standard registry advertises the twenty student tools, once each, in wire order`() {
    runBlocking {
      val database = CoachingTestDb.database
      val config =
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow()
      val invites =
        SupporterInviteService(
          database,
          SupporterInviteConfig.from(config).getOrThrow(),
          TokenGenerator(),
          SupporterService(database, LinkEndedNotice(QueueService(database))),
        )
      val shares = CostReportShareService(database, CostReportConfig.from(config).getOrThrow(), null)

      val names =
        StudentToolRegistry
          .standard(database, invites, shares)
          .definitions()
          .map { it.getValue("name").jsonPrimitive.content }

      assertEquals(
        listOf(
          "search_colleges",
          "find_college",
          "similar_colleges",
          "update_money_profile",
          "college_cost_profile",
          "update_college_list",
          "college_admissions_profile",
          "federal_aid_policy",
          "fafsa_process",
          "fafsa_deadlines",
          "share_cost_report",
          "revoke_cost_report_share",
          "stop_cost_report_offers",
          "invite_supporter",
          "admission_test_timeline",
          "college_automatic_awards",
          "psat_national_merit",
          "update_test_scores",
          "update_intended_subjects",
          "update_sharing_permission",
        ),
        names,
      )
    }
  }
}
