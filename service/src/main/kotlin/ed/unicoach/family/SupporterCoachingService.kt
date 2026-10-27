package ed.unicoach.family

import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ChatTool
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.CoachTurnLoop
import ed.unicoach.coaching.ConvoProjection
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.PreFlight
import ed.unicoach.coaching.PreparedTurn
import ed.unicoach.coaching.ReadOnlyChatTool
import ed.unicoach.coaching.TurnParty
import ed.unicoach.coaching.TurnSettings
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.BudgetVerdict
import ed.unicoach.coaching.coachMessageError
import ed.unicoach.coaching.coachToday
import ed.unicoach.coaching.collegelist.SharedCollegeListEntry
import ed.unicoach.coaching.convoNameFrom
import ed.unicoach.coaching.graduationDateOf
import ed.unicoach.coaching.pinnedSystemPrompt
import ed.unicoach.coaching.renderDateBlock
import ed.unicoach.coaching.visibleHistory
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SupporterLlmCostsDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoParty
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.LinkedStudent
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.UserId
import kotlinx.serialization.json.JsonObject
import java.time.InstantSource

/**
 * The supporter's own coach (RFC 258, product slice family/06): a linked
 * parent's conversation about the student they support. The sibling of
 * [ed.unicoach.coaching.CoachingService]: the same `convos` table, the same
 * provider loop ([CoachTurnLoop]), and its own party, prompt, read-only
 * registry and budget subject.
 *
 * What it guarantees, each one a cut point the brief named:
 * - The student's own conversations never reach this coach: the system text is
 *   the parent prompt, the date block and the subject block (the shared list),
 *   and replay history is this conversation's own.
 * - The student's coaching memory never reaches it: nothing here reads claims,
 *   observations, commitments, fit suggestions, the money profile or scores.
 * - A parent's turn never writes the student's state: [tools] is the read-only
 *   [SupporterToolRegistry], and every subject tool reads through the seam.
 * - Nothing is extracted from a parent's turn: this service enqueues nothing,
 *   and extraction refuses a supporter's conversation by name besides.
 *
 * Every turn re-checks the live link in its pre-flight, so a severed link
 * refuses the very next message (brief 0014 D14). The budget gate is the
 * parent's OWN account ([BudgetService.verdictForAccount]), and every call is
 * attributed to the parent through `supporter_llm_costs`, written on the same
 * transaction as its `convo_requests` row.
 */
class SupporterCoachingService(
  private val database: Database,
  llmCallLog: LlmCallLog,
  private val config: SupporterCoachingConfig,
  private val budgetService: BudgetService,
  private val subjects: SubjectReadService,
  private val supporters: SupporterService,
  private val tools: SupporterToolRegistry,
  // The instant "today" is read at; the zone is the coach's (RFC 186). A test
  // passes a fixed one.
  private val clock: InstantSource = InstantSource.system(),
) {
  private val loop = CoachTurnLoop(database, llmCallLog, TurnSettings(config.model, config.maxTokens, config.maxToolRounds))

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  suspend fun listConvos(userId: UserId): Result<SupporterConvoList> =
    runCatching {
      database.withConnection { session ->
        val supporter = supporters.findInSession(session, userId) ?: return@withConnection SupporterConvoList.NoSupporter
        SupporterConvoList.Found(ConvosDao.listBySupporterWithActivity(session, supporter.id).getOrThrow())
      }
    }

  /** Readable with no live link: after an unlink the parent's own words stay theirs to read. */
  suspend fun listTurns(
    userId: UserId,
    convoId: ConvoId,
  ): Result<SupporterTurns> =
    runCatching {
      database.withConnection { session ->
        val supporter = supporters.findInSession(session, userId) ?: return@withConnection SupporterTurns.NoSupporter
        when (val authored = loadAuthored(session, convoId, supporter.id)) {
          is Authored.Refused -> return@withConnection SupporterTurns.ConvoNotFound(authored.reason)
          is Authored.Found -> Unit
        }
        SupporterTurns.Found(ConvoProjection.visibleExchanges(ConvosDao.listTurns(session, convoId).getOrThrow()))
      }
    }

  // ---------------------------------------------------------------------------
  // Turns
  // ---------------------------------------------------------------------------

  suspend fun startConvo(
    userId: UserId,
    studentId: StudentId,
    message: String,
  ): Result<SupporterTurnStart> {
    coachMessageError(message)?.let { return Result.success(SupporterTurnStart.Invalid(listOf(it))) }

    return runCatching {
      val preFlight =
        database.withConnection { session ->
          val supporter =
            supporters.findInSession(session, userId) ?: return@withConnection PreFlight.Refused(SupporterTurnStart.NoSupporter)
          admit(session, userId, studentId)?.let { return@withConnection PreFlight.Refused(it) }
          val context =
            when (val read = subjectContext(session, userId, supporter, studentId)) {
              is SubjectContextRead.Refused -> return@withConnection PreFlight.Refused(SupporterTurnStart.StudentNotFound(read.reason))
              is SubjectContextRead.Found -> read.context
            }
          val prompt = pinnedSystemPrompt(session, config.systemPromptName, config.systemPromptVersion)
          // Created LAST, after every refusal: a refused start leaves no row.
          val convo =
            ConvosDao
              .create(session, NewConvo(studentId, convoNameFrom(message), ConvoParty.Supporter(supporter.id)))
              .getOrThrow()
          PreFlight.Ready(prepare(session, convo, supporter, prompt, context, message))
        }
      run(preFlight, isFirstTurn = true)
    }
  }

  suspend fun postTurn(
    userId: UserId,
    convoId: ConvoId,
    message: String,
  ): Result<SupporterTurnStart> {
    coachMessageError(message)?.let { return Result.success(SupporterTurnStart.Invalid(listOf(it))) }

    return runCatching {
      val preFlight =
        database.withConnection { session ->
          val supporter =
            supporters.findInSession(session, userId) ?: return@withConnection PreFlight.Refused(SupporterTurnStart.NoSupporter)
          // The subject comes from the conversation row, never from the request.
          val convo =
            when (val authored = loadAuthored(session, convoId, supporter.id)) {
              is Authored.Refused -> return@withConnection PreFlight.Refused(SupporterTurnStart.ConvoNotFound(authored.reason))
              is Authored.Found -> authored.convo
            }
          admit(session, userId, convo.studentId)?.let { return@withConnection PreFlight.Refused(it) }
          val context =
            when (val read = subjectContext(session, userId, supporter, convo.studentId)) {
              is SubjectContextRead.Refused -> return@withConnection PreFlight.Refused(SupporterTurnStart.StudentNotFound(read.reason))
              is SubjectContextRead.Found -> read.context
            }
          val prompt = pinnedSystemPrompt(session, config.systemPromptName, config.systemPromptVersion)
          PreFlight.Ready(prepare(session, convo, supporter, prompt, context, message))
        }
      run(preFlight, isFirstTurn = false)
    }
  }

  private suspend fun run(
    preFlight: PreFlight<SupporterTurnStart>,
    isFirstTurn: Boolean,
  ): SupporterTurnStart =
    when (preFlight) {
      is PreFlight.Refused -> {
        preFlight.result
      }

      is PreFlight.Ready -> {
        val opener = loop.openUserTurn(preFlight.turn)
        SupporterTurnStart.Started(
          convo = preFlight.turn.convo,
          userTurn = opener.request,
          reply = loop.buildReplyFlow(preFlight.turn, opener, isFirstTurn),
        )
      }
    }

  /**
   * The live link, then the parent's own budget — in that order, so a caller
   * with no link learns nothing about an allowance. Null when the turn may run.
   */
  private fun admit(
    session: SqlSession,
    userId: UserId,
    studentId: StudentId,
  ): SupporterTurnStart? {
    SupporterStudentLinksDao.findLiveForUser(session, userId, studentId).getOrThrow()
      ?: return SupporterTurnStart.StudentNotFound(StudentRefusal.NO_LIVE_LINK)
    return when (val verdict = budgetService.verdictForAccount(session, userId).getOrThrow()) {
      is BudgetVerdict.Exhausted -> SupporterTurnStart.BudgetExhausted(verdict.entitlement)

      // Not dead: this arm is what makes the `when` exhaustive, so a third
      // BudgetVerdict would fail to compile here instead of falling through as
      // "allowed to spend".
      BudgetVerdict.Entitled -> null
    }
  }

  /** Everything about the student the system text may carry: the roster entry and the shared list. */
  private class SubjectContext(
    val student: LinkedStudent,
    val list: List<SharedCollegeListEntry>,
  )

  /** The subject context, or which read refused it. */
  private sealed interface SubjectContextRead {
    class Found(
      val context: SubjectContext,
    ) : SubjectContextRead

    class Refused(
      val reason: StudentRefusal,
    ) : SubjectContextRead
  }

  /** Read through the seam on the pre-flight's session; refused, naming which read, when the student is out of reach. */
  private fun subjectContext(
    session: SqlSession,
    userId: UserId,
    supporter: Supporter,
    studentId: StudentId,
  ): SubjectContextRead {
    val list =
      when (val read = subjects.collegeListInSession(session, userId, studentId)) {
        SubjectRead.NotFound -> return SubjectContextRead.Refused(StudentRefusal.SUBJECT_REFUSED)
        is SubjectRead.Found -> read.value
      }
    val student =
      supporters.rosterEntryInSession(session, supporter.id, studentId)
        ?: return SubjectContextRead.Refused(StudentRefusal.NOT_ON_ROSTER)
    return SubjectContextRead.Found(SubjectContext(student, list))
  }

  private fun prepare(
    session: SqlSession,
    convo: Convo,
    supporter: Supporter,
    prompt: SystemPrompt,
    context: SubjectContext,
    message: String,
  ): PreparedTurn =
    PreparedTurn(
      convo = convo,
      prompt = prompt,
      system = composeSystem(session, prompt, convo.studentId, context),
      messages = visibleHistory(session, convo.id) + ChatMessage.text(ChatRole.USER, message),
      party = SupporterTurnParty(Subject(supporter.userId, convo.studentId), supporter.id),
    )

  /**
   * The parent prompt body, then the date block (the student's coach's own),
   * then the subject block. Nothing else about the student is composed in: no
   * commitment, fit suggestion, money profile, score, claim or observation.
   */
  private fun composeSystem(
    session: SqlSession,
    prompt: SystemPrompt,
    studentId: StudentId,
    context: SubjectContext,
  ): String {
    val graduation =
      graduationDateOf(session, studentId, "composing the parent coach date block for a linked student")
    return buildString {
      appendLine(prompt.body)
      appendLine()
      appendLine(renderDateBlock(clock.coachToday(), graduation))
      appendLine()
      appendLine(SubjectBlock.render(context.student, context.list))
    }.trimEnd()
  }

  /** An active conversation the supporter authored, or why it is not one. */
  private sealed interface Authored {
    class Found(
      val convo: Convo,
    ) : Authored

    class Refused(
      val reason: ConvoRefusal,
    ) : Authored
  }

  /**
   * The convo iff it is an active conversation [supporterId] authored; otherwise
   * the refusal reason, which the route logs and the wire never shows (no
   * existence leak).
   */
  private fun loadAuthored(
    session: SqlSession,
    convoId: ConvoId,
    supporterId: SupporterId,
  ): Authored {
    // Only the expected miss is an outcome; a database fault (or a corrupt party
    // pair) must surface as a failure, not as a 404 that hides it.
    val convo =
      ConvosDao.findById(session, convoId, SoftDeleteScope.ACTIVE).getOrElse { e ->
        if (e is NotFoundException) return Authored.Refused(ConvoRefusal.MISSING_OR_DELETED) else throw e
      }
    return when (val party = convo.party) {
      ConvoParty.Student -> {
        Authored.Refused(ConvoRefusal.STUDENT_PARTY)
      }

      is ConvoParty.Supporter -> {
        if (party.supporterId == supporterId) Authored.Found(convo) else Authored.Refused(ConvoRefusal.OTHER_SUPPORTER)
      }
    }
  }

  /**
   * The supporter side of a turn ([TurnParty]): the read-only registry, each
   * [SubjectScopedChatTool] handed the conversation's [subject], every call
   * attributed to [supporterId] beside its request row, and nothing marked on
   * success — a parent's turn settles nothing on the student's record.
   */
  private inner class SupporterTurnParty(
    private val subject: Subject,
    private val supporterId: SupporterId,
  ) : TurnParty {
    override val tools: ToolRegistry get() = this@SupporterCoachingService.tools.registry

    override suspend fun dispatch(
      tool: ChatTool,
      input: JsonObject,
    ): JsonObject =
      when (tool) {
        is SubjectScopedChatTool -> {
          tool.execute(subject, input)
        }

        is ReadOnlyChatTool -> {
          tool.execute(input)
        }

        // The two kinds above are the registry's whole allowlist and its only
        // factory refuses any other, so this arm is a broken invariant: refuse
        // rather than run an unvetted tool unscoped. The loop answers the throw
        // to the model as a structured failure and logs it with its stack.
        else -> {
          throw IllegalStateException("tool [${tool.name}] is outside the supporter registry's allowlist")
        }
      }

    override fun onRequestAppended(
      session: SqlSession,
      request: ConvoRequest,
    ) {
      SupporterLlmCostsDao.insert(session, request.llmRequestId, supporterId).getOrThrow()
    }

    override suspend fun onSucceeded() {}
  }
}

/**
 * The subject block of a supporter coach's system text (RFC 258): whom this
 * person supports, how they are related, and the shared list as it stood when
 * the turn began. The list is the seam's projection — school, status, living
 * plan — so the student's reasons never reach it. No `college_id`: the parent
 * prompt forbids showing one, and no parent tool takes one.
 */
internal object SubjectBlock {
  fun render(
    student: LinkedStudent,
    list: List<SharedCollegeListEntry>,
  ): String =
    buildString {
      append("The student this person supports is ${student.studentName.value}; this person is their ${student.relationship.value}.")
      append("\n")
      append(listBlock(list))
    }

  private fun listBlock(list: List<SharedCollegeListEntry>): String =
    if (list.isEmpty()) {
      ed.unicoach.coaching.collegelist.CollegeListBlock.EMPTY_LINE
    } else {
      (listOf(ed.unicoach.coaching.collegelist.CollegeListBlock.HEADER) + list.map(::schoolLine)).joinToString("\n")
    }

  private fun schoolLine(entry: SharedCollegeListEntry): String =
    buildString {
      append("- ${entry.collegeName}: ${entry.status.value}")
      entry.livingPlan?.let { append("; ${ed.unicoach.coaching.collegelist.CollegeListBlock.LIVING_PLAN_LEAD}${it.label}") }
    }
}
