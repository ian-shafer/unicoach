package ed.unicoach.coaching

import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.fitlens.FitLensConfig
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.IncomeBand
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
    /** The first words of v4's cost paragraph — the boundary v5 replaces from. */
    private const val COST_PARAGRAPH_OPENER = " When the student has schools on their college list"

    /** The first words of v4's college-list paragraph — the boundary v5 preserves and v6 inserts before. */
    private const val LIST_PARAGRAPH_OPENER = " The student's college list is theirs"

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
    assertTrue(
      appended.startsWith(" When the student has schools on their college list"),
      "the money paragraph must open with the single space that joins it to the paragraph before it",
    )
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
    assertTrue(
      appended.startsWith(" The student's college list is theirs"),
      "the money paragraph must open with the single space that joins it to the paragraph before it",
    )
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

  /**
   * The 0049 seed's structural contract (RFC 141). Unlike 0047/0048, v5 does
   * not append: it REPLACES v4's cost paragraph, which sits in the middle of
   * the body followed by the college-list paragraph. So the contract is a
   * byte-identical prefix AND a byte-identical suffix, with the new money
   * paragraph in between; both boundaries are located from the v4 body at
   * runtime, so the seed migration stays the single home of the approved copy.
   *
   * The middle is asserted by markers only, and absence of the banned terms is
   * deliberately NOT asserted: the glossary is stated contrastively ("housing
   * and food, never room and board"), so the prompt must name a term in order
   * to forbid it.
   */
  @Test
  fun `coach v5 is v4 with the cost paragraph replaced`() {
    // The prefix/suffix assertions themselves live in moneyParagraph(), which
    // refuses to return a vacuous extraction; this test owns the markers that
    // say the replacement is the RIGHT paragraph, not merely a well-formed one.
    val moneyParagraph = moneyParagraph()

    assertTrue(
      moneyParagraph.startsWith(COST_PARAGRAPH_OPENER),
      "the money paragraph must open with the single space that joins it to the paragraph before it",
    )
    assertTrue(moneyParagraph.contains(CollegeCostChatTool.TOOL_NAME), "the paragraph must name the cost tool")
    assertTrue(
      moneyParagraph.contains("precision_offer"),
      "the paragraph must key the in-answer invitation off the result",
    )
    assertTrue(
      moneyParagraph.contains("U.S. Department of Education College Scorecard"),
      "the paragraph must require attribution",
    )
    assertTrue(moneyParagraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(moneyParagraph.contains("housing and food"), "the glossary term that retires room and board")
  }

  /**
   * The glossary half of the 0049 seed's contract (RFC 141), split from the
   * structural test so a drifted glossary pair is never reported under the
   * structural test's name — `assertTrue` short-circuits, so one test would
   * let a prefix regression mask every glossary assertion behind it.
   *
   * The pairs are asserted CONTRASTIVELY ("housing and food, never room and
   * board"): the prompt must name a banned term in order to forbid it, so
   * absence of the banned tokens is deliberately not assertable.
   */
  @Test
  fun `coach v5 states each contrastive glossary pair`() {
    val moneyParagraph = moneyParagraph()

    assertTrue(moneyParagraph.contains("never tuition on its own"), "contrastive pair: tuition and fees")
    assertTrue(moneyParagraph.contains("never room and board"), "contrastive pair: housing and food")
    assertTrue(moneyParagraph.contains("never the sticker price"), "contrastive pair: the published price")
    assertTrue(moneyParagraph.contains("never an award"), "contrastive pair: a financial aid offer")
    assertTrue(
      moneyParagraph.contains("never subtract loans or work-study from a price"),
      "loans and work-study change who pays and when; they never make a school cheaper",
    )
  }

  /**
   * The 0050 seed's structural contract (RFC 142). v6 neither appends nor
   * replaces: it INSERTS one sentence at a known interior boundary — the end of
   * v5's money paragraph, immediately before the college-list paragraph. So the
   * contract is a byte-identical v5 prefix AND a byte-identical v5 college-list
   * suffix, with the appended sentence between them.
   *
   * The sentence is asserted by markers only, and the absence of the jargon it
   * forbids is deliberately NOT asserted: like v5's glossary, the rule names
   * the terms to avoid ("no quintiles") in order to forbid them.
   */
  @Test
  fun `coach v6 is v5 plus the source-jargon sentence`() {
    // The prefix/suffix assertions themselves live in sourceJargonSentence(),
    // which refuses to return a vacuous extraction; this test owns the markers
    // that say the inserted sentence is the RIGHT one.
    val sentence = sourceJargonSentence()

    assertTrue(
      sentence.startsWith(" Never name a data source's internal buckets"),
      "the sentence must open with the single space that joins it to the sentence before it",
    )
    assertTrue(sentence.contains("no quintiles"), "the rule must name quintiles as one instance of the banned class")
    assertTrue(
      sentence.contains("the band's dollar range"),
      "the rule must say what to say INSTEAD, or it is a ban list with a vacuum behind it",
    )
    // The example phrase in the prompt must be a phrase the wire actually
    // sends: a later bracket rewrite would otherwise leave the coach taught a
    // dollar range no tool result carries.
    assertTrue(
      sentence.contains(IncomeBand.OVER_110K.bracket),
      "the example range must be the live IncomeBand.bracket, not a hand-typed copy of it",
    )
  }

  /**
   * The v6 source-jargon sentence: the sentence v6 inserts at the end of v5's
   * money paragraph, extracted through the guarded [revisedMiddle].
   */
  private fun sourceJargonSentence(): String = revisedMiddle(base = "v5", revised = "v6", prefixOpener = LIST_PARAGRAPH_OPENER)

  /**
   * The v5 money paragraph: the paragraph v5 puts where v4's cost paragraph
   * was, extracted through the guarded [revisedMiddle].
   */
  private fun moneyParagraph(): String = revisedMiddle(base = "v4", revised = "v5", prefixOpener = COST_PARAGRAPH_OPENER)

  /**
   * The one guarded extractor behind [moneyParagraph] and
   * [sourceJargonSentence]: the middle of the [revised] coach prompt, with the
   * [base] version's byte-identical prefix (everything up to [prefixOpener])
   * and its byte-identical college-list suffix removed. Both boundaries are
   * located from the [base] body at runtime, so the seed migration stays the
   * single home of the approved copy.
   *
   * Shared rather than cloned per version because every guard here is about the
   * MECHANISM, not the copy: each extraction primitive degrades SILENTLY —
   * `substringBefore` returns the whole receiver when the delimiter is missing,
   * and `removePrefix`/`removeSuffix` are no-ops when the affix does not match —
   * and `prefix + suffix` is the whole of [base], so a [revised] that changed
   * nothing would hand back the empty string and every `contains` downstream
   * would still be reached and still pass. A duplicated copy of this drifts
   * (one grew the emptiness check, the other did not); one copy cannot.
   */
  private fun revisedMiddle(
    base: String,
    revised: String,
    prefixOpener: String,
  ): String {
    val baseBody = SystemPromptsDao.findByNameAndVersion(session, "coach", base).getOrThrow().body
    val revisedBody = SystemPromptsDao.findByNameAndVersion(session, "coach", revised).getOrThrow().body
    val prefix = baseBody.substringBefore(prefixOpener)
    val suffix = LIST_PARAGRAPH_OPENER + baseBody.substringAfter(LIST_PARAGRAPH_OPENER)
    assertTrue(prefix != baseBody, "$base must contain the opener [$prefixOpener], or the extraction is vacuous")
    assertTrue(revisedBody.startsWith(prefix), "everything before the opener must be $base byte-for-byte")
    assertTrue(revisedBody.endsWith(suffix), "$base's college-list paragraph must survive as a byte-identical suffix")
    val middle = revisedBody.removePrefix(prefix).removeSuffix(suffix)
    assertTrue(middle.isNotEmpty(), "$revised must actually change the middle; an empty extraction means it equals $base")
    return middle
  }
}
