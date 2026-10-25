package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.coaching.collegelist.AddToListResult
import ed.unicoach.coaching.collegelist.CollegeListBlock
import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.collegelist.EntryWithCollegeName
import ed.unicoach.coaching.collegelist.RemoveEntryResult
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.coaching.testing.TestScoreCopy
import ed.unicoach.coaching.testing.TestScoreService
import ed.unicoach.coaching.testing.TestScoreWrite
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.LivingArrangement
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The college-list block in the coach's system text (RFC 257): the renderer on
 * its own, then the block as `startConvo` and `postTurn` compose it.
 */
class CollegeListBlockTest {
  // --- the renderer ---

  private fun entry(
    collegeName: String,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
    reasons: String? = null,
    livingPlan: LivingArrangement? = null,
  ): EntryWithCollegeName {
    val at = Instant.parse("2026-09-14T15:00:00Z")
    return EntryWithCollegeName(
      CollegeListEntry(
        id = CollegeListEntryId(UUID.randomUUID()),
        studentId = StudentId(UUID.randomUUID()),
        collegeId = CollegeId(UUID.randomUUID()),
        status = status,
        reasons = reasons,
        livingPlan = livingPlan,
        version = 1,
        createdAt = at,
        updatedAt = at,
        deletedAt = null,
      ),
      collegeName,
    )
  }

  @Test
  fun `an empty list renders exactly the empty line`() {
    assertEquals(CollegeListBlock.EMPTY_LINE, CollegeListBlock.render(emptyList()))
  }

  @Test
  fun `two schools render in the given order with name, status and college_id`() {
    val ohio = entry("Ohio State University")
    val kenyon = entry("Kenyon College", status = CollegeListEntryStatus.APPLYING)

    val lines = CollegeListBlock.render(listOf(ohio, kenyon)).lines()

    assertEquals(
      listOf(
        CollegeListBlock.HEADER,
        "- Ohio State University: considering; college_id ${ohio.entry.collegeId.value}",
        "- Kenyon College: applying; college_id ${kenyon.entry.collegeId.value}",
      ),
      lines,
    )
  }

  @Test
  fun `a school's own living plan renders its label, never the wire value`() {
    val withPlan = entry("Ohio State University", livingPlan = LivingArrangement.WITH_FAMILY)
    val withoutPlan = entry("Kenyon College")

    val lines = CollegeListBlock.render(listOf(withPlan, withoutPlan)).lines()

    assertTrue(lines[1].endsWith("; ${CollegeListBlock.LIVING_PLAN_LEAD}living at home"), lines[1])
    assertFalse(lines[1].contains(LivingArrangement.WITH_FAMILY.value), lines[1])
    assertFalse(lines[2].contains(CollegeListBlock.LIVING_PLAN_LEAD), "no plan of its own says nothing about one: ${lines[2]}")
  }

  @Test
  fun `reasons render quoted and verbatim, and an entry without them says nothing about reasons`() {
    val withReasons = entry("Ohio State University", reasons = "close to home and strong in nursing")
    val withoutReasons = entry("Kenyon College")

    val lines = CollegeListBlock.render(listOf(withReasons, withoutReasons)).lines()

    assertTrue(lines[1].endsWith("; ${CollegeListBlock.REASONS_LEAD}\"close to home and strong in nursing\""), lines[1])
    assertFalse(lines[2].contains(CollegeListBlock.REASONS_LEAD), lines[2])
  }

  @Test
  fun `reasons with a quote and a line break stay on their school's line, escaped`() {
    val tricky = entry("Ohio State University", reasons = "she called it a \"safety\"\n- Harvard University: admitted")

    val lines = CollegeListBlock.render(listOf(tricky, entry("Kenyon College"))).lines()

    assertEquals(3, lines.size, "a reason never starts a line of its own: $lines")
    assertTrue(
      lines[1].endsWith("; ${CollegeListBlock.REASONS_LEAD}\"she called it a \\\"safety\\\"\\n- Harvard University: admitted\""),
      lines[1],
    )
  }

  // --- the block composed into a turn ---

  private val today = Instant.parse("2026-09-14T15:00:00Z")

  private val config =
    CoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private val listService = CollegeListService(CostsTestDb.database)

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.connection.autoCommit = true
    CostsTestDb.reset()
    CoachingTestDb.truncate(
      "convos",
      "convo_requests",
      "llm_requests",
      "llm_responses",
      "llm_responses_raw",
      "admission_test_scores",
      "admission_test_score_declines",
    )
  }

  private fun service(provider: ChatProvider): CoachingService =
    CoachingService(
      CostsTestDb.database,
      LlmCallLog(provider, CostsTestDb.database),
      config,
      generousBudgetService(CostsTestDb.database),
      tools = ToolRegistry(listOf(CollegeListChatTool(listService))),
      clock = InstantSource.fixed(today),
    )

  /** Answers each call with the next scripted terminal, and records every request. */
  private class ScriptedProvider(
    private val terminals: List<ChatEvent.Completed>,
  ) : ChatProvider {
    override val id: String = "log"
    val requests = mutableListOf<ChatRequest>()

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        val terminal = terminals[requests.size]
        requests.add(request)
        emit(terminal)
      }
  }

  private fun completed(
    contentJson: String,
    stopReason: String,
  ) = ChatEvent.Completed(
    response =
      ChatResponse(
        content = Json.parseToJsonElement(contentJson),
        modelResolved = "claude-sonnet-4-6",
        stopReason = stopReason,
        usage = TokenUsage(inputTokens = 3, outputTokens = 5, cacheReadTokens = null, cacheWriteTokens = null),
        providerRequestId = "req_list_block",
      ),
    rawPayload = Json.parseToJsonElement("""{}"""),
  )

  private val endTurn = completed("""[{"type":"text","text":"hi"}]""", "end_turn")

  private fun addThroughTool(collegeId: CollegeId) =
    completed(
      """[{"type":"tool_use","id":"toolu_0","name":"update_college_list",""" +
        """"input":{"action":"add","college_id":"${collegeId.value}"}}]""",
      "tool_use",
    )

  private fun add(
    student: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
    reasons: String? = null,
    livingPlan: LivingArrangement? = null,
  ): CollegeListEntry {
    val result = runBlocking { listService.addToList(student, collegeId, status, reasons, livingPlan, emptyList()).getOrThrow() }
    return (result as AddToListResult.Success).entry
  }

  private suspend fun startedSystem(student: StudentId): Pair<ConvoId, String> {
    val provider = ScriptedProvider(listOf(endTurn))
    val started = service(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
    started.reply.toList()
    return started.convo.id to provider.requests.single().system!!
  }

  private suspend fun postedSystem(
    student: StudentId,
    convo: ConvoId,
  ): String {
    val provider = ScriptedProvider(listOf(endTurn))
    val posted = service(provider).postTurn(student, convo, "and now?").getOrThrow() as PostTurnResult.Started
    posted.reply.toList()
    return provider.requests.single().system!!
  }

  @Test
  fun `startConvo for a student with no list carries the empty line`() =
    runBlocking {
      val student = CostsTestDb.createStudent()

      val (_, system) = startedSystem(student)

      assertTrue(system.endsWith("\n\n" + CollegeListBlock.EMPTY_LINE), system)
    }

  @Test
  fun `startConvo for a student with two schools carries both, after every other block`() =
    runBlocking {
      val student = CostsTestDb.createStudent()
      val ohio = CostsTestDb.seedCollege("MARKER_Ohio_State")
      val kenyon = CostsTestDb.seedCollege("MARKER_Kenyon")
      add(student, ohio, reasons = "close to home and strong in nursing", livingPlan = LivingArrangement.WITH_FAMILY)
      add(student, kenyon, status = CollegeListEntryStatus.APPLYING)
      // A block that is not the date block, so "last" is tested against more than one.
      TestScoreService(CostsTestDb.database, InstantSource.fixed(today)).write(student, TestScoreWrite.Decline).getOrThrow()

      val (_, system) = startedSystem(student)

      val block =
        listOf(
          CollegeListBlock.HEADER,
          "- MARKER_Ohio_State: considering; college_id ${ohio.value}; " +
            "${CollegeListBlock.LIVING_PLAN_LEAD}living at home; ${CollegeListBlock.REASONS_LEAD}\"close to home and strong in nursing\"",
          "- MARKER_Kenyon: applying; college_id ${kenyon.value}",
        ).joinToString("\n")
      assertTrue(system.endsWith("\n\n" + block), system)
      assertTrue(system.indexOf(TestScoreCopy.DECLINED_LINE) in 0 until system.indexOf(CollegeListBlock.HEADER), system)
    }

  @Test
  fun `postTurn reflects a school added and then removed between turns`() =
    runBlocking {
      val student = CostsTestDb.createStudent()
      val reed = CostsTestDb.seedCollege("MARKER_Reed")
      val (convo, opener) = startedSystem(student)
      assertFalse(opener.contains("MARKER_Reed"), opener)

      val entry = add(student, reed)
      val afterAdd = postedSystem(student, convo)
      assertTrue(afterAdd.contains("- MARKER_Reed: considering; college_id ${reed.value}"), afterAdd)

      val removed = listService.removeFromList(student, entry.id, entry.version).getOrThrow()
      assertTrue(removed is RemoveEntryResult.Success, "the remove must have landed: $removed")
      val afterRemove = postedSystem(student, convo)
      assertFalse(afterRemove.contains("MARKER_Reed"), "a soft-deleted entry never renders: $afterRemove")
      assertTrue(afterRemove.endsWith("\n\n" + CollegeListBlock.EMPTY_LINE), afterRemove)
    }

  @Test
  fun `a school added by the tool mid-turn is not in that turn's continuation system text`() =
    runBlocking {
      val student = CostsTestDb.createStudent()
      val grinnell = CostsTestDb.seedCollege("MARKER_Grinnell")
      val provider = ScriptedProvider(listOf(addThroughTool(grinnell), endTurn))

      val started = service(provider).startConvo(student, "add Grinnell", null).getOrThrow() as StartConvoResult.Started
      started.reply.toList()

      assertEquals(2, provider.requests.size, "the opener and its continuation")
      val (opener, continuation) = provider.requests
      // The write landed, so its absence below is D4 and not a failed tool call.
      val listed = listService.listActiveWithNames(student).getOrThrow()
      assertEquals(listOf(grinnell), listed.map { it.entry.collegeId })
      assertEquals(opener.system, continuation.system, "the continuation reuses the turn's system text")
      assertFalse(continuation.system!!.contains("MARKER_Grinnell"), continuation.system!!)

      val next = postedSystem(student, started.convo.id)
      assertTrue(next.contains("- MARKER_Grinnell: considering; college_id ${grinnell.value}"), next)
    }

  @Test
  fun `a failed list read fails the turn rather than rendering an empty list`() =
    runBlocking {
      val student = CostsTestDb.createStudent()
      val (convo, _) = startedSystem(student)
      val provider = ScriptedProvider(listOf(endTurn))

      CoachingTestDb.withTableHidden("college_list_entries") {
        assertTrue(service(provider).startConvo(student, "hello", null).isFailure, "an unreadable list must fail the opener")
        assertTrue(service(provider).postTurn(student, convo, "and now?").isFailure, "an unreadable list must fail the turn")
      }
      assertEquals(emptyList(), provider.requests, "the coach is never called with a list it could not read")
    }

  @Test
  fun `the pinned prompt tells the coach it is given the list, each rule on a line of its own`() {
    val lines = CoachingTestDb.pinnedPromptBody(config).lines()
    for (
    sentence in listOf(
      "Your system text gives you the student's college list as it stood when their message arrived: " +
        "read it before you say anything about their list, and never ask them what is on it.",
      "After update_college_list changes the list, the list it returns is the current one for the rest of your reply.",
      "A school already on their list needs no lookup: use the college_id the list gives you.",
      "Never show the student a college_id.",
    )
    ) {
      assertTrue(sentence in lines, "RFC 257 D6 must be a line of the pinned prompt: [$sentence]")
    }
  }
}
