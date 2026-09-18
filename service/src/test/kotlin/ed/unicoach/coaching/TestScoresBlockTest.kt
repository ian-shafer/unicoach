package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.coaching.testing.TestScoreService
import ed.unicoach.coaching.testing.TestScoreWrite
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scores block in the coach's system text (RFC 207).
 *
 * Composed when the derived answer status is ANSWERED or DECLINED, and absent
 * for a student who has never been asked -- so a brand-new student's prompt
 * keeps the rest of its text. The cases are deliberately not one student's
 * shape (brief 0010 D22): a junior with a month-precision score and nothing
 * else on file, a senior with a refusal, and a student already past their
 * expected graduation date.
 */
class TestScoresBlockTest {
  private val today = Instant.parse("2026-09-14T15:00:00Z")

  private val config =
    ed.unicoach.coaching.CoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private val generousBudget = generousBudgetService(CoachingTestDb.database)

  private fun service(provider: ChatProvider): CoachingService =
    CoachingService(
      CoachingTestDb.database,
      LlmCallLog(provider, CoachingTestDb.database),
      config,
      generousBudget,
      clock = InstantSource.fixed(today),
    )

  private val scoresService = TestScoreService(CoachingTestDb.database, InstantSource.fixed(today))

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.connection.autoCommit = true
    CoachingTestDb.truncate(
      "convos",
      "convo_requests",
      "llm_requests",
      "llm_responses",
      "llm_responses_raw",
      "students",
      "users",
      "admission_test_scores",
      "admission_test_score_declines",
    )
  }

  private fun createStudent(
    label: String,
    graduation: PartialDate = StudentRowFixture.DEFAULT_GRADUATION,
  ): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', '$label-$userId@test.com', 'Block User', 'ahash')",
      )
      StudentRowFixture.insert(CoachingTestDb.connection, studentId, userId, graduation)
    }
    return StudentId(studentId)
  }

  /** Emits one text delta then the supplied terminal, and hands the request to [onRequest]. */
  private class ScriptedProvider(
    private val onRequest: (ChatRequest) -> Unit,
  ) : ChatProvider {
    override val id: String = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        onRequest(request)
        emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
        emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("hi")))
        emit(
          ChatEvent.Completed(
            response =
              ChatResponse(
                content = Json.parseToJsonElement("""[{"type":"text","text":"hi"}]"""),
                modelResolved = "claude-sonnet-4-6",
                stopReason = "end_turn",
                usage = TokenUsage(inputTokens = 3, outputTokens = 5, cacheReadTokens = null, cacheWriteTokens = null),
                providerRequestId = "req_block",
              ),
            rawPayload = Json.parseToJsonElement("""{}"""),
          ),
        )
      }
  }

  private suspend fun systemTextFor(student: StudentId): String {
    var captured: ChatRequest? = null
    val provider = ScriptedProvider { captured = it }
    val started = service(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
    started.reply.toList()
    return captured!!.system!!
  }

  @Test
  fun `an answered block renders a month-precision score for a student with nothing else on file`() =
    runBlocking {
      val student = createStudent("block-junior")
      scoresService
        .write(
          student,
          TestScoreWrite.Record(AdmissionTestScoreComponent.SAT_TOTAL, 1340, PartialDate.YearAndMonth(Year.of(2026), Month.MARCH)),
        ).getOrThrow()

      val system = systemTextFor(student)
      assertTrue(system.contains("Test scores the student has told us about"), system)
      assertTrue(system.contains("SAT total 1340, sat in Mar 2026 (the day is not recorded)"), system)
    }

  @Test
  fun `a declined block renders for a senior and never names a score`() =
    runBlocking {
      val student = createStudent("block-senior", PartialDate.FullDate(LocalDate.of(2027, 6, 15)))
      scoresService.write(student, TestScoreWrite.Decline).getOrThrow()

      val system = systemTextFor(student)
      assertTrue(system.contains("Test scores: declined (asked and declined"), system)
      assertFalse(system.contains("SAT total"), system)
    }

  @Test
  fun `a student past graduation composes a full-date score line`() =
    runBlocking {
      val student = createStudent("block-past", PartialDate.FullDate(LocalDate.of(2025, 6, 15)))
      scoresService
        .write(
          student,
          TestScoreWrite.Record(AdmissionTestScoreComponent.ACT_COMPOSITE, 29, PartialDate.FullDate(LocalDate.of(2025, 10, 25))),
        ).getOrThrow()

      val system = systemTextFor(student)
      assertTrue(system.contains("ACT composite 29, sat on Oct 25, 2025"), system)
    }

  @Test
  fun `a student who has never been asked has no scores block`() =
    runBlocking {
      val student = createStudent("block-never")
      val system = systemTextFor(student)
      assertFalse(system.contains("Test scores"), system)
      assertNotNull(system, "the system text still composes, block or not")
    }
}
