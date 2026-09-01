package ed.unicoach.coaching

import ed.unicoach.chat.ToolRegistry
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The adapter is a verbatim delegate (RFC 153 D72): name/definition come from
 * the wrapped tool unchanged, and execute forwards the input and returns the
 * wrapped result. The registry case is here too, because the failure it guards
 * — two tools advertising one name — is a construction-time `require` that only
 * fires when both are registered together.
 */
class SimilarCollegesChatToolTest {
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
  private val service = CollegeSearchService(database)
  private val wrapped = SimilarCollegesTool(service, ed.unicoach.college.Codebook.EMPTY)
  private val adapter = SimilarCollegesChatTool(wrapped)

  @Test
  fun `name and definition come from the wrapped tool`() {
    assertEquals(SimilarCollegesTool.TOOL_NAME, adapter.name)
    assertEquals(wrapped.definition, adapter.definition)
    assertEquals(adapter.name, adapter.definition["name"]!!.toString().trim('"'), "the registry contract")
  }

  @Test
  fun `execute delegates and returns the wrapped tool's object`() =
    runBlocking {
      // A malformed input short-circuits in the wrapped tool's parser (no DB read),
      // so the adapter returning the identical structured error proves delegation.
      val badInput = buildJsonObject { put("not_a_field", "x") }
      assertEquals(wrapped.execute(badInput), adapter.execute(badInput))
    }

  @Test
  fun `it registers beside the search tool under a name of its own`() {
    val search = CollegeChatTool(CollegeSearchTool(service, ed.unicoach.college.Codebook.EMPTY))
    // A duplicate name is a construction-time `require` failure, so a registry
    // that CONSTRUCTS is the assertion that the two names are distinct.
    val registry = ToolRegistry(listOf(search, adapter))

    assertNotNull(registry.get(SimilarCollegesTool.TOOL_NAME), "the coach can dispatch to it")
    assertSame(adapter.definition, registry.get(SimilarCollegesTool.TOOL_NAME)!!.definition)
    assertEquals(
      listOf(CollegeSearchTool.TOOL_NAME, SimilarCollegesTool.TOOL_NAME),
      registry.definitions().map { it["name"]!!.toString().trim('"') },
      "every turn advertises both, in registration order",
    )
  }
}
