package ed.unicoach.coaching.synthesis

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.coaching.ForcedToolInput
import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.JsonParseFailure
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.ToolSchema
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.category
import ed.unicoach.coaching.coachToday
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
import ed.unicoach.db.dao.NationalMeritCyclesDao
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
import ed.unicoach.db.models.CycleWindow
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

      writeDeterministicNudges(session, student)

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
    PSAT_WINDOW(CommitmentLens.PSAT_WINDOW, PSAT_WINDOW_NUDGE_STATEMENT),
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
   *
   * It takes the [Student] ROW and not the id because one step needs a column of
   * it: the PSAT-window step (RFC 243) joins on her stored expected graduation
   * year, which is the competition's own name. Re-reading the row inside that
   * step would be a second read of a fact this pass already holds.
   */
  private fun writeDeterministicNudges(
    session: SqlSession,
    student: Student,
  ) {
    val studentId = student.id
    runShareNudgeStep(session, studentId)
    // The invite predicate REQUIRES a live share where the share predicate is
    // suppressed BY one, so no pass can write both and no opening can carry
    // both bullets.
    runInviteNudgeStep(session, studentId)
    // The PSAT-window step (RFC 243) is INDEPENDENT of both, deliberately. The
    // two offers above are the same KIND of thing -- share the report, invite the
    // parent -- so raising both at once reads as one muddled ask, which is why
    // their predicates exclude each other. A closing test window is a different
    // subject with a date on it, and a student inside her 30-day lead who also
    // becomes share-eligible may carry both bullets: the opener's instruction is
    // to raise what is relevant, naturally, and deferring a dated sitting to keep
    // an opener tidy is the wrong trade. Cross-suppression would also change when
    // RFC 240's landed nudge fires, which this run did not ask Ian for.
    runPsatWindowNudgeStep(session, student)
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
  // The PSAT/NMSQT window nudge (RFC 243)
  // ---------------------------------------------------------------------------

  /**
   * Why the PSAT-window step did or did not fire for this student (RFC 243) --
   * the [ShareNudgeVerdict] shape one step along, for the same reason: a skip is
   * a named outcome, so "why did my student never hear about the window?" is
   * answerable from the log.
   */
  internal sealed interface PsatWindowNudgeVerdict {
    data object Eligible : PsatWindowNudgeVerdict

    data class Suppressed(
      val reason: PsatWindowNudgeSuppression,
    ) : PsatWindowNudgeVerdict
  }

  /**
   * The nine distinct reasons the PSAT-window step declines to fire, in the
   * order the step reaches them.
   *
   * INTERNAL, AND READ BY NAME IN THE SUITE. Every one of these is a guard that
   * writes nothing, so "no row was written" is the same observation for all
   * nine -- and three of them were SHADOWED by a later guard, which means the
   * guard could be deleted and the suite stayed green. The committed corpus
   * cannot isolate [NOT_QUALIFYING_YEAR] with data at all: it needs a dated
   * cycle whose window is near AND a student of that class who is not in the
   * qualifying grade, and the year of school is DERIVED from her class and
   * today. A guard no test can fail is indistinguishable from an absent one.
   *
   * THERE IS NO COOLDOWN MEMBER, and that is the design rather than an omission.
   * The qualifying sitting happens once in a student's life, so
   * [PsatWindowNudgeSuppression.ALREADY_RAISED] -- one `psat_window` row in ANY
   * status -- is the whole repeat rule: a dropped nudge is a student who did not
   * want it, and that answer stands.
   */
  internal enum class PsatWindowNudgeSuppression {
    DISABLED,
    NO_CYCLE_ROW,
    NOT_QUALIFYING_YEAR,
    CYCLE_EXPIRED,
    CYCLE_UNDATED,
    WINDOW_NOT_NEAR,
    WINDOW_PASSED,
    ALREADY_RAISED,
    OPEN_SET_AT_CAP,
  }

  /**
   * The PSAT-window eligibility decision (RFC 243): config on; a cycle row for
   * her own class; that row in the year of school whose sitting qualifies; that
   * row still BELIEVED today (`expires_on`); the row DATED; today inside
   * [SynthesisConfig.psatWindowNudgeLead] of the window opening and not past its
   * close; no `psat_window` row for her in any status; and the open set below
   * the cap. Every negative answer is a named
   * [PsatWindowNudgeVerdict.Suppressed] reason, never an anonymous false.
   *
   * THE CYCLE ROW IS READ BEFORE THE YEAR OF SCHOOL IS JUDGED, because the
   * qualifying grade is the ROW's -- a published rule of the programme, stored
   * so nothing speaks it from memory -- and an 11 typed here would be a second
   * opinion about it. Both reasons are true of a student who has neither, so
   * the difference is which one the log names, and the row we do not hold is
   * the one that stopped the step.
   *
   * OUR OWN FACTS ARE DECIDED BEFORE THE CORPUS'S FRESHNESS. Her year of school
   * is derived from HER STORED GRADUATION DATE (RFC 186) and is true whether or
   * not we still believe two publishers' pages, so a student whose qualifying
   * sitting is not this year hears that as the reason. Expiry is a statement
   * about trusting the ROW'S DATES, so it sits immediately before the first
   * check that reads one.
   *
   * IT COSTS NOTHING FOR A STUDENT THE CONFIG EXCLUDES, and at most three
   * statements for anyone else: her cycle row, her latest `psat_window`
   * commitment, and her open set -- each read only once the cheaper answers have
   * not settled it.
   *
   * [ed.unicoach.coaching.coachToday] AND NEVER `LocalDate.now(clock)`: the zone
   * is ours, not the injected clock's, and a sweep reading UTC-today would move
   * every boundary here by a day for an East Coast family between 20:00 and
   * midnight.
   */
  internal fun computePsatWindowNudgeVerdict(
    session: SqlSession,
    student: Student,
  ): PsatWindowNudgeVerdict {
    if (!config.psatWindowNudgeEnabled) return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.DISABLED)

    val today = clock.coachToday()
    val studentId = student.id
    // HER OWN STORED GRADUATION YEAR IS THE COMPETITION'S NAME: NMSC names the
    // 2028 competition for the class of 2028, so the join is her own stored year
    // and nothing inferred.
    val graduationYear = student.expectedHighSchoolGraduationDate.year.value
    val cycle =
      NationalMeritCyclesDao.findByCompetitionYear(session, graduationYear).getOrThrow()
        ?: return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.NO_CYCLE_ROW)

    val yearOfSchool = HighSchoolYear.of(today, student.expectedHighSchoolGraduationDate)
    if (yearOfSchool.grade != cycle.qualifyingGrade) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.NOT_QUALIFYING_YEAR)
    }

    // A READING WE NO LONGER BELIEVE IS NOT A WINDOW: `expires_on` is the day we
    // stop believing the two publishers' pages, so a nudge fired past it would
    // be raising a dated sitting out of a stale reading -- the refusal RFC 197's
    // calendar makes for itself, and the one [NationalMeritService] makes for
    // the same row on the reactive side.
    //
    // IT IS ANSWERED AFTER HER YEAR OF SCHOOL AND BEFORE EVERY DATE BELOW. The
    // year of school comes from her own stored graduation date, so it is ours
    // and it is settled; from here down every check reads one of this row's
    // dates, and this is the gate that says they may be read at all.
    if (today > cycle.expiresOn) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.CYCLE_EXPIRED)
    }

    // AN UNDATED CYCLE CANNOT BE "NEAR". The publisher describes the window in
    // words long before it dates it (measured: no cycle after 2028 is dated), and
    // a nudge that fired on a description would be raising a deadline we cannot
    // name -- which is the one thing this corpus refuses to do.
    val window =
      cycle.window as? CycleWindow.Dated
        ?: return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.CYCLE_UNDATED)

    if (today < window.opensOn.minus(config.psatWindowNudgeLead)) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.WINDOW_NOT_NEAR)
    }
    // A DELIBERATE REFUSAL AND NOT AN OMISSION. Nothing in the product stores
    // whether she sat the test, so an unprompted "you may have missed it, and
    // there is a route" would be a guess about her life. The alternate-entry
    // answer is fully available the moment she asks, through the tool's
    // `alternate_entry_open` arm; raising it unasked is not.
    if (today > window.closesOn) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.WINDOW_PASSED)
    }

    // IN ANY STATUS, which is exactly what `findLatestByStudentAndLens`
    // guarantees (RFC 160) -- so this needs no new DAO method and no second
    // definition of "already raised". Open, fulfilled or dropped, one row is the
    // whole repeat rule.
    if (CommitmentsDao.findLatestByStudentAndLens(session, studentId, CommitmentLens.PSAT_WINDOW).getOrThrow() != null) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.ALREADY_RAISED)
    }
    if (CommitmentsDao.listOpenByStudent(session, studentId).getOrThrow().size >= config.maxOpenCommitments) {
      return PsatWindowNudgeVerdict.Suppressed(PsatWindowNudgeSuppression.OPEN_SET_AT_CAP)
    }
    return PsatWindowNudgeVerdict.Eligible
  }

  /**
   * The PSAT-window step (RFC 243): decide, then either write the one nudge or
   * log the named reason. The WRITE is [insertNudge] with this step's own
   * [DeterministicNudge] entry, not a third copy of "a nudge is an open EXPLICIT
   * commitment with template text and no supports" -- RFC 240 bound each lens to
   * its own statement in one place precisely because the pair travelling as two
   * arguments made a transposed pair type-legal.
   */
  private fun runPsatWindowNudgeStep(
    session: SqlSession,
    student: Student,
  ) {
    val studentId = student.id
    when (val verdict = computePsatWindowNudgeVerdict(session, student)) {
      PsatWindowNudgeVerdict.Eligible -> {
        insertNudge(session, studentId, DeterministicNudge.PSAT_WINDOW)
      }

      is PsatWindowNudgeVerdict.Suppressed -> {
        logger.debug("psat-window-nudge suppressed for student=[{}]: [{}]", studentId.asString, verdict.reason)
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
     * The fixed PSAT/NMSQT window statement (RFC 243): template text, never LLM
     * output. Delivery reads it into the opener like any other explicit
     * commitment.
     *
     * IT SAYS WHAT THE MOMENT IS AND NOT WHAT THE MONEY IS. Every figure -- the
     * window's own dates, the base rate, what any school pays -- comes from the
     * `psat_national_merit` tool when the coach raises this, because a statement
     * frozen in code cannot go stale beside a corpus that expires.
     */
    const val PSAT_WINDOW_NUDGE_STATEMENT =
      "The PSAT/NMSQT window is close, and they are in the one year of school whose sitting counts for National " +
        "Merit. When the moment is right, raise it once: what the test is worth, that their school decides the day " +
        "and they do not register themselves, and that no college ever sees the score."

    /**
     * The lenses the synthesis LLM may propose through `record_synthesis`
     * (RFC 160): the tool schema enumerates exactly this set and parseOutput
     * rejects the rest. Built through an exhaustive when, so a new
     * [CommitmentLens] member does not compile until its proposability is
     * decided here — [CommitmentLens.SHARE_REPORT] is written by the
     * deterministic share-nudge step only, [CommitmentLens.INVITE_SUPPORTER] by
     * the invite-nudge step only (RFC 240), and [CommitmentLens.PSAT_WINDOW] by
     * the PSAT-window step only (RFC 243) -- a model that could propose that one
     * could raise it for anyone, where the deterministic step raises it for a
     * junior whose single qualifying sitting is near. All three are deliberately
     * absent.
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
            CommitmentLens.PSAT_WINDOW,
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
