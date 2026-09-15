package ed.unicoach.coaching.aid

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.CHECKED_ON_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.CITED_AS_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.COVERAGE_NOTE_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.RULES_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.SOURCE_NOTE_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.STATEMENT_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.TOPICS_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.TOPIC_KEY
import ed.unicoach.coaching.aid.FafsaProcessChatTool.Companion.URL_KEY
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaProcessTopic
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rendered half of RFC 216: the `fafsa_process` payload, which is a curated
 * corpus and reads no store at all -- so this test needs no database and no
 * clock, only the student id the tool contract carries and never uses.
 *
 * The assertions below are the slice's acceptance criteria, one @Test each: a
 * load-bearing sentence with no document and no fetch date does not ship, the
 * divorced-parent answer is the support rule and never the old one, the no-SSN
 * answer stops where the citation stops, and nothing dated or uncountable rides
 * along.
 */
class FafsaProcessChatToolTest {
  private val tool = FafsaProcessChatTool()

  private val student = StudentId(UUID.randomUUID())

  private fun execute(input: JsonObject = buildJsonObject {}): JsonObject = runBlocking { tool.execute(student, input) }

  /** Every rule of the payload, flattened, paired with the topic group it was emitted under. */
  private fun servedRules(): List<Pair<String, JsonObject>> =
    execute()[TOPICS_KEY]!!
      .jsonArray
      .flatMap { group ->
        val topic = group.jsonObject[TOPIC_KEY]!!.jsonPrimitive.content
        group.jsonObject[RULES_KEY]!!.jsonArray.map { topic to it.jsonObject }
      }

  /** Every statement the payload speaks, in emission order. */
  private fun servedStatements(): List<String> = servedRules().map { (_, rule) -> rule[STATEMENT_KEY]!!.jsonPrimitive.content }

  private fun statementsOf(topic: FafsaProcessTopic): List<String> =
    servedRules().filter { (group, _) -> group == topic.value }.map { (_, rule) -> rule[STATEMENT_KEY]!!.jsonPrimitive.content }

  @Test
  fun `every rule carries a document, an https page and a fetch date that is not in the future`() {
    val today = LocalDate.now()
    val rules = servedRules()
    assertEquals(FafsaProcessCopy.RULES.size, rules.size, "every rule of the corpus must reach the wire")

    rules.forEach { (topic, rule) ->
      val statement = assertNotNull(rule[STATEMENT_KEY], "a rule under [$topic] carries no statement").jsonPrimitive.content
      assertTrue(statement.isNotBlank(), "a blank statement under [$topic]")
      val citedAs = assertNotNull(rule[CITED_AS_KEY], "no document cited for [$statement]").jsonPrimitive.content
      assertTrue(citedAs.isNotBlank(), "a blank citation for [$statement]")
      val url = assertNotNull(rule[URL_KEY], "no page cited for [$statement]").jsonPrimitive.content
      assertTrue(url.startsWith("https://"), "a rule must cite an https page, got [$url] for [$statement]")
      // The date must PARSE -- a wire string the model reads as a date -- and
      // must be a day that has happened: we cannot have read a page tomorrow.
      val checkedOn = LocalDate.parse(assertNotNull(rule[CHECKED_ON_KEY], "no fetch date for [$statement]").jsonPrimitive.content)
      assertFalse(checkedOn.isAfter(today), "[$statement] claims it was read on [$checkedOn], which is in the future")
    }
  }

  @Test
  fun `all six topics are served, non-empty, and every rule sits under its own topic`() {
    val groups = execute()[TOPICS_KEY]!!.jsonArray
    assertEquals(
      FafsaProcessTopic.entries.map { it.value },
      groups.map { it.jsonObject[TOPIC_KEY]!!.jsonPrimitive.content },
      "the payload emits every topic, in the copy's own order",
    )
    groups.forEach { group ->
      val topic = group.jsonObject[TOPIC_KEY]!!.jsonPrimitive.content
      assertTrue(group.jsonObject[RULES_KEY]!!.jsonArray.isNotEmpty(), "topic [$topic] must carry at least one rule")
    }

    // The group a rule is PRINTED under is the topic the rule itself declares:
    // a mis-filed rule would otherwise be answered under the wrong question.
    val byStatement = FafsaProcessCopy.RULES.associate { it.statement to it.topic.value }
    servedRules().forEach { (group, rule) ->
      val statement = rule[STATEMENT_KEY]!!.jsonPrimitive.content
      assertEquals(byStatement[statement], group, "[$statement] was emitted under the wrong topic")
    }
  }

  @Test
  fun `the divorced-parent answer is the more-than-half-support rule, never the custodial parent`() {
    val statements = statementsOf(FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS)

    assertTrue(
      statements.any { it.contains("more than half") },
      "the support rule is the answer, and it must be said in those words: $statements",
    )
    // The legacy web is saturated with the old heuristic, so the corpus names
    // it AS the old one rather than leaving the family to notice the change.
    assertTrue(
      statements.any { it.contains("lived with") && (it.contains("no longer correct") || it.contains("old rule")) },
      "the 'parent you lived with' rule must be named as the one that is no longer correct: $statements",
    )
    assertFalse(
      servedStatements().any { it.contains("custodial parent") },
      "no rule anywhere may state the custodial parent as the answer",
    )
  }

  @Test
  fun `the stepparent consequence rides with the divorced-parent rules`() {
    assertTrue(
      statementsOf(FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS).any {
        it.contains("stepparent") && it.contains("contributor")
      },
      "a remarried parent brings a second contributor, and the family is told so in the same topic",
    )
  }

  @Test
  fun `the no-SSN topic gives the account rule and stops where the citation stops`() {
    val statements = statementsOf(FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN)

    assertTrue(
      statements.any { it.contains("no Social Security number") && it.contains("create a StudentAid.gov account") },
      "the cited answer is that an account CAN be created: $statements",
    )
    assertTrue(
      statements.any { it.contains("financial aid office") },
      "where the process is genuinely uncertain the family is sent to a named office: $statements",
    )
    // The redesign of this path was in progress and dated when it was
    // researched, so none of it is coach copy: a dated status is not a rule.
    val payload = execute().toString()
    listOf("ITIN-based", "2026-27 cycle", "as of March 2025", "redesign", "in progress").forEach {
      assertFalse(payload.contains(it), "dated redesign-status wording reached the payload: [$it]")
    }
  }

  @Test
  fun `no dated rollout statistic is anywhere in the payload`() {
    val payload = execute().toString()
    listOf("three-quarters", "74", "unanswered", "GAO").forEach {
      assertFalse(payload.contains(it), "a dated measurement of one rollout is not a standing rule: [$it]")
    }
  }

  @Test
  fun `the free-form rule cites its own subsection and the disclosure duty cites the one below it`() {
    val statements = statementsOf(FafsaProcessTopic.THE_FORM_IS_FREE)
    val rules = servedRules().filter { (group, _) -> group == FafsaProcessTopic.THE_FORM_IS_FREE.value }

    val freeForm = rules.single { (_, rule) -> rule[STATEMENT_KEY]!!.jsonPrimitive.content.contains("may charge a family") }
    val freeFormCitation = freeForm.second[CITED_AS_KEY]!!.jsonPrimitive.content
    assertTrue(
      freeFormCitation.contains("1090(d)(3)"),
      "the FAFSA-is-free rule is § 1090(d)(3): got [$freeFormCitation]",
    )

    val preparer = rules.single { (_, rule) -> rule[STATEMENT_KEY]!!.jsonPrimitive.content.contains("paid to prepare") }
    val preparerCitation = preparer.second[CITED_AS_KEY]!!.jsonPrimitive.content
    assertTrue(preparerCitation.contains("1090(d)(2)"), "the preparer conditions are § 1090(d)(2): got [$preparerCitation]")
    assertTrue(
      preparerCitation.contains("1090(d)(2)(C)"),
      "the duty to TELL a family the form is free is its own subsection, § 1090(d)(2)(C): got [$preparerCitation]",
    )
    assertTrue(
      statements.any { it.contains("must tell the family") && it.contains("free") },
      "and the duty is stated, not only cited: $statements",
    )
  }

  @Test
  fun `the definition advertises a closed no-argument schema and a key legend`() {
    assertEquals(FafsaProcessChatTool.TOOL_NAME, tool.name)
    val definition = tool.definition
    assertEquals(FafsaProcessChatTool.TOOL_NAME, definition["name"]!!.jsonPrimitive.content)
    val schema = definition["input_schema"]!!.jsonObject
    assertEquals(0, schema["properties"]!!.jsonObject.size, "the tool takes no argument at all")
    assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.content.toBoolean(), "and refuses any other key")

    // RFC 199: the description is a LEGEND -- one entry per wire key, read from
    // the very consts the payload is keyed by, so a renamed key breaks the
    // legend that explains it instead of leaving the model reading a dead name.
    val description = definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("A legend of the payload's keys follows"), "the legend announces itself")
    listOf(TOPICS_KEY, TOPIC_KEY, RULES_KEY, STATEMENT_KEY, CITED_AS_KEY, URL_KEY, CHECKED_ON_KEY, COVERAGE_NOTE_KEY, SOURCE_NOTE_KEY)
      .forEach { assertTrue(description.contains(it), "wire key [$it] has no legend entry") }
    FafsaProcessTopic.entries.forEach {
      assertTrue(description.contains("\"${it.value}\""), "the legend must quote the topic code [${it.value}] the wire carries")
    }
  }

  @Test
  fun `a call with any input key is a structured error, not a narrower read`() {
    val result = execute(buildJsonObject { put("topic", "dependency") })
    assertTrue(result["error"]!!.jsonPrimitive.content.contains("topic"), "got $result")
    assertTrue(result[TOPICS_KEY] == null, "an error is not also a payload")
  }

  @Test
  fun `the unscoped ChatTool execute is a structured error`() =
    runBlocking {
      val result = (tool as ed.unicoach.chat.ChatTool).execute(buildJsonObject {})
      assertTrue(result["error"]!!.jsonPrimitive.content.contains("student-scoped"), "got $result")
    }

  @Test
  fun `no bare source code reaches the payload`() {
    // Every value here is prose or a citation, so NOTHING in this payload is a
    // number by contract: the allowlist is empty on purpose, and a numeric
    // field appearing later must be argued for rather than inherited.
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    val violations = BareSourceCodeGuard.listViolations(execute(), emptySet())
    assertEquals(
      emptyList(),
      violations.map(BareSourceCodeGuard::mapMessage),
      "a bare source code reached the FAFSA rulebook",
    )
    val payload = execute().toString()
    assertFalse(BareSourceCodeGuard.CODE_EQUALS_WORD.containsMatchIn(payload), "the payload transcribes a source codebook")
    // The FAFSA is Common Data Set H.801; a family is told the form's name.
    assertFalse(payload.contains("H.801"), "a CDS field id must never reach a family")
    assertFalse(Regex("""H\.\d""").containsMatchIn(payload), "nor any other CDS field id")
  }

  @Test
  fun `the payload promises no coverage number`() {
    val payload = execute().toString()
    // 219 of 366 was measured from the committed seed and is not observable at
    // runtime: a hardcoded count is a promise that rots the next time the seed
    // moves. The coverage note says the shape of the gap in words instead.
    assertFalse(payload.contains("219"), "a coverage count reached the payload")
    assertFalse(payload.contains("366"), "a coverage denominator reached the payload")
    val coverageNote = execute()[COVERAGE_NOTE_KEY]!!.jsonPrimitive.content
    assertTrue(coverageNote.contains("some colleges and not for all"), "the gap is stated in words: [$coverageNote]")
    assertTrue(coverageNote.contains("financial aid office"), "and the family is sent somewhere that can answer it")
    assertEquals(FafsaProcessChatTool.COVERAGE_NOTE, coverageNote, "the note the model reads is the const, never a retyped copy")
    assertEquals(FafsaProcessChatTool.SOURCE_NOTE, execute()[SOURCE_NOTE_KEY]!!.jsonPrimitive.content)
  }
}
