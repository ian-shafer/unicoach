package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoResponse
import ed.unicoach.db.models.ConvoResponseId
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoTurnId
import ed.unicoach.db.models.SystemPromptId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvoProjectionTest {
  private val convoId = ConvoId(UUID.randomUUID())
  private val promptId = SystemPromptId(UUID.randomUUID())

  /**
   * A request row. [turnId] defaults to [id] (a singleton turn, matching the
   * legacy backfill); an excursion's rows pass a shared [turnId] so the
   * projection groups them as one logical turn.
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
      provider = "log",
      modelRequested = "m",
      systemPromptId = promptId,
      requestParams = null,
      content = json("""[{"type":"text","text":"$text"}]"""),
      kind = kind,
      turnId = ConvoTurnId(turnId),
    )

  private fun response(
    id: Long,
    requestId: Long,
    stopReason: String,
    content: JsonElement?,
  ): ConvoResponse =
    ConvoResponse(
      id = ConvoResponseId(id),
      requestId = ConvoRequestId(requestId),
      convoId = convoId,
      content = content,
      modelResolved = "m",
      stopReason = stopReason,
      inputTokens = null,
      outputTokens = null,
      cacheReadTokens = null,
      cacheWriteTokens = null,
      providerRequestId = null,
      latencyMs = null,
      createdAt = Instant.EPOCH.plusSeconds(id),
    )

  private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

  private fun text(t: String): JsonElement = json("""[{"type":"text","text":"$t"}]""")

  @Test
  fun `a plain no-tool turn projects to one exchange`() {
    val turns =
      listOf(
        ConvoTurn(
          request(1, ConvoRequestKind.USER, "hi"),
          response(1, 1, "end_turn", text("hello")),
        ),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(1, exchanges.size)
    assertEquals(
      1L,
      exchanges
        .single()
        .userRequest.id.value,
    )
    assertEquals("hello", ConvoContent.renderText(exchanges.single().finalResponse.content!!))
  }

  @Test
  fun `an excursion collapses to one exchange keyed on the user request and final response`() {
    // The user opener (id 1) and its tool_result continuation (id 2) share turn_id 1.
    val turns =
      listOf(
        ConvoTurn(request(1, ConvoRequestKind.USER, "question", turnId = 1), response(1, 1, "tool_use", text("(tool call)"))),
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(tool result)", turnId = 1), response(2, 2, "end_turn", text("answer"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(1, exchanges.size)
    val exchange = exchanges.single()
    assertEquals(1L, exchange.userRequest.id.value)
    assertEquals(2L, exchange.finalResponse.id.value)
    assertEquals("answer", ConvoContent.renderText(exchange.finalResponse.content!!))
  }

  @Test
  fun `a failed final response yields zero exchanges`() {
    val turns =
      listOf(
        ConvoTurn(request(1, ConvoRequestKind.USER, "q", turnId = 1), response(1, 1, "tool_use", text("(tool)"))),
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1), response(2, 2, "error", content = null)),
      )

    assertEquals(0, ConvoProjection.visibleExchanges(turns).size)
  }

  @Test
  fun `a still-open excursion (no closing response) yields zero exchanges`() {
    val turns =
      listOf(
        ConvoTurn(request(1, ConvoRequestKind.USER, "q", turnId = 1), response(1, 1, "tool_use", text("(tool)"))),
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1), response = null),
      )

    assertEquals(0, ConvoProjection.visibleExchanges(turns).size)
  }

  @Test
  fun `two back-to-back excursions yield exactly two exchanges keyed by their distinct turn_ids`() {
    val turns =
      listOf(
        // Excursion A: turn_id 1.
        ConvoTurn(request(1, ConvoRequestKind.USER, "qA", turnId = 1), response(1, 1, "tool_use", text("(toolA)"))),
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(resultA)", turnId = 1), response(2, 2, "end_turn", text("answerA"))),
        // Excursion B: turn_id 3.
        ConvoTurn(request(3, ConvoRequestKind.USER, "qB", turnId = 3), response(3, 3, "tool_use", text("(toolB)"))),
        ConvoTurn(request(4, ConvoRequestKind.TOOL_RESULT, "(resultB)", turnId = 3), response(4, 4, "end_turn", text("answerB"))),
      )

    val exchanges = ConvoProjection.visibleExchanges(turns)
    assertEquals(2, exchanges.size)
    assertEquals(listOf(1L, 3L), exchanges.map { it.userRequest.id.value })
    assertEquals(listOf("answerA", "answerB"), exchanges.map { ConvoContent.renderText(it.finalResponse.content!!) })
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
        ConvoTurn(request(1, ConvoRequestKind.USER, "q", turnId = 1), response(1, 1, "tool_use", text("(tool)"))),
        // BUG shape: continuation minted a fresh turn_id 2 instead of reusing 1.
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 2), response(2, 2, "end_turn", text("answer"))),
      )
    assertEquals(0, ConvoProjection.visibleExchanges(fragmented).size)

    // The correct shape (shared turn_id 1) surfaces exactly one exchange.
    val correct =
      listOf(
        ConvoTurn(request(1, ConvoRequestKind.USER, "q", turnId = 1), response(1, 1, "tool_use", text("(tool)"))),
        ConvoTurn(request(2, ConvoRequestKind.TOOL_RESULT, "(result)", turnId = 1), response(2, 2, "end_turn", text("answer"))),
      )
    assertEquals(1, ConvoProjection.visibleExchanges(correct).size)
  }

  @Test
  fun `two turns where the first failed project only the successful one`() {
    val turns =
      listOf(
        ConvoTurn(request(1, ConvoRequestKind.USER, "doomed"), response(1, 1, "error", content = null)),
        ConvoTurn(request(2, ConvoRequestKind.USER, "ok"), response(2, 2, "end_turn", text("done"))),
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
