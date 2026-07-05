package ed.unicoach.coaching.synthesis

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SynthesisSweepHandlerTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
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

  @BeforeEach
  fun reset() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
      stmt.execute("TRUNCATE TABLE students, users CASCADE")
    }
  }

  /** Inserts a user + active student and returns the student's id string. */
  private fun seedStudent(): String {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sweep-$userId@test.com', 'Sweep User', 'ahash')",
      )
      stmt.execute(
        """
        INSERT INTO students (id, user_id, expected_high_school_graduation_year)
        VALUES ('$studentId', '$userId', 2028)
        """.trimIndent(),
      )
    }
    return studentId.toString()
  }

  private fun countSynthesizeJobs(): Int =
    connection
      .prepareStatement("SELECT COUNT(*) FROM jobs WHERE job_type = ?")
      .use { stmt ->
        stmt.setString(1, JobType.SYNTHESIZE_STUDENT.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }

  @Test
  fun `config advertises SYNTHESIS_SWEEP and executionTimeout under lockDuration`() {
    val handler = SynthesisSweepHandler(database, QueueService(database))
    assertEquals(JobType.SYNTHESIS_SWEEP, handler.jobType)
    assertTrue(
      handler.config.executionTimeout < handler.config.lockDuration,
      "executionTimeout must be strictly less than lockDuration so a slow sweep cannot outlive its lock",
    )
    assertEquals(1, handler.config.maxAttempts, "maxAttempts must be 1: next-tick re-production is the retry")
  }

  @Test
  fun `with N active students the sweep enqueues N SYNTHESIZE_STUDENT jobs and succeeds`() =
    runTest {
      repeat(3) { seedStudent() }

      val handler = SynthesisSweepHandler(database, QueueService(database))
      val result = handler.execute(JsonObject(emptyMap()))

      assertTrue(result is JobResult.Success, "Expected Success, got: $result")
      assertEquals(3, countSynthesizeJobs(), "One SYNTHESIZE_STUDENT job per active student")
    }

  @Test
  fun `no active students yields Success and zero enqueues`() =
    runTest {
      val handler = SynthesisSweepHandler(database, QueueService(database))
      val result = handler.execute(JsonObject(emptyMap()))

      assertTrue(result is JobResult.Success, "Expected Success, got: $result")
      assertEquals(0, countSynthesizeJobs(), "No students -> no enqueues")
    }

  @Test
  fun `a single enqueue failure is logged and does not abort the sweep`() =
    runTest {
      repeat(3) { seedStudent() }

      // A JobsDao whose 2nd insert fails: the remaining students must still be
      // enqueued and the sweep must still return Success (best-effort fan-out).
      val calls = AtomicInteger(0)
      val flakyDao =
        object : JobsDao() {
          override fun insert(
            session: SqlSession,
            newJob: NewJob,
          ): JobInsertResult =
            if (calls.incrementAndGet() == 2) {
              JobInsertResult.DatabaseFailure(RuntimeException("forced enqueue failure"))
            } else {
              super.insert(session, newJob)
            }
        }

      val handler = SynthesisSweepHandler(database, QueueService(database, flakyDao))
      val result = handler.execute(JsonObject(emptyMap()))

      assertTrue(result is JobResult.Success, "A single enqueue failure must not fail the sweep, got: $result")
      assertEquals(2, countSynthesizeJobs(), "The other two students are still enqueued")
    }
}
