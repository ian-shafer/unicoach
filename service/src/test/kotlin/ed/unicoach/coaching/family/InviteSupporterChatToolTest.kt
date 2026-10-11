package ed.unicoach.coaching.family

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.common.config.TOKEN_QUERY_PARAM
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The chat door of RFC 240. Against the REAL [SupporterInviteService] and a real
 * database, on [ed.unicoach.coaching.report.CostReportChatToolsTest]'s
 * precedent: a fake of the one service call this tool makes would only prove
 * that the fake agrees with itself, and the two facts most worth asserting —
 * that the stored row holds a HASH, and that two asks leave two LIVE invites —
 * are facts about the database.
 */
class InviteSupporterChatToolTest {
  private companion object {
    const val INVITE_URL_BASE = "https://app.unicoach.test/invite"

    /** The one shape a minted link has, composed from the parameter name's own home. */
    const val URL_PREFIX = "$INVITE_URL_BASE?$TOKEN_QUERY_PARAM="
  }

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.truncate(
      "share_events",
      "supporter_invites",
      "supporter_student_links",
      "supporters",
      "students",
      "users",
    )
  }

  private val service =
    SupporterInviteService(
      CoachingTestDb.database,
      SupporterInviteConfig(inviteUrlBase = INVITE_URL_BASE, tokenTtl = Duration.ofDays(14)),
      TokenGenerator(),
      SupporterService(CoachingTestDb.database, LinkEndedNotice(QueueService(CoachingTestDb.database))),
    )

  private val tool = InviteSupporterChatTool(service)

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun inviteOf(result: JsonObject): JsonObject = result.getValue(InviteSupporterChatTool.RESULT_KEY).jsonObject

  private fun createStudent(label: String = "ist"): StudentId = CoachingTestDb.createStudent(label)

  /**
   * The raw token as it rides inside the url, which is the only place it exists.
   *
   * The prefix is ASSERTED, not assumed: `substringAfter` answers a url with no
   * token parameter with the WHOLE url, which is not blank and is not a token —
   * so the leak sweep below would look for the url inside the url, find nothing,
   * and pass. The parameter name is read from its one home, never retyped: the
   * minting side and the serving side cannot spell the key differently.
   */
  private fun tokenIn(invite: JsonObject): String {
    val url = invite.getValue(StudentScopedChatTool.URL_KEY).jsonPrimitive.content
    assertTrue(url.startsWith(URL_PREFIX), "the url must carry the token as [$URL_PREFIX<token>]: [$url]")
    return url.removePrefix(URL_PREFIX)
  }

  /**
   * Closes the student's endpoint through the DAO, as `SynthesisServiceTest`
   * does: `students` is OCC-versioned, so a hand-written UPDATE re-implements
   * the version bump `enforce_versioning` demands — and a change to how a soft
   * delete is recorded would leave this test green against a LIVE endpoint.
   */
  private fun closeStudent(studentId: StudentId) {
    StudentsDao.delete(CoachingTestDb.sqlSession, studentId, currentVersion = 1).getOrThrow()
  }

  private fun inviteCount(): Int {
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM supporter_invites").use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `the definition carries the name and the ethos contract`() {
    assertEquals("invite_supporter", tool.name)
    assertEquals("invite_supporter", tool.definition["name"]!!.jsonPrimitive.content)
    val description = tool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("Only call this when the student asks"), "never sent without the student asking")
    assertTrue(description.contains("Never open a conversation with it"), "the standing rule, in the tool's own words")
    assertTrue(description.contains("works once"), "a single-use link the student may spread to two people fails silently")
    assertTrue(description.contains("their own coach workspace"), "what connecting gives, beyond the report page")
    assertTrue(description.contains("never reads the student's coach conversations"), "the ceiling's private half")
    assertTrue(description.contains("Do not push"), "the offer is never a nudge")
    assertTrue(description.contains("never refused"), "a student who ASKS is always served")
    // The two doors are distinguishable to the model, or it will reach for the
    // wrong one: the read-only page is named here and told apart from this.
    assertTrue(description.contains("share_cost_report"), "the description says which thing this is NOT")
    // The tool takes nothing at all, including no relationship word.
    assertEquals(
      emptySet<String>(),
      tool.definition["input_schema"]!!
        .jsonObject
        .getValue("properties")
        .jsonObject.keys,
      "the tool takes no argument: a relationship word here would be the model inventing a fact about a family",
    )
  }

  @Test
  fun `inviting returns the link, the date it dies, and both halves of the ceiling`() =
    runBlocking {
      val studentId = createStudent()

      val invite = inviteOf(tool.execute(studentId, input("{}")))

      assertTrue(invite.getValue(StudentScopedChatTool.LINK_CREATED_KEY).jsonPrimitive.boolean)
      val url = invite.getValue(StudentScopedChatTool.URL_KEY).jsonPrimitive.content
      assertTrue(url.startsWith(URL_PREFIX), "the coach is handed the real link: [$url]")
      // The coach must be able to say the date out loud, so it parses as an instant.
      val expiresAt = Instant.parse(invite.getValue(InviteSupporterChatTool.EXPIRES_AT_KEY).jsonPrimitive.content)
      assertTrue(expiresAt.isAfter(Instant.now()), "a link handed over already dead is worse than none: [$expiresAt]")
      assertEquals(
        InviteSupporterChatTool.ONE_PERSON_ONE_USE,
        invite.getValue(InviteSupporterChatTool.ONE_PERSON_ONE_USE_KEY).jsonPrimitive.content,
      )
      assertEquals(
        InviteSupporterChatTool.WHAT_CONNECTING_GIVES,
        invite.getValue(InviteSupporterChatTool.WHAT_CONNECTING_GIVES_KEY).jsonPrimitive.content,
      )
      assertEquals(
        InviteSupporterChatTool.WHAT_STAYS_PRIVATE,
        invite.getValue(InviteSupporterChatTool.WHAT_STAYS_PRIVATE_KEY).jsonPrimitive.content,
      )
      assertTrue(invite[StudentScopedChatTool.STATEMENT_KEY] == null, "a created link needs no decline sentence")
    }

  /**
   * The credential's hygiene, at the door that speaks it. The token rides inside
   * `url` and NOWHERE else: not as a field of its own, and not in the row, which
   * holds only its SHA-256 — so a leaked database cannot be replayed as a
   * family's invitation.
   */
  @Test
  fun `the raw token rides only inside the url and the row holds only its hash`() =
    runBlocking {
      val studentId = createStudent()

      val invite = inviteOf(tool.execute(studentId, input("{}")))
      val token = tokenIn(invite)

      // No `isNotBlank` guard: `tokenIn` already refused a url that carries no
      // token, by name, instead of handing a bogus one to the sweep below.
      val fields = invite.keys.filter { it != StudentScopedChatTool.URL_KEY }
      val leaking =
        fields.filter {
          invite
            .getValue(it)
            .jsonPrimitive.content
            .contains(token)
        }
      assertTrue(leaking.isEmpty(), "these result fields repeat the raw token: $leaking")

      val stored = SupporterInvitesDao.findLiveByStudent(CoachingTestDb.sqlSession, studentId).getOrThrow()
      assertEquals(1, stored.size)
      val hash = stored.single().tokenHash.value
      // contentEquals, not assertNotEquals: two ByteArrays are never `equals`,
      // so the reference comparison would pass against the token stored raw.
      assertFalse(token.toByteArray().contentEquals(hash), "the row must not hold the token itself")
      assertTrue(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).contentEquals(hash),
        "the stored value must be the token's SHA-256, or the link the coach spoke cannot be redeemed",
      )
      // The word nobody stated (RFC 240 decision 4): the coach has no honest way
      // to ask, so the catch-all is what it mints.
      assertEquals(SupporterRelationship.SUPPORTER, stored.single().relationship)
    }

  /**
   * Two parents, RFC 232 §2. The tool does NOT behave like `share_cost_report`,
   * which hands back the same link: `supporter_invites` keeps only a hash, so an
   * earlier link is unrecoverable by construction, and a student who asks twice
   * is asking to invite a second person.
   */
  @Test
  fun `two asks mint two independently live invites`() =
    runBlocking {
      val studentId = createStudent()

      val first = inviteOf(tool.execute(studentId, input("{}")))
      val second = inviteOf(tool.execute(studentId, input("{}")))

      assertNotEquals(
        first.getValue(StudentScopedChatTool.URL_KEY).jsonPrimitive.content,
        second.getValue(StudentScopedChatTool.URL_KEY).jsonPrimitive.content,
        "a second ask must be a second person's link, never a repeat of the first",
      )
      val live = SupporterInvitesDao.findLiveByStudent(CoachingTestDb.sqlSession, studentId).getOrThrow()
      assertEquals(2, live.size, "BOTH links are live: minting the second must not kill the first")
    }

  /**
   * A closed student endpoint: nothing this server can mint for. A RESULT with
   * `link_created: false` and a sentence, not an `{error}` — the model cannot
   * tell a failed write from "there is nothing to try", and this is the second.
   */
  @Test
  fun `a closed student endpoint declines with a sentence the coach can say`() =
    runBlocking {
      val studentId = createStudent()
      closeStudent(studentId)

      val invite = inviteOf(tool.execute(studentId, input("{}")))

      assertFalse(
        invite.getValue(StudentScopedChatTool.LINK_CREATED_KEY).jsonPrimitive.boolean,
        "link_created is present and false in this shape too, so the coach reads one key",
      )
      assertEquals(
        InviteSupporterChatTool.UNAVAILABLE,
        invite.getValue(StudentScopedChatTool.STATEMENT_KEY).jsonPrimitive.content,
      )
      assertTrue(invite[StudentScopedChatTool.URL_KEY] == null, "a decline hands over no link")
      assertEquals(0, inviteCount(), "a decline writes no invite row")
    }

  @Test
  fun `a surplus input field is refused rather than ignored`() =
    runBlocking {
      val studentId = createStudent()

      val refusal = errorOf(tool.execute(studentId, input("""{"relationship": "uncle"}""")))

      assertEquals("unknown field(s): [relationship]", refusal, "the refusal must name the surplus key")
      assertEquals(0, inviteCount(), "a refused call writes no invite row")
    }

  @Test
  fun `an unscoped dispatch never mints an invite`() =
    runBlocking {
      // The misroute guard on StudentScopedChatTool: with no turn student there
      // is nobody to invite on behalf of, and the tool says so instead of guessing.
      assertNotNull(errorOf(tool.execute(input("{}"))))
      assertEquals(0, inviteCount(), "a misrouted call must write nothing")
    }

  @Test
  fun `one student never receives another student's invite`() =
    runBlocking {
      val one = createStudent("one")
      val other = createStudent("other")

      tool.execute(one, input("{}"))
      tool.execute(other, input("{}"))

      val oneLive = SupporterInvitesDao.findLiveByStudent(CoachingTestDb.sqlSession, one).getOrThrow()
      val otherLive = SupporterInvitesDao.findLiveByStudent(CoachingTestDb.sqlSession, other).getOrThrow()
      assertEquals(1, oneLive.size)
      assertEquals(1, otherLive.size)
      assertNotEquals(oneLive.single().id, otherLive.single().id, "each student's invite is their own")
    }

  /**
   * The opt-out silences the OFFER, never the ability (RFC 160's standing rule
   * for `share_cost_report`, RFC 240 §4). The synthesis nudge step reads this
   * same `opted_out` event and declines to offer; this door must not, or a
   * student who asked the coach to stop suggesting sharing has quietly lost the
   * way to invite their own parent.
   */
  @Test
  fun `an opted-out student who asks is still minted a link`() =
    runBlocking {
      val studentId = createStudent()
      ShareEventsDao.recordOptOut(CoachingTestDb.sqlSession, studentId).getOrThrow()

      val invite = inviteOf(tool.execute(studentId, input("{}")))

      assertTrue(
        invite.getValue(StudentScopedChatTool.LINK_CREATED_KEY).jsonPrimitive.boolean,
        "a student who ASKS is never refused: the opt-out silences the offer, not the door",
      )
      assertNotNull(invite[StudentScopedChatTool.URL_KEY], "the ask hands over a real link")
      assertEquals(1, inviteCount(), "and the ask really minted a row")
    }
}
