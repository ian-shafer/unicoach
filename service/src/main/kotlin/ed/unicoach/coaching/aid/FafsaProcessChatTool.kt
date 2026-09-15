package ed.unicoach.coaching.aid

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaProcessTopic
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaRule
import ed.unicoach.coaching.costs.AidPolicyWire
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The `fafsa_process` chat tool (RFC 216): the coach's cited answers to the
 * FAFSA questions that are not about dates -- who files, the contributor and
 * consent model, divorced or separated parents, a contributor with no Social
 * Security number, dependency, and what happens after the form is submitted.
 *
 * A sibling of [FederalAidPolicyChatTool], not an extension of it. That tool
 * resolves federal NUMBERS from a store and returns them on every Pell and loan
 * answer; this one returns a curated prose corpus, so hanging the corpus off it
 * would put the whole rulebook on every money answer and mix two different kinds
 * of fact under one name.
 *
 * It takes NO input, for the reason that tool takes none plus one of its own: a
 * topic argument would let the model fetch `dependency` and then answer a
 * divorced-parent question from memory. One call returns everything the coach
 * may say, so a half-answer is not reachable.
 *
 * Read -> render and nothing else: every rule and every citation lives in
 * [FafsaProcessCopy], and every behavioural rule -- what to SAY to a family --
 * lives in the coach system prompt.
 */
class FafsaProcessChatTool : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    if (input.isNotEmpty()) {
      return errorObject(unknownFieldsReason(input, emptySet())!!)
    }
    return rulebook()
  }

  /** The whole rulebook, grouped by topic in the copy's own order. */
  private fun rulebook(): JsonObject =
    buildJsonObject {
      putJsonArray(TOPICS_KEY) {
        FafsaProcessTopic.entries.forEach { topic ->
          add(
            buildJsonObject {
              put(TOPIC_KEY, topic.value)
              putJsonArray(RULES_KEY) { FafsaProcessCopy.rulesOf(topic).forEach { add(ruleObject(it)) } }
            },
          )
        }
      }
      put(COVERAGE_NOTE_KEY, COVERAGE_NOTE)
      put(SOURCE_NOTE_KEY, SOURCE_NOTE)
    }

  /** One rule: the sentence, the document it came from, that document's own page, and the day we read it. */
  private fun ruleObject(rule: FafsaRule): JsonObject =
    buildJsonObject {
      put(STATEMENT_KEY, rule.statement)
      put(CITED_AS_KEY, rule.citedAs)
      put(URL_KEY, rule.url)
      put(CHECKED_ON_KEY, rule.checkedOn.toString())
    }

  companion object {
    const val TOOL_NAME = "fafsa_process"

    // Wire keys as consts because the DESCRIPTION legend quotes them: the
    // legend the model reads and the payload it receives cannot drift apart.
    const val TOPICS_KEY = "topics"
    const val TOPIC_KEY = "topic"
    const val RULES_KEY = "rules"
    const val STATEMENT_KEY = "statement"
    const val CITED_AS_KEY = "cited_as"
    const val URL_KEY = "url"
    const val CHECKED_ON_KEY = "checked_on"
    const val COVERAGE_NOTE_KEY = "coverage_note"
    const val SOURCE_NOTE_KEY = "source_note"

    /**
     * What this corpus does NOT answer: which forms one named college requires.
     * No figure rides this sentence on purpose -- coverage is per college and
     * moves with the seed, so a hardcoded count would be a promise that rots.
     */
    val COVERAGE_NOTE =
      "These rules are the federal process and are the same everywhere. Which aid forms a named college " +
        "requires is a per-college fact: read ${AidPolicyWire.FORMS_KEY} in the ${AidPolicyWire.KEY} section " +
        "of ${CollegeCostChatTool.TOOL_NAME}, which holds a filing for some colleges and not for all, and " +
        "send the family to that college's financial aid office for anything it does not answer."

    /** Why every sentence here rides with a document and a date. */
    val SOURCE_NOTE =
      "Every rule was read from the publisher's own document on the $CHECKED_ON_KEY date beside it; say the " +
        "rule with its $CITED_AS_KEY, and never state a FAFSA process rule this tool does not carry."

    /**
     * The payload legend the model is told (RFC 199): one entry per wire key,
     * what it means and what values it takes, built from the very consts the
     * payload is keyed by. Every behavioural rule lives in the coach system
     * prompt; an entry here says only how to READ a key.
     *
     * Not `const`: the topic codes are rendered from the enum the payload is
     * written from, so the legend can never quote a topic the wire no longer
     * carries.
     */
    val DESCRIPTION =
      "The FAFSA process rules the coach may state, each with the document it came from: who files and who " +
        "is a contributor, divorced or separated parents, a contributor without a Social Security number, " +
        "dependency, what happens after the form is submitted, and the rule that the form is free. It takes " +
        "no input -- one call returns the whole rulebook. A legend of the payload's keys follows.\n" +
        "$TOPICS_KEY - the groups, each carrying a $TOPIC_KEY code and its $RULES_KEY: " +
        FafsaProcessTopic.entries.joinToString(" | ") { "\"${it.value}\"" } + ".\n" +
        "$STATEMENT_KEY - one rule, in a sentence a family can read.\n" +
        "$CITED_AS_KEY / $URL_KEY - the publisher and document that rule was read from, and that publisher's " +
        "own page.\n" +
        "$CHECKED_ON_KEY - the day WE read that document, which is a different date from the document's own " +
        "edition year.\n" +
        "$COVERAGE_NOTE_KEY - what this corpus does not answer, and where the per-college answer lives.\n" +
        "$SOURCE_NOTE_KEY - why every sentence here rides with a document and a date.\n"
  }
}
