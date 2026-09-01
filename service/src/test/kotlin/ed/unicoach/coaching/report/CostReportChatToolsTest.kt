package ed.unicoach.coaching.report

import ed.unicoach.coaching.report.ReportTestDb.SHARE_URL_BASE
import ed.unicoach.coaching.report.ReportTestDb.serviceWith
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat door of RFC 155: the payload the coach reads, and the ethos contract
 * that rides the tool descriptions. Both tools run against the real service and
 * a real database, on [ed.unicoach.coaching.MoneyProfileChatToolTest]'s
 * precedent — a hand-written fake of a two-method service would only prove that
 * the fake agrees with itself.
 */
class CostReportChatToolsTest {
  @BeforeEach
  fun resetDatabase() {
    ReportTestDb.reset()
  }

  private val service = serviceWith()

  private val shareTool = ShareCostReportChatTool(service)
  private val revokeTool = RevokeCostReportShareChatTool(service)

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun shareOf(result: JsonObject): JsonObject = result.getValue(ShareCostReportChatTool.RESULT_KEY).jsonObject

  private fun createStudent(): StudentId = ReportTestDb.createStudent("crt")

  @Test
  fun `the share definition carries the name and the ethos contract`() {
    assertEquals("share_cost_report", shareTool.name)
    assertEquals("share_cost_report", shareTool.definition["name"]!!.jsonPrimitive.content)
    val description = shareTool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("Only call this when the student asks"), "never shared without the student asking")
    assertTrue(description.contains("after a cost comparison has actually happened"), "value before ask")
    assertTrue(description.contains("anyone with the link can see it"), "the reach of the link is always spoken")
    assertTrue(description.contains("the report is live"), "a parent must not read it as a fixed document")
    assertTrue(description.contains("Asking again returns the same link"), "re-sharing never orphans a link already sent")
    assertTrue(description.contains("revoke it at any time"), "revocation is part of the offer, not a hidden setting")
    assertTrue(description.contains("Do not push"), "no nudge: that is first-value/06, not this slice")
    // Brief 0003's money words, and none of the retired ones.
    assertTrue(description.contains("tuition and fees") && description.contains("housing and food"))
    assertTrue(description.contains("the published price") && description.contains("a financial aid offer"))
    assertFalse(description.contains("room and board"))
    assertFalse(description.contains("sticker"))
    assertFalse(description.contains("award"))
  }

  @Test
  fun `the revoke definition says what revoking actually does`() {
    assertEquals("revoke_cost_report_share", revokeTool.name)
    val description = revokeTool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("every link the student has ever shared"), "revoke is a promise about every link")
    assertTrue(description.contains("safe to call when nothing is shared"))
  }

  @Test
  fun `sharing returns the link and the sentence about who can see it`() =
    runBlocking {
      val studentId = createStudent()

      val share = shareOf(shareTool.execute(studentId, input("{}")))

      val url = share.getValue("url").jsonPrimitive.content
      assertTrue(url.startsWith("$SHARE_URL_BASE?token="), "the coach is handed the real link: [$url]")
      assertTrue(share.getValue("newly_created").jsonPrimitive.boolean, "the first share is the one that mints")
      assertFalse(share.getValue("previous_link_no_longer_works").jsonPrimitive.boolean, "a first share kills nothing")
      assertEquals(ShareCostReportChatTool.WHO_CAN_SEE, share.getValue("who_can_see").jsonPrimitive.content)
      assertEquals(ShareCostReportChatTool.LIVE_REPORT, share.getValue("live_report").jsonPrimitive.content)
      assertTrue(share["previous_link_note"] == null, "there was no dead link to warn about")
    }

  @Test
  fun `a repeat share hands back the same link`() =
    runBlocking {
      val studentId = createStudent()

      val first = shareOf(shareTool.execute(studentId, input("{}")))
      val second = shareOf(shareTool.execute(studentId, input("{}")))

      assertEquals(
        first.getValue("url").jsonPrimitive.content,
        second.getValue("url").jsonPrimitive.content,
        "the student can re-send the link they already gave their parent",
      )
      assertFalse(
        second.getValue("newly_created").jsonPrimitive.boolean,
        "the coach must be able to say \"the same link as before\" rather than imply a new one",
      )
      assertFalse(second.getValue("previous_link_no_longer_works").jsonPrimitive.boolean)
      assertTrue(second["previous_link_note"] == null, "nothing died, so nothing is announced")
    }

  @Test
  fun `a share after a secret rotation warns that the earlier link is dead`() =
    runBlocking {
      val studentId = createStudent()
      val before = shareOf(shareTool.execute(studentId, input("{}")))

      val rotated = ShareCostReportChatTool(serviceWith("a-rotated-share-token-secret-long-enough"))
      val after = shareOf(rotated.execute(studentId, input("{}")))

      assertNotEquals(
        before.getValue("url").jsonPrimitive.content,
        after.getValue("url").jsonPrimitive.content,
      )
      assertTrue(after.getValue("newly_created").jsonPrimitive.boolean, "a rotation forces a genuinely new link")
      assertTrue(after.getValue("previous_link_no_longer_works").jsonPrimitive.boolean)
      assertEquals(ShareCostReportChatTool.PREVIOUS_LINK_NOTE, after.getValue("previous_link_note").jsonPrimitive.content)
    }

  @Test
  fun `an unconfigured secret declines with a sentence the coach can say`() =
    runBlocking {
      val studentId = createStudent()

      val result = ShareCostReportChatTool(serviceWith(secret = null)).execute(studentId, input("{}"))

      // A RESULT, not an error: a deployment with no secret is not a fault of
      // this student's request, and the model must be able to tell "there is
      // nothing to try" from "the write failed, try again".
      assertNull(errorOf(result), "an unconfigured secret is a result, never the error channel")
      val share = shareOf(result)
      assertFalse(share.getValue(ShareCostReportChatTool.LINK_CREATED_KEY).jsonPrimitive.boolean, "no link was created")
      assertEquals(
        ShareCostReportChatTool.UNAVAILABLE,
        share.getValue(ShareCostReportChatTool.STATEMENT_KEY).jsonPrimitive.content,
        "the decline is honest and speakable",
      )
      assertNull(share["url"], "no link is invented")
      // Revoking still answers without a secret.
      val revoke = RevokeCostReportShareChatTool(serviceWith(secret = null)).execute(studentId, input("{}"))
      assertTrue(errorOf(revoke) == null, "revocation needs no secret")
    }

  @Test
  fun `revoking says whether anything was live, and is safe to repeat`() =
    runBlocking {
      val studentId = createStudent()
      shareTool.execute(studentId, input("{}"))

      val revoked = shareOf(revokeTool.execute(studentId, input("{}")))
      assertTrue(revoked.getValue("revoked").jsonPrimitive.boolean)
      assertEquals(RevokeCostReportShareChatTool.REVOKED_STATEMENT, revoked.getValue("statement").jsonPrimitive.content)

      val again = shareOf(revokeTool.execute(studentId, input("{}")))
      assertFalse(again.getValue("revoked").jsonPrimitive.boolean, "nothing live is an outcome, not an error")
      assertEquals(RevokeCostReportShareChatTool.NOTHING_LIVE_STATEMENT, again.getValue("statement").jsonPrimitive.content)
      assertTrue(errorOf(again) == null, "a repeat revoke is never an error object")
    }

  @Test
  fun `revoking with nothing ever shared is not an error`() =
    runBlocking {
      val studentId = createStudent()

      val result = revokeTool.execute(studentId, input("{}"))

      assertFalse(shareOf(result).getValue("revoked").jsonPrimitive.boolean)
      assertTrue(errorOf(result) == null)
    }

  @Test
  fun `a surplus input field is refused rather than ignored`() =
    runBlocking {
      val studentId = createStudent()

      // Ends on a Unit-returning assertion on purpose: an expression body whose
      // last call is `assertNotNull` infers a non-Unit return type, and JUnit
      // drops a non-void @Test at discovery -- the test would compile, pass
      // review, and never run.
      val share = errorOf(shareTool.execute(studentId, input("""{"student_id": "someone-else"}""")))
      val revoke = errorOf(revokeTool.execute(studentId, input("""{"student_id": "someone-else"}""")))

      assertEquals("unknown field(s): [student_id]", share, "the share refusal must name the surplus key")
      assertEquals("unknown field(s): [student_id]", revoke, "the revoke refusal must name the surplus key")
    }

  @Test
  fun `an unscoped dispatch never mints a link`() =
    runBlocking {
      // The misroute guard on StudentScopedChatTool: without the turn's student
      // there is no one to mint for, and the tool says so instead of guessing.
      assertNotNull(errorOf(shareTool.execute(input("{}"))))
      assertNotNull(errorOf(revokeTool.execute(input("{}"))))
      ed.unicoach.coaching.CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT COUNT(*) FROM cost_report_shares").use { rs ->
          rs.next()
          assertEquals(0, rs.getInt(1), "a misrouted call must write nothing")
        }
      }
    }

  @Test
  fun `one student never receives another student's link`() =
    runBlocking {
      val one = createStudent()
      val other = createStudent()

      val oneUrl = shareOf(shareTool.execute(one, input("{}"))).getValue("url").jsonPrimitive.content
      val otherUrl = shareOf(shareTool.execute(other, input("{}"))).getValue("url").jsonPrimitive.content

      assertNotEquals(oneUrl, otherUrl)
      // ...and revoking one student's link leaves the other's alone.
      revokeTool.execute(one, input("{}"))
      val stillLive = shareOf(shareTool.execute(other, input("{}")))
      assertEquals(otherUrl, stillLive.getValue("url").jsonPrimitive.content, "the other student's own link is untouched")
    }
}
