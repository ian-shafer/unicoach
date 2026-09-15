package ed.unicoach.coaching

import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.IntendedSubjectName
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `update_intended_subjects` chat tool (RFC 205): the coach's write path
 * into what the student wants to study, when they say it mid-conversation.
 *
 * Total by the [ed.unicoach.chat.ChatTool] contract — malformed input, an
 * unresolvable term, or a fourth subject returns a structured `{ "error": ... }`
 * object the model reads, never a throw.
 *
 * **It can look a subject up and it can record one, and it cannot INVENT one.**
 * A term is recorded only when it resolves UNAMBIGUOUSLY against the published
 * taxonomy; anything else stores NOTHING and comes back with the candidates and
 * a reason, so the coach asks the student which they meant rather than guessing
 * on their behalf. No synonym is stored and none is invented.
 *
 * "I don't know yet" and "I'd rather not say" are both first-class answers here.
 * They are different sentences and the tool records them as different states.
 *
 * It ships NO byte ceiling on its description. The ceilings were all deleted by
 * RFC 203 and the live tests carry "do not add a ceiling back" comments.
 *
 * A thin input adapter by design: [execute] only orchestrates parse -> resolve
 * -> write -> render. The cap, the D17 empty-set rule and the validation live in
 * [IntendedSubjectsService], the single owner shared with REST.
 *
 * **No slug reaches the student.** The result names subjects in words only; the
 * slug is identity the model never has to speak.
 */
class IntendedSubjectsChatTool(
  private val service: IntendedSubjectsService,
  private val vocabulary: SubjectVocabularyService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  private val inputProperties: JsonObject =
    buildJsonObject {
      putJsonObject(SUBJECTS_FIELD) {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put(
          "description",
          "The subject or subjects the student just said they want to study, in their own words " +
            "(for example \"nursing\", \"marine biology\"). At most " +
            "${IntendedSubjectsDao.MAX_SUBJECTS}, and this REPLACES whatever was stored before, so " +
            "send the whole answer and not just the new part. Each word must match one subject on " +
            "the published list; if it matches none or more than one, nothing is stored and the " +
            "result gives you the choices to put back to the student. An empty array records that " +
            "the student no longer knows.",
        )
      }
      putJsonObject(UNDECIDED_FIELD) {
        put("type", "boolean")
        put("const", true)
        put(
          "description",
          "Literal true when the student said they do not know yet. That is a real answer and it is " +
            "recorded as one; it is not the same as declining, and it is not silence.",
        )
      }
      putJsonObject(DECLINED_FIELD) {
        put("type", "boolean")
        put("const", true)
        put(
          "description",
          "Literal true when the student would rather not say what they want to study.",
        )
      }
      putJsonObject(LOOK_UP_FIELD) {
        put("type", "string")
        put(
          "description",
          "Look the published list up WITHOUT storing anything - use it when you are not sure a word " +
            "is on the list, or to offer the student the nearby choices. Returns matching subject " +
            "names only.",
        )
      }
    }

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        put("properties", inputProperties)
        putJsonArray("required") {}
        // The published boundary is the closed set the parser enforces.
        put("additionalProperties", false)
      }
    }

  /** Read off the very object the schema publishes, never listed a second time. */
  private val knownFields: Set<String> = inputProperties.keys

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val update =
      when (val parsed = parseCall(input)) {
        is ParsedCall.Invalid -> {
          return errorObject(parsed.reason)
        }

        is ParsedCall.LookUp -> {
          return lookUpResult(parsed.term)
        }

        is ParsedCall.Stated -> {
          parsed.update
        }

        is ParsedCall.Named -> {
          when (val resolved = resolve(parsed.terms)) {
            is Resolution.Ok -> IntendedSubjectsUpdate.Subjects(resolved.slugs)

            // Rendered HERE, where the model is answered, and nowhere else.
            is Resolution.Unresolved -> return unresolvedObject(resolved.terms)

            is Resolution.Failed -> return errorObject(resolved.reason)
          }
        }
      }

    // Total by the [ed.unicoach.chat.ChatTool] contract, the
    // [MoneyProfileChatTool] shape: a failed write is a REASON the coach can say
    // out loud, never a throw that breaks the student's turn.
    val outcome =
      service
        .replace(studentId, update)
        .getOrElse { e ->
          logger.warn("tool [{}] intended-subjects write failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject(WRITE_FAILED_REASON)
        }

    return when (outcome) {
      is UpdateIntendedSubjectsResult.Success -> answerObject(outcome.answer)
      is UpdateIntendedSubjectsResult.Rejected -> errorObject(refusalReason(outcome.reasons))
    }
  }

  /** What one call asks for, or the reason it is malformed — the money tool's [MoneyProfileChatTool] parse shape. */
  private sealed interface ParsedCall {
    /** Read the published list and store NOTHING. */
    data class LookUp(
      val term: String,
    ) : ParsedCall

    /** Record subjects, in the student's own words — still to be resolved against the taxonomy. */
    data class Named(
      val terms: List<String>,
    ) : ParsedCall

    /** Record an answer that names no subject; nothing more is needed to write it. */
    data class Stated(
      val update: IntendedSubjectsUpdate,
    ) : ParsedCall

    data class Invalid(
      val reason: String,
    ) : ParsedCall
  }

  /**
   * Maps the wire shape onto what this call asks for; every malformation is a
   * [ParsedCall.Invalid]. It reads nothing and writes nothing, so [execute] is
   * the orchestrator its doc comment claims to be.
   *
   * The "exactly one of the three" rule is NOT decided here: it is
   * [intendedSubjectsChoice], shared with the REST door, so the two cannot
   * disagree about what an answer is. Only the sentences the model reads are
   * this tool's own.
   */
  private fun parseCall(input: JsonObject): ParsedCall {
    unknownFieldsReason(input, knownFields)?.let { return ParsedCall.Invalid(it) }

    val flags =
      when (val read = readFlags(input)) {
        is FlagsRead.Ok -> read.flags
        is FlagsRead.Invalid -> return ParsedCall.Invalid(read.reason)
      }

    val terms =
      when (val read = readTerms(input)) {
        is TermsRead.Ok -> read.terms
        is TermsRead.Invalid -> return ParsedCall.Invalid(read.reason)
      }

    if (flags.lookUp != null) {
      if (terms != null || flags.undecided || flags.declined) {
        return ParsedCall.Invalid("$LOOK_UP_FIELD looks the list up and stores nothing; it cannot be combined with an answer")
      }
      // The same two guards `GET /api/v1/subjects` puts on its `q`, because this
      // is the same read: a BLANK term is a caller defect and not a request for
      // everything (unguarded it becomes `name ILIKE '%%'`, the whole list), and
      // the term is bounded by the vocabulary's own
      // [SubjectVocabularyService.MAX_QUERY_LENGTH] rather than by a number
      // written here a second time. Trimmed first, as the route trims, so " "
      // and "" are one case.
      val term = flags.lookUp.trim()
      if (term.isEmpty()) {
        return ParsedCall.Invalid("$LOOK_UP_FIELD must name something to look for; omit it entirely to record an answer")
      }
      if (term.length > SubjectVocabularyService.MAX_QUERY_LENGTH) {
        return ParsedCall.Invalid(
          "$LOOK_UP_FIELD must be at most ${SubjectVocabularyService.MAX_QUERY_LENGTH} characters " +
            "(got ${term.length}); ask the student for a shorter word",
        )
      }
      return ParsedCall.LookUp(term)
    }

    return when (val choice = intendedSubjectsChoice(terms, declined = flags.declined, undecided = flags.undecided)) {
      is IntendedSubjectsChoice.Subjects -> {
        ParsedCall.Named(choice.subjects)
      }

      is IntendedSubjectsChoice.Stated -> {
        ParsedCall.Stated(choice.update)
      }

      IntendedSubjectsChoice.Several -> {
        ParsedCall.Invalid("only one of $SUBJECTS_FIELD, $UNDECIDED_FIELD, $DECLINED_FIELD may be set in one call")
      }

      IntendedSubjectsChoice.None -> {
        ParsedCall.Invalid(
          "nothing to do: set $SUBJECTS_FIELD, $UNDECIDED_FIELD or $DECLINED_FIELD to record an answer, " +
            "or $LOOK_UP_FIELD to read the list",
        )
      }
    }
  }

  /** The three non-array keys of one call, already read and already refused if malformed. */
  private data class Flags(
    val lookUp: String?,
    val undecided: Boolean,
    val declined: Boolean,
  )

  private sealed interface FlagsRead {
    data class Ok(
      val flags: Flags,
    ) : FlagsRead

    data class Invalid(
      val reason: String,
    ) : FlagsRead
  }

  /**
   * Reads `look_up`, `undecided` and `declined`, so [parseCall] reads the call
   * and then decides what it is, at one altitude.
   *
   * Each mismatch is refused AT ITS OWN READ, with the reason
   * [StudentScopedChatTool] already builds — it names the field, the shape
   * expected and the offending element. So a `null` here can only ever mean the
   * key was ABSENT: `undecided: "true"`, a string where a boolean belongs, is a
   * refusal naming the field and never a call that said nothing.
   *
   * Literal-true, the money tool's convention: a `false` is a malformed call
   * rather than "leave it alone", which is what omitting the key already says.
   * The RULE is [literalTrueViolations], shared with the REST door so the two
   * cannot disagree about a `false`; only the sentence is this tool's own.
   */
  private fun readFlags(input: JsonObject): FlagsRead {
    val lookUp =
      when (val read = getString(input, LOOK_UP_FIELD)) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return FlagsRead.Invalid(read.reason)
      }
    val undecided =
      when (val read = getBoolean(input, UNDECIDED_FIELD)) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return FlagsRead.Invalid(read.reason)
      }
    val declined =
      when (val read = getBoolean(input, DECLINED_FIELD)) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return FlagsRead.Invalid(read.reason)
      }

    literalTrueViolations(UNDECIDED_FIELD to undecided, DECLINED_FIELD to declined)
      .firstOrNull()
      ?.let { return FlagsRead.Invalid("$it must be true when present; omit it otherwise") }

    return FlagsRead.Ok(Flags(lookUp = lookUp, undecided = undecided == true, declined = declined == true))
  }

  /** The `subjects` array read: null when the key is absent, possibly EMPTY when it is present. */
  private sealed interface TermsRead {
    data class Ok(
      val terms: List<String>?,
    ) : TermsRead

    data class Invalid(
      val reason: String,
    ) : TermsRead
  }

  private fun readTerms(input: JsonObject): TermsRead {
    val element = input[SUBJECTS_FIELD] ?: return TermsRead.Ok(null)
    val array = element as? JsonArray ?: return TermsRead.Invalid("$SUBJECTS_FIELD must be an array of strings")
    val terms =
      array.mapIndexed { index, item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
          return TermsRead.Invalid("$SUBJECTS_FIELD entry is not a string: [$item] at index [$index]")
        }
        primitive.content
      }
    return TermsRead.Ok(terms)
  }

  /** One term nobody could pin down, with the choices to put back to the student. */
  private data class UnresolvedTerm(
    val term: String,
    val candidates: List<IntendedSubjectName>,
  )

  /** Either every term resolved to exactly one subject, or the refusal that stores nothing. */
  private sealed interface Resolution {
    data class Ok(
      val slugs: List<String>,
    ) : Resolution

    /**
     * The FACTS of the refusal, not a rendered envelope: the resolver matches,
     * and [execute] — the one site that answers the model — renders.
     */
    data class Unresolved(
      val terms: List<UnresolvedTerm>,
    ) : Resolution

    /** The taxonomy could not be READ, so nothing about the terms is known. */
    data class Failed(
      val reason: String,
    ) : Resolution
  }

  /**
   * Resolves the student's words against the PUBLISHED NAMES, and nothing else.
   *
   * An exact case-insensitive name match wins outright. Otherwise the term must
   * match exactly ONE subject by substring; zero or several store NOTHING and
   * come back with the candidates, because a tool that picks one of several for
   * the student has recorded an answer the student did not give.
   *
   * EVERY term is attempted before anything is refused. The write stays
   * all-or-nothing — one bad word stores nothing — but the REPORT is complete,
   * so a student who names three unknown subjects is asked once and not three
   * times. The same pooling [IntendedSubjectsService.replaceSubjects] and
   * [refusalReason] already do.
   */
  private suspend fun resolve(terms: List<String>): Resolution {
    if (terms.isEmpty()) return Resolution.Ok(emptyList())
    val slugs = mutableListOf<String>()
    val unresolved = mutableListOf<UnresolvedTerm>()
    for (term in terms) {
      val candidates =
        vocabulary
          .list(query = term)
          .getOrElse { e ->
            logger.warn("tool [{}] subject look-up failed for term=[{}]", TOOL_NAME, term, e)
            // A failed READ is not an unresolvable term: nothing is known about
            // any of them, so the pooling stops here rather than reporting the
            // terms already seen as though they had been judged.
            return Resolution.Failed(READ_FAILED_REASON)
          }
      val exact = candidates.filter { it.name.equals(term, ignoreCase = true) }
      val chosen =
        when {
          exact.size == 1 -> exact.single()
          candidates.size == 1 -> candidates.single()
          else -> null
        }
      if (chosen == null) {
        unresolved.add(UnresolvedTerm(term, candidates))
      } else {
        slugs.add(chosen.slug)
      }
    }
    if (unresolved.isNotEmpty()) return Resolution.Unresolved(unresolved)
    return Resolution.Ok(slugs)
  }

  /**
   * The "I could not tell which you meant" result: a reason naming EVERY term
   * that failed, and each one's candidate NAMES — never a guess, and never a
   * slug.
   *
   * It is an [ERROR_KEY] object, so nothing downstream mistakes it for a stored
   * answer, and it carries the candidates per term so the coach can put every
   * choice back to the student in one turn instead of one turn per bad word.
   */
  private suspend fun unresolvedObject(unresolved: List<UnresolvedTerm>): JsonObject {
    // A term matching NOTHING still gets choices: the coach has to be able to
    // put a list back to the student in this turn, and "not on the list" with no
    // list is a dead end. Read ONCE for all such terms, and before the builder —
    // a JSON builder body is not a coroutine, and a suspending read inside one
    // is the compile error that says so.
    val wholeList =
      if (unresolved.any { it.candidates.isEmpty() }) {
        vocabulary.list().getOrElse { e ->
          val terms = unresolved.joinToString(", ") { it.term }
          logger.warn("tool [{}] subject list read failed while offering choices for terms=[{}]", TOOL_NAME, terms, e)
          return errorObject(READ_FAILED_REASON)
        }
      } else {
        emptyList()
      }
    val offered = unresolved.map { it to it.candidates.ifEmpty { wholeList }.take(MAX_CANDIDATES) }
    val reason =
      unresolved.joinToString("; ") { entry ->
        if (entry.candidates.isEmpty()) {
          "[${entry.term}] is not a subject on the list"
        } else {
          "[${entry.term}] matches more than one subject"
        }
      } + "; nothing was stored, so ask the student which of the listed subjects they mean for each, " +
        "and do not store a subject they did not choose"
    return buildJsonObject {
      put(ERROR_KEY, reason)
      putJsonArray(UNRESOLVED_KEY) {
        offered.forEach { (entry, choices) ->
          addJsonObject {
            put(TERM_KEY, entry.term)
            putJsonArray(CANDIDATES_KEY) { choices.forEach { add(JsonPrimitive(it.name)) } }
          }
        }
      }
    }
  }

  /** The look-up result: matching subject NAMES, and an explicit note that nothing was stored. */
  private suspend fun lookUpResult(term: String): JsonObject {
    val matches =
      vocabulary
        .list(query = term)
        .getOrElse { e ->
          logger.warn("tool [{}] subject look-up failed for term=[{}]", TOOL_NAME, term, e)
          return errorObject(READ_FAILED_REASON)
        }
    return buildJsonObject {
      put("stored", false)
      putJsonArray("subjects") { matches.take(MAX_CANDIDATES).forEach { add(JsonPrimitive(it.name)) } }
    }
  }

  /**
   * The post-write echo: the status, the subject NAMES, and one sentence saying
   * what the stored answer now buys — so the coach can say it instead of
   * inventing a benefit.
   */
  private fun answerObject(answer: IntendedSubjectAnswer): JsonObject =
    buildJsonObject {
      putJsonObject("intended_subjects") {
        put("status", answer.status.value)
        putJsonArray("subjects") { answer.subjects.forEach { add(JsonPrimitive(it.name)) } }
        put("buys", mapStatusToBuys(answer.status))
      }
    }

  /**
   * What the stored answer buys, per status.
   *
   * An explicit named branch for each of the four, no `else`: a fifth status
   * must be a compile error here, because this is the sentence a student hears
   * and a default would say something true of a different answer.
   */
  private fun mapStatusToBuys(status: AnswerStatus): String =
    when (status) {
      AnswerStatus.ANSWERED -> {
        "Stored. From now on you can talk about these subjects by name without asking again, and later " +
          "answers about schools and about what study costs can be about what the student actually wants " +
          "to study."
      }

      AnswerStatus.UNDECIDED -> {
        "Stored as not knowing yet, which is a real answer. Do not ask again as though it were unanswered; " +
          "the student can name a subject whenever they want to."
      }

      AnswerStatus.DECLINED -> {
        "Stored as declined. Do not ask again. Everything else works exactly as before."
      }

      // Unreachable through this tool: no caller can write `unanswered`, which
      // is only ever the state of a student nobody has asked. Stated rather than
      // folded into a default, because a default here would tell a student who
      // just answered that we never asked.
      AnswerStatus.UNANSWERED -> {
        "Nothing is recorded yet; the student has not been asked."
      }
    }

  /** Every refusal in one sentence, each naming the input it is about — never just the first. */
  private fun refusalReason(reasons: List<UpdateIntendedSubjectsResult.Reason>): String =
    reasons.joinToString("; ") { reason ->
      when (reason) {
        is UpdateIntendedSubjectsResult.Reason.TooMany -> {
          "at most ${IntendedSubjectsDao.MAX_SUBJECTS} subjects can be stored (got ${reason.submitted}); " +
            "ask the student which they would keep"
        }

        is UpdateIntendedSubjectsResult.Reason.NotASubject -> {
          "[${reason.slug}] is not a subject on the list"
        }

        is UpdateIntendedSubjectsResult.Reason.NoLongerOffered -> {
          "[${reason.slug}] is no longer offered"
        }

        is UpdateIntendedSubjectsResult.Reason.Duplicate -> {
          "[${reason.slug}] was named more than once"
        }
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(IntendedSubjectsChatTool::class.java)

    const val TOOL_NAME = "update_intended_subjects"

    const val SUBJECTS_FIELD = "subjects"
    const val UNDECIDED_FIELD = "undecided"
    const val DECLINED_FIELD = "declined"
    const val LOOK_UP_FIELD = "look_up"

    /**
     * The unresolved result's shape, named where the envelope is written: an
     * array under [UNRESOLVED_KEY], one object per refused term, each carrying
     * the [TERM_KEY] the student said and the [CANDIDATES_KEY] choices for it.
     * Per term, so three bad words are three questions the coach can ask in one
     * turn rather than one list nobody can attribute.
     */
    const val UNRESOLVED_KEY = "unresolved"

    const val TERM_KEY = "term"

    const val CANDIDATES_KEY = "candidates"

    /** How many names one result offers. A list of choices a coach can read aloud, not a catalogue. */
    const val MAX_CANDIDATES = 10

    /**
     * What the coach says when the database, not the student, is the problem.
     *
     * Two sentences rather than one because they are two different facts: a
     * failed WRITE means the answer the student just gave is not stored and must
     * be offered again, while a failed READ means nothing was lost. Neither is a
     * throw: [ed.unicoach.chat.ChatTool.execute] is total, so a transient
     * database fault becomes a reason the model can say out loud instead of a
     * broken turn.
     */
    const val WRITE_FAILED_REASON =
      "could not store that just now, so nothing was saved; tell the student and offer to try again in a moment"

    const val READ_FAILED_REASON =
      "could not read the subject list just now, so nothing was stored or changed; try again in a moment"

    // A schema legend, not a rulebook (RFC 199): what this tool records, the one
    // fact no parameter can state (it never invents a subject), and what the
    // result gives back. Every behavioural rule -- when to offer the question,
    // what it buys, how a decline is honoured -- lives in the coach system
    // prompt, stated once.
    const val DESCRIPTION =
      "Record what the student just said about what they want to STUDY - one to " +
        "${IntendedSubjectsDao.MAX_SUBJECTS} subjects, or that they do not know yet, or that they would " +
        "rather not say - or look the published subject list up without storing anything. The call " +
        "replaces the whole answer, so send everything the student wants stored and not just the new " +
        "part. A word that does not match exactly one subject on the list stores NOTHING and comes back " +
        "with the choices, so ask the student which they mean rather than choosing for them. The result " +
        "gives the stored answer in subject names and one sentence about what it buys."
  }
}
