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
  fun resetDatabase() {
    database.withConnection { s ->
      s.prepareStatement("TRUNCATE TABLE jobs CASCADE").use { stmt ->
        stmt.execute()
      }
    }
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
          QueueWorker(database, jobsDao, emptyList(), completedJobRetention = 1.minutes, completedJobReapInterval = 50.milliseconds)
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
