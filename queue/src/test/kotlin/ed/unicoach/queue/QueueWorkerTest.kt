package ed.unicoach.queue

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.dao.AttemptFindResult
import ed.unicoach.queue.dao.JobFindResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class QueueWorkerTest {
  companion object {
    private lateinit var database: Database
    private lateinit var session: SqlSession

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)

      session =
        object : SqlSession {
          val conn = database.createRawConnection()

          override fun prepareStatement(sql: String) = conn.prepareStatement(sql)
        }
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) {
        database.close()
      }
    }
  }

  private val jobsDao = JobsDao()

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { s ->
        s.prepareStatement("TRUNCATE TABLE jobs CASCADE").use { stmt ->
          stmt.execute()
        }
      }
      Unit
    }

  class TestHandler(
    override val jobType: JobType = JobType.TEST_JOB,
    override val config: JobTypeConfig = JobTypeConfig(),
    private val executionBlock: suspend (JsonObject) -> JobResult,
  ) : JobHandler {
    override suspend fun execute(payload: JsonObject): JobResult = executionBlock(payload)
  }

  private fun enqueue(
    type: JobType = JobType.TEST_JOB,
    maxAttempts: Int? = null,
    delay: kotlin.time.Duration? = null,
  ): UUID {
    val res = jobsDao.insert(session, NewJob(type, buildJsonObject { }, maxAttempts, delay))
    assertTrue(res is ed.unicoach.queue.dao.JobInsertResult.Success, "Failed to enqueue")
    return res.job.id
  }

  private fun assertStatus(
    jobId: UUID,
    expected: JobStatus,
  ) {
    val res = jobsDao.findById(session, jobId)
    assertTrue(res is JobFindResult.Success)
    assertEquals(expected, res.job.status)
  }

  private suspend fun awaitStatus(
    jobId: UUID,
    expected: JobStatus,
    timeoutMillis: Long = 5000L,
  ) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMillis) {
      if (jobsDao.findById(session, jobId).let { it is JobFindResult.Success && it.job.status == expected }) return
      delay(50)
    }
    assertStatus(jobId, expected) // Fails if still not met
  }

  // --- TESTS ---

  @Test
  fun `worker picks up scheduled job and completes it`() =
    runBlocking {
      withTimeout(15.seconds) {
        val completionDeferred = CompletableDeferred<Unit>()
        val handler =
          TestHandler {
            completionDeferred.complete(Unit)
            JobResult.Success
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue()

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        completionDeferred.await()
        awaitStatus(jobId, JobStatus.COMPLETED)
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.COMPLETED)
        val attemptsRes = jobsDao.findAttemptsByJobId(session, jobId)
        assertTrue(attemptsRes is AttemptFindResult.Success)
        assertEquals(1, attemptsRes.attempts.size)
        assertEquals(AttemptStatus.SUCCESS, attemptsRes.attempts.first().status)
      }
    }

  @Test
  fun `worker retries on RetriableFailure with backoff`() =
    runBlocking {
      withTimeout(15.seconds) {
        var executions = 0
        val handler =
          TestHandler(
            config =
              JobTypeConfig(
                maxAttempts = 3,
                backoffStrategy = BackoffStrategy.Fixed(10.milliseconds),
                delayedJobPollInterval = 50.milliseconds,
              ),
          ) {
            executions++
            JobResult.RetriableFailure("Try again")
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue()

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        // wait until dead lettered
        awaitStatus(jobId, JobStatus.DEAD_LETTERED, 8000L)
        worker.stop(5.seconds)

        // Should have retried at least twice before dead-lettering at 3
        assertStatus(jobId, JobStatus.DEAD_LETTERED)
        val attemptsRes = jobsDao.findAttemptsByJobId(session, jobId)
        assertTrue(attemptsRes is AttemptFindResult.Success)
        assertEquals(3, attemptsRes.attempts.size)
        assertTrue(attemptsRes.attempts.all { it.status == AttemptStatus.RETRIABLE_FAILURE })
      }
    }

  @Test
  fun `worker dead-letters immediately on PermanentFailure`() =
    runBlocking {
      withTimeout(15.seconds) {
        val completionDeferred = CompletableDeferred<Unit>()
        val handler =
          TestHandler(config = JobTypeConfig(maxAttempts = 5)) {
            completionDeferred.complete(Unit)
            JobResult.PermanentFailure("Fatal")
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue()

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        completionDeferred.await()
        awaitStatus(jobId, JobStatus.DEAD_LETTERED)
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.DEAD_LETTERED)
        val attemptsRes = jobsDao.findAttemptsByJobId(session, jobId)
        assertTrue(attemptsRes is AttemptFindResult.Success)
        assertEquals(1, attemptsRes.attempts.size)
        assertEquals(AttemptStatus.PERMANENT_FAILURE, attemptsRes.attempts.first().status)
        assertEquals("Fatal", attemptsRes.attempts.first().errorMessage)
      }
    }

  @Test
  fun `worker treats uncaught exception as retriable failure`() =
    runBlocking {
      withTimeout(15.seconds) {
        val completionDeferred = CompletableDeferred<Unit>()
        val handler =
          TestHandler(
            config =
              JobTypeConfig(
                maxAttempts = 2,
                backoffStrategy = BackoffStrategy.Fixed(0.seconds),
                delayedJobPollInterval = 50.milliseconds,
              ),
          ) {
            completionDeferred.complete(Unit)
            throw IllegalStateException("BOOM")
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue()

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        completionDeferred.await()
        awaitStatus(jobId, JobStatus.DEAD_LETTERED)
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.DEAD_LETTERED)
        val attemptsRes = jobsDao.findAttemptsByJobId(session, jobId)
        assertTrue(attemptsRes is AttemptFindResult.Success)
        assertEquals(AttemptStatus.RETRIABLE_FAILURE, attemptsRes.attempts.first().status)
        assertEquals("BOOM", attemptsRes.attempts.first().errorMessage)
      }
    }

  @Test
  fun `worker times out handler execution and records retriable failure`() =
    runBlocking {
      withTimeout(15.seconds) {
        val completionDeferred = CompletableDeferred<Unit>()
        val handler =
          TestHandler(
            config = JobTypeConfig(maxAttempts = 1, executionTimeout = 50.milliseconds, delayedJobPollInterval = 50.milliseconds),
          ) {
            completionDeferred.complete(Unit)
            delay(200) // Hang longer than executionTimeout
            JobResult.Success
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue()

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        completionDeferred.await()
        awaitStatus(jobId, JobStatus.DEAD_LETTERED, 8000L)
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.DEAD_LETTERED) // max attempts 1
        val attemptsRes = jobsDao.findAttemptsByJobId(session, jobId)
        assertTrue(attemptsRes is AttemptFindResult.Success)
        assertEquals(AttemptStatus.RETRIABLE_FAILURE, attemptsRes.attempts.first().status)
        assertEquals("Execution timed out", attemptsRes.attempts.first().errorMessage)
      }
    }

  @Test
  fun `worker resolves max_attempts from job when set`() =
    runBlocking {
      withTimeout(15.seconds) {
        var execs = 0
        val handler =
          TestHandler(
            config =
              JobTypeConfig(
                maxAttempts = 5,
                backoffStrategy = BackoffStrategy.Fixed(0.seconds),
                delayedJobPollInterval = 50.milliseconds,
              ),
          ) {
            execs++
            JobResult.RetriableFailure("Oops")
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        // Overriding handler limit (5) with job limit (2)
        val jobId = enqueue(maxAttempts = 2)

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        awaitStatus(jobId, JobStatus.DEAD_LETTERED, 8000L)
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.DEAD_LETTERED)
        assertEquals(2, execs)
      }
    }

  @Test
  fun `stuck job reaper resets stale RUNNING jobs`() =
    runBlocking {
      withTimeout(15.seconds) {
        // Manually force a stale running job using test connection
        val jobId = enqueue()
        database.createRawConnection().use { conn ->
          conn.createStatement().execute(
            "UPDATE jobs SET status = 'RUNNING', locked_until = NOW() - INTERVAL '1 minute' WHERE id = '$jobId'",
          )
        }

        // Set reaper interval impossibly low to trigger immediately
        val worker = QueueWorker(database, jobsDao, emptyList(), stuckJobCheckInterval = 50.milliseconds)
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        awaitStatus(jobId, JobStatus.SCHEDULED, 8000L)
        worker.stop(5.seconds)

        // Should have been swept back to SCHEDULED
        assertStatus(jobId, JobStatus.SCHEDULED)
      }
    }

  @Test
  fun `completed job reaper deletes old completed jobs`() =
    runBlocking {
      withTimeout(15.seconds) {
        val jobId = enqueue()
        // Manually push updated_at to the past
        database.createRawConnection().use { conn ->
          conn.createStatement().execute("ALTER TABLE jobs DISABLE TRIGGER trigger_03_enforce_jobs_updated_at")
          conn.createStatement().execute(
            "UPDATE jobs SET status = 'COMPLETED', updated_at = NOW() - INTERVAL '2 hours' WHERE id = '$jobId'",
          )
          conn.createStatement().execute("ALTER TABLE jobs ENABLE TRIGGER trigger_03_enforce_jobs_updated_at")
        }

        // Reaper sweeps completed jobs older than 1 minute, checking every 50ms
        val worker =
          QueueWorker(database, jobsDao, emptyList(), finishedJobRetention = 1.minutes, finishedJobReapInterval = 50.milliseconds)
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        // wait for job to be deleted
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000L) {
          if (jobsDao.findById(session, jobId) is JobFindResult.NotFound) break
          delay(50)
        }
        worker.stop(5.seconds)

        // Job should be completely deleted
        val res = jobsDao.findById(session, jobId)
        assertTrue(res is JobFindResult.NotFound)
      }
    }

  /** Sets [jobId]'s status and finish time, past the trigger that would stamp `updated_at` with NOW(). */
  private fun finish(
    jobId: UUID,
    status: JobStatus,
    daysAgo: Int,
  ) {
    database.createRawConnection().use { conn ->
      conn.createStatement().use { stmt ->
        stmt.execute("ALTER TABLE jobs DISABLE TRIGGER trigger_03_enforce_jobs_updated_at")
        try {
          stmt.execute(
            "UPDATE jobs SET status = '${status.value}', updated_at = NOW() - INTERVAL '$daysAgo days' WHERE id = '$jobId'",
          )
        } finally {
          stmt.execute("ALTER TABLE jobs ENABLE TRIGGER trigger_03_enforce_jobs_updated_at")
        }
        stmt.execute(
          "INSERT INTO job_attempts (job_id, attempt_number, started_at, status) " +
            "VALUES ('$jobId', 1, NOW() - INTERVAL '$daysAgo days', 'RETRIABLE_FAILURE')",
        )
      }
    }
  }

  private fun attemptCount(jobId: UUID): Long =
    database.createRawConnection().use { conn ->
      conn.prepareStatement("SELECT count(*) FROM job_attempts WHERE job_id = ?").use { stmt ->
        stmt.setObject(1, jobId)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getLong(1)
        }
      }
    }

  /** Brief 0002 D13: every finished job is reaped 30 days after it finished; an unfinished one never is. */
  @Test
  fun `the reaper deletes completed and dead-lettered jobs past retention and keeps the rest`() {
    runBlocking {
      withTimeout(15.seconds) {
        val reaped = listOf(JobStatus.COMPLETED, JobStatus.DEAD_LETTERED).map { status -> enqueue().also { finish(it, status, 31) } }
        val keptRecent = listOf(JobStatus.COMPLETED, JobStatus.DEAD_LETTERED).map { status -> enqueue().also { finish(it, status, 29) } }
        val keptUnfinished = listOf(JobStatus.SCHEDULED, JobStatus.RUNNING).map { status -> enqueue().also { finish(it, status, 400) } }

        // The default retention (30 days) and stuck-job interval; only the reap interval is short.
        val worker = QueueWorker(database, jobsDao, emptyList(), finishedJobReapInterval = 50.milliseconds)
        worker.start(kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
        try {
          while (reaped.any { jobsDao.findById(session, it) !is JobFindResult.NotFound }) delay(50)
          // A few more passes, so a reaper that took the kept rows too would have done so.
          delay(300)
        } finally {
          worker.stop(5.seconds)
        }

        reaped.forEach { assertEquals(0L, attemptCount(it), "a reaped job's attempts go with it") }
        assertEquals(
          listOf(JobStatus.COMPLETED, JobStatus.DEAD_LETTERED, JobStatus.SCHEDULED, JobStatus.RUNNING),
          (keptRecent + keptUnfinished).map { (jobsDao.findById(session, it) as JobFindResult.Success).job.status },
          "a job finished 29 days ago, and an unfinished one of any age, is kept",
        )
        (keptRecent + keptUnfinished).forEach { assertEquals(1L, attemptCount(it)) }
      }
    }
  }

  /** RFC 267 D-C: a dead-letter is an operator alert, so it is one ERROR line, for every job type. */
  @Test
  fun `a dead-lettered job logs one ERROR line naming its type and id`() {
    runBlocking {
      withTimeout(15.seconds) {
        val logger = org.slf4j.LoggerFactory.getLogger(QueueWorker::class.java) as ch.qos.logback.classic.Logger
        val appender =
          ch.qos.logback.core.read
            .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            .apply { start() }
        logger.addAppender(appender)
        val jobId = enqueue()
        val handler = TestHandler { JobResult.PermanentFailure("the payload names nobody") }
        val worker = QueueWorker(database, jobsDao, listOf(handler))
        worker.start(kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
        try {
          awaitStatus(jobId, JobStatus.DEAD_LETTERED)
        } finally {
          worker.stop(5.seconds)
          logger.detachAppender(appender)
        }

        val errors = appender.list.filter { it.level == ch.qos.logback.classic.Level.ERROR }.map { it.formattedMessage }
        assertEquals(1, errors.size, "one ERROR line per dead-letter; got $errors")
        assertTrue(errors.single().contains(jobId.toString()), "the line names the job: ${errors.single()}")
        assertTrue(errors.single().contains(JobType.TEST_JOB.value), "and its type: ${errors.single()}")
        assertTrue(errors.single().contains("the payload names nobody"), "and its last error: ${errors.single()}")
      }
    }
  }

  @Test
  fun `worker respects per-job-type concurrency`() =
    runBlocking {
      withTimeout(15.seconds) {
        val concurrentExecutions =
          java.util.concurrent.atomic
            .AtomicInteger(0)
        val maxSeen =
          java.util.concurrent.atomic
            .AtomicInteger(0)
        val completedExecutions =
          java.util.concurrent.atomic
            .AtomicInteger(0)

        val handler =
          TestHandler(config = JobTypeConfig(concurrency = 2)) {
            val current = concurrentExecutions.incrementAndGet()
            maxSeen.updateAndGet { maxOf(it, current) }
            delay(100)
            concurrentExecutions.decrementAndGet()
            completedExecutions.incrementAndGet()
            JobResult.Success
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        // Enqueue 5 jobs
        repeat(5) { enqueue() }

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        val start = System.currentTimeMillis()
        while (completedExecutions.get() < 5 && System.currentTimeMillis() - start < 8000L) {
          delay(50)
        }
        worker.stop(5.seconds)

        assertEquals(5, completedExecutions.get())
        assertEquals(2, maxSeen.get())
      }
    }

  @Test
  fun `worker does not pick up jobs with future scheduled_at`() =
    runBlocking {
      withTimeout(15.seconds) {
        var hit = false
        val handler =
          TestHandler {
            hit = true
            JobResult.Success
          }

        val worker = QueueWorker(database, jobsDao, listOf(handler))
        val jobId = enqueue(delay = 1.minutes)

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        worker.start(scope)

        // Let it attempt to pick it up if it was incorrectly going to
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1000L) {
          if (hit) break
          delay(50)
        }
        worker.stop(5.seconds)

        assertStatus(jobId, JobStatus.SCHEDULED) // Untouched
        assertEquals(false, hit)
      }
    }
}
