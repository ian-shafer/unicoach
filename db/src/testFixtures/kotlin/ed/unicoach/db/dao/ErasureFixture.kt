package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoParty
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.FrozenCost
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.IntendedSubjectWrite
import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.JsonParseFailureCategory
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewAdmissionTestScore
import ed.unicoach.db.models.NewAuthIdentity
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.NewLlmRequest
import ed.unicoach.db.models.NewLlmResponse
import ed.unicoach.db.models.NewMoneyProfile
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterInvite
import ed.unicoach.db.models.NewSupporterLinkRequest
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.ShareEventKind
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SystemPromptId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserEdit
import ed.unicoach.db.models.UserId
import ed.unicoach.db.session.SessionMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * One account for an erasure to walk (RFC 266, slice deletion/03/the-walk,
 * decision D-E): a user who is BOTH a student and a supporter, the supporter
 * linked to a second student's account, with a row the account's roots reach
 * in every table of the erasure reach.
 *
 * Built through the DAOs' own create paths where they exist, and a raw INSERT
 * where none does (`email_sends`, `jobs`, `job_attempts`), so every row is the
 * shape production writes. It also writes, for the account: a session, a
 * verification token, an auth identity, an invite (D10's tables), a second
 * address in its history, and `jobs` rows naming each of its identifiers in
 * every status, at more than one depth, which the walk must leave as they are
 * (RFC 267 D13: finished jobs age out through the queue's reaper instead).
 *
 * The fixture is hand-built and ASSERTED AGAINST THE CATALOG: the erasure tests
 * fail naming any table in `erasure_edge`'s reach with no fixture row, so a
 * table added later fails the gate until this file covers it.
 *
 * [SqlSession] is the seam, as on every fixture here: the caller owns the
 * transaction. Nothing here commits.
 */
object ErasureFixture {
  /** The second student, whose account the erased supporter is linked to. Never erased by the account's walk. */
  data class OtherStudent(
    val userId: UserId,
    val studentId: StudentId,
    val convoId: ConvoId,
    val shareId: CostReportShareId,
  )

  /** The account, by every identifier an erasure reads. */
  data class Account(
    val userId: UserId,
    val studentId: StudentId,
    val supporterId: SupporterId,
    /** The student's own conversation. */
    val studentConvoId: ConvoId,
    /** The supporter's conversation about [other]'s student. */
    val supporterConvoId: ConvoId,
    /** The address the account holds now, as written: mixed case. */
    val email: String,
    /** The address it held before, found only in `users_versions`. */
    val previousEmail: String,
    /** Base64 of the session's token hash, as `SESSION_EXTEND_EXPIRY` carries it. */
    val sessionTokenHash: String,
    val originalTransactionId: String,
    /** Every LLM call an owner row of the account points at. */
    val llmRequestIds: List<LlmRequestId>,
    val other: OtherStudent,
  )

  /** A parent and the student their conversation is about, two separate accounts (test 9, brief 0014 D9). */
  data class ParentAndStudent(
    val parentUserId: UserId,
    val supporterId: SupporterId,
    val studentUserId: UserId,
    val studentId: StudentId,
    /** The parent's conversation about the student. */
    val convoId: ConvoId,
    val llmRequestId: LlmRequestId,
  )

  /** The nanodollars every fixture call costs, so a spend total is predictable. */
  const val CALL_COST_NANODOLLARS = 1_000L

  /** Both accounts: [otherStudent], then [account] linked to it. */
  fun build(session: SqlSession): Account = account(session, otherStudent(session))

  /** The second student: a conversation with one turn, a claim, and a cost-report share. */
  fun otherStudent(session: SqlSession): OtherStudent {
    val userId = user(session, "other")
    val studentId = student(session, userId)
    val prompt = systemPrompt(session)
    val convoId = convo(session, studentId, ConvoParty.Student, "Their own plans")
    turn(session, convoId, prompt)
    claim(session, studentId, "They want to study oceans")
    val shareId = costReportShare(session, studentId)
    return OtherStudent(userId, studentId, convoId, shareId)
  }

  /** The account, linked to [other], reaching a row in every table of the erasure reach. */
  fun account(
    session: SqlSession,
    other: OtherStudent,
  ): Account {
    val tag = UUID.randomUUID().toString().take(8)
    val previousEmail = "erase-before-$tag@test.com"
    val userId = user(session, "erase-before-$tag", email = previousEmail)
    // A second address, so the history holds one the live row no longer does.
    val email = "Erase-$tag@Test.com"
    val current = UsersDao.findById(session, userId, SoftDeleteScope.ACTIVE).getOrThrow()
    UsersDao
      .update(
        session,
        UserEdit(
          id = userId,
          version = current.version,
          email = valid(EmailAddress.create(email.lowercase())),
          name = current.name,
          displayName = current.displayName,
          isAdmin = false,
        ),
      ).getOrThrow()

    val studentId = student(session, userId)
    val supporterId = SupportersDao.create(session, NewSupporter(userId)).getOrThrow().id
    val prompt = systemPrompt(session)
    val college = college(session)
    val calls = mutableListOf<LlmRequestId>()

    // The student's own conversation and everything distilled from it.
    val studentConvoId = convo(session, studentId, ConvoParty.Student, "My plans")
    val (requestId, turnCall) = turn(session, studentConvoId, prompt)
    calls += turnCall
    val observation =
      ObservationsDao
        .create(session, NewObservation(studentId, studentConvoId, requestId, Instant.now(), "I love the sea"))
        .getOrThrow()
    val claim = claim(session, studentId, "Wants to study marine biology")
    ClaimSupportDao.link(session, claim, observation.id).getOrThrow()
    val commitment =
      CommitmentsDao
        .create(
          session,
          NewCommitment(studentId, CommitmentLens.GAP, CommitmentDisclosure.EXPLICIT, "Visit two coastal campuses"),
        ).getOrThrow()
    CommitmentSupportDao.link(session, commitment.id, claim).getOrThrow()
    val entry =
      CollegeListEntriesDao
        .create(
          session,
          NewCollegeListEntry(studentId, college, CollegeListEntryStatus.CONSIDERING, "Near the ocean", LivingArrangement.ON_CAMPUS),
        ).getOrThrow()
    CollegeListEntrySupportDao.link(session, entry.id, observation.id).getOrThrow()
    val suggestion = FitSuggestionsDao.create(session, NewFitSuggestion(studentId, college(session), "Strong marine lab")).getOrThrow()
    FitSuggestionsDao.markSurfaced(session, suggestion.id, studentConvoId).getOrThrow()

    val extractionCall = call(session, "extract")
    calls += extractionCall
    ExtractionRunsDao
      .create(
        session,
        NewExtractionRun(
          studentConvoId,
          studentId,
          requestId,
          ExtractionOutcome.Failed(JsonParseFailureCategory.INVALID_FIELD, "quoted: I love the sea"),
          prompt,
          extractionCall,
        ),
      ).getOrThrow()
    val synthesisCall = call(session, "synthesize")
    calls += synthesisCall
    SynthesisRunsDao
      .create(
        session,
        NewSynthesisRun(studentId, SynthesisOutcome.Failed(JsonParseFailureCategory.INVALID_FIELD, "about the sea"), prompt, synthesisCall),
      ).getOrThrow()
    val queryCall = call(session, "fit query")
    val reasonCall = call(session, "fit reason")
    calls += listOf(queryCall, reasonCall)
    FitLensRunsDao
      .create(
        session,
        NewFitLensRun(
          studentId,
          FitLensOutcome.Failed(FitLensFailureCategory.INVALID_CONTENT, "marine biology"),
          prompt,
          prompt,
          queryCall,
          reasonCall,
        ),
      ).getOrThrow()

    // What the student told us outside a conversation.
    MoneyProfilesDao
      .create(
        session,
        NewMoneyProfile(
          studentId = studentId,
          incomeBand = IncomeBand.K48_TO_75K,
          incomeBandStatus = AnswerStatus.ANSWERED,
          residencyState = "CA",
          residencyStatus = AnswerStatus.ANSWERED,
          livingPlan = LivingArrangement.WITH_FAMILY,
          livingPlanStatus = AnswerStatus.ANSWERED,
          dependency = DependencyStatus.DEPENDENT,
          dependencyStatus = AnswerStatus.ANSWERED,
        ),
      ).getOrThrow()
    AdmissionTestScoresDao
      .upsertForStudent(
        session,
        NewAdmissionTestScore(
          studentId,
          AdmissionTest.SAT,
          AdmissionTestScoreComponent.SAT_TOTAL,
          1350,
          StudentRowFixture.graduationYear(2026),
        ),
      ).getOrThrow()
    AdmissionTestScoreDeclinesDao.insertDecline(session, studentId).getOrThrow()
    val slug = "erasure-$tag"
    SubjectTaxonomyFixture.insertSubject(session, slug, "Marine Biology $tag", "26")
    IntendedSubjectsDao.replaceAnswer(session, studentId, IntendedSubjectWrite.Subjects(listOf(slug))).getOrThrow()
    SharingPermissionsDao.set(session, studentId, SharingPermission.INTENDED_SUBJECTS, SharingPermissionStatus.GRANTED).getOrThrow()
    val shareId = costReportShare(session, studentId)
    ShareEventsDao.record(session, studentId, shareId, ShareEventKind.MINTED).getOrThrow()

    // The supporter: linked to the other student, a conversation about them,
    // and the spend row for its call.
    SupporterStudentLinksDao
      .create(session, NewSupporterStudentLink(supporterId, other.studentId, SupporterRelationship.PARENT))
      .getOrThrow()
    SupporterLinkRequestsDao
      .create(session, NewSupporterLinkRequest(supporterId, other.studentId, other.shareId))
      .getOrThrow()
    val supporterConvoId = convo(session, other.studentId, ConvoParty.Supporter(supporterId), "About my child")
    val (_, supporterCall) = turn(session, supporterConvoId, prompt)
    calls += supporterCall
    SupporterLlmCostsDao.insert(session, supporterCall, supporterId).getOrThrow()

    // The account itself: its subscription, its mail, and D10's rows.
    val originalTransactionId = "erasure-$tag"
    SubscriptionsDao
      .upsert(
        session,
        userId,
        originalTransactionId,
        "unicoach.monthly",
        SubscriptionStatus.ACTIVE,
        Instant.now().minus(Duration.ofDays(1)),
        Instant.now().plus(Duration.ofDays(29)),
      ).getOrThrow()
    RawSql.execute(
      session,
      "INSERT INTO email_sends (recipient_email, sender_email, subject, body, status, provider, user_id) " +
        "VALUES (?, 'coach@unicoach.test', 'Welcome', 'Hello there', 'SENT', 'ses', ?)",
      email.lowercase(),
      userId.value,
    )
    val rawToken = "erasure-session-$tag"
    SessionsDao
      .create(
        session,
        NewSession(
          userId,
          TokenHash.fromRawToken(rawToken),
          "Fixture/1.0",
          "127.0.0.1",
          SessionMetadata.getDefaultInstance(),
          Duration.ofDays(30),
          LoginMethod.PASSWORD,
        ),
      ).getOrThrow()
    VerificationTokensDao
      .create(session, NewVerificationToken(userId, TokenHash.fromRawToken("erasure-verify-$tag"), Instant.now().plus(Duration.ofDays(1))))
      .getOrThrow()
    RawSql.execute(
      session,
      "INSERT INTO account_tokens (user_id, type, user_version, token_hash, expires_at) " +
        "VALUES (?, 'password_reset', 1, ?, NOW() + interval '1 hour')",
      userId.value,
      TokenHash.fromRawToken("erasure-reset-$tag").value,
    )
    UserAuthIdentitiesDao
      .create(
        session,
        NewAuthIdentity(
          userId,
          AuthProvider.APPLE,
          valid(ProviderSubject.create("erasure-subject-$tag")),
          valid(EmailAddress.create(email.lowercase())),
          emailVerified = true,
        ),
      ).getOrThrow()
    SupporterInvitesDao
      .create(
        session,
        NewSupporterInvite(
          tokenHash = TokenHash.fromRawToken("erasure-invite-$tag"),
          inviterUserId = userId,
          inviterRole = InviterRole.STUDENT,
          studentId = studentId,
          supporterId = null,
          relationship = SupporterRelationship.PARENT,
          expiresAt = Instant.now().plus(Duration.ofDays(7)),
        ),
      ).getOrThrow()

    val account =
      Account(
        userId = userId,
        studentId = studentId,
        supporterId = supporterId,
        studentConvoId = studentConvoId,
        supporterConvoId = supporterConvoId,
        email = email,
        previousEmail = previousEmail,
        sessionTokenHash = Base64.getEncoder().encodeToString(TokenHash.fromRawToken(rawToken).value),
        originalTransactionId = originalTransactionId,
        llmRequestIds = calls,
        other = other,
      )
    jobs(session, account)
    return account
  }

  /** Two separate accounts: a student, and a parent with a conversation about them (test 9). */
  fun parentAndStudent(session: SqlSession): ParentAndStudent {
    val studentUserId = user(session, "subject")
    val studentId = student(session, studentUserId)
    val parentUserId = user(session, "parent")
    val supporterId = SupportersDao.create(session, NewSupporter(parentUserId)).getOrThrow().id
    SupporterStudentLinksDao
      .create(session, NewSupporterStudentLink(supporterId, studentId, SupporterRelationship.PARENT))
      .getOrThrow()
    val convoId = convo(session, studentId, ConvoParty.Supporter(supporterId), "How do I help with essays")
    val (_, call) = turn(session, convoId, systemPrompt(session))
    SupporterLlmCostsDao.insert(session, call, supporterId).getOrThrow()
    return ParentAndStudent(parentUserId, supporterId, studentUserId, studentId, convoId, call)
  }

  /** Closes [userId] as the account close does, so the walk will take it. */
  fun close(
    session: SqlSession,
    userId: UserId,
  ) {
    val user = UsersDao.findById(session, userId, SoftDeleteScope.ACTIVE).getOrThrow()
    UsersDao.delete(session, userId, user.version).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // The jobs naming the account, in every status, beside two naming only others.
  // ---------------------------------------------------------------------------

  /** Every status `jobs.status` admits. */
  private val JOB_STATUSES = listOf("SCHEDULED", "RUNNING", "COMPLETED", "DEAD_LETTERED")

  private fun jobs(
    session: SqlSession,
    account: Account,
  ) {
    val naming =
      listOf(
        "SEND_EMAIL" to
          buildJsonObject {
            put("to", account.email)
            put("template", "welcome")
            put("context", buildJsonObject { put("name", "Fixture") })
            put("userId", account.userId.value.toString())
          },
        // A historical address, nested a level down: matched at any depth.
        "SEND_EMAIL" to
          buildJsonObject {
            put("to", "someone@test.com")
            put("template", "digest")
            put("context", buildJsonObject { put("cc", account.previousEmail.uppercase()) })
          },
        "SYNTHESIZE_STUDENT" to buildJsonObject { put("studentId", account.studentId.value.toString()) },
        "FIT_LENS" to buildJsonObject { put("studentId", account.studentId.value.toString()) },
        "EXTRACT_CONVERSATION" to
          buildJsonObject {
            put("convoId", account.studentConvoId.value.toString())
            put("throughRequestId", 1)
          },
        "SESSION_EXTEND_EXPIRY" to buildJsonObject { put("tokenHash", account.sessionTokenHash) },
        "FUTURE_JOB" to buildJsonObject { put("subject", buildJsonObject { put("supporterId", account.supporterId.value.toString()) }) },
      )
    for ((type, payload) in naming) {
      for (status in JOB_STATUSES) job(session, type, payload.toString(), status)
    }
    // Survivors: the other student's job, and the subscription's refresh (D6).
    job(
      session,
      "SYNTHESIZE_STUDENT",
      buildJsonObject {
        put(
          "studentId",
          account.other.studentId.value
            .toString(),
        )
      }.toString(),
      "SCHEDULED",
    )
    job(
      session,
      "REFRESH_SUBSCRIPTION",
      buildJsonObject { put("originalTransactionId", account.originalTransactionId) }.toString(),
      "SCHEDULED",
    )
  }

  private fun job(
    session: SqlSession,
    type: String,
    payload: String,
    status: String,
  ) {
    val jobId =
      RawSql.single(session, "INSERT INTO jobs (job_type, payload, status) VALUES (?, ?::jsonb, ?) RETURNING id", type, payload, status) {
        it.getObject(1, UUID::class.java)
      }
    RawSql.execute(
      session,
      "INSERT INTO job_attempts (job_id, attempt_number, started_at, status) VALUES (?, 1, NOW(), 'RETRIABLE_FAILURE')",
      jobId,
    )
  }

  // ---------------------------------------------------------------------------
  // Building blocks
  // ---------------------------------------------------------------------------

  private fun user(
    session: SqlSession,
    name: String,
    email: String = "$name-${UUID.randomUUID()}@test.com",
  ): UserId =
    UsersDao
      .create(
        session,
        NewUser(
          email = valid(EmailAddress.create(email)),
          name = valid(PersonName.create("Fixture ${name.take(20)}")),
          displayName = null,
          passwordHash = valid(PasswordHash.create("\$argon2id\$v=19\$m=65536,t=3,p=4\$c2FsdA\$aGFzaA")),
        ),
      ).getOrThrow()
      .id

  private fun student(
    session: SqlSession,
    userId: UserId,
  ): StudentId =
    StudentsDao
      .create(session, NewStudent(userId, StudentRowFixture.DEFAULT_GRADUATION, LocalDate.of(2009, 7, 10)))
      .getOrThrow()
      .id

  private fun systemPrompt(session: SqlSession): SystemPromptId =
    SystemPromptsDao
      .create(session, NewSystemPrompt("erasure-fixture", UUID.randomUUID().toString(), "You are a coach."))
      .getOrThrow()
      .id

  private fun college(session: SqlSession): CollegeId {
    CodebookReferenceFixture.seed(session)
    return CollegesDao.upsert(session, newCollegeFixture(Random.nextInt(80_000_000, 89_999_999))).getOrThrow().id
  }

  private fun convo(
    session: SqlSession,
    studentId: StudentId,
    party: ConvoParty,
    name: String,
  ): ConvoId = ConvosDao.create(session, NewConvo(studentId, valid(ConvoName.create(name)), party)).getOrThrow().id

  /** One user turn: a logged call and the conversation's request row pointing at it. */
  private fun turn(
    session: SqlSession,
    convoId: ConvoId,
    prompt: SystemPromptId,
  ): Pair<ConvoRequestId, LlmRequestId> {
    val llmRequestId = call(session, "I want to study the sea")
    val turnId = ConvosDao.nextTurnId(session).getOrThrow()
    val request = ConvosDao.appendRequest(session, NewConvoRequest(convoId, prompt, llmRequestId, turnId)).getOrThrow()
    return request.id to llmRequestId
  }

  /** One logged LLM call, with its response and its raw payload, every personal column set. */
  private fun call(
    session: SqlSession,
    text: String,
  ): LlmRequestId {
    val content: JsonArray =
      buildJsonArray {
        add(
          buildJsonObject {
            put("role", "user")
            put("content", text)
          },
        )
      }
    val request =
      LlmCallsDao
        .appendRequest(
          session,
          NewLlmRequest(
            provider = "anthropic",
            modelRequested = "claude-fixture",
            system = "You are a coach.",
            content = content,
            maxTokens = 1024,
            tools = buildJsonArray { add(JsonPrimitive("tool")) },
            toolChoice = buildJsonObject { put("type", "auto") },
            params = buildJsonObject { put("temperature", 0) },
          ),
        ).getOrThrow()
    LlmCallsDao
      .appendResponse(
        session,
        NewLlmResponse(
          requestId = request.id,
          outcome = LlmCallOutcome.Completed(content, "claude-fixture", "end_turn"),
          providerRequestId = "req-${UUID.randomUUID()}",
          inputTokens = 30,
          outputTokens = 50,
          cacheReadTokens = 7,
          cacheWriteTokens = 11,
          cost = FrozenCost(Nanodollars.of(CALL_COST_NANODOLLARS), estimated = false),
          latencyMs = 5,
        ),
        rawPayload = buildJsonObject { put("echo", text) },
      ).getOrThrow()
    return request.id
  }

  private fun claim(
    session: SqlSession,
    studentId: StudentId,
    statement: String,
  ) = ClaimsDao
    .create(
      session,
      NewClaim(
        studentId,
        ClaimOrigin.STUDENT_STATED,
        ClaimKind.GOAL,
        ClaimSubject.STUDENT,
        ClaimTopic.ACADEMICS,
        ClaimVisibility.STUDENT_VISIBLE,
        statement,
      ),
    ).getOrThrow()
    .id

  private fun costReportShare(
    session: SqlSession,
    studentId: StudentId,
  ): CostReportShareId =
    CostReportSharesDao
      .create(
        session,
        NewCostReportShare(CostReportShareId(UUID.randomUUID()), studentId, TokenHash.fromRawToken("share-${UUID.randomUUID()}")),
      ).getOrThrow()
      .id

  private fun <T> valid(result: ValidationResult<T>): T =
    when (result) {
      is ValidationResult.Valid -> result.value
      is ValidationResult.Invalid -> error("fixture value is invalid: ${result.error}")
    }
}
