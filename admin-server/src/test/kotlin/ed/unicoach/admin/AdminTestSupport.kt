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
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRun
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
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

/**
 * Shared scaffolding for admin-server tests: a real test-DB-backed Database,
 * AuthService, and the configured admin module, plus user/student seeders. The
 * test DB is reset to a clean migrated state by `bin/test` before the suite.
 */
object AdminTestSupport {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "admin-server.conf", "service.conf", "email.conf")
      .getOrThrow()

  private val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val adminConfig = AdminConfig.from(config).getOrThrow()

  val database = Database(dbConfig)
  val argon2Hasher = Argon2Hasher()
  private val emailConfig =
    ed.unicoach.email.EmailConfig
      .from(config)
      .getOrThrow()
  private val emailService =
    ed.unicoach.email.EmailService(
      database,
      ed.unicoach.email.EmailProviderFactory
        .fromConfig(emailConfig)
        .getOrThrow(),
      emailConfig,
    )
  private val emailVerificationService =
    ed.unicoach.auth.EmailVerificationService(
      database,
      emailService,
      TokenGenerator(),
      ed.unicoach.auth.EmailVerificationConfig
        .from(config)
        .getOrThrow(),
    )
  val authService = AuthService(database, argon2Hasher, TokenGenerator(), emailVerificationService, StubGoogleTokenVerifier())

  fun Application.installTestAdminModule() {
    adminModule(database, authService, argon2Hasher, emailVerificationService, adminConfig)
  }

  /** Truncate users (cascades to sessions/students) for an isolated test. */
  fun resetDatabase() {
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use { conn ->
      conn.createStatement().use { it.execute("TRUNCATE TABLE users CASCADE") }
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

  /** Appends a convo_request (FK parent for an observation's source_request_id and a run's through_request_id). */
  fun seedConvoRequest(convoId: ConvoId): ConvoRequest =
    runBlocking {
      database
        .withConnection { session ->
          ConvosDao.appendRequest(
            session,
            NewConvoRequest(
              convoId = convoId,
              provider = "anthropic",
              modelRequested = "claude-opus-4-8",
              systemPromptId = extractionPromptId(),
              requestParams = null,
              content = JsonArray(emptyList()),
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

  /** Appends an extraction_runs row via the DAO. */
  fun seedExtractionRun(
    studentId: StudentId,
    convoId: ConvoId,
    throughRequestId: ed.unicoach.db.models.ConvoRequestId,
    outcome: ExtractionOutcome = ExtractionOutcome.APPLIED,
    modelResolved: String? = "claude-sonnet-4-6",
    observationsWritten: Int = 1,
    claimsWritten: Int = 1,
    inputTokens: Int? = 100,
    outputTokens: Int? = 50,
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
              provider = "log",
              modelResolved = modelResolved,
              observationsWritten = if (outcome == ExtractionOutcome.FAILED) 0 else observationsWritten,
              claimsWritten = if (outcome == ExtractionOutcome.FAILED) 0 else claimsWritten,
              inputTokens = inputTokens,
              outputTokens = outputTokens,
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
}
