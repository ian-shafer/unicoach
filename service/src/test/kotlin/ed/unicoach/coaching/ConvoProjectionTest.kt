package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoTurnId
import ed.unicoach.db.models.LlmCall
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmFailureKind
import ed.unicoach.db.models.LlmRequest
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.LlmResponse
import ed.unicoach.db.models.LlmResponseId
import ed.unicoach.db.models.SystemPromptId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvoProjectionTest {
  private val convoId = ConvoId(UUID.randomUUID())
  private val promptId = SystemPromptId(UUID.randomUUID())

  /**
   * A request row paired with its logged call (RFC 106). [turnId] defaults to
   * [id] (a singleton turn); an excursion's rows pass a shared [turnId] so the
   * projection groups them as one logical turn. The user input [text] is written
   * as the last `role = user` message of the joined [LlmRequest.content] messages
   * array — the tail the projection lifts as this turn's `userContent`.
   */
  private fun request(
    id: Long,
    kind: ConvoRequestKind,
    text: String = "u$id",
    turnId: Long = id,
  ): ConvoRequest =
    ConvoRequest(
      id = ConvoRequestId(id),
      convoId = convoId,
      createdAt = Instant.EPOCH.plusSeconds(id),
      systemPromptId = promptId,
      llmRequestId = LlmRequestId(id),
      kind = kind,
      turnId = ConvoTurnId(turnId),
    )

  /** The joined `llm_requests` row whose messages tail carries this turn's user [text]. */
  private fun llmRequest(
    id: Long,
    text: String,
  ): LlmRequest =
    LlmRequest(
      id = LlmRequestId(id),
      createdAt = Instant.EPOCH.plusSeconds(id),
      provider = "log",
      modelRequested = "m",
      system = null,
      content = messages(text),
      maxTokens = 1024,
      tools = null,
      toolChoice = null,
      params = null,
    )

  /** A completed response carrying [content], or a failed response when [content] is null. */
  private fun response(
    id: Long,
    requestId: Long,
    stopReason: String,
    content: JsonElement?,
  ): LlmResponse =
    LlmResponse(
      id = LlmResponseId(id),
      createdAt = Instant.EPOCH.plusSeconds(id),
      requestId = LlmRequestId(requestId),
      outcome =
        if (content != null) {
          LlmCallOutcome.Completed(content = content, modelResolved = "m", stopReason = stopReason)
        } else {
          LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, stopReason)
        },
      providerRequestId = null,
      inputTokens = null,
      outputTokens = null,
      cacheReadTokens = null,
      cacheWriteTokens = null,
      latencyMs = 0,
    )

  /** Builds a [ConvoTurn] whose joined call carries the request messages and the (nullable) response. */
  private fun turn(
    id: Long,
    kind: ConvoRequestKind,
    userText: String,
    turnId: Long = id,
    response: LlmResponse?,
  ): ConvoTurn =
    ConvoTurn(
      request = request(id, kind, userText, turnId),
      call = LlmCall(request = llmRequest(id, userText), response = response, raw = null),
    )

  private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

  /** A `messages` array whose sole (last) user message content-block array carries [t]. */
  private fun messages(t: String): JsonArray = json("""[{"role":"user","content":[{"type":"text","text":"$t"}]}]""") as JsonArray

  private fun text(t: String): JsonElement = json("""[{"type":"text","text":"$t"}]""")

  @Test
  fun `a plain no-tool turn projects to one exchange`() {
    val turns =
      listOf(
        turn(1, ConvoRequestKind.USER, "hi", response = response(1, 1, "end_turn", text("hello"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(1, exchanges.size)
    assertEquals(
      1L,
      exchanges
        .single()
        .userRequest.id.value,
    )
    assertEquals("hello", ConvoContent.renderText(exchanges.single().finalContent))
  }

  @Test
  fun `an excursion collapses to one exchange keyed on the user request and final response`() {
    // The user opener (id 1) and its tool_result continuation (id 2) share turn_id 1.
    val turns =
      listOf(
        turn(1, ConvoRequestKind.USER, "question", turnId = 1, response = response(1, 1, "tool_use", text("(tool call)"))),
        turn(2, ConvoRequestKind.TOOL_RESULT, "(tool result)", turnId = 1, response = response(2, 2, "end_turn", text("answer"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(1, exchanges.size)
    val exchange = exchanges.single()
    assertEquals(1L, exchange.userRequest.id.value)
    assertEquals(2L, exchange.finalResponse.id.value)
    assertEquals("answer", ConvoContent.renderText(exchange.finalContent))
  }

  @Test
  fun `a failed final response yields zero exchanges`() {
    val turns =
      listOf(
        turn(1, ConvoRequestKind.USER, "q", turnId = 1, response = response(1, 1, "tool_use", text("(tool)"))),
        turn(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1, response = response(2, 2, "error", content = null)),
      )

    assertEquals(0, ConvoProjection.visibleExchanges(turns).size)
  }

  @Test
  fun `a still-open excursion (no closing response) yields zero exchanges`() {
    val turns =
      listOf(
        turn(1, ConvoRequestKind.USER, "q", turnId = 1, response = response(1, 1, "tool_use", text("(tool)"))),
        turn(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1, response = null),
      )

    assertEquals(0, ConvoProjection.visibleExchanges(turns).size)
  }

  @Test
  fun `two back-to-back excursions yield exactly two exchanges keyed by their distinct turn_ids`() {
    val turns =
      listOf(
        // Excursion A: turn_id 1.
        turn(1, ConvoRequestKind.USER, "qA", turnId = 1, response = response(1, 1, "tool_use", text("(toolA)"))),
        turn(2, ConvoRequestKind.TOOL_RESULT, "(resultA)", turnId = 1, response = response(2, 2, "end_turn", text("answerA"))),
        // Excursion B: turn_id 3.
        turn(3, ConvoRequestKind.USER, "qB", turnId = 3, response = response(3, 3, "tool_use", text("(toolB)"))),
        turn(4, ConvoRequestKind.TOOL_RESULT, "(resultB)", turnId = 3, response = response(4, 4, "end_turn", text("answerB"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(2, exchanges.size)
    assertEquals(listOf(1L, 3L), exchanges.map { it.userRequest.id.value })
    assertEquals(listOf("answerA", "answerB"), exchanges.map { ConvoContent.renderText(it.finalContent) })
  }

  @Test
  fun `a continuation stamped with a wrong fresh turn_id fragments into a phantom open group (regression guard)`() {
    // The shared-turn_id invariant: a loop-written continuation MUST reuse its
    // opener's turn_id. If a refactor instead minted a FRESH turn_id (here 2) for
    // the tool_result continuation, the opener's group (turn_id 1) never sees a
    // non-tool_use response and the continuation's group (turn_id 2) has no
    // kind='user' opener — so BOTH fragments drop and the excursion vanishes,
    // rather than surfacing with the wrong pairing. This asserts the projection
    // fails safe (zero exchanges), guarding the invariant the write path enforces.
    val fragmented =
      listOf(
        turn(1, ConvoRequestKind.USER, "q", turnId = 1, response = response(1, 1, "tool_use", text("(tool)"))),
        // BUG shape: continuation minted a fresh turn_id 2 instead of reusing 1.
        turn(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 2, response = response(2, 2, "end_turn", text("answer"))),
      )
    assertEquals(0, ConvoProjection.visibleExchanges(fragmented).size)

    // The correct shape (shared turn_id 1) surfaces exactly one exchange.
    val correct =
      listOf(
        turn(1, ConvoRequestKind.USER, "q", turnId = 1, response = response(1, 1, "tool_use", text("(tool)"))),
        turn(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1, response = response(2, 2, "end_turn", text("answer"))),
      )
    assertEquals(1, ConvoProjection.visibleExchanges(correct).size)
  }

  @Test
  fun `two turns where the first failed project only the successful one`() {
    val turns =
      listOf(
        turn(1, ConvoRequestKind.USER, "doomed", response = response(1, 1, "error", content = null)),
        turn(2, ConvoRequestKind.USER, "ok", response = response(2, 2, "end_turn", text("done"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(1, exchanges.size)
    assertEquals(
      2L,
      exchanges
        .single()
        .userRequest.id.value,
    )
  }
}
