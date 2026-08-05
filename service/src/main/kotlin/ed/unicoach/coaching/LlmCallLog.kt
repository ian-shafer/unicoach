package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.TokenUsage
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.models.FrozenCost
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmFailureKind
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewLlmRequest
import ed.unicoach.db.models.NewLlmResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The single seam through which every LLM provider call flows (RFC 106). A
 * concrete wrapper (not a `ChatProvider`) holding the pure [ChatProvider] and
 * owning every write to the provider-agnostic call log (`llm_requests` /
 * `llm_responses` / `llm_responses_raw`). Because each composition root injects
 * only the [LlmCallLog] and names the raw provider in exactly one place, an
 * unlogged provider call is hard to write.
 *
 * Two entry points, one for each call shape:
 * - [record] — accumulation (extraction / synthesis / fit-lens): inserts the
 *   request, invokes `provider.chat`, writes the terminal response row, and
 *   returns the id + classified terminal. The caller writes its own domain/run
 *   row afterward in its own transaction.
 * - [recordStreaming] — streaming (chat): inserts the request eagerly (id known
 *   up front), returns a cold `events` flow that relays every provider event to
 *   the collector and, on the terminal, writes the response row.
 *
 * Both terminate through one shared [mapOutcome] + [rowFor], so outcome semantics
 * live in exactly one place. The guarantee: every [llm_requests] row this opens
 * gets exactly one matching `llm_responses` row before returning or propagating
 * — including `rejected` / `transient_failure` terminals, an exception escaping
 * the flow (recorded as `internal_error`, distinct from a provider-reported
 * `transient_failure`), and cancellation (written under [NonCancellable]).
 *
 * [nanoTime] is injectable for deterministic latency tests; it defaults to the
 * codebase's monotonic-clock idiom `System::nanoTime`.
 */
class LlmCallLog(
  private val provider: ChatProvider,
  private val database: Database,
  private val priceBook: LlmPriceBook = LlmPriceBook.EMPTY,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  companion object {
    private val log = org.slf4j.LoggerFactory.getLogger(LlmCallLog::class.java)

    /** The canonical reason recorded for a cancellation (no ChatEvent terminal carries one). */
    const val CANCELLED_REASON = "client disconnected before terminal"

    /**
     * The `llm_responses` UNIQUE(request_id) constraint name (migration 0038).
     * [writeCancelledIfAbsent] swallows ONLY this collision — the intended 1:1
     * idempotency race — and rethrows any other constraint violation (e.g. a
     * `23514` CHECK, a genuine integrity defect).
     */
    const val RESPONSE_REQUEST_ID_UNIQUE = "llm_responses_request_id_key"
  }

  val providerId: String get() = provider.id

  /**
   * Accumulation path. Inserts the [request] as an `llm_requests` row, times
   * `provider.chat(request)`, classifies the terminal (or a caught exception, as
   * `internal_error`), writes the `llm_responses` (+ raw) row, and returns the id
   * plus the terminal. On an exception escaping the flow (a port-contract defect)
   * the `internal_error` row is written and the exception is rethrown so the
   * caller's existing `catch` runs.
   */
  suspend fun record(request: ChatRequest): LoggedCall {
    val requestId = appendRequest(request)
    val start = nanoTime()
    val terminal: ChatEvent.Terminal
    try {
      terminal = chat(request)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
      withContext(NonCancellable) {
        writeResponse(requestId, cancelledOutcome(), usage = null, providerRequestId = null, rawPayload = null, start = start)
      }
      throw cancellation
    } catch (defect: Exception) {
      writeResponse(requestId, internalErrorOutcome(defect), usage = null, providerRequestId = null, rawPayload = null, start = start)
      throw defect
    }
    writeResponse(
      requestId,
      mapOutcome(terminal),
      usage = usageOf(terminal),
      providerRequestId = providerRequestIdOf(terminal),
      rawPayload = rawPayloadOf(terminal),
      start = start,
    )
    return LoggedCall(requestId, terminal)
  }

  /**
   * Streaming path. Inserts the [request] eagerly so the id is known before the
   * first event, then returns a cold [StreamingCall] whose `events` flow, on
   * collection, relays every provider event to the collector and, on the
   * terminal (or on cancellation / an escaping exception), writes the
   * `llm_responses` (+ raw) row. The id lets the caller stamp its `convo_requests`
   * row before collecting, exactly as today.
   */
  suspend fun recordStreaming(request: ChatRequest): StreamingCall {
    val requestId = appendRequest(request)
    val events =
      flow {
        val start = nanoTime()
        var terminal: ChatEvent.Terminal? = null
        try {
          provider.stream(request).collect { event ->
            if (event is ChatEvent.Terminal) terminal = event
            emit(event)
          }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
          withContext(NonCancellable) {
            writeResponse(requestId, cancelledOutcome(), usage = null, providerRequestId = null, rawPayload = null, start = start)
          }
          throw cancellation
        } catch (defect: Exception) {
          writeResponse(requestId, internalErrorOutcome(defect), usage = null, providerRequestId = null, rawPayload = null, start = start)
          throw defect
        }
        val last =
          terminal
            ?: throw IllegalStateException("chat provider [${provider.id}] stream completed without a terminal event")
        writeResponse(
          requestId,
          mapOutcome(last),
          usage = usageOf(last),
          providerRequestId = providerRequestIdOf(last),
          rawPayload = rawPayloadOf(last),
          start = start,
        )
      }
    return StreamingCall(requestId, events)
  }

  /**
   * Writes exactly one `cancelled` `llm_responses` row for [requestId] — unless
   * one already exists — closing the mid-tool-loop cancellation gap (RFC 106).
   *
   * [recordStreaming] commits its `llm_requests` row eagerly but writes the
   * response only when the returned cold `events` flow is collected. On the chat
   * tool loop, a continuation is opened (row committed) one iteration before its
   * events are collected; if the client disconnects in that gap the cold flow
   * never runs, so no response row is written for that request. [CoachingService]
   * calls this from its cancellation handler to write the missing row.
   *
   * The provider call never ran, so the row carries `latencyMs = 0`, null usage,
   * null provider-request-id, and no raw payload. The entire DB write runs under
   * [NonCancellable] so it completes even though the calling coroutine is already
   * cancelled. It is idempotent: if the collected flow (or a prior call) already
   * wrote the response, the insert hits the `llm_responses`
   * [RESPONSE_REQUEST_ID_UNIQUE] 1:1 constraint, surfaced by
   * [LlmCallsDao.appendResponse] as a [ConstraintViolationException] naming that
   * constraint — swallowed here as a no-op. Any other failure — including a
   * different constraint violation such as a `23514` CHECK integrity defect —
   * propagates.
   */
  suspend fun writeCancelledIfAbsent(requestId: LlmRequestId) {
    writeTerminalIfAbsent(requestId, cancelledOutcome())
  }

  /**
   * Writes exactly one `internal_error` `llm_responses` row for [requestId] —
   * unless one already exists — recording a defect that interrupted a call after
   * its `llm_requests` row committed but before its response was written (RFC 106).
   *
   * The sibling of [writeCancelledIfAbsent] for the non-cancellation case:
   * [CoachingService] commits a `convo_requests` extension row in a transaction of
   * its own after [recordStreaming] commits the `llm_requests` row; a defect in
   * that transaction (e.g. a transient DB error on the `convo_requests` INSERT)
   * leaves the just-committed `llm_requests` row with no response, since its cold
   * flow will never be collected. [CoachingService] calls this from its defect
   * handler, stamping [internalErrorOutcome] so the orphan is closed with the same
   * outcome [recordStreaming]'s own in-flow catch would have written.
   *
   * The provider call never ran, so the row carries `latencyMs = 0`, null usage,
   * null provider-request-id, and no raw payload — identical to
   * [writeCancelledIfAbsent] but for the stamped outcome. It is idempotent on the
   * same [RESPONSE_REQUEST_ID_UNIQUE] 1:1 constraint; any other failure propagates.
   */
  suspend fun writeInternalErrorIfAbsent(
    requestId: LlmRequestId,
    cause: Throwable,
  ) {
    writeTerminalIfAbsent(requestId, internalErrorOutcome(cause))
  }

  /**
   * Writes one terminal `llm_responses` row carrying [outcome] for [requestId],
   * idempotently — the shared body of [writeCancelledIfAbsent] and
   * [writeInternalErrorIfAbsent]. The provider call never ran (both callers repair
   * an [llm_requests] row whose cold flow will never be collected), so the row
   * carries `latencyMs = 0`, null usage, null provider-request-id, and no raw
   * payload. The entire DB write runs under [NonCancellable] so it completes even
   * when the calling coroutine is already cancelled. On the [llm_responses]
   * [RESPONSE_REQUEST_ID_UNIQUE] 1:1 collision — the response was already written
   * by the collected flow or a prior call — the insert is a no-op; any other
   * failure (notably a `23514` CHECK integrity defect mapped to the same exception
   * type) propagates.
   */
  private suspend fun writeTerminalIfAbsent(
    requestId: LlmRequestId,
    outcome: LlmCallOutcome,
  ) {
    withContext(NonCancellable) {
      database.withConnection { session ->
        LlmCallsDao
          .appendResponse(
            session,
            NewLlmResponse(
              requestId = requestId,
              outcome = outcome,
              providerRequestId = null,
              inputTokens = null,
              outputTokens = null,
              cacheReadTokens = null,
              cacheWriteTokens = null,
              // The provider call never ran (both callers repair an opener whose cold
              // flow will never be collected), so nothing was billed: cost 0 is a true,
              // exactly-measured statement, and it keeps routine mid-tool-loop
              // disconnects out of the uncostedCalls signal (RFC 108).
              cost = FrozenCost(nanodollars = Nanodollars.of(0), estimated = false),
              // latency_ms = 0 is the true provider streaming duration for a call
              // whose stream was never collected (the provider call never ran); the
              // outcome disambiguates this from a genuinely instant completed call.
              latencyMs = 0,
            ),
            rawPayload = null,
          ).onFailure { failure ->
            // Swallow ONLY the 1:1 idempotency race (the response was already
            // written by the collected flow or a prior call). Any other failure —
            // notably a 23514 CHECK violation mapped to the same exception type —
            // is a real integrity defect and must propagate.
            val isIdempotentRace =
              failure is ConstraintViolationException &&
                failure.constraint == RESPONSE_REQUEST_ID_UNIQUE
            if (!isIdempotentRace) throw failure
          }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Provider invocation
  // ---------------------------------------------------------------------------

  /** Accumulates the provider stream into its terminal (the `ChatProvider.chat` extension, inlined to keep the try/catch here). */
  private suspend fun chat(request: ChatRequest): ChatEvent.Terminal {
    var last: ChatEvent? = null
    provider.stream(request).collect { event -> last = event }
    return when (val terminal = last) {
      is ChatEvent.Terminal -> terminal
      null -> throw IllegalStateException("chat provider [${provider.id}] stream completed without emitting any event")
      else -> throw IllegalStateException("chat provider [${provider.id}] stream ended on a non-terminal event [$terminal]")
    }
  }

  // ---------------------------------------------------------------------------
  // Log writes
  // ---------------------------------------------------------------------------

  private suspend fun appendRequest(request: ChatRequest): LlmRequestId =
    database.withConnection { session ->
      LlmCallsDao.appendRequest(session, newRequest(request)).getOrThrow().id
    }

  /**
   * Writes the classified [outcome] as one `llm_responses` (+ optional raw) row
   * for [requestId], stamping the elapsed latency from [start]. The single write
   * point both entry points and every terminal arm funnel through, so exactly one
   * response row is written per opened request.
   */
  private suspend fun writeResponse(
    requestId: LlmRequestId,
    outcome: LlmCallOutcome,
    usage: TokenUsage?,
    providerRequestId: String?,
    rawPayload: JsonElement?,
    start: Long,
  ) {
    val latencyMs = ((nanoTime() - start) / 1_000_000).toInt()
    val frozenCost = costOf(outcome, usage)
    warnIfEstimatedDefault(outcome, frozenCost)
    database.withConnection { session ->
      LlmCallsDao
        .appendResponse(
          session,
          NewLlmResponse(
            requestId = requestId,
            outcome = outcome,
            providerRequestId = providerRequestId,
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            cacheReadTokens = usage?.cacheReadTokens,
            cacheWriteTokens = usage?.cacheWriteTokens,
            cost = frozenCost,
            latencyMs = latencyMs,
          ),
          rawPayload = rawPayload,
        ).getOrThrow()
    }
  }

  /**
   * Freezes this call's dollar cost from the [priceBook] in effect now (RFC 108),
   * or `null` when it cannot be computed. A cost exists only on the `Completed`
   * arm — the sole arm carrying both a resolved model and (possibly) [usage];
   * every failure/cancellation/defect arm reaches here with `usage = null` and
   * no resolved model, so its cost freezes `NULL`. On a `Completed` whose
   * `usage` is present, [LlmPriceBook.costOf] still returns `null` when a base
   * token count is unreported (an unquantifiable billed call → `uncostedCalls`).
   * Pure: no side effects. See [warnIfEstimatedDefault] for the estimated-model
   * observability signal.
   */
  private fun costOf(
    outcome: LlmCallOutcome,
    usage: TokenUsage?,
  ): FrozenCost? =
    when (outcome) {
      is LlmCallOutcome.Completed -> usage?.let { priceBook.costOf(outcome.modelResolved, it) }
      is LlmCallOutcome.Failed -> null
    }

  /**
   * WARNs once, naming the unrecognized model, when [cost] was priced at the
   * price book's default rate — the sole detection path for a provider-side
   * rename that would otherwise silently over-charge every student's meter.
   */
  private fun warnIfEstimatedDefault(
    outcome: LlmCallOutcome,
    cost: FrozenCost?,
  ) {
    if (cost?.estimated == true && outcome is LlmCallOutcome.Completed) {
      log.warn(
        "Priced LLM call at the default rate: resolved model [{}] is absent from the price book (cost is an estimate).",
        outcome.modelResolved,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Classification (the sole producer of the failure reason)
  // ---------------------------------------------------------------------------

  /**
   * Maps a provider [ChatEvent.Terminal] to its persisted [LlmCallOutcome].
   * The sole producer of a completed/failed distinction from a terminal;
   * cancellation and internal-error outcomes are produced by [cancelledOutcome] /
   * [internalErrorOutcome] (no terminal exists to carry them). The failure
   * `reason` is synthesized from a single documented source per arm: `Rejected` /
   * `TransientFailure` carry the terminal's own `reason`.
   */
  private fun mapOutcome(terminal: ChatEvent.Terminal): LlmCallOutcome =
    when (terminal) {
      is ChatEvent.Completed -> {
        LlmCallOutcome.Completed(
          content = terminal.response.content,
          modelResolved = terminal.response.modelResolved,
          stopReason = terminal.response.stopReason,
        )
      }

      is ChatEvent.Rejected -> {
        LlmCallOutcome.Failed(LlmFailureKind.REJECTED, terminal.reason)
      }

      is ChatEvent.TransientFailure -> {
        LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, terminal.reason)
      }
    }

  private fun cancelledOutcome(): LlmCallOutcome = LlmCallOutcome.Failed(LlmFailureKind.CANCELLED, CANCELLED_REASON)

  private fun internalErrorOutcome(e: Throwable): LlmCallOutcome =
    LlmCallOutcome.Failed(LlmFailureKind.INTERNAL_ERROR, "${e::class.simpleName}: ${e.message}")

  private fun usageOf(terminal: ChatEvent.Terminal): TokenUsage? =
    when (terminal) {
      is ChatEvent.Completed -> terminal.response.usage
      is ChatEvent.Rejected -> null
      is ChatEvent.TransientFailure -> null
    }

  private fun providerRequestIdOf(terminal: ChatEvent.Terminal): String? =
    when (terminal) {
      is ChatEvent.Completed -> terminal.response.providerRequestId
      is ChatEvent.Rejected -> terminal.providerRequestId
      is ChatEvent.TransientFailure -> terminal.providerRequestId
    }

  private fun rawPayloadOf(terminal: ChatEvent.Terminal): JsonElement? =
    when (terminal) {
      is ChatEvent.Completed -> terminal.rawPayload
      is ChatEvent.Rejected -> terminal.rawPayload
      is ChatEvent.TransientFailure -> terminal.rawPayload
    }

  // ---------------------------------------------------------------------------
  // Request → row mapping
  // ---------------------------------------------------------------------------

  /**
   * Serializes a [ChatRequest] to its `llm_requests` row shape. [messages] is
   * serialized through [ChatMessage.serializeChatMessages] — the single shared
   * definition `AnthropicChatProvider.requestBody` also uses — so the logged
   * `content` array is byte-identical to the sent `messages` array; [tools]
   * becomes a `JsonArray` (null when empty). [TokenUsage] never enters `:db` —
   * only the four ints do, on the response.
   */
  private fun newRequest(request: ChatRequest): NewLlmRequest =
    NewLlmRequest(
      provider = provider.id,
      modelRequested = request.model,
      system = request.system,
      content = ChatMessage.serializeChatMessages(request.messages),
      maxTokens = request.maxTokens,
      tools = if (request.tools.isEmpty()) null else JsonArray(request.tools),
      toolChoice = request.toolChoice,
      params = request.params,
    )
}

/**
 * The result of a completed [LlmCallLog.record] call: the log-owned request id
 * (for the caller's domain/run row) and the classified provider [terminal] (read
 * exactly as the caller read the old terminal).
 */
data class LoggedCall(
  val llmRequestId: LlmRequestId,
  val terminal: ChatEvent.Terminal,
)

/**
 * The result of an [LlmCallLog.recordStreaming] call: the log-owned request id
 * (known before the first event, so the caller stamps its `convo_requests` row
 * before collecting) and the cold [events] flow that relays provider events and
 * writes the response row on the terminal.
 */
data class StreamingCall(
  val llmRequestId: LlmRequestId,
  val events: Flow<ChatEvent>,
)
