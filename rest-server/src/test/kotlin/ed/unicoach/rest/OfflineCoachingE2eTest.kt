package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.AnthropicChatProvider
import ed.unicoach.chat.AnthropicTestFixtures
import ed.unicoach.chat.Replay
import ed.unicoach.chat.ScriptedAnthropicTransport
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionHandler
import ed.unicoach.coaching.extraction.ExtractionService
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.coaching.synthesis.SynthesisHandler
import ed.unicoach.coaching.synthesis.SynthesisService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.QueueWorker
import ed.unicoach.queue.SynthesisPayload
import ed.unicoach.queue.dao.JobsDao
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.web.common.logging.RequestLoggingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 107: extraction and synthesis end-to-end through the real QueueWorker. A
 * coaching turn (real provider over a recorded text stream) seeds one closed
 * conversation turn and enqueues EXTRACT_CONVERSATION. The extraction/synthesis
 * services run the REAL AnthropicChatProvider over a recorded forced-tool
 * (`record_extraction` / `record_synthesis`) capture, so the RFC-104 forced-tool
 * dispatch, the per-field parse, and the claim/commitment DAO writes are all
 * exercised. Coaching tables are truncated per test.
 */
class OfflineCoachingE2eTest {
  companion object {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var connection: Connection
    private lateinit var database: Database

    // The worker runs against its own connection pool, mirroring the real
    // queue-worker process. Sharing the server's pool lets a busy full-suite run
    // starve the worker's claim query, leaving jobs SCHEDULED past the timeout.
    private lateinit var workerDatabase: Database
    private lateinit var extractionConfig: ExtractionConfig
    private lateinit var synthesisConfig: SynthesisConfig

    /**
     * The worker-side budget gate (RFC 109). e2e students spend far below the
     * packaged allowance, so the real config admits every pass here — the gate's
     * refusals are pinned in CoachingBudgetRoutingTest.
     */
    private lateinit var workerBudgetService: BudgetService
    private val chatTransport = SwappableAnthropicTransport()
    private var port: Int = 0

    // A minimal, parser-valid record_extraction payload: no observations, one NEW
    // claim (avoids coupling to the seeded turn's runtime request id).
    private const val EXTRACTION_INPUT =
      """{"observations":[],"claims":[{"op":"new","statement":"The student wants to major in biology.",""" +
        """"kind":"goal","subject":"student","topic":"academics","origin":"student_stated","visibility":"student_visible"}]}"""

    // A minimal, parser-valid record_synthesis payload: one commitment, no supports.
    private const val SYNTHESIS_INPUT =
      """{"commitments":[{"lens":"gap","disclosure":"explicit","statement":"Ask the student about their target application deadlines."}]}"""

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      workerDatabase = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

      val sessionConfig = SessionConfig.from(config).getOrThrow()
      val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
      val coachingConfig = CoachingConfig.from(config).getOrThrow()
      val clientKeyGateConfig = ClientKeyGateConfig.from(config).getOrThrow()
      val requestLoggingConfig = RequestLoggingConfig.from(config).getOrThrow()
      val queueService = QueueService(database)
      val emailVerificationConfig =
        ed.unicoach.auth.EmailVerificationConfig
          .from(config)
          .getOrThrow()
      val googleTokenVerifier = ed.unicoach.auth.GoogleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
      val appleTokenVerifier = ed.unicoach.auth.AppleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
      // debounce defaults to 5m (coalesces rapid turns); zero it so the enqueued
      // EXTRACT_CONVERSATION job is immediately due for the worker.
      extractionConfig =
        ExtractionConfig
          .from(
            com.typesafe.config.ConfigFactory
              .parseString("extraction.debounce = 0s")
              .withFallback(config),
          ).getOrThrow()
      synthesisConfig = SynthesisConfig.from(config).getOrThrow()
      workerBudgetService =
        BudgetService(
          workerDatabase,
          BudgetConfig.from(config).getOrThrow(),
          subscriptionPlansFrom(config),
        )

      // Real provider over the fake seam, wrapped by the real LlmCallLog (RFC 106).
      val llmCallLog = LlmCallLog(AnthropicChatProvider(chatTransport, AutoCloseable {}), database)

      server =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            llmCallLog,
            coachingConfig,
            clientKeyGateConfig,
            emailVerificationConfig,
            googleTokenVerifier,
            appleTokenVerifier,
            queueService,
            extractionConfig,
            requestLoggingConfig,
            BudgetConfig.from(config).getOrThrow(),
            offlineAppStoreServerApi(),
            subscriptionPlansFrom(config),
          )
        }
      server.start(wait = false)
      port =
        runBlocking {
          server.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::server.isInitialized) server.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
      if (::database.isInitialized) database.close()
      if (::workerDatabase.isInitialized) workerDatabase.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  @BeforeEach
  fun resetTables() {
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE convos, llm_requests, extraction_runs, synthesis_runs, claims, observations, commitments, jobs CASCADE",
      )
      // Self-heal the prompts this flow reads. system_prompts is immutable, so the
      // codebase's convention for resetting it is TRUNCATE + re-seed, and a sibling
      // module's DAO test on the shared DB may have wiped the migration seed by the
      // time this class runs (see admin-web's fitLensPromptId self-healing lookup).
      // Bodies are irrelevant here (the provider is faked); only the (name, version)
      // rows must resolve, so ON CONFLICT DO NOTHING keeps whatever body is present.
      stmt.execute(
        "INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'coach') ON CONFLICT (name, version) DO NOTHING",
      )
      stmt.execute(
        "INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v2', 'extraction') ON CONFLICT (name, version) DO NOTHING",
      )
      stmt.execute(
        "INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v2', 'synthesis') ON CONFLICT (name, version) DO NOTHING",
      )
    }
  }

  private fun url(path: String) = "http://localhost:$port$path"

  private fun markEmailVerified(email: String) {
    connection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun seedClosedConversation(): String {
    val email = "offline${java.util.UUID.randomUUID()}@company.com"
    val reg =
      client.post(url("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Offline User")))
      }
    assertEquals(HttpStatusCode.Created, reg.status)
    markEmailVerified(email)
    val cookie =
      reg.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    val sr =
      client.post(url("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }
    assertEquals(HttpStatusCode.Created, sr.status)

    // The coaching turn produces one closed turn and enqueues EXTRACT_CONVERSATION.
    chatTransport.script(AnthropicTestFixtures.canonicalTextReplay)
    val convo =
      client.post(url("/api/v1/conversations")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateConversationRequest("I want help choosing a major", null)))
      }
    assertEquals(HttpStatusCode.Created, convo.status, convo.bodyAsText())
    return mapper.readTree(convo.bodyAsText())["conversation"]["id"].asText()
  }

  private fun singleJobId(jobType: String): String =
    connection.prepareStatement("SELECT id::text FROM jobs WHERE job_type = ?").use { stmt ->
      stmt.setString(1, jobType)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "expected a $jobType job")
        rs.getString(1)
      }
    }

  private fun count(sql: String): Int =
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun singleString(sql: String): String =
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next())
        rs.getString(1)
      }
    }

  private fun runWorkerUntil(
    handler: JobHandler,
    block: suspend () -> Unit,
  ) = runBlocking {
    val worker = QueueWorker(workerDatabase, JobsDao(), listOf(handler))
    val scope = CoroutineScope(Dispatchers.IO)
    worker.start(scope)
    try {
      block()
    } finally {
      worker.stop(5.seconds)
    }
  }

  private fun extractionHandler(replay: List<ed.unicoach.chat.AnthropicTransportEvent>): ExtractionHandler =
    ExtractionHandler(
      ExtractionService(
        workerDatabase,
        LlmCallLog(AnthropicChatProvider(ScriptedAnthropicTransport.of(replay), AutoCloseable {}), workerDatabase),
        extractionConfig,
        workerBudgetService,
      ),
    )

  // ---------------------------------------------------------------------------

  @Test
  fun `extraction persists an extraction_runs row and a claim side effect`() {
    val convoId = runBlocking { seedClosedConversation() }
    val jobId = singleJobId("EXTRACT_CONVERSATION")

    val replay = AnthropicTestFixtures.forcedToolReplay("record_extraction", EXTRACTION_INPUT)
    runWorkerUntil(extractionHandler(replay)) {
      awaitJobStatus(connection, jobId, "COMPLETED")
    }

    assertEquals(
      1,
      count("SELECT COUNT(*) FROM extraction_runs WHERE convo_id = '$convoId'"),
      "extraction must append exactly one run row",
    )
    // RFC 106 moved provider off extraction_runs onto the logged call (llm_requests).
    assertEquals(
      "anthropic",
      singleString(
        "SELECT lr.provider FROM extraction_runs er JOIN llm_requests lr ON er.llm_request_id = lr.id WHERE er.convo_id = '$convoId'",
      ),
    )
    assertTrue(count("SELECT COUNT(*) FROM claims") >= 1, "extraction must persist the NEW claim")
  }

  @Test
  fun `synthesis persists a synthesis_runs row and a commitment side effect`() {
    runBlocking { seedClosedConversation() }
    val extractJobId = singleJobId("EXTRACT_CONVERSATION")

    // Extraction first, so an active claim exists for synthesis to read.
    val extractReplay = AnthropicTestFixtures.forcedToolReplay("record_extraction", EXTRACTION_INPUT)
    runWorkerUntil(extractionHandler(extractReplay)) {
      awaitJobStatus(connection, extractJobId, "COMPLETED")
    }
    val studentId = singleString("SELECT student_id::text FROM extraction_runs LIMIT 1")

    // Enqueue and run synthesis for that student.
    val synthJobId =
      runBlocking {
        val enq = QueueService(database).enqueue(JobType.SYNTHESIZE_STUDENT, SynthesisPayload(studentId).asJson())
        (enq as EnqueueResult.Success)
          .job.id
          .toString()
      }
    val synthReplay = AnthropicTestFixtures.forcedToolReplay("record_synthesis", SYNTHESIS_INPUT)
    val synthHandler =
      SynthesisHandler(
        SynthesisService(
          workerDatabase,
          LlmCallLog(AnthropicChatProvider(ScriptedAnthropicTransport.of(synthReplay), AutoCloseable {}), workerDatabase),
          synthesisConfig,
          workerBudgetService,
        ),
      )
    runWorkerUntil(synthHandler) {
      awaitJobStatus(connection, synthJobId, "COMPLETED")
    }

    assertEquals(
      1,
      count("SELECT COUNT(*) FROM synthesis_runs WHERE student_id = '$studentId'"),
      "synthesis must append exactly one run row",
    )
    assertEquals(
      "anthropic",
      singleString(
        "SELECT lr.provider FROM synthesis_runs sr JOIN llm_requests lr ON sr.llm_request_id = lr.id WHERE sr.student_id = '$studentId'",
      ),
    )
    assertEquals(
      1,
      count("SELECT COUNT(*) FROM commitments WHERE student_id = '$studentId'"),
      "synthesis must persist the proposed commitment",
    )
  }

  @Test
  fun `a provider transient failure reschedules the extraction job`() {
    runBlocking { seedClosedConversation() }
    val jobId = singleJobId("EXTRACT_CONVERSATION")

    // A recorded transport IO failure: the real provider maps it to a
    // TransientFailure terminal, which the service folds to a retriable job result.
    val throwingReplay =
      listOf(Replay(listOf(AnthropicTestFixtures.opened()), throwing = IOException("connection reset")))
    val handler =
      ExtractionHandler(
        ExtractionService(
          workerDatabase,
          LlmCallLog(AnthropicChatProvider(ScriptedAnthropicTransport(throwingReplay), AutoCloseable {}), workerDatabase),
          extractionConfig,
          workerBudgetService,
        ),
      )
    runWorkerUntil(handler) {
      awaitJobAttempts(connection, jobId, 1)
    }

    assertEquals("RETRIABLE_FAILURE", latestJobAttemptStatus(connection, jobId))
    assertEquals("SCHEDULED", jobStatus(connection, jobId))
    // A transient provider failure before any billed Completed writes no run row.
    assertEquals(0, count("SELECT COUNT(*) FROM extraction_runs"), "a transient provider failure writes no run row")
    assertEquals(0, count("SELECT COUNT(*) FROM claims"))
  }
}
