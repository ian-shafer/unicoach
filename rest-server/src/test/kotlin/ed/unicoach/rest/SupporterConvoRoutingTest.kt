package ed.unicoach.rest

import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.rest.models.SupporterConversation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The supporter's coach doors (RFC 258 §7) over the served stack: every row of
 * the refusal table, each answered before any SSE byte; the SSE frame order;
 * the list's shape; and what an unlink does — the conversations stay readable
 * and the next turn is refused.
 */
class SupporterConvoRoutingTest {
  companion object {
    private lateinit var parentCoach: ParentCoachServer

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      parentCoach = ParentCoachServer.start()
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::parentCoach.isInitialized) parentCoach.close()
    }

    private const val LIST = ParentCoachServer.CONVERSATIONS_PATH
    private const val START = ParentCoachServer.START_PATH
  }

  @BeforeEach
  fun reset() {
    parentCoach.coach.reset()
  }

  private val family get() = parentCoach.family

  private fun messagesPath(convoId: String) = "$LIST/$convoId/messages"

  private fun streamPath(convoId: String) = "$LIST/$convoId/messages/stream"

  private fun errorCode(body: String): String = parentCoach.json(body)["code"].asText()

  /** A started, completed parent conversation; its id. */
  private suspend fun startedConvo(linked: FamilyRoutes.Family): String {
    val streamed = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)
    assertEquals(HttpStatusCode.OK, streamed.status, streamed.body)
    return parentCoach.startedConvoId(streamed)
  }

  private fun assertRefusedBeforeStream(
    streamed: ParentCoachServer.Streamed,
    status: HttpStatusCode,
    code: String,
  ) {
    assertEquals(status, streamed.status, streamed.body)
    assertTrue(
      streamed.contentType?.startsWith("application/json") == true,
      "refused as JSON, before any SSE byte: ${streamed.contentType}",
    )
    assertEquals(code, errorCode(streamed.body))
  }

  @Test
  fun `401 on every route with no session`() {
    runBlocking {
      val any = UUID.randomUUID().toString()
      assertEquals(HttpStatusCode.Unauthorized, parentCoach.get(LIST, null).first)
      assertEquals(HttpStatusCode.Unauthorized, parentCoach.get(messagesPath(any), null).first)
      assertEquals(HttpStatusCode.Unauthorized, parentCoach.postJson(START, null, """{"studentId":"$any","message":"hi"}""").status)
      assertEquals(HttpStatusCode.Unauthorized, parentCoach.postJson(streamPath(any), null, """{"message":"hi"}""").status)
    }
  }

  @Test
  fun `403 email_not_verified from the global gate`() {
    runBlocking {
      val client = HttpClient(CIO)
      val cookie = client.use { sessionCookie(registerUser(it, parentCoach.url(""), "unverified-${UUID.randomUUID()}@company.com")) }

      val (status, body) = parentCoach.get(LIST, cookie)

      assertEquals(HttpStatusCode.Forbidden, status, body)
      assertEquals("email_not_verified", errorCode(body))
    }
  }

  @Test
  fun `404 supporter_not_found for a caller with no supporters row`() {
    runBlocking {
      val student = family.registerStudent("Only A Student")
      val any = UUID.randomUUID().toString()

      assertEquals("supporter_not_found", errorCode(parentCoach.get(LIST, student.cookie).second))
      assertEquals("supporter_not_found", errorCode(parentCoach.get(messagesPath(any), student.cookie).second))
      assertRefusedBeforeStream(
        parentCoach.startParentConvo(student.cookie, any),
        HttpStatusCode.NotFound,
        "supporter_not_found",
      )
      assertRefusedBeforeStream(
        parentCoach.postParentMessage(student.cookie, any, "hi"),
        HttpStatusCode.NotFound,
        "supporter_not_found",
      )
    }
  }

  @Test
  fun `400 for a malformed id or body`() {
    runBlocking {
      val linked = family.family()

      assertRefusedBeforeStream(
        parentCoach.startParentConvo(linked.parent.cookie, "not-a-uuid"),
        HttpStatusCode.BadRequest,
        "validation_failed",
      )
      assertRefusedBeforeStream(
        parentCoach.startParentConvo(linked.parent.cookie, linked.studentId, "   "),
        HttpStatusCode.BadRequest,
        "validation_failed",
      )
      assertRefusedBeforeStream(
        parentCoach.postParentMessage(linked.parent.cookie, "not-a-uuid", "hi"),
        HttpStatusCode.BadRequest,
        "validation_failed",
      )
      assertEquals(HttpStatusCode.BadRequest, parentCoach.get(messagesPath("not-a-uuid"), linked.parent.cookie).first)
      assertEquals(HttpStatusCode.BadRequest, parentCoach.postJson(START, linked.parent.cookie, "{}").status)
    }
  }

  @Test
  fun `404 student_not_found with one body for never linked, severed and closed`() {
    runBlocking {
      val never = family.family()
      val stranger = family.family()
      val neverLinked = parentCoach.startParentConvo(stranger.parent.cookie, never.studentId)

      val severed = family.family()
      family.sever(severed)
      val afterSever = parentCoach.startParentConvo(severed.parent.cookie, severed.studentId)

      val closed = family.family()
      family.closeStudentRow(closed.student)
      val afterClose = parentCoach.startParentConvo(closed.parent.cookie, closed.studentId)

      assertRefusedBeforeStream(neverLinked, HttpStatusCode.NotFound, "student_not_found")
      assertRefusedBeforeStream(afterSever, HttpStatusCode.NotFound, "student_not_found")
      assertRefusedBeforeStream(afterClose, HttpStatusCode.NotFound, "student_not_found")
      assertEquals(neverLinked.body, afterSever.body, "one body for all")
      assertEquals(neverLinked.body, afterClose.body, "one body for all")
      assertEquals(
        0,
        parentCoach.count(
          "SELECT COUNT(*) FROM convos WHERE student_id IN (?::uuid, ?::uuid, ?::uuid) AND party = 'supporter'",
          never.studentId,
          severed.studentId,
          closed.studentId,
        ),
      )
    }
  }

  @Test
  fun `404 not_found for another supporter's conversation`() {
    runBlocking {
      val linked = family.family()
      val convo = startedConvo(linked)
      val other = family.family()

      assertRefusedBeforeStream(parentCoach.postParentMessage(other.parent.cookie, convo, "hi"), HttpStatusCode.NotFound, "not_found")
      val (status, body) = parentCoach.get(messagesPath(convo), other.parent.cookie)
      assertEquals(HttpStatusCode.NotFound, status, body)
      assertEquals("not_found", errorCode(body))
    }
  }

  @Test
  fun `402 coaching_budget_exhausted when the parent's own allowance is spent`() {
    runBlocking {
      val linked = family.family()
      exhaustParent(linked.parent)

      val refused = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)

      assertRefusedBeforeStream(refused, HttpStatusCode.PaymentRequired, "coaching_budget_exhausted")
      assertEquals(0, parentCoach.count("SELECT COUNT(*) FROM convos WHERE student_id = ?::uuid AND party = 'supporter'", linked.studentId))
    }
  }

  @Test
  fun `the start streams conversation, delta, message in order, and a turn streams user_message, delta, message`() {
    runBlocking {
      val linked = family.family()
      val started = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)

      assertEquals(HttpStatusCode.OK, started.status, started.body)
      assertTrue(started.contentType!!.startsWith("text/event-stream"), started.contentType)
      assertEquals(listOf("conversation", "delta", "message"), started.frames.map { it.first })
      val convo = parentCoach.startedConvoId(started)

      val turn = parentCoach.postParentMessage(linked.parent.cookie, convo, "And then?")
      assertEquals(listOf("user_message", "delta", "message"), turn.frames.map { it.first })
      assertEquals(
        ParentCoachServer.ScriptedCoach.DEFAULT_REPLY,
        parentCoach.json(turn.frames.last().second)["message"]["content"].asText(),
      )
    }
  }

  @Test
  fun `the list carries exactly the published fields, for the caller's conversations only`() {
    runBlocking {
      val linked = family.family()
      val convo = startedConvo(linked)
      startedConvo(family.family())

      val (status, body) = parentCoach.get(LIST, linked.parent.cookie)

      assertEquals(HttpStatusCode.OK, status, body)
      val conversations = parentCoach.json(body)["conversations"]
      assertEquals(1, conversations.size(), body)
      val item = conversations[0]
      assertEquals(
        SupporterConversation::class.java.declaredFields
          .filterNot { it.isSynthetic }
          .map { it.name }
          .toSet(),
        item.fieldNames().asSequence().toSet(),
      )
      assertEquals(convo, item["id"].asText())
      assertEquals(linked.studentId, item["studentId"].asText())
    }
  }

  @Test
  fun `after an unlink the conversation is listed and readable, and the next turn is refused`() {
    runBlocking {
      val linked = family.family()
      val convo = startedConvo(linked)
      family.sever(linked)

      val (listStatus, listBody) = parentCoach.get(LIST, linked.parent.cookie)
      val (readStatus, readBody) = parentCoach.get(messagesPath(convo), linked.parent.cookie)
      val post = parentCoach.postParentMessage(linked.parent.cookie, convo, "Are you still there?")

      assertEquals(HttpStatusCode.OK, listStatus, listBody)
      assertEquals(convo, parentCoach.json(listBody)["conversations"][0]["id"].asText())
      assertEquals(HttpStatusCode.OK, readStatus, readBody)
      assertEquals(2, parentCoach.json(readBody)["messages"].size(), "the parent's message and the coach's answer")
      assertRefusedBeforeStream(post, HttpStatusCode.NotFound, "student_not_found")
    }
  }

  /** Spend far past any allowance, attributed to [parent]'s supporter row. */
  private fun exhaustParent(parent: FamilyRoutes.Account) {
    val supporterId =
      parentCoach.db
        .prepareStatement("SELECT sp.id FROM supporters sp JOIN users u ON u.id = sp.user_id WHERE u.email = ?")
        .use { stmt ->
          stmt.setString(1, parent.email)
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1, UUID::class.java)
          }
        }
    LlmCallRowFixture.attributeToSupporter(parentCoach.db, supporterId, LlmCallRowFixture.insert(parentCoach.db, 1_000_000_000_000_000L))
  }
}
