package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentDelta
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoTurn
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
) {
  private val logger = LoggerFactory.getLogger(CoachingService::class.java)

  companion object {
    const val MESSAGE_MAX_LENGTH = 100_000
    const val NAME_DERIVATION_MAX = 80
    private const val MESSAGE_FIELD = "message"
    private const val NAME_FIELD = "name"

    private const val COACH_UNAVAILABLE_REASON = "transient"
    private const val COACH_FAILED_REASON = "permanent"
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
          val visible = ConvosDao.listTurns(session, convoId).getOrThrow().filter(::isVisible)
          ListTurnsResult.Found(visible)
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
          is ValidationResult.Valid -> result.value
          is ValidationResult.Invalid ->
            return Result.success(
              UpdateConvoResult.ValidationFailure(listOf(nameFieldError(result.error))),
            )
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
            true -> ConvosDao.archive(session, convoId).getOrThrow()
            false -> ConvosDao.unarchive(session, convoId).getOrThrow()
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
          is ValidationResult.Valid -> result.value
          is ValidationResult.Invalid ->
            return Result.success(StartConvoResult.ValidationFailure(listOf(nameFieldError(result.error))))
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
          val messages = visibleHistory(session, convo.id) + ChatMessage(ChatRole.USER, message)
          Preflight(convo, userTurn, prompt, messages)
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
            val messages = visibleHistory(session, convoId) + ChatMessage(ChatRole.USER, message)
            Preflight(owned, userTurn, prompt, messages)
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
    // Visible prior turns (USER/ASSISTANT pairs) plus this turn's new user message.
    val messages: List<ChatMessage>,
  )

  /**
   * The cold reply flow. Collecting it executes the provider call (tx-1 already
   * committed the user turn), relays text deltas, persists exactly one response
   * row in tx-2, and emits the terminal. A collector cancellation persists the
   * abandoned error row under NonCancellable iff no response row exists yet.
   */
  private fun buildReplyFlow(
    preflight: Preflight,
    isFirstTurn: Boolean,
  ): Flow<ReplyEvent> =
    flow {
      val request =
        ChatRequest(
          model = config.model,
          system = preflight.prompt.body,
          messages = preflight.messages,
          maxTokens = config.maxTokens,
        )

      var responsePersisted = false
      val start = System.currentTimeMillis()
      var providerRequestId: String? = null

      try {
        val terminal = collectTurn(request) { delta -> emit(ReplyEvent.Delta(delta)) }
        val latencyMs = (System.currentTimeMillis() - start).toInt()
        providerRequestId = terminal.providerRequestId()

        val event = persistTerminal(preflight, terminal, latencyMs, isFirstTurn)
        responsePersisted = true
        emit(event)
      } catch (cancellation: kotlinx.coroutines.CancellationException) {
        // Client disconnected: the provider call is cancelled cooperatively.
        // Record the abandoned turn iff tx-2 has not already committed a row.
        if (!responsePersisted) {
          withContext(NonCancellable) {
            persistAbandoned(preflight, isFirstTurn)
          }
        }
        throw cancellation
      } catch (defect: Exception) {
        // A non-cancellation exception escaping the flow is a provider defect;
        // the port contract says treat it as transient.
        if (!responsePersisted) {
          val latencyMs = (System.currentTimeMillis() - start).toInt()
          val event =
            persistTerminal(
              preflight,
              SyntheticFailure(retriable = true, reason = "provider defect: ${defect.message}", providerRequestId = providerRequestId),
              latencyMs,
              isFirstTurn,
            )
          responsePersisted = true
          emit(event)
        }
      }
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

        is ChatEvent.Completed -> terminal = CompletedTerminal(event.response, event.rawPayload)
        is ChatEvent.Rejected ->
          terminal =
            FailureTerminal(
              retriable = false,
              reason = event.reason,
              providerRequestId = event.providerRequestId,
              rawPayload = event.rawPayload,
            )
        is ChatEvent.TransientFailure ->
          terminal =
            FailureTerminal(
              retriable = true,
              reason = event.reason,
              providerRequestId = event.providerRequestId,
              rawPayload = event.rawPayload,
            )
        else -> {}
      }
    }
    return terminal
      ?: throw IllegalStateException("chat provider [${chatProvider.id}] stream completed without a terminal event")
  }

  /** Writes the terminal response row (tx-2) and maps it to a [ReplyEvent]. */
  private suspend fun persistTerminal(
    preflight: Preflight,
    terminal: TurnTerminal,
    latencyMs: Int,
    isFirstTurn: Boolean,
  ): ReplyEvent =
    try {
      database.withConnection { session ->
        when (terminal) {
          is CompletedTerminal -> {
            val response =
              ConvosDao
                .appendResponse(
                  session,
                  completedRow(preflight.userTurn, terminal.response, latencyMs),
                  terminal.rawPayload,
                ).getOrThrow()
            ReplyEvent.Completed(response)
          }

          is FailureTerminal -> {
            ConvosDao
              .appendResponse(
                session,
                errorRow(preflight.userTurn, terminal.providerRequestId, latencyMs),
                terminal.rawPayload,
              ).getOrThrow()
            if (isFirstTurn) ConvosDao.delete(session, preflight.convo.id).getOrThrow()
            failedEvent(terminal.retriable, terminal.reason)
          }

          is SyntheticFailure -> {
            ConvosDao
              .appendResponse(
                session,
                errorRow(preflight.userTurn, terminal.providerRequestId, latencyMs),
                null,
              ).getOrThrow()
            if (isFirstTurn) ConvosDao.delete(session, preflight.convo.id).getOrThrow()
            failedEvent(terminal.retriable, terminal.reason)
          }
        }
      }
    } catch (e: Exception) {
      // A reply that is not durable is never reported as success: listMessages
      // could never show it. Log the loss (bracketed) and report transient.
      logger.error(
        "terminal persistence failed for convo=[{}] request=[{}]: [{}]",
        preflight.convo.id.asString,
        preflight.userTurn.id.asString,
        e.message,
      )
      ReplyEvent.Failed(retriable = true, reason = COACH_UNAVAILABLE_REASON)
    }

  /** NonCancellable finalizer write for an abandoned (client-disconnected) turn. */
  private suspend fun persistAbandoned(
    preflight: Preflight,
    isFirstTurn: Boolean,
  ) {
    try {
      database.withConnection { session ->
        ConvosDao
          .appendResponse(
            session,
            errorRow(preflight.userTurn, providerRequestId = null, latencyMs = null),
            null,
          ).getOrThrow()
        if (isFirstTurn) ConvosDao.delete(session, preflight.convo.id).getOrThrow()
      }
    } catch (e: Exception) {
      logger.error(
        "abandoned-turn persistence failed for convo=[{}] request=[{}]: [{}]",
        preflight.convo.id.asString,
        preflight.userTurn.id.asString,
        e.message,
      )
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

  private fun appendUserTurn(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
    systemPromptId: SystemPromptId,
    message: String,
  ): ConvoRequest =
    ConvosDao
      .appendRequest(
        session,
        NewConvoRequest(
          convoId = convoId,
          provider = chatProvider.id,
          modelRequested = config.model,
          systemPromptId = systemPromptId,
          requestParams = null,
          content = ConvoContent.userContent(message),
        ),
      ).getOrThrow()

  /** Visible turns (success responses) as ordered chat messages: USER then ASSISTANT per turn. */
  private fun visibleHistory(
    session: ed.unicoach.db.dao.SqlSession,
    convoId: ConvoId,
  ): List<ChatMessage> {
    val turns = ConvosDao.listTurns(session, convoId).getOrThrow().filter(::isVisible)
    return buildList {
      for (turn in turns) {
        add(ChatMessage(ChatRole.USER, ConvoContent.renderText(turn.request.content)))
        val response = turn.response ?: continue
        add(ChatMessage(ChatRole.ASSISTANT, ConvoContent.renderText(response.content ?: continue)))
      }
    }
  }

  private fun isVisible(turn: ConvoTurn): Boolean = turn.response?.content != null

  private fun completedRow(
    userTurn: ConvoRequest,
    response: ChatResponse,
    latencyMs: Int,
  ): NewConvoResponse =
    NewConvoResponse(
      requestId = userTurn.id,
      convoId = userTurn.convoId,
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
    userTurn: ConvoRequest,
    providerRequestId: String?,
    latencyMs: Int?,
  ): NewConvoResponse =
    NewConvoResponse(
      requestId = userTurn.id,
      convoId = userTurn.convoId,
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

  private sealed interface TurnTerminal {
    fun providerRequestId(): String?
  }

  private class CompletedTerminal(
    val response: ChatResponse,
    val rawPayload: JsonElement,
  ) : TurnTerminal {
    override fun providerRequestId(): String? = response.providerRequestId
  }

  private class FailureTerminal(
    val retriable: Boolean,
    val reason: String,
    val providerRequestId: String?,
    val rawPayload: JsonElement?,
  ) : TurnTerminal {
    override fun providerRequestId(): String? = providerRequestId
  }

  private class SyntheticFailure(
    val retriable: Boolean,
    val reason: String,
    val providerRequestId: String?,
  ) : TurnTerminal {
    override fun providerRequestId(): String? = providerRequestId
  }
}
