package ed.unicoach.rest.plugins

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.rest.auth.SessionConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class SessionExpiryPluginTest {
  companion object {
    private lateinit var database: Database
    private lateinit var queueService: QueueService

    private val testSessionConfig =
      SessionConfig(
        expiration = Duration.ofDays(7),
        cookieName = "test_session",
        cookieDomain = "localhost",
        cookieSecure = false,
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        DatabaseConfig
          .from(config)
          .getOrThrow()
      database = Database(dbConfig)
      queueService = QueueService(database)
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
    database.withConnection { session ->
      session.prepareStatement("TRUNCATE TABLE jobs CASCADE").use { it.execute() }
    }
  }

  private fun countJobs(): Int =
    database.withConnection { session ->
      session.prepareStatement("SELECT COUNT(*) FROM jobs WHERE job_type = 'SESSION_EXTEND_EXPIRY'").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    }

  @Test
  fun `plugin does not enqueue when no session cookie is present`() =
    testApplication {
      install(SessionExpiryPlugin) {
        this.sessionConfig = testSessionConfig
        this.queueService = Companion.queueService
        this.ignorePathPrefixes = setOf("/health")
      }
      routing {
        get("/api/v1/ping") {
          call.respondText("pong")
        }
      }

      val response = client.get("/api/v1/ping")
      assertEquals(HttpStatusCode.OK, response.status)

      // Allow fire-and-forget coroutine to complete
      delay(500)
      assertEquals(0, countJobs())
    }

  @Test
  fun `plugin does not enqueue for non-API paths`() =
    testApplication {
      install(SessionExpiryPlugin) {
        this.sessionConfig = testSessionConfig
        this.queueService = Companion.queueService
        this.ignorePathPrefixes = setOf("/health")
      }
      routing {
        get("/health") {
          call.respondText("ok")
        }
      }

      val response =
        client.get("/health") {
          header("Cookie", "test_session=some-token-value")
        }
      assertEquals(HttpStatusCode.OK, response.status)

      delay(500)
      assertEquals(0, countJobs())
    }

  @Test
  fun `plugin does not enqueue for non-2xx responses`() =
    testApplication {
      install(SessionExpiryPlugin) {
        this.sessionConfig = testSessionConfig
        this.queueService = Companion.queueService
        this.ignorePathPrefixes = setOf("/health")
      }
      routing {
        get("/api/v1/unauthorized") {
          call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        }
      }

      val response =
        client.get("/api/v1/unauthorized") {
          header("Cookie", "test_session=some-token-value")
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)

      delay(500)
      assertEquals(0, countJobs())
    }

  @Test
  fun `plugin enqueues for valid API request with session cookie`() =
    testApplication {
      install(SessionExpiryPlugin) {
        this.sessionConfig = testSessionConfig
        this.queueService = Companion.queueService
        this.ignorePathPrefixes = setOf("/health")
      }
      routing {
        get("/api/v1/ping") {
          call.respondText("pong")
        }
      }

      val response =
        client.get("/api/v1/ping") {
          header("Cookie", "test_session=my-secret-token")
        }
      assertEquals(HttpStatusCode.OK, response.status)

      // Allow fire-and-forget coroutine to complete
      delay(1000)
      assertEquals(1, countJobs())
    }

  @Test
  fun `plugin logs error and does not crash on enqueue failure`() =
    testApplication {
      // Use a database that is closed to simulate enqueue failure
      val closedDb = run {
        val config =
          AppConfig
            .load("common.conf", "db.conf")
            .getOrThrow()
        val dbConfig =
          DatabaseConfig
            .from(config)
            .getOrThrow()
        Database(dbConfig)
      }
      closedDb.close()
      val failingQueueService = QueueService(closedDb)

      install(SessionExpiryPlugin) {
        this.sessionConfig = testSessionConfig
        this.queueService = failingQueueService
        this.ignorePathPrefixes = setOf("/health")
      }
      routing {
        get("/api/v1/ping") {
          call.respondText("pong")
        }
      }

      val response =
        client.get("/api/v1/ping") {
          header("Cookie", "test_session=some-token-value")
        }
      // The response is still 200 — the plugin does not interfere with response delivery
      assertEquals(HttpStatusCode.OK, response.status)
    }
}
