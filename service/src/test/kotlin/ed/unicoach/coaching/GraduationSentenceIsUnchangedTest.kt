package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.StudentRowFixture
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
import java.time.Month
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behaviour pin RFC 209 promised: **the graduation sentence does not move.**
 *
 * That RFC replaces the three decomposed graduation columns with a date and a
 * precision, and rewrites the DAO that reads them. Its whole claim is that a
 * student hears exactly what she heard before, so this file asserts the
 * sentence BYTE FOR BYTE, at each of the three precisions.
 *
 * **RFC 227 moved it, once, on purpose** (brief 0010 D21: one date seam, US
 * shape). `on 15 June 2028` is now `on Jun 15, 2028`. The pin is RE-PINNED to
 * the new bytes rather than loosened -- a pin that stops pinning is worse than
 * no pin at all -- and the words around the date are untouched, which is the
 * property that says the change was a SHAPE change and not a meaning change.
 *
 * It is a NEW file on purpose. The standing rule this brief learned the hard
 * way is that a guard inside the artifact under rewrite is not a guard: the
 * assertions that prove the rewrite was faithful cannot live in the test file
 * the rewrite edits ([CoachingServiceTest] moves its student inserts onto
 * [StudentRowFixture] in the same run).
 *
 * The cases are deliberately not one student's shape (brief 0010 D22): a junior
 * with a year only, a full date, a month, a SENIOR, and a student whose
 * graduation is already in the past.
 */
class GraduationSentenceIsUnchangedTest {
  private val today = Instant.parse("2026-09-11T15:00:00Z")

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.connection.autoCommit = true
    CoachingTestDb.truncate("convos", "convo_requests", "llm_requests", "llm_responses", "llm_responses_raw", "students", "users")
    CodebookReferenceFixture.seed(CoachingTestDb.sqlSession)
  }

  @Test
  fun `a year-only graduation says the year and says the month is not there`() {
    assertEquals(
      "The student expects to finish high school in 2028 (only the year is recorded — no month, no day); " +
        "their year of school today is grade 11 (junior year).",
      graduationLineFor(PartialDate.YearOnly(Year.of(2028))),
      "RFC 186's year-only sentence, unchanged by RFC 209's storage change",
    )
  }

  @Test
  fun `a month-precision graduation names the month and says the day is not there`() {
    assertEquals(
      "in Jun 2028 (the day is not recorded)",
      graduationPhraseFor(PartialDate.YearAndMonth(Year.of(2028), Month.JUNE)),
    )
  }

  @Test
  fun `a full-date graduation is spoken month, day, year`() {
    assertEquals(
      "on Jun 15, 2028",
      graduationPhraseFor(PartialDate.FullDate(java.time.LocalDate.of(2028, 6, 15))),
    )
  }

  @Test
  fun `a senior and a student already past graduation are spoken the same way`() {
    // Not the junior every other case is: a senior graduating next spring, and
    // a student whose date has passed. Both must still be stated at their
    // stored precision, with no invented month.
    assertEquals("in 2027 (only the year is recorded — no month, no day)", graduationPhraseFor(PartialDate.YearOnly(Year.of(2027))))
    assertEquals("on Jun 5, 2020", graduationPhraseFor(PartialDate.FullDate(java.time.LocalDate.of(2020, 6, 5))))
  }

  /** The whole graduation LINE, body and clause, for a student with this date on file. */
  private fun graduationLineFor(graduation: PartialDate): String {
    val below = dateBlockFor(graduation).trim().lines()
    assertEquals(2, below.size, "the date block is two lines (RFC 186): $below")
    return below[1]
  }

  /**
   * Only the phrase RFC 209 could have broken: what sits between "finish high
   * school " and the ";" that introduces the year-of-school clause.
   */
  private fun graduationPhraseFor(graduation: PartialDate): String {
    val line = graduationLineFor(graduation)
    val prefix = "The student expects to finish high school "
    assertTrue(line.startsWith(prefix), "the graduation line must open with RFC 186's words: [$line]")
    return line.removePrefix(prefix).substringBefore(";")
  }

  private fun dateBlockFor(graduation: PartialDate): String =
    runBlocking {
      val student = createStudent(graduation)
      var captured: ChatRequest? = null
      val provider = CapturingProvider { captured = it }
      val service =
        CoachingService(
          CoachingTestDb.database,
          LlmCallLog(provider, CoachingTestDb.database),
          config,
          generousBudgetService(CoachingTestDb.database),
          clock = InstantSource.fixed(today),
        )
      val started = service.startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      started.reply.toList()
      val system = assertNotNull(captured?.system, "the provider must have been given a system text")
      val body = coachPromptBody()
      assertTrue(system.startsWith(body), "the pinned prompt body must still open the system text, in full and verbatim")
      system.removePrefix(body)
    }

  private val config =
    CoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private fun coachPromptBody(): String =
    CoachingTestDb.connection
      .prepareStatement(
        "SELECT body FROM system_prompts WHERE name = ? AND version = ?",
      ).use { stmt ->
        stmt.setString(1, config.systemPromptName)
        stmt.setString(2, config.systemPromptVersion)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "the pinned coach prompt row must exist in the migration-seeded catalog")
          rs.getString("body")
        }
      }

  private fun createStudent(graduation: PartialDate): StudentId {
    val userId = java.util.UUID.randomUUID()
    val studentId = java.util.UUID.randomUUID()
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES " +
          "('$userId', 'grad-$userId@test.com', 'Grad User', 'ahash')",
      )
    }
    StudentRowFixture.insert(CoachingTestDb.connection, studentId, userId, graduation)
    return StudentId(studentId)
  }

  /** Records the request and answers with one delta, so a turn completes. */
  private class CapturingProvider(
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
                providerRequestId = "req_209",
              ),
            rawPayload = Json.parseToJsonElement("""{"id":"req_209"}"""),
          ),
        )
      }
  }
}
