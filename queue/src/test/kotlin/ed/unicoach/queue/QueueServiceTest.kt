package ed.unicoach.queue

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
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
  fun `enqueue creates SCHEDULED job with immediate scheduled_at`() {
    val result = service.enqueue(JobType.TEST_JOB, simplePayload())
    assertTrue(result is EnqueueResult.Success)
    val job = result.job
    assertEquals(JobStatus.SCHEDULED, job.status)
    assertNotNull(job.id)
  }

  @Test
  fun `enqueue with delay sets future scheduled_at`() {
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
  fun `enqueue with custom max_attempts stores value on job`() {
    val result = service.enqueue(JobType.TEST_JOB, simplePayload(), maxAttempts = 3)
    assertTrue(result is EnqueueResult.Success)
    assertEquals(3, result.job.maxAttempts)
  }

  @Test
  fun `enqueue with null max_attempts stores NULL`() {
    val result = service.enqueue(JobType.TEST_JOB, simplePayload(), maxAttempts = null)
    assertTrue(result is EnqueueResult.Success)
    assertNull(result.job.maxAttempts)
  }
}
