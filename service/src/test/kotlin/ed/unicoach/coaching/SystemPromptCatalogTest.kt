package ed.unicoach.coaching

import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.fitlens.FitLensConfig
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The contract between `service.conf` and the migration-seeded `system_prompts`
 * catalog (RFC 129): every `(name, version)` pair the runtime pins must already
 * exist as a catalog row.
 *
 * `system_prompts` is insert-only and immutable (RFC 33, `db/schema/0007`), and
 * `bin/test` re-migrates the database before every run, so the catalog is
 * complete before the first test executes. No fixture seeds or truncates it.
 * A pin with no row therefore used to surface as a 500 deep inside an unrelated
 * module's routing test (RFC 124); this class turns it into one named failure
 * in the module that owns the pin.
 *
 * The pairs are read from configuration, never hard-coded — that is the point:
 * a version bump in `service.conf` costs a seed migration and the pin, and zero
 * test edits.
 */
class SystemPromptCatalogTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val appConfig =
    ed.unicoach.common.config.AppConfig
      .load("service.conf")
      .getOrThrow()

  private val coaching = CoachingConfig.from(appConfig).getOrThrow()
  private val extraction = ExtractionConfig.from(appConfig).getOrThrow()
  private val synthesis = SynthesisConfig.from(appConfig).getOrThrow()

  // fitLens.enabled is false by default and is deliberately ignored: the pin
  // must resolve whether or not the feature is switched on, which is precisely
  // the case RFC 124 broke.
  private val fitLens = FitLensConfig.from(appConfig).getOrThrow()

  /**
   * Resolves one pinned pair, naming the config key that carries it so the
   * failure points at the edit that has to be made.
   */
  private fun assertPinned(
    versionKey: String,
    name: String,
    version: String,
  ) {
    SystemPromptsDao.findByNameAndVersion(session, name, version).getOrElse {
      fail(
        "$versionKey pins system_prompt ($name, $version), which the migration-seeded " +
          "catalog does not contain. Add a seed migration under db/schema/ before pinning it.",
      )
    }
  }

  @Test
  fun `every system prompt service dot conf pins exists in the migration-seeded catalog`() {
    assertPinned("coaching.systemPromptVersion", coaching.systemPromptName, coaching.systemPromptVersion)
    assertPinned("extraction.promptVersion", extraction.promptName, extraction.promptVersion)
    assertPinned("synthesis.promptVersion", synthesis.promptName, synthesis.promptVersion)
    assertPinned("fitLens.queryPromptVersion", fitLens.queryPromptName, fitLens.queryPromptVersion)
    assertPinned("fitLens.reasonPromptVersion", fitLens.reasonPromptName, fitLens.reasonPromptVersion)
  }

  /**
   * The 0047 seed's structural contract (RFC 135, mirroring 0044's v2-over-v1
   * convention): v3 is the v2 body byte-identical as a prefix, joined by a
   * single space to exactly one appended paragraph — the know-your-real-price
   * instruction. The paragraph's markers are asserted, not its full copy: the
   * seed migration is the single home of the approved wording.
   */
  @Test
  fun `coach v3 is the v2 body verbatim plus one appended cost paragraph`() {
    val v2 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v2").getOrThrow().body
    val v3 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v3").getOrThrow().body
    assertTrue(v3.startsWith(v2), "the v2 prefix must be byte-identical, so the cost paragraph is the only change")
    val appended = v3.removePrefix(v2)
    assertTrue(appended.startsWith(" When the student has schools on their college list"), "single-space paragraph join")
    assertTrue(appended.contains(CollegeCostChatTool.TOOL_NAME), "the paragraph must name the cost tool")
    assertTrue(appended.contains("precision_offer"), "the paragraph must key the in-answer invitation off the result")
    assertTrue(
      appended.contains("offer to record their household income band"),
      "the paragraph must cover recording the band in-conversation",
    )
    // The paragraph deliberately does NOT name the write tool: the coach is
    // told what to offer; which tool records it is the tool description's job.
    assertFalse(appended.contains(MoneyProfileChatTool.TOOL_NAME), "the write tool's name does not ride the prompt")
    assertTrue(appended.contains("U.S. Department of Education College Scorecard"), "the paragraph must require attribution")
  }

  /**
   * The 0048 seed's structural contract (RFC 136, the same convention): v4 is
   * the v3 body byte-identical as a prefix, joined by a single space to
   * exactly one appended paragraph — the college-list keeper instruction. The
   * paragraph's markers are asserted, not its full copy: the seed migration is
   * the single home of the approved wording.
   */
  @Test
  fun `coach v4 is the v3 body verbatim plus one appended college-list paragraph`() {
    val v3 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v3").getOrThrow().body
    val v4 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v4").getOrThrow().body
    assertTrue(v4.startsWith(v3), "the v3 prefix must be byte-identical, so the list paragraph is the only change")
    val appended = v4.removePrefix(v3)
    assertTrue(appended.startsWith(" The student's college list is theirs"), "single-space paragraph join")
    // v3 named the action tool (college_cost_profile); v4 follows: the list
    // tool is this slice's action tool, so its name rides the prompt.
    assertTrue(appended.contains(CollegeListChatTool.TOOL_NAME), "the paragraph must name the list tool")
    assertTrue(appended.contains("offer to add it to their list"), "the paragraph must carry the proactive offer")
    assertTrue(
      appended.contains("only when the student asks or agrees"),
      "the paragraph must forbid writing without the student's say-so",
    )
    assertTrue(appended.contains("let it go without comment"), "a declined offer is never pushed")
    assertTrue(appended.contains("offer to update the school's status"), "milestones prompt a status offer")
  }
}
