package ed.unicoach.rest

import com.typesafe.config.Config
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.AppStoreTransport
import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.StubIdTokenVerifier
import ed.unicoach.chat.AnthropicStreamTransport
import ed.unicoach.chat.AnthropicTransportEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ScriptedAnthropicTransport
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.web.common.logging.RequestLoggingConfig
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import kotlin.test.fail

/**
 * The create-student body for a suite that needs A student and does not care
 * which one. The date of birth is [StudentRowFixture.DEFAULT_DATE_OF_BIRTH], the
 * comfortably-13+ literal every fixture row already uses (RFC 222): a dozen
 * suites send it incidentally, and a copy in each is a dozen edits the next
 * required field would cost. `StudentRoutingTest`, whose subject IS the
 * birthdate, states its dates explicitly instead.
 */
fun createStudentBody(graduationDate: String = "2028"): CreateStudentRequest =
  CreateStudentRequest(graduationDate, StudentRowFixture.DEFAULT_DATE_OF_BIRTH.toString())

/**
 * A [JobsDao] that fails the insert for one [JobType], simulating an enqueue
 * failure inside the request transaction. Used to prove atomic rollback: when the
 * SEND_EMAIL enqueue fails, the surrounding user-creation transaction rolls back
 * (RFC 96, tested through the register route).
 */
class FailingJobsDao(
  private val failOnType: JobType,
) : JobsDao() {
  override fun insert(
    session: SqlSession,
    newJob: NewJob,
  ): JobInsertResult =
    if (newJob.jobType == failOnType) {
      JobInsertResult.DatabaseFailure(RuntimeException("injected enqueue failure for $failOnType"))
    } else {
      super.insert(session, newJob)
    }
}

/**
 * A forwarding [AnthropicStreamTransport] whose backing scripted transport is
 * swapped per test. Lets one embedded server (booted once per class with the
 * real [ed.unicoach.chat.AnthropicChatProvider] wired over this seam) serve a
 * fresh recorded script for each test, and lets the test read back the request
 * bodies the real provider built.
 */
class SwappableAnthropicTransport : AnthropicStreamTransport {
  @Volatile
  var current: ScriptedAnthropicTransport = ScriptedAnthropicTransport(emptyList())

  /** Install a fresh script for the next collection(s). */
  fun script(vararg replays: List<AnthropicTransportEvent>) {
    current = ScriptedAnthropicTransport(replays.map { ed.unicoach.chat.Replay(it) })
  }

  override fun stream(body: JsonObject): Flow<AnthropicTransportEvent> = current.stream(body)
}

/** Polls `jobs.status` until it equals [expected] or the timeout elapses. */
suspend fun awaitJobStatus(
  connection: Connection,
  jobId: String,
  expected: String,
  timeoutMillis: Long = 30_000L,
) {
  val start = System.currentTimeMillis()
  var last: String? = null
  while (System.currentTimeMillis() - start < timeoutMillis) {
    last = jobStatus(connection, jobId)
    if (last == expected) return
    delay(50)
  }
  fail(
    "job $jobId did not reach status $expected within ${timeoutMillis}ms " +
      "(last=$last, attempts=${jobAttemptCount(connection, jobId)}, " +
      "lastAttempt=${latestJobAttemptStatus(connection, jobId)}, lastError=${latestJobAttemptError(connection, jobId)})",
  )
}

/** Polls until at least [minCount] `job_attempts` rows exist for [jobId]. */
suspend fun awaitJobAttempts(
  connection: Connection,
  jobId: String,
  minCount: Int = 1,
  timeoutMillis: Long = 30_000L,
) {
  val start = System.currentTimeMillis()
  var last = 0
  while (System.currentTimeMillis() - start < timeoutMillis) {
    last = jobAttemptCount(connection, jobId)
    if (last >= minCount) return
    delay(50)
  }
  fail("job $jobId did not record >= $minCount attempts within ${timeoutMillis}ms (last=$last)")
}

fun jobStatus(
  connection: Connection,
  jobId: String,
): String? =
  connection.prepareStatement("SELECT status FROM jobs WHERE id = ?::uuid").use { stmt ->
    stmt.setString(1, jobId)
    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
  }

fun jobAttemptCount(
  connection: Connection,
  jobId: String,
): Int =
  connection.prepareStatement("SELECT COUNT(*) FROM job_attempts WHERE job_id = ?::uuid").use { stmt ->
    stmt.setString(1, jobId)
    stmt.executeQuery().use { rs ->
      rs.next()
      rs.getInt(1)
    }
  }

/** The latest `job_attempts.status` for [jobId], or null if none. */
fun latestJobAttemptStatus(
  connection: Connection,
  jobId: String,
): String? =
  connection
    .prepareStatement(
      "SELECT status FROM job_attempts WHERE job_id = ?::uuid ORDER BY attempt_number DESC LIMIT 1",
    ).use { stmt ->
      stmt.setString(1, jobId)
      stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

/** The latest `job_attempts.error_message` for [jobId], or null if none. */
fun latestJobAttemptError(
  connection: Connection,
  jobId: String,
): String? =
  connection
    .prepareStatement(
      "SELECT error_message FROM job_attempts WHERE job_id = ?::uuid ORDER BY attempt_number DESC LIMIT 1",
    ).use { stmt ->
      stmt.setString(1, jobId)
      stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

/**
 * Boots and starts `appModule` against [database] with [appStore] as the Apple
 * transport and [provider] as the chat provider, on an ephemeral port — the
 * harness every suite that must mint a REAL subscription shares, because the only
 * way this system mints one is `POST /api/v1/subscriptions/verify` against a
 * scripted App Store conversation.
 *
 * One copy, so an `appModule` signature change costs one edit and no two suites
 * can drift in how they wire the server. A suite that needs a parameter this does
 * not take (a configured client-key gate, a switchable `JobsDao`) boots its own:
 * the point of sharing is the DEFAULT wiring, not a parameter list that grows a
 * knob per caller.
 */
fun startAppModuleServer(
  config: Config,
  database: Database,
  budgetConfig: BudgetConfig,
  appStore: AppStoreTransport,
  provider: ChatProvider,
): EmbeddedServer<*, *> {
  val server =
    embeddedServer(Netty, port = 0, host = "127.0.0.1") {
      environment.monitor.subscribe(ApplicationStopped) { database.close() }
      appModule(
        database,
        SessionConfig.from(config).getOrThrow(),
        RequestSizeConfig.from(config).getOrThrow(),
        LlmCallLog(provider, database),
        CoachingConfig.from(config).getOrThrow(),
        ClientKeyGateConfig.from(config).getOrThrow(),
        EmailVerificationConfig.from(config).getOrThrow(),
        GoogleIdTokenVerifier(StubIdTokenVerifier()),
        AppleIdTokenVerifier(StubIdTokenVerifier()),
        QueueService(database),
        ExtractionConfig.from(config).getOrThrow(),
        RequestLoggingConfig.from(config).getOrThrow(),
        budgetConfig,
        AppStoreServerApi(appStore, AppStoreTestFixtures.authTokens()),
        subscriptionPlansFrom(config),
        testAppleNotificationVerifier(),
        costReportConfigFrom(config),
        supporterInviteConfigFrom(config),
      )
    }
  server.start(wait = false)
  return server
}

/** The port a `port = 0` server actually bound. */
fun EmbeddedServer<*, *>.boundPort(): Int =
  runBlocking {
    engine
      .resolvedConnectors()
      .first()
      .port
  }
