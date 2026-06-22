package ed.unicoach.coaching.extraction

import ed.unicoach.chat.LogOnlyChatProvider
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionHandlerTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  private val extractionConfig =
    ExtractionConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  /** A stub service returning a fixed result, recording the call args. */
  private inner class StubService(
    private val result: ExtractionResult,
  ) : ExtractionService(database, LogOnlyChatProvider(), extractionConfig) {
    var lastConvoId: ConvoId? = null
    var lastThrough: ConvoRequestId? = null

    override suspend fun extract(
      convoId: ConvoId,
      throughRequestId: ConvoRequestId,
    ): ExtractionResult {
      lastConvoId = convoId
      lastThrough = throughRequestId
      return result
    }
  }

  @Test
  fun `valid payload delegates and returns Success`() =
    runBlocking {
      val stub = StubService(ExtractionResult.Success)
      val handler = ExtractionHandler(stub)
      val convo = UUID.randomUUID()
      val payload =
        buildJsonObject {
          put("convoId", convo.toString())
          put("throughRequestId", 42L)
        }

      val result = handler.execute(payload)
      assertEquals(JobResult.Success, result)
      assertEquals(ConvoId(convo), stub.lastConvoId)
      assertEquals(ConvoRequestId(42L), stub.lastThrough)
    }

  @Test
  fun `malformed payload returns PermanentFailure`() =
    runBlocking {
      val handler = ExtractionHandler(StubService(ExtractionResult.Success))
      val payload = buildJsonObject { put("nonsense", true) }

      val result = handler.execute(payload)
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `non-uuid convoId returns PermanentFailure`() =
    runBlocking {
      val handler = ExtractionHandler(StubService(ExtractionResult.Success))
      val payload =
        buildJsonObject {
          put("convoId", "not-a-uuid")
          put("throughRequestId", 1L)
        }
      val result = handler.execute(payload)
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `transient service error returns RetriableFailure carrying the root cause`() =
    runBlocking {
      val cause = IllegalStateException("provider exploded")
      val stub = StubService(ExtractionResult.TransientFailure("boom", cause))
      val handler = ExtractionHandler(stub)
      val payload =
        buildJsonObject {
          put("convoId", UUID.randomUUID().toString())
          put("throughRequestId", 7L)
        }
      val result = handler.execute(payload)
      assertTrue(result is JobResult.RetriableFailure, "got $result")
      assertEquals("boom", result.message)
      assertEquals(cause, result.cause)
    }

  @Test
  fun `config advertises EXTRACT_CONVERSATION with executionTimeout below lockDuration`() {
    val handler = ExtractionHandler(StubService(ExtractionResult.Success))
    assertEquals(JobType.EXTRACT_CONVERSATION, handler.jobType)
    assertTrue(
      handler.config.executionTimeout < handler.config.lockDuration,
      "executionTimeout ${handler.config.executionTimeout} must be < lockDuration ${handler.config.lockDuration}",
    )
  }
}
