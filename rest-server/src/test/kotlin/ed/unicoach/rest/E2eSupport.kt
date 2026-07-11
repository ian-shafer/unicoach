package ed.unicoach.rest

import ed.unicoach.chat.AnthropicStreamTransport
import ed.unicoach.chat.AnthropicTransportEvent
import ed.unicoach.chat.ScriptedAnthropicTransport
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import kotlin.test.fail

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
