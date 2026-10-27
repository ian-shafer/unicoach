package ed.unicoach.rest

import ed.unicoach.rest.ParentCoachServer.ScriptedCoach
import ed.unicoach.web.common.routing.registeredRoutes
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four cut points brief 0014 makes mandatory for the parent's coach (RFC
 * 258), each a named test, end to end over the SERVED routes with the real
 * database and the scripted provider seam:
 *
 * 1. the student's conversation content never reaches the parent's coach;
 * 2. coaching memory derived from the student's chats never reaches it;
 * 3. a parent's turn never writes the student's state;
 * 4. nothing is extracted from a parent's turn onto the student's record.
 *
 * "Reaches" is asserted on what the provider was actually SENT: every
 * `llm_requests` body the parent's calls logged — the system text, the
 * messages and the tool results.
 */
class SupporterCoachingCutPointTest {
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
  }

  @BeforeEach
  fun reset() {
    parentCoach.coach.reset()
  }

  private val family get() = parentCoach.family

  /** A string no fixture, prompt or payload could contain by accident. */
  private fun sentinel(): String = "SENTINEL-${UUID.randomUUID()}"

  /**
   * The parent starts a conversation and the scripted model calls
   * `family_cost_picture`, then `fafsa_deadlines`, then answers: the cost-and-
   * deadline turn the slice is for.
   */
  private suspend fun parentCostTurn(linked: FamilyRoutes.Family): ParentCoachServer.Streamed {
    parentCoach.coach.enqueue(ScriptedCoach.toolUse("family_cost_picture"), ScriptedCoach.toolUse("fafsa_deadlines"))
    val streamed = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)
    assertEquals(HttpStatusCode.OK, streamed.status, streamed.body)
    assertEquals("message", streamed.frames.last().first, "the parent's turn completed: ${streamed.body}")
    return streamed
  }

  private fun assertNoBodyCarries(
    sentinel: String,
    watermark: Long,
    expectedCalls: Int,
  ) {
    val bodies = parentCoach.llmRequestBodiesAbove(watermark)
    assertEquals(expectedCalls, bodies.size, "the parent's calls, every one logged")
    assertTrue(bodies.any { it.contains("family_cost_picture") && it.contains("tool_result") }, "a tool result rode a call")
    bodies.forEachIndexed { i, body ->
      assertTrue(!body.contains(sentinel), "parent call #$i carried the student's [$sentinel]: $body")
    }
  }

  @Test
  fun studentConversationNeverReachesParentCoach() {
    runBlocking {
      val linked = family.family()
      family.addToList(linked.student, family.seedCollege("Cut Point One College"), "A school I like")
      val sentinel = sentinel()
      // The student's own conversation: their words and their coach's, both carrying it.
      parentCoach.coach.enqueue(ScriptedCoach.text("I hear you about $sentinel."))
      val studentsConvo = parentCoach.startStudentConvo(linked.student.cookie, "Please keep this private: $sentinel")
      val watermark = parentCoach.llmRequestWatermark()

      parentCostTurn(linked)
      // The student's conversation is not the parent's to continue: a parent who
      // learned its id is refused before any provider call.
      val intoStudentsConvo = parentCoach.postParentMessage(linked.parent.cookie, studentsConvo, "What did she say?")

      assertEquals(HttpStatusCode.NotFound, intoStudentsConvo.status, intoStudentsConvo.body)
      assertNoBodyCarries(sentinel, watermark, expectedCalls = 3)
    }
  }

  @Test
  fun coachingMemoryNeverReachesParentCoach() {
    runBlocking {
      val linked = family.family()
      val sentinel = sentinel()
      // A list entry whose reasons carry it, in the student's own words.
      family.addToList(linked.student, family.seedCollege("Cut Point Two College"), "Because of $sentinel")
      // Coaching memory derived from the student's chat: an observation and a claim carrying it.
      val studentsConvo = parentCoach.startStudentConvo(linked.student.cookie, "Hello coach")
      seedMemory(linked.studentId, studentsConvo, sentinel)
      val watermark = parentCoach.llmRequestWatermark()

      parentCostTurn(linked)

      assertNoBodyCarries(sentinel, watermark, expectedCalls = 3)
    }
  }

  @Test
  fun parentTurnNeverWritesStudentState() {
    runBlocking {
      val linked = family.family()
      family.addToList(linked.student, family.seedCollege("Cut Point Three College"), "My reasons")
      family.answerMoneyProfile(linked.student)
      parentCoach.startStudentConvo(linked.student.cookie, "Hello coach")
      seedOpenCommitment(linked.studentId)
      val rowsBefore = studentRows(linked.studentId)
      val readsBefore = studentReads(linked.student.cookie)

      // Every tool the parent's coach advertises, called in one round.
      val advertised = parentCoach.coach.requests.size
      parentCoach.coach.enqueue(ScriptedCoach.toolUse("family_cost_picture", "fafsa_deadlines", "fafsa_process", "find_college"))
      val first = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)
      assertEquals("message", first.frames.last().first, first.body)
      val toolsOffered =
        parentCoach.coach.requests[advertised]
          .tools
          .map { it["name"].toString().trim('"') }
          .toSet()
      assertEquals(setOf("family_cost_picture", "fafsa_deadlines", "fafsa_process", "find_college"), toolsOffered)
      // And a second turn in the same conversation.
      val second = parentCoach.postParentMessage(linked.parent.cookie, parentCoach.startedConvoId(first), "And then what?")
      assertEquals("message", second.frames.last().first, second.body)

      assertEquals(rowsBefore, studentRows(linked.studentId), "the student's rows, byte for byte")
      assertEquals(readsBefore, studentReads(linked.student.cookie), "the student's served reads")
    }
  }

  @Test
  fun parentTurnNeverExtracts() {
    runBlocking {
      val linked = family.family()
      val streamed = parentCostTurn(linked)
      val parentConvo = parentCoach.startedConvoId(streamed)

      assertEquals(
        0,
        parentCoach.count(
          "SELECT COUNT(*) FROM jobs WHERE job_type = 'EXTRACT_CONVERSATION' AND payload->>'convoId' = ?",
          parentConvo,
        ),
        "a parent's turn enqueues no extraction",
      )
      assertEquals(0, parentCoach.count("SELECT COUNT(*) FROM extraction_runs WHERE convo_id = ?::uuid", parentConvo))
      assertEquals(0, parentCoach.count("SELECT COUNT(*) FROM observations WHERE convo_id = ?::uuid", parentConvo))
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures and snapshots
  // ---------------------------------------------------------------------------

  /** An observation and a claim on the student, both carrying [sentinel], as extraction would write them. */
  private fun seedMemory(
    studentId: String,
    convoId: String,
    sentinel: String,
  ) {
    parentCoach.db
      .prepareStatement(
        """
        INSERT INTO observations (student_id, convo_id, source_request_id, uttered_at, quote)
        SELECT ?::uuid, ?::uuid, MAX(id), NOW(), ? FROM convo_requests WHERE convo_id = ?::uuid
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, studentId)
        stmt.setString(2, convoId)
        stmt.setString(3, "I said $sentinel")
        stmt.setString(4, convoId)
        stmt.executeUpdate()
      }
    parentCoach.db
      .prepareStatement(
        "INSERT INTO claims (student_id, origin, kind, subject, topic, statement) VALUES (?::uuid, 'student_stated', 'goal', 'student', 'academics', ?)",
      ).use { stmt ->
        stmt.setString(1, studentId)
        stmt.setString(2, "The student wants $sentinel")
        stmt.executeUpdate()
      }
  }

  /** An open, explicit commitment: the row a turn that "settled" the student's record would mark. */
  private fun seedOpenCommitment(studentId: String) {
    parentCoach.db
      .prepareStatement(
        "INSERT INTO commitments (student_id, lens, disclosure, statement) VALUES (?::uuid, 'gap', 'explicit', 'Ask about deadlines')",
      ).use { stmt ->
        stmt.setString(1, studentId)
        stmt.executeUpdate()
      }
  }

  /** Every student-keyed table a write could reach, each row as its full JSON, in a stable order. */
  private fun studentRows(studentId: String): Map<String, List<String>> {
    val byStudent =
      listOf(
        "college_list_entries",
        "money_profiles",
        "admission_test_scores",
        "admission_test_score_declines",
        "commitments",
        "claims",
        "observations",
        "sharing_permissions",
        "cost_report_shares",
        "supporter_invites",
        "intended_subjects",
        "intended_subject_status",
      )
    val rows =
      byStudent.associateWith { table ->
        rowsOf("SELECT row_to_json(t)::text FROM $table t WHERE student_id = ?::uuid", studentId)
      }
    return rows + ("students" to rowsOf("SELECT row_to_json(t)::text FROM students t WHERE id = ?::uuid", studentId))
  }

  private fun rowsOf(
    sql: String,
    studentId: String,
  ): List<String> =
    parentCoach.db.prepareStatement(sql).use { stmt ->
      stmt.setString(1, studentId)
      stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) }.sorted() }
    }

  /** Every parameterless read under `/api/v1/students/me`, from the served tree, status and body. */
  private suspend fun studentReads(cookie: String): List<String> {
    val ownStudent = listOf("api", "v1", "students", "me")
    val paths =
      registeredRoutes(family.server.application.plugin(RoutingRoot) as RoutingNode)
        .filter {
          it.method == HttpMethod.Get && it.parameterNames.isEmpty() && it.path
            .trim('/')
            .split('/')
            .take(4) == ownStudent
        }.map { it.path }
    assertTrue(paths.isNotEmpty(), "the walk must find the student's reads")
    return paths.map { path ->
      val (status, body) = parentCoach.get(path, cookie)
      "$path ${status.value} $body"
    }
  }
}
