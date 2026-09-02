package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.coaching.ForcedToolInput
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.ToolSchema
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.coaching.forcedToolChoice
import ed.unicoach.coaching.readForcedTool
import ed.unicoach.college.Codebook
import ed.unicoach.college.CollegeQueryVocabulary
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdvisoryLockDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.latestUpdatedAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * The per-student fit-lens discovery pass (RFC 98): reaches into the college
 * dataset and proposes one real school the student has not mentioned, grounded in
 * their claims. A sibling of [ed.unicoach.coaching.synthesis.SynthesisService] —
 * it neither reads, writes, nor imports synthesis, and vice versa.
 *
 * [discover] is explicit two-call orchestration — **reason → retrieve → reason**:
 * a read transaction, LLM call #1 (formulate a [CollegeQuery]) outside any
 * transaction, retrieval in the worker, LLM call #2 (reason over the real
 * matches) outside any transaction, then a write transaction. Each transaction
 * takes the student advisory lock; it is released across the LLM calls and the
 * retrieval (an LLM call must never hold a DB connection). Novelty is a
 * deterministic write-time guarantee — a re-check under the lock plus the
 * `UNIQUE(student_id, college_id)` backstop — never a prompt hope.
 *
 * [CollegeSearchService] is constructor-injected (a thin orchestrator over
 * [Database]), not built internally.
 *
 * [budgetService] gates the pass (RFC 109): an exhausted student's pass skips in
 * the read phase with a named [SkipReason], spending nothing on either call and
 * writing no run row. The parameter is undefaulted so a root cannot wire an
 * ungated pass by omission.
 *
 * [codebook] is the loaded published codebook (RFC 147). This pass used to carry
 * its OWN copy of the filter vocabulary — a second `record_college_query` schema
 * and a second parse — and that copy had drifted far enough that its codebook
 * had to be re-stated as prose in a seeded prompt. Both halves are now
 * [CollegeQueryVocabulary]'s, shared verbatim with `search_colleges`, so the
 * two boundaries cannot describe the same filter differently again.
 */
class FitLensService(
  private val database: Database,
  private val llmCallLog: LlmCallLog,
  private val collegeSearchService: CollegeSearchService,
  private val config: FitLensConfig,
  private val budgetService: BudgetService,
  codebook: Codebook,
) {
  private val vocabulary = CollegeQueryVocabulary(codebook)

  /**
   * Call #1's tool: the SHARED [CollegeQuery] filter fields, all optional
   * (absent = the axis is unconstrained). An instance val rather than a
   * companion constant because the vocabulary it advertises is read from the
   * loaded codebook, not written here. `limit` is not in the schema — the
   * service sets it after parse. Guidance, not a hard validator (tier A) —
   * [parseQuery] enforces.
   */
  private val recordCollegeQueryTool: JsonObject =
    ToolSchema.tool(
      name = RECORD_COLLEGE_QUERY_TOOL_NAME,
      description =
        "Record the structured college-dataset query distilled from the " +
          "student's claims. Omit any axis you are unsure of.",
      inputSchema = ToolSchema.objectSchema(*vocabulary.schemaProperties().toList().toTypedArray()),
    )
  private val logger = LoggerFactory.getLogger(FitLensService::class.java)

  private companion object {
    /**
     * Upper bound on a rationale's length, mirroring the
     * `fit_suggestions_rationale_length_check` DB CHECK (2048 chars). Enforced in
     * [parseReason] so an over-length rationale fails the pass instead of tripping
     * the CHECK at write time.
     */
    private const val MAX_RATIONALE_CHARS = 2_048

    /**
     * How a figure the school does not report is written into the prompt.
     *
     * The prompt is read by a model, so the literal token `null` is not a
     * statement of absence -- it is a word, and one printed directly under a
     * sentence promising the number beside it is a published in-state net price
     * (RFC 157). Every nullable SCALAR on the row says its absence in words; the
     * program list is a list, and an empty one already reads as no programs.
     */
    private const val NOT_REPORTED = "not reported"

    const val RECORD_COLLEGE_QUERY_TOOL_NAME = "record_college_query"
    const val RECORD_FIT_REASON_TOOL_NAME = "record_fit_reason"

    // Call #2: the chosen college (or none). Absent/blank collegeId = no fit.
    // Guidance, not a hard validator (tier A) — parseReason enforces.
    private val RECORD_FIT_REASON_TOOL: JsonObject =
      ToolSchema.tool(
        name = RECORD_FIT_REASON_TOOL_NAME,
        description =
          "Record at most one recommended college from the supplied matches, " +
            "with a rationale. Omit collegeId when nothing genuinely fits.",
        inputSchema =
          ToolSchema.objectSchema(
            "collegeId" to ToolSchema.string(),
            "rationale" to ToolSchema.string(),
          ),
      )
  }

  /**
   * Runs one fit-lens pass over [studentId]. Returns a sealed [FitLensResult] the
   * handler maps to a `JobResult`.
   */
  suspend fun discover(studentId: StudentId): FitLensResult {
    val readout =
      try {
        readPhase(studentId)
      } catch (e: Exception) {
        logger.warn("fit-lens read phase failed for student=[{}]", studentId.asString, e)
        return FitLensResult.TransientFailure("read phase: ${e.message}", e)
      }

    return when (readout) {
      is ReadPhase.Skip -> FitLensResult.Skipped(readout.reason)
      is ReadPhase.Ready -> runPass(studentId, readout)
    }
  }

  // ---------------------------------------------------------------------------
  // Read phase (txn under the student advisory lock)
  // ---------------------------------------------------------------------------

  private suspend fun readPhase(studentId: StudentId): ReadPhase =
    database.withConnection { session ->
      val studentResult = StudentsDao.findById(session, studentId, SoftDeleteScope.ALL)
      if (studentResult.exceptionOrNull() is NotFoundException) {
        return@withConnection ReadPhase.Skip(SkipReason.StudentNotFound(studentId))
      }
      val student = studentResult.getOrThrow()
      if (student.deletedAt != null) return@withConnection ReadPhase.Skip(SkipReason.StudentSoftDeleted(studentId))

      AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

      // Budget gate (RFC 109), taken under the lock so the verdict is consistent
      // with the pass it guards, and ahead of every other gate: an exhausted
      // student is blocked from spending regardless of how much signal they have.
      when (val verdict = budgetService.verdict(session, studentId).getOrThrow()) {
        is BudgetVerdict.Exhausted -> {
          return@withConnection ReadPhase.Skip(SkipReason.BudgetExhausted(studentId, verdict.entitlement))
        }

        // Not dead: this arm is what makes the `when` exhaustive, so a third
        // BudgetVerdict would fail to compile here instead of falling through as
        // "allowed to spend". Do not collapse it back to an `is` check.
        BudgetVerdict.Entitled -> {}
      }

      val activeClaims = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
      // minClaims floor: too little signal to search on.
      if (activeClaims.size < config.minClaims) {
        return@withConnection ReadPhase.Skip(SkipReason.BelowMinClaimsFloor(studentId, activeClaims.size, config.minClaims))
      }

      val listEntries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
      val suggestedIds = FitSuggestionsDao.listSuggestedCollegeIds(session, studentId).getOrThrow()

      // Freshness gate: no model change since the last applied run.
      val lastAppliedAt = FitLensRunsDao.lastAppliedAt(session, studentId).getOrThrow()
      val freshness = (activeClaims + listEntries).latestUpdatedAt()
      if (lastAppliedAt != null && freshness != null && !freshness.isAfter(lastAppliedAt)) {
        return@withConnection ReadPhase.Skip(SkipReason.ModelUnchangedSinceLastApplied(studentId, freshness, lastAppliedAt))
      }

      // Failure circuit breaker: stop re-billing a model state that has failed to
      // parse maxConsecutiveFailures times running.
      val consecutiveFailures = FitLensRunsDao.consecutiveFailuresSince(session, studentId).getOrThrow()
      if (consecutiveFailures >= config.maxConsecutiveFailures) {
        return@withConnection ReadPhase.Skip(
          SkipReason.FailureCircuitBreakerOpen(studentId, consecutiveFailures, config.maxConsecutiveFailures),
        )
      }

      // Resolve both prompt catalog rows up front so a later failure still has
      // both provenance pins.
      val queryPrompt =
        SystemPromptsDao
          .findByNameAndVersion(session, config.queryPromptName, config.queryPromptVersion)
          .getOrThrow()
      val reasonPrompt =
        SystemPromptsDao
          .findByNameAndVersion(session, config.reasonPromptName, config.reasonPromptVersion)
          .getOrThrow()

      // The capped claim set feeds both prompts (mirrors synthesis.maxClaims).
      val cappedClaims = activeClaims.take(config.maxClaims)
      // The Layer-1 steering exclusion set (steering only, never the novelty
      // guarantee): college-list + already-suggested colleges, resolved to names
      // so LLM call #1 can be told to propose something else. The write-time
      // recheck (Layer 2) is the actual guarantee.
      val excludedCollegeIds = (listEntries.map { it.collegeId } + suggestedIds).toSet()
      val excludedCollegeNames = CollegesDao.listNamesByIds(session, excludedCollegeIds).getOrThrow()

      ReadPhase.Ready(
        cappedClaims = cappedClaims,
        excludedCollegeNames = excludedCollegeNames,
        queryPrompt = queryPrompt,
        reasonPrompt = reasonPrompt,
      )
    }

  // ---------------------------------------------------------------------------
  // The two-call pass: formulate → retrieve → reason → write
  // ---------------------------------------------------------------------------

  private suspend fun runPass(
    studentId: StudentId,
    ready: ReadPhase.Ready,
  ): FitLensResult {
    // LLM call #1 — formulate the CollegeQuery.
    val queryCall =
      when (val outcome = runChat(studentId, "formulate", buildQueryRequest(ready))) {
        is ChatOutcome.Bail -> return outcome.result
        is ChatOutcome.Completed -> outcome
      }
    val queryLlmRequestId = queryCall.llmRequestId

    // The forced tool's input object is the payload; a missing tool_use block is
    // the tier-A analogue of an unparseable envelope.
    val queryParse =
      when (val forced = readForcedTool(queryCall.event.response, RECORD_COLLEGE_QUERY_TOOL_NAME)) {
        is ForcedToolInput.Absent -> QueryParse.Failure(FailureReason.QueryNoToolUse(studentId, forced.stopReason, forced.excerpt))
        is ForcedToolInput.Present -> parseQuery(studentId, forced.input)
      }
    val query =
      when (queryParse) {
        is QueryParse.Failure -> return onQueryParseFailure(studentId, ready, queryParse, queryLlmRequestId)
        is QueryParse.Parsed -> queryParse.query
      }

    // Retrieve — run the query directly in the worker (no shared txn).
    val outcome =
      try {
        collegeSearchService.search(query.copy(limit = config.searchLimit)).getOrThrow()
      } catch (e: Exception) {
        logger.warn("fit-lens retrieval failed for student=[{}]", studentId.asString, e)
        return FitLensResult.TransientFailure("retrieval: ${e.message}", e)
      }
    val matches =
      when (outcome) {
        // A program word the vocabulary cannot expand is not a fault and not
        // worth a retry: no college can match it, which is the zero-match
        // outcome with a reason. Logged so the formulating prompt can be fixed.
        is CollegeSearchOutcome.UnresolvableProgramFilter -> {
          val reason =
            SkipReason.UnresolvableProgramFilter(studentId, outcome.field, outcome.value, outcome.cause)
          logger.warn("fit-lens query named an unusable program filter: {}", reason.toDisplay())
          // The run row is written exactly as a zero-match pass writes it — the
          // tokens were spent — but the SKIP carries the cause, so a broken
          // formulating prompt is not filed as "the search found nothing".
          return onZeroMatches(studentId, ready, queryLlmRequestId, reason)
        }

        // Not a domain outcome at all: the index has never been built, which no
        // retry of the LLM can fix and the next ingest does. Retriable, and
        // never billed as an applied run with zero suggestions.
        is CollegeSearchOutcome.IndexNotBuilt -> {
          logger.error(
            "fit-lens retrieval hit an UNBUILT college_search_index for student=[{}]; " +
              "run the ingest's `search-index` phase",
            studentId.asString,
          )
          return FitLensResult.TransientFailure("retrieval: the college search index has not been built yet")
        }

        is CollegeSearchOutcome.Page -> {
          outcome.page.matches
        }
      }
    if (matches.isEmpty()) return onZeroMatches(studentId, ready, queryLlmRequestId)

    // LLM call #2 — reason over the real matches.
    val reasonCall =
      when (val outcome = runChat(studentId, "reason", buildReasonRequest(ready, matches))) {
        is ChatOutcome.Bail -> return outcome.result
        is ChatOutcome.Completed -> outcome
      }
    val reasonLlmRequestId = reasonCall.llmRequestId
    val matchIds = matches.map { it.id }.toSet()

    val reasonParse =
      when (val forced = readForcedTool(reasonCall.event.response, RECORD_FIT_REASON_TOOL_NAME)) {
        is ForcedToolInput.Absent -> ReasonParse.Failure(FailureReason.ReasonNoToolUse(studentId, forced.stopReason, forced.excerpt))
        is ForcedToolInput.Present -> parseReason(studentId, forced.input, matchIds)
      }
    return when (reasonParse) {
      is ReasonParse.Failure -> {
        onReasonParseFailure(studentId, ready, reasonParse, matches.size, queryLlmRequestId, reasonLlmRequestId)
      }

      is ReasonParse.Empty -> {
        onReasonEmpty(studentId, ready, matches.size, queryLlmRequestId, reasonLlmRequestId)
      }

      is ReasonParse.Chosen -> {
        writePhase(
          studentId,
          ready,
          reasonParse.collegeId,
          reasonParse.rationale,
          matches.size,
          queryLlmRequestId,
          reasonLlmRequestId,
        )
      }
    }
  }

  /**
   * Runs one [chatProvider] call, folding a thrown exception, a `Rejected`, and a
   * `TransientFailure` terminal into a single [ChatOutcome.Bail] carrying a
   * [FitLensResult.TransientFailure]; a billed [ChatEvent.Completed] is
   * [ChatOutcome.Completed]. Shared by both LLM calls so [runPass] reads as its
   * formulate→retrieve→reason→write pipeline.
   */
  private suspend fun runChat(
    studentId: StudentId,
    label: String,
    request: ChatRequest,
  ): ChatOutcome {
    val loggedCall =
      try {
        llmCallLog.record(request)
      } catch (e: Exception) {
        logger.warn("fit-lens [{}] call failed for student=[{}]", label, studentId.asString, e)
        return ChatOutcome.Bail(FitLensResult.TransientFailure("$label call: ${e.message}", e))
      }
    return when (val event = loggedCall.terminal) {
      is ChatEvent.Rejected -> ChatOutcome.Bail(FitLensResult.TransientFailure("$label rejected: ${event.reason}"))
      is ChatEvent.TransientFailure -> ChatOutcome.Bail(FitLensResult.TransientFailure("$label transient: ${event.reason}"))
      is ChatEvent.Completed -> ChatOutcome.Completed(event, loggedCall.llmRequestId)
    }
  }

  /**
   * A completed-but-unusable call #1: a Failed pass that died before any retrieve,
   * so `matches_considered` is null and only call #1's tokens are billed.
   */
  private suspend fun onQueryParseFailure(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: QueryParse.Failure,
    queryLlmRequestId: LlmRequestId,
  ): FitLensResult {
    logger.warn(
      "unusable fit-lens CollegeQuery for student=[{}]: [{}]",
      studentId.asString,
      parsed.detail.toDisplay(),
    )
    // Died before the reason call: only the query call is referenced.
    return writeFailedRun(
      studentId,
      ready,
      reason = parsed.detail,
      matchesConsidered = null,
      queryLlmRequestId = queryLlmRequestId,
      reasonLlmRequestId = null,
    )
  }

  /**
   * Zero-match: a valid Skipped outcome (nothing novel to reason over). Tokens
   * spent, so an applied row with `suggestions_written = 0` advances freshness.
   * Only the query call ran, so `reason_llm_request_id` stays null.
   */
  private suspend fun onZeroMatches(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    queryLlmRequestId: LlmRequestId,
    // Why there was nothing to reason over. Defaulted to the plain zero-match
    // reason, so the one caller with a CAUSE (an unexpandable program filter)
    // states it and every other caller is unchanged.
    reason: SkipReason = SkipReason.ZeroSearchMatches(studentId),
  ): FitLensResult {
    val write =
      writeAppliedRun(
        studentId,
        ready,
        suggestionsWritten = 0,
        matchesConsidered = 0,
        queryLlmRequestId = queryLlmRequestId,
        reasonLlmRequestId = null,
      )
    return write ?: FitLensResult.Skipped(reason)
  }

  private suspend fun onReasonParseFailure(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ReasonParse.Failure,
    matchesConsidered: Int,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId,
  ): FitLensResult {
    logger.warn(
      "unusable fit-lens reason output for student=[{}]: [{}]",
      studentId.asString,
      parsed.detail.toDisplay(),
    )
    return writeFailedRun(
      studentId,
      ready,
      reason = parsed.detail,
      matchesConsidered = matchesConsidered,
      queryLlmRequestId = queryLlmRequestId,
      reasonLlmRequestId = reasonLlmRequestId,
    )
  }

  /** The model found nothing that genuinely fits: a valid Skipped/applied with no suggestion. */
  private suspend fun onReasonEmpty(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    matchesConsidered: Int,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId,
  ): FitLensResult {
    val write =
      writeAppliedRun(
        studentId,
        ready,
        suggestionsWritten = 0,
        matchesConsidered = matchesConsidered,
        queryLlmRequestId = queryLlmRequestId,
        reasonLlmRequestId = reasonLlmRequestId,
      )
    return write ?: FitLensResult.Skipped(SkipReason.ReasonReturnedNoFit(studentId))
  }

  // ---------------------------------------------------------------------------
  // Write phase (txn under the student advisory lock) — the deterministic novelty gate
  // ---------------------------------------------------------------------------

  private suspend fun writePhase(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    collegeId: CollegeId,
    rationale: String,
    matchesConsidered: Int,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId,
  ): FitLensResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

        // Novelty re-verification under the lock against the student's structured
        // college ids: active college_list entries + prior fit_suggestions. The
        // exclusion set fed to the LLM was steering only; this is the guarantee.
        val listCollegeIds = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow().map { it.collegeId }
        val priorSuggestedIds = FitSuggestionsDao.listSuggestedCollegeIds(session, studentId).getOrThrow()
        val known = (listCollegeIds + priorSuggestedIds).toSet()

        val suggestionsWritten =
          if (collegeId in known) {
            // A collision: write no suggestion; tokens still logged.
            0
          } else {
            val create = FitSuggestionsDao.create(session, NewFitSuggestion(studentId, collegeId, rationale))
            when (val error = create.exceptionOrNull()) {
              null -> {
                1
              }

              // Only the UNIQUE(student_id, college_id) backstop is a benign
              // novelty collision (a concurrent overlapping pass raced past the
              // recheck): write no suggestion, still log tokens. Any OTHER
              // constraint violation — e.g. an over-length rationale tripping
              // fit_suggestions_rationale_length_check (23514) — is a genuine
              // failure that must not be swallowed as a no-op; rethrow so the
              // catch below records it as a TransientFailure.
              is ConstraintViolationException -> {
                if (error.constraint == "fit_suggestions_student_college_unique") 0 else throw error
              }

              else -> {
                throw error
              }
            }
          }

        appendRun(
          session,
          studentId,
          ready,
          FitLensOutcome.Applied(suggestionsWritten),
          matchesConsidered,
          queryLlmRequestId,
          reasonLlmRequestId,
        )
        FitLensResult.Applied
      }
    } catch (e: Exception) {
      logger.warn("fit-lens write phase failed for student=[{}]", studentId.asString, e)
      FitLensResult.TransientFailure("write phase: ${e.message}", e)
    }

  /**
   * Writes an `applied` run (no suggestion) for the zero-match and empty-reason
   * skips. Returns null on success; a [FitLensResult.TransientFailure] if the
   * DB write fails, so the caller retries rather than silently dead-lettering as
   * `JobResult.Success` (which would lose the billed-token row and not advance
   * freshness) — matching [writePhase]'s propagation.
   */
  private suspend fun writeAppliedRun(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    suggestionsWritten: Int,
    matchesConsidered: Int,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId?,
  ): FitLensResult? =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        appendRun(
          session,
          studentId,
          ready,
          FitLensOutcome.Applied(suggestionsWritten),
          matchesConsidered,
          queryLlmRequestId,
          reasonLlmRequestId,
        )
      }
      null
    } catch (e: Exception) {
      logger.warn("fit-lens applied-run write failed for student=[{}]", studentId.asString, e)
      FitLensResult.TransientFailure("applied-run write: ${e.message}", e)
    }

  /**
   * Writes a `failed` run carrying the pass's token usage (the spend is
   * recorded); the freshness marker does not advance. [category]/[reason] are
   * persisted onto the row (`fit_lens_runs_failure_consistency_check` requires
   * both on a `failed` row) and [reason] also rides on the returned
   * [FitLensResult.Failed] so the handler dead-letters (no retry) with a
   * specific diagnostic. If the DB write itself fails, propagates a
   * [FitLensResult.TransientFailure] instead so the run row is not silently
   * lost — matching [writePhase]'s propagation.
   */
  private suspend fun writeFailedRun(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    reason: FailureReason,
    matchesConsidered: Int?,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId?,
  ): FitLensResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        appendRun(
          session,
          studentId,
          ready,
          FitLensOutcome.Failed(reason.category, reason.toDisplay()),
          matchesConsidered,
          queryLlmRequestId,
          reasonLlmRequestId,
        )
      }
      FitLensResult.Failed(reason)
    } catch (e: Exception) {
      logger.warn("fit-lens failed-run write failed for student=[{}]", studentId.asString, e)
      FitLensResult.TransientFailure("failed-run write: ${e.message}", e)
    }

  private fun appendRun(
    session: SqlSession,
    studentId: StudentId,
    ready: ReadPhase.Ready,
    outcome: FitLensOutcome,
    matchesConsidered: Int?,
    queryLlmRequestId: LlmRequestId,
    reasonLlmRequestId: LlmRequestId?,
  ) {
    FitLensRunsDao
      .append(
        session,
        NewFitLensRun(
          studentId = studentId,
          outcome = outcome,
          querySystemPromptId = ready.queryPrompt.id,
          reasonSystemPromptId = ready.reasonPrompt.id,
          queryLlmRequestId = queryLlmRequestId,
          reasonLlmRequestId = reasonLlmRequestId,
          matchesConsidered = matchesConsidered,
        ),
      ).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // Prompt assembly
  // ---------------------------------------------------------------------------

  private fun buildQueryRequest(ready: ReadPhase.Ready): ChatRequest =
    ChatRequest(
      model = config.model,
      system = ready.queryPrompt.body,
      messages = listOf(ChatMessage.text(ChatRole.USER, buildQueryContext(ready))),
      maxTokens = config.queryMaxTokens,
      tools = listOf(recordCollegeQueryTool),
      toolChoice = forcedToolChoice(RECORD_COLLEGE_QUERY_TOOL_NAME),
    )

  private fun buildQueryContext(ready: ReadPhase.Ready): String =
    buildString {
      appendLine("# Active claims")
      if (ready.cappedClaims.isEmpty()) {
        appendLine("(none)")
      } else {
        for (claim in ready.cappedClaims) {
          appendLine("- kind=${claim.kind.value} topic=${claim.topic.value}: ${claim.statement}")
        }
      }
      appendLine()
      // Layer-1 novelty steering: the colleges the student already knows or has
      // been suggested before, by name, so the model proposes something else.
      // This is steering only; the write-time recheck is the guarantee.
      appendLine("# Colleges already known or previously suggested (propose something else)")
      if (ready.excludedCollegeNames.isEmpty()) {
        appendLine("(none)")
      } else {
        for (name in ready.excludedCollegeNames) {
          appendLine("- $name")
        }
      }
    }

  private fun buildReasonRequest(
    ready: ReadPhase.Ready,
    matches: List<CollegeMatch>,
  ): ChatRequest =
    ChatRequest(
      model = config.model,
      system = ready.reasonPrompt.body,
      messages = listOf(ChatMessage.text(ChatRole.USER, buildReasonContext(ready, matches))),
      maxTokens = config.reasonMaxTokens,
      tools = listOf(RECORD_FIT_REASON_TOOL),
      toolChoice = forcedToolChoice(RECORD_FIT_REASON_TOOL_NAME),
    )

  private fun buildReasonContext(
    ready: ReadPhase.Ready,
    matches: List<CollegeMatch>,
  ): String =
    buildString {
      appendLine("# Active claims")
      for (claim in ready.cappedClaims) {
        appendLine("- kind=${claim.kind.value} topic=${claim.topic.value}: ${claim.statement}")
      }
      appendLine()
      appendLine("# Retrieved colleges (choose at most one, by collegeId)")
      // RFC 157 D-A: the College Scorecard builds this net price for students
      // paying the IN-STATE rate and publishes no out-of-state counterpart, so
      // the key SAYS in-state here. This read goes round CollegeCostService, and
      // so round its withholding, and the search index does not carry the
      // family's residency -- withholding it belongs with the index (D-G). What
      // this line can do today, it does: no model is handed a bare number whose
      // residency basis is unstated.
      appendLine(
        "(inStateNetPricePerYearUsd is the school's own published in-state net price, not this family's; " +
          "at a public school outside the family's state it is somebody else's figure.)",
      )
      for (match in matches) {
        appendLine(
          "- collegeId=[${match.id.asString}] name=[${match.name}] city=[${match.city}] " +
            "state=[${match.state}] control=[${match.control}] " +
            "undergradEnrollmentHeadcount=[${match.undergradEnrollmentHeadcount ?: NOT_REPORTED}] " +
            "admissionRateShare=[${match.admissionRateShare ?: NOT_REPORTED}] " +
            "inStateNetPricePerYearUsd=[${match.netPricePerYearUsd ?: NOT_REPORTED}] " +
            "completionRate150pct4yrShare=[${match.completionRate150pct4yrShare ?: NOT_REPORTED}] " +
            "medianEarnings10yAfterEntryUsd=[${match.medianEarnings10yAfterEntryUsd ?: NOT_REPORTED}] " +
            "programs=[${match.programTitles.orEmpty().joinToString("; ")}]",
        )
      }
    }

  // ---------------------------------------------------------------------------
  // Output parsing (JSON element DSL — the service module has no serialization plugin)
  // ---------------------------------------------------------------------------

  /**
   * Reads call #1's [CollegeQuery] filter object from the forced tool's
   * `tool_use.input` through the SHARED [CollegeQueryVocabulary] (RFC 147 D45).
   * Any type-invalid field, and any codebook word the loaded codebook does not
   * carry, fails the pass — no partial acceptance, and never a silently dropped
   * filter. The model never sets `limit`; the service sets it here.
   */
  private fun parseQuery(
    studentId: StudentId,
    root: JsonObject,
  ): QueryParse {
    // An unknown key is a REFUSAL, not a shrug — the CollegeSearchTool rule
    // applied here, where it was missing. `vocabulary.parse` reads the keys it
    // knows and ignores the rest, so a model still writing the RETIRED
    // vocabulary (`locales: [11,12,13]`, the shape the v2 prompt taught) would
    // otherwise have its locale filter silently dropped and get every college
    // back — a narrower question answered with a wider answer, which is exactly
    // the failure D45 exists to remove. It is also what made the "roll the
    // prompt back to v2" note a trap rather than a rollback.
    val unknown = root.keys - vocabulary.fieldNames
    if (unknown.isNotEmpty()) {
      return QueryParse.Failure(
        FailureReason.QueryTypeInvalidField(
          studentId,
          "unknown field(s): [${unknown.sorted().joinToString(", ")}]",
        ),
      )
    }

    return vocabulary
      .parse(root, config.searchLimit)
      .fold(
        onSuccess = { QueryParse.Parsed(it) },
        // The vocabulary's message already names the field and, for a word it
        // does not know, lists the whole vocabulary — so it is carried through
        // verbatim rather than reduced to a bare field name.
        onFailure = { QueryParse.Failure(FailureReason.QueryTypeInvalidField(studentId, it.message)) },
      )
  }

  /**
   * Reads call #2's output from the forced tool's `tool_use.input`. An empty
   * object `{}` (or an object with a null/absent collegeId) is [ReasonParse.Empty]
   * (nothing fits). A named `collegeId` must be present in [matchIds]; one outside
   * the set, or a blank rationale, is [ReasonParse.Failure].
   */
  private fun parseReason(
    studentId: StudentId,
    root: JsonObject,
    matchIds: Set<CollegeId>,
  ): ReasonParse {
    val collegeIdRaw = (root["collegeId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (collegeIdRaw == null || collegeIdRaw.isBlank()) {
      // An empty {} or an absent/null collegeId: nothing genuinely fits.
      return ReasonParse.Empty
    }

    val collegeId =
      runCatching { CollegeId(java.util.UUID.fromString(collegeIdRaw)) }.getOrNull()
        ?: return ReasonParse.Failure(FailureReason.ReasonInvalidCollegeId(studentId, collegeIdRaw))
    if (collegeId !in matchIds) {
      return ReasonParse.Failure(FailureReason.ReasonCollegeIdOutsideMatchSet(studentId, collegeId))
    }

    val rationale = (root["rationale"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (rationale == null || rationale.isBlank()) {
      return ReasonParse.Failure(FailureReason.ReasonRationaleMissing(studentId))
    }
    // Belt-and-suspenders: reject an over-length rationale here as a parse failure
    // (a Failed pass) rather than letting it reach the write and trip
    // fit_suggestions_rationale_length_check as a swallowed no-op. The DB CHECK
    // remains the backstop.
    if (rationale.length > MAX_RATIONALE_CHARS) {
      return ReasonParse.Failure(FailureReason.ReasonRationaleTooLong(studentId, rationale.length, MAX_RATIONALE_CHARS))
    }

    return ReasonParse.Chosen(collegeId, rationale)
  }

  // ---------------------------------------------------------------------------
  // Internal carriers
  // ---------------------------------------------------------------------------

  /**
   * The outcome of one LLM call: a billed [Completed] event carrying its
   * log-owned [llmRequestId] (RFC 106), or a [Bail] that short-circuits [runPass].
   */
  private sealed interface ChatOutcome {
    data class Completed(
      val event: ChatEvent.Completed,
      val llmRequestId: LlmRequestId,
    ) : ChatOutcome

    data class Bail(
      val result: FitLensResult,
    ) : ChatOutcome
  }

  private sealed interface ReadPhase {
    data class Skip(
      val reason: SkipReason,
    ) : ReadPhase

    data class Ready(
      val cappedClaims: List<Claim>,
      val excludedCollegeNames: List<String>,
      val queryPrompt: SystemPrompt,
      val reasonPrompt: SystemPrompt,
    ) : ReadPhase
  }

  private sealed interface QueryParse {
    data class Parsed(
      val query: CollegeQuery,
    ) : QueryParse

    data class Failure(
      val detail: FailureReason,
    ) : QueryParse
  }

  private sealed interface ReasonParse {
    data class Chosen(
      val collegeId: CollegeId,
      val rationale: String,
    ) : ReasonParse

    data object Empty : ReasonParse

    data class Failure(
      val detail: FailureReason,
    ) : ReasonParse
  }
}
