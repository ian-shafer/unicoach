package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewConvoResponse
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
 * A turn is two transactions bracketing one un-transacted provider call (the
 * connection is never held across the stream): tx-1 validates, persists the
 * user request, and snapshots replay history; collecting [ReplyEvent] flow runs
 * the provider; tx-2 persists exactly one response row for the request.
 */
class CoachingService(
  private val database: Database,
  private val chatProvider: ChatProvider,
  private val config: CoachingConfig,
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
      val preflight =
        database.withConnection { session ->
          val prompt = resolveSystemPrompt(session)
          val convo = ConvosDao.create(session, NewConvo(studentId, resolvedName)).getOrThrow()
          val userTurn = appendUserTurn(session, convo.id, prompt.id, message)
          val messages = visibleHistory(session, convo.id) + ChatMessage.text(ChatRole.USER, message)
          // Next-session opener (RFC 93): compose open explicit commitments into
          // the coach prompt so the coach raises them naturally in its first reply.
          val pending = openExplicitCommitments(session, studentId)
          Preflight(convo, userTurn, prompt, composeSystem(prompt, pending), messages, pending.map { it.id })
        }
      StartConvoResult.Started(
        convo = preflight.convo,
        userTurn = preflight.userTurn,
        reply = buildReplyFlow(preflight, isFirstTurn = true),
      )
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
      val preflight =
        database.withConnection { session ->
          val owned = loadOwned(session, convoId, studentId)
          if (owned == null) {
            null
          } else {
            val prompt = resolveSystemPrompt(session)
            val userTurn = appendUserTurn(session, convoId, prompt.id, message)
            val messages = visibleHistory(session, convoId) + ChatMessage.text(ChatRole.USER, message)
            // postTurn never surfaces commitments: only a new conversation opens
            // with reflection, so an insight is not re-raised mid-conversation.
            Preflight(owned, userTurn, prompt, prompt.body, messages, emptyList())
          }
        }
      if (preflight == null) {
        PostTurnResult.NotFound
      } else {
        PostTurnResult.Started(
          convo = preflight.convo,
          userTurn = preflight.userTurn,
          reply = buildReplyFlow(preflight, isFirstTurn = false),
        )
      }
    }
  }

  private class Preflight(
    val convo: Convo,
    val userTurn: ConvoRequest,
    val prompt: SystemPrompt,
    // The outgoing system text: the prompt body, optionally with open explicit
    // commitments (RFC 93) composed in for the next-session opener.
    val system: String,
    // Visible prior turns (USER/ASSISTANT pairs) plus this turn's new user message.
    val messages: List<ChatMessage>,
    // Open explicit commitments surfaced in this turn's opener; marked fulfilled
    // on a successful terminal. Empty for postTurn and when nothing is pending.
    val disclosedCommitmentIds: List<CommitmentId>,
  )

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
    preflight: Preflight,
    isFirstTurn: Boolean,
  ): Flow<ReplyEvent> =
    flow {
      val messages = preflight.messages.toMutableList()
      // The request row whose response the current call persists: the user turn
      // on the first call, a fresh tool_result continuation row thereafter.
      var currentRequest = preflight.userTurn
      var toolRounds = 0
      var succeeded = false
      // Wall-clock start of the in-flight provider call, captured in the loop
      // body so the outer provider-defect catch can record real elapsed time.
      var iterationStart = System.currentTimeMillis()

      try {
        loop@ while (true) {
          val forceNoTools = toolRounds >= config.maxToolRounds
          iterationStart = System.currentTimeMillis()
          val call = runProviderCall(buildCallRequest(preflight, messages, forceNoTools)) { delta -> emit(ReplyEvent.Delta(delta)) }

          when (val persisted = persistCallResponse(preflight.convo.id, currentRequest, call.terminal, call.latencyMs)) {
            is CallOutcome.Failed -> {
              emit(persisted.event)
              break@loop
            }

            is CallOutcome.Completed -> {
              val chatResponse = persisted.chatResponse
              val toolUses = dispatchableToolUses(chatResponse, forceNoTools)

              if (toolUses.isEmpty()) {
                // Final answer (a plain end_turn, the forced no-tools call, or a
                // malformed tool_use with no dispatchable block).
                // A successful turn surfaced any disclosed commitments (RFC 93):
                // mark them fulfilled against this convo. Bound to success — a
                // failed turn soft-deletes the convo, so they stay open to
                // re-surface next session.
                markDisclosedCommitmentsFulfilled(preflight)
                succeeded = true
                emit(ReplyEvent.Completed(persisted.response))
                break@loop
              }

              // Dispatch every requested tool, then continue the loop with the
              // assistant's verbatim tool_use message and one tool_result answer.
              val toolResultContent = ConvoContent.blockArray(dispatchTools(toolUses))
              messages.appendToolRound(chatResponse.content, toolResultContent)
              currentRequest = appendToolResultTurn(preflight, toolResultContent)
              toolRounds++
            }
          }
        }
      } catch (cancellation: kotlinx.coroutines.CancellationException) {
        // Client disconnected mid-loop: the in-flight provider call is cancelled
        // cooperatively. Record the abandoned turn for the in-flight request iff
        // no response row for it exists yet.
        withContext(NonCancellable) {
          persistAbandoned(preflight.convo.id, currentRequest)
        }
        throw cancellation
      } catch (defect: Exception) {
        // A non-cancellation exception escaping the loop is a provider defect;
        // the port contract says treat it as transient. Log the real defect here
        // with its stack trace: the fallback persist below records for
        // `currentRequest`, which in the rare tool_result-write-failure window is
        // the already-answered prior request, so its own duplicate-insert failure
        // would otherwise be the only thing logged, masking this root cause.
        logger.warn(
          "coach loop defect for convo=[{}] request=[{}]: [{}]",
          preflight.convo.id.asString,
          currentRequest.id.asString,
          defect.message,
          defect,
        )
        val event =
          persistCallResponse(
            preflight.convo.id,
            currentRequest,
            SyntheticFailure(retriable = true, reason = "provider defect: ${defect.message}", providerRequestId = null),
            latencyMs = (System.currentTimeMillis() - iterationStart).toInt(),
          )
        if (event is CallOutcome.Failed) emit(event.event)
      } finally {
        // Exchange-level first-turn cleanup: a first turn that never produced a
        // successful final response leaves no orphan convo behind.
        if (isFirstTurn && !succeeded) {
          withContext(NonCancellable) { deleteConvoQuietly(preflight.convo.id) }
        }
      }
    }

  /**
   * Assembles the wire-shape [ChatRequest] for one loop iteration. On the cap
   * round ([forceNoTools]) the tool set is empty, forcing the model to a text
   * answer; otherwise it advertises the full registry.
   */
  private fun buildCallRequest(
    preflight: Preflight,
    messages: List<ChatMessage>,
    forceNoTools: Boolean,
  ): ChatRequest =
    ChatRequest(
      model = config.model,
      system = preflight.system,
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

  /** Runs one provider call, streaming text deltas via [onDelta], and pairs the terminal with its measured latency. */
  private suspend fun runProviderCall(
    request: ChatRequest,
    onDelta: suspend (String) -> Unit,
  ): TimedTerminal {
    val start = System.currentTimeMillis()
    val terminal = collectTurn(request, onDelta)
    return TimedTerminal(terminal, (System.currentTimeMillis() - start).toInt())
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

  /** Collects the provider stream, relaying text deltas via [onDelta], returning the terminal. */
  private suspend fun collectTurn(
    request: ChatRequest,
    onDelta: suspend (String) -> Unit,
  ): TurnTerminal {
    var terminal: TurnTerminal? = null
    chatProvider.stream(request).collect { event ->
      when (event) {
        is ChatEvent.ContentBlockDelta -> {
          val delta = event.delta
          if (delta is ContentDelta.Text) onDelta(delta.text)
        }

        is ChatEvent.Completed -> {
          terminal = CompletedTerminal(event.response, event.rawPayload)
        }

        is ChatEvent.Rejected -> {
          terminal =
            FailureTerminal(
              retriable = false,
              reason = event.reason,
              providerRequestId = event.providerRequestId,
              rawPayload = event.rawPayload,
            )
        }

        is ChatEvent.TransientFailure -> {
          terminal =
            FailureTerminal(
              retriable = true,
              reason = event.reason,
              providerRequestId = event.providerRequestId,
              rawPayload = event.rawPayload,
            )
        }

        else -> {}
      }
    }
    return terminal
      ?: throw IllegalStateException("chat provider [${chatProvider.id}] stream completed without a terminal event")
  }

  /**
   * Writes the response row for [requestRow] (this call's `convo_responses`
   * pair) and classifies the outcome for the loop. A [CallOutcome.Completed]
   * carries both the persisted row and the parsed [ChatResponse] (so the loop
   * can read `stop_reason`/content without re-reading the DB); a failure
   * (provider Rejected/Transient, synthetic defect, or a non-durable write)
   * yields [CallOutcome.Failed]. This is the per-call recording point for every
   * outcome — success or failure — so no billed call goes unrecorded.
   */
  private suspend fun persistCallResponse(
    convoId: ConvoId,
    requestRow: ConvoRequest,
    terminal: TurnTerminal,
    latencyMs: Int,
  ): CallOutcome =
    try {
      database.withConnection { session ->
        when (terminal) {
          is CompletedTerminal -> {
            val response =
              ConvosDao
                .appendResponse(
                  session,
                  completedRow(requestRow, terminal.response, latencyMs),
                  terminal.rawPayload,
                ).getOrThrow()
            CallOutcome.Completed(response, terminal.response)
          }

          is FailureTerminal -> {
            ConvosDao
              .appendResponse(
                session,
                errorRow(requestRow, terminal.providerRequestId, latencyMs),
                terminal.rawPayload,
              ).getOrThrow()
            CallOutcome.Failed(failedEvent(terminal.retriable, terminal.reason))
          }

          is SyntheticFailure -> {
            ConvosDao
              .appendResponse(
                session,
                errorRow(requestRow, terminal.providerRequestId, latencyMs),
                null,
              ).getOrThrow()
            CallOutcome.Failed(failedEvent(terminal.retriable, terminal.reason))
          }
        }
      }
    } catch (e: Exception) {
      // A reply that is not durable is never reported as success: listMessages
      // could never show it. Log the loss (bracketed) and report transient.
      logger.error(
        "terminal persistence failed for convo=[{}] request=[{}]: [{}]",
        convoId.asString,
        requestRow.id.asString,
        e.message,
      )
      CallOutcome.Failed(ReplyEvent.Failed(retriable = true, reason = COACH_UNAVAILABLE_REASON))
    }

  /**
   * Dispatches every requested tool in order, answering each with one
   * `tool_result` block (`tool_use_id` matched). A tool that returns an object
   * is a normal result the model reads (`is_error` unset), even its own
   * `{ "error": ... }` domain error. An unknown tool name (model hallucination)
   * or a throwing tool yields an `is_error` result with a structured failure
   * object, so the model can recover — a tool defect is never a turn failure.
   *
   * The failure that is sent to (and persisted for) the model is squashed to a
   * fixed failure-kind marker; the throwable's message and type — which may
   * carry internals — stay in the log only. `ChatTool.execute` is total by
   * contract, so a throw is an exceptional defect worth a full stack trace.
   */
  private suspend fun dispatchTools(toolUses: List<ConvoContent.ToolUse>): List<JsonObject> =
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
        ConvoContent.toolResultBlock(toolUse.id, tool.execute(toolUse.input), isError = false)
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

  /**
   * Appends the tool_result continuation request row (`kind = tool_result`),
   * carrying the identical `tool_result` block array that is sent as the next
   * call's new input. Its envelope mirrors the user turn's; `request_params` is
   * null (the tool set is static registry config, not per-request vendor params).
   *
   * It reuses the opener's `turn_id` (`preflight.userTurn.turnId`, surfaced by
   * the opener's `RETURNING *`) — never re-minting — so every row of the
   * excursion shares one turn_id and the visible-exchange projection and
   * extraction window group them as one logical turn.
   */
  private suspend fun appendToolResultTurn(
    preflight: Preflight,
    content: JsonElement,
  ): ConvoRequest =
    database.withConnection { session ->
      ConvosDao
        .appendRequest(
          session,
          NewConvoRequest(
            convoId = preflight.convo.id,
            provider = chatProvider.id,
            modelRequested = config.model,
            systemPromptId = preflight.prompt.id,
            requestParams = null,
            content = content,
            turnId = preflight.userTurn.turnId,
            kind = ConvoRequestKind.TOOL_RESULT,
          ),
        ).getOrThrow()
    }

  /** NonCancellable finalizer write for an abandoned (client-disconnected) in-flight request. */
  private suspend fun persistAbandoned(
    convoId: ConvoId,
    requestRow: ConvoRequest,
  ) {
    try {
      database.withConnection { session ->
        ConvosDao
          .appendResponse(
            session,
            errorRow(requestRow, providerRequestId = null, latencyMs = null),
            null,
          ).getOrThrow()
      }
    } catch (e: Exception) {
      logger.error(
        "abandoned-turn persistence failed for convo=[{}] request=[{}]: [{}]",
        convoId.asString,
        requestRow.id.asString,
        e.message,
      )
    }
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
   * Composes the outgoing system text: the prompt body verbatim when nothing is
   * pending (identical to today), else the body plus a rendered reflection block
   * so the coach raises the commitments naturally in its first reply.
   */
  private fun composeSystem(
    prompt: SystemPrompt,
    pending: List<Commitment>,
  ): String {
    if (pending.isEmpty()) return prompt.body
    val block =
      buildString {
        appendLine(prompt.body)
        appendLine()
        appendLine(
          "Since you last spoke, you have been reflecting on the following — raise what is relevant, naturally:",
        )
        for (commitment in pending) {
          appendLine("- ${commitment.statement}")
        }
      }
    return block.trimEnd()
  }

  /**
   * Marks the commitments disclosed in this turn's opener (RFC 93) fulfilled
   * against the convo, in one transaction. Called only on the loop's final
   * successful answer — a failed turn soft-deletes the convo, leaving the
   * commitments open to re-surface next session. Empty for postTurn and when
   * nothing was pending, so this is a no-op on the common path.
   */
  private suspend fun markDisclosedCommitmentsFulfilled(preflight: Preflight) {
    if (preflight.disclosedCommitmentIds.isEmpty()) return
    database.withConnection { session ->
      for (commitmentId in preflight.disclosedCommitmentIds) {
        CommitmentsDao.markFulfilled(session, commitmentId, preflight.convo.id).getOrThrow()
      }
    }
  }

  /**
   * Appends the `kind='user'` opener of a new logical turn, minting its
   * `turn_id` once via [ConvosDao.nextTurnId]. The loop threads this same
   * `turn_id` onto every `tool_result` continuation of the excursion (see
   * [appendToolResultTurn]), so all rows of the turn share one value; it is never
   * re-minted mid-excursion.
   */
  private fun appendUserTurn(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
    systemPromptId: SystemPromptId,
    message: String,
  ): ConvoRequest {
    val turnId = ConvosDao.nextTurnId(session).getOrThrow()
    return ConvosDao
      .appendRequest(
        session,
        NewConvoRequest(
          convoId = convoId,
          provider = chatProvider.id,
          modelRequested = config.model,
          systemPromptId = systemPromptId,
          requestParams = null,
          content = ConvoContent.userContent(message),
          turnId = turnId,
        ),
      ).getOrThrow()
  }

  /**
   * Visible exchanges as ordered chat messages: USER then ASSISTANT per
   * exchange, text-only. Cross-turn replay stays text-only (thinking and tool
   * plumbing are not replayed); a tool excursion collapses to its user text and
   * final answer via [ConvoProjection.visibleExchanges].
   */
  private fun visibleHistory(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
  ): List<ChatMessage> {
    val exchanges = ConvoProjection.visibleExchanges(ConvosDao.listTurns(session, convoId).getOrThrow())
    return buildList {
      for (exchange in exchanges) {
        add(ChatMessage.text(ChatRole.USER, ConvoContent.renderText(exchange.userRequest.content)))
        add(ChatMessage.text(ChatRole.ASSISTANT, ConvoContent.renderText(exchange.finalResponse.content ?: continue)))
      }
    }
  }

  private fun completedRow(
    requestRow: ConvoRequest,
    response: ChatResponse,
    latencyMs: Int,
  ): NewConvoResponse =
    NewConvoResponse(
      requestId = requestRow.id,
      convoId = requestRow.convoId,
      content = response.content,
      modelResolved = response.modelResolved,
      stopReason = response.stopReason,
      inputTokens = response.usage.inputTokens,
      outputTokens = response.usage.outputTokens,
      cacheReadTokens = response.usage.cacheReadTokens,
      cacheWriteTokens = response.usage.cacheWriteTokens,
      providerRequestId = response.providerRequestId,
      latencyMs = latencyMs,
    )

  private fun errorRow(
    requestRow: ConvoRequest,
    providerRequestId: String?,
    latencyMs: Int?,
  ): NewConvoResponse =
    NewConvoResponse(
      requestId = requestRow.id,
      convoId = requestRow.convoId,
      content = null,
      modelResolved = null,
      stopReason = "error",
      inputTokens = null,
      outputTokens = null,
      cacheReadTokens = null,
      cacheWriteTokens = null,
      providerRequestId = providerRequestId,
      latencyMs = latencyMs,
    )

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

  // ---------------------------------------------------------------------------
  // Internal terminal carriers
  // ---------------------------------------------------------------------------

  private sealed interface TurnTerminal

  /** One provider call's terminal paired with its measured wall-clock latency. */
  private class TimedTerminal(
    val terminal: TurnTerminal,
    val latencyMs: Int,
  )

  private class CompletedTerminal(
    val response: ChatResponse,
    val rawPayload: JsonElement,
  ) : TurnTerminal

  private class FailureTerminal(
    val retriable: Boolean,
    val reason: String,
    val providerRequestId: String?,
    val rawPayload: JsonElement?,
  ) : TurnTerminal

  private class SyntheticFailure(
    val retriable: Boolean,
    val reason: String,
    val providerRequestId: String?,
  ) : TurnTerminal

  /**
   * The classified outcome of one persisted provider call in the loop.
   * [Completed] carries the persisted response row and the parsed [ChatResponse]
   * (so the loop reads `stop_reason`/content without re-reading the DB);
   * [Failed] carries the mapped terminal event.
   */
  private sealed interface CallOutcome {
    data class Completed(
      val response: ed.unicoach.db.models.ConvoResponse,
      val chatResponse: ChatResponse,
    ) : CallOutcome

    data class Failed(
      val event: ReplyEvent.Failed,
    ) : CallOutcome
  }
}
