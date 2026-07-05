package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentBlocks
import ed.unicoach.chat.TokenUsage
import ed.unicoach.chat.chat
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.util.truncateForLog
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
import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.latestUpdatedAt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
 */
class FitLensService(
  private val database: Database,
  private val chatProvider: ChatProvider,
  private val collegeSearchService: CollegeSearchService,
  private val config: FitLensConfig,
) {
  private val logger = LoggerFactory.getLogger(FitLensService::class.java)

  private val json = Json { ignoreUnknownKeys = true }

  private companion object {
    /**
     * Upper bound on a rationale's length, mirroring the
     * `fit_suggestions_rationale_length_check` DB CHECK (2048 chars). Enforced in
     * [parseReason] so an over-length rationale fails the pass instead of tripping
     * the CHECK at write time.
     */
    private const val MAX_RATIONALE_CHARS = 2_048
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
      if (studentResult.exceptionOrNull() is NotFoundException) return@withConnection ReadPhase.Skip("student not found")
      val student = studentResult.getOrThrow()
      if (student.deletedAt != null) return@withConnection ReadPhase.Skip("student soft-deleted")

      AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

      val activeClaims = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
      // minClaims floor: too little signal to search on.
      if (activeClaims.size < config.minClaims) return@withConnection ReadPhase.Skip("below minClaims floor")

      val listEntries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
      val suggestedIds = FitSuggestionsDao.listSuggestedCollegeIds(session, studentId).getOrThrow()

      // Freshness gate: no model change since the last applied run.
      val lastAppliedAt = FitLensRunsDao.lastAppliedAt(session, studentId).getOrThrow()
      val freshness = (activeClaims + listEntries).latestUpdatedAt()
      if (lastAppliedAt != null && freshness != null && !freshness.isAfter(lastAppliedAt)) {
        return@withConnection ReadPhase.Skip("model unchanged since last applied run")
      }

      // Failure circuit breaker: stop re-billing a model state that has failed to
      // parse maxConsecutiveFailures times running.
      val consecutiveFailures = FitLensRunsDao.consecutiveFailuresSince(session, studentId).getOrThrow()
      if (consecutiveFailures >= config.maxConsecutiveFailures) {
        return@withConnection ReadPhase.Skip("failure circuit breaker open ($consecutiveFailures failures)")
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
    val queryCompleted =
      when (val outcome = runChat(studentId, "formulate", buildQueryRequest(ready))) {
        is ChatOutcome.Bail -> return outcome.result
        is ChatOutcome.Completed -> outcome.event
      }
    val queryUsage = queryCompleted.response.usage
    val modelResolved = queryCompleted.response.modelResolved

    val queryRaw = ContentBlocks.renderText(queryCompleted.response.content)
    val query =
      when (val parsed = parseQuery(queryRaw)) {
        is QueryParse.Failure -> return onQueryParseFailure(studentId, ready, parsed, queryRaw, queryUsage, modelResolved)
        is QueryParse.Parsed -> parsed.query
      }

    // Retrieve — run the query directly in the worker (no shared txn).
    val matches =
      try {
        collegeSearchService.search(query.copy(limit = config.searchLimit)).getOrThrow()
      } catch (e: Exception) {
        logger.warn("fit-lens retrieval failed for student=[{}]", studentId.asString, e)
        return FitLensResult.TransientFailure("retrieval: ${e.message}", e)
      }
    if (matches.isEmpty()) return onZeroMatches(studentId, ready, queryUsage, modelResolved)

    // LLM call #2 — reason over the real matches.
    val reasonCompleted =
      when (val outcome = runChat(studentId, "reason", buildReasonRequest(ready, matches))) {
        is ChatOutcome.Bail -> return outcome.result
        is ChatOutcome.Completed -> outcome.event
      }
    // Both calls are billed on a completed pass: the run's token columns sum them.
    val totalUsage = sumUsage(queryUsage, reasonCompleted.response.usage)
    val reasonRaw = ContentBlocks.renderText(reasonCompleted.response.content)
    val matchIds = matches.map { it.id }.toSet()

    return when (val parsed = parseReason(reasonRaw, matchIds)) {
      is ReasonParse.Failure -> onReasonParseFailure(studentId, ready, parsed, reasonRaw, matches.size, totalUsage, modelResolved)
      is ReasonParse.Empty -> onReasonEmpty(studentId, ready, matches.size, totalUsage, modelResolved)
      is ReasonParse.Chosen -> writePhase(studentId, ready, parsed.collegeId, parsed.rationale, matches.size, totalUsage, modelResolved)
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
    val event =
      try {
        chatProvider.chat(request)
      } catch (e: Exception) {
        logger.warn("fit-lens [{}] call failed for student=[{}]", label, studentId.asString, e)
        return ChatOutcome.Bail(FitLensResult.TransientFailure("$label call: ${e.message}", e))
      }
    return when (event) {
      is ChatEvent.Rejected -> ChatOutcome.Bail(FitLensResult.TransientFailure("$label rejected: ${event.reason}"))
      is ChatEvent.TransientFailure -> ChatOutcome.Bail(FitLensResult.TransientFailure("$label transient: ${event.reason}"))
      is ChatEvent.Completed -> ChatOutcome.Completed(event)
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
    queryRaw: String,
    queryUsage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult {
    logger.warn(
      "unparseable fit-lens CollegeQuery for student=[{}]: [{}]; raw=[{}]",
      studentId.asString,
      parsed.detail,
      truncateForLog(queryRaw),
    )
    return writeFailedRun(
      studentId,
      ready,
      category = parsed.category,
      reason = parsed.detail,
      matchesConsidered = null,
      usage = queryUsage,
      modelResolved = modelResolved,
    )
  }

  /**
   * Zero-match: a valid Skipped outcome (nothing novel to reason over). Tokens
   * spent, so an applied row with `suggestions_written = 0` advances freshness.
   */
  private suspend fun onZeroMatches(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    queryUsage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult {
    val write =
      writeAppliedRun(studentId, ready, suggestionsWritten = 0, matchesConsidered = 0, usage = queryUsage, modelResolved = modelResolved)
    return write ?: FitLensResult.Skipped("zero search matches")
  }

  private suspend fun onReasonParseFailure(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ReasonParse.Failure,
    reasonRaw: String,
    matchesConsidered: Int,
    totalUsage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult {
    logger.warn(
      "unusable fit-lens reason output for student=[{}]: [{}]; raw=[{}]",
      studentId.asString,
      parsed.detail,
      truncateForLog(reasonRaw),
    )
    return writeFailedRun(
      studentId,
      ready,
      category = parsed.category,
      reason = parsed.detail,
      matchesConsidered = matchesConsidered,
      usage = totalUsage,
      modelResolved = modelResolved,
    )
  }

  /** The model found nothing that genuinely fits: a valid Skipped/applied with no suggestion. */
  private suspend fun onReasonEmpty(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    matchesConsidered: Int,
    totalUsage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult {
    val write =
      writeAppliedRun(
        studentId,
        ready,
        suggestionsWritten = 0,
        matchesConsidered = matchesConsidered,
        usage = totalUsage,
        modelResolved = modelResolved,
      )
    return write ?: FitLensResult.Skipped("reason returned no fit")
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
    usage: TokenUsage,
    modelResolved: String?,
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

        appendRun(session, studentId, ready, FitLensOutcome.APPLIED, suggestionsWritten, matchesConsidered, usage, modelResolved)
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
    usage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult? =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        appendRun(session, studentId, ready, FitLensOutcome.APPLIED, suggestionsWritten, matchesConsidered, usage, modelResolved)
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
    category: FitLensFailureCategory,
    reason: String,
    matchesConsidered: Int?,
    usage: TokenUsage,
    modelResolved: String?,
  ): FitLensResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        appendRun(
          session,
          studentId,
          ready,
          FitLensOutcome.FAILED,
          0,
          matchesConsidered,
          usage,
          modelResolved,
          failureCategory = category,
          failureReason = reason,
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
    suggestionsWritten: Int,
    matchesConsidered: Int?,
    usage: TokenUsage,
    modelResolved: String?,
    failureCategory: FitLensFailureCategory? = null,
    failureReason: String? = null,
  ) {
    FitLensRunsDao
      .append(
        session,
        NewFitLensRun(
          studentId = studentId,
          outcome = outcome,
          querySystemPromptId = ready.queryPrompt.id,
          reasonSystemPromptId = ready.reasonPrompt.id,
          provider = chatProvider.id,
          modelResolved = modelResolved,
          suggestionsWritten = suggestionsWritten,
          matchesConsidered = matchesConsidered,
          inputTokens = usage.inputTokens,
          outputTokens = usage.outputTokens,
          cacheReadTokens = usage.cacheReadTokens,
          cacheWriteTokens = usage.cacheWriteTokens,
          failureCategory = failureCategory,
          failureReason = failureReason,
        ),
      ).getOrThrow()
  }

  /** Sums two calls' token usage column-by-column, null-safe (null + n = n). */
  private fun sumUsage(
    a: TokenUsage,
    b: TokenUsage,
  ): TokenUsage =
    TokenUsage(
      inputTokens = sumNullable(a.inputTokens, b.inputTokens),
      outputTokens = sumNullable(a.outputTokens, b.outputTokens),
      cacheReadTokens = sumNullable(a.cacheReadTokens, b.cacheReadTokens),
      cacheWriteTokens = sumNullable(a.cacheWriteTokens, b.cacheWriteTokens),
    )

  private fun sumNullable(
    a: Int?,
    b: Int?,
  ): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

  // ---------------------------------------------------------------------------
  // Prompt assembly
  // ---------------------------------------------------------------------------

  private fun buildQueryRequest(ready: ReadPhase.Ready): ChatRequest =
    ChatRequest(
      model = config.model,
      system = ready.queryPrompt.body,
      messages = listOf(ChatMessage.text(ChatRole.USER, buildQueryContext(ready))),
      maxTokens = config.queryMaxTokens,
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
      for (match in matches) {
        appendLine(
          "- collegeId=${match.id.asString} name=${match.name} city=${match.city} state=${match.state} " +
            "control=${match.control} undergradEnrollment=${match.undergradEnrollment} admissionRate=${match.admissionRate} " +
            "netPrice=${match.netPrice} graduationRate=${match.graduationRate} medianEarnings=${match.medianEarnings} " +
            "programs=${match.programTitles.joinToString("; ")}",
        )
      }
    }

  // ---------------------------------------------------------------------------
  // Output parsing (JSON element DSL — the service module has no serialization plugin)
  // ---------------------------------------------------------------------------

  /**
   * Parses call #1's strict-JSON [CollegeQuery] filter object defensively. Any
   * structural or type-invalid field fails the pass (no partial acceptance). The
   * model never sets `limit`; the service sets it after parse.
   */
  private fun parseQuery(raw: String): QueryParse {
    val root =
      try {
        json.parseToJsonElement(raw.trim()) as? JsonObject
          ?: return QueryParse.Failure(FitLensFailureCategory.MALFORMED_OUTPUT, "root is not a JSON object")
      } catch (e: Exception) {
        return QueryParse.Failure(FitLensFailureCategory.MALFORMED_OUTPUT, "malformed JSON: ${e.message}")
      }

    fun stringField(name: String): String? = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // An absent key is a legitimately unconstrained axis (null). A present key
    // that is not the expected scalar type fails the whole parse.
    fun intField(name: String): Result<Int?> {
      val el = root[name] ?: return Result.success(null)
      val v = (el as? JsonPrimitive)?.intOrNull ?: return Result.failure(IllegalArgumentException(name))
      return Result.success(v)
    }

    fun doubleField(name: String): Result<Double?> {
      val el = root[name] ?: return Result.success(null)
      val v = (el as? JsonPrimitive)?.doubleOrNull ?: return Result.failure(IllegalArgumentException(name))
      return Result.success(v)
    }

    fun stringList(name: String): Result<List<String>?> {
      val el = root[name] ?: return Result.success(null)
      val arr = el as? JsonArray ?: return Result.failure(IllegalArgumentException(name))
      val out =
        arr.map { e ->
          (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return Result.failure(IllegalArgumentException("$name[]"))
        }
      return Result.success(out)
    }

    fun intList(name: String): Result<List<Int>?> {
      val el = root[name] ?: return Result.success(null)
      val arr = el as? JsonArray ?: return Result.failure(IllegalArgumentException(name))
      val out =
        arr.map { e ->
          (e as? JsonPrimitive)?.intOrNull ?: return Result.failure(IllegalArgumentException("$name[]"))
        }
      return Result.success(out)
    }

    return try {
      QueryParse.Parsed(
        CollegeQuery(
          cipPrefix = stringField("cipPrefix"),
          states = stringList("states").getOrThrow(),
          region = intField("region").getOrThrow(),
          locales = intList("locales").getOrThrow(),
          control = intList("control").getOrThrow(),
          minUndergradEnrollment = intField("minUndergradEnrollment").getOrThrow(),
          maxUndergradEnrollment = intField("maxUndergradEnrollment").getOrThrow(),
          minAdmissionRate = doubleField("minAdmissionRate").getOrThrow(),
          maxAdmissionRate = doubleField("maxAdmissionRate").getOrThrow(),
          maxNetPrice = intField("maxNetPrice").getOrThrow(),
          minGraduationRate = doubleField("minGraduationRate").getOrThrow(),
          // Overwritten by the service before retrieval; a placeholder here.
          limit = config.searchLimit,
        ),
      )
    } catch (e: IllegalArgumentException) {
      QueryParse.Failure(FitLensFailureCategory.MALFORMED_OUTPUT, "type-invalid field [${e.message}]")
    }
  }

  /**
   * Parses call #2's strict-JSON output. An empty object `{}` (or an object with
   * a null/absent collegeId) is [ReasonParse.Empty] (nothing fits). A named
   * `collegeId` must be present in [matchIds]; one outside the set, or a blank
   * rationale, is [ReasonParse.Failure].
   */
  private fun parseReason(
    raw: String,
    matchIds: Set<CollegeId>,
  ): ReasonParse {
    val root =
      try {
        json.parseToJsonElement(raw.trim()) as? JsonObject
          ?: return ReasonParse.Failure(FitLensFailureCategory.MALFORMED_OUTPUT, "root is not a JSON object")
      } catch (e: Exception) {
        return ReasonParse.Failure(FitLensFailureCategory.MALFORMED_OUTPUT, "malformed JSON: ${e.message}")
      }

    val collegeIdRaw = (root["collegeId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (collegeIdRaw == null || collegeIdRaw.isBlank()) {
      // An empty {} or an absent/null collegeId: nothing genuinely fits.
      return ReasonParse.Empty
    }

    val collegeId =
      runCatching { CollegeId(java.util.UUID.fromString(collegeIdRaw)) }.getOrNull()
        ?: return ReasonParse.Failure(FitLensFailureCategory.INVALID_CONTENT, "collegeId is not a UUID: [$collegeIdRaw]")
    if (collegeId !in matchIds) {
      return ReasonParse.Failure(
        FitLensFailureCategory.INVALID_CONTENT,
        "collegeId [$collegeIdRaw] is outside the retrieved match set",
      )
    }

    val rationale = (root["rationale"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (rationale == null || rationale.isBlank()) {
      return ReasonParse.Failure(FitLensFailureCategory.INVALID_CONTENT, "rationale is missing or blank")
    }
    // Belt-and-suspenders: reject an over-length rationale here as a parse failure
    // (a Failed pass) rather than letting it reach the write and trip
    // fit_suggestions_rationale_length_check as a swallowed no-op. The DB CHECK
    // remains the backstop.
    if (rationale.length > MAX_RATIONALE_CHARS) {
      return ReasonParse.Failure(
        FitLensFailureCategory.INVALID_CONTENT,
        "rationale exceeds $MAX_RATIONALE_CHARS chars (${rationale.length})",
      )
    }

    return ReasonParse.Chosen(collegeId, rationale)
  }

  // ---------------------------------------------------------------------------
  // Internal carriers
  // ---------------------------------------------------------------------------

  /** The outcome of one LLM call: a billed [Completed] event, or a [Bail] that short-circuits [runPass]. */
  private sealed interface ChatOutcome {
    data class Completed(
      val event: ChatEvent.Completed,
    ) : ChatOutcome

    data class Bail(
      val result: FitLensResult,
    ) : ChatOutcome
  }

  private sealed interface ReadPhase {
    data class Skip(
      val reason: String,
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
      val category: FitLensFailureCategory,
      val detail: String,
    ) : QueryParse
  }

  private sealed interface ReasonParse {
    data class Chosen(
      val collegeId: CollegeId,
      val rationale: String,
    ) : ReasonParse

    data object Empty : ReasonParse

    data class Failure(
      val category: FitLensFailureCategory,
      val detail: String,
    ) : ReasonParse
  }
}
