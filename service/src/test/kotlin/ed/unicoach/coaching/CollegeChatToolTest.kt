package ed.unicoach.coaching

import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The adapter is a verbatim delegate: name/definition come from the wrapped tool
 * unchanged, and execute forwards the input and returns the wrapped result.
 */
class CollegeChatToolTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  // The delegation, not the vocabulary, is under test: an empty codebook is the
  // honest snapshot of a database whose `codebooks` ingest phase never ran.
  private val wrapped = CollegeSearchTool(CollegeSearchService(database), ed.unicoach.college.Codebook.EMPTY)
  private val adapter = CollegeChatTool(wrapped)

  @Test
  fun `name and definition come from the wrapped tool`() {
    assertEquals(CollegeSearchTool.TOOL_NAME, adapter.name)
    assertEquals(wrapped.definition, adapter.definition)
  }

  @Test
  fun `execute delegates and returns the wrapped tool's object`() =
    runBlocking {
      // A malformed input short-circuits in the wrapped tool's parser (no DB read),
      // so the adapter returning the identical structured error proves delegation.
      val badInput = buildJsonObject { put("not_a_field", "x") }
      assertEquals(wrapped.execute(badInput), adapter.execute(badInput))
    }
}
