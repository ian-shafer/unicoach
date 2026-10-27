package ed.unicoach.coaching

import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ChatTool
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.coaching.collegelist.CollegeListBlock
import ed.unicoach.coaching.collegelist.EntryWithCollegeName
import ed.unicoach.coaching.collegelist.readActiveListInSession
import ed.unicoach.coaching.testing.TestScoreCopy
import ed.unicoach.coaching.testing.TestScores
import ed.unicoach.coaching.testing.readTestScoresInSession
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.nonMoneyAnswerStatus
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoParty
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.FitSuggestionForOpener
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.SystemPromptId
import ed.unicoach.db.models.usDate
import ed.unicoach.error.FieldError
import kotlinx.serialization.json.JsonObject
import java.time.InstantSource
import java.time.LocalDate

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
 * A turn's synchronous pre-flight transaction validates, gates and snapshots
 * replay history; the provider calls themselves run in [CoachTurnLoop] (RFC 258,
 * shared with the supporter's coach), which this service drives with a
 * [StudentTurnParty]. The response side of the log is owned entirely by
 * [LlmCallLog] (RFC 106); neither this service nor the loop writes a response
 * row.
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
  llmCallLog: LlmCallLog,
  private val config: CoachingConfig,
  private val budgetService: BudgetService,
  private val tools: ToolRegistry = ToolRegistry(emptyList()),
  // The instant "today" is read at (RFC 186). Defaulted like the sibling clock
  // sites (SynthesisService, FederalAidPolicyService): production passes none
  // and a test passes a fixed one.
  //
  // It is an InstantSource and not a Clock, which those two are, and the
  // difference is the point: this service takes the INSTANT and supplies the
  // ZONE itself ([COACH_ZONE]), where they honour their clock's zone. A `Clock`
  // parameter would advertise a zone it then discarded, and the first draft of
  // this code did exactly that -- `LocalDate.now(clock)` read the injected
  // clock's zone, so a test pinning a UTC clock proved the opposite of what it
  // claimed. A type that carries no zone cannot be read that way.
  private val clock: InstantSource = InstantSource.system(),
) {
  // The provider loop, shared with the supporter's coach (RFC 258): this class
  // decides WHAT a student turn is (ownership, budget, the composed system
  // text) and the loop runs it.
  private val loop = CoachTurnLoop(database, llmCallLog, TurnSettings(config.model, config.maxTokens, config.maxToolRounds))

  companion object {
    const val MESSAGE_MAX_LENGTH = 100_000
    const val NAME_DERIVATION_MAX = 80
    internal const val MESSAGE_FIELD = "message"
    private const val NAME_FIELD = "name"
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
        if (listing == null || !isStudentsOwn(listing.convo, studentId)) {
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
    val messageError = coachMessageError(message)
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
        convoNameFrom(message)
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
          val prompt = pinnedSystemPrompt(session, config.systemPromptName, config.systemPromptVersion)
          val convo = ConvosDao.create(session, NewConvo(studentId, resolvedName, ConvoParty.Student)).getOrThrow()
          val messages = visibleHistory(session, convo.id) + ChatMessage.text(ChatRole.USER, message)
          // Next-session opener: compose open explicit commitments (RFC 93) and
          // open fit-lens suggestions (RFC 98) into the coach prompt so the coach
          // raises them naturally in its first reply.
          val pending = openExplicitCommitments(session, studentId)
          val pendingFits = openFitSuggestions(session, studentId)
          val moneyProfile = activeMoneyProfile(session, studentId)
          val testScores = activeTestScores(session, studentId)
          val collegeList = readActiveListInSession(session, studentId)
          val graduation = graduationDate(session, studentId)
          PreFlight.Ready(
            PreparedTurn(
              convo,
              prompt,
              composeSystem(prompt, graduation, pending, pendingFits, moneyProfile, testScores, collegeList),
              messages,
              StudentTurnParty(convo.id, studentId, pending.map { it.id }, pendingFits.map { it.id }),
            ),
          )
        }
      when (preFlight) {
        is PreFlight.Refused -> {
          preFlight.result
        }

        is PreFlight.Ready -> {
          val opener = loop.openUserTurn(preFlight.turn)
          StartConvoResult.Started(
            convo = preFlight.turn.convo,
            userTurn = opener.request,
            reply = loop.buildReplyFlow(preFlight.turn, opener, isFirstTurn = true),
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
    val messageError = coachMessageError(message)
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
          val prompt = pinnedSystemPrompt(session, config.systemPromptName, config.systemPromptVersion)
          val messages = visibleHistory(session, convoId) + ChatMessage.text(ChatRole.USER, message)
          // postTurn never surfaces commitments or fit suggestions: only a new
          // conversation opens with reflection, so an insight is not re-raised
          // mid-conversation. The money-profile block (RFC 134) IS composed on
          // every turn: what may be used and what must not be re-asked applies
          // mid-conversation too.
          val moneyProfile = activeMoneyProfile(session, studentId)
          // The scores block rides every turn too (RFC 207): what she told us a
          // week ago is still what she told us, and a decline is never re-asked.
          val testScores = activeTestScores(session, studentId)
          // The college list rides every turn (RFC 257): a school added or
          // dropped between turns is in the next turn's system text.
          val collegeList = readActiveListInSession(session, studentId)
          // The date block rides every turn, not only the opener (RFC 186): a
          // conversation that ran past midnight, or one resumed a week later,
          // must not be answered against the date it opened on.
          val graduation = graduationDate(session, studentId)
          PreFlight.Ready(
            PreparedTurn(
              owned,
              prompt,
              composeSystem(prompt, graduation, emptyList(), emptyList(), moneyProfile, testScores, collegeList),
              messages,
              StudentTurnParty(owned.id, studentId, emptyList(), emptyList()),
            ),
          )
        }
      when (preFlight) {
        is PreFlight.Refused -> {
          preFlight.result
        }

        is PreFlight.Ready -> {
          val opener = loop.openUserTurn(preFlight.turn)
          PostTurnResult.Started(
            convo = preFlight.turn.convo,
            userTurn = opener.request,
            reply = loop.buildReplyFlow(preFlight.turn, opener, isFirstTurn = false),
          )
        }
      }
    }
  }

  /**
   * The student side of a turn (RFC 258's [TurnParty]): the student's own
   * registry, each [StudentScopedChatTool] handed the convo's student (RFC 134),
   * nothing written beside a request row, and — on a successful terminal — the
   * opener's disclosed commitments (RFC 93) and surfaced fit suggestions (RFC 98)
   * marked against the convo. Both lists are empty for postTurn and when
   * nothing was pending.
   */
  private inner class StudentTurnParty(
    private val convoId: ConvoId,
    private val studentId: StudentId,
    private val disclosedCommitmentIds: List<CommitmentId>,
    private val surfacedFitSuggestionIds: List<FitSuggestionId>,
  ) : TurnParty {
    override val tools: ToolRegistry get() = this@CoachingService.tools

    override suspend fun dispatch(
      tool: ChatTool,
      input: JsonObject,
    ): JsonObject =
      when (tool) {
        is StudentScopedChatTool -> tool.execute(studentId, input)
        else -> tool.execute(input)
      }

    override fun onRequestAppended(
      session: ed.unicoach.db.dao.SqlSession,
      request: ConvoRequest,
    ) {}

    override suspend fun onSucceeded() {
      markDisclosedCommitmentsFulfilled(convoId, disclosedCommitmentIds)
      markSurfacedFitSuggestions(convoId, surfacedFitSuggestionIds)
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
    return if (isStudentsOwn(convo, studentId)) convo else null
  }

  /**
   * Whether [convo] is [studentId]'s OWN conversation: about them AND with them
   * (RFC 258). A supporter's conversation about the student matches on
   * `student_id` alone, and is the parent's words, so it is not the student's
   * to read, rename, archive, delete or continue -- it answers exactly as a
   * conversation that does not exist.
   */
  private fun isStudentsOwn(
    convo: Convo,
    studentId: StudentId,
  ): Boolean =
    convo.studentId == studentId &&
      when (convo.party) {
        ConvoParty.Student -> true
        is ConvoParty.Supporter -> false
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
   * The student's own test scores for the coach context block (RFC 207): the
   * live refusal, the live scores, and the derived answer status, read through
   * [readTestScoresInSession] -- the one home of that derivation, shared with
   * [ed.unicoach.coaching.testing.TestScoreService]. A missing refusal is an
   * ordinary no-decline, not a fault; any other failure propagates, because a
   * DB outage must fail the turn, never silently drop the declined-field guard.
   */
  private fun activeTestScores(
    session: ed.unicoach.db.dao.SqlSession,
    studentId: StudentId,
  ): TestScores = readTestScoresInSession(session, studentId)

  /**
   * The student's expected high-school graduation date, for the date block
   * (RFC 186) -- [graduationDateOf], which is the one home for the two rules
   * this read turns on (ALL rather than ACTIVE, and a missing row as a located
   * [IllegalStateException]).
   *
   * It moved out of this class when the admission-test timeline needed the same
   * read and copied it verbatim (RFC 197). The clause below is the only thing
   * the two calls differ by, which is exactly why one function takes it.
   */
  private fun graduationDate(
    session: ed.unicoach.db.dao.SqlSession,
    studentId: StudentId,
  ): PartialDate =
    graduationDateOf(
      session,
      studentId,
      "composing the coach date block, though convos.student_id foreign-keys to it",
    )

  /**
   * Composes the outgoing system text: the prompt body, then the date block
   * (RFC 186), then a rendered reflection block when the coach has commitments
   * (RFC 93) or fit-lens suggestions (RFC 98) to raise in its first reply, then
   * the money-profile block (RFC 134) once a profile row exists, then the
   * test-scores block (RFC 207), then the college-list block (RFC 257) --
   * last, and on every turn, empty list included.
   *
   * The date block is UNCONDITIONAL, and the early return that used to hand
   * back [SystemPrompt.body] verbatim when nothing was pending is gone with it.
   * That return is the reason this had to be restructured rather than appended
   * to: the student with no profile, no commitment and no fit suggestion is
   * every brand-new student, i.e. precisely the one asking how long she has
   * got. A block that renders for everyone else and not for her is worse than
   * no block.
   */
  private fun composeSystem(
    prompt: SystemPrompt,
    graduation: PartialDate,
    pending: List<Commitment>,
    pendingFits: List<FitSuggestionForOpener>,
    moneyProfile: MoneyProfile?,
    testScores: TestScores,
    collegeList: List<EntryWithCollegeName>,
  ): String {
    val block =
      buildString {
        appendLine(prompt.body)
        appendLine()
        appendLine(renderDateBlock(today(), graduation))
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
          // The band's dollar range, never the bare `over_110k` code (RFC 142):
          // whatever the coach reads here it may repeat aloud, and a code read
          // aloud is exactly the source jargon the prompt forbids.
          appendLine(
            "- household income band: " +
              renderMoneyField(
                moneyProfile.incomeBandStatus,
                moneyProfile.incomeBand?.bracket,
                "income_band",
                moneyProfile,
              ),
          )
          appendLine(
            "- state of residency: " +
              renderMoneyField(moneyProfile.residencyStatus, moneyProfile.residencyState, "residency_state", moneyProfile),
          )
          // The plan in the words a student says it, never the `with_family`
          // wire name (RFC 152, the RFC 142 rule applied to the third field):
          // whatever the coach reads here it may repeat aloud.
          appendLine(
            "- usual living plan: " +
              renderMoneyField(
                moneyProfile.livingPlanStatus,
                moneyProfile.livingPlan?.label,
                "living_plan",
                moneyProfile,
              ),
          )
        }
        when (testScores.status) {
          AnswerStatus.ANSWERED -> {
            appendLine()
            appendLine(TestScoreCopy.ANSWERED_HEADER)
            for (score in testScores.scores) {
              appendLine(TestScoreCopy.scoreLineOf(score.component, score.score.score, score.sitting))
            }
          }

          AnswerStatus.DECLINED -> {
            appendLine()
            appendLine(TestScoreCopy.DECLINED_LINE)
          }

          AnswerStatus.UNANSWERED -> {
            // Nothing to remember: never asked, no refusal, no score. The block
            // is absent, so a brand-new student's prompt stays byte-identical
            // to today's.
          }

          AnswerStatus.UNDECIDED -> {
            // `AnswerStatus.of` cannot produce this, but a when over the enum
            // must name the arm -- and reading an undecided score answer as
            // silence would be row corruption dressed as a blank.
            throw nonMoneyAnswerStatus(AnswerStatus.UNDECIDED, location = "admission test scores block")
          }
        }
        appendLine()
        appendLine(CollegeListBlock.render(collegeList))
      }
    return block.trimEnd()
  }

  /**
   * Today, as a date (RFC 186, brief 0010 D15) -- [coachToday], which is now the
   * one home for the rule that the zone is OURS and never the injected clock's.
   * It moved out of this class with [COACH_ZONE] when a second coach-facing date
   * reader arrived (RFC 197 D15): one decision, one home.
   */
  private fun today(): LocalDate = clock.coachToday()

  /**
   * One money-profile field line, through the shared renderer (RFC 209): the
   * three arms and their exact words live in [renderDeclinableAnswer], which
   * takes an answer rather than a money profile, so the next surface with a
   * declinable answer calls it instead of writing a fourth ladder.
   *
   * This wrapper is all that is money-specific: naming the cell a corrupt read
   * sits in, which only this caller knows.
   */
  private fun renderMoneyField(
    status: AnswerStatus,
    value: String?,
    column: String,
    profile: MoneyProfile,
  ): String = renderDeclinableAnswer(status, value, "money_profiles.$column (row ${profile.id.value})")

  /**
   * Marks the commitments disclosed in this turn's opener (RFC 93) fulfilled
   * against the convo, in one transaction. Called only on the loop's final
   * successful answer — a failed turn soft-deletes the convo, leaving the
   * commitments open to re-surface next session. Empty for postTurn and when
   * nothing was pending, so this is a no-op on the common path.
   */
  private suspend fun markDisclosedCommitmentsFulfilled(
    convoId: ConvoId,
    disclosedCommitmentIds: List<CommitmentId>,
  ) {
    if (disclosedCommitmentIds.isEmpty()) return
    database.withConnection { session ->
      for (commitmentId in disclosedCommitmentIds) {
        CommitmentsDao.markFulfilled(session, commitmentId, convoId).getOrThrow()
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
  private suspend fun markSurfacedFitSuggestions(
    convoId: ConvoId,
    surfacedFitSuggestionIds: List<FitSuggestionId>,
  ) {
    if (surfacedFitSuggestionIds.isEmpty()) return
    database.withConnection { session ->
      for (suggestionId in surfacedFitSuggestionIds) {
        FitSuggestionsDao.markSurfaced(session, suggestionId, convoId).getOrThrow()
      }
    }
  }

  private fun nameFieldError(error: ValidationError): FieldError = FieldError(NAME_FIELD, "Invalid name: $error")
}
