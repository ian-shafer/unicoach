package ed.unicoach.coaching

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.admissions.CollegeAdmissionsChatTool
import ed.unicoach.coaching.aid.FederalAidPolicyChatTool
import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.costs.AT_HOME_ASSUMPTION_STATEMENT
import ed.unicoach.coaching.costs.AidPolicyWire
import ed.unicoach.coaching.costs.BorrowingWire
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.PrecisionOffer
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.fitlens.FitLensConfig
import ed.unicoach.coaching.report.RevokeCostReportShareChatTool
import ed.unicoach.coaching.report.ShareCostReportChatTool
import ed.unicoach.coaching.report.StopCostReportOffersChatTool
import ed.unicoach.coaching.synthesis.SynthesisConfig
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.college.FindCollegeTool
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.RESIDENCY_TIERS_KEY
import ed.unicoach.db.models.SystemPrompt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    /**
     * The ONLY prefix that may precede the word "subtract" in coach copy: the
     * rule has to be stated as a prohibition, never as a licence.
     */
    private const val PERMITTED_SUBTRACT_PREFIX = "never "

    /**
     * Every mention of subtracting, with the words in front of it.
     *
     * The lookback is the permitted prefix's own LENGTH, derived rather than
     * retyped: the scan only works because the window and the string it is
     * compared against are the same size, and that coupling was written out by
     * hand at seven sites, in none of which it was said aloud.
     */
    private val SUBTRACT_MENTIONS = Regex("""(.{0,${PERMITTED_SUBTRACT_PREFIX.length}})subtract""")

    /** Every mention of subtracting in [copy] that does NOT forbid it -- empty, or the copy is wrong. */
    private fun listSubtractionsNotForbidden(copy: String): List<String> =
      SUBTRACT_MENTIONS
        .findAll(copy)
        .map { it.groupValues[1] }
        .filterNot { it == PERMITTED_SUBTRACT_PREFIX }
        .toList()

    /** A `vNN` catalog label, and the number it carries. No leading zero: `v08` is not a label anything writes. */
    private val VERSION_LABEL = Regex("^v([1-9][0-9]*)$")

    /**
     * The byte that separates two authored lines in the SERVED body, and
     * therefore the one byte that makes an opener match the START of a sentence
     * rather than a phrase inside one.
     *
     * It is a newline from v27 on (RFC 185): the body is
     * `prompts/coach-system-prompt.md` verbatim, so the file's own line breaks
     * are what the model is served. Under RFC 181 it was a single space, because
     * the generator joined the lines with one — see [LEGACY_JOIN_SPACE], which is
     * still what the pre-v27 rows in the catalog carry.
     *
     * Named and applied in [openerOf], not typed into each literal below: a
     * leading separator inside a string literal is invisible and load-bearing at
     * once, so an author adding a paragraph could not see the rule, and the
     * drift guard had to trim it back off to compare the opener with the file it
     * came from. The literals are the copy; the separator is this constant.
     */
    private const val BODY_LINE_BREAK = "\n"

    /**
     * The single space every coach row UP TO v26 has between two authored
     * sentences, kept for the rollback assertions that read those immutable rows.
     *
     * `system_prompts` is insert-only, so v14 through v26 are still exactly the
     * one-long-line bodies RFC 181's join rule produced, and an opener looked for
     * in them has to carry the separator THEY were written with — which is why
     * [CoachBody] pairs a body with its own separator rather than letting a
     * caller pick one. Sharing one
     * constant with [BODY_LINE_BREAK] would make every rollback assertion look
     * for a newline in a row that has none — a silent pass turning into a silent
     * failure, depending on which way the shared value went.
     */
    private const val LEGACY_JOIN_SPACE = " "

    /**
     * The last coach version whose body is its authored lines JOINED WITH ONE
     * SPACE (RFC 181). v27 on carry `prompts/coach-system-prompt.md` verbatim,
     * so their authored lines are separated by [BODY_LINE_BREAK] (RFC 185).
     *
     * `system_prompts` is insert-only, so this boundary is a fact about rows
     * that already exist, and it never moves again.
     */
    private const val LAST_SPACE_JOINED_VERSION = 26

    /**
     * A catalog row's body together with the separator THAT ROW was written
     * with, so an opener is always looked for in the dialect of the body it is
     * looked for in.
     *
     * The pairing is DERIVED from the row's version by [bodyOf] and never
     * chosen by a caller. Two interchangeable `String` producers made the
     * dialect a naming convention: picking the pre-v27 one for a v27 body, or
     * the other way round, compiles and — on an `assertFalse`, which is most of
     * the rollback assertions here — passes vacuously forever. There is no
     * spelling of that mistake left to write.
     */
    private class CoachBody(
      private val text: String,
      private val lineSeparator: String,
    ) {
      /** Whether this body carries [paragraph], matched at the START of a line rather than inside a sentence. */
      fun contains(paragraph: CoachParagraph): Boolean = text.contains(lineSeparator + paragraph.openingWords)
    }

    /**
     * Every paragraph of the served coach prompt, each named by its opening
     * words.
     *
     * The body is one line per sentence with no paragraph marker in it, so a
     * paragraph's END is the next paragraph's beginning, and this set is what
     * says where those are. (A line break is not a paragraph marker: every
     * sentence sits on its own line, so a break separates two sentences of one
     * paragraph as often as it separates two paragraphs.) It exists to keep a paragraph-scoped assertion
     * SCOPED: "this paragraph never says room and board" is a true and useful
     * rule that the whole body fails, because the glossary paragraph says the
     * words in order to retire them.
     *
     * Order does not matter — [paragraphOf] takes the nearest opener that
     * follows — so a new paragraph is one entry here, wherever it is added.
     *
     * It is a CLOSED SET rather than a list of strings because the opener used
     * to live in two homes: once in the index that bounds every paragraph, and
     * again, retyped verbatim, at the accessor that asks for it. The drift guard
     * below only ever watched the index, so a reworded first sentence could
     * leave an accessor's literal pointing at nothing while the guard stayed
     * green. With an enum there is one literal per paragraph and an opener
     * outside the set does not compile.
     */
    private enum class CoachParagraph(
      val openingWords: String,
    ) {
      COST("When the student has schools on their college list"),
      SOURCE_JARGON("Never name a data source's internal buckets"),
      COLLEGE_LIST("The student's college list is theirs"),
      FIGURE_STATUS("When a figure has no amount"),
      ADMISSIONS("A school also publishes what it looks for and when it"),
      LIVING_ARRANGEMENT("When a school reports its costs by living arrangement,"),
      SEARCH("When a student names something they want to study, search"),
      COMPARISON("When two or more schools appear together, the cost tool"),
      NAME_LOOKUP("When the student names a school in words -- the full name,"),
      SIMILAR_COLLEGES("When a student names one school and asks for others like"),
      LIVING_PLAN("A cost result may also carry a third precision_offer"),
      COST_REPORT("When the student and you have actually compared what"),
      RESIDENCY_BASIS("At a public school, the published price and the price"),
      FEDERAL_AID("When a family asks about the Pell Grant or federal student"),
      SHARE_NUDGE("When your opening reflections include one about sharing"),
      NEED_AND_FORMS("When a family asks whether a school meets full financial"),
      BORROWING("Borrowing is a different question from price, and you"),
      SEARCH_RULER("A college search now ranks on one of two prices, and which"),
    }

    /**
     * [paragraph]'s opening words as they appear in the SERVED body: the line
     * break, then the copy. The one place the two are put together.
     */
    private fun openerOf(paragraph: CoachParagraph): String = BODY_LINE_BREAK + paragraph.openingWords

    /**
     * The admissions tool the v8 paragraph names (RFC 148), read from the tool
     * itself on this file's own v3/v4/v5/v7 precedent. The pairing under test is
     * SEEDED COPY versus SHIPPING TOOL: a literal here would keep passing after
     * the tool was renamed, leaving the seeded prompt telling the model to call
     * a tool that no longer exists -- prompt-vs-code drift, which is the one
     * failure this class exists to make loud.
     */
    private val ADMISSIONS_TOOL_NAME = CollegeAdmissionsChatTool.TOOL_NAME

    /**
     * The two search-side tools the v12 paragraph routes between (RFC 154), read
     * from the tools themselves for the same reason [ADMISSIONS_TOOL_NAME] is:
     * the pairing under test is seeded copy versus shipping tool.
     */
    private val FIND_COLLEGE_TOOL_NAME = FindCollegeTool.TOOL_NAME
    private val COLLEGE_SEARCH_TOOL_NAME = CollegeSearchTool.TOOL_NAME

    /**
     * The similar-colleges tool the v13 paragraph names (RFC 153), read from
     * the tool itself for the same reason [ADMISSIONS_TOOL_NAME] is: the
     * pairing under test is SEEDED COPY versus SHIPPING TOOL.
     */
    private val SIMILAR_TOOL_NAME = SimilarCollegesTool.TOOL_NAME

    /**
     * The two Family Cost Report tools the v15 paragraph names (RFC 155), read
     * from the tool classes themselves on the same precedent as
     * [ADMISSIONS_TOOL_NAME]: the pairing under test is SEEDED COPY versus
     * SHIPPING TOOL, and a literal here would keep passing after a rename,
     * leaving the seeded prompt telling the model to call a tool that no longer
     * exists.
     */
    private val SHARE_REPORT_TOOL_NAME = ShareCostReportChatTool.TOOL_NAME

    /** See [SHARE_REPORT_TOOL_NAME]: the revoke half of the same pair. */
    private val REVOKE_SHARE_TOOL_NAME = RevokeCostReportShareChatTool.TOOL_NAME

    /**
     * The federal-aid tool the v17 paragraph names (RFC 159), read from the
     * tool itself on the same precedent as [ADMISSIONS_TOOL_NAME]: the pairing
     * under test is SEEDED COPY versus SHIPPING TOOL.
     */
    private val FEDERAL_AID_TOOL_NAME = FederalAidPolicyChatTool.TOOL_NAME

    /**
     * The opt-out tool the v17 paragraph names (RFC 160), read from the tool
     * itself on the same precedent as [SHARE_REPORT_TOOL_NAME]: the pairing
     * under test is SEEDED COPY versus SHIPPING TOOL.
     */
    private val STOP_OFFERS_TOOL_NAME = StopCostReportOffersChatTool.TOOL_NAME

    /**
     * The two interior rules RFC 166 added (the in-district tier, the at-home
     * assumption), each named by its opening words so the served body — not a
     * quoted copy of it — is what the assertions read.
     *
     * They open no paragraph of their own: they sit inside the residency and
     * living-arrangement paragraphs, which is why they are located by their own
     * first words rather than by the paragraph walk.
     */
    private const val IN_DISTRICT_OPENER = "Some public schools publish a third, lower price"

    /** The first words of the at-home assumption v19 inserts (gate-2 D17). */
    private const val AT_HOME_OPENER = "The at-home total counts no housing and food"

    /**
     * The at-home assumption, read from the SHIPPING Kotlin constant rather
     * than retyped (RFC 166 D17), on this file's [ADMISSIONS_TOOL_NAME]
     * precedent: the pairing under test is SEEDED COPY versus SHIPPING CODE.
     *
     * The same sentence is what `AT_HOME_ASSUMPTION_STATEMENT` puts on
     * `ComparisonBasis`, so the family hears ONE wording whether the zero
     * reaches them through the coach or through the parent-facing report. A
     * literal here would keep passing after either side was reworded, leaving
     * the two surfaces quietly saying different things about whose zero it is —
     * which is the drift this pins, and it can only be pinned from one side.
     */
    private val AT_HOME_ASSUMPTION = AT_HOME_ASSUMPTION_STATEMENT

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
   * The file a human edits (RFC 181, RFC 185): `prompts/coach-system-prompt.md`,
   * one sentence per line, from which `bin/prompt-seed` GENERATES the seed
   * migration.
   *
   * Resolved relative to the module directory, which is the working directory of
   * a `:service` test, on this suite's own precedent — `ForbiddenCostArithmeticTest`
   * reads `File("../db/src/main/kotlin/...")` and
   * `MoneyAttributionNamesNoPublisherTest` reads `File("../public-web/src/main")`.
   * A root walk looking for `settings.gradle.kts` would be a second way of
   * answering a question this suite already answers one way.
   */
  private val promptSourceFile = File("../prompts/coach-system-prompt.md")

  /**
   * The body the file says the row must carry: the file itself (RFC 185).
   *
   * There is no rule here any more, and that is the point. RFC 181 restated its
   * join — strip, drop blanks, join with one space — in a third language, so the
   * shell validator, the awk emitter and this test were three implementations of
   * one transform that had to agree on what whitespace was, and did not (the
   * U+2000 case). A verbatim copy has nothing to drift from.
   *
   * `removeSuffix("\n")` is the one adjustment, and it is not a transform of the
   * body: it is how a text file's FINAL LINE TERMINATOR is spelled. `awk` reads
   * records without it, so the seed carries the file's lines and not the byte
   * that ends the last one. Whether an editor leaves that byte is invisible in
   * every diff, so it is a terminator here and never body. On a file with no
   * final newline the call is a no-op, which makes it this rule's exact inverse
   * either way.
   */
  private fun authoredBody(): String = promptSourceFile.readText().removeSuffix("\n")

  /**
   * [this] with the served body's line breaks turned back into the single spaces
   * a Kotlin literal spells them with.
   *
   * For the few assertions whose subject spans TWO authored sentences — a rule
   * and the attribution that must sit beside it, or a shipping constant that is
   * one string in code and two lines in the file. What those assert is
   * ADJACENCY in the copy, and adjacency is what the author's line breaks are
   * free to move: the file is one sentence per line today, and a later author
   * may join two of them or split one without changing a word. Flattening says
   * exactly that and no more; every other assertion in this class reads the
   * served body unflattened, because a single sentence needs no help.
   *
   * It is deliberately NOT the reverse of [BODY_LINE_BREAK]: nothing here turns
   * a space back into a line break, because which spaces were joins is the one
   * fact RFC 181's rule threw away.
   */
  private fun String.replaceLineBreaksWithSpaces(): String = replace(BODY_LINE_BREAK, LEGACY_JOIN_SPACE)

  /**
   * The text around [index], for a failure that has to show WHERE two long
   * bodies part. Both bodies are 25 KB of prose, so `assertEquals` dumps 50 KB
   * into the log and locates nothing; the first differing character, with a
   * window from each side, is the whole finding.
   */
  private fun String.getExcerptAround(index: Int): String = substring((index - 40).coerceAtLeast(0), (index + 40).coerceAtMost(length))

  /**
   * The pinned row as `coach/v27` — the identity every failure about that row
   * carries. "The pinned coach row" is not an identifier: `bin/prompt-seed`
   * moves the label at land, so the reader of a CI log would have to open
   * `service.conf` to learn which row the failure is about.
   */
  private fun pinnedRowLabel(): String = "${coaching.systemPromptName}/${coaching.systemPromptVersion}"

  /**
   * [row]'s body, tagged with the separator the row's OWN version was seeded
   * with: the RFC 181 join space up to [LAST_SPACE_JOINED_VERSION], the source
   * file's line break from v27 on.
   *
   * Every paragraph assertion over a catalog row goes through this, so no call
   * site names a separator and none can name the wrong one.
   */
  private fun bodyOf(row: SystemPrompt): CoachBody =
    CoachBody(
      row.body,
      if (versionNumberOf(row.version) <= LAST_SPACE_JOINED_VERSION) LEGACY_JOIN_SPACE else BODY_LINE_BREAK,
    )

  /**
   * The number in a `vNN` catalog label, refusing anything else.
   *
   * The shape is asserted, never assumed: `removePrefix("v")` is a SILENT NO-OP
   * when the affix does not match — the deleted helper this file replaced said
   * so in its own comment — so an unguarded `removePrefix("v").toInt()` reports
   * a malformed label as an unhandled NumberFormatException, and a leading zero
   * as a number nothing in this catalog names. `bin/prompt-seed` writes these
   * labels with the same grammar (`-v[1-9][0-9]*`).
   */
  private fun versionNumberOf(label: String): Int {
    val number = versionNumberOrNull(label)
    assertNotNull(
      number,
      "a [${coaching.systemPromptName}] version this test reasons about must be a vNN label with no leading zero; [$label] is not, so its number has no meaning",
    )
    return number
  }

  /**
   * The number in a `vNN` label, or null when the label is not one.
   *
   * The null case is not hypothetical and is not corruption: `system_prompts`
   * is shared by every test in this suite and is NOT truncated between tests,
   * and sibling tests insert their own `coach` rows under a `v-<uuid>` label to
   * anchor a foreign key. Those rows are not catalog versions, so a question
   * about the catalog's newest version has to skip them rather than assert they
   * cannot exist — asserting it made the answer depend on which test class ran
   * first.
   */
  private fun versionNumberOrNull(label: String): Int? =
    VERSION_LABEL
      .find(label)
      ?.groupValues
      ?.get(1)
      ?.toInt()

  /** The body the runtime is actually served: the coach row `service.conf` pins. */
  private fun servedBody(): String =
    SystemPromptsDao
      .findByNameAndVersion(session, coaching.systemPromptName, coaching.systemPromptVersion)
      .getOrThrow()
      .body

  /**
   * The `coach` row labelled [version], failing with the PAIR it looked for.
   *
   * `getOrThrow()` on a missing row raises `NotFoundException`, whose message is
   * the bare "Record not found" — and the versions read through here are derived
   * from a constant rather than typed at the call site, so nothing else in the
   * log would name the row that is missing.
   */
  private fun coachBodyAt(version: String): String =
    SystemPromptsDao
      .findByNameAndVersion(session, coaching.systemPromptName, version)
      .getOrElse {
        fail(
          "system_prompts has no row [${coaching.systemPromptName}/$version]; it is one of the two rows the " +
            "RFC 185 transition is stated over, and the table is insert-only, so every version it has ever cut stays " +
            "selectable. Re-migrate: nix develop -c bin/test",
        )
      }.body

  /**
   * One paragraph of the served body: from [paragraph]'s opening words to the
   * next paragraph's, or to the end of the prompt when nothing follows it.
   *
   * THE SCOPING RULE every paragraph test below relies on, stated here once: the
   * paragraph is located by its opening words and read no further than the next
   * paragraph, so an assertion made over the returned text is about THAT
   * paragraph's copy and not about the whole prompt. What a paragraph says is
   * the subject; which version introduced it is git's business now, in the diff
   * of prompts/coach-system-prompt.md (RFC 181).
   *
   * This replaces the version-diffing extractors RFC 181 retired
   * (`appendedParagraph`, `insertedSpan`, `revisedMiddle`). They answered
   * "what did version N add to version N-1?", which is a question about
   * ancestry, and ancestry is now `git diff prompts/coach-system-prompt.md`.
   * What is left is the question those extractors were only ever a means to:
   * does the copy we SHIP say what it must? So the paragraph is located in the
   * shipped body, and no previous version's wording is quoted anywhere.
   */
  private fun paragraphAt(paragraph: CoachParagraph): String = paragraphOf(servedBody(), paragraph)

  /**
   * [paragraphAt]'s substring arithmetic, over a body handed to it rather than
   * one it reads.
   *
   * Split out because the walk — find the opener, then stop at the nearest
   * opener that starts after it — is the part that can be wrong, and it needed
   * a database round trip to exercise. With the body as a parameter the rule is
   * a pure function of text, and [paragraphAt] is the one line that says where
   * the text comes from.
   */
  private fun paragraphOf(
    body: String,
    paragraph: CoachParagraph,
  ): String {
    val start = body.indexOf(openerOf(paragraph))
    assertTrue(start >= 0, "the served coach prompt must contain the paragraph opening [${paragraph.openingWords}]")
    val end =
      CoachParagraph.entries
        .mapNotNull { other -> body.indexOf(openerOf(other)).takeIf { it > start } }
        .minOrNull()
        ?: body.length
    return body.substring(start, end)
  }

  /**
   * The standing copy rules, asserted over one paragraph.
   *
   * These six assertions are not about any one slice: they are the rules every
   * paragraph of the coach prompt has had to satisfy since RFCs 141/142/147 —
   * the retired terms stay retired, no mention of subtracting is a licence, and
   * no source codebook is transcribed into copy a family will hear. They were
   * retyped at each paragraph test, which is how one of them ended up asserted
   * in ten places and skipped in an eleventh with nothing to say so.
   *
   * [name] is passed because a shared helper otherwise reports a failure with
   * no paragraph in it, and "the coach prompt says award somewhere" is a much
   * worse message than the one it replaces.
   *
   * The positive glossary assertions (`tuition and fees`, `housing and food`,
   * `published price`) stay at the call sites: they differ per paragraph, which
   * is precisely what makes them not standing rules.
   */
  private fun assertParagraphKeepsTheStandingCopyRules(
    name: String,
    paragraph: String,
  ) {
    assertFalse(paragraph.contains("room and board"), "[$name] states the retired term, not even contrastively")
    assertFalse(paragraph.contains("sticker"), "[$name] says sticker price; it is the published price (RFC 141)")
    assertFalse(paragraph.contains("award"), "[$name] says award; it is a financial aid offer (RFC 141)")
    assertEquals(
      emptyList(),
      listSubtractionsNotForbidden(paragraph),
      "every mention of subtracting in [$name] must forbid it",
    )
    // The negative below passes for free if CODE_EQUALS_WORD ever stops matching
    // anything, so the pattern's own health is asserted first: a vacuous guard
    // and a clean paragraph are indistinguishable from this assertion alone.
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertFalse(CODE_EQUALS_WORD.containsMatchIn(paragraph), "[$name] transcribes a source codebook")
  }

  /**
   * The central assertion of RFC 181 and RFC 185, and the one that replaced the
   * retired byte-identical-prefix family: the body the runtime is SERVED is
   * exactly the file a human edits — verbatim, byte for byte, with no rule
   * between them.
   *
   * The old tests pinned version N against version N-1's wording quoted as a
   * Kotlin literal, and left the file on disk unchecked. This pins the shipped
   * row against the authored file, so a seed regenerated from a different source,
   * a hand-edited seed, and a source edited without regenerating all fail here.
   *
   * It is the SOLE guarantee that the seed still corresponds to the file (RFC 181
   * D4): the recorded-digest column that used to state the same thing a second
   * time was dropped, because an immutable row cannot stay accurate about a
   * mutable file. So this failure message has to be enough on its own — it names
   * both artifacts, the character they part at, an excerpt from each side, and
   * the one command that fixes it.
   */
  @Test
  fun `the pinned coach prompt body is exactly the authored source file`() {
    assertTrue(
      promptSourceFile.isFile,
      "the authored prompt source must exist at [${promptSourceFile.absolutePath}]; it is what the seed is generated from",
    )
    val served = servedBody()
    val authored = authoredBody()
    val divergence = authored.commonPrefixWith(served).length

    assertEquals(
      authored,
      served,
      "the served coach prompt is not the authored file: row [${pinnedRowLabel()}] and " +
        "[${promptSourceFile.absolutePath}] first differ at character [$divergence] — " +
        "the file says [${authored.getExcerptAround(divergence)}], the row says [${served.getExcerptAround(divergence)}]. " +
        "Either the seed is stale or hand-edited, or the file was edited without regenerating it; " +
        "the seed is a GENERATED artifact, so edit the file and regenerate: nix develop -c bin/prompt-seed",
    )
  }

  /**
   * The acceptance test for "this slice changed no wording" (RFC 185 D2).
   *
   * The transition row changes what the model is served: every sentence-joining
   * space becomes a newline, because the body is now the source file verbatim
   * instead of its lines joined with one space. That is a real change to a real
   * artifact, so it gets a real version — and this states exactly how far the
   * change goes. No word, no sentence and no ordering changed: the diff is
   * WHITESPACE ONLY, and the proof is that the row with every newline replaced
   * by a space is its predecessor byte for byte.
   *
   * The replacement runs one way only, and that is deliberate. Going the other
   * way — turning the predecessor's spaces into newlines — would need to know
   * WHICH spaces were joins, which is the very information RFC 181's join threw
   * away.
   *
   * BOTH LABELS ARE THE TRANSITION'S OWN, read from [LAST_SPACE_JOINED_VERSION],
   * and NEITHER is the pin. They used to be `pin` and `pin - 1`, which read as
   * renumbering-proof and was in fact the opposite: it restated the RFC 185
   * relation about WHATEVER version is pinned, so the next authored prose edit —
   * a v28 whose predecessor v27 already carries line breaks — would fail this
   * test with "RFC 185 changed the mechanism, never a word" about a slice that
   * did change words. `system_prompts` is insert-only, so the pair this test is
   * about is a permanent fact and reading it by label is what makes the claim
   * hold forever.
   *
   * A LATER SLICE THEREFORE LEAVES THIS TEST ALONE: authoring prose adds a
   * version above the pair and says nothing about it. The one thing that moves
   * the pair is THIS slice being renumbered by another run landing first, which
   * moves [LAST_SPACE_JOINED_VERSION] — the constant that owns that fact for
   * every assertion in this file, not just for this one.
   */
  @Test
  fun `the line-broken coach prompt differs from its space-joined predecessor in whitespace only, never a word`() {
    val predecessor = "v$LAST_SPACE_JOINED_VERSION"
    val transition = "v${LAST_SPACE_JOINED_VERSION + 1}"

    val before = coachBodyAt(predecessor)
    val after = coachBodyAt(transition)

    // A vacuous pass is the failure mode worth naming: if the transition body
    // carried no newline at all, the replacement below would be the identity and
    // this test would assert nothing beyond the predecessor test it replaced.
    assertTrue(
      after.contains(BODY_LINE_BREAK),
      "[$transition] must carry the source file's line breaks (RFC 185), or the comparison below is the identity",
    )

    val flattened = after.replaceLineBreaksWithSpaces()
    val divergence = flattened.commonPrefixWith(before).length

    assertEquals(
      before,
      flattened,
      "[$transition] is [$predecessor] with the joins turned into line breaks and NOTHING else: " +
        "flattened, they first differ at character [$divergence] — [$predecessor] says " +
        "[${before.getExcerptAround(divergence)}], [$transition] says [${flattened.getExcerptAround(divergence)}]. " +
        "RFC 185 changed the mechanism and the whitespace, never a word",
    )
  }

  /**
   * The drift guard on [CoachParagraph] itself.
   *
   * The openers are the only thing that says where a paragraph of the served
   * body ENDS, and they are Kotlin literals. If an author rewords the first
   * sentence of a paragraph in `prompts/coach-system-prompt.md` — which RFC 181
   * exists to make easy — a stale opener does not fail loudly: [paragraphOf]
   * would run on to the NEXT opener, quietly widening someone else's
   * paragraph-scoped negative until it scopes nothing at all. Every opener is
   * therefore pinned to a real line start of the file, and to exactly one
   * position in the served body, so the index cannot drift in silence.
   *
   * The lines are read with no trim at all (RFC 185): the body is the file
   * verbatim, so a line of the file is a line of the served body, and trimming
   * one side would compare two strings the shipped artifact never contains.
   * Blank lines are dropped only because no opener can start one.
   */
  @Test
  fun `every paragraph opener still starts a line of the authored source file`() {
    val sentences = promptSourceFile.readText().lines().filter(String::isNotEmpty)
    val body = servedBody()

    CoachParagraph.entries.forEach { paragraph ->
      assertTrue(
        sentences.any { it.startsWith(paragraph.openingWords) },
        "[CoachParagraph.$paragraph] names [${paragraph.openingWords}], which no line of prompts/coach-system-prompt.md starts with; the index has drifted from the file",
      )
      assertEquals(
        1,
        Regex(Regex.escape(openerOf(paragraph))).findAll(body).count(),
        "the opener [${paragraph.openingWords}] must locate exactly one paragraph of the served body, or paragraphOf is scoping by luck",
      )
    }
  }

  /**
   * The pin and the catalog tip are the same version (RFC 181).
   *
   * `bin/prompt-seed` now OWNS the `coaching.systemPromptVersion` line: at land
   * it generates the next coach seed and rewrites the pin to the label it just
   * generated. So the assertion that used to live in `CoachingConfigTest` as
   * `assertEquals("v24", ...)` cannot be a literal any more — any land-time
   * relabel invalidates it, and a literal that has to be edited by the same
   * step that made it wrong guards nothing.
   *
   * What is worth guarding is the relation the generator promises: the runtime
   * serves the NEWEST coach row, never a row the generator has already
   * superseded. That is stated here, in the class that already holds the
   * session and already owns the pin-versus-catalog contract.
   *
   * The tip is found by numeric order on the `vNN` label, not by string order:
   * `v9` sorts after `v24` as text, and the catalog has long since passed the
   * point where that is hypothetical.
   *
   * Rows whose label is not a `vNN` version are SKIPPED, not refused: sibling
   * tests in this suite insert their own `coach` rows under a `v-<uuid>` label
   * and `system_prompts` is not truncated between tests, so refusing them made
   * this assertion depend on which test class happened to run first.
   */
  @Test
  fun `the pinned coach version is the catalog's highest coach version`() {
    val versionNumbers = mutableListOf<Int>()
    connection.prepareStatement("SELECT version FROM system_prompts WHERE name = ?").use { statement ->
      statement.setString(1, coaching.systemPromptName)
      statement.executeQuery().use { rows ->
        while (rows.next()) {
          versionNumberOrNull(rows.getString("version"))?.let { versionNumbers += it }
        }
      }
    }
    assertTrue(
      versionNumbers.isNotEmpty(),
      "the catalog must carry at least one vNN [${coaching.systemPromptName}] row",
    )

    assertEquals(
      "v${versionNumbers.max()}",
      coaching.systemPromptVersion,
      "service.conf pins a coach version that is not the catalog's newest; bin/prompt-seed rewrites this line at land (RFC 181)",
    )
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
   * The money paragraph (RFC 141), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served money paragraph names the cost tool, the offer key and the glossary terms`() {
    val moneyParagraph = moneyParagraph()

    assertTrue(moneyParagraph.contains(CollegeCostChatTool.TOOL_NAME), "the paragraph must name the cost tool")
    assertTrue(
      moneyParagraph.contains("precision_offer"),
      "the paragraph must key the in-answer invitation off the result",
    )
    assertTrue(moneyParagraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(moneyParagraph.contains("housing and food"), "the glossary term that retires room and board")
    // RFC 177's POSITIVE half. MoneyAttributionNamesNoPublisherTest bans the
    // fixed publisher name, which a prompt with no attribution sentence at all
    // would also satisfy; this is the assertion that says the attribution must
    // be there and must be to the source the TOOL names.
    assertTrue(
      moneyParagraph.contains("the source the tool names beside it"),
      "RFC 177: every cost figure is attributed to the source the tool names, never to a fixed publisher",
    )
  }

  /**
   * The money paragraph (RFC 141), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served money paragraph states each contrastive glossary pair`() {
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
   * The money paragraph (RFC 142), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served money paragraph forbids source jargon and says what to say instead`() {
    val sentence = sourceJargonSentence()

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
   * The money paragraph (RFC 145), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served money paragraph raises residency before household income`() {
    val moneyParagraph = moneyParagraph()

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
    assertTrue(moneyParagraph.contains("never raise that field again yourself"), "a decline of either field stays permanent")
  }

  /**
   * The admissions paragraph (RFC 148), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served admissions paragraph routes every admissions question to the tool`() {
    val paragraph = admissionsParagraph()

    assertTrue(paragraph.contains(ADMISSIONS_TOOL_NAME), "the paragraph must name the admissions tool")
    assertTrue(paragraph.contains("Common Data Set"), "the paragraph must say whose data the tool reports")
    assertTrue(
      paragraph.contains("what an admission office weighs") && paragraph.contains("application deadlines"),
      "the paragraph must say when to reach for the tool, not merely that it exists",
    )
    assertTrue(
      paragraph.contains("data_availability"),
      "the first silence: a named field is a school that does not report it",
    )
    assertTrue(
      paragraph.contains("say so plainly rather than estimating it"),
      "an unreported field is stated, never estimated",
    )
    assertTrue(
      paragraph.contains("not missing data"),
      "the second silence: a round flagged not offered is a reported fact",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("a financial aid offer"), "the glossary term survives: an offer, never an award")
    assertFalse(paragraph.contains("award"), "'award' is retired copy (RFC 141) and this paragraph never states it contrastively")
    assertTrue(
      paragraph.contains("never subtract merit money from a published price"),
      "a share and an average are not an offer to this student; they never net out of a price",
    )
  }

  /**
   * The admissions paragraph (RFC 148), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served admissions paragraph carries the honest denominator`() {
    val paragraph = admissionsParagraph()

    assertTrue(
      paragraph.contains("of all full-time freshmen"),
      "the share's denominator must be stated in full: all full-time freshmen",
    )
    assertTrue(paragraph.contains("non-need (merit) aid"), "the approved term for the money itself")
    assertFalse(paragraph.contains("without need"), "the banned denominator: no source reports a count of students without need")
    assertFalse(paragraph.contains("without financial need"), "the same banned denominator, spelled out")
    assertFalse(paragraph.contains("freshmen without"), "no phrasing may narrow the denominator away from all freshmen")
  }

  /**
   * The living-arrangement paragraph (RFC 149), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served living-arrangement paragraph leads with the split, never a blended total`() {
    val paragraph = livingArrangementParagraph()

    assertTrue(
      paragraph.contains("lead with that split rather than with one total"),
      "v9's whole point: the split comes first, not the blended total",
    )
    assertTrue(
      paragraph.contains("Always name which arrangement you are quoting"),
      "the same school has three prices; an unnamed one is a number the family cannot use",
    )
    assertTrue(
      paragraph.contains("living on campus") &&
        paragraph.contains("renting off campus") &&
        paragraph.contains("living at home"),
      "the three arrangements must be named in words a student says, not in wire keys",
    )
    assertTrue(
      paragraph.contains("say they are estimates"),
      "the living-cost lines are the school's own estimates and must be marked as such",
    )
    assertTrue(
      paragraph.contains("living at home instead of on campus would cost"),
      "the at-home comparison is the sentence this slice exists to make sayable",
    )
    assertTrue(
      paragraph.contains("never add up the parts that are there and call the result the total"),
      "a missing total is a missing part, never a sum of whatever happens to be present",
    )
    assertTrue(
      paragraph.contains("no residence halls"),
      "the no-dorms case is an answer the coach states, not an unreported figure",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(paragraph.contains("housing and food"), "the glossary term that retires room and board")
    assertFalse(paragraph.contains("room and board"), "the retired term is never stated here, not even contrastively")
    assertFalse(paragraph.contains("sticker"), "the published price, never the sticker price (RFC 141)")
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
    val served = servedBody()

    val subtractions = SUBTRACT_MENTIONS.findAll(served).map { it.groupValues[1] }.toList()
    assertTrue(subtractions.isNotEmpty(), "the rule must actually be stated, or this assertion is vacuous")
    assertEquals(
      emptyList(),
      listSubtractionsNotForbidden(served),
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
   * The comparison paragraph (RFC 151), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served comparison paragraph keeps one basis to a column`() {
    val paragraph = comparisonParagraph()

    assertTrue(
      paragraph.contains(CollegeCostChatTool.COMPARISON_BASIS_KEY),
      "the paragraph must key the contract off the object the result carries",
    )
    assertTrue(
      paragraph.contains("say those five lines first") && paragraph.contains("above the table"),
      "v11's whole point: the assumptions are said as ordinary copy above the table, never as a note beneath it",
    )
    assertTrue(
      paragraph.contains("above the estimated living costs"),
      "the stable block is rendered above the estimate block, and the two are named",
    )
    assertTrue(
      paragraph.contains("three columns"),
      "RFC 124's cap, restated in the concrete comparison case",
    )
    assertTrue(
      paragraph.contains("leave that cell blank and label it as not reported"),
      "a missing part is a labelled blank; the payload's data_availability is never rendered as a number",
    )
    assertTrue(
      paragraph.contains("never write a zero") && paragraph.contains("never carry a neighbour's number across"),
      "a blank is never a zero and never a neighbour's figure",
    )
    assertTrue(
      paragraph.contains("no residence halls has none"),
      "the no-dorms case is an answer the coach states, not an unreported figure",
    )
    assertTrue(
      paragraph.contains("Keep one residency and one way of living in a column"),
      "two bases never mix into one column - the contract this slice exists for",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(paragraph.contains("housing and food"), "the glossary term that retires room and board")
    // The served-body guard below sweeps the WHOLE prompt; this says the rule
    // holds inside the span v11 actually adds, so a relaxation here is reported
    // as v11's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the comparison paragraph", paragraph)
  }

  /**
   * The living-plan paragraph (RFC 152), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served living-plan paragraph leads with the plan the student answered`() {
    val paragraph = livingPlanParagraph()

    assertTrue(
      paragraph.contains(PrecisionOffer.LIVING_PLAN.field),
      "the paragraph must name the money-profile field the offer fills",
    )
    assertTrue(
      paragraph.contains(PrecisionOffer.RESIDENCY.field) && paragraph.contains(PrecisionOffer.INCOME_BAND.field),
      "and must place it AFTER the two offers that move the number more often (D4)",
    )
    assertTrue(
      paragraph.contains("only when the result offers it") && paragraph.contains("cost is already what you are talking about"),
      "the question is raised only when the result offers it and cost is already the subject",
    )
    assertTrue(
      paragraph.contains("lead with that one way of living"),
      "v14's whole point: an answered plan is what the coach LEADS with",
    )
    LivingArrangement.entries.forEach { arrangement ->
      assertTrue(
        paragraph.contains(arrangement.label),
        "the plan is named in the student's own words, never as a wire key: [${arrangement.label}]",
      )
      assertFalse(
        paragraph.contains(arrangement.value),
        "and the wire key itself is never read aloud: [${arrangement.value}]",
      )
    }
    assertTrue(
      paragraph.contains("A plan set for one school wins over the usual plan at that school"),
      "the per-college override (D2a), stated as the rule the coach applies",
    )
    assertTrue(
      paragraph.contains("Living at home is never something you assume quietly"),
      "with_family is never inferred by us: the assumption is named in the same breath",
    )
    assertTrue(
      paragraph.contains("keep the correction as that school's own plan"),
      "and a correction becomes that school's override, not a rewritten default",
    )
    assertTrue(
      paragraph.contains("say the reason plainly") && paragraph.contains("no residence halls"),
      "a school not priced for the plan gets its reason said, never a substituted arrangement",
    )
    assertTrue(
      paragraph.contains("never quote a different arrangement in its place") &&
        paragraph.contains("never carry a neighbour's figure across"),
      "never a substitute and never a neighbour's figure",
    )
    assertTrue(
      paragraph.contains("The other ways of living stay true and stay available"),
      "the breakdown is never filtered (D2): a \"what if\" stays answerable from the same result",
    )
    assertTrue(
      paragraph.contains("never raise the question again yourself"),
      "a decline is permanent (brief 0001 D11)",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(paragraph.contains("housing and food"), "the glossary term that retires room and board")
    assertParagraphKeepsTheStandingCopyRules("the living-plan paragraph", paragraph)
  }

  /**
   * The cost-report paragraph (RFC 155), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served cost-report paragraph offers the share link only after a real comparison`() {
    val paragraph = costReportParagraph()

    assertTrue(paragraph.contains(SHARE_REPORT_TOOL_NAME), "the paragraph must name the share tool")
    assertTrue(paragraph.contains(REVOKE_SHARE_TOOL_NAME), "the paragraph must name the revoke tool")
    assertTrue(
      paragraph.contains("Offer it only after the comparison has happened"),
      "value before ask (brief 0001 D12): the offer follows a real cost comparison, it never opens one",
    )
    assertTrue(
      paragraph.contains("unless the student asks for it or accepts the offer"),
      "the link is never minted or sent without the student's say-so",
    )
    assertTrue(
      paragraph.contains("anyone who has it can see"),
      "handing over the link must always carry what the link actually is",
    )
    assertTrue(paragraph.contains("needs no login"), "the parent opens it with no account; the coach says so")
    assertTrue(paragraph.contains("can revoke"), "revocation is the control, and the student is told they hold it")
    assertTrue(
      paragraph.contains("every link they have shared is now dead"),
      "RFC 155 D-B: revoke is a promise about every link ever sent, not the latest one",
    )
    assertTrue(
      paragraph.contains("let it change nothing about what you do next"),
      "a declined offer changes nothing: the nudge belongs to first-value/06, not here",
    )
    assertTrue(
      paragraph.contains("the report is live") && paragraph.contains("it updates as the student updates their list"),
      "the page is not a document: a parent must not read last week's list as this week's answer",
    )
    assertTrue(
      paragraph.contains("you get the same one back"),
      "asking again returns the SAME link, so a link a parent already saved keeps working (RFC 155 D-B)",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(paragraph.contains("housing and food"), "the glossary term that retires room and board")
    assertTrue(paragraph.contains("published price"), "the published price, stated positively")
    assertTrue(paragraph.contains("financial aid offer"), "a financial aid offer, stated positively")
    assertFalse(paragraph.contains("without need"), "the banned denominator: no source reports a count of students without need")
    // The served-body guard elsewhere sweeps the WHOLE prompt; this says the
    // rule holds inside the span v15 actually adds, so a relaxation here is
    // reported as v15's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the cost-report paragraph", paragraph)
  }

  /**
   * The rollback RFC 155 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v14`), which is only real if the v14 row is
   * still in the insert-only catalog and still carries the copy it was approved
   * with. Asserted here rather than assumed, because a rollback nobody checks
   * is discovered to be broken at the worst moment.
   */
  @Test
  fun `coach v14 stays selectable so the v15 rollback is real`() {
    val v14 =
      SystemPromptsDao
        .findByNameAndVersion(session, "coach", "v14")
        .getOrElse { cause ->
          fail("the v14 row must remain selectable, or COACHING_SYSTEM_PROMPT_VERSION=v14 is not a rollback", cause)
        }
    val v15 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v15").getOrThrow().body

    assertTrue(v14.body.isNotEmpty(), "the v14 body must be the copy it was seeded with, not an empty row")
    assertTrue(v14.body != v15, "v14 and v15 must be different bodies, or the pin bought nothing")
    assertTrue(
      bodyOf(v14).contains(CoachParagraph.SOURCE_JARGON),
      "v14 must still carry the source-jargon rule it was approved with",
    )
    assertTrue(bodyOf(v14).contains(CoachParagraph.COST), "v14 must still carry the money paragraph it was approved with")
    assertFalse(v14.body.contains(SHARE_REPORT_TOOL_NAME), "the rollback target must not already name the v15 share tool")
  }

  /**
   * The residency-basis paragraph (RFC 157), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served residency-basis paragraph says which residency every figure assumes`() {
    val paragraph = residencyBasisParagraph()

    assertTrue(
      paragraph.contains("students paying in-state tuition and fees"),
      "RFC 157: the basis the two blended figures have always had and never stated",
    )
    assertTrue(
      paragraph.contains("Never offer either of those two figures to a family from another state as their price"),
      "D-A: a figure whose residency does not apply to this family is never offered as theirs",
    )
    assertTrue(
      paragraph.contains("Say the out-of-state total instead"),
      "and the family is pointed at the figure that IS theirs, not left with a blank",
    )
    assertTrue(
      paragraph.contains("out-of-state tuition and fees"),
      "the out-of-state total is named by what it is built from",
    )
    // RFC 177's POSITIVE half, for the residency basis: the basis is published
    // by whichever source the tool names, so the sentence names no publisher.
    assertTrue(
      paragraph.contains("the source the tool names publishes them on that basis"),
      "RFC 177: the residency basis is published by the source the tool names, never by a fixed publisher",
    )
    assertTrue(
      paragraph.contains("A private school publishes one price for every family"),
      "at a private college the distinction does not exist (RFC 135), so nothing changes there",
    )
    assertTrue(
      paragraph.contains("show both figures still and say what basis they are on"),
      "D-B: residency unknown states the basis and withholds nothing",
    )
    assertTrue(
      paragraph.contains("never make an answer wait on it"),
      "guided, not gated (brief 0001 D11): no answer is ever gated on the residency question",
    )
    // RFCs 141/142 money language, carried into the new paragraph.
    assertTrue(paragraph.contains("tuition and fees"), "the glossary term for the price the school sets")
    assertTrue(paragraph.contains("housing and food"), "the glossary term that retires room and board")
    assertTrue(paragraph.contains("published price"), "the published price, stated positively")
    assertTrue(paragraph.contains("financial aid offer"), "a financial aid offer, stated positively")
    assertFalse(paragraph.contains("without need"), "the banned denominator: no source reports a count of students without need")
    // The served-body guard elsewhere sweeps the WHOLE prompt; this says the
    // rule holds inside the span v16 actually adds, so a relaxation here is
    // reported as v16's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the residency-basis paragraph", paragraph)
  }

  /**
   * The rollback RFC 157 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v15`), which is only real if the v15 row is
   * still in the insert-only catalog and still carries the copy it was approved
   * with. Asserted here rather than assumed, on the same precedent as the v15
   * rollback test above: a rollback nobody checks is discovered to be broken at
   * the worst moment.
   */
  @Test
  fun `coach v15 stays selectable so the v16 rollback is real`() {
    val v15 =
      SystemPromptsDao
        .findByNameAndVersion(session, "coach", "v15")
        .getOrElse { cause ->
          fail("the v15 row must remain selectable, or COACHING_SYSTEM_PROMPT_VERSION=v15 is not a rollback", cause)
        }
    val v16 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v16").getOrThrow().body

    assertTrue(v15.body.isNotEmpty(), "the v15 body must be the copy it was seeded with, not an empty row")
    assertTrue(v15.body != v16, "v15 and v16 must be different bodies, or the pin bought nothing")
    assertTrue(
      bodyOf(v15).contains(CoachParagraph.SOURCE_JARGON),
      "v15 must still carry the source-jargon rule it was approved with",
    )
    assertTrue(bodyOf(v15).contains(CoachParagraph.COST), "v15 must still carry the money paragraph it was approved with")
    assertFalse(
      v15.body.contains("students paying in-state tuition and fees"),
      "the rollback target must not already carry v16's residency-basis rule",
    )
  }

  private fun federalAidParagraph(): String = paragraphAt(CoachParagraph.FEDERAL_AID)

  /**
   * The federal-aid paragraph (RFC 159), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served federal-aid paragraph dates every figure by its award year`() {
    val paragraph = federalAidParagraph()

    // Seeded copy versus SHIPPING tool name, read from the tool itself: a
    // literal here would keep passing after a rename, leaving the prompt
    // naming a tool the registry does not serve.
    assertTrue(paragraph.contains(FEDERAL_AID_TOOL_NAME), "the paragraph must name the federal-aid tool")
    assertTrue(
      paragraph.contains("rather than from memory"),
      "policy figures come from the tool, never remembered (RFC 159 D-D)",
    )
    assertTrue(
      paragraph.contains("Always say which award year a figure is for"),
      "the slice's first acceptance criterion: every figure is dated by its award year",
    )
    assertTrue(
      paragraph.contains("never a promised amount") && paragraph.contains("never promise a family a specific Pell amount"),
      "Pell is eligibility and a range, never a promised amount",
    )
    assertTrue(
      paragraph.contains("never subtract a loan or a loan limit from any price"),
      "brief 0003: a loan limit is a cap, never a discount",
    )
    assertTrue(
      paragraph.contains("prior award year") && paragraph.contains("say that plainly"),
      "D-G: a stale award year is said, never silently served",
    )
    assertTrue(
      paragraph.contains("dependent or independent for federal aid") &&
        paragraph.contains("most students applying straight from high school are dependent"),
      "D-F: the dependency question is invited with its value named",
    )
    assertTrue(
      paragraph.contains("answer fully anyway with both sets of figures") &&
        paragraph.contains("never raise it again yourself"),
      "declinable, with a full answer served regardless — guided, not gated",
    )
    // The retired money words stay retired inside the new span.
    assertFalse(paragraph.contains("room and board"), "the retired term is never stated here, not even contrastively")
    assertFalse(paragraph.contains("sticker"), "the published price, never the sticker price (RFC 141)")
    // The served-body guard elsewhere sweeps the WHOLE prompt; this says the
    // rule holds inside the span v17 actually adds, so a relaxation here is
    // reported as v17's own rather than as the catalog's.
    assertEquals(
      emptyList(),
      listSubtractionsNotForbidden(paragraph),
      "every mention of subtracting in the new paragraph must forbid it",
    )
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertFalse(CODE_EQUALS_WORD.containsMatchIn(paragraph), "the new paragraph must transcribe no source codebook")
  }

  /**
   * The share-nudge paragraph (RFC 160), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served share-nudge paragraph treats a surfaced reflection as the sanctioned offer`() {
    val paragraph = shareNudgeParagraph()

    assertTrue(paragraph.contains(SHARE_REPORT_TOOL_NAME), "the paragraph must name the share tool it routes to")
    assertTrue(paragraph.contains(STOP_OFFERS_TOOL_NAME), "the paragraph must name the opt-out tool")
    assertTrue(
      paragraph.contains("the sanctioned moment"),
      "RFC 160: a surfaced share-nudge reflection IS the sanctioned offer",
    )
    assertTrue(
      paragraph.contains("you still never open with the offer"),
      "the never-open-unasked rule stands in every other conversation",
    )
    assertTrue(
      paragraph.contains("the topic is closed for this conversation"),
      "a decline or deferral ends the topic without residue",
    )
    assertTrue(
      paragraph.contains("Nothing you offer is ever gated on sharing"),
      "guided, not gated (brief 0001 D11)",
    )
    assertTrue(
      paragraph.contains("they can still ask to share"),
      "opting out of the suggestion never disables the ability",
    )
    // The paragraph states no price and no price arithmetic; the standing money
    // guards still sweep the appended span so a relaxation is reported as v18's own.
    assertParagraphKeepsTheStandingCopyRules("the share-nudge paragraph", paragraph)
  }

  /**
   * The rollback RFC 160 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v17`), which is only real if the v17 row is
   * still in the insert-only catalog, still carries the copy it was approved
   * with, and does not already name the share-nudge tool.
   */
  @Test
  fun `coach v17 stays selectable as v18's rollback target`() {
    val v17 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v17").getOrThrow()

    assertEquals("v17", v17.version, "the rollback target must still be selectable by name and version")
    assertTrue(v17.body.contains(FEDERAL_AID_TOOL_NAME), "v17 must still carry RFC 159's federal-aid copy")
    assertFalse(
      v17.body.contains(STOP_OFFERS_TOOL_NAME),
      "the rollback target must not already name the v18 opt-out tool",
    )
  }

  /**
   * The v17 pin's documented rollback is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v16`), which is only real if the v16 row
   * is still selectable and still carries the copy it was seeded with.
   * Asserted here rather than assumed, on the same precedent as the v15/v16
   * rollback tests above.
   */
  @Test
  fun `coach v16 stays selectable so the v17 rollback is real`() {
    val v16 =
      SystemPromptsDao
        .findByNameAndVersion(session, "coach", "v16")
        .getOrElse { cause ->
          fail("the v16 row must remain selectable, or COACHING_SYSTEM_PROMPT_VERSION=v16 is not a rollback", cause)
        }
    val v17 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v17").getOrThrow().body

    assertTrue(v16.body.isNotEmpty(), "the v16 body must be the copy it was seeded with, not an empty row")
    assertTrue(v16.body != v17, "v16 and v17 must be different bodies, or the pin bought nothing")
    assertTrue(
      bodyOf(v16).contains(CoachParagraph.SOURCE_JARGON),
      "v16 must still carry the source-jargon rule it was approved with",
    )
    assertTrue(bodyOf(v16).contains(CoachParagraph.COST), "v16 must still carry the money paragraph it was approved with")
    assertFalse(
      v16.body.contains(FEDERAL_AID_TOOL_NAME),
      "the rollback target must not already name the v17 federal-aid tool",
    )
    assertFalse(
      v16.body.contains(STOP_OFFERS_TOOL_NAME),
      "the rollback target must not already name the v18 opt-out tool",
    )
  }

  /**
   * The need-and-forms paragraph (RFC 170), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served need-and-forms paragraph gives the two figures and never a verdict`() {
    val paragraph = needAndFormsParagraph()

    assertTrue(paragraph.contains(AidPolicyWire.KEY), "the paragraph must name the section it routes to")
    assertTrue(paragraph.contains(AidPolicyWire.FORMS_KEY), "the paragraph must name the forms list")
    assertTrue(
      paragraph.contains(CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY),
      "the paragraph must name the key that says why a school has no section",
    )
    // D6: no source publishes a yes/no about meeting full need, so the coach
    // must never produce one.
    assertTrue(
      paragraph.contains("neither of them is a yes or a no"),
      "the two figures answer the question; a verdict of the coach's own does not",
    )
    assertTrue(
      paragraph.contains("never give one of your own"),
      "RFC 170 D6: the meets-full-need verdict is not the coach's to give",
    )
    // RFC 148's denominator rule, carried into the copy: both figures are
    // about a much smaller population than "freshmen".
    assertTrue(
      paragraph.contains("never about every freshman and never about this student"),
      "each figure must be said over the cohort it is actually about",
    )
    // D5: absence is absence from a FILING, never a school's denial.
    assertTrue(
      paragraph.contains("not listed in that filing"),
      "an absent form is absent from the filing",
    )
    assertTrue(
      paragraph.contains("not the school saying it is not required"),
      "RFC 170 D5: no source publishes a negative, so the coach must not imply one",
    )
    // The standing money guards still sweep the appended span, so a relaxation
    // is reported as v20's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the need-and-forms paragraph", paragraph)
    // No bare CDS field id may reach the model, and the prompt is context too.
    assertFalse(Regex("H\\.\\d").containsMatchIn(paragraph), "a CDS field id must never appear in the prompt")
  }

  /**
   * The rollback RFC 170 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v19`), which is only real if the v19 row
   * is still in the insert-only catalog, still carries the copy it was approved
   * with, and does not already carry the need-and-forms instruction.
   */
  @Test
  fun `coach v19 stays selectable as v20's rollback target`() {
    val v19 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v19").getOrThrow()

    assertEquals("v19", v19.version, "the rollback target must still be selectable by name and version")
    assertTrue(bodyOf(v19).contains(CoachParagraph.FIGURE_STATUS), "v19 must still carry RFC 166's figure-status copy")
    // NOT a bare `aid_policy` check: v17's paragraph names the
    // `federal_aid_policy` TOOL, which contains that key as a substring. The
    // marker is the phrase the v20 paragraph actually adds.
    assertFalse(
      v19.body.contains("the ${AidPolicyWire.KEY} section of college_cost_profile"),
      "the rollback target must not already name the v20 aid-policy section",
    )
  }

  private fun searchRulerParagraph(): String = paragraphAt(CoachParagraph.SEARCH_RULER)

  /**
   * The search-ruler paragraph (RFC 169), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served search-ruler paragraph says which ruler is in force and why`() {
    val paragraph = searchRulerParagraph()

    // Both tools by the name the registry actually advertises, never a literal.
    assertTrue(paragraph.contains(COLLEGE_SEARCH_TOOL_NAME), "the paragraph must name the search tool it is about")
    assertTrue(paragraph.contains(SIMILAR_TOOL_NAME), "and the peer tool, which ranks on the same ruler")
    assertTrue(
      paragraph.contains("depends on whether the state the family lives in is on file"),
      "RFC 169 D1: which ruler is in force, and why",
    )
    assertTrue(
      paragraph.contains("No financial aid of any kind is in a published ranking"),
      "D14(a): a published ranking says aid is not in it, never silently",
    )
    assertTrue(
      paragraph.contains("There is no out-of-state price after aid and there never can be"),
      "the honest reason the published ruler exists at all (RFC 149)",
    )
    assertTrue(
      paragraph.contains("Never require the answer, never hold back a result waiting for it"),
      "D6: the state question is offered and never gates -- guided, not gated",
    )
    assertTrue(
      paragraph.contains("excluded_unknown"),
      "D11: a school with no figure on the ruler in force is dropped and COUNTED, never treated as cheap",
    )
    // The two places this paragraph has to agree with v19 rather than merely
    // follow it. RFC 166 gave the COST answers a third tuition tier and a
    // sentence for the school whose publisher separates none; a search ranks two
    // tiers and now emits that same sentence on a row. Silence on either would
    // let the coach read a search price back as an in-district one, or read the
    // shared sentence as a claim about what the school charges.
    assertTrue(
      paragraph.contains("the in-district tier is a cost answer and never a search ranking"),
      "the search ruler has two tiers, and v19's third one is not one of them",
    )
    assertTrue(
      paragraph.contains(RESIDENCY_TIERS_KEY),
      "brief 0006 D19: a search row can carry the same tier sentence a cost answer carries",
    )
    assertTrue(
      v21Body().contains(IN_DISTRICT_OPENER),
      "the premise: v19 is where the in-district tier lives and v21 still carries it, or the sentences above guard nothing",
    )
    // The standing money guards, swept over the span v21 actually adds, so a
    // relaxation here is reported as v21's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the search-ruler paragraph", paragraph)
    // v6's ban on source-internal names still governs: the paragraph names
    // PAYLOAD keys and TOOL names, and no canonical table or publisher column.
    assertFalse(paragraph.contains("price_figures"), "a canonical table name is never said to a family")
    assertFalse(paragraph.contains("CHG"), "an IPEDS charge code is never said to a family")
  }

  /**
   * The v22 wording of the two spans RFC 177 rewrites, quoted from the IMMUTABLE
   * `db/schema/0092` row.
   *
   * A literal, and safe as one for exactly one reason: it is the copy of a row
   * that can never be updated in place (`db/schema/0007`'s triggers), so it
   * cannot drift. It is what makes the rollback claim real — v22 is only a
   * rollback if it still carries the copy it was approved with.
   */
  private val v22MoneyAttribution =
    "Always attribute cost figures to the U.S. Department of Education College Scorecard, and when a " +
      "school doesn't report a figure, say that plainly rather than estimating."

  /** The v23 attribution copy, named here only to assert v22 does NOT already carry it. */
  private val moneyAttributionOpener = "Always attribute each cost figure"

  /**
   * The rollback RFC 177 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v22`), which is only real if the v22 row is
   * still in the insert-only catalog and still carries the copy it was approved
   * with -- including the Scorecard attribution v23 removes.
   */
  @Test
  fun `coach v22 stays selectable as v23's rollback target`() {
    val v22 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v22").getOrThrow()

    assertEquals("v22", v22.version, "the rollback target must still be selectable by name and version")
    assertTrue(v22.body.contains(v22MoneyAttribution), "v22 must still carry the copy it was approved with")
    assertFalse(
      v22.body.contains(moneyAttributionOpener),
      "the rollback target must not already carry the v23 attribution copy",
    )
  }

  /**
   * The rollback RFC 185 D2 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v26`), which is only real if the v26 row is
   * still in the insert-only catalog and still carries the pre-RFC-185 body --
   * the one whose sentences are joined with a single space.
   *
   * The label is a LITERAL, on the v14-v22 precedent, and that is the point of
   * this test rather than an oversight. It names the row RFC 185's rollback
   * sentence names, and it goes on naming it after the pin moves again. The
   * whitespace-only test above reads the same row through a label DERIVED from
   * [LAST_SPACE_JOINED_VERSION], which is a fact about the transition rather
   * than about the rollback. A rollback target is an immutable row, so nothing
   * about it moves when the pin does.
   *
   * v25 is not named here. It is an equally valid rollback target -- v24, v25
   * and v26 carry the same space-joined body, because two runs that authored
   * nothing regenerated the seed at land -- but the version RFC 185's own
   * rollback sentence documents is the one immediately below the pin, and a
   * test per identical row would assert the same fact three times.
   */
  @Test
  fun `coach v26 stays selectable so the v27 rollback is real`() {
    val v26 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v26").getOrThrow()

    assertEquals("v26", v26.version, "the rollback target must still be selectable by name and version")
    assertFalse(
      v26.body.contains(BODY_LINE_BREAK),
      "v26 is a pre-RFC-185 row: its authored lines are joined with one space, never a line break",
    )
    assertTrue(bodyOf(v26).contains(CoachParagraph.COST), "v26 must still carry the money paragraph it was approved with")
  }

  /**
   * The rollback RFC 169 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v21`), which is only real if the v21 row is
   * still in the insert-only catalog, still carries the copy it was approved
   * with, and does not already carry the search-ruler instruction.
   */
  @Test
  fun `coach v21 stays selectable as v22's rollback target`() {
    val v21 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v21").getOrThrow()

    assertEquals("v21", v21.version, "the rollback target must still be selectable by name and version")
    assertTrue(
      v21.body.contains(CollegeCostChatTool.BORROWING_AVAILABILITY_KEY),
      "v21 must still carry RFC 175's borrowing copy",
    )
    assertFalse(
      v21.body.contains("ranks on one of two prices"),
      "the rollback target must not already carry the v22 search-ruler copy",
    )
  }

  /** The served v21 body, read from the catalog so the migration stays the one home of the copy. */
  private fun v21Body(): String = SystemPromptsDao.findByNameAndVersion(session, "coach", "v21").getOrThrow().body

  private fun needAndFormsParagraph(): String = paragraphAt(CoachParagraph.NEED_AND_FORMS)

  /**
   * The borrowing paragraph (RFC 175), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served borrowing paragraph never presents a debt figure as a price`() {
    val paragraph = borrowingParagraph()

    assertTrue(paragraph.contains(BorrowingWire.KEY), "the paragraph must name the section it routes to")
    assertTrue(
      paragraph.contains(CollegeCostChatTool.BORROWING_AVAILABILITY_KEY),
      "the paragraph must name the key that says why a school has no section",
    )
    // D8: the cohort is a named graduating class, said with its year.
    assertTrue(
      paragraph.contains("GRADUATED from that school in the year the section names"),
      "the cohort must be named, not left to be inferred",
    )
    assertTrue(
      paragraph.contains("not this year's freshmen"),
      "and it must be told apart from the two cohorts it is most often confused with",
    )
    // D10: a Common Data Set figure is the school's own claim.
    assertTrue(
      paragraph.contains("so name the school as the one saying it"),
      "RFC 175 D10: a self-reported figure is attributed, never said in the flat voice",
    )
    // D3: loan types are never summed and none of them is a total.
    assertTrue(
      paragraph.contains("Never add two kinds of loan together"),
      "RFC 175 D3: the loan types overlap and may not be added",
    )
    assertTrue(
      paragraph.contains("say plainly when the private figure is not in that school's filing"),
      "a missing private figure is said, not left as federal standing for everything",
    )
    // Brief 0003, without exception: a debt is never a price.
    assertTrue(
      paragraph.contains("never present a debt figure as a cost"),
      "a debt figure is never presented as a price",
    )
    // The standing money guards still sweep the appended span, so a relaxation
    // is reported as v21's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the borrowing paragraph", paragraph)
    // No bare CDS field id may reach the model, and the prompt is context too.
    assertFalse(Regex("H\\.\\d").containsMatchIn(paragraph), "a CDS field id must never appear in the prompt")
  }

  /**
   * The v21 pin's documented rollback is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v20`), which is only real if the v20 row
   * is still selectable and still carries the copy it was seeded with.
   */
  @Test
  fun `coach v20 stays selectable so the v21 rollback is real`() {
    val v20 =
      SystemPromptsDao
        .findByNameAndVersion(session, "coach", "v20")
        .getOrElse { cause ->
          fail("the v20 row must remain selectable, or COACHING_SYSTEM_PROMPT_VERSION=v20 is not a rollback", cause)
        }

    assertTrue(v20.body.isNotEmpty(), "the v20 body must be the copy it was seeded with, not an empty row")
    assertTrue(v20.body.contains(AidPolicyWire.KEY), "v20 must still carry its own aid-policy paragraph")
    assertFalse(
      v20.body.contains(BorrowingWire.KEY),
      "the rollback target must not already name the v21 borrowing section",
    )
  }

  private fun borrowingParagraph(): String = paragraphAt(CoachParagraph.BORROWING)

  private fun shareNudgeParagraph(): String = paragraphAt(CoachParagraph.SHARE_NUDGE)

  /**
   * The rollback RFC 166 documents is one env var
   * (`COACHING_SYSTEM_PROMPT_VERSION=v18`), which is only real if the v18 row is
   * still in the insert-only catalog and still carries the copy it was approved
   * with. Asserted here rather than assumed, on the same precedent as every
   * rollback test above: a rollback nobody checks is discovered to be broken at
   * the worst moment.
   */
  @Test
  fun `coach v18 stays selectable as v19's rollback target`() {
    val v18 =
      SystemPromptsDao
        .findByNameAndVersion(session, "coach", "v18")
        .getOrElse { cause ->
          fail("the v18 row must remain selectable, or COACHING_SYSTEM_PROMPT_VERSION=v18 is not a rollback", cause)
        }
    val v19 = SystemPromptsDao.findByNameAndVersion(session, "coach", "v19").getOrThrow().body

    assertTrue(v18.body.isNotEmpty(), "the v18 body must be the copy it was seeded with, not an empty row")
    assertTrue(v18.body != v19, "v18 and v19 must be different bodies, or the pin bought nothing")
    assertTrue(
      bodyOf(v18).contains(CoachParagraph.SOURCE_JARGON),
      "v18 must still carry the source-jargon rule it was approved with",
    )
    assertTrue(v18.body.contains(STOP_OFFERS_TOOL_NAME), "v18 must still carry RFC 160's opt-out copy")
    assertFalse(v18.body.contains(IN_DISTRICT_OPENER), "the rollback target must not already carry v19's in-district tier")
    assertFalse(v18.body.contains(AT_HOME_OPENER), "nor v19's at-home assumption")
    assertFalse(bodyOf(v18).contains(CoachParagraph.FIGURE_STATUS), "nor v19's figure-status paragraph")
  }

  private fun residencyBasisParagraph(): String = paragraphAt(CoachParagraph.RESIDENCY_BASIS)

  private fun costReportParagraph(): String = paragraphAt(CoachParagraph.COST_REPORT)

  private fun livingPlanParagraph(): String = paragraphAt(CoachParagraph.LIVING_PLAN)

  private fun comparisonParagraph(): String = paragraphAt(CoachParagraph.COMPARISON)

  /**
   * The name-lookup paragraph (RFC 154), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served name-lookup paragraph copies an id and never constructs one`() {
    val paragraph = nameLookupParagraph()

    // Seeded copy versus SHIPPING tool names, both read from the tools: a
    // literal here would keep passing after a rename, leaving the prompt naming
    // a tool the registry does not serve.
    assertTrue(
      paragraph.contains(FIND_COLLEGE_TOOL_NAME),
      "the paragraph must name the lookup tool the registry serves",
    )
    assertTrue(
      paragraph.contains(COLLEGE_SEARCH_TOOL_NAME),
      "and the structured search it routes the OTHER kind of question to",
    )
    assertTrue(
      paragraph.contains(CollegeListChatTool.TOOL_NAME) &&
        paragraph.contains(CollegeCostChatTool.TOOL_NAME) &&
        paragraph.contains(ADMISSIONS_TOOL_NAME),
      "the id is carried to every tool that takes one, each named as it ships",
    )
    assertTrue(
      paragraph.contains("college_id"),
      "the id travels between tools under the one word the tool schemas use",
    )
    assertTrue(
      paragraph.contains("never build an id yourself") &&
        paragraph.contains("never guess one") &&
        paragraph.contains("never ask the student"),
      "RFC 154: an id is copied from a lookup, never constructed, guessed, or requested",
    )
    assertTrue(
      paragraph.contains("the name is ambiguous") && paragraph.contains("city and state"),
      "several matches is a question to the student, told apart by the fields the lookup returns",
    )
    assertTrue(
      paragraph.contains("temporarily unavailable") && paragraph.contains("never tell them the school does not exist"),
      "an unbuilt index is a deployment state, never an empty world (RFC 154 D-C)",
    )
    // RFCs 141/142 money language: the paragraph names no retired term, which is
    // what keeps the appended span assertable by absence.
    // The served-body guard below sweeps the WHOLE prompt; this says the rule
    // holds inside the span v12 actually adds, so a relaxation here is reported
    // as v12's own rather than as the catalog's.
    assertParagraphKeepsTheStandingCopyRules("the name-lookup paragraph", paragraph)
  }

  private fun nameLookupParagraph(): String = paragraphAt(CoachParagraph.NAME_LOOKUP)

  /**
   * The similar-colleges paragraph (RFC 153), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served similar-colleges paragraph says the axes it used and the axes it dropped`() {
    val paragraph = similarCollegesParagraph()

    // The tool is named from the CONSTANT, never typed: a literal would keep
    // passing after the tool was renamed, leaving the seeded prompt telling the
    // model to call a tool that no longer exists.
    assertTrue(
      paragraph.contains(SIMILAR_TOOL_NAME),
      "the paragraph must name the shipping tool [$SIMILAR_TOOL_NAME]: [$paragraph]",
    )
    assertTrue(
      paragraph.contains("rather than naming peers from memory"),
      "D62: similarity is decided by the call, not by a list the model imagines it knows",
    )
    // The THIRD tool role, and only the third: this paragraph routes an anchor
    // by id and claims NO name resolution of its own, because v12 already made
    // find_college the one name-to-id door and two doors would teach the coach
    // that either will do.
    assertTrue(
      paragraph.contains("college_id") && paragraph.contains(FIND_COLLEGE_TOOL_NAME),
      "the anchor is a college_id, and [$FIND_COLLEGE_TOOL_NAME] is what turns a name into one: [$paragraph]",
    )
    assertTrue(
      paragraph.contains("which axes the answer ranked on") && paragraph.contains("had to drop"),
      "D67: the axes used AND the axes dropped are both said, with the reason",
    )
    assertTrue(
      paragraph.contains("a silence is never a no"),
      "D67 again: an unreported figure was not judged, and is not a failure",
    )
    // The pairing under test is SEEDED COPY versus SHIPPING PAYLOAD, so the key
    // is READ from the tool, never typed: an immutable seed cannot import a
    // constant, and renaming the key would otherwise leave the shipped prompt
    // telling the coach to read a field the tool no longer emits.
    assertTrue(
      paragraph.contains(SimilarCollegesTool.AXES_SCORED_KEY),
      "the paragraph must name the shipping key [${SimilarCollegesTool.AXES_SCORED_KEY}]: [$paragraph]",
    )
    assertTrue(
      paragraph.contains("rank aid") && paragraph.contains("Never say it as a percentage"),
      "D70: distance is never rendered as a percentage or as a quality score",
    )
    // RFCs 141/142 money language: the paragraph names no retired term, which is
    // what keeps the appended span assertable by absence.
    // The served-body guard below sweeps the WHOLE prompt; this says the rule
    // holds inside the span v13 actually adds.
    assertParagraphKeepsTheStandingCopyRules("the similar-colleges paragraph", paragraph)
  }

  private fun similarCollegesParagraph(): String = paragraphAt(CoachParagraph.SIMILAR_COLLEGES)

  private fun livingArrangementParagraph(): String = paragraphAt(CoachParagraph.LIVING_ARRANGEMENT)

  /**
   * The search paragraph (RFC 150), asserted in the body the runtime is
   * actually SERVED — the row `service.conf` pins.
   */
  @Test
  fun `the served search paragraph teaches the subject word and the honest count`() {
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

  private fun searchParagraph(): String = paragraphAt(CoachParagraph.SEARCH)

  /**
   * The six figure statuses (RFC 166), asserted in the body the runtime is
   * SERVED — the paragraph that tells the coach to say the entry's own sentence
   * when a figure has no amount.
   *
   * Lifted, when RFC 181 retired the v19-versus-v18 reconstruction test, from
   * that test's third section: the reconstruction was ancestry, but WHICH
   * sentences the prompt recites is copy that still ships, and it is checked
   * here against [FigureStatusCopy] rather than against literals.
   */
  @Test
  fun `the served figure-status paragraph recites every shipping status sentence`() {
    val statuses = paragraphAt(CoachParagraph.FIGURE_STATUS)

    // The key is READ from the tool, never typed: the paragraph tells the coach
    // to read an array off the payload, and a literal here would keep passing
    // after a rename, leaving the prompt naming a key the tool does not emit.
    assertTrue(
      statuses.contains(CollegeCostChatTool.FIGURE_STATUSES_KEY),
      "the paragraph must name the shipping key [${CollegeCostChatTool.FIGURE_STATUSES_KEY}]: [$statuses]",
    )
    // EXHAUSTIVE over the vocabulary, and read from the SHIPPING sentence: a
    // reword in FigureStatusCopy would otherwise ship sentence A in the payload
    // while the prompt enumerated sentence B, with nothing failing. Exhaustive,
    // so a SEVENTH status also fails here rather than reaching the coach unspoken.
    FigureStatus.entries.forEach { status ->
      // REPORTED carries no sentence at all — a plainly reported figure is
      // given plainly — and the paragraph says exactly that, below.
      val spoken = FigureStatusCopy.agentlessStatementOf(status) ?: return@forEach
      assertTrue(
        statuses.contains(spoken),
        "the served prompt must recite the SHIPPING sentence for [${status.value}]: [$spoken] in [$statuses]",
      )
    }
    assertTrue(
      statuses.contains("A figure that is simply reported needs none of these sentences"),
      "reported is the sixth status: the number is given plainly, with no sentence beside it",
    )
    // The OURS/THEIRS split is keyed on the SENTENCE, so the sentence and the
    // attribution are asserted TOGETHER: reworded in FigureStatusCopy and only
    // half-updated here, the paragraph would recite our gap among the school's
    // own silences — the misattribution RFC 149 D-B exists against.
    val ours = assertNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_COLLECTED_BY_US))
    assertTrue(
      statuses.replaceLineBreaksWithSpaces().contains("$ours That last sentence is ours and not the school's"),
      "the sentence that is OURS must be attributed as ours where it is said: [$statuses]",
    )
    assertTrue(
      statuses.contains("it is our gap, so never tell a family the school failed to report"),
      "D-B reused: a status that is OURS is never spoken as the school's failure",
    )

    assertFalse(statuses.contains("room and board"), "the retired term is never stated here, not even contrastively")
    assertFalse(statuses.contains("sticker"), "the published price, never the sticker price (RFC 141)")
    assertEquals(
      emptyList(),
      listSubtractionsNotForbidden(statuses),
      "every mention of subtracting in this paragraph must forbid it",
    )
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertFalse(CODE_EQUALS_WORD.containsMatchIn(statuses), "this paragraph must transcribe no source codebook")
  }

  /**
   * The two interior rules RFC 166 added — the in-district tier and the at-home
   * assumption — asserted in the body the runtime is SERVED.
   *
   * Lifted from the retired v19-versus-v18 reconstruction test for the same
   * reason as the figure statuses above: what the rules SAY still ships, and it
   * is stated here as a property of the shipped prompt rather than as a
   * subtraction against a previous version's body.
   */
  @Test
  fun `the served coach prompt states the in-district tier and the at-home assumption`() {
    val served = servedBody()

    // 1. The third tier, and the district that is never asked about (RFC 166 D4).
    assertTrue(served.contains(IN_DISTRICT_OPENER), "the served prompt must carry the in-district tier: [$IN_DISTRICT_OPENER]")
    assertTrue(
      served.contains("never as the tuition and fees line inside a total"),
      "the in-district figure is a labelled tier, never the tuition line inside a total",
    )
    assertTrue(
      served.contains("never present an in-state figure as an in-district one"),
      "RFC 161's open item: a figure keeps the label its publisher gave it",
    )
    assertTrue(
      served.contains("Never ask which district a family lives in"),
      "no new question: residency_state cannot select in-district, and a state answer does not answer a district",
    )
    assertTrue(
      served.contains("does not separate an in-district price, say so in words"),
      "where the tier is not published, the coach says what we do not know rather than guessing",
    )

    // 2. The at-home zero, in words, and ours (gate-2 D17). The sentence is read
    // from the shipping constant, so a reword there fails here rather than
    // leaving the prompt and the report saying two different things.
    assertTrue(served.contains(AT_HOME_OPENER), "the served prompt must carry the at-home assumption: [$AT_HOME_OPENER]")
    assertTrue(
      served.replaceLineBreaksWithSpaces().contains(AT_HOME_ASSUMPTION),
      "the assumption must be said in the exact words the report says",
    )
    assertTrue(
      served.contains("zero is ours rather than the school's") && served.contains("never say the school reported it"),
      "the zero is a unicoach assumption and is never attributed to the school",
    )
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

  private fun admissionsParagraph(): String = paragraphAt(CoachParagraph.ADMISSIONS)

  /**
   * The source-jargon sentence, as the served body carries it: the tail of the
   * money paragraph, from its own opening words to the college-list paragraph.
   */
  private fun sourceJargonSentence(): String = paragraphAt(CoachParagraph.SOURCE_JARGON)

  /**
   * The money paragraph, as the served body carries it: from its opening words
   * to the source-jargon sentence that closes it.
   */
  private fun moneyParagraph(): String = paragraphAt(CoachParagraph.COST)
}
