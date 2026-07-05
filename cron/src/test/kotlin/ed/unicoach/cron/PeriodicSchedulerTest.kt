package ed.unicoach.cron

import ed.unicoach.cron.dao.PeriodicFindResult
import ed.unicoach.cron.dao.PeriodicJobsDao
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeriodicSchedulerTest {
  companion object {
    private lateinit var database: Database
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
      if (::database.isInitialized) database.close()
    }
  }

  private val periodicJobsDao = PeriodicJobsDao()
  private val jobsDao = JobsDao()

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun reset() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE periodic_jobs")
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private fun insertRow(
    name: String = "sweep",
    jobType: String = "TEST_JOB",
    schedule: String = "0 3 * * *",
    nextRunAt: Instant,
    enabled: Boolean = true,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
        VALUES (?, ?, '{}'::jsonb, ?, 'UTC', ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, name)
        stmt.setString(2, jobType)
        stmt.setString(3, schedule)
        stmt.setTimestamp(4, Timestamp.from(nextRunAt))
        stmt.setBoolean(5, enabled)
        stmt.executeUpdate()
      }
  }

  private fun jobsCount(jobType: String? = null): Int =
    connection
      .prepareStatement(
        if (jobType == null) {
          "SELECT COUNT(*) FROM jobs"
        } else {
          "SELECT COUNT(*) FROM jobs WHERE job_type = ?"
        },
      ).use { stmt ->
        if (jobType != null) stmt.setString(1, jobType)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }

  private fun nextRunAtOf(name: String): Instant =
    (periodicJobsDao.findByName(session, PeriodicJobName(name)) as PeriodicFindResult.Success).job.nextRunAt

  private fun lastRunAtOf(name: String): Instant? =
    (periodicJobsDao.findByName(session, PeriodicJobName(name)) as PeriodicFindResult.Success).job.lastRunAt

  /**
   * Reads a row's timestamps straight from SQL, bypassing [PeriodicJobsDao.mapRow]
   * — used for a CORRUPT row, whose unknown `job_type` would make `findByName`
   * fail to reconstruct. `null` when the row is absent.
   */
  private fun rawTimestamps(name: String): Pair<Instant, Instant?>? =
    connection
      .prepareStatement("SELECT next_run_at, last_run_at FROM periodic_jobs WHERE name = ?")
      .use { stmt ->
        stmt.setString(1, name)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          rs.getTimestamp("next_run_at").toInstant() to rs.getTimestamp("last_run_at")?.toInstant()
        }
      }

  private fun scheduler(dao: JobsDao = jobsDao) = PeriodicScheduler(database, dao, periodicJobsDao)

  @Test
  fun `a due row enqueues exactly one job of its type and advances next_run_at`() {
    val past = Instant.now().minusSeconds(120)
    insertRow(name = "sweep", jobType = "TEST_JOB", schedule = "0 3 * * *", nextRunAt = past, enabled = true)

    runBlocking { scheduler().tickOnce() }

    assertEquals(1, jobsCount("TEST_JOB"), "Exactly one job of the row's type must be enqueued")
    assertEquals(1, jobsCount(), "No other job may be enqueued")
    // next_run_at advanced to a future 03:00 UTC boundary; last_run_at stamped.
    assertTrue(nextRunAtOf("sweep").isAfter(past), "next_run_at must advance past the claim instant")
    assertTrue(nextRunAtOf("sweep").isAfter(Instant.now()), "next_run_at must be a future fire")
    assertTrue(lastRunAtOf("sweep") != null, "last_run_at must be stamped on dispatch")
  }

  @Test
  fun `a future row is not dispatched and not advanced`() {
    val future = Instant.now().plusSeconds(3600)
    insertRow(name = "sweep", nextRunAt = future, enabled = true)

    runBlocking { scheduler().tickOnce() }

    assertEquals(0, jobsCount(), "A future row must enqueue nothing")
    assertEquals(future.epochSecond, nextRunAtOf("sweep").epochSecond, "next_run_at must be untouched")
    assertTrue(lastRunAtOf("sweep") == null, "last_run_at must stay null")
  }

  @Test
  fun `a disabled row is not dispatched and not advanced`() {
    val past = Instant.now().minusSeconds(120)
    insertRow(name = "sweep", nextRunAt = past, enabled = false)

    runBlocking { scheduler().tickOnce() }

    assertEquals(0, jobsCount(), "A disabled row must enqueue nothing")
    assertEquals(past.epochSecond, nextRunAtOf("sweep").epochSecond, "next_run_at must be untouched")
  }

  @Test
  fun `an unparseable schedule is parked inert - nothing enqueued, no last_run stamp, no throw`() {
    val past = Instant.now().minusSeconds(120)
    insertRow(name = "broken", schedule = "not a cron", nextRunAt = past, enabled = true)

    runBlocking { scheduler().tickOnce() }

    assertEquals(0, jobsCount(), "A misconfigured row must enqueue nothing")
    // The bad row is PARKED (next_run_at advanced into the future) so it leaves
    // the due window instead of being reclaimed as cheapest-due every tick. It
    // was not run, so last_run_at stays null.
    assertTrue(
      nextRunAtOf("broken").isAfter(Instant.now()),
      "A parked misconfigured row must have next_run_at moved into the future",
    )
    assertTrue(lastRunAtOf("broken") == null, "last_run_at must stay null for a parked (never-run) row")
  }

  @Test
  fun `a broken row does not starve a healthy due row behind it`() {
    // The broken row sorts FIRST by next_run_at (cheapest-due), so a naive drain
    // that stopped on the broken row would never reach the healthy one. Assert
    // the healthy row is still dispatched in the same tick.
    val older = Instant.now().minusSeconds(300)
    val newer = Instant.now().minusSeconds(120)
    insertRow(name = "broken", jobType = "TEST_JOB", schedule = "not a cron", nextRunAt = older, enabled = true)
    insertRow(name = "healthy", jobType = "TEST_JOB", schedule = "0 3 * * *", nextRunAt = newer, enabled = true)

    runBlocking { scheduler().tickOnce() }

    // Healthy row dispatched exactly once despite the broken row being cheapest-due.
    assertEquals(1, jobsCount("TEST_JOB"), "The healthy row must be dispatched, not starved by the broken one")
    assertTrue(nextRunAtOf("healthy").isAfter(newer), "The healthy row's next_run_at must advance")
    assertTrue(lastRunAtOf("healthy") != null, "The healthy row's last_run_at must be stamped")
    // Broken row parked (future next_run_at), never enqueued, never stamped.
    assertTrue(nextRunAtOf("broken").isAfter(Instant.now()), "The broken row must be parked into the future")
    assertTrue(lastRunAtOf("broken") == null, "The broken row must not be stamped (it never ran)")
  }

  @Test
  fun `a corrupt job_type row is parked inert - nothing enqueued, no last_run stamp, no throw`() {
    // An unknown job_type is a reachable row state (no CHECK ties job_type to the
    // enum). claimDue selects it as cheapest-due, mapRow cannot reconstruct it, so
    // the scheduler must PARK it (advance next_run_at out of the due window) rather
    // than fold the reconstruction failure into a DB-failure back-off.
    val past = Instant.now().minusSeconds(120)
    insertRow(name = "corrupt", jobType = "NO_SUCH_JOB_TYPE", schedule = "0 3 * * *", nextRunAt = past, enabled = true)

    runBlocking { scheduler().tickOnce() }

    assertEquals(0, jobsCount(), "A corrupt row must enqueue nothing")
    // findByName would fail to reconstruct the corrupt row, so read raw.
    val (corruptNext, corruptLast) = rawTimestamps("corrupt")!!
    assertTrue(corruptNext.isAfter(Instant.now()), "A parked corrupt row must have next_run_at moved into the future")
    assertTrue(corruptLast == null, "last_run_at must stay null for a parked (never-run) row")
  }

  @Test
  fun `a corrupt job_type row does not starve a healthy due row behind it`() {
    // The corrupt row sorts FIRST by next_run_at (cheapest-due). Before the fix,
    // mapRow's throw folded to ClaimDueResult.DatabaseFailure -> NONE_DUE, halting
    // the drain and leaving the corrupt row cheapest-due forever, starving the
    // healthy row every tick. Assert the healthy row is dispatched in the same tick.
    val older = Instant.now().minusSeconds(300)
    val newer = Instant.now().minusSeconds(120)
    insertRow(name = "corrupt", jobType = "NO_SUCH_JOB_TYPE", schedule = "0 3 * * *", nextRunAt = older, enabled = true)
    insertRow(name = "healthy", jobType = "TEST_JOB", schedule = "0 3 * * *", nextRunAt = newer, enabled = true)

    runBlocking { scheduler().tickOnce() }

    // Healthy row dispatched exactly once despite the corrupt row being cheapest-due.
    assertEquals(1, jobsCount("TEST_JOB"), "The healthy row must be dispatched, not starved by the corrupt one")
    assertTrue(nextRunAtOf("healthy").isAfter(newer), "The healthy row's next_run_at must advance")
    assertTrue(lastRunAtOf("healthy") != null, "The healthy row's last_run_at must be stamped")
    // Corrupt row parked (future next_run_at), never enqueued, never stamped. Read
    // raw: findByName cannot reconstruct the unknown job_type.
    val (corruptNext, corruptLast) = rawTimestamps("corrupt")!!
    assertTrue(corruptNext.isAfter(Instant.now()), "The corrupt row must be parked into the future")
    assertTrue(corruptLast == null, "The corrupt row must not be stamped (it never ran)")
  }

  @Test
  fun `a forced enqueue failure leaves next_run_at unadvanced - nothing commits`() {
    val past = Instant.now().minusSeconds(120)
    insertRow(name = "sweep", nextRunAt = past, enabled = true)

    // A JobsDao whose insert always fails: the whole claim+advance+enqueue
    // transaction must roll back, leaving next_run_at unadvanced.
    val failingDao =
      object : JobsDao() {
        override fun insert(
          session: SqlSession,
          newJob: NewJob,
        ): JobInsertResult = JobInsertResult.DatabaseFailure(RuntimeException("forced insert failure"))
      }

    // The forced failure propagates out of the claim transaction (triggering the
    // rollback in Database.withConnection); the tick loop would log-and-continue,
    // but here we drive tickOnce directly, so we catch the expected throw.
    runBlocking {
      runCatching { scheduler(failingDao).tickOnce() }
    }

    assertEquals(0, jobsCount(), "A rolled-back transaction must leave no job")
    assertEquals(past.epochSecond, nextRunAtOf("sweep").epochSecond, "next_run_at must be unadvanced after rollback")
    assertTrue(lastRunAtOf("sweep") == null, "last_run_at must be unset after rollback")
  }
}
