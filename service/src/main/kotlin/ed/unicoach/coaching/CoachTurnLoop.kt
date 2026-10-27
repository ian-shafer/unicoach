package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ChatTool
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoTurnId
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.SystemPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Who a turn is run FOR (RFC 258): the one thing the provider loop varies by.
 * The student's coach and a supporter's coach run the same loop — the same
 * logging, the same tool rounds, the same cleanup — and differ only in the
 * tools they advertise, how a tool is handed its subject, what is written
 * beside each request row, and what a successful turn marks.
 *
 * Built per turn, so a party may carry that turn's own state (the student's
 * disclosed commitments) without the loop knowing what it is.
 */
internal interface TurnParty {
  /** The registry advertised on every call of the turn. */
  val tools: ToolRegistry

  /**
   * Runs [tool] with the subject injected by the party, never by the model.
   * Total like [ChatTool.execute]; a throw is caught by the loop and answered
   * to the model as a structured failure.
   */
  suspend fun dispatch(
    tool: ChatTool,
    input: JsonObject,
  ): JsonObject

  /**
   * Called on the SAME session that just appended [request], so anything the
   * party writes beside a request row commits or rolls back with it.
   */
  fun onRequestAppended(
    session: SqlSession,
    request: ConvoRequest,
  )

  /** Called once, when the turn produced its successful final answer. */
  suspend fun onSucceeded()
}

/**
 * The settings the loop reads: the model, the per-call response ceiling, and the
 * bound on tool-dispatch rounds. Each coach supplies its own from its config.
 */
internal class TurnSettings(
  val model: String,
  val maxTokens: Int,
  val maxToolRounds: Int,
)

/**
 * One turn ready to run: the convo it belongs to, the prompt row it pins, the
 * outgoing system text, the replay history plus this turn's new user message,
 * and the [party] it runs for.
 */
internal class PreparedTurn(
  val convo: Convo,
  val prompt: SystemPrompt,
  val system: String,
  val messages: List<ChatMessage>,
  val party: TurnParty,
)

/**
 * What a turn's synchronous pre-flight transaction decided, for any coach:
 * either the turn is [Ready] to run, or the pre-flight already [Refused] it and
 * carries the exact caller-facing outcome [R] (each coach's own result type).
 * Each method's refusals are then just its own result type's arms, so no path
 * needs a null standing in for one of several distinct reasons.
 */
internal sealed interface PreFlight<out R> {
  class Ready(
    val turn: PreparedTurn,
  ) : PreFlight<Nothing>

  class Refused<R>(
    val result: R,
  ) : PreFlight<R>
}

/**
 * One provider call whose request has already been logged and whose
 * `convo_requests` extension row has been stamped: the [request] row (carrying
 * the log-owned `llm_request_id`, minted or shared `turn_id`, and `kind`) and
 * the cold [events] flow from [LlmCallLog.recordStreaming] that, on collection,
 * relays provider events and writes the terminal response row. The single unit
 * both the opener and each tool continuation are built as.
 */
internal class OpenedCall(
  val request: ConvoRequest,
  val events: Flow<ChatEvent>,
)

/**
 * The coach's provider loop (RFC 94, RFC 106), shared by every coach (RFC 258):
 * open the user's call, stream the reply, dispatch tools through the turn's
 * [TurnParty], continue, and clean up a first turn that never answered.
 *
 * A turn brackets one un-transacted provider call per iteration (the connection
 * is never held across the stream): the provider call flows through
 * [LlmCallLog.recordStreaming], which logs the request, hands back the
 * log-owned `llm_request_id`, and writes the terminal response row itself; this
 * loop stamps a `convo_requests` extension row carrying that id before
 * collecting the events. The response side of the log is owned entirely by
 * [LlmCallLog]; this loop never writes a response row.
 */
internal class CoachTurnLoop(
  private val database: Database,
  private val llmCallLog: LlmCallLog,
  private val settings: TurnSettings,
) {
  private val logger = LoggerFactory.getLogger(CoachTurnLoop::class.java)

  companion object {
    private const val COACH_UNAVAILABLE_REASON = "transient"
    private const val COACH_FAILED_REASON = "permanent"

    // Fixed tool-dispatch failure-kind markers sent to the model in an is_error
    // tool_result. Raw throwable messages never cross this boundary.
    private const val TOOL_FAILURE_UNKNOWN = "unknown_tool"
    private const val TOOL_FAILURE_THREW = "tool_threw"
  }

  /**
   * Opens the turn's `kind = 'user'` call: logs the opener [ChatRequest] via
   * [LlmCallLog.recordStreaming], mints a fresh `turn_id`, and stamps the opener
   * `convo_requests` row referencing the returned `llm_request_id`. The row is
   * written before the [OpenedCall.events] flow is collected (RFC 106), so the
   * route can render the user message from [OpenedCall.request] up front.
   */
  suspend fun openUserTurn(turn: PreparedTurn): OpenedCall {
    val request = buildCallRequest(turn, turn.messages, forceNoTools = false)
    val call = llmCallLog.recordStreaming(request) // commits the opener's llm_requests row
    try {
      val turnId = database.withConnection { session -> ConvosDao.nextTurnId(session).getOrThrow() }
      val row = appendRequestRow(turn, call.llmRequestId, turnId, ConvoRequestKind.USER)
      return OpenedCall(row, call.events)
    } catch (cancellation: CancellationException) {
      // The opener's request row is committed, but its cold events flow will
      // never be collected — whether the client disconnected (cancellation) or a
      // defect (e.g. a DB error on nextTurnId / appendRequestRow) interrupted us
      // here, before buildReplyFlow ever runs. Mirror recordStreaming's own in-flow
      // handler: write the missing response row now, where call.llmRequestId is in
      // scope, then rethrow — otherwise the just-committed request would dangle with
      // no llm_responses row.
      llmCallLog.writeCancelledIfAbsent(call.llmRequestId)
      throw cancellation
    } catch (defect: Exception) {
      // The request row is committed but its cold flow will never be collected;
      // a defect (e.g. a DB error on appendRequestRow) interrupted us. Record the
      // internal_error response so the request isn't orphaned, then rethrow.
      llmCallLog.writeInternalErrorIfAbsent(call.llmRequestId, defect)
      throw defect
    }
  }

  /**
   * Opens a `kind = 'tool_result'` continuation call for [messages]: logs the
   * continuation [ChatRequest] via [LlmCallLog.recordStreaming] and stamps a
   * `convo_requests` row sharing the excursion's [turnId] and referencing the
   * returned `llm_request_id`.
   */
  private suspend fun openContinuation(
    turn: PreparedTurn,
    messages: List<ChatMessage>,
    turnId: ConvoTurnId,
    forceNoTools: Boolean,
  ): OpenedCall {
    val request = buildCallRequest(turn, messages, forceNoTools)
    val call = llmCallLog.recordStreaming(request) // commits the continuation's llm_requests row
    try {
      val row = appendRequestRow(turn, call.llmRequestId, turnId, ConvoRequestKind.TOOL_RESULT)
      return OpenedCall(row, call.events)
    } catch (cancellation: CancellationException) {
      // The continuation's request row is committed, but its cold events flow is
      // returned uncollected — an interruption in appendRequestRow (after the
      // llm_requests commit, before collection begins) means the flow never runs
      // and no response row is ever written for it, whether the client disconnected
      // (cancellation) or a defect struck. Mirror recordStreaming's own in-flow
      // handler: write the missing response row now, where call.llmRequestId is in
      // scope, then rethrow — this closes the orphaned-continuation window
      // buildReplyFlow's loop catch cannot (there currentCall still points at the
      // previous, already-responded call).
      llmCallLog.writeCancelledIfAbsent(call.llmRequestId)
      throw cancellation
    } catch (defect: Exception) {
      // The request row is committed but its cold flow will never be collected;
      // a defect (e.g. a DB error on appendRequestRow) interrupted us. Record the
      // internal_error response so the request isn't orphaned, then rethrow.
      llmCallLog.writeInternalErrorIfAbsent(call.llmRequestId, defect)
      throw defect
    }
  }

  /**
   * Stamps one `convo_requests` extension row referencing [llmRequestId], in its
   * own transaction, and hands the same session to the party
   * ([TurnParty.onRequestAppended]) so whatever it writes beside the row commits
   * with it.
   */
  private suspend fun appendRequestRow(
    turn: PreparedTurn,
    llmRequestId: LlmRequestId,
    turnId: ConvoTurnId,
    kind: ConvoRequestKind,
  ): ConvoRequest =
    database.withConnection { session ->
      val row =
        ConvosDao
          .appendRequest(
            session,
            NewConvoRequest(
              convoId = turn.convo.id,
              systemPromptId = turn.prompt.id,
              llmRequestId = llmRequestId,
              turnId = turnId,
              kind = kind,
            ),
          ).getOrThrow()
      turn.party.onRequestAppended(session, row)
      row
    }

  /**
   * The cold reply flow: the bounded chat tool-use loop (RFC 94). Collecting it
   * runs one or more provider calls on the request coroutine, streaming text
   * deltas across every call so the user sees one continuous reply. Each call
   * persists its own `convo_requests` row and call-log pair — no billed call
   * goes unrecorded. A `tool_use` terminal dispatches the tools, appends a
   * `kind = tool_result` continuation request, extends the running message list,
   * and iterates; a non-`tool_use` terminal is the final answer.
   *
   * Cleanup is exchange-level: on the first turn, if the exchange terminates
   * without a successful final response (whether the first call or a
   * continuation failed, or the collector cancelled), the just-created convo is
   * deleted so no orphan remains. A non-first-turn failure leaves the convo with
   * the exchange non-visible, exactly as a failed postTurn does.
   *
   * `maxToolRounds` bounds tool-dispatch rounds; on reaching it while the model
   * still returns `tool_use`, the loop makes one final continuation with
   * `tools = emptyList()`, forcing a text answer.
   */
  fun buildReplyFlow(
    turn: PreparedTurn,
    opener: OpenedCall,
    isFirstTurn: Boolean,
  ): Flow<ReplyEvent> =
    flow {
      val messages = turn.messages.toMutableList()
      // The already-opened call whose events this iteration collects: the opener
      // on the first pass, a fresh tool_result continuation thereafter. Its
      // request row and llm_request are already logged; LlmCallLog owns the
      // response-row write when its events terminate.
      var currentCall = opener
      var toolRounds = 0
      var succeeded = false

      try {
        loop@ while (true) {
          val forceNoTools = toolRounds >= settings.maxToolRounds
          val terminal = collectCall(currentCall.events) { delta -> emit(ReplyEvent.Delta(delta)) }

          when (terminal) {
            is ChatEvent.Rejected -> {
              emit(failedEvent(turn, currentCall.request, retriable = false, reason = terminal.reason))
              break@loop
            }

            is ChatEvent.TransientFailure -> {
              emit(failedEvent(turn, currentCall.request, retriable = true, reason = terminal.reason))
              break@loop
            }

            is ChatEvent.Completed -> {
              val chatResponse = terminal.response
              val toolUses = dispatchableToolUses(chatResponse, forceNoTools)

              if (toolUses.isEmpty()) {
                // Final answer (a plain end_turn, the forced no-tools call, or a
                // malformed tool_use with no dispatchable block). The party marks
                // what a successful turn settles (the student's disclosed
                // commitments, RFC 93) — bound to success, because a failed first
                // turn soft-deletes the convo.
                turn.party.onSucceeded()
                succeeded = true
                emit(
                  ReplyEvent.Completed(
                    convoRequest = currentCall.request,
                    content = chatResponse.content,
                    createdAt = currentCall.request.createdAt,
                  ),
                )
                break@loop
              }

              // Dispatch every requested tool, then continue the loop with the
              // assistant's verbatim tool_use message and one tool_result answer.
              val toolResultContent = ConvoContent.blockArray(dispatchTools(toolUses, turn.party))
              messages.appendToolRound(chatResponse.content, toolResultContent)
              currentCall = openContinuation(turn, messages, currentCall.request.turnId, toolRounds + 1 >= settings.maxToolRounds)
              toolRounds++
            }
          }
        }
      } catch (cancellation: CancellationException) {
        // Client disconnected mid-loop. By the time this catch runs, currentCall's
        // response row has always already been written, so there is nothing to
        // repair here — only rethrow (after the finally's first-turn cleanup). The
        // per-opened-call cancellation guarantee is owned at the source: openUserTurn
        // and openContinuation each write their own request's cancelled row if a
        // disconnect strikes after recordStreaming commits its llm_requests row but
        // before its cold events flow is collected; and recordStreaming itself owns
        // the in-flight write when the disconnect lands WHILE currentCall.events is
        // being collected (its own NonCancellable catch). A writeCancelledIfAbsent
        // for currentCall here would be dead: currentCall never points at an
        // uncollected, unresponded request when this catch fires — the opener/
        // continuation guard would have already handled that request before it ever
        // became currentCall.
        throw cancellation
      } catch (defect: Exception) {
        // A non-cancellation exception escaping the loop is a provider defect. Its
        // response row is written by LlmCallLog as internal_error before the
        // exception reaches here (the stream flow catches and records it); the port
        // contract says treat it as transient to the client.
        logger.warn(
          "coach loop defect for convo=[{}] request=[{}]: [{}]",
          turn.convo.id.asString,
          currentCall.request.id.asString,
          defect.message,
          defect,
        )
        emit(failedEvent(turn, currentCall.request, retriable = true, reason = "provider defect: ${defect.message}"))
      } finally {
        // Exchange-level first-turn cleanup: a first turn that never produced a
        // successful final response leaves no orphan convo behind.
        if (isFirstTurn && !succeeded) {
          withContext(NonCancellable) { deleteConvoQuietly(turn.convo.id) }
        }
      }
    }

  /**
   * Assembles the wire-shape [ChatRequest] for one loop iteration. On the cap
   * round ([forceNoTools]) the tool set is empty, forcing the model to a text
   * answer; otherwise it advertises the party's full registry.
   */
  private fun buildCallRequest(
    turn: PreparedTurn,
    messages: List<ChatMessage>,
    forceNoTools: Boolean,
  ): ChatRequest =
    ChatRequest(
      model = settings.model,
      system = turn.system,
      messages = messages,
      maxTokens = settings.maxTokens,
      tools = if (forceNoTools) emptyList() else turn.party.tools.definitions(),
    )

  /**
   * The `tool_use` blocks the loop must dispatch this round: the response's tool
   * uses when the model terminated on [ConvoProjection.TOOL_USE_STOP_REASON] and
   * this is not the forced no-tools cap round; empty otherwise (a plain answer,
   * the forced final call, or a malformed terminal with no dispatchable block).
   */
  private fun dispatchableToolUses(
    chatResponse: ChatResponse,
    forceNoTools: Boolean,
  ): List<ConvoContent.ToolUse> =
    if (!forceNoTools && chatResponse.stopReason == ConvoProjection.TOOL_USE_STOP_REASON) {
      ConvoContent.toolUses(chatResponse.content)
    } else {
      emptyList()
    }

  /**
   * Extends the running message list for the next continuation call: the
   * assistant's verbatim [assistantContent] (carrying the `tool_use` blocks
   * Anthropic requires echoed back) then the [toolResultContent] user message.
   */
  private fun MutableList<ChatMessage>.appendToolRound(
    assistantContent: JsonElement,
    toolResultContent: JsonElement,
  ) {
    add(ChatMessage(ChatRole.ASSISTANT, assistantContent))
    add(ChatMessage(ChatRole.USER, toolResultContent))
  }

  /**
   * Collects one call's already-logged event flow (from [OpenedCall.events]),
   * relaying text deltas via [onDelta] and returning the provider terminal. The
   * response row is written by [LlmCallLog] when the flow terminates; this only
   * reads the terminal to drive the loop. A stream that ends without a terminal
   * is a provider-contract defect.
   */
  private suspend fun collectCall(
    events: Flow<ChatEvent>,
    onDelta: suspend (String) -> Unit,
  ): ChatEvent.Terminal {
    var terminal: ChatEvent.Terminal? = null
    events.collect { event ->
      when (event) {
        is ChatEvent.ContentBlockDelta -> {
          val delta = event.delta
          if (delta is ContentDelta.Text) onDelta(delta.text)
        }

        is ChatEvent.Terminal -> {
          terminal = event
        }

        is ChatEvent.MessageStart,
        is ChatEvent.ContentBlockStart,
        is ChatEvent.ContentBlockStop,
        is ChatEvent.MessageDelta,
        is ChatEvent.Raw,
        -> {
          // Live-view events: the response row is written by LlmCallLog, so the
          // loop reads only text deltas and the terminal. Listed rather than an
          // `else` so a new event kind is a compile error, not a silent drop.
        }
      }
    }
    return terminal
      ?: throw IllegalStateException("chat provider [${llmCallLog.providerId}] stream completed without a terminal event")
  }

  /**
   * Dispatches every requested tool in order, answering each with one
   * `tool_result` block (`tool_use_id` matched). A tool that returns an object
   * is a normal result the model reads (`is_error` unset), even its own
   * `{ "error": ... }` domain error. An unknown tool name (model hallucination)
   * or a throwing tool yields an `is_error` result with a structured failure
   * object, so the model can recover — a tool defect is never a turn failure.
   *
   * The tool receives its subject from [party] ([TurnParty.dispatch]) — the
   * student's id for the student's coach (RFC 134), the linked student for a
   * supporter's (RFC 258) — so the model never sees or supplies one.
   *
   * The failure that is sent to (and persisted for) the model is squashed to a
   * fixed failure-kind marker; the throwable's message and type — which may
   * carry internals — stay in the log only. `ChatTool.execute` is total by
   * contract, so a throw is an exceptional defect worth a full stack trace.
   */
  private suspend fun dispatchTools(
    toolUses: List<ConvoContent.ToolUse>,
    party: TurnParty,
  ): List<JsonObject> =
    toolUses.map { toolUse ->
      val tool = party.tools.get(toolUse.name)
      if (tool == null) {
        logger.warn("chat requested unknown tool name=[{}] id=[{}]", toolUse.name, toolUse.id)
        return@map ConvoContent.toolResultBlock(
          toolUse.id,
          toolFailure(toolUse.name, TOOL_FAILURE_UNKNOWN),
          isError = true,
        )
      }
      try {
        val result = party.dispatch(tool, toolUse.input)
        ConvoContent.toolResultBlock(toolUse.id, result, isError = false)
      } catch (e: Throwable) {
        logger.warn("tool [{}] threw during dispatch id=[{}] input=[{}]", toolUse.name, toolUse.id, toolUse.input, e)
        ConvoContent.toolResultBlock(
          toolUse.id,
          toolFailure(toolUse.name, TOOL_FAILURE_THREW),
          isError = true,
        )
      }
    }

  /**
   * A structured tool-dispatch failure sent to the model: the tool [name] and a
   * fixed [failureKind] marker, never the raw throwable message (which may carry
   * internals). The model reads these fields to recover; root-cause detail is in
   * the log.
   */
  private fun toolFailure(
    name: String,
    failureKind: String,
  ): JsonObject =
    buildJsonObject {
      put("tool", name)
      put("failure", failureKind)
    }

  /** Soft-deletes the convo, swallowing (bracketed-logging) any failure — cleanup must not mask the turn outcome. */
  private suspend fun deleteConvoQuietly(convoId: ConvoId) {
    try {
      database.withConnection { session -> ConvosDao.delete(session, convoId).getOrThrow() }
    } catch (e: Exception) {
      logger.error("first-turn cleanup delete failed for convo=[{}]", convoId.asString, e)
    }
  }

  /**
   * The client-facing failure for a terminal that did not answer, logged with
   * everything needed to trace it: the conversation, the request whose call
   * ended the turn, and the party the turn ran for (both coaches share this loop).
   */
  private fun failedEvent(
    turn: PreparedTurn,
    request: ConvoRequest,
    retriable: Boolean,
    reason: String,
  ): ReplyEvent.Failed {
    logger.warn(
      "coach turn failed convo=[{}] request=[{}] party=[{}] retriable=[{}] reason=[{}]",
      turn.convo.id.asString,
      request.id.asString,
      turn.convo.party,
      retriable,
      reason,
    )
    return ReplyEvent.Failed(retriable = retriable, reason = if (retriable) COACH_UNAVAILABLE_REASON else COACH_FAILED_REASON)
  }
}
