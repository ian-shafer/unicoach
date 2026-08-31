package ed.unicoach.coaching

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.admissions.CollegeAdmissionsChatTool
import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.PrecisionOffer
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
import kotlin.test.assertEquals
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

    /**
     * The admissions tool the v8 paragraph names (RFC 148), read from the tool
     * itself on this file's own v3/v4/v5/v7 precedent. The pairing under test is
     * SEEDED COPY versus SHIPPING TOOL: a literal here would keep passing after
     * the tool was renamed, leaving the seeded prompt telling the model to call
     * a tool that no longer exists -- prompt-vs-code drift, which is the one
     * failure this class exists to make loud.
     */
    private val ADMISSIONS_TOOL_NAME = CollegeAdmissionsChatTool.TOOL_NAME

    /** The first words of the codebook sentence v3 deletes (RFC 147). */
    private const val CODEBOOK_SENTENCE_OPENER = "The coded fields use these codebooks:"

    /** The first words of the sentence that FOLLOWS it — the span's right edge. */
    private const val CODEBOOK_SENTENCE_SUCCESSOR = "Do not set a result limit;"

    /**
     * The hand-transcribed-codebook pattern, from its one home in
     * [BareSourceCodeGuard] (RFC 147): three files need it, and three hand-typed
     * copies is how one of them arrived with doubled backslashes and could never
     * fire. Every use here is preceded by the shared positive control.
     */
    private val CODE_EQUALS_WORD = BareSourceCodeGuard.CODE_EQUALS_WORD

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
   * The 0053 seed's structural contract (RFC 145). Like v5 over v4, v7
   * REPLACES the money paragraph — v6's precision_offer rule is income-only and
   * would misfire on a residency offer — so the contract is the same one: a
   * byte-identical v6 prefix, a byte-identical v6 college-list suffix, and the
   * rewritten paragraph between them, through the same guarded extractor.
   *
   * The markers say the replacement is the RIGHT paragraph: it raises
   * residency, asks the single state question, says what that unlocks, and
   * raises it BEFORE income.
   */
  @Test
  fun `coach v7 is v6 with the money paragraph replaced`() {
    val moneyParagraph = v7MoneyParagraph()

    assertTrue(
      moneyParagraph.startsWith(COST_PARAGRAPH_OPENER),
      "the money paragraph must open with the single space that joins it to the paragraph before it",
    )
    assertTrue(moneyParagraph.contains(CollegeCostChatTool.TOOL_NAME), "the paragraph must still name the cost tool")
    assertTrue(
      moneyParagraph.contains(CollegeCostChatTool.PRECISION_OFFER_KEY),
      "the paragraph must key the in-answer invitations off the result",
    )
    assertTrue(
      moneyParagraph.contains(PrecisionOffer.RESIDENCY.field),
      "the paragraph must name the residency offer by the field the result carries",
    )
    assertTrue(
      moneyParagraph.contains("ask what state the family lives in"),
      "v7's whole point is that the coach finally asks where they live",
    )
    assertTrue(
      moneyParagraph.contains("in-state") && moneyParagraph.contains("out-of-state"),
      "the paragraph must say what the answer unlocks, not merely ask for it",
    )
    // Positionally on the SENTENCE that states the order, not on the bare
    // field names: `income_band` is a prefix of income_band_label and
    // income_band_status, so a bare indexOf would silently measure whichever
    // longer token came first (the sibling assertion in CollegeCostChatToolTest
    // refuses indexOf for the same hazard).
    assertTrue(
      moneyParagraph.contains("A ${PrecisionOffer.RESIDENCY.field} offer comes first"),
      "the paragraph must state the order it wants: residency before household income",
    )
    assertTrue(
      moneyParagraph.contains("ask what state the family lives in before you raise household income"),
      "residency must be raised before household income - the ordering is the product decision",
    )
    assertTrue(
      moneyParagraph.contains("U.S. Department of Education College Scorecard"),
      "the attribution rule must survive the rewrite",
    )
    assertTrue(moneyParagraph.contains("never raise that field again yourself"), "a decline of either field stays permanent")
  }

  /**
   * RFC 142's source-jargon rule must survive RFC 145's rewrite of the
   * paragraph it lives inside. The sentence is extracted from v6 at runtime and
   * asserted to be present verbatim in v7's money paragraph — never retyped
   * here, or the test would only prove that two hand-typed copies agree.
   */
  @Test
  fun `coach v7 preserves the v6 source-jargon sentence verbatim`() {
    val sentence = sourceJargonSentence()

    assertTrue(
      v7MoneyParagraph().contains(sentence),
      "v7's money paragraph must carry v6's source-jargon sentence byte-for-byte: [$sentence]",
    )
  }

  /**
   * The 0058 seed's structural contract (RFC 148). v8 is ADDITIVE like v3 over
   * v2 and v4 over v3: the whole v7 body byte-identical as a prefix, joined by
   * a single space to exactly one appended paragraph — the admissions
   * instruction. The paragraph's markers are asserted, not its full copy: the
   * seed migration is the single home of the approved wording.
   */
  @Test
  fun `coach v8 is v7 plus one appended admissions paragraph`() {
    val appended = admissionsParagraph()

    assertTrue(
      appended.startsWith(" A school also publishes what it looks for"),
      "the admissions paragraph must open with the single space that joins it to the paragraph before it",
    )
    assertTrue(appended.contains(ADMISSIONS_TOOL_NAME), "the paragraph must name the admissions tool")
    assertTrue(appended.contains("Common Data Set"), "the paragraph must say whose data the tool reports")
    assertTrue(
      appended.contains("what an admission office weighs") && appended.contains("application deadlines"),
      "the paragraph must say when to reach for the tool, not merely that it exists",
    )
    assertTrue(
      appended.contains("data_availability"),
      "the first silence: a named field is a school that does not report it",
    )
    assertTrue(
      appended.contains("say so plainly rather than estimating it"),
      "an unreported field is stated, never estimated",
    )
    assertTrue(
      appended.contains("not missing data"),
      "the second silence: a round flagged not offered is a reported fact",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(appended.contains("a financial aid offer"), "the glossary term survives: an offer, never an award")
    assertFalse(appended.contains("award"), "'award' is retired copy (RFC 141) and this paragraph never states it contrastively")
    assertTrue(
      appended.contains("never subtract merit money from a published price"),
      "a share and an average are not an offer to this student; they never net out of a price",
    )
  }

  /**
   * RFC 148's D4, the binding honesty rule: the merit share's denominator is
   * ALL full-time freshmen. The Common Data Set has no count of students
   * without financial need, so a prompt that said or implied that denominator
   * would teach the coach a statistic no source reports.
   *
   * Absence is assertable here — unlike v5's contrastive glossary, this rule is
   * stated positively, so the banned phrasing appears nowhere in the paragraph.
   * "non-need" is the approved term and does not contain the banned substring,
   * so no exception is needed for it.
   */
  @Test
  fun `coach v8 carries the honest denominator`() {
    val appended = admissionsParagraph()

    assertTrue(
      appended.contains("of all full-time freshmen"),
      "the share's denominator must be stated in full: all full-time freshmen",
    )
    assertTrue(appended.contains("non-need (merit) aid"), "the approved term for the money itself")
    assertFalse(appended.contains("without need"), "the banned denominator: no source reports a count of students without need")
    assertFalse(appended.contains("without financial need"), "the same banned denominator, spelled out")
    assertFalse(appended.contains("freshmen without"), "no phrasing may narrow the denominator away from all freshmen")
  }

  /**
   * RFC 142's source-jargon sentence must survive RFC 148's append. It does so
   * by construction — v8 keeps the whole v7 body as a prefix — but the sentence
   * is the one piece of copy two prior versions have already had to preserve
   * across a rewrite, so it is asserted rather than assumed. It is extracted
   * from v6 at runtime, never retyped here.
   */
  @Test
  fun `coach v8 preserves the v7 source-jargon sentence verbatim`() {
    val sentence = sourceJargonSentence()
    val v8 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v8").getOrThrow().body

    assertTrue(
      v8.contains(sentence),
      "v8 must carry v6's source-jargon sentence byte-for-byte: [$sentence]",
    )
  }

  /**
   * The 0063 seed's structural contract (RFC 149). v9 is ADDITIVE like v3 over
   * v2, v4 over v3 and v8 over v7: the whole v8 body byte-identical as a prefix,
   * joined by a single space to exactly one appended paragraph — the
   * living-arrangement instruction. The paragraph's markers are asserted, not its
   * full copy: the seed migration is the single home of the approved wording.
   */
  @Test
  fun `coach v9 is v8 plus one appended living-arrangement paragraph`() {
    val appended = livingArrangementParagraph()

    assertTrue(
      appended.startsWith(" When a school reports its costs by living arrangement"),
      "the paragraph must open with the single space that joins it to the paragraph before it",
    )
    assertTrue(
      appended.contains("lead with that split rather than with one total"),
      "v9's whole point: the split comes first, not the blended total",
    )
    assertTrue(
      appended.contains("Always name which arrangement you are quoting"),
      "the same school has three prices; an unnamed one is a number the family cannot use",
    )
    assertTrue(
      appended.contains("living on campus") &&
        appended.contains("renting off campus") &&
        appended.contains("living at home"),
      "the three arrangements must be named in words a student says, not in wire keys",
    )
    assertTrue(
      appended.contains("say they are estimates"),
      "the living-cost lines are the school's own estimates and must be marked as such",
    )
    assertTrue(
      appended.contains("living at home instead of on campus would cost"),
      "the at-home comparison is the sentence this slice exists to make sayable",
    )
    assertTrue(
      appended.contains("never add up the parts that are there and call the result the total"),
      "a missing total is a missing part, never a sum of whatever happens to be present",
    )
    assertTrue(
      appended.contains("no residence halls"),
      "the no-dorms case is an answer the coach states, not an unreported figure",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(appended.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(appended.contains("housing and food"), "the glossary term that retires room and board")
    assertFalse(appended.contains("room and board"), "the retired term is never stated here, not even contrastively")
    assertFalse(appended.contains("sticker"), "the published price, never the sticker price (RFC 141)")
  }

  /**
   * RFC 149's D-F rules, as the prompt half of the contract whose code half is
   * `ForbiddenCostArithmeticTest`. A tool can refuse to compute a forbidden
   * number; only the prompt can stop the coach computing it out loud.
   *
   * Absence is assertable for the arithmetic itself — every "subtract" in the
   * whole v9 body is preceded by "never", which is a property of the finished
   * copy rather than of one paragraph, so a later version that relaxed it
   * anywhere fails here.
   */
  @Test
  fun `the served coach prompt forbids the net-price arithmetic and the cross-vintage sum`() {
    // The prompt the runtime actually SERVES, read from the pin rather than
    // typed here: a literal "v9" would keep passing after a v10 was pinned,
    // leaving the body the coach is really given unverified -- which is the one
    // failure this class exists to make loud.
    val served =
      SystemPromptsDao
        .findByNameAndVersion(session, coaching.systemPromptName, coaching.systemPromptVersion)
        .getOrThrow()
        .body

    val subtractions = Regex("""(.{0,6})subtract""").findAll(served).map { it.groupValues[1] }.toList()
    assertTrue(subtractions.isNotEmpty(), "the rule must actually be stated, or this assertion is vacuous")
    assertEquals(
      emptyList(),
      subtractions.filterNot { it == "never " },
      "every mention of subtracting a price in the coach prompt must forbid it: [$subtractions]",
    )

    val appended = livingArrangementParagraph()
    assertTrue(
      appended.contains("never subtract a net price from tuition"),
      "D-F rule 2: aid applies to the whole price, never to one part of it",
    )
    assertTrue(
      appended.contains("never present it as one arrangement's total and never compare the two"),
      "D-F rule 1: the blended cost of attendance is not the component sum",
    )
    assertTrue(
      appended.contains("never add figures from two different years together"),
      "D-F rule 3: only same-vintage figures are ever summed",
    )
  }

  /**
   * RFC 142's source-jargon sentence must survive RFC 149's append. It does so by
   * construction — v9 keeps the whole v8 body as a prefix — but it is the one
   * piece of copy three prior versions have already had to preserve, so it is
   * asserted rather than assumed. It is extracted from v6 at runtime, never
   * retyped here.
   */
  @Test
  fun `coach v9 preserves the v7 and v8 source-jargon sentence verbatim`() {
    val sentence = sourceJargonSentence()
    val v9 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v9").getOrThrow().body

    assertTrue(
      v9.contains(sentence),
      "v9 must carry v6's source-jargon sentence byte-for-byte: [$sentence]",
    )
    // And RFC 141's contrastive glossary pairs, which live in the money
    // paragraph v7 rewrote and v9 leaves untouched -- extracted at runtime,
    // never retyped. v5's own paragraph is NOT the one to look for: v7 replaced
    // it, so asserting that would assert the wrong copy survived.
    assertTrue(v9.contains(v7MoneyParagraph()), "v7's money paragraph must survive the append byte-for-byte")
  }

  /**
   * The 0066 seed's structural contract (RFC 151). v11 is ADDITIVE like v3 over
   * v2, v4 over v3, v8 over v7, v9 over v8 and v10 over v9: the whole v10 body
   * byte-identical as a prefix, joined by a single space to exactly one appended
   * paragraph — the comparison instruction. The paragraph's markers are
   * asserted, not its full copy: the seed migration is the single home of the
   * approved wording.
   */
  @Test
  fun `coach v11 is v10 plus one appended comparison paragraph`() {
    val appended = comparisonParagraph()

    assertTrue(
      appended.startsWith(" When two or more schools appear together"),
      "the paragraph must open with the single space that joins it to the paragraph before it",
    )
    assertTrue(
      appended.contains(CollegeCostChatTool.COMPARISON_BASIS_KEY),
      "the paragraph must key the contract off the object the result carries",
    )
    assertTrue(
      appended.contains("say those five lines first") && appended.contains("above the table"),
      "v11's whole point: the assumptions are said as ordinary copy above the table, never as a note beneath it",
    )
    assertTrue(
      appended.contains("above the estimated living costs"),
      "the stable block is rendered above the estimate block, and the two are named",
    )
    assertTrue(
      appended.contains("three columns"),
      "RFC 124's cap, restated in the concrete comparison case",
    )
    assertTrue(
      appended.contains("leave that cell blank and label it as not reported"),
      "a missing part is a labelled blank; the payload's data_availability is never rendered as a number",
    )
    assertTrue(
      appended.contains("never write a zero") && appended.contains("never carry a neighbour's number across"),
      "a blank is never a zero and never a neighbour's figure",
    )
    assertTrue(
      appended.contains("no residence halls has none"),
      "the no-dorms case is an answer the coach states, not an unreported figure",
    )
    assertTrue(
      appended.contains("Keep one residency and one way of living in a column"),
      "two bases never mix into one column - the contract this slice exists for",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(appended.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(appended.contains("housing and food"), "the glossary term that retires room and board")
    assertFalse(appended.contains("room and board"), "the retired term is never stated here, not even contrastively")
    assertFalse(appended.contains("sticker"), "the published price, never the sticker price (RFC 141)")
    assertFalse(appended.contains("award"), "a financial aid offer, never an award (RFC 141)")
    // The served-body guard below sweeps the WHOLE prompt; this says the rule
    // holds inside the span v11 actually adds, so a relaxation here is reported
    // as v11's own rather than as the catalog's.
    assertEquals(
      emptyList(),
      Regex("""(.{0,6})subtract""")
        .findAll(appended)
        .map { it.groupValues[1] }
        .toList()
        .filterNot { it == "never " },
      "every mention of subtracting in the new paragraph must forbid it",
    )
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertFalse(CODE_EQUALS_WORD.containsMatchIn(appended), "the new paragraph must transcribe no source codebook")
  }

  /**
   * RFC 142's source-jargon sentence and RFC 141's glossary pairs must survive
   * RFC 151's append. They do so by construction — v11 keeps the whole v10 body
   * as a prefix — but they are the copy five prior versions have already had to
   * preserve, so they are asserted rather than assumed. Both are extracted at
   * runtime, never retyped here.
   */
  @Test
  fun `coach v11 preserves the source-jargon sentence and the money paragraph verbatim`() {
    val sentence = sourceJargonSentence()
    val v11 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v11").getOrThrow().body

    assertTrue(
      v11.contains(sentence),
      "v11 must carry v6's source-jargon sentence byte-for-byte: [$sentence]",
    )
    assertTrue(v11.contains(v7MoneyParagraph()), "v7's money paragraph must survive the append byte-for-byte")
  }

  /**
   * The v11 comparison paragraph: everything v11 appends to the v10 body.
   * Guarded exactly as [livingArrangementParagraph] is — `removePrefix` is a
   * silent no-op when the affix does not match, so the prefix is asserted before
   * it is removed, and an empty remainder would let every `contains` pass
   * vacuously.
   */
  private fun comparisonParagraph(): String {
    val v10 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v10").getOrThrow().body
    val v11 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v11").getOrThrow().body
    assertTrue(v11.startsWith(v10), "the v10 prefix must be byte-identical, so the new paragraph is the only change")
    val appended = v11.removePrefix(v10)
    assertTrue(appended.isNotEmpty(), "v11 must actually append something; an empty remainder means it equals v10")
    return appended
  }

  /**
   * The v9 living-arrangement paragraph: everything v9 appends to the v8 body.
   * Guarded exactly as [admissionsParagraph] is -- `removePrefix` is a silent
   * no-op when the affix does not match, so the prefix is asserted before it is
   * removed, and an empty remainder would let every `contains` pass vacuously.
   */
  private fun livingArrangementParagraph(): String {
    val v8 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v8").getOrThrow().body
    val v9 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v9").getOrThrow().body
    assertTrue(v9.startsWith(v8), "the v8 prefix must be byte-identical, so the new paragraph is the only change")
    val appended = v9.removePrefix(v8)
    assertTrue(appended.isNotEmpty(), "v9 must actually append something; an empty remainder means it equals v8")
    return appended
  }

  /**
   * The 0065 seed's structural contract (RFC 150 D58): v10 is the v9 body
   * byte-identical plus exactly one appended paragraph, the additive shape
   * every coach seed since 0047 has used. Extracted at runtime, so the
   * migration stays the one home of the copy.
   */
  @Test
  fun `coach v10 is the v9 body verbatim plus one appended search paragraph`() {
    val paragraph = searchParagraph()

    // Search by SUBJECT, never by a code the model guessed at.
    assertTrue(paragraph.contains("subject word"), "the paragraph must teach the subject word: [$paragraph]")
    // The honest population count, and the unjudgeable ones said in words.
    assertTrue(paragraph.contains("total number of matches"), "it must cite the tool's total: [$paragraph]")
    assertTrue(paragraph.contains("could not be judged"), "it must report the excluded unknowns: [$paragraph]")
    // Never a source's own code, in writing or aloud.
    assertTrue(paragraph.contains("code"), "it must forbid stating a source code: [$paragraph]")
    // Brief 0003's money vocabulary applies verbatim to any price the tool returns.
    assertTrue(paragraph.contains("tuition and fees"), "the money words must apply to tool prices: [$paragraph]")
    assertTrue(paragraph.contains("housing and food"), "[$paragraph]")
    assertTrue(paragraph.contains("published price"), "[$paragraph]")
    assertTrue(paragraph.contains("financial aid offer"), "[$paragraph]")
    assertTrue(paragraph.contains("subtract loans"), "loans are never taken off a price: [$paragraph]")
  }

  @Test
  fun `coach v10 preserves the source-jargon sentence and the money paragraph verbatim`() {
    val v10 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v10").getOrThrow().body
    assertTrue(v10.contains(sourceJargonSentence()), "v10 must carry v6's source-jargon sentence byte-for-byte")
    assertTrue(v10.contains(v7MoneyParagraph()), "v7's money paragraph must survive the append byte-for-byte")
  }

  /** See [livingArrangementParagraph]: everything v10 appends to the v9 body. */
  private fun searchParagraph(): String {
    val v9 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v9").getOrThrow().body
    val v10 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v10").getOrThrow().body
    assertTrue(v10.startsWith(v9), "the v9 prefix must be byte-identical, so the new paragraph is the only change")
    val appended = v10.removePrefix(v9)
    assertTrue(appended.isNotEmpty(), "v10 must actually append something; an empty remainder means it equals v9")
    return appended
  }

  /**
   * The 0061 seed's structural contract (RFC 147). Unlike every coach seed
   * above, v3 of the fit-lens query prompt ADDS NOTHING: it is v2 with exactly
   * one span deleted — the hand-written codebook sentence — so the contract is
   * a byte-identical prefix, a byte-identical suffix, and a removed middle that
   * is the sentence and nothing else.
   *
   * The deleted text is located from the v2 body at runtime by its opening
   * words, so the migration stays the single home of both copies, and the
   * removal is asserted to be non-empty: `removeRange` on a missing span would
   * silently make this test compare v2 to itself.
   */
  @Test
  fun `fit lens query v3 is v2 with the hand-written codebook deleted`() {
    val v2 = SystemPromptsDao.findByNameAndVersion(session, "fit_lens_query", "v2").getOrThrow().body
    val v3 = SystemPromptsDao.findByNameAndVersion(session, "fit_lens_query", "v3").getOrThrow().body

    val start = v2.indexOf(CODEBOOK_SENTENCE_OPENER)
    assertTrue(start >= 0, "v2 must contain the codebook sentence, or this test is vacuous")
    val end = v2.indexOf(CODEBOOK_SENTENCE_SUCCESSOR)
    assertTrue(end > start, "v2 must contain the sentence that follows the codebook, or the span is unbounded")

    assertEquals(v2.removeRange(start, end), v3, "v3 must be v2 with the codebook sentence removed and nothing else")

    // ...and what came out was the codebook itself, not some other sentence.
    val deleted = v2.substring(start, end)
    assertTrue(deleted.contains("1=New England"), "the deleted span must be the region codebook: [$deleted]")
    assertTrue(deleted.contains("11/12/13 city"), "the deleted span must be the locale codebook: [$deleted]")
    assertTrue(deleted.contains("1=public"), "the deleted span must be the control codebook: [$deleted]")

    // The property the whole slice exists for: every "<digit>=<word>" pair is
    // gone from the prompt. Positive control first -- a pattern that cannot
    // fire would make this and the sweep below pass forever.
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertTrue(CODE_EQUALS_WORD.containsMatchIn(deleted), "the guard pattern must fire on the span it describes")
    assertFalse(CODE_EQUALS_WORD.containsMatchIn(v3), "v3 must name no code-to-word pair: [$v3]")
    assertTrue(v3.contains("record_college_query"), "v3 must still name the tool it forces")
  }

  /**
   * The other three seeded prompts are asserted NOT to have been re-versioned
   * for this slice, because the reason they were not is a finding rather than
   * an omission: the codebook prose existed in `fit_lens_query` alone. A coach
   * or reason prompt that ever grows one should fail here and be dealt with.
   */
  @Test
  fun `no seeded prompt carries a hand-written codebook except the retired fit lens versions`() {
    val retired = setOf("fit_lens_query" to "v1", "fit_lens_query" to "v2")
    val offenders = mutableListOf<String>()
    var scanned = 0
    var retiredSeen = 0
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT name, version, body FROM system_prompts").use { rs ->
        while (rs.next()) {
          val name = rs.getString("name")
          val version = rs.getString("version")
          if ((name to version) in retired) {
            // The two rows the allow-list excuses must BE there and must still
            // carry a codebook; an allow-list that excuses nothing real is an
            // allow-list nobody will notice has gone stale.
            assertTrue(
              CODE_EQUALS_WORD.containsMatchIn(rs.getString("body")),
              "the retired row $name/$version must still carry the codebook it is excused for",
            )
            retiredSeen++
            continue
          }
          scanned++
          if (CODE_EQUALS_WORD.containsMatchIn(rs.getString("body"))) offenders += "$name/$version"
        }
      }
    }
    // A sweep over zero rows is a green test that proved nothing -- the catalog
    // is migration-seeded and insert-only, so it is never legitimately empty.
    assertTrue(scanned > 0, "the sweep must actually scan seeded prompts")
    assertEquals(retired.size, retiredSeen, "both retired fit-lens rows must be present in the catalog")
    assertEquals(emptyList(), offenders, "a seeded prompt is transcribing a codebook again")
  }

  /**
   * The v8 admissions paragraph: everything v8 appends to the v7 body. Guarded
   * the way [revisedMiddle] is — `removePrefix` is a silent no-op when the
   * affix does not match, so the prefix is asserted before it is removed, and
   * an empty remainder would let every `contains` below pass vacuously.
   */
  private fun admissionsParagraph(): String {
    val v7 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v7").getOrThrow().body
    val v8 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v8").getOrThrow().body
    assertTrue(v8.startsWith(v7), "the v7 prefix must be byte-identical, so the admissions paragraph is the only change")
    val appended = v8.removePrefix(v7)
    assertTrue(appended.isNotEmpty(), "v8 must actually append something; an empty remainder means it equals v7")
    return appended
  }

  /**
   * The v7 money paragraph: the paragraph v7 puts where v6's money paragraph
   * was, extracted through the guarded [revisedMiddle].
   */
  private fun v7MoneyParagraph(): String = revisedMiddle(base = "v6", revised = "v7", prefixOpener = COST_PARAGRAPH_OPENER)

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
