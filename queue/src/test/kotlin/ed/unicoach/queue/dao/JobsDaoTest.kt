package ed.unicoach.queue.dao

import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.AttemptStatus
import ed.unicoach.queue.Job
import ed.unicoach.queue.JobStatus
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class JobsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val jobsDao = JobsDao()

  private fun simplePayload(tag: String = "test"): JsonObject = JsonObject(mapOf("tag" to JsonPrimitive(tag)))

  private fun insertJob(
    jobType: JobType = JobType.TEST_JOB,
    payload: JsonObject = simplePayload(),
    maxAttempts: Int? = null,
    delay: kotlin.time.Duration? = null,
  ): Job {
    val result = jobsDao.insert(session, NewJob(jobType, payload, maxAttempts, delay))
    assertTrue(result is JobInsertResult.Success, "Expected insert success, got: $result")
    return result.job
  }

  // -------------------------------------------------------------------------
  // insert
  // -------------------------------------------------------------------------

  @Test
  fun `insert creates job with SCHEDULED status and default scheduled_at`() {
    val job = insertJob()
    assertEquals(JobStatus.SCHEDULED, job.status)
    assertNotNull(job.id)
    assertNotNull(job.scheduledAt)
    // scheduled_at should be very close to now (within 5 seconds)
    val delta =
      java.time.Duration
        .between(job.scheduledAt, Instant.now())
        .abs()
    assertTrue(delta.seconds < 5, "scheduled_at should be close to now, delta=${delta.seconds}s")
  }

  @Test
  fun `insert with delay sets future scheduled_at`() {
    val before = Instant.now()
    val job = insertJob(delay = 1.hours)
    assertTrue(job.scheduledAt.isAfter(before.plusSeconds(3500)), "scheduled_at should be ~1h in future")
  }

  @Test
  fun `insert with null max_attempts stores NULL`() {
    val job = insertJob(maxAttempts = null)
    assertNull(job.maxAttempts)
  }

  @Test
  fun `insert with explicit max_attempts stores value`() {
    val job = insertJob(maxAttempts = 5)
    assertEquals(5, job.maxAttempts)
  }

  // -------------------------------------------------------------------------
  // findNextScheduledJob
  // -------------------------------------------------------------------------

  @Test
  fun `findNextScheduledJob returns only SCHEDULED job with scheduled_at in the past`() {
    val job = insertJob()
    val result = jobsDao.findNextScheduledJob(session, JobType.TEST_JOB)
    assertTrue(result is JobFindResult.Success)
    assertEquals(job.id, result.job.id)
  }

  @Test
  fun `findNextScheduledJob excludes future scheduled_at`() {
    insertJob(delay = 1.hours)
    val result = jobsDao.findNextScheduledJob(session, JobType.TEST_JOB)
    assertTrue(result is JobFindResult.NotFound)
  }

  @Test
  fun `findNextScheduledJob uses SKIP LOCKED to prevent double pickup`() {
    insertJob()

    // Open a second connection that locks the row
    val config =
      ed.unicoach.common.config.AppConfig
        .load("common.conf", "db.conf")
        .getOrThrow()
    val dbConfig = DatabaseConfig.from(config).getOrThrow()
    val conn2 = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    conn2.autoCommit = false

    val session2 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = conn2.prepareStatement(sql)
      }

    // Lock the job row on conn2
    val locked = jobsDao.findNextScheduledJob(session2, JobType.TEST_JOB)
    assertTrue(locked is JobFindResult.Success, "Expected to lock job on conn2")

    // session uses conn1 (autocommit=false implicit from test setup, but here conn is autocommit=true
    // so it won't see the lock of conn2 at the same time — we need a separate explicit conn1 txn)
    val conn1 = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    conn1.autoCommit = false
    val session1 =
      object : SqlSession {
        override fun prepareStatement(sql: String) = conn1.prepareStatement(sql)
      }

    // conn2 holds FOR UPDATE; SKIP LOCKED on conn1 should see NotFound
    val result = jobsDao.findNextScheduledJob(session1, JobType.TEST_JOB)
    assertTrue(result is JobFindResult.NotFound, "Expected NotFound when row is locked by another session, got: $result")

    conn2.rollback()
    conn2.close()
    conn1.rollback()
    conn1.close()
  }

  @Test
  fun `findNextScheduledJob returns NotFound when no jobs of the requested type exist`() {
    val result = jobsDao.findNextScheduledJob(session, JobType.TEST_JOB)
    assertTrue(result is JobFindResult.NotFound)
  }

  @Test
  fun `findNextScheduledJob ignores SCHEDULED jobs of a different type`() {
    insertJob(jobType = JobType.TEST_JOB_B)
    val result = jobsDao.findNextScheduledJob(session, JobType.TEST_JOB)
    assertTrue(result is JobFindResult.NotFound, "Expected NotFound for TEST_JOB when only TEST_JOB_B exists")
  }

  // -------------------------------------------------------------------------
  // claimJob
  // -------------------------------------------------------------------------

  @Test
  fun `claimJob transitions SCHEDULED to RUNNING setting locked_until via SQL`() {
    val job = insertJob()
    val result = jobsDao.claimJob(session, job.id, 10.minutes)
    assertTrue(result is JobUpdateResult.Success)
    assertEquals(JobStatus.RUNNING, result.job.status)
    assertNotNull(result.job.lockedUntil)
    assertTrue(result.job.lockedUntil!!.isAfter(Instant.now()), "locked_until should be in the future")
  }

  // -------------------------------------------------------------------------
  // updateStatus
  // -------------------------------------------------------------------------

  @Test
  fun `completeJob transitions RUNNING to COMPLETED clearing locked_until`() {
    val job = insertJob()
    jobsDao.claimJob(session, job.id, 10.minutes)
    val result = jobsDao.completeJob(session, job.id)
    assertTrue(result is JobUpdateResult.Success)
    assertEquals(JobStatus.COMPLETED, result.job.status)
    assertNull(result.job.lockedUntil)
  }

  @Test
  fun `deadLetterJob transitions RUNNING to DEAD_LETTERED clearing locked_until`() {
    val job = insertJob()
    jobsDao.claimJob(session, job.id, 10.minutes)
    val result = jobsDao.deadLetterJob(session, job.id)
    assertTrue(result is JobUpdateResult.Success)
    assertEquals(JobStatus.DEAD_LETTERED, result.job.status)
    assertNull(result.job.lockedUntil)
  }

  // -------------------------------------------------------------------------
  // reschedule
  // -------------------------------------------------------------------------

  @Test
  fun `reschedule resets to SCHEDULED with future scheduled_at`() {
    val job = insertJob()
    jobsDao.claimJob(session, job.id, 10.minutes)
    val result = jobsDao.reschedule(session, job.id, 30.minutes)
    assertTrue(result is JobUpdateResult.Success)
    assertEquals(JobStatus.SCHEDULED, result.job.status)
    assertTrue(result.job.scheduledAt.isAfter(Instant.now()), "scheduled_at should be in the future after reschedule")
    assertNull(result.job.lockedUntil)
  }

  // -------------------------------------------------------------------------
  // insertAttempt
  // -------------------------------------------------------------------------

  private fun claimedJob(): Pair<Job, Job> {
    val job = insertJob()
    val claimed = jobsDao.claimJob(session, job.id, 10.minutes)
    println("CLAIM JOB RESULT: $claimed")
    if (claimed is JobUpdateResult.DatabaseFailure) println(claimed.error.exception.stackTraceToString())
    assertTrue(claimed is JobUpdateResult.Success)
    return job to claimed.job
  }

  @Test
  fun `insertAttempt records attempt with all fields`() {
    val (_, claimed) = claimedJob()
    val result =
      jobsDao.insertAttempt(
        session = session,
        jobId = claimed.id,
        attemptNumber = 1,
        startedAt = claimed.updatedAt,
        status = AttemptStatus.SUCCESS,
        errorMessage = null,
      )
    assertTrue(result is AttemptInsertResult.Success)
    val attempt = result.attempt
    assertEquals(claimed.id, attempt.jobId)
    assertEquals(1, attempt.attemptNumber)
    assertEquals(AttemptStatus.SUCCESS, attempt.status)
    assertNull(attempt.errorMessage)
    assertNotNull(attempt.finishedAt)
  }

  @Test
  fun `insertAttempt enforces unique job_id and attempt_number`() {
    val (_, claimed) = claimedJob()
    val r1 = jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.SUCCESS)
    assertTrue(r1 is AttemptInsertResult.Success)
    val r2 = jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.RETRIABLE_FAILURE)
    assertTrue(r2 is AttemptInsertResult.DatabaseFailure, "Expected DatabaseFailure for duplicate attempt_number, got: $r2")
  }

  @Test
  fun `insertAttempt constraint rejects invalid status`() {
    val (_, claimed) = claimedJob()
    // Bypass the enum to force an invalid string into the DB
    val sql =
      """
      INSERT INTO job_attempts (job_id, attempt_number, started_at, status)
      VALUES (?, ?, NOW(), 'INVALID_STATUS')
      """.trimIndent()
    val stmt = connection.prepareStatement(sql)
    stmt.setObject(1, claimed.id)
    stmt.setInt(2, 1)
    var threw = false
    try {
      stmt.executeUpdate()
    } catch (e: Exception) {
      threw = true
    } finally {
      stmt.close()
    }
    assertTrue(threw, "Expected a constraint violation for invalid attempt status")
  }

  @Test
  fun `insertAttempt constraint rejects oversized error_message`() {
    val (_, claimed) = claimedJob()
    val oversized = "e".repeat(4097)
    val result = jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.PERMANENT_FAILURE, oversized)
    assertTrue(result is AttemptInsertResult.DatabaseFailure, "Expected DatabaseFailure for oversized error_message")
  }

  // -------------------------------------------------------------------------
  // countAttempts / findAttemptsByJobId
  // -------------------------------------------------------------------------

  @Test
  fun `countAttempts returns correct count`() {
    val (_, claimed) = claimedJob()
    jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.RETRIABLE_FAILURE)
    // Re-claim to get a new updatedAt for second attempt
    jobsDao.reschedule(session, claimed.id, 0.seconds)
    val reClaimed = jobsDao.claimJob(session, claimed.id, 10.minutes)
    assertTrue(reClaimed is JobUpdateResult.Success)
    jobsDao.insertAttempt(session, claimed.id, 2, reClaimed.job.updatedAt, AttemptStatus.SUCCESS)

    val result = jobsDao.countAttempts(session, claimed.id)
    assertTrue(result is AttemptCountResult.Success)
    assertEquals(2, result.count)
  }

  @Test
  fun `findAttemptsByJobId returns attempts ordered by attempt_number`() {
    val (_, claimed) = claimedJob()
    jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.RETRIABLE_FAILURE)
    jobsDao.reschedule(session, claimed.id, 0.seconds)
    val reClaimed = jobsDao.claimJob(session, claimed.id, 10.minutes)
    assertTrue(reClaimed is JobUpdateResult.Success)
    jobsDao.insertAttempt(session, claimed.id, 2, reClaimed.job.updatedAt, AttemptStatus.SUCCESS)

    val result = jobsDao.findAttemptsByJobId(session, claimed.id)
    assertTrue(result is AttemptFindResult.Success)
    val attempts = result.attempts
    assertEquals(2, attempts.size)
    assertEquals(1, attempts[0].attemptNumber)
    assertEquals(2, attempts[1].attemptNumber)
  }

  // -------------------------------------------------------------------------
  // resetStuckRunning
  // -------------------------------------------------------------------------

  @Test
  fun `resetStuckRunning resets stale RUNNING jobs`() {
    val job = insertJob()
    // Set the job into RUNNING with a past locked_until via raw SQL
    connection.createStatement().use { stmt ->
      stmt.execute(
        "UPDATE jobs SET status = 'RUNNING', locked_until = NOW() - INTERVAL '1 minute' WHERE id = '${job.id}'",
      )
    }
    val result = jobsDao.resetStuckRunning(session)
    assertTrue(result is JobResetResult.Success)
    assertEquals(1, result.count)

    val found = jobsDao.findById(session, job.id)
    assertTrue(found is JobFindResult.Success)
    assertEquals(JobStatus.SCHEDULED, found.job.status)
    assertNull(found.job.lockedUntil)
  }

  @Test
  fun `resetStuckRunning ignores RUNNING jobs with future locked_until`() {
    val job = insertJob()
    jobsDao.claimJob(session, job.id, 10.minutes)
    val result = jobsDao.resetStuckRunning(session)
    assertTrue(result is JobResetResult.Success)
    assertEquals(0, result.count)
  }

  @Test
  fun `resetStuckRunning returns count equal to number of stale jobs reset`() {
    val job1 = insertJob()
    val job2 = insertJob()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "UPDATE jobs SET status = 'RUNNING', locked_until = NOW() - INTERVAL '1 second' WHERE id IN ('${job1.id}', '${job2.id}')",
      )
    }
    val result = jobsDao.resetStuckRunning(session)
    assertTrue(result is JobResetResult.Success)
    assertEquals(2, result.count)
  }

  // -------------------------------------------------------------------------
  // deleteBefore
  // -------------------------------------------------------------------------

  @Test
  fun `deleteBefore removes old jobs with matching statuses via SQL interval`() {
    val job = insertJob()
    // Manually push updated_at into the past
    connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE jobs DISABLE TRIGGER trigger_03_enforce_jobs_updated_at")
      stmt.execute("UPDATE jobs SET updated_at = NOW() - INTERVAL '2 hours' WHERE id = '${job.id}'")
      stmt.execute("ALTER TABLE jobs ENABLE TRIGGER trigger_03_enforce_jobs_updated_at")
    }
    val result = jobsDao.deleteBefore(session, setOf(JobStatus.SCHEDULED), 1.hours)
    assertTrue(result is JobDeleteResult.Success)
    assertEquals(1, result.count)
  }

  @Test
  fun `deleteBefore ignores jobs with statuses not in the provided set`() {
    val job = insertJob()
    connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE jobs DISABLE TRIGGER trigger_03_enforce_jobs_updated_at")
      stmt.execute("UPDATE jobs SET updated_at = NOW() - INTERVAL '2 hours' WHERE id = '${job.id}'")
      stmt.execute("ALTER TABLE jobs ENABLE TRIGGER trigger_03_enforce_jobs_updated_at")
    }
    // Only targeting COMPLETED — job is SCHEDULED, should not be deleted
    val result = jobsDao.deleteBefore(session, setOf(JobStatus.COMPLETED), 1.hours)
    assertTrue(result is JobDeleteResult.Success)
    assertEquals(0, result.count)
  }

  @Test
  fun `deleteBefore ignores jobs newer than the duration`() {
    insertJob()
    // Job was just created (updated_at ~= NOW()), should not be deleted by 1h threshold
    val result = jobsDao.deleteBefore(session, setOf(JobStatus.SCHEDULED), 1.hours)
    assertTrue(result is JobDeleteResult.Success)
    assertEquals(0, result.count)
  }

  // -------------------------------------------------------------------------
  // deleteByIds
  // -------------------------------------------------------------------------

  @Test
  fun `deleteByIds cascades to job_attempts`() {
    val (_, claimed) = claimedJob()
    jobsDao.insertAttempt(session, claimed.id, 1, claimed.updatedAt, AttemptStatus.SUCCESS)

    val deleteResult = jobsDao.deleteByIds(session, listOf(claimed.id))
    assertTrue(deleteResult is JobDeleteResult.Success)
    assertEquals(1, deleteResult.count)

    // Verify job_attempts were also deleted via CASCADE
    connection.prepareStatement("SELECT COUNT(*) FROM job_attempts WHERE job_id = ?").use { stmt ->
      stmt.setObject(1, claimed.id)
      stmt.executeQuery().use { rs ->
        rs.next()
        assertEquals(0, rs.getInt(1), "job_attempts should have been cascade-deleted")
      }
    }
  }

  // -------------------------------------------------------------------------
  // findById
  // -------------------------------------------------------------------------

  @Test
  fun `findById returns job`() {
    val job = insertJob()
    val result = jobsDao.findById(session, job.id)
    assertTrue(result is JobFindResult.Success)
    assertEquals(job.id, result.job.id)
  }

  @Test
  fun `findById returns NotFound for nonexistent ID`() {
    val result = jobsDao.findById(session, UUID.randomUUID())
    assertTrue(result is JobFindResult.NotFound)
  }

  // -------------------------------------------------------------------------
  // countByStatus
  // -------------------------------------------------------------------------

  @Test
  fun `countByStatus returns counts grouped by status`() {
    insertJob()
    insertJob()
    val job3 = insertJob()
    jobsDao.claimJob(session, job3.id, 10.minutes)

    val result = jobsDao.countByStatus(session)
    assertTrue(result is JobCountResult.Success)
    assertEquals(2, result.counts[JobStatus.SCHEDULED])
    assertEquals(1, result.counts[JobStatus.RUNNING])
  }

  @Test
  fun `countByStatus filters by job type when specified`() {
    insertJob(jobType = JobType.TEST_JOB)
    insertJob(jobType = JobType.TEST_JOB)
    insertJob(jobType = JobType.TEST_JOB_B)

    val result = jobsDao.countByStatus(session, JobType.TEST_JOB)
    assertTrue(result is JobCountResult.Success)
    assertEquals(2, result.counts[JobStatus.SCHEDULED])
    assertNull(result.counts.entries.find { it.value > 0 && it.key != JobStatus.SCHEDULED })
  }

  // -------------------------------------------------------------------------
  // Constraint tests
  // -------------------------------------------------------------------------

  @Test
  fun `payload size constraint rejects oversized payloads`() {
    // Build a payload exceeding 65536 bytes
    val bigValue = "x".repeat(65537)
    val oversized = JsonObject(mapOf("data" to JsonPrimitive(bigValue)))
    val result = jobsDao.insert(session, NewJob(JobType.TEST_JOB, oversized, null, null))
    assertTrue(result is JobInsertResult.DatabaseFailure, "Expected DatabaseFailure for oversized payload, got: $result")
  }

  @Test
  fun `status constraint rejects invalid status values`() {
    val job = insertJob()
    val stmt = connection.prepareStatement("UPDATE jobs SET status = 'INVALID_STATUS' WHERE id = ?")
    stmt.setObject(1, job.id)
    var threw = false
    try {
      stmt.executeUpdate()
    } catch (e: Exception) {
      threw = true
    } finally {
      stmt.close()
    }
    assertTrue(threw, "Expected constraint violation for invalid status")
  }
}
