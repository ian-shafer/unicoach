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
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.CanonicalCostReader
import ed.unicoach.coaching.costs.canonical.DatedStat
import ed.unicoach.coaching.costs.canonical.DbCanonicalCostReader
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
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
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.PriceRuler
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.latestUpdatedAt
import kotlinx.coroutines.CancellationException
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
  /**
   * The canonical money store's read side (RFC 166 §9), injected exactly as
   * every other collaborator here is. Defaulted, so no root has to name it --
   * and substitutable, which is the only way to prove what this pass does when
   * that read FAILS without breaking the rest of the pass for unrelated
   * reasons.
   */
  private val canonicalCostReader: CanonicalCostReader = DbCanonicalCostReader(database),
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
      // `offerPublishedPrice = false`: the fit lens carries no student residency
      // into the query, so it is always on the net-price ruler (RFC 169) and the
      // published bound could only ever be refused. The DIGEST is a different
      // question and is answered from the canonical net-price stat (RFC 166 §9).
      inputSchema =
        ToolSchema.objectSchema(
          *vocabulary
            .schemaProperties(offerPublishedPrice = false)
            .toList()
            .toTypedArray(),
        ),
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

    /**
     * How a figure the source dates no year is written (RFC 166 §9).
     *
     * `cohort_money_stats.vintage` stores the literal `'undated'` where the
     * publisher dates nothing, and [DatedStat] hands that back as a null year.
     * The digest states the year of every figure it prints, so the ABSENCE of a
     * year is stated too -- an unstated year reads as this year's number.
     */
    private const val NOT_DATED = "not dated by the source"

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

    // The digest's one money figure, read from the CANONICAL store rather than
    // off the search index row (RFC 166 §9). ONE batched statement for the whole
    // match set, on its own short-lived connection: retrieval has already
    // returned, and no LLM call is in flight.
    val netPrices = netPricesForDigest(studentId, matches.map { it.id })

    // LLM call #2 — reason over the real matches.
    val reasonCall =
      when (val outcome = runChat(studentId, "reason", buildReasonRequest(ready, matches, netPrices))) {
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

  /**
   * The digest's net prices, or the fact that this run could not read them:
   * this read DEGRADES, it never aborts the pass.
   *
   * The figure is ADVISORY -- one line of a digest the model reasons over. A
   * failure here used to return `TransientFailure`, throwing away a completed
   * search and a paid LLM call for a read that only ever decorated them, and
   * leaving the student with no suggestions at all rather than suggestions with
   * one fact missing.
   *
   * What it may NOT do is launder the failure into a data claim. Returning an
   * empty map made every matched college print "We have not collected this
   * figure yet." -- a statement about unicoach's COVERAGE, made to a model that
   * narrates it to a student. [DigestNetPrices.Unavailable] is the other state,
   * and the digest omits the key entirely for it (see [netPriceSegment]), so
   * the not-collected sentence keeps meaning only what it says.
   *
   * [CancellationException] is rethrown FIRST, as every sibling in this package
   * does: it is an [Exception], so catching it here would log an abandoned turn
   * as a database fault and then walk on into a BILLED second LLM call.
   */
  private suspend fun netPricesForDigest(
    studentId: StudentId,
    collegeIds: List<CollegeId>,
  ): DigestNetPrices =
    try {
      DigestNetPrices.Read(readNetPrices(collegeIds))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.warn(
        "fit-lens canonical net-price read failed for student=[{}]; the digest omits the net-price " +
          "key and the run continues",
        studentId.asString,
        e,
      )
      DigestNetPrices.Unavailable
    }

  /**
   * The average net price each matched college carries in the canonical store
   * (RFC 166 §9), keyed by college; a college with no `avg_net_price` row at all
   * is simply absent from the map.
   *
   * Read through [CanonicalCostReader] -- THE door onto the store -- so the
   * latest-vintage choice, the whole-dollar rounding, the reading/status
   * pairing and the FULL canonical address are the same ones the cost answer
   * uses, rather than a second assembly of them here. That matters because the
   * store holds more than one `avg_net_price` series per college: two doors
   * would let this digest and the cost page quote two different populations'
   * figures.
   *
   * The band-less row is the one asked for: this pass knows nothing about the
   * family's income band, and the overall figure is the school's own.
   */
  private suspend fun readNetPrices(collegeIds: List<CollegeId>): Map<CollegeId, DigestNetPrice> =
    canonicalCostReader
      .readCohortStats(collegeIds)
      .mapNotNull { (collegeId, figures) ->
        figures.cohortOf(CostField.NET_PRICE, band = null)?.let { stat ->
          // The tier is resolved HERE, with the row, INSIDE the guard that
          // makes this read best-effort -- never at render time. A stored cell
          // id no tier answers for is a store problem like any other, so this
          // run omits the net-price key and continues; resolved in the digest
          // loop it would throw out of a pass that has already billed a call.
          collegeId to
            DigestNetPrice(stat, AssuranceTier.of(stat.source, stat.sourceVariable, "the fit-lens net-price digest"))
        }
      }.toMap()

  /**
   * The net-price part of one college's digest line, or NOTHING at all when
   * this run could not read the store.
   *
   * Omission is the point. The keys below are answered from the canonical
   * store, and every sentence they can carry -- including "We have not
   * collected this figure yet." -- is a claim about our DATA. A run that could
   * not read the store knows nothing about this college's net price, so it says
   * nothing about it, and the model is left with the fields it can trust.
   */
  private fun netPriceSegment(
    netPrices: DigestNetPrices,
    collegeId: CollegeId,
  ): String =
    when (netPrices) {
      is DigestNetPrices.Read -> "${netPriceDigest(netPrices.byCollege[collegeId])} "
      DigestNetPrices.Unavailable -> ""
    }

  /**
   * One college's net price as the digest states it: at most one dollar amount,
   * under ONE stable key, with the population it was built on, its status and
   * its vintage as their OWN fields beside it (RFC 166 §9).
   *
   * Three rules, each of which the earlier shape broke:
   *
   * 1. The basis is a FIELD, never a spelling. `inStateNetPricePerYearUsd` vs
   *    `netPricePerYearUsd` made every reader -- the model, this file's own
   *    prose, each test -- carry the prefix convention in its head; drop or
   *    paraphrase it in one place and a `residency_scope = in_state_rate_paying`
   *    figure is spoken as everybody's price, the RFC 157 defect exactly. The
   *    cost tool already emits a basis CODE beside its figure; so does this.
   * 2. `netPricePerYearUsd` holds DOLLARS or nothing. A status sentence served
   *    under a key that names a per-year USD amount invites a model to quote the
   *    sentence as if it were the figure. Where there is no amount the key is
   *    OMITTED. [FigureStatus] rides as its own token EITHER WAY, with the
   *    family-facing statement beside it where the status has one -- the shape
   *    of the tool's `figure_statuses`.
   * 3. Nothing is dated that does not exist. With no row there is no source, so
   *    no vintage clause and no basis is stated at all: the only true thing is
   *    that we have not collected it ([FigureStatus.NOT_COLLECTED_BY_US]), which
   *    is OUR silence and never the school's.
   */
  private fun netPriceDigest(netPrice: DigestNetPrice?): String {
    // No row, so no publisher: the status sentence names nobody, which is right
    // -- the gap is OURS (RFC 177).
    if (netPrice == null) return agentlessStatusFields(FigureStatus.NOT_COLLECTED_BY_US)

    val stat = netPrice.stat
    val amount = stat.amountUsd
    // No dollars, no dollar key: where there is no amount the key is OMITTED
    // and the status fields below are the whole answer.
    val figure = if (amount != null) "netPricePerYearUsd=[$amount] " else ""
    // The status fields are emitted for EVERY row, shown or blank. They used to
    // hang on the no-dollars branch alone, which put the tier -- what KIND of
    // number this is -- on exactly the figures a family never sees, and left the
    // one net price the model actually quotes with no instrument behind it. A
    // shown, plainly reported row says nothing about its status
    // ([FigureStatusCopy.statementOf] answers null for it) and so says its tier
    // alone.
    return figure +
      "netPriceBasis=[${stat.residencyScope.value}] " +
      "netPriceVintage=[${stat.vintage?.label ?: NOT_DATED}] " +
      statusFields(stat.status, stat.source, netPrice.assurance)
  }

  /**
   * ONE row's status as the digest states it, shown or blank: the stable CODE,
   * plus the sentence a coach may say where there is one, NAMING the publisher
   * whose row this cell came from (RFC 177), and always saying what KIND of
   * number it is (RFC 179).
   *
   * Emitted for EVERY row and not for the blanks alone. Hung on the no-dollars
   * branch, the tier appeared on exactly the figures a family never sees and the
   * one net price the model quotes had no instrument behind it.
   *
   * [assurance] arrives ALREADY RESOLVED, from the read that owns the row: the
   * tier is a function of the stored `(source, source_variable)` pair, and
   * resolving that pair here -- at render time, outside the read's best-effort
   * guard -- let one unreadable row halt the whole pass. A row is a row whether
   * or not it bears dollars, so a suppressed net price still has an instrument
   * behind it and the digest may say which.
   *
   * [FigureStatus.REPORTED] DOES reach here now: a shown net price carries its
   * tier too, under the publisher sentence RFC 177 wrote for it. Where a status
   * has nothing to say, [AssuranceTierCopy.composedStatementOf] leaves the tier
   * sentence standing alone -- it is the FLOOR, so the composed line always says
   * something.
   */
  private fun statusFields(
    status: FigureStatus,
    source: MoneySource,
    assurance: AssuranceTier,
  ): String =
    statusLine(
      status,
      // Two sentences, in the seam's own order: whose act first, what kind of
      // number second (RFC 179). Composed BESIDE the status sentence and never
      // into it -- [FigureStatusCopy] is byte-pinned by the immutable prompt
      // row, and a tier appended inside its return value would move it. The
      // composition itself is [AssuranceTierCopy]'s, not this file's.
      AssuranceTierCopy.composedStatementOf(FigureStatusCopy.statementOf(status, source), assurance),
    )

  /**
   * The same two fields with NO publisher in hand: there is no row, so there is
   * nobody to name, and the sentence says so agentlessly.
   *
   * Its own name rather than a null [MoneySource] (RFC 177): the one caller is
   * the no-row branch, and a caller that HAS a publisher cannot reach the
   * agentless sentence by leaving an argument off.
   */
  private fun agentlessStatusFields(status: FigureStatus): String = statusLine(status, FigureStatusCopy.agentlessStatementOf(status))

  /** The two fields themselves, once, whichever of the two sentences above the caller chose. */
  private fun statusLine(
    status: FigureStatus,
    statement: String?,
  ): String =
    "netPriceStatus=[${status.value}] " +
      "netPriceNote=[${statement ?: NOT_REPORTED}]"

  private fun buildReasonRequest(
    ready: ReadPhase.Ready,
    matches: List<CollegeMatch>,
    netPrices: DigestNetPrices,
  ): ChatRequest =
    ChatRequest(
      model = config.model,
      system = ready.reasonPrompt.body,
      messages = listOf(ChatMessage.text(ChatRole.USER, buildReasonContext(ready, matches, netPrices))),
      maxTokens = config.reasonMaxTokens,
      tools = listOf(RECORD_FIT_REASON_TOOL),
      toolChoice = forcedToolChoice(RECORD_FIT_REASON_TOOL_NAME),
    )

  private fun buildReasonContext(
    ready: ReadPhase.Ready,
    matches: List<CollegeMatch>,
    netPrices: DigestNetPrices,
  ): String =
    buildString {
      appendLine("# Active claims")
      for (claim in ready.cappedClaims) {
        appendLine("- kind=${claim.kind.value} topic=${claim.topic.value}: ${claim.statement}")
      }
      appendLine()
      appendLine("# Retrieved colleges (choose at most one, by collegeId)")
      // RFC 166 §9: the net price comes from the CANONICAL store, and the row
      // says on which population it was built -- so `netPriceBasis` is READ off
      // `residency_scope` rather than asserted. RFC 157 D-A still holds where the
      // basis IS in-state: this read goes round CollegeCostService and so round
      // its withholding, and the read phase never learns the family's residency,
      // so withholding stays with the index (D-G). What this line can do today it
      // does: no model is handed a bare number whose basis or year is unstated.
      // ... and it is written only where there ARE net-price keys to describe.
      // A run whose canonical read was unavailable carries none, so a paragraph
      // explaining them would describe a line the model cannot see.
      if (netPrices is DigestNetPrices.Read) {
        appendLine(
          "(netPricePerYearUsd is the school's own average net price per year, and netPriceBasis says " +
            "which students it was built on. in_state_rate_paying is the school's own published in-state " +
            "net price, not this family's; at a public school outside the family's state it is somebody " +
            "else's figure. all is built for every student at the school and claims no residency. " +
            "netPriceVintage is the year the figure describes. Where we hold no amount there is no " +
            "netPricePerYearUsd key at all: netPriceStatus carries the reason code and netPriceNote says " +
            "it in words.)",
        )
      }
      for (match in matches) {
        appendLine(
          "- collegeId=[${match.id.asString}] name=[${match.name}] city=[${match.city}] " +
            "state=[${match.state}] control=[${match.control}] " +
            "undergradEnrollmentHeadcount=[${match.undergradEnrollmentHeadcount ?: NOT_REPORTED}] " +
            "admissionRateShare=[${match.admissionRateShare ?: NOT_REPORTED}] " +
            netPriceSegment(netPrices, match.id) +
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
      // The NET ruler, stated: the fit lens carries no student residency into
      // its query, exactly as it advertises no published bound
      // (`offerPublishedPrice = false`). Both facts are one fact, said twice
      // rather than left to a default nobody can see (RFC 169).
      .parse(root, config.searchLimit, PriceRuler.NetPrice)
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

  /**
   * What this run knows about the matched colleges' net prices: the figures it
   * read (a college with no row is simply absent from [Read.byCollege]), or the
   * fact that the read was UNAVAILABLE to this run.
   *
   * Two states, because they are two different things to say. "This college has
   * no figure" is a claim about our data; "the read failed" is a claim about
   * this run. Collapsing them printed the first sentence for the second fact.
   */
  private sealed interface DigestNetPrices {
    data class Read(
      val byCollege: Map<CollegeId, DigestNetPrice>,
    ) : DigestNetPrices

    data object Unavailable : DigestNetPrices
  }

  /**
   * One digest net price and what KIND of number it is -- the tier resolved
   * WITH the row, inside the guarded read, rather than at render time.
   *
   * The two travel as one value so the digest loop cannot reach a stat whose
   * tier has not been resolved: resolving it in the loop put a throwing step
   * outside [netPricesForDigest]'s best-effort catch, where one bad stored row
   * halted a whole pass after a billed LLM call.
   */
  private data class DigestNetPrice(
    val stat: DatedStat,
    val assurance: AssuranceTier,
  )

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
