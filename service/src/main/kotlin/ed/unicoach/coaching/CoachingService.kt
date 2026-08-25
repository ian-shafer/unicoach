package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.FitSuggestionForOpener
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.SystemPromptId
import ed.unicoach.error.FieldError
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
 * Coaching domain layer, sibling to AuthService/StudentService: constructor DI,
 * suspend methods returning Result<sealed outcome>, all DB access through
 * `database.withConnection`, no HTTP/Ktor imports.
 *
 * Student resolution stays in the routes; this service takes a [StudentId] and
 * enforces ownership on every convo operation (a row missing, soft-deleted, or
 * owned by another student is the not-found outcome — existence is never
 * leaked). Archived convos remain fetchable and writable.
 *
 * A turn brackets one un-transacted provider call (the connection is never held
 * across the stream): tx-1 validates and snapshots replay history; the provider
 * call flows through [LlmCallLog.recordStreaming], which logs the request, hands
 * back the log-owned `llm_request_id`, and writes the terminal response row
 * itself; this service stamps a `convo_requests` extension row carrying that id
 * before collecting the [ReplyEvent] flow. The response side of the log is owned
 * entirely by [LlmCallLog] (RFC 106); this service never writes a response row.
 *
 * The unit of budget admission is the TURN, not the individual provider call
 * (RFC 109): [budgetService] is consulted once in each turn's pre-flight
 * transaction — the last point where the outcome can still be a sealed variant
 * with nothing persisted — and an admitted turn then runs to its terminal without
 * being re-gated, so a half-answered turn is never stranded for spend already made.
 * The parameter is undefaulted: a root cannot ship an ungated coach by omission.
 */
class CoachingService(
  private val database: Database,
  private val llmCallLog: LlmCallLog,
  private val config: CoachingConfig,
  private val budgetService: BudgetService,
  private val tools: ToolRegistry = ToolRegistry(emptyList()),
) {
  private val logger = LoggerFactory.getLogger(CoachingService::class.java)

  companion object {
    const val MESSAGE_MAX_LENGTH = 100_000
    const val NAME_DERIVATION_MAX = 80
    private const val MESSAGE_FIELD = "message"
    private const val NAME_FIELD = "name"

    private const val COACH_UNAVAILABLE_REASON = "transient"
    private const val COACH_FAILED_REASON = "permanent"

    // Fixed tool-dispatch failure-kind markers sent to the model in an is_error
    // tool_result. Raw throwable messages never cross this boundary.
    private const val TOOL_FAILURE_UNKNOWN = "unknown_tool"
    private const val TOOL_FAILURE_THREW = "tool_threw"
  }

  // ---------------------------------------------------------------------------
  // Lifecycle reads/writes
  // ---------------------------------------------------------------------------

  suspend fun listConvos(
    studentId: StudentId,
    archive: ArchiveScope,
  ): Result<List<ConvoWithActivity>> =
    runCatching {
      database.withConnection { session ->
        ConvosDao.listByStudentWithActivity(session, studentId, archive).getOrThrow()
      }
    }

  suspend fun getConvo(
    studentId: StudentId,
    convoId: ConvoId,
  ): Result<GetConvoResult> =
    runCatching {
      database.withConnection { session ->
        val listing = ConvosDao.findByIdWithActivity(session, convoId).getOrNull()
        if (listing == null || listing.convo.studentId != studentId) {
          GetConvoResult.NotFound
        } else {
          GetConvoResult.Found(listing)
        }
      }
    }

  suspend fun deleteConvo(
    studentId: StudentId,
    convoId: ConvoId,
  ): Result<DeleteConvoResult> =
    runCatching {
      database.withConnection { session ->
        val owned = loadOwned(session, convoId, studentId)
        if (owned == null) {
          DeleteConvoResult.NotFound
        } else {
          ConvosDao.delete(session, convoId).getOrThrow()
          DeleteConvoResult.Success
        }
      }
    }

  suspend fun listTurns(
    studentId: StudentId,
    convoId: ConvoId,
  ): Result<ListTurnsResult> =
    runCatching {
      database.withConnection { session ->
        val owned = loadOwned(session, convoId, studentId)
        if (owned == null) {
          ListTurnsResult.NotFound
        } else {
          val exchanges = ConvoProjection.visibleExchanges(ConvosDao.listTurns(session, convoId).getOrThrow())
          ListTurnsResult.Found(exchanges)
        }
      }
    }

  suspend fun updateConvo(
    studentId: StudentId,
    convoId: ConvoId,
    update: ConvoUpdate,
  ): Result<UpdateConvoResult> {
    if (update.name == null && update.archived == null) {
      return Result.success(
        UpdateConvoResult.ValidationFailure(
          listOf(FieldError(NAME_FIELD, "At least one of name or archived must be supplied")),
        ),
      )
    }

    val validatedName: ConvoName? =
      if (update.name != null) {
        when (val result = ConvoName.create(update.name)) {
          is ValidationResult.Valid -> {
            result.value
          }

          is ValidationResult.Invalid -> {
            return Result.success(
              UpdateConvoResult.ValidationFailure(listOf(nameFieldError(result.error))),
            )
          }
        }
      } else {
        null
      }

    return runCatching {
      database.withConnection { session ->
        val owned = loadOwned(session, convoId, studentId)
        if (owned == null) {
          UpdateConvoResult.NotFound
        } else {
          // Rename strictly first: archive's SET LOCAL bypass suppresses the
          // updated_at trigger for the rest of the transaction.
          if (validatedName != null) {
            ConvosDao.rename(session, convoId, validatedName).getOrThrow()
          }
          when (update.archived) {
            true -> {
              ConvosDao.archive(session, convoId).getOrThrow()
            }

            false -> {
              ConvosDao.unarchive(session, convoId).getOrThrow()
            }

            null -> {}
          }
          val listing = ConvosDao.findByIdWithActivity(session, convoId).getOrThrow()
          UpdateConvoResult.Success(listing)
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Turn path
  // ---------------------------------------------------------------------------

  suspend fun startConvo(
    studentId: StudentId,
    message: String,
    name: String?,
  ): Result<StartConvoResult> {
    val messageError = validateMessage(message)
    if (messageError != null) {
      return Result.success(StartConvoResult.ValidationFailure(listOf(messageError)))
    }
    val resolvedName: ConvoName =
      if (name != null) {
        when (val result = ConvoName.create(name)) {
          is ValidationResult.Valid -> {
            result.value
          }

          is ValidationResult.Invalid -> {
            return Result.success(StartConvoResult.ValidationFailure(listOf(nameFieldError(result.error))))
          }
        }
      } else {
        deriveName(message)
      }

    return runCatching {
      val preFlight: PreFlight<StartConvoResult> =
        database.withConnection { session ->
          // Budget first, before ConvosDao.create: a refused turn leaves no convo
          // row, no convo_requests row, and no llm_requests row behind.
          when (val verdict = budgetService.verdict(session, studentId).getOrThrow()) {
            is BudgetVerdict.Exhausted -> {
              return@withConnection PreFlight.Refused(StartConvoResult.BudgetExhausted(verdict.entitlement))
            }

            // Not dead: this arm is what makes the `when` exhaustive, so a third
            // BudgetVerdict would fail to compile here instead of falling through
            // as "allowed to spend". Do not collapse it back to an `is` check.
            BudgetVerdict.Entitled -> {}
          }
          val prompt = resolveSystemPrompt(session)
          val convo = ConvosDao.create(session, NewConvo(studentId, resolvedName)).getOrThrow()
          val messages = visibleHistory(session, convo.id) + ChatMessage.text(ChatRole.USER, message)
          // Next-session opener: compose open explicit commitments (RFC 93) and
          // open fit-lens suggestions (RFC 98) into the coach prompt so the coach
          // raises them naturally in its first reply.
          val pending = openExplicitCommitments(session, studentId)
          val pendingFits = openFitSuggestions(session, studentId)
          val moneyProfile = activeMoneyProfile(session, studentId)
          PreFlight.Ready(
            Prepared(
              convo,
              prompt,
              composeSystem(prompt, pending, pendingFits, moneyProfile),
              messages,
              pending.map { it.id },
              pendingFits.map { it.id },
            ),
          )
        }
      when (preFlight) {
        is PreFlight.Refused -> {
          preFlight.result
        }

        is PreFlight.Ready -> {
          val opener = openUserTurn(preFlight.prepared)
          StartConvoResult.Started(
            convo = preFlight.prepared.convo,
            userTurn = opener.request,
            reply = buildReplyFlow(preFlight.prepared, opener, isFirstTurn = true),
          )
        }
      }
    }
  }

  suspend fun postTurn(
    studentId: StudentId,
    convoId: ConvoId,
    message: String,
  ): Result<PostTurnResult> {
    val messageError = validateMessage(message)
    if (messageError != null) {
      return Result.success(PostTurnResult.ValidationFailure(listOf(messageError)))
    }

    return runCatching {
      val preFlight: PreFlight<PostTurnResult> =
        database.withConnection { session ->
          // Ownership outranks budget: a missing, soft-deleted, or foreign convo
          // stays NotFound, so only an owned convo can learn its student's budget
          // state.
          val owned =
            loadOwned(session, convoId, studentId)
              ?: return@withConnection PreFlight.Refused(PostTurnResult.NotFound)
          when (val verdict = budgetService.verdict(session, studentId).getOrThrow()) {
            is BudgetVerdict.Exhausted -> {
              return@withConnection PreFlight.Refused(PostTurnResult.BudgetExhausted(verdict.entitlement))
            }

            // Not dead: this arm is what makes the `when` exhaustive, so a third
            // BudgetVerdict would fail to compile here instead of falling through
            // as "allowed to spend". Do not collapse it back to an `is` check.
            BudgetVerdict.Entitled -> {}
          }
          val prompt = resolveSystemPrompt(session)
          val messages = visibleHistory(session, convoId) + ChatMessage.text(ChatRole.USER, message)
          // postTurn never surfaces commitments or fit suggestions: only a new
          // conversation opens with reflection, so an insight is not re-raised
          // mid-conversation. The money-profile block (RFC 134) IS composed on
          // every turn: what may be used and what must not be re-asked applies
          // mid-conversation too.
          val moneyProfile = activeMoneyProfile(session, studentId)
          PreFlight.Ready(
            Prepared(owned, prompt, composeSystem(prompt, emptyList(), emptyList(), moneyProfile), messages, emptyList(), emptyList()),
          )
        }
      when (preFlight) {
        is PreFlight.Refused -> {
          preFlight.result
        }

        is PreFlight.Ready -> {
          val opener = openUserTurn(preFlight.prepared)
          PostTurnResult.Started(
            convo = preFlight.prepared.convo,
            userTurn = opener.request,
            reply = buildReplyFlow(preFlight.prepared, opener, isFirstTurn = false),
          )
        }
      }
    }
  }

  /**
   * What a turn's synchronous pre-flight transaction decided: either the turn is
   * [Ready] to run, or the pre-flight already [Refused] it and carries the exact
   * caller-facing outcome ([StartConvoResult] or [PostTurnResult]). Each method's
   * refusals are then just its own result type's arms, so neither path needs a
   * null standing in for one of several distinct reasons.
   */
  private sealed interface PreFlight<out R> {
    class Ready(
      val prepared: Prepared,
    ) : PreFlight<Nothing>

    class Refused<R>(
      val result: R,
    ) : PreFlight<R>
  }

  private class Prepared(
    val convo: Convo,
    val prompt: SystemPrompt,
    // The outgoing system text: the prompt body, optionally with open explicit
    // commitments (RFC 93) composed in for the next-session opener.
    val system: String,
    // Visible prior turns (USER/ASSISTANT pairs) plus this turn's new user message.
    val messages: List<ChatMessage>,
    // Open explicit commitments surfaced in this turn's opener; marked fulfilled
    // on a successful terminal. Empty for postTurn and when nothing is pending.
    val disclosedCommitmentIds: List<CommitmentId>,
    // Open fit-lens suggestions (RFC 98) surfaced in this turn's opener; marked
    // surfaced on a successful terminal. Empty for postTurn and when none pending.
    val surfacedFitSuggestionIds: List<FitSuggestionId>,
  )

  /**
   * One provider call whose request has already been logged and whose
   * `convo_requests` extension row has been stamped: the [request] row (carrying
   * the log-owned `llm_request_id`, minted or shared `turn_id`, and `kind`) and
   * the cold [events] flow from [LlmCallLog.recordStreaming] that, on collection,
   * relays provider events and writes the terminal response row. The single unit
   * both the opener and each tool continuation are built as.
   */
  private class OpenedCall(
    val request: ConvoRequest,
    val events: kotlinx.coroutines.flow.Flow<ChatEvent>,
  )

  /**
   * Opens the turn's `kind = 'user'` call: logs the opener [ChatRequest] via
   * [LlmCallLog.recordStreaming], mints a fresh `turn_id`, and stamps the opener
   * `convo_requests` row referencing the returned `llm_request_id`. The row is
   * written before the [OpenedCall.events] flow is collected (RFC 106), so the
   * route can render the user message from [OpenedCall.request] up front.
   */
  private suspend fun openUserTurn(prepared: Prepared): OpenedCall {
    val request = buildCallRequest(prepared, prepared.messages, forceNoTools = false)
    val call = llmCallLog.recordStreaming(request) // commits the opener's llm_requests row
    try {
      val turnId = database.withConnection { session -> ConvosDao.nextTurnId(session).getOrThrow() }
      val row = appendRequestRow(prepared, call.llmRequestId, turnId, ConvoRequestKind.USER)
      return OpenedCall(row, call.events)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
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
    prepared: Prepared,
    messages: List<ChatMessage>,
    turnId: ed.unicoach.db.models.ConvoTurnId,
    forceNoTools: Boolean,
  ): OpenedCall {
    val request = buildCallRequest(prepared, messages, forceNoTools)
    val call = llmCallLog.recordStreaming(request) // commits the continuation's llm_requests row
    try {
      val row = appendRequestRow(prepared, call.llmRequestId, turnId, ConvoRequestKind.TOOL_RESULT)
      return OpenedCall(row, call.events)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
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

  /** Stamps one `convo_requests` extension row referencing [llmRequestId], in its own transaction. */
  private suspend fun appendRequestRow(
    prepared: Prepared,
    llmRequestId: LlmRequestId,
    turnId: ed.unicoach.db.models.ConvoTurnId,
    kind: ConvoRequestKind,
  ): ConvoRequest =
    database.withConnection { session ->
      ConvosDao
        .appendRequest(
          session,
          NewConvoRequest(
            convoId = prepared.convo.id,
            systemPromptId = prepared.prompt.id,
            llmRequestId = llmRequestId,
            turnId = turnId,
            kind = kind,
          ),
        ).getOrThrow()
    }

  /**
   * The cold reply flow: the bounded chat tool-use loop (RFC 94). Collecting it
   * runs one or more provider calls on the request coroutine, streaming text
   * deltas across every call so the user sees one continuous reply. Each call
   * persists its own `convo_requests` + `convo_responses` pair — no billed call
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
  private fun buildReplyFlow(
    prepared: Prepared,
    opener: OpenedCall,
    isFirstTurn: Boolean,
  ): Flow<ReplyEvent> =
    flow {
      val messages = prepared.messages.toMutableList()
      // The already-opened call whose events this iteration collects: the opener
      // on the first pass, a fresh tool_result continuation thereafter. Its
      // request row and llm_request are already logged; LlmCallLog owns the
      // response-row write when its events terminate.
      var currentCall = opener
      var toolRounds = 0
      var succeeded = false

      try {
        loop@ while (true) {
          val forceNoTools = toolRounds >= config.maxToolRounds
          val terminal = collectCall(currentCall.events) { delta -> emit(ReplyEvent.Delta(delta)) }

          when (terminal) {
            is ChatEvent.Rejected -> {
              emit(failedEvent(retriable = false, reason = terminal.reason))
              break@loop
            }

            is ChatEvent.TransientFailure -> {
              emit(failedEvent(retriable = true, reason = terminal.reason))
              break@loop
            }

            is ChatEvent.Completed -> {
              val chatResponse = terminal.response
              val toolUses = dispatchableToolUses(chatResponse, forceNoTools)

              if (toolUses.isEmpty()) {
                // Final answer (a plain end_turn, the forced no-tools call, or a
                // malformed tool_use with no dispatchable block).
                // A successful turn surfaced any disclosed commitments (RFC 93):
                // mark them fulfilled against this convo. Bound to success — a
                // failed turn soft-deletes the convo, so they stay open to
                // re-surface next session.
                markDisclosedCommitmentsFulfilled(prepared)
                markSurfacedFitSuggestions(prepared)
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
              val toolResultContent = ConvoContent.blockArray(dispatchTools(toolUses, prepared.convo.studentId))
              messages.appendToolRound(chatResponse.content, toolResultContent)
              currentCall = openContinuation(prepared, messages, currentCall.request.turnId, toolRounds + 1 >= config.maxToolRounds)
              toolRounds++
            }
          }
        }
      } catch (cancellation: kotlinx.coroutines.CancellationException) {
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
          prepared.convo.id.asString,
          currentCall.request.id.asString,
          defect.message,
          defect,
        )
        emit(failedEvent(retriable = true, reason = "provider defect: ${defect.message}"))
      } finally {
        // Exchange-level first-turn cleanup: a first turn that never produced a
        // successful final response leaves no orphan convo behind.
        if (isFirstTurn && !succeeded) {
          withContext(NonCancellable) { deleteConvoQuietly(prepared.convo.id) }
        }
      }
    }

  /**
   * Assembles the wire-shape [ChatRequest] for one loop iteration. On the cap
   * round ([forceNoTools]) the tool set is empty, forcing the model to a text
   * answer; otherwise it advertises the full registry.
   */
  private fun buildCallRequest(
    prepared: Prepared,
    messages: List<ChatMessage>,
    forceNoTools: Boolean,
  ): ChatRequest =
    ChatRequest(
      model = config.model,
      system = prepared.system,
      messages = messages,
      maxTokens = config.maxTokens,
      tools = if (forceNoTools) emptyList() else tools.definitions(),
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

        else -> {}
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
   * A [StudentScopedChatTool] receives the turn's [studentId] (RFC 134) so it
   * writes the owning student's data without the model ever seeing or
   * supplying an id; a plain [ed.unicoach.chat.ChatTool] is dispatched
   * unchanged.
   *
   * The failure that is sent to (and persisted for) the model is squashed to a
   * fixed failure-kind marker; the throwable's message and type — which may
   * carry internals — stay in the log only. `ChatTool.execute` is total by
   * contract, so a throw is an exceptional defect worth a full stack trace.
   */
  private suspend fun dispatchTools(
    toolUses: List<ConvoContent.ToolUse>,
    studentId: StudentId,
  ): List<JsonObject> =
    toolUses.map { toolUse ->
      val tool = tools.get(toolUse.name)
      if (tool == null) {
        logger.warn("chat requested unknown tool name=[{}] id=[{}]", toolUse.name, toolUse.id)
        return@map ConvoContent.toolResultBlock(
          toolUse.id,
          toolFailure(toolUse.name, TOOL_FAILURE_UNKNOWN),
          isError = true,
        )
      }
      try {
        val result =
          when (tool) {
            is StudentScopedChatTool -> tool.execute(studentId, toolUse.input)
            else -> tool.execute(toolUse.input)
          }
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
      logger.error("first-turn cleanup delete failed for convo=[{}]: [{}]", convoId.asString, e.message)
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Loads an active convo iff it is owned by [studentId]; null otherwise (no existence leak). */
  private fun loadOwned(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
    studentId: StudentId,
  ): Convo? {
    val convo = ConvosDao.findById(session, convoId, SoftDeleteScope.ACTIVE).getOrNull() ?: return null
    return if (convo.studentId == studentId) convo else null
  }

  private fun resolveSystemPrompt(session: ed.unicoach.db.dao.SqlSession): SystemPrompt {
    val result = SystemPromptsDao.findByNameAndVersion(session, config.systemPromptName, config.systemPromptVersion)
    return result.getOrElse {
      throw IllegalStateException(
        "system prompt not found for name=[${config.systemPromptName}] version=[${config.systemPromptVersion}]",
        it,
      )
    }
  }

  /**
   * The student's open explicit commitments for the next-session opener (RFC 93),
   * or empty when the feature is disabled — the sole gate for the whole opener
   * path, so a disabled feature reads nothing and behaves exactly as before.
   */
  private fun openExplicitCommitments(
    session: ed.unicoach.db.dao.SqlSession,
    studentId: StudentId,
  ): List<Commitment> =
    if (config.surfaceCommitments) {
      CommitmentsDao.listOpenExplicitByStudent(session, studentId).getOrThrow()
    } else {
      emptyList()
    }

  /**
   * The student's open fit-lens suggestions for the next-session opener (RFC 98),
   * or empty when the feature is disabled — the sole gate for the fit-lens opener
   * contribution, so a disabled feature reads nothing and behaves exactly as
   * before.
   */
  private fun openFitSuggestions(
    session: ed.unicoach.db.dao.SqlSession,
    studentId: StudentId,
  ): List<FitSuggestionForOpener> =
    if (config.surfaceFitSuggestions) {
      FitSuggestionsDao.listOpenForOpener(session, studentId).getOrThrow()
    } else {
      emptyList()
    }

  /**
   * The student's active money profile for the coach context block (RFC 134),
   * or null before the first write — an absent row composes nothing, so a
   * student who has never touched money topics keeps the prompt verbatim.
   * Any other failure propagates like the sibling context loaders
   * ([openExplicitCommitments], [openFitSuggestions]): a DB outage must fail
   * the turn, never silently drop the declined-field guard.
   */
  private fun activeMoneyProfile(
    session: ed.unicoach.db.dao.SqlSession,
    studentId: StudentId,
  ): MoneyProfile? =
    MoneyProfilesDao.findActiveByStudent(session, studentId).getOrElse { e ->
      if (e is ed.unicoach.db.dao.NotFoundException) null else throw e
    }

  /**
   * Composes the outgoing system text: the prompt body verbatim when nothing is
   * pending (identical to today), else the body plus a rendered reflection block
   * so the coach raises the commitments (RFC 93) and fit-lens suggestions (RFC 98)
   * naturally in its first reply, plus the money-profile block (RFC 134) once a
   * profile row exists.
   */
  private fun composeSystem(
    prompt: SystemPrompt,
    pending: List<Commitment>,
    pendingFits: List<FitSuggestionForOpener>,
    moneyProfile: MoneyProfile? = null,
  ): String {
    if (pending.isEmpty() && pendingFits.isEmpty() && moneyProfile == null) return prompt.body
    val block =
      buildString {
        appendLine(prompt.body)
        if (pending.isNotEmpty()) {
          appendLine()
          appendLine(
            "Since you last spoke, you have been reflecting on the following — raise what is relevant, naturally:",
          )
          for (commitment in pending) {
            appendLine("- ${commitment.statement}")
          }
        }
        for (fit in pendingFits) {
          appendLine()
          appendLine("I found a school you'd love: ${fit.collegeName} (${fit.city}, ${fit.state}) — ${fit.rationale}")
        }
        if (moneyProfile != null) {
          appendLine()
          appendLine(
            "Money profile (use answered values; a declined field was asked and declined — never re-ask it " +
              "unless the student reopens the topic; an unanswered field is still open):",
          )
          appendLine(
            "- household income band: " +
              renderMoneyField(moneyProfile.incomeBandStatus, moneyProfile.incomeBand?.value, "income_band", moneyProfile),
          )
          appendLine(
            "- state of residency: " +
              renderMoneyField(moneyProfile.residencyStatus, moneyProfile.residencyState, "residency_state", moneyProfile),
          )
        }
      }
    return block.trimEnd()
  }

  /**
   * One money-profile field line: `answered (value)` | `declined` |
   * `unanswered` (RFC 134). An `answered` status with no value violates the
   * schema's value-iff-answered CHECK — row corruption, surfaced as
   * [CorruptPersistedValueException] naming the [column] and [profile] row
   * (the DAO's convention for a corrupt read), never rendered into the prompt.
   */
  private fun renderMoneyField(
    status: AnswerStatus,
    value: String?,
    column: String,
    profile: MoneyProfile,
  ): String =
    when (status) {
      AnswerStatus.ANSWERED -> {
        value?.let { "answered ($it)" }
          ?: throw CorruptPersistedValueException(
            "null",
            ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
            location = "money_profiles.$column (row ${profile.id.value})",
          )
      }

      AnswerStatus.DECLINED -> {
        "declined"
      }

      AnswerStatus.UNANSWERED -> {
        "unanswered"
      }
    }

  /**
   * Marks the commitments disclosed in this turn's opener (RFC 93) fulfilled
   * against the convo, in one transaction. Called only on the loop's final
   * successful answer — a failed turn soft-deletes the convo, leaving the
   * commitments open to re-surface next session. Empty for postTurn and when
   * nothing was pending, so this is a no-op on the common path.
   */
  private suspend fun markDisclosedCommitmentsFulfilled(prepared: Prepared) {
    if (prepared.disclosedCommitmentIds.isEmpty()) return
    database.withConnection { session ->
      for (commitmentId in prepared.disclosedCommitmentIds) {
        CommitmentsDao.markFulfilled(session, commitmentId, prepared.convo.id).getOrThrow()
      }
    }
  }

  /**
   * Marks the fit-lens suggestions surfaced in this turn's opener (RFC 98) as
   * surfaced against the convo, in one transaction. Called only on the loop's
   * final successful answer — a failed turn soft-deletes the convo, leaving the
   * suggestions open to re-surface next session. Empty for postTurn and when
   * nothing was pending, so this is a no-op on the common path.
   */
  private suspend fun markSurfacedFitSuggestions(prepared: Prepared) {
    if (prepared.surfacedFitSuggestionIds.isEmpty()) return
    database.withConnection { session ->
      for (suggestionId in prepared.surfacedFitSuggestionIds) {
        FitSuggestionsDao.markSurfaced(session, suggestionId, prepared.convo.id).getOrThrow()
      }
    }
  }

  /**
   * Visible exchanges as ordered chat messages: USER then ASSISTANT per
   * exchange, text-only. Cross-turn replay stays text-only (thinking and tool
   * plumbing are not replayed); a tool excursion collapses to its user text and
   * final answer via [ConvoProjection.visibleExchanges]. Both content sides come
   * from the joined generic call log (RFC 106): the user input from
   * [VisibleExchange.userContent], the coach answer from [VisibleExchange.finalContent].
   */
  private fun visibleHistory(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
  ): List<ChatMessage> {
    val exchanges = ConvoProjection.visibleExchanges(ConvosDao.listTurns(session, convoId).getOrThrow())
    return buildList {
      for (exchange in exchanges) {
        add(ChatMessage.text(ChatRole.USER, ConvoContent.renderText(exchange.userContent)))
        add(ChatMessage.text(ChatRole.ASSISTANT, ConvoContent.renderText(exchange.finalContent)))
      }
    }
  }

  private fun failedEvent(
    retriable: Boolean,
    reason: String,
  ): ReplyEvent.Failed {
    logger.warn("coach turn failed retriable=[{}] reason=[{}]", retriable, reason)
    return ReplyEvent.Failed(retriable = retriable, reason = if (retriable) COACH_UNAVAILABLE_REASON else COACH_FAILED_REASON)
  }

  private fun validateMessage(message: String): FieldError? {
    val trimmed = message.trim()
    return when {
      trimmed.isBlank() -> FieldError(MESSAGE_FIELD, "Message must not be blank")
      message.length > MESSAGE_MAX_LENGTH -> FieldError(MESSAGE_FIELD, "Message exceeds $MESSAGE_MAX_LENGTH characters")
      else -> null
    }
  }

  /** Derives a name from the first message: collapse whitespace, truncate to NAME_DERIVATION_MAX, trim. */
  private fun deriveName(message: String): ConvoName {
    val collapsed = message.trim().replace(Regex("\\s+"), " ")
    val truncated = collapsed.take(NAME_DERIVATION_MAX).trim()
    return when (val result = ConvoName.create(truncated)) {
      is ValidationResult.Valid -> result.value

      // A non-blank message always yields a valid name; defensive fallback.
      is ValidationResult.Invalid -> (ConvoName.create("Conversation") as ValidationResult.Valid).value
    }
  }

  private fun nameFieldError(error: ValidationError): FieldError = FieldError(NAME_FIELD, "Invalid name: $error")
}
