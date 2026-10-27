package ed.unicoach.rest

import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.rest.ParentCoachServer.ScriptedCoach
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
 * Who pays for the parent's coach (RFC 258, brief 0014 D23): every call is
 * attributed to the parent through `supporter_llm_costs`, which the account
 * ledger re-keys onto the parent's own account, and none of it reaches the
 * student's meter — over the served routes, with every call really logged.
 */
class SupporterCoachingCostTest {
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

  private fun supporterIdOf(account: FamilyRoutes.Account): UUID =
    parentCoach.db
      .prepareStatement("SELECT sp.id FROM supporters sp JOIN users u ON u.id = sp.user_id WHERE u.email = ?")
      .use { stmt ->
        stmt.setString(1, account.email)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getObject(1, UUID::class.java)
        }
      }

  /** The parent's calls: the `llm_requests` behind their convo's request rows. */
  private fun parentCallIds(convoId: String): List<Long> =
    parentCoach.db.prepareStatement("SELECT llm_request_id FROM convo_requests WHERE convo_id = ?::uuid ORDER BY id").use { stmt ->
      stmt.setString(1, convoId)
      stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

  /** A parent turn with exactly one tool round: two provider calls. */
  private suspend fun oneToolRoundTurn(linked: FamilyRoutes.Family): String {
    parentCoach.coach.enqueue(ScriptedCoach.toolUse("family_cost_picture"))
    val streamed = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)
    assertEquals(HttpStatusCode.OK, streamed.status, streamed.body)
    assertEquals("message", streamed.frames.last().first, streamed.body)
    return parentCoach.startedConvoId(streamed)
  }

  @Test
  fun `a parent turn with one tool round writes two supporter_llm_costs rows for that parent`() {
    runBlocking {
      val linked = family.family()
      val convo = oneToolRoundTurn(linked)
      val calls = parentCallIds(convo)

      assertEquals(2, calls.size, "the opener and the one continuation")
      assertEquals(
        2,
        parentCoach.count(
          "SELECT COUNT(*) FROM supporter_llm_costs WHERE supporter_id = ? AND llm_request_id = ANY (?)",
          supporterIdOf(linked.parent),
          parentCoach.db.createArrayOf("bigint", calls.toTypedArray()),
        ),
      )
    }
  }

  @Test
  fun `student_llm_cost holds none of the parent's calls and account_llm_cost bills them to the parent's user`() {
    runBlocking {
      val linked = family.family()
      assertEquals(2, parentCallIds(oneToolRoundTurn(linked)).size)

      // This student has no spend of their own, so ANY row is the parent's leaking in.
      assertEquals(
        0,
        parentCoach.count("SELECT COUNT(*) FROM student_llm_cost WHERE student_id = ?::uuid", linked.studentId),
        "the conversation is ABOUT the student, and none of it is the student's spend",
      )
      assertEquals(
        2,
        parentCoach.count(
          """
          SELECT COUNT(*) FROM account_llm_cost a
          JOIN users u ON u.id = a.user_id
          WHERE u.email = ?
          """.trimIndent(),
          linked.parent.email,
        ),
        "both calls are on the parent's own account",
      )
      assertEquals(
        0,
        parentCoach.count(
          "SELECT COUNT(*) FROM account_llm_cost a JOIN users u ON u.id = a.user_id WHERE u.email = ?",
          linked.student.email,
        ),
        "and none on the student's",
      )
    }
  }

  @Test
  fun `an exhausted parent allowance answers 402 before any convo row exists`() {
    runBlocking {
      val linked = family.family()
      LlmCallRowFixture.attributeToSupporter(
        parentCoach.db,
        supporterIdOf(linked.parent),
        LlmCallRowFixture.insert(parentCoach.db, 1_000_000_000_000_000L),
      )

      val refused = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId)

      assertEquals(HttpStatusCode.PaymentRequired, refused.status, refused.body)
      assertEquals("coaching_budget_exhausted", parentCoach.json(refused.body)["code"].asText())
      assertEquals(0, parentCoach.count("SELECT COUNT(*) FROM convos WHERE student_id = ?::uuid", linked.studentId))
      assertEquals(emptyList(), parentCoach.coach.requests, "and no provider call was made")
    }
  }

  @Test
  fun `the student's own allowance is untouched by the parent's spend`() {
    runBlocking {
      val linked = family.family()
      val before = parentCoach.get("/api/v1/students/me/coaching-usage", linked.student.cookie)
      assertEquals(HttpStatusCode.OK, before.first, before.second)

      oneToolRoundTurn(linked)
      oneToolRoundTurn(linked)

      // The served meter floors to whole percent, so a few cents leaking in would
      // still read 0%. The ledger sums are exact: the parent really spent, and
      // not one nanodollar of it reached the student's meter.
      assertTrue(
        summedNanodollars("SELECT SUM(cost_nanodollars) FROM supporter_llm_cost WHERE supporter_id = ?", supporterIdOf(linked.parent)) > 0,
        "the parent's turns cost something, so the student's zero below is not vacuous",
      )
      assertEquals(
        0L,
        summedNanodollars("SELECT SUM(cost_nanodollars) FROM student_llm_cost WHERE student_id = ?::uuid", linked.studentId),
        "none of the parent's spend is billed to the student",
      )
      assertEquals(before, parentCoach.get("/api/v1/students/me/coaching-usage", linked.student.cookie))
    }
  }

  /** A `SUM(cost_nanodollars)` query's value; an empty sum (no rows) reads 0. */
  private fun summedNanodollars(
    sql: String,
    arg: Any,
  ): Long =
    parentCoach.db.prepareStatement(sql).use { stmt ->
      stmt.setObject(1, arg)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getLong(1)
      }
    }
}
