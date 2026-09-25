package ed.unicoach.coaching.synthesis

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.coaching.ForcedToolInput
import ed.unicoach.coaching.JsonParseFailure
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.ToolSchema
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.category
import ed.unicoach.coaching.forcedToolChoice
import ed.unicoach.coaching.readForcedTool
import ed.unicoach.coaching.toDisplay
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdvisoryLockDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.latestUpdatedAt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The per-student synthesis pass (RFC 93): reflects over a student's accumulated
 * model — distilled active claims, their college list, and calendar context — and
 * writes coach-owned commitments. Runs only in the queue worker; never on the
 * chat request path (RFC 43).
 *
 * The pass is three phases — a read transaction, the LLM call **outside any
 * transaction**, then a write transaction — mirroring [ExtractionService][ed.unicoach.coaching.extraction.ExtractionService]:
 * the multi-second provider call never pins a pooled connection or holds the
 * student advisory lock. Each transaction takes the student lock; it is released
 * between phases and re-acquired for the write. The student's synthesis freshness
 * marker (latest applied `synthesis_runs.created_at`) makes at-least-once
 * delivery cheap and no-ops a lost race.
 *
 * A plain `class` (not `open`): tests inject a fake [ChatProvider] rather than
 * override a method. [clock] is the sole date input the pass needs (the timing
 * lens reasons over "today"); the freshness gate and every lifecycle timestamp
 * are DB-clocked through `NOW()` defaults and `lastAppliedAt`.
 *
 * [budgetService] gates the pass (RFC 109): an exhausted student's pass skips in
 * the read phase, spending nothing and writing no run row. The parameter is
 * undefaulted so a root cannot wire an ungated pass by omission.
 */
class SynthesisService(
  private val database: Database,
  private val llmCallLog: LlmCallLog,
  private val config: SynthesisConfig,
  private val budgetService: BudgetService,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val logger = LoggerFactory.getLogger(SynthesisService::class.java)

  /**
   * Runs one synthesis pass over [studentId]. Returns a sealed [SynthesisResult]
   * the handler maps to a `JobResult`.
   */
  suspend fun synthesize(studentId: StudentId): SynthesisResult {
    val readout =
      try {
        readPhase(studentId)
      } catch (e: Exception) {
        logger.warn("synthesis read phase failed for student=[{}]", studentId.asString, e)
        return SynthesisResult.TransientFailure("read phase: ${e.message}", e)
      }

    return when (readout) {
      is ReadPhase.NoOp -> {
        SynthesisResult.Success
      }

      is ReadPhase.BudgetExhausted -> {
        SynthesisResult.SkippedBudgetExhausted(readout.studentId, readout.entitlement)
      }

      is ReadPhase.Ready -> {
        runLlmAndWrite(studentId, readout)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Read phase
  // ---------------------------------------------------------------------------

  private suspend fun readPhase(studentId: StudentId): ReadPhase =
    database.withConnection { session ->
      val studentResult = StudentsDao.findById(session, studentId, SoftDeleteScope.ALL)
      // A genuinely nonexistent student is a fast no-op success, not a retriable
      // failure: retrying the pass cannot conjure the row, so all maxAttempts would
      // be wasted. Same terminal treatment as the soft-deleted case below.
      if (studentResult.exceptionOrNull() is NotFoundException) return@withConnection ReadPhase.NoOp
      val student = studentResult.getOrThrow()
      // Soft-deleted student: skip as a no-op success (no run appended).
      if (student.deletedAt != null) return@withConnection ReadPhase.NoOp

      AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

      // Budget gate (RFC 109), taken under the lock so the verdict is consistent
      // with the pass it guards, and before any further read. A skip is a named
      // outcome, distinct from the anonymous NoOp: nothing to reflect on and
      // blocked from reflecting are different answers to "why did this student's
      // synthesis stop running".
      when (val verdict = budgetService.verdict(session, studentId).getOrThrow()) {
        is BudgetVerdict.Exhausted -> {
          return@withConnection ReadPhase.BudgetExhausted(studentId, verdict.entitlement)
        }

        // Not dead: this arm is what makes the `when` exhaustive, so a third
        // BudgetVerdict would fail to compile here instead of falling through as
        // "allowed to spend". Do not collapse it back to an `is` check.
        BudgetVerdict.Entitled -> {}
      }

      writeDeterministicNudges(session, studentId)

      val lastAppliedAt = SynthesisRunsDao.lastAppliedAt(session, studentId).getOrThrow()

      val activeClaims = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
      // Nothing distilled to reflect on.
      if (activeClaims.isEmpty()) return@withConnection ReadPhase.NoOp

      val listEntries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()

      // Freshness gate (read-phase-only): the newest model mutation across active
      // claims and list entries. If nothing changed since the last applied pass,
      // no-op before any LLM call.
      val freshness = (activeClaims + listEntries).latestUpdatedAt()
      if (lastAppliedAt != null && freshness != null && !freshness.isAfter(lastAppliedAt)) {
        return@withConnection ReadPhase.NoOp
      }

      val openCommitments = CommitmentsDao.listOpenByStudent(session, studentId).getOrThrow()
      // Open set saturated: do not pile up.
      if (openCommitments.size >= config.maxOpenCommitments) return@withConnection ReadPhase.NoOp

      val prompt =
        SystemPromptsDao
          .findByNameAndVersion(session, config.promptName, config.promptVersion)
          .getOrThrow()

      ReadPhase.Ready(
        student = student,
        lastAppliedAt = lastAppliedAt,
        activeClaims = activeClaims,
        listEntries = listEntries,
        openCommitments = openCommitments,
        prompt = prompt,
      )
    }

  // ---------------------------------------------------------------------------
  // The deterministic nudge steps (RFC 160, RFC 240)
  // ---------------------------------------------------------------------------

  /**
   * The deterministic OFFER nudges: the lens and the fixed template statement
   * written under it, bound in ONE place. A caller names the OFFER, so it cannot
   * hand one offer's lens the other offer's sentence — the two travelled as
   * independent arguments, and a transposed pair was type-legal.
   *
   * Both statements are `const val`, so these entries read compile-time
   * constants and no class-initialisation cycle is possible.
   */
  private enum class DeterministicNudge(
    val lens: CommitmentLens,
    val statement: String,
  ) {
    SHARE_REPORT(CommitmentLens.SHARE_REPORT, SHARE_NUDGE_STATEMENT),
    INVITE_SUPPORTER(CommitmentLens.INVITE_SUPPORTER, INVITE_NUDGE_STATEMENT),
  }

  /**
   * The deterministic nudge steps (RFC 160, RFC 240): boolean logic over DB
   * state, no LLM. Called HERE — after the student/budget gates, under the held
   * advisory lock, inside this read/write transaction — and before every
   * LLM-phase gate, so a pass whose LLM phases no-op on freshness (or on an
   * empty claim set, or at the open-set cap read against the post-insert state)
   * still writes an eligible nudge.
   *
   * One line per offer, because the read phase is a sequence of named gates and
   * each router belongs one level below it.
   */
  private fun writeDeterministicNudges(
    session: SqlSession,
    studentId: StudentId,
  ) {
    runShareNudgeStep(session, studentId)
    // The invite predicate REQUIRES a live share where the share predicate is
    // suppressed BY one, so no pass can write both and no opening can carry
    // both bullets.
    runInviteNudgeStep(session, studentId)
  }

  // ---------------------------------------------------------------------------
  // The share-nudge step (RFC 160)
  // ---------------------------------------------------------------------------

  /**
   * Why the share-nudge step did or did not fire for this student (RFC 160) —
   * the [BudgetVerdict] shape: a skip is a named outcome, so "why did my
   * student never get the invite?" is answerable from the log.
   */
  private sealed interface ShareNudgeVerdict {
    data object Eligible : ShareNudgeVerdict

    data class Suppressed(
      val reason: ShareNudgeSuppression,
    ) : ShareNudgeVerdict
  }

  /** The eight distinct reasons the share-nudge step declines to fire. */
  private enum class ShareNudgeSuppression {
    DISABLED,
    TOO_FEW_LIST_ENTRIES,
    LIVE_SHARE,
    OPTED_OUT,
    OPEN_SET_AT_CAP,
    OPEN_SHARE_NUDGE,
    COOLDOWN_NOT_ELAPSED,
    LIST_UNCHANGED_SINCE_LAST_NUDGE,
  }

  /**
   * The share-nudge eligibility decision (RFC 160 §2): config on;
   * ≥ [SHARE_NUDGE_MIN_LIST_ENTRIES] active list entries (the report is a
   * comparison — two schools make one); no live share (a student with a live
   * link needs no invitation); no `opted_out` share event ("never ask me
   * again" is forever); the open set below the cap and holding no open
   * `share_report` row; and, when a prior `share_report` commitment exists,
   * the re-nudge condition ([computeReNudgeSuppression]). Every negative answer is a
   * named [ShareNudgeVerdict.Suppressed] reason, never an anonymous false.
   */
  private fun computeShareNudgeVerdict(
    session: SqlSession,
    studentId: StudentId,
  ): ShareNudgeVerdict {
    if (!config.shareNudgeEnabled) return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.DISABLED)

    val listEntries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
    if (listEntries.size < SHARE_NUDGE_MIN_LIST_ENTRIES) {
      return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.TOO_FEW_LIST_ENTRIES)
    }

    if (CostReportSharesDao.findLiveByStudent(session, studentId).getOrThrow() != null) {
      return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.LIVE_SHARE)
    }
    if (ShareEventsDao.hasOptOut(session, studentId).getOrThrow()) {
      return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.OPTED_OUT)
    }

    val openCommitments = CommitmentsDao.listOpenByStudent(session, studentId).getOrThrow()
    if (openCommitments.size >= config.maxOpenCommitments) {
      return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.OPEN_SET_AT_CAP)
    }
    if (openCommitments.any { it.lens == CommitmentLens.SHARE_REPORT }) {
      return ShareNudgeVerdict.Suppressed(ShareNudgeSuppression.OPEN_SHARE_NUDGE)
    }

    val latest = CommitmentsDao.findLatestByStudentAndLens(session, studentId, CommitmentLens.SHARE_REPORT).getOrThrow()
    if (latest != null) {
      val suppression = computeReNudgeSuppression(latest, listEntries)
      if (suppression != null) return ShareNudgeVerdict.Suppressed(suppression)
    }
    return ShareNudgeVerdict.Eligible
  }

  /**
   * RFC 160's re-nudge condition against the most recent `share_report`
   * commitment, as the suppression it hits — or null when a repeat may fire:
   * the commitment is resolved (belt beside the caller's open-set check), the
   * cooldown has elapsed since it was created, AND some active entry was
   * touched since — so a repeat points at something new.
   */
  private fun computeReNudgeSuppression(
    latest: Commitment,
    listEntries: List<CollegeListEntry>,
  ): ShareNudgeSuppression? {
    // Exhaustive over the status, so a new CommitmentStatus member must decide
    // here whether it suppresses like OPEN or anchors the cooldown like the
    // resolved pair.
    when (latest.status) {
      CommitmentStatus.OPEN -> {
        return ShareNudgeSuppression.OPEN_SHARE_NUDGE
      }

      CommitmentStatus.FULFILLED, CommitmentStatus.DROPPED -> {}
    }
    val cooldownOver = latest.createdAt.plus(config.shareNudgeCooldown)
    if (Instant.now(clock).isBefore(cooldownOver)) return ShareNudgeSuppression.COOLDOWN_NOT_ELAPSED
    // The re-nudge must have something new to point at: some active entry
    // touched since the last nudge was written.
    if (listEntries.none { it.updatedAt.isAfter(latest.createdAt) }) {
      return ShareNudgeSuppression.LIST_UNCHANGED_SINCE_LAST_NUDGE
    }
    return null
  }

  /** The share-nudge router: the verdict decides between the write and the logged skip. */
  private fun runShareNudgeStep(
    session: SqlSession,
    studentId: StudentId,
  ) {
    when (val verdict = computeShareNudgeVerdict(session, studentId)) {
      ShareNudgeVerdict.Eligible -> {
        insertNudge(session, studentId, DeterministicNudge.SHARE_REPORT)
      }

      is ShareNudgeVerdict.Suppressed -> {
        logger.debug("share-nudge suppressed for student=[{}]: [{}]", studentId.asString, verdict.reason)
      }
    }
  }

  /**
   * Inserts ONE deterministic nudge commitment — [nudge]'s fixed template
   * statement under [nudge]'s own lens, never LLM text, and no support links (no
   * claims were reasoned over) — and returns the created row.
   *
   * One home for the write, because what distinguishes the offers is their
   * PREDICATE, not how the row is stored: the share nudge (RFC 160) and the
   * invite nudge (RFC 240) otherwise keep line-for-line copies of "a nudge is an
   * open EXPLICIT commitment with template text and no supports", and a third
   * offer lens keeps a third.
   *
   * The offer is ONE argument and the statement is not an argument at all: the
   * pair that must agree is bound on [DeterministicNudge], where no caller can
   * cross it.
   */
  private fun insertNudge(
    session: SqlSession,
    studentId: StudentId,
    nudge: DeterministicNudge,
  ): Commitment {
    val created =
      CommitmentsDao
        .create(
          session,
          NewCommitment(
            studentId = studentId,
            lens = nudge.lens,
            disclosure = CommitmentDisclosure.EXPLICIT,
            statement = nudge.statement,
          ),
        ).getOrThrow()
    logger.info(
      "nudge commitment [{}] lens=[{}] written for student=[{}]",
      created.id.asString,
      nudge.lens.value,
      studentId.asString,
    )
    return created
  }

  // ---------------------------------------------------------------------------
  // The invite-nudge step (RFC 240)
  // ---------------------------------------------------------------------------

  /**
   * Why the invite-nudge step did or did not fire for this student (RFC 240) —
   * the [ShareNudgeVerdict] shape, for the same reason: a skip is a named
   * outcome, so "why was my student never asked?" is answerable from the log.
   *
   * A SECOND pair of types and not a widened first pair: the two offers share no
   * condition but the opt-out, and one enum over both would have members that are
   * unreachable for one of them.
   */
  private sealed interface InviteNudgeVerdict {
    data object Eligible : InviteNudgeVerdict

    data class Suppressed(
      val reason: InviteNudgeSuppression,
    ) : InviteNudgeVerdict
  }

  /** The eight distinct reasons the invite-nudge step declines to fire. */
  private enum class InviteNudgeSuppression {
    DISABLED,
    NO_LIVE_SHARE,
    OPTED_OUT,
    ALREADY_LINKED,
    LIVE_INVITE,
    OPEN_SET_AT_CAP,
    OPEN_INVITE_NUDGE,
    COOLDOWN_NOT_ELAPSED,
  }

  /**
   * The invite-nudge eligibility decision (RFC 240 §2): config on; a LIVE
   * cost-report share (this offer is the second step, and only a student who
   * took the first is asked to take it); no `opted_out` share event — the SAME
   * durable never as the share nudge's, which is what makes "the opt-out covers
   * both offers" enforced where the offer is written rather than asked of the
   * model; nobody already linked; no live invite already out; and the open set
   * below the cap, holding no open `invite_supporter` row, and past the cooldown
   * on the last one.
   *
   * Condition 2 is the load-bearing one. The share nudge is suppressed BECAUSE a
   * live share exists; this one REQUIRES one, so the two predicates are mutually
   * exclusive by construction: no pass can write both rows, and no opening can
   * carry both bullets. "Alongside, not instead of" is enforced here, in code,
   * and not asked of the model.
   *
   * There is deliberately no list-change half (the share nudge's re-fire rule).
   * Condition 5 is this offer's "something new": a re-offer is only reachable
   * once the previous invite lapsed unused, was revoked, or was consumed and then
   * unlinked — each of which is itself the new fact. A list-change test would
   * suppress the one student who most needs asking again, the one whose first
   * invite expired untouched.
   */
  private fun computeInviteNudgeVerdict(
    session: SqlSession,
    studentId: StudentId,
  ): InviteNudgeVerdict {
    if (!config.inviteNudgeEnabled) return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.DISABLED)

    if (CostReportSharesDao.findLiveByStudent(session, studentId).getOrThrow() == null) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.NO_LIVE_SHARE)
    }
    if (ShareEventsDao.hasOptOut(session, studentId).getOrThrow()) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.OPTED_OUT)
    }

    if (SupporterStudentLinksDao.listForStudent(session, studentId).getOrThrow().isNotEmpty()) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.ALREADY_LINKED)
    }
    if (SupporterInvitesDao.findLiveByStudent(session, studentId).getOrThrow().isNotEmpty()) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.LIVE_INVITE)
    }

    val openCommitments = CommitmentsDao.listOpenByStudent(session, studentId).getOrThrow()
    if (openCommitments.size >= config.maxOpenCommitments) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.OPEN_SET_AT_CAP)
    }
    if (openCommitments.any { it.lens == CommitmentLens.INVITE_SUPPORTER }) {
      return InviteNudgeVerdict.Suppressed(InviteNudgeSuppression.OPEN_INVITE_NUDGE)
    }

    val latest =
      CommitmentsDao
        .findLatestByStudentAndLens(session, studentId, CommitmentLens.INVITE_SUPPORTER)
        .getOrThrow()
    if (latest != null) {
      val suppression = computeInviteReNudgeSuppression(latest)
      if (suppression != null) return InviteNudgeVerdict.Suppressed(suppression)
    }
    return InviteNudgeVerdict.Eligible
  }

  /**
   * RFC 240's re-offer condition against the most recent `invite_supporter`
   * commitment, as the suppression it hits — or null when a repeat may fire.
   * The cooldown is anchored on when the nudge was WRITTEN, like the share
   * nudge's, and there is no list-change half (see [computeInviteNudgeVerdict]).
   */
  private fun computeInviteReNudgeSuppression(latest: Commitment): InviteNudgeSuppression? {
    // Exhaustive over the status, so a new CommitmentStatus member must decide
    // here whether it suppresses like OPEN or anchors the cooldown like the
    // resolved pair. Belt beside the caller's open-set check.
    when (latest.status) {
      CommitmentStatus.OPEN -> {
        return InviteNudgeSuppression.OPEN_INVITE_NUDGE
      }

      CommitmentStatus.FULFILLED, CommitmentStatus.DROPPED -> {}
    }
    val cooldownOver = latest.createdAt.plus(config.inviteNudgeCooldown)
    if (Instant.now(clock).isBefore(cooldownOver)) return InviteNudgeSuppression.COOLDOWN_NOT_ELAPSED
    return null
  }

  /** The invite-nudge router: the verdict decides between the write and the logged skip. */
  private fun runInviteNudgeStep(
    session: SqlSession,
    studentId: StudentId,
  ) {
    when (val verdict = computeInviteNudgeVerdict(session, studentId)) {
      InviteNudgeVerdict.Eligible -> {
        insertNudge(session, studentId, DeterministicNudge.INVITE_SUPPORTER)
      }

      is InviteNudgeVerdict.Suppressed -> {
        logger.debug("invite-nudge suppressed for student=[{}]: [{}]", studentId.asString, verdict.reason)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LLM call (no transaction) + write phase
  // ---------------------------------------------------------------------------

  private suspend fun runLlmAndWrite(
    studentId: StudentId,
    ready: ReadPhase.Ready,
  ): SynthesisResult {
    val request =
      ChatRequest(
        model = config.model,
        system = ready.prompt.body,
        messages = buildPromptMessages(ready),
        maxTokens = config.maxTokens,
        tools = listOf(RECORD_SYNTHESIS_TOOL),
        toolChoice = forcedToolChoice(RECORD_SYNTHESIS_TOOL_NAME),
      )

    val loggedCall =
      try {
        llmCallLog.record(request)
      } catch (e: Exception) {
        logger.warn("synthesis provider call failed for student=[{}]", studentId.asString, e)
        return SynthesisResult.TransientFailure("provider call: ${e.message}", e)
      }

    return when (val terminal = loggedCall.terminal) {
      // No billed, usable call: nothing to account, no run row.
      is ChatEvent.Rejected -> {
        SynthesisResult.TransientFailure("provider rejected: ${terminal.reason}")
      }

      is ChatEvent.TransientFailure -> {
        SynthesisResult.TransientFailure("provider transient: ${terminal.reason}")
      }

      is ChatEvent.Completed -> {
        handleCompleted(studentId, ready, terminal, loggedCall.llmRequestId)
      }
    }
  }

  /**
   * Handles a billed `Completed` terminal: parses the output and either applies it
   * (write phase) or, on any parse failure, records the billed spend as a `failed`
   * run so the token ledger is never dropped.
   */
  private suspend fun handleCompleted(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    terminal: ChatEvent.Completed,
    llmRequestId: LlmRequestId,
  ): SynthesisResult {
    // A Completed call is billed regardless of what the tool input contains. The
    // forced tool's input object is the payload; a missing tool_use block is the
    // tier-A analogue of an unparseable envelope.
    val parseResult =
      when (val forced = readForcedTool(terminal.response, RECORD_SYNTHESIS_TOOL_NAME)) {
        is ForcedToolInput.Absent -> ParseResult.Failure(JsonParseFailure.NoToolUse(forced.stopReason, forced.excerpt))
        is ForcedToolInput.Present -> parseOutput(forced.input)
      }
    return when (parseResult) {
      is ParseResult.Failure -> {
        logger.warn(
          "unusable synthesis output for student=[{}]: [{}]",
          studentId.asString,
          parseResult.failure.toDisplay(),
        )
        writeFailedRun(studentId, ready, parseResult.failure, llmRequestId)
        SynthesisResult.TransientFailure("unusable synthesis output: ${parseResult.failure.toDisplay()}")
      }

      is ParseResult.Parsed -> {
        writePhase(studentId, ready, parseResult.output, llmRequestId)
      }
    }
  }

  /**
   * Writes a `failed` synthesis_run carrying the call's token usage (the spend is
   * recorded); the freshness marker does not advance. Re-checks the freshness
   * marker under the held write lock so a concurrent same-student pass that
   * already applied does not draw a duplicate `failed` row.
   */
  private suspend fun writeFailedRun(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    failure: JsonParseFailure,
    llmRequestId: LlmRequestId,
  ) {
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        if (lostRace(session, studentId, ready.lastAppliedAt)) return@withConnection
        appendRun(
          session,
          studentId,
          ready.prompt.id,
          SynthesisOutcome.Failed(failure.category, failure.toDisplay()),
          llmRequestId,
        )
      }
    } catch (e: Exception) {
      // A DB failure here must not overwrite the "unparseable synthesis output"
      // diagnostic the caller is about to surface with a generic worker retry.
      logger.warn("synthesis failed-run write failed for student=[{}]", studentId.asString, e)
    }
  }

  private suspend fun writePhase(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ParsedOutput,
    llmRequestId: LlmRequestId,
  ): SynthesisResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

        // Lost-race no-op: an applied run appeared since the read-phase snapshot.
        if (lostRace(session, studentId, ready.lastAppliedAt)) {
          return@withConnection SynthesisResult.Success
        }

        // Re-load the active claim set under the held lock; supports and stale-drop
        // key on this fresh set.
        val freshActive = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
        val activeIds = freshActive.map { it.id }.toSet()

        applyWrites(session, studentId, ready, parsed, activeIds, llmRequestId)
      }
    } catch (e: Exception) {
      logger.warn("synthesis write phase failed for student=[{}]", studentId.asString, e)
      SynthesisResult.TransientFailure("write phase: ${e.message}", e)
    }

  /**
   * Inserts up to `maxNewCommitmentsPerRun` proposed commitments (each with its
   * still-active support links), stale-drops open commitments whose entire
   * non-empty support set is inactive, and appends the `applied` run advancing
   * the freshness marker.
   */
  private fun applyWrites(
    session: SqlSession,
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ParsedOutput,
    activeIds: Set<ClaimId>,
    llmRequestId: LlmRequestId,
  ): SynthesisResult {
    val commitmentsWritten = createProposedCommitments(session, studentId, parsed, activeIds)
    val commitmentsDropped = staleDropOpenCommitments(session, ready.openCommitments, activeIds)
    appendRun(
      session,
      studentId,
      ready.prompt.id,
      SynthesisOutcome.Applied(commitmentsWritten, commitmentsDropped),
      llmRequestId,
    )
    return SynthesisResult.Success
  }

  /**
   * Creates up to `maxNewCommitmentsPerRun` proposed commitments, linking each to
   * its support claims filtered to the fresh active set. Returns the count created.
   */
  private fun createProposedCommitments(
    session: SqlSession,
    studentId: StudentId,
    parsed: ParsedOutput,
    activeIds: Set<ClaimId>,
  ): Int {
    var commitmentsWritten = 0
    for (spec in parsed.commitments.take(config.maxNewCommitmentsPerRun)) {
      val created =
        CommitmentsDao
          .create(
            session,
            NewCommitment(
              studentId = studentId,
              lens = spec.lens,
              disclosure = spec.disclosure,
              statement = spec.statement,
              triggerAt = spec.triggerAt,
            ),
          ).getOrThrow()
      for (claimId in spec.supports) {
        if (claimId in activeIds) {
          CommitmentSupportDao.link(session, created.id, claimId).getOrThrow()
        }
      }
      commitmentsWritten++
    }
    return commitmentsWritten
  }

  /**
   * Stale-drops each open commitment whose support set is non-empty and wholly
   * inactive (it has lost its basis); a commitment with no support is never
   * dropped. Returns the count dropped.
   */
  private fun staleDropOpenCommitments(
    session: SqlSession,
    openCommitments: List<Commitment>,
    activeIds: Set<ClaimId>,
  ): Int {
    var commitmentsDropped = 0
    for (open in openCommitments) {
      val support = CommitmentSupportDao.listClaimsForCommitment(session, open.id).getOrThrow()
      if (support.isNotEmpty() && support.none { it.id in activeIds }) {
        CommitmentsDao.drop(session, open.id, "stale_basis").getOrThrow()
        commitmentsDropped++
      }
    }
    return commitmentsDropped
  }

  /** Whether an applied run has appeared since the read-phase [snapshot] (a concurrent pass won). */
  private fun lostRace(
    session: SqlSession,
    studentId: StudentId,
    snapshot: Instant?,
  ): Boolean {
    val current = SynthesisRunsDao.lastAppliedAt(session, studentId).getOrThrow()
    return when {
      current == null -> false
      snapshot == null -> true
      else -> current.isAfter(snapshot)
    }
  }

  private fun appendRun(
    session: SqlSession,
    studentId: StudentId,
    systemPromptId: ed.unicoach.db.models.SystemPromptId,
    outcome: SynthesisOutcome,
    llmRequestId: LlmRequestId,
  ) {
    SynthesisRunsDao
      .append(
        session,
        NewSynthesisRun(
          studentId = studentId,
          outcome = outcome,
          systemPromptId = systemPromptId,
          llmRequestId = llmRequestId,
        ),
      ).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // Prompt assembly
  // ---------------------------------------------------------------------------

  private fun buildPromptMessages(ready: ReadPhase.Ready): List<ChatMessage> {
    val today = Instant.now(clock)
    val context =
      buildString {
        appendLine("# Today")
        appendLine(today.toString())
        appendLine()
        appendLine("# Student")
        appendLine("expectedHighSchoolGraduation=${ready.student.expectedHighSchoolGraduationDate.toIso()}")
        appendLine()
        appendLine("# Active claims")
        if (ready.activeClaims.isEmpty()) {
          appendLine("(none)")
        } else {
          for (claim in ready.activeClaims.take(config.maxClaims)) {
            appendLine(
              "- id=${claim.id.asString} kind=${claim.kind.value} topic=${claim.topic.value} confidence=${claim.confidence}: ${claim.statement}",
            )
          }
        }
        appendLine()
        appendLine("# College list")
        if (ready.listEntries.isEmpty()) {
          appendLine("(none)")
        } else {
          for (entry in ready.listEntries) {
            appendLine(
              "- collegeId=${entry.collegeId.asString} status=${entry.status.value} updatedAt=${entry.updatedAt}",
            )
          }
        }
        appendLine()
        appendLine("# Open commitments (do not restate)")
        if (ready.openCommitments.isEmpty()) {
          appendLine("(none)")
        } else {
          for (commitment in ready.openCommitments) {
            appendLine("- ${commitment.statement}")
          }
        }
      }
    return listOf(ChatMessage.text(ChatRole.USER, context))
  }

  // ---------------------------------------------------------------------------
  // Output parsing
  // ---------------------------------------------------------------------------

  /**
   * Extracts the commitments from the forced tool's `tool_use.input` object via
   * the JSON element DSL (the `service` module has no kotlinx-serialization
   * compiler plugin). The object always arrives structured (forced tool use, RFC
   * 104) — this is only the per-field enforcement point. Returns a
   * [ParseResult.Failure] naming the offending field on any missing, wrong-shape,
   * or enum-membership failure.
   */
  private fun parseOutput(root: JsonObject): ParseResult {
    val commitments = mutableListOf<CommitmentSpec>()
    // Distinguish "key absent" (a legitimately empty proposal set → empty array)
    // from "key present but not an array" (a structurally malformed output). The
    // latter is a BadField failure so it routes to writeFailedRun as a FAILED run,
    // never a silent zero-commitments APPLIED run. Keeps parseOutput total.
    val commitmentsElement = root["commitments"]
    val commitmentsArray =
      when (commitmentsElement) {
        null -> JsonArray(emptyList())
        is JsonArray -> commitmentsElement
        else -> return ParseResult.Failure(JsonParseFailure.BadField("commitments", "not an array"))
      }
    for (element in commitmentsArray) {
      val obj = element as? JsonObject ?: return ParseResult.Failure(JsonParseFailure.BadField("commitments[]", "not an object"))
      // Read each field via a safe cast to JsonPrimitive: a JsonObject/JsonArray
      // where a scalar is expected returns null (a BadField), never throws (`.jsonPrimitive`
      // would). Keeps parseOutput total so a malformed Completed reaches writeFailedRun.
      val lensRaw = (obj["lens"] as? JsonPrimitive)?.contentOrNull
      // A known-but-non-proposable lens (share_report) is rejected exactly like
      // an unknown one: the LLM may only propose the subset its schema offers
      // (RFC 160) — the nudge lens is written by deterministic code alone.
      val lens =
        lensRaw?.let { CommitmentLens.fromValue(it) }?.takeIf { it in LLM_PROPOSABLE_LENSES }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("lens", lensRaw ?: "missing"))
      val disclosureRaw = (obj["disclosure"] as? JsonPrimitive)?.contentOrNull
      val disclosure =
        disclosureRaw?.let { CommitmentDisclosure.fromValue(it) }
          ?: return ParseResult.Failure(JsonParseFailure.BadField("disclosure", disclosureRaw ?: "missing"))
      val statement =
        (obj["statement"] as? JsonPrimitive)?.takeIf { it.isString }?.content
          ?: return ParseResult.Failure(JsonParseFailure.BadField("statement", "missing or non-string"))
      if (statement.isBlank()) return ParseResult.Failure(JsonParseFailure.BadField("statement", "blank"))
      val triggerAt =
        when (val rawTrigger = (obj["triggerAt"] as? JsonPrimitive)?.contentOrNull) {
          null -> {
            null
          }

          else -> {
            parseTriggerAt(rawTrigger)
              ?: return ParseResult.Failure(JsonParseFailure.BadField("triggerAt", rawTrigger))
          }
        }
      val supports =
        obj["supports"]?.let { it as? JsonArray }?.let { arr ->
          arr.map { s ->
            val idRaw = (s as? JsonPrimitive)?.contentOrNull
            runCatching { ClaimId(java.util.UUID.fromString(idRaw)) }.getOrNull()
              ?: return ParseResult.Failure(JsonParseFailure.BadField("supports[]", idRaw ?: "null"))
          }
        } ?: emptyList()
      commitments.add(CommitmentSpec(lens, disclosure, statement, triggerAt, supports))
    }

    return ParseResult.Parsed(ParsedOutput(commitments))
  }

  /** Accepts an ISO-8601 instant, or a bare ISO date (interpreted as start-of-day UTC). */
  private fun parseTriggerAt(raw: String): Instant? =
    runCatching { Instant.parse(raw) }.getOrNull()
      ?: runCatching {
        java.time.LocalDate
          .parse(raw)
          .atStartOfDay(java.time.ZoneOffset.UTC)
          .toInstant()
      }.getOrNull()

  // ---------------------------------------------------------------------------
  // Internal carriers
  // ---------------------------------------------------------------------------

  private sealed interface ReadPhase {
    data object NoOp : ReadPhase

    data class BudgetExhausted(
      val studentId: StudentId,
      val entitlement: Entitlement,
    ) : ReadPhase

    data class Ready(
      val student: Student,
      val lastAppliedAt: Instant?,
      val activeClaims: List<Claim>,
      val listEntries: List<CollegeListEntry>,
      val openCommitments: List<Commitment>,
      val prompt: SystemPrompt,
    ) : ReadPhase
  }

  private data class ParsedOutput(
    val commitments: List<CommitmentSpec>,
  )

  private data class CommitmentSpec(
    val lens: CommitmentLens,
    val disclosure: CommitmentDisclosure,
    val statement: String,
    val triggerAt: Instant?,
    val supports: List<ClaimId>,
  )

  private sealed interface ParseResult {
    data class Parsed(
      val output: ParsedOutput,
    ) : ParseResult

    data class Failure(
      val failure: JsonParseFailure,
    ) : ParseResult
  }

  companion object {
    private const val RECORD_SYNTHESIS_TOOL_NAME = "record_synthesis"

    /**
     * The fixed share-nudge statement (RFC 160): template text, never LLM
     * output, in the money vocabulary. Delivery reads it into the opener like
     * any other explicit commitment.
     */
    const val SHARE_NUDGE_STATEMENT =
      "Their college list now has real cost figures. When the moment is right, " +
        "suggest sharing the family cost report with a parent — it is a live link, " +
        "revocable any time, and only shows what they have already seen."

    /**
     * The share nudge fires only for a list of at least this many active
     * entries: the report is a comparison, and two schools make one (RFC 160,
     * a product decision — a constant, not a tunable).
     */
    const val SHARE_NUDGE_MIN_LIST_ENTRIES = 2

    /**
     * The fixed invite-nudge statement (RFC 240): template text, never LLM
     * output, in the same register as [SHARE_NUDGE_STATEMENT]. Delivery reads it
     * into the opener like any other explicit commitment.
     */
    const val INVITE_NUDGE_STATEMENT =
      "They have already sent a parent the family cost report. When the moment is right, " +
        "offer to connect that parent properly — their own account and their own coach workspace, " +
        "and the student can end one supporter's access without ending anyone else's."

    /**
     * The lenses the synthesis LLM may propose through `record_synthesis`
     * (RFC 160): the tool schema enumerates exactly this set and parseOutput
     * rejects the rest. Built through an exhaustive when, so a new
     * [CommitmentLens] member does not compile until its proposability is
     * decided here — [CommitmentLens.SHARE_REPORT] is written by the
     * deterministic share-nudge step only, and [CommitmentLens.INVITE_SUPPORTER]
     * by the invite-nudge step only (RFC 240), so both are deliberately absent.
     */
    private val LLM_PROPOSABLE_LENSES: Set<CommitmentLens> =
      CommitmentLens.entries
        .filter { lens ->
          when (lens) {
            CommitmentLens.GAP,
            CommitmentLens.TIMING,
            CommitmentLens.CONTRADICTION,
            -> true

            CommitmentLens.SHARE_REPORT,
            CommitmentLens.INVITE_SUPPORTER,
            -> false
          }
        }.toSet()

    // Mirrors the fields parseOutput reads, enums enumerated from the domain
    // enums. Guidance, not a hard validator (tier A) — parseOutput enforces.
    // The lens set is the LLM-PROPOSABLE subset (LLM_PROPOSABLE_LENSES), not
    // the whole enum: the share_report and invite_supporter nudge lenses are
    // written by deterministic code only (RFC 160, RFC 240), so the schema never
    // offers them and parseOutput rejects them.
    private val RECORD_SYNTHESIS_TOOL: JsonObject =
      ToolSchema.tool(
        name = RECORD_SYNTHESIS_TOOL_NAME,
        description = "Record the coach commitments synthesized from the student's model.",
        inputSchema =
          ToolSchema.objectSchema(
            "commitments" to
              ToolSchema.arrayOf(
                ToolSchema.objectSchema(
                  "lens" to
                    ToolSchema.enum(*LLM_PROPOSABLE_LENSES.map { it.value }.toTypedArray()),
                  "disclosure" to
                    ToolSchema.enum(*CommitmentDisclosure.entries.map { it.value }.toTypedArray()),
                  "statement" to ToolSchema.string(),
                  "triggerAt" to ToolSchema.string(),
                  "supports" to ToolSchema.arrayOf(ToolSchema.string()),
                ),
              ),
          ),
      )
  }
}
