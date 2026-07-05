package ed.unicoach.queue

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class QueueServiceTest {
  companion object {
    private lateinit var database: Database
    private lateinit var jdbcUrl: String
    private lateinit var dbUser: String
    private var dbPassword: String? = null

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      jdbcUrl = dbConfig.jdbcUrl
      dbUser = dbConfig.user
      dbPassword = dbConfig.password
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) {
        database.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    val conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "")
    conn.use { c ->
      c.createStatement().use { stmt ->
        stmt.execute("TRUNCATE TABLE jobs CASCADE")
      }
    }
  }

  private val service get() = QueueService(database)

  private fun simplePayload(): JsonObject = JsonObject(mapOf("k" to JsonPrimitive("v")))

  @Test
  fun `enqueue creates SCHEDULED job with immediate scheduled_at`() =
    runTest {
      val result = service.enqueue(JobType.TEST_JOB, simplePayload())
      assertTrue(result is EnqueueResult.Success)
      val job = result.job
      assertEquals(JobStatus.SCHEDULED, job.status)
      assertNotNull(job.id)
    }

  @Test
  fun `enqueue with delay sets future scheduled_at`() =
    runTest {
      val result = service.enqueue(JobType.TEST_JOB, simplePayload(), delay = 1.hours)
      assertTrue(result is EnqueueResult.Success)
      val job = result.job
      assertTrue(
        job.scheduledAt.isAfter(
          java.time.Instant
            .now()
            .plusSeconds(3500),
        ),
        "scheduled_at should be ~1h in future",
      )
    }

  @Test
  fun `enqueue with custom max_attempts stores value on job`() =
    runTest {
      val result = service.enqueue(JobType.TEST_JOB, simplePayload(), maxAttempts = 3)
      assertTrue(result is EnqueueResult.Success)
      assertEquals(3, result.job.maxAttempts)
    }

  @Test
  fun `enqueue with null max_attempts stores NULL`() =
    runTest {
      val result = service.enqueue(JobType.TEST_JOB, simplePayload(), maxAttempts = null)
      assertTrue(result is EnqueueResult.Success)
      assertNull(result.job.maxAttempts)
    }

  @Test
  fun `enqueue(session) inserts a SCHEDULED SEND_EMAIL job on the caller's connection`() =
    runTest {
      val payload = JsonObject(mapOf("to" to JsonPrimitive("user@example.com")))
      val result =
        database.withConnection { session ->
          service.enqueue(session, JobType.SEND_EMAIL, payload)
        }

      assertTrue(result is EnqueueResult.Success)
      assertEquals(JobStatus.SCHEDULED, result.job.status)
      assertEquals(JobType.SEND_EMAIL, result.job.jobType)
      assertEquals(payload, result.job.payload)
      assertEquals(1, jobCount(JobType.SEND_EMAIL))
    }

  @Test
  fun `enqueue(session) rolls back with the surrounding transaction`() =
    runTest {
      runCatching {
        database.withConnection { session ->
          service.enqueue(session, JobType.SEND_EMAIL, simplePayload())
          throw RuntimeException("abort the surrounding transaction")
        }
      }

      // The enqueue shared the caller's transaction, so the rollback removed it.
      assertEquals(0, jobCount(JobType.SEND_EMAIL))
    }

  private fun jobCount(jobType: JobType): Int {
    val conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "")
    conn.use { c ->
      c.prepareStatement("SELECT COUNT(*) FROM jobs WHERE job_type = ?").use { stmt ->
        stmt.setString(1, jobType.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getInt(1)
        }
      }
    }
  }
}
