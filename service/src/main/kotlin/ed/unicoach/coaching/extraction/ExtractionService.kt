package ed.unicoach.coaching.extraction

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.coaching.ConvoContent
import ed.unicoach.coaching.ConvoProjection
import ed.unicoach.coaching.ForcedToolInput
import ed.unicoach.coaching.JsonParseFailure
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.ToolSchema
import ed.unicoach.coaching.category
import ed.unicoach.coaching.forcedToolChoice
import ed.unicoach.coaching.readForcedTool
import ed.unicoach.coaching.toDisplay
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdvisoryLockDao
import ed.unicoach.db.dao.ClaimSupportDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimRevision
import ed.unicoach.db.models.ClaimStatus
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The per-conversation extraction pass (RFC 66): distills a window of finished
 * coaching turns into immutable observations and revisable claims. Runs only in
 * the queue worker; never on the chat request path.
 *
 * The pass is three phases — a read transaction, the LLM call **outside any
 * transaction**, then a write transaction — so the multi-second provider call
 * never pins a pooled connection or holds the student advisory lock. Each
 * transaction takes the student lock; the lock is released between phases and
 * re-acquired for the write. The conversation watermark (highest applied
 * `through_request_id`) makes at-least-once delivery idempotent for the
 * conversation's stream; the student lock serializes shared claim writes across
 * concurrent same-student passes.
 */
open class ExtractionService(
  private val database: Database,
  private val llmCallLog: LlmCallLog,
  private val config: ExtractionConfig,
) {
  private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

  /**
   * Runs one extraction pass over [convoId] up to and including [throughRequestId].
   * Returns a sealed [ExtractionResult] the handler maps to a `JobResult`.
   */
  open suspend fun extract(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
  ): ExtractionResult {
    val readout =
      try {
        readPhase(convoId, throughRequestId)
      } catch (e: Exception) {
        logger.warn("extraction read phase failed for convo=[{}]", convoId.asString, e)
        return ExtractionResult.TransientFailure("read phase: ${e.message}", e)
      }

    return when (readout) {
      is ReadPhase.NoOp -> ExtractionResult.Success

      // The window carries the effective through-request id: when the safety cap
      // trimmed the range, this is the last distilled turn, not the requested
      // target, so the watermark advances only over what was distilled.
      is ReadPhase.Window -> runLlmAndWrite(convoId, readout.throughRequestId, readout)
    }
  }

  // ---------------------------------------------------------------------------
  // Read phase
  // ---------------------------------------------------------------------------

  private suspend fun readPhase(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
  ): ReadPhase =
    database.withConnection { session ->
      val convo = ConvosDao.findById(session, convoId, SoftDeleteScope.ALL).getOrThrow()
      // Soft-deleted conversation: skip as a no-op success (no run appended).
      if (convo.deletedAt != null) return@withConnection ReadPhase.NoOp

      val studentId = convo.studentId
      AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

      val watermark = ExtractionRunsDao.watermark(session, convoId).getOrThrow()
      if (throughRequestId.value <= watermark) return@withConnection ReadPhase.NoOp

      val allTurns = ConvosDao.listTurns(session, convoId, SoftDeleteScope.ALL).getOrThrow()
      // Window on WHOLE logical turns (turn_id groups), never raw rows: a tool-use
      // excursion is many rows, so a per-row cap landing inside an open excursion
      // would drop the still-open exchange AND advance the watermark past its
      // opener, orphaning the excursion forever (F1). Grouping by turn_id and
      // capping on distinct turns makes that split structurally impossible.
      //
      // A logical turn is in-window only if ALL its rows fall in
      // (watermark, throughRequestId] — so a turn straddling either boundary is
      // wholly deferred, never half-included. When the contracted range exceeds
      // the safety cap, keep the OLDEST windowMaxTurns whole turns and advance the
      // watermark only to the last kept turn — the remaining newer turns are
      // picked up by a later pass. Keeping the newest N instead would strand the
      // older turns forever (the watermark would jump past turns never distilled).
      val keptTurns = wholeTurnsInWindow(allTurns, watermark, throughRequestId.value).take(config.windowMaxTurns)

      // No whole turn in range (e.g. all soft-deleted out of listTurns, or only a
      // straddling open excursion): nothing to distill this pass.
      if (keptTurns.isEmpty()) return@withConnection ReadPhase.NoOp

      val windowTurns = keptTurns.flatMap { it.rows }

      // The effective target is the last kept whole turn's final-answer row — its
      // highest request id. Because the cap trims to whole turns, the watermark
      // always lands on a complete turn's last row, so the next pass's
      // `request.id > watermark` filter excludes that whole turn (never a
      // mid-excursion continuation).
      val effectiveThrough = ConvoRequestId(keptTurns.last().rows.maxOf { it.request.id.value })

      val activeClaims = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
      val prompt =
        SystemPromptsDao
          .findByNameAndVersion(session, config.promptName, config.promptVersion)
          .getOrThrow()

      ReadPhase.Window(
        studentId = studentId,
        watermark = watermark,
        throughRequestId = effectiveThrough,
        turns = windowTurns,
        activeClaims = activeClaims,
        prompt = prompt,
      )
    }

  /**
   * Groups [allTurns] by `turn_id` (via the shared [ConvoProjection.groupByTurnId]
   * owner, preserving creation order) and returns the logical turns eligible to
   * advance the window — those that are BOTH:
   *
   *  - **id-contained**: every row falls in `(watermark, throughRequestId]`, so a
   *    turn straddling either boundary (some rows in, some out) is excluded
   *    entirely and never half-distilled; and
   *  - **closed**: the group's highest-id row bears a final answer — a response
   *    with `content != null` and `stop_reason != 'tool_use'`. An in-range but
   *    still-open excursion (its tail row is a `tool_use` call, a failed close, or
   *    has no response yet) is deferred, not counted toward the cap.
   *
   * The closure test makes the whole-turn watermark boundary a structural property
   * of this function, not merely a promise of the enqueue contract: even if a
   * caller targets a request id sitting on an open excursion's opener, that
   * excursion is withheld and the watermark cannot advance into it (the invariant
   * "the watermark MUST NOT advance into an open excursion").
   */
  private fun wholeTurnsInWindow(
    allTurns: List<ConvoTurn>,
    watermark: Long,
    throughRequestId: Long,
  ): List<ConvoTurnGroup> =
    ConvoProjection
      .groupByTurnId(allTurns)
      .values
      .map { ConvoTurnGroup(it) }
      .filter { group ->
        group.rows.all { it.request.id.value > watermark && it.request.id.value <= throughRequestId } && group.isClosed
      }

  /** One logical turn: all `convo_requests` rows sharing a `turn_id`, in id order. */
  private class ConvoTurnGroup(
    val rows: List<ConvoTurn>,
  ) {
    /**
     * True when this turn's final answer is written: its highest-id row carries a
     * response with `content != null` and `stop_reason != 'tool_use'`. A tail row
     * that is a mid-excursion `tool_use` call, a failed (null-content) close, or
     * has no response yet leaves the turn open — its later rows may not exist yet,
     * so advancing the watermark over it would strand them.
     */
    val isClosed: Boolean
      get() {
        val tail = rows.maxByOrNull { it.request.id.value }?.call?.response ?: return false
        // The turn is closed only on a completed, non-tool_use terminal; a
        // tool_use call is mid-excursion, a failed/cancelled terminal (no
        // Completed outcome) is not a final answer.
        val outcome = tail.outcome as? ed.unicoach.db.models.LlmCallOutcome.Completed ?: return false
        return outcome.stopReason != ConvoProjection.TOOL_USE_STOP_REASON
      }
  }

  // ---------------------------------------------------------------------------
  // LLM call (no transaction) + write phase
  // ---------------------------------------------------------------------------

  private suspend fun runLlmAndWrite(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
    window: ReadPhase.Window,
  ): ExtractionResult {
    val request =
      ChatRequest(
        model = config.model,
        system = window.prompt.body,
        messages = buildPromptMessages(window),
        maxTokens = config.maxTokens,
        tools = listOf(RECORD_EXTRACTION_TOOL),
        toolChoice = forcedToolChoice(RECORD_EXTRACTION_TOOL_NAME),
      )

    // LlmCallLog logs the request/response/raw and returns the classified terminal
    // plus the log-owned llm_request_id the run row references (RFC 106). Provider
    // trouble surfaces as a Rejected/TransientFailure terminal; a defect throwing
    // from the flow is recorded as internal_error and rethrown to this catch.
    val loggedCall =
      try {
        llmCallLog.record(request)
      } catch (e: Exception) {
        logger.warn("extraction provider call failed for convo=[{}]", convoId.asString, e)
        return ExtractionResult.TransientFailure("provider call: ${e.message}", e)
      }
    val llmRequestId = loggedCall.llmRequestId

    return when (val terminal = loggedCall.terminal) {
      // No billed, usable call: nothing to account, no run row.
      is ChatEvent.Rejected -> {
        ExtractionResult.TransientFailure("provider rejected: ${terminal.reason}")
      }

      is ChatEvent.TransientFailure -> {
        ExtractionResult.TransientFailure("provider transient: ${terminal.reason}")
      }

      is ChatEvent.Completed -> {
        // A Completed call is billed regardless of what the tool input contains.
        // The forced tool's input object is the payload; a missing tool_use block
        // is the tier-A analogue of an unparseable envelope.
        val parseResult =
          when (val forced = readForcedTool(terminal.response, RECORD_EXTRACTION_TOOL_NAME)) {
            is ForcedToolInput.Absent -> ParseResult.Failure(JsonParseFailure.NoToolUse(forced.stopReason, forced.excerpt))
            is ForcedToolInput.Present -> parseOutput(forced.input)
          }
        when (parseResult) {
          is ParseResult.Failure -> {
            logger.warn(
              "unusable extraction output for convo=[{}]: [{}]",
              convoId.asString,
              parseResult.failure.toDisplay(),
            )
            writeFailedRun(convoId, throughRequestId, window, parseResult.failure, llmRequestId)
            ExtractionResult.TransientFailure("unusable extraction output: ${parseResult.failure.toDisplay()}")
          }

          is ParseResult.Parsed -> {
            writePhase(convoId, throughRequestId, window, parseResult.output, llmRequestId)
          }
        }
      }
    }
  }

  /**
   * Writes a `failed` extraction_run carrying the call's token usage (the spend
   * is recorded) and returns; the watermark does not advance.
   *
   * Re-reads the watermark under the held write lock — mirroring [writePhase] —
   * so a coalesced same-conversation pass that already advanced the watermark to
   * `>= throughRequestId` writes no duplicate `failed` row (the single-row
   * invariant: a `Completed` call writes exactly one `extraction_runs` row).
   */
  private suspend fun writeFailedRun(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
    window: ReadPhase.Window,
    failure: JsonParseFailure,
    llmRequestId: LlmRequestId,
  ) {
    database.withConnection { session ->
      AdvisoryLockDao.lockStudent(session, window.studentId).getOrThrow()
      val watermark = ExtractionRunsDao.watermark(session, convoId).getOrThrow()
      if (throughRequestId.value <= watermark) return@withConnection
      ExtractionRunsDao
        .append(
          session,
          NewExtractionRun(
            convoId = convoId,
            studentId = window.studentId,
            throughRequestId = throughRequestId,
            outcome = ExtractionOutcome.Failed(failure.category, failure.toDisplay()),
            systemPromptId = window.prompt.id,
            llmRequestId = llmRequestId,
          ),
        ).getOrThrow()
    }
  }

  private suspend fun writePhase(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
    window: ReadPhase.Window,
    parsed: ParsedOutput,
    llmRequestId: LlmRequestId,
  ): ExtractionResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, window.studentId).getOrThrow()

        // Re-read the watermark under the write lock: a concurrent same-conversation
        // pass may have advanced it past our target — lost-race no-op.
        val watermark = ExtractionRunsDao.watermark(session, convoId).getOrThrow()
        if (throughRequestId.value <= watermark) {
          return@withConnection ExtractionResult.Success
        }

        // Re-load the active claim set under the held lock: the read-txn snapshot
        // is stale; supersede/reinforce ops validate against this fresh set.
        val freshActive =
          ClaimsDao
            .listActiveByStudent(session, window.studentId)
            .getOrThrow()
            .associateBy { it.id }

        applyWrites(session, convoId, throughRequestId, window, parsed, freshActive, llmRequestId)
      }
    } catch (e: StaleSupersedeTargetException) {
      logger.warn(
        "extraction supersede/reinforce targets a non-active claim for convo=[{}]",
        convoId.asString,
        e,
      )
      ExtractionResult.TransientFailure("stale claim target: ${e.message}", e)
    } catch (e: Exception) {
      logger.warn("extraction write phase failed for convo=[{}]", convoId.asString, e)
      ExtractionResult.TransientFailure("write phase: ${e.message}", e)
    }

  /**
   * Inserts observations, applies claim ops, links support, recomputes
   * confidence for every touched claim under the held student lock, and appends
   * the `applied` run advancing the watermark. Throws
   * [StaleSupersedeTargetException] when an op's target is no longer active.
   */
  private fun applyWrites(
    session: SqlSession,
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
    window: ReadPhase.Window,
    parsed: ParsedOutput,
    freshActive: Map<ClaimId, Claim>,
    llmRequestId: LlmRequestId,
  ): ExtractionResult {
    val validSourceIds = window.turns.map { it.request.id.value }.toSet()

    // 1. Insert observations; index → inserted Observation.
    val insertedObservations: List<Observation> =
      parsed.observations.map { spec ->
        if (spec.sourceRequestId !in validSourceIds) {
          throw IllegalStateException("observation cites sourceRequestId ${spec.sourceRequestId} outside the window")
        }
        val turn = window.turns.first { it.request.id.value == spec.sourceRequestId }
        ObservationsDao
          .append(
            session,
            NewObservation(
              studentId = window.studentId,
              convoId = convoId,
              sourceRequestId = ConvoRequestId(spec.sourceRequestId),
              utteredAt = turn.request.createdAt,
              quote = spec.quote,
            ),
          ).getOrThrow()
      }

    // 2. Apply claim ops. Track touched claims for the confidence recompute.
    val touchedClaimIds = mutableSetOf<ClaimId>()
    var claimsWritten = 0
    var claimsSuperseded = 0

    for (op in parsed.claims) {
      when (op.op) {
        ClaimOp.NEW -> {
          val created = ClaimsDao.create(session, op.toNewClaim(window.studentId)).getOrThrow()
          linkSupports(session, created.id, op.supports, insertedObservations)
          touchedClaimIds.add(created.id)
          claimsWritten++
        }

        ClaimOp.REINFORCE -> {
          val target = resolveActiveTarget(op, freshActive)
          linkSupports(session, target.id, op.supports, insertedObservations)
          touchedClaimIds.add(target.id)
        }

        ClaimOp.SUPERSEDE -> {
          val target = resolveActiveTarget(op, freshActive)
          val replacement = ClaimsDao.create(session, op.toNewClaim(window.studentId)).getOrThrow()
          linkSupports(session, replacement.id, op.supports, insertedObservations)
          ClaimsDao
            .revise(
              session,
              target.id,
              ClaimRevision(ClaimStatus.SUPERSEDED, target.confidence, replacement.id),
            ).getOrThrow()
          touchedClaimIds.add(replacement.id)
          claimsWritten++
          claimsSuperseded++
        }
      }
    }

    // 3. Recompute confidence for every touched (active) claim from its support set.
    val now = Instant.now()
    for (claimId in touchedClaimIds) {
      val support = ClaimSupportDao.listObservationsForClaim(session, claimId).getOrThrow()
      val confidence = computeConfidence(support, now)
      val claim = ClaimsDao.findById(session, claimId).getOrThrow()
      // Only active claims carry a live confidence; a superseded replacement is active.
      if (claim.status == ClaimStatus.ACTIVE) {
        ClaimsDao.revise(session, claimId, ClaimRevision(ClaimStatus.ACTIVE, confidence)).getOrThrow()
      }
    }

    // 4. Append the applied run advancing the watermark.
    ExtractionRunsDao
      .append(
        session,
        NewExtractionRun(
          convoId = convoId,
          studentId = window.studentId,
          throughRequestId = throughRequestId,
          outcome =
            ExtractionOutcome.Applied(
              observationsWritten = insertedObservations.size,
              claimsWritten = claimsWritten,
              claimsSuperseded = claimsSuperseded,
            ),
          systemPromptId = window.prompt.id,
          llmRequestId = llmRequestId,
        ),
      ).getOrThrow()

    return ExtractionResult.Success
  }

  private fun resolveActiveTarget(
    op: ClaimOpSpec,
    freshActive: Map<ClaimId, Claim>,
  ): Claim {
    val targetId =
      op.targetClaimId
        ?: throw StaleSupersedeTargetException("${op.op} op missing targetClaimId")
    return freshActive[targetId]
      ?: throw StaleSupersedeTargetException("targetClaimId ${targetId.asString} is not in the active set")
  }

  private fun linkSupports(
    session: SqlSession,
    claimId: ClaimId,
    supports: List<Int>,
    observations: List<Observation>,
  ) {
    for (index in supports) {
      val obs =
        observations.getOrNull(index)
          ?: throw IllegalStateException("support index $index out of range (${observations.size} observations)")
      ClaimSupportDao.link(session, claimId, obs.id).getOrThrow()
    }
  }

  /**
   * `confidence = round(1000 * (1 - exp(-Σ w_i)))`, each supporting observation
   * contributing `w_i = 0.5 ^ (age_days(uttered_at) / confidenceHalfLifeDays)`.
   * No support → 0.
   */
  private fun computeConfidence(
    support: List<Observation>,
    now: Instant,
  ): Int {
    if (support.isEmpty()) return 0
    val weightSum =
      support.sumOf { obs ->
        val ageDays = (now.epochSecond - obs.utteredAt.epochSecond).coerceAtLeast(0L) / 86_400.0
        0.5.pow(ageDays / config.confidenceHalfLifeDays)
      }
    return (1000.0 * (1.0 - exp(-weightSum))).roundToInt().coerceIn(0, 1000)
  }

  // ---------------------------------------------------------------------------
  // Prompt assembly
  // ---------------------------------------------------------------------------

  private fun buildPromptMessages(window: ReadPhase.Window): List<ChatMessage> {
    val transcript =
      buildString {
        appendLine("# Current active claims")
        if (window.activeClaims.isEmpty()) {
          appendLine("(none)")
        } else {
          for (claim in window.activeClaims) {
            appendLine(
              "- id=${claim.id.asString} kind=${claim.kind.value} subject=${claim.subject.value} topic=${claim.topic.value}: ${claim.statement}",
            )
          }
        }
        appendLine()
        appendLine("# Transcript window")
        // Collapse any tool excursion to its user text and final coach answer:
        // the synthetic tool_result requests and tool_use responses inside the
        // window contribute nothing to the distilled transcript.
        for (exchange in ConvoProjection.visibleExchanges(window.turns)) {
          appendLine("[userTurn id=${exchange.userRequest.id.value}] ${ConvoContent.renderText(exchange.userContent)}")
          appendLine("[coach] ${ConvoContent.renderText(exchange.finalContent)}")
        }
      }
    return listOf(ChatMessage.text(ChatRole.USER, transcript))
  }

  // ---------------------------------------------------------------------------
  // Output parsing
  // ---------------------------------------------------------------------------

  /**
   * Extracts the observations/claims from the forced tool's `tool_use.input`
   * object via the JSON element DSL (the `service` module has no
   * kotlinx-serialization compiler plugin, so generated `@Serializable`
   * serializers are unavailable here). The object always arrives structured
   * (forced tool use, RFC 104) — this is only the per-field enforcement point.
   * Returns a [ParseResult.Failure] naming the offending field/value on any
   * missing, wrong-shape, or enum-membership failure, so the caller can record
   * what was wrong rather than a bare "unusable".
   */
  private fun parseOutput(root: JsonObject): ParseResult {
    val observations = mutableListOf<ObservationSpec>()
    val observationsArray =
      when (val element = root["observations"]) {
        null -> JsonArray(emptyList())

        is JsonArray -> element

        // Present but not an array: a structural failure, NOT an empty result. A
        // lenient `as? JsonArray ?: emptyList` would misclassify malformed output
        // as a valid zero-observation APPLIED run.
        else -> return ParseResult.Failure(JsonParseFailure.BadField("observations", "not an array"))
      }
    for (element in observationsArray) {
      val obj = element as? JsonObject ?: return ParseResult.Failure(JsonParseFailure.BadField("observations[]", "not an object"))
      val sourceRequestId =
        obj["sourceRequestId"].primitiveOrNull?.longOrNull
          ?: return ParseResult.Failure(JsonParseFailure.BadField("sourceRequestId", "missing or non-integer"))
      val quote =
        obj["quote"].primitiveOrNull?.takeIf { it.isString }?.content
          ?: return ParseResult.Failure(JsonParseFailure.BadField("quote", "missing or non-string"))
      if (quote.isBlank()) return ParseResult.Failure(JsonParseFailure.BadField("quote", "blank"))
      observations.add(ObservationSpec(sourceRequestId, quote))
    }

    val claims = mutableListOf<ClaimOpSpec>()
    val claimsArray =
      when (val element = root["claims"]) {
        null -> JsonArray(emptyList())

        is JsonArray -> element

        // Present but not an array: same misclassification hazard as observations.
        else -> return ParseResult.Failure(JsonParseFailure.BadField("claims", "not an array"))
      }
    for (element in claimsArray) {
      val obj = element as? JsonObject ?: return ParseResult.Failure(JsonParseFailure.BadField("claims[]", "not an object"))
      val op =
        obj["op"].primitiveOrNull?.contentOrNull?.let { ClaimOp.fromWire(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("op", obj["op"].primitiveOrNull?.contentOrNull ?: "missing"))
      val statement =
        obj["statement"].primitiveOrNull?.takeIf { it.isString }?.content
          ?: return ParseResult.Failure(JsonParseFailure.BadField("statement", "missing or non-string"))
      if (statement.isBlank()) return ParseResult.Failure(JsonParseFailure.BadField("statement", "blank"))
      val kind =
        obj["kind"].primitiveOrNull?.contentOrNull?.let { ClaimKind.fromValue(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("kind", obj["kind"].primitiveOrNull?.contentOrNull ?: "missing"))
      val subject =
        obj["subject"].primitiveOrNull?.contentOrNull?.let { ClaimSubject.fromValue(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("subject", obj["subject"].primitiveOrNull?.contentOrNull ?: "missing"))
      val topic =
        obj["topic"].primitiveOrNull?.contentOrNull?.let { ClaimTopic.fromValue(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("topic", obj["topic"].primitiveOrNull?.contentOrNull ?: "missing"))
      val origin =
        obj["origin"].primitiveOrNull?.contentOrNull?.let { ClaimOrigin.fromValue(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("origin", obj["origin"].primitiveOrNull?.contentOrNull ?: "missing"))
      val visibility =
        when (val rawVisibility = obj["visibility"].primitiveOrNull?.contentOrNull) {
          null -> {
            ClaimVisibility.STUDENT_VISIBLE
          }

          else -> {
            ClaimVisibility.fromValue(rawVisibility)
              ?: return ParseResult.Failure(JsonParseFailure.BadField("visibility", rawVisibility))
          }
        }
      val supports =
        when (val element = obj["supports"]) {
          null -> {
            emptyList()
          }

          is JsonArray -> {
            element.map { s ->
              s.primitiveOrNull?.intOrNull
                ?: return ParseResult.Failure(JsonParseFailure.BadField("supports[]", "non-integer"))
            }
          }

          // Present but not an array: fail rather than silently drop the support links.
          else -> {
            return ParseResult.Failure(JsonParseFailure.BadField("supports", "not an array"))
          }
        }
      val targetClaimId =
        when (val rawTarget = obj["targetClaimId"].primitiveOrNull?.contentOrNull) {
          null -> {
            null
          }

          else -> {
            runCatching { ClaimId(java.util.UUID.fromString(rawTarget)) }.getOrNull()
              ?: return ParseResult.Failure(JsonParseFailure.BadField("targetClaimId", rawTarget))
          }
        }
      if (op != ClaimOp.NEW && targetClaimId == null) {
        return ParseResult.Failure(JsonParseFailure.BadField("targetClaimId", "required for op=${op.name.lowercase()}"))
      }
      claims.add(ClaimOpSpec(op, statement, kind, subject, topic, origin, visibility, supports, targetClaimId))
    }

    return ParseResult.Parsed(ParsedOutput(observations, claims))
  }

  /**
   * Safe [JsonPrimitive] view of a (possibly absent) field: null when the field
   * is missing or holds a non-primitive (object/array). Keeps [parseOutput]
   * total — a non-primitive where a scalar is expected becomes a structured
   * [ParseResult.Failure] rather than the `IllegalArgumentException` that
   * `jsonPrimitive` throws, which would escape past [writeFailedRun] and drop the
   * billed-token ledger.
   */
  private val JsonElement?.primitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

  // ---------------------------------------------------------------------------
  // Internal carriers
  // ---------------------------------------------------------------------------

  private sealed interface ReadPhase {
    data object NoOp : ReadPhase

    data class Window(
      val studentId: StudentId,
      val watermark: Long,
      // The effective window target: the id of the last turn actually distilled.
      // Equals the requested throughRequestId unless the safety cap trimmed the
      // range, in which case the watermark advances only to here.
      val throughRequestId: ConvoRequestId,
      val turns: List<ConvoTurn>,
      val activeClaims: List<Claim>,
      val prompt: SystemPrompt,
    ) : ReadPhase
  }

  private class StaleSupersedeTargetException(
    message: String,
  ) : Exception(message)

  private data class ParsedOutput(
    val observations: List<ObservationSpec>,
    val claims: List<ClaimOpSpec>,
  )

  /** The outcome of [parseOutput]: a parsed document or a structured failure. */
  private sealed interface ParseResult {
    data class Parsed(
      val output: ParsedOutput,
    ) : ParseResult

    data class Failure(
      val failure: JsonParseFailure,
    ) : ParseResult
  }

  private data class ObservationSpec(
    val sourceRequestId: Long,
    val quote: String,
  )

  private enum class ClaimOp {
    NEW,
    REINFORCE,
    SUPERSEDE,
    ;

    companion object {
      fun fromWire(value: String): ClaimOp? =
        when (value) {
          "new" -> NEW
          "reinforce" -> REINFORCE
          "supersede" -> SUPERSEDE
          else -> null
        }
    }
  }

  private data class ClaimOpSpec(
    val op: ClaimOp,
    val statement: String,
    val kind: ClaimKind,
    val subject: ClaimSubject,
    val topic: ClaimTopic,
    val origin: ClaimOrigin,
    val visibility: ClaimVisibility,
    val supports: List<Int>,
    val targetClaimId: ClaimId?,
  ) {
    fun toNewClaim(studentId: StudentId): NewClaim =
      NewClaim(
        studentId = studentId,
        origin = origin,
        kind = kind,
        subject = subject,
        topic = topic,
        visibility = visibility,
        statement = statement,
      )
  }

  private companion object {
    const val RECORD_EXTRACTION_TOOL_NAME = "record_extraction"

    // Mirrors the fields parseOutput reads, with every enum enumerated to steer
    // the model. Guidance, not a hard validator (tier A) — parseOutput enforces.
    private val RECORD_EXTRACTION_TOOL: JsonObject =
      ToolSchema.tool(
        name = RECORD_EXTRACTION_TOOL_NAME,
        description =
          "Record the observations and claim operations distilled from the " +
            "supplied transcript window.",
        inputSchema =
          ToolSchema.objectSchema(
            "observations" to
              ToolSchema.arrayOf(
                ToolSchema.objectSchema(
                  "sourceRequestId" to ToolSchema.integer(),
                  "quote" to ToolSchema.string(),
                ),
              ),
            "claims" to
              ToolSchema.arrayOf(
                ToolSchema.objectSchema(
                  // `op` has no backing domain enum in scope — the three wire
                  // literals are defined by ClaimOp.fromWire in this file, so they
                  // stay literal here (a derivation would only re-hardcode them).
                  "op" to ToolSchema.enum("new", "reinforce", "supersede"),
                  "statement" to ToolSchema.string(),
                  // Every enumerated value is derived from its owner enum's wire
                  // `.value`, so a new/renamed enum member cannot silently drift
                  // the schema out of sync with what parseOutput accepts (matching
                  // SynthesisService's lens/disclosure derivation).
                  "kind" to ToolSchema.enum(*ClaimKind.entries.map { it.value }.toTypedArray()),
                  "subject" to ToolSchema.enum(*ClaimSubject.entries.map { it.value }.toTypedArray()),
                  "topic" to ToolSchema.enum(*ClaimTopic.entries.map { it.value }.toTypedArray()),
                  "origin" to ToolSchema.enum(*ClaimOrigin.entries.map { it.value }.toTypedArray()),
                  "visibility" to ToolSchema.enum(*ClaimVisibility.entries.map { it.value }.toTypedArray()),
                  "supports" to ToolSchema.arrayOf(ToolSchema.integer()),
                  "targetClaimId" to ToolSchema.string(),
                ),
              ),
          ),
      )
  }
}
