package ed.unicoach.admin

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.StubGoogleTokenVerifier
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ClaimSupportDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRun
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.FitLensRun
import ed.unicoach.db.models.FitSuggestion
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.NewLlmRequest
import ed.unicoach.db.models.NewLlmResponse
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SynthesisRun
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import java.sql.DriverManager
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shared scaffolding for admin-web tests: a real test-DB-backed Database,
 * AuthService, and the configured admin module, plus user/student seeders. The
 * test DB is reset to a clean migrated state by `bin/test` before the suite.
 */
object AdminTestSupport {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "admin-web.conf", "service.conf")
      .getOrThrow()

  private val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val adminConfig = AdminConfig.from(config).getOrThrow()
  private val requestLoggingConfig =
    ed.unicoach.web.common.logging.RequestLoggingConfig
      .from(config)
      .getOrThrow()

  val database = Database(dbConfig)
  val argon2Hasher = Argon2Hasher()
  private val queueService = ed.unicoach.queue.QueueService(database)
  private val emailVerificationService =
    ed.unicoach.auth.EmailVerificationService(
      database,
      queueService,
      TokenGenerator(),
      ed.unicoach.auth.EmailVerificationConfig
        .from(config)
        .getOrThrow(),
    )
  val authService = AuthService(database, argon2Hasher, TokenGenerator(), emailVerificationService, StubGoogleTokenVerifier())

  fun Application.installTestAdminModule() {
    adminModule(database, authService, argon2Hasher, emailVerificationService, queueService, adminConfig, requestLoggingConfig)
  }

  /**
   * Truncate users (cascades to sessions/students) and the college reference
   * tables (cascades to college_programs and colleges_versions) for an isolated
   * test.
   */
  fun resetDatabase() {
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use { conn ->
      // `llm_requests` (and its `llm_responses` / `llm_responses_raw` cascade, RFC 106)
      // is truncated explicitly: an orphan/unlinked call has no FK back to `users`, so
      // the user cascade alone would leave stale calls to pollute the unlinked-call test.
      conn.createStatement().use { it.execute("TRUNCATE TABLE users, colleges, jobs, llm_requests CASCADE") }
    }
  }

  fun seedUser(
    email: String,
    password: String = "Password123!",
    name: String = "Test User",
    isAdmin: Boolean = false,
  ): User =
    runBlocking {
      val hash = argon2Hasher.hash(password)
      val newUser =
        NewUser(
          email = (EmailAddress.create(email) as ValidationResult.Valid).value,
          name = (PersonName.create(name) as ValidationResult.Valid).value,
          displayName = null,
          passwordHash = (PasswordHash.create(hash) as ValidationResult.Valid).value,
          isAdmin = isAdmin,
        )
      database.withConnection { session -> UsersDao.create(session, newUser) }.getOrThrow()
    }

  fun seedStudent(
    userId: UserId,
    gradIso: String = "2028",
  ): Student =
    runBlocking {
      val date = (PartialDate.parse(gradIso) as ValidationResult.Valid).value
      database.withConnection { session -> StudentsDao.create(session, NewStudent(userId, date)) }.getOrThrow()
    }

  /** Upserts a colleges row via the DAO (RFC 82); re-upsert with a changed field bumps the version. */
  fun seedCollege(
    unitId: Int,
    name: String = "Test University",
    city: String = "Townsville",
    state: String = "CA",
    control: Int = 1,
    opeid: String? = "0012$unitId",
    admissionRate: Double? = 0.5,
    netPrice: Int? = 20000,
  ): College =
    runBlocking {
      database
        .withConnection { session ->
          CollegesDao.upsert(
            session,
            NewCollege(
              unitId = unitId,
              opeid = opeid,
              name = name,
              city = city,
              state = state,
              region = 8,
              locale = 13,
              latitude = 34.0,
              longitude = -118.0,
              control = control,
              undergradEnrollment = 5000,
              admissionRate = admissionRate,
              satAvg = 1200,
              costAttendance = 40000,
              netPrice = netPrice,
              tuitionInState = 12000,
              tuitionOutState = 30000,
              graduationRate = 0.7,
              medianEarnings = 55000,
              pctPell = 0.4,
              website = "https://test$unitId.edu",
            ),
          )
        }.getOrThrow()
    }

  /** Inserts a system_prompts catalog row directly (immutable; insert-only). */
  fun seedSystemPrompt(
    name: String,
    version: String,
    body: String,
  ): SystemPrompt =
    runBlocking {
      database.withConnection { session -> SystemPromptsDao.create(session, NewSystemPrompt(name, version, body)) }.getOrThrow()
    }

  /** Resolves the migration-seeded extraction prompt id (`extraction v1`). */
  private fun extractionPromptId(): ed.unicoach.db.models.SystemPromptId =
    runBlocking {
      database
        .withConnection { session -> SystemPromptsDao.findByNameAndVersion(session, "extraction", "v1") }
        .getOrThrow()
        .id
    }

  /** Inserts a convo for a student (FK parent for observations/extraction_runs). */
  fun seedConvo(
    studentId: StudentId,
    name: String = "Test Convo",
  ): Convo =
    runBlocking {
      val convoName = (ConvoName.create(name) as ValidationResult.Valid).value
      database.withConnection { session -> ConvosDao.create(session, NewConvo(studentId, convoName)) }.getOrThrow()
    }

  /**
   * Appends an `llm_requests` row (RFC 106) and returns its id — the FK a
   * `convo_requests` / `*_runs` row references. [content] defaults to a minimal
   * one-message array; [tools] is null unless a call wants to render a tool schema.
   */
  fun seedLlmRequest(
    provider: String = "anthropic",
    modelRequested: String = "claude-opus-4-8",
    system: String? = "be a good coach",
    content: JsonArray = JsonArray(emptyList()),
    maxTokens: Int = 1024,
    tools: JsonArray? = null,
    toolChoice: kotlinx.serialization.json.JsonObject? = null,
    params: kotlinx.serialization.json.JsonObject? = null,
  ): LlmRequestId =
    runBlocking {
      database
        .withConnection { session ->
          LlmCallsDao.appendRequest(
            session,
            NewLlmRequest(
              provider = provider,
              modelRequested = modelRequested,
              system = system,
              content = content,
              maxTokens = maxTokens,
              tools = tools,
              toolChoice = toolChoice,
              params = params,
            ),
          )
        }.getOrThrow()
        .id
    }

  /**
   * Appends a completed `llm_responses` row (+ raw when [rawPayload] is non-null)
   * for [llmRequestId] (RFC 106), so a joined read finds a terminal.
   */
  fun seedLlmResponse(
    llmRequestId: LlmRequestId,
    outcome: LlmCallOutcome =
      LlmCallOutcome.Completed(
        content =
          kotlinx.serialization.json.Json
            .parseToJsonElement("""[{"type":"text","text":"hi there"}]"""),
        modelResolved = "claude-opus-4-8",
        stopReason = "end_turn",
      ),
    providerRequestId: String? = "req_test",
    inputTokens: Int? = 100,
    outputTokens: Int? = 50,
    cacheReadTokens: Int? = 0,
    cacheWriteTokens: Int? = 0,
    latencyMs: Int = 123,
    rawPayload: kotlinx.serialization.json.JsonElement? = JsonArray(emptyList()),
  ) = runBlocking {
    database
      .withConnection { session ->
        LlmCallsDao.appendResponse(
          session,
          NewLlmResponse(
            requestId = llmRequestId,
            outcome = outcome,
            providerRequestId = providerRequestId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            latencyMs = latencyMs,
          ),
          rawPayload = rawPayload,
        )
      }.getOrThrow()
  }

  /**
   * Appends a convo_request (FK parent for an observation's source_request_id and
   * a run's through_request_id). Seeds an owning `llm_requests` row first (RFC 106)
   * unless [llmRequestId] is supplied.
   */
  fun seedConvoRequest(
    convoId: ConvoId,
    llmRequestId: LlmRequestId = seedLlmRequest(),
    kind: ed.unicoach.db.models.ConvoRequestKind = ed.unicoach.db.models.ConvoRequestKind.USER,
  ): ConvoRequest =
    runBlocking {
      database
        .withConnection { session ->
          val turnId = ConvosDao.nextTurnId(session).getOrThrow()
          ConvosDao.appendRequest(
            session,
            NewConvoRequest(
              convoId = convoId,
              systemPromptId = extractionPromptId(),
              llmRequestId = llmRequestId,
              turnId = turnId,
              kind = kind,
            ),
          )
        }.getOrThrow()
    }

  /** Appends an observation row via the DAO. */
  fun seedObservation(
    studentId: StudentId,
    convoId: ConvoId,
    sourceRequestId: ed.unicoach.db.models.ConvoRequestId,
    quote: String = "I want to study engineering",
    utteredAt: Instant = Instant.now(),
  ): Observation =
    runBlocking {
      database
        .withConnection { session ->
          ObservationsDao.append(session, NewObservation(studentId, convoId, sourceRequestId, utteredAt, quote))
        }.getOrThrow()
    }

  /** Inserts a claims row via the DAO. */
  fun seedClaim(
    studentId: StudentId,
    statement: String = "Wants to study CS",
    origin: ClaimOrigin = ClaimOrigin.STUDENT_STATED,
    kind: ClaimKind = ClaimKind.GOAL,
    subject: ClaimSubject = ClaimSubject.STUDENT,
    topic: ClaimTopic = ClaimTopic.ACADEMICS,
    visibility: ClaimVisibility = ClaimVisibility.STUDENT_VISIBLE,
  ): Claim =
    runBlocking {
      database
        .withConnection { session ->
          ClaimsDao.create(session, NewClaim(studentId, origin, kind, subject, topic, visibility, statement))
        }.getOrThrow()
    }

  /** Appends an extraction_runs row via the DAO. Defaults to an `Applied` outcome. Seeds its owning call (RFC 106). */
  fun seedExtractionRun(
    studentId: StudentId,
    convoId: ConvoId,
    throughRequestId: ed.unicoach.db.models.ConvoRequestId,
    outcome: ExtractionOutcome =
      ExtractionOutcome.Applied(observationsWritten = 1, claimsWritten = 1, claimsSuperseded = 0),
    llmRequestId: LlmRequestId = seedLlmRequest(provider = "anthropic", modelRequested = "claude-sonnet-4-6"),
  ): ExtractionRun =
    runBlocking {
      database
        .withConnection { session ->
          ExtractionRunsDao.append(
            session,
            NewExtractionRun(
              convoId = convoId,
              studentId = studentId,
              throughRequestId = throughRequestId,
              outcome = outcome,
              systemPromptId = extractionPromptId(),
              llmRequestId = llmRequestId,
            ),
          )
        }.getOrThrow()
    }

  /** Links an observation to a claim (claim_support) via the DAO. */
  fun seedClaimSupport(
    claimId: ClaimId,
    observationId: ObservationId,
  ) = runBlocking {
    database.withConnection { session -> ClaimSupportDao.link(session, claimId, observationId) }.getOrThrow()
  }

  /** Resolves the migration-seeded synthesis prompt id (`synthesis v1`). */
  private fun synthesisPromptId(): ed.unicoach.db.models.SystemPromptId =
    runBlocking {
      database
        .withConnection { session -> SystemPromptsDao.findByNameAndVersion(session, "synthesis", "v1") }
        .getOrThrow()
        .id
    }

  /** Inserts a commitments row via the DAO. */
  fun seedCommitment(
    studentId: StudentId,
    statement: String = "Help them narrow the college list",
    lens: CommitmentLens = CommitmentLens.GAP,
    disclosure: CommitmentDisclosure = CommitmentDisclosure.EXPLICIT,
  ): Commitment =
    runBlocking {
      database
        .withConnection { session -> CommitmentsDao.create(session, NewCommitment(studentId, lens, disclosure, statement)) }
        .getOrThrow()
    }

  /** Links a claim to a commitment (commitment_support) via the DAO. */
  fun seedCommitmentSupport(
    commitmentId: CommitmentId,
    claimId: ClaimId,
  ) = runBlocking {
    database.withConnection { session -> CommitmentSupportDao.link(session, commitmentId, claimId) }.getOrThrow()
  }

  /** Appends a synthesis_runs row via the DAO. Defaults to an `Applied` outcome. Seeds its owning call (RFC 106). */
  fun seedSynthesisRun(
    studentId: StudentId,
    outcome: SynthesisOutcome = SynthesisOutcome.Applied(commitmentsWritten = 2, commitmentsDropped = 1),
    llmRequestId: LlmRequestId = seedLlmRequest(provider = "anthropic", modelRequested = "claude-sonnet-4-6"),
  ): SynthesisRun =
    runBlocking {
      database
        .withConnection { session ->
          SynthesisRunsDao.append(
            session,
            NewSynthesisRun(
              studentId = studentId,
              outcome = outcome,
              systemPromptId = synthesisPromptId(),
              llmRequestId = llmRequestId,
            ),
          )
        }.getOrThrow()
    }

  // Find-or-create: the fit-lens prompts are migration-seeded, but a sibling
  // module's test on the shared DB may TRUNCATE system_prompts and restore only
  // its own rows, so a self-healing lookup keeps the fit-lens seeders robust
  // under the full-suite run order.
  private fun fitLensPromptId(name: String): ed.unicoach.db.models.SystemPromptId =
    runBlocking {
      database
        .withConnection { session ->
          SystemPromptsDao.findByNameAndVersion(session, name, "v1").recoverCatching {
            SystemPromptsDao
              .create(
                session,
                ed.unicoach.db.models
                  .NewSystemPrompt(name, "v1", "$name test body"),
              ).getOrThrow()
          }
        }.getOrThrow()
        .id
    }

  private fun fitLensQueryPromptId(): ed.unicoach.db.models.SystemPromptId = fitLensPromptId("fit_lens_query")

  private fun fitLensReasonPromptId(): ed.unicoach.db.models.SystemPromptId = fitLensPromptId("fit_lens_reason")

  /** Inserts a fit_suggestions row via the DAO (RFC 98). */
  fun seedFitSuggestion(
    studentId: StudentId,
    collegeId: CollegeId,
    rationale: String = "A grounded pitch for this school",
  ): FitSuggestion =
    runBlocking {
      database
        .withConnection { session -> FitSuggestionsDao.create(session, NewFitSuggestion(studentId, collegeId, rationale)) }
        .getOrThrow()
    }

  /**
   * Appends a fit_lens_runs row via the DAO (RFC 98). Defaults to an `Applied`
   * outcome. Seeds two owning calls (RFC 106): [queryLlmRequestId] and
   * [reasonLlmRequestId]. Pass `reasonLlmRequestId = null` for a pass that bailed
   * before the reason call.
   */
  fun seedFitLensRun(
    studentId: StudentId,
    outcome: FitLensOutcome = FitLensOutcome.Applied(suggestionsWritten = 1),
    matchesConsidered: Int? = 5,
    queryLlmRequestId: LlmRequestId = seedLlmRequest(provider = "anthropic", modelRequested = "claude-sonnet-4-6"),
    reasonLlmRequestId: LlmRequestId? = seedLlmRequest(provider = "anthropic", modelRequested = "claude-sonnet-4-6"),
  ): FitLensRun =
    runBlocking {
      database
        .withConnection { session ->
          FitLensRunsDao.append(
            session,
            NewFitLensRun(
              studentId = studentId,
              outcome = outcome,
              querySystemPromptId = fitLensQueryPromptId(),
              reasonSystemPromptId = fitLensReasonPromptId(),
              queryLlmRequestId = queryLlmRequestId,
              reasonLlmRequestId = reasonLlmRequestId,
              matchesConsidered = matchesConsidered,
            ),
          )
        }.getOrThrow()
    }

  /** Logs in and returns the raw session cookie value for the admin session cookie. */
  fun login(
    email: String,
    password: String,
  ): String =
    runBlocking {
      val result =
        authService
          .login(
            email = email,
            password = password,
            oldCookieToken = null,
            sessionExpirationSeconds = adminConfig.sessionExpirationSeconds,
            userAgent = "test",
            initialIp = "127.0.0.1",
          ).getOrThrow()
      (result as ed.unicoach.auth.LoginResult.Success).token
    }

  fun cookieHeader(token: String): String = "${adminConfig.cookieName}=$token"

  fun uniqueEmail(): String = "admin-test-${java.util.UUID.randomUUID()}@example.com"

  /**
   * The hover `title` a `FieldType.TIMESTAMP` cell renders for [instant]: the same
   * instant in the configured display zone as a full ISO-8601 offset datetime,
   * matching `renderTimestampValue`. Derived from [adminConfig]'s display zone (not
   * hardcoded UTC) so an assertion tracks whatever `admin.display.timezone` resolves
   * to. Note this differs from `Instant.toString()` on fractional-second formatting,
   * so tests must format through this rather than comparing the raw ISO string.
   */
  fun expectedTimestampTitle(instant: Instant): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(adminConfig.display.timezone))

  /**
   * Asserts that [body] renders [value] as a compacted `FieldType.UUID` cell: the
   * visible text is an ellipsis plus the last [AdminDisplay.idTailChars]
   * characters, the full value stays reachable through the span
   * `title` and the copy button's `data-full`, and the raw UUID never appears as
   * visible cell text (`>$value<`). When [refPath] is non-null, the cell's `refSlug`
   * navigation glyph href must also be present.
   */
  fun assertCompactUuid(
    body: String,
    value: String,
    refPath: String? = null,
  ) {
    val tail = adminConfig.display.idTailChars
    assertTrue(body.contains("…${value.takeLast(tail)}"), "[$value] must compact to ellipsis + tail")
    assertTrue(body.contains("title=\"$value\""), "[$value] must carry the full value as a hover title")
    assertTrue(body.contains("data-full=\"$value\""), "[$value] must emit an id-copy button carrying the full value")
    if (refPath != null) {
      assertTrue(body.contains(refPath), "[$value] must keep its refSlug href [$refPath]")
    }
    assertFalse(body.contains(">$value<"), "The raw UUID [$value] must not render as visible cell text")
  }
}
