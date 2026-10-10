package ed.unicoach.coaching

import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The student's stored field of study, as the two search doors need it (RFC 245
 * A2) — the subject slugs when they answered, and an empty set in every other
 * case.
 *
 * [Residency]'s shape, deliberately, because it is the same question asked of a
 * different answer store: one read that never fails, one payload the chat-free
 * `:college` tools take as plain data, and one invitation that is made in
 * exactly one state. What it is NOT is a second copy of the answer: the store is
 * RFC 205's `intended_subject_status` / `intended_subjects` pair, read through
 * [IntendedSubjectsService], and nothing here writes or caches it.
 *
 * Every state that is not [OnFile] resolves to the SAME downstream behaviour —
 * a search that renders exactly as it did before this RFC — but they are not one
 * state, because the model must be told a different thing in each: an
 * invitation, a silence, or a reason.
 */
internal sealed interface IntendedField {
  /**
   * The subjects to label rows against: non-empty ONLY for [OnFile].
   *
   * A SET of slugs and not the whole answer, because `:college` cannot see
   * `:service` and must not learn what an [IntendedSubjectAnswer] is. It takes
   * the words and labels rows with them.
   */
  val storedSubjectSlugs: Set<String>

  /**
   * The student said what they want to study. There is a field, and nothing left
   * to offer.
   *
   * [storedSubjectSlugs] holds the shape the store permits — 1..[IntendedSubjectsDao.MAX_SUBJECTS]
   * — and the guard is here rather than in a comment: the schema's
   * status-iff-subjects trigger, its deferred cap trigger and
   * [IntendedSubjectAnswer] all say so, so any other size is two tables
   * disagreeing, and it would render rows against a field no status in RFC 245's
   * table maps to. BOTH bounds, on `IntendedSubjectWrite.Subjects`' precedent:
   * `isNotEmpty()` is the lower bound wearing another word, and a surplus shape
   * is as wrong as a missing one. An `init` guard rather than a
   * distinguished-first-element constructor, on the same precedent: these slugs
   * are a SET, and singling one out would impose an order the answer does not
   * have.
   *
   * It is a PROGRAMMING-ERROR net and nothing else. The read path below refuses
   * a disagreeing row itself, with the student named, and degrades — so this
   * `require` can only be reached by a SECOND construction site, which is the
   * one thing a type-level guard can catch that a read-path check cannot.
   */
  data class OnFile(
    override val storedSubjectSlugs: Set<String>,
  ) : IntendedField {
    init {
      require(storedSubjectSlugs.size in 1..IntendedSubjectsDao.MAX_SUBJECTS) {
        "a stored field holds 1..${IntendedSubjectsDao.MAX_SUBJECTS} subject slugs; got " +
          "${storedSubjectSlugs.size}: [${storedSubjectSlugs.sorted().joinToString(", ")}] -- " +
          "two tables disagreeing, not a student to label rows for"
      }
    }
  }

  /** Nobody has asked yet: `intended_subject_status` is still unanswered. The one state that invites. */
  data object NotAsked : IntendedField {
    override val storedSubjectSlugs: Set<String> get() = emptySet()
  }

  /**
   * We hold no field to label with and must not ask again. The CAUSE travels
   * with it: two of the three are answers the student gave, and the third is a
   * fault of ours that must never be reported as one of them.
   */
  data class Unavailable(
    val cause: Cause,
  ) : IntendedField {
    override val storedSubjectSlugs: Set<String> get() = emptySet()
  }

  /** WHY there is no field to label with. */
  enum class Cause {
    /** The student said they would rather not say. A closed topic, permanently (RFC 169's rule). */
    DECLINED,

    /**
     * The student said "I do not know yet" — a real answer at sixteen (RFC 205),
     * recorded as one. It is NOT corruption here, unlike the same value on
     * `money_profiles`, and it is NOT silence: asking again is exactly what the
     * coach prompt forbids.
     */
    UNDECIDED,

    /**
     * The intended-subjects read FAULTED. We do not know what the student said,
     * which is not the same as knowing they have said nothing.
     */
    READ_FAILED,

    /**
     * The read SUCCEEDED and the rows disagree: an `ANSWERED` status holding a
     * subject set outside 1..[IntendedSubjectsDao.MAX_SUBJECTS]. Both of this
     * table's invariants are deferred constraint triggers, so a hand-edited row
     * or a second concurrent writer can land one — and [Residency.Cause.CORRUPT_PROFILE]
     * is this answer's sibling, named for the same reason: it is CORRUPTION, so
     * it is logged as such and never allowed to fail a search.
     */
    CORRUPT_ANSWER,
  }

  companion object {
    /**
     * Reads the stored field for [studentId]. Never fails: every fault is the
     * caller's own no-field behaviour, which is the search that shipped before
     * this RFC.
     *
     * [caller] is the tool that will run unlabelled, for the reason
     * [Residency.forStudent]'s is: two search doors share this helper and an
     * operator chasing "the coach stopped saying my subject" must be able to
     * tell which one degraded.
     *
     * [readAnswer] is a SEAM, not a framework — the same function-typed
     * parameter [ResidencyOffer]'s read uses, and for the same reason: the
     * failure branch is the one a Postgres-backed test cannot ask for on demand,
     * and it is the branch that decides whether a database fault turns a working
     * search into an error.
     */
    suspend fun forStudent(
      studentId: StudentId,
      caller: String,
      readAnswer: suspend (StudentId) -> Result<IntendedSubjectAnswer>,
    ): IntendedField =
      readAnswer(studentId).fold(
        onSuccess = { answer -> forAnswer(answer, caller) },
        onFailure = { error -> afterFailedRead(studentId, caller, error) },
      )

    /**
     * An intended-subjects read that FAULTED — one altitude, like the arm beside
     * it, and the same step [Residency.afterFailedRead] names.
     *
     * We do not know what the student said, which is not the same as knowing
     * they have said nothing, so the cause says so and the note on the page says
     * so too.
     */
    private fun afterFailedRead(
      studentId: StudentId,
      caller: String,
      error: Throwable,
    ): IntendedField {
      logDegradedRead(studentId, caller, READ_NAME, error)
      return Unavailable(Cause.READ_FAILED)
    }

    /**
     * The field an answer states — FOUR named arms over [AnswerStatus] and no
     * `else`, the precedent every other consumer of this answer follows
     * (`CollegeCostService.subjectsSpokenFor`).
     *
     * Three of them degrade identically, and they are still named one by one: a
     * fifth status must stop this build rather than fall into whichever default
     * happened to be written, and the three are three different things to tell
     * the model.
     */
    private fun forAnswer(
      answer: IntendedSubjectAnswer,
      caller: String,
    ): IntendedField =
      when (answer.status) {
        // The whole point of this RFC: the label has something to say.
        AnswerStatus.ANSWERED -> {
          onAnswered(answer, caller)
        }

        // Never asked. The one state the invitation is made in.
        AnswerStatus.UNANSWERED -> {
          NotAsked
        }

        // A remembered decline is an ANSWER, not a silence: the offer is keyed
        // on the status and never on the subject list being empty, which is
        // what makes a decline permanent.
        AnswerStatus.DECLINED -> {
          Unavailable(Cause.DECLINED)
        }

        // "I do not know yet" is the status residency does not have, and the
        // reason it is named here is that it must fall into NEITHER default: it
        // does not invite, and there is no subject to label against.
        AnswerStatus.UNDECIDED -> {
          Unavailable(Cause.UNDECIDED)
        }
      }

    /**
     * An ANSWERED row, which must carry 1..[IntendedSubjectsDao.MAX_SUBJECTS]
     * subjects — [Residency.onAnswered]'s shape, for the same reason.
     *
     * `Result.fold` does not catch what its success arm throws, so building
     * [OnFile] straight out of a row would let two disagreeing tables turn a
     * SIDE read into a failed search — the one outcome this whole offer design
     * exists to prevent. The disagreement is refused HERE instead, where the
     * student and the row are both in hand: logged as the corruption it is, and
     * degraded exactly as its faulting sibling is. [OnFile]'s own guard then
     * stands behind this as the net for a second construction site.
     */
    private fun onAnswered(
      answer: IntendedSubjectAnswer,
      caller: String,
    ): IntendedField {
      val slugs = answer.subjects.map { it.slug }.toSet()
      if (slugs.size !in 1..IntendedSubjectsDao.MAX_SUBJECTS) {
        logger.error(
          "intended-subject rows say answered but hold {} subject(s): caller=[{}] student=[{}] " +
            "status=[{}] subjects=[{}] -- the caller runs unlabelled; the rows violate their own " +
            "status-iff-subjects and cap triggers",
          slugs.size,
          caller,
          answer.studentId.asString,
          answer.status.value,
          slugs.sorted().joinToString(", "),
        )
        return Unavailable(Cause.CORRUPT_ANSWER)
      }
      return OnFile(slugs)
    }

    /** WHICH answer a degraded-read log line is about. */
    private const val READ_NAME = "intended-subjects"

    private val logger = LoggerFactory.getLogger(IntendedField::class.java)
  }
}

/**
 * The invitation to say what the student wants to study, [ResidencyOffer]'s
 * shape applied to the field of study (RFC 245 A2).
 *
 * The copy says what the ANSWER WOULD CHANGE and then asks. It never gates: the
 * page it rides carries every school the filters matched, in the order the
 * ranking chose, and this key is a sibling of that page rather than a condition
 * on it.
 *
 * It also does NOT restate what the label means — that sentence is in the tool
 * DESCRIPTION the model already read, in the one wording both search doors use,
 * and a second account beside it in different words is the drift
 * `ResidencyOffer`'s own doc exists to prevent.
 *
 * The key is OMITTED entirely when there is nothing to offer, so its presence
 * stays meaningful.
 */
internal object IntendedSubjectOffer {
  /** The wire key on a search result. */
  const val KEY = "intended_subject_offer"

  /**
   * The `update_intended_subjects` parameter this offer would fill, read from
   * the tool that accepts it rather than re-typed: a second copy here is a
   * second thing to rename when it moves, and the binding between the two is
   * asserted.
   */
  val FIELD: String = IntendedSubjectsChatTool.SUBJECTS_FIELD

  const val OFFER =
    "If the student says what they want to study (record it with ${IntendedSubjectsChatTool.TOOL_NAME}), " +
      "every school a search returns will say whether its programme census records that subject, and name " +
      "which of their subjects it was. It changes nothing about which schools come back or in what order. " +
      "Offer it; never require it, and never hold back a result waiting for it."

  /**
   * The wire key carrying WHY there is no label, when the reason is not "nobody
   * asked".
   */
  const val UNAVAILABLE_KEY = "intended_subject_unavailable"

  /** The sentence for each cause worth saying out loud; null when there is nothing to add. */
  fun noteFor(cause: IntendedField.Cause): String? =
    when (cause) {
      // A decline is a decision the student made and a closed topic. Repeating
      // it back on every result would be re-raising it in all but name.
      IntendedField.Cause.DECLINED -> {
        null
      }

      // "I do not know yet" is the same: an answer that was given. Saying it
      // back on every page is how a coach ends up asking again in all but name.
      IntendedField.Cause.UNDECIDED -> {
        null
      }

      IntendedField.Cause.READ_FAILED -> {
        "This student's stored subjects could not be read on this request, so no school here is labelled " +
          "for their field. Do not tell them we hold none -- we do not know whether we do."
      }

      // The rows disagree with each other, which is OURS and not theirs. Said
      // in `ResidencyOffer`'s corrupt-profile words: never "you have none", and
      // the repair is theirs to offer rather than ours to assume.
      IntendedField.Cause.CORRUPT_ANSWER -> {
        "This student's stored subjects could not be used, so no school here is labelled for their field. " +
          "Do not tell them we hold none; if they want their field labelled, ask them to say what they " +
          "want to study again."
      }
    }
}

/**
 * Writes whatever a stored field has to say on a result: the invitation when
 * nobody has asked, the reason when we hold nothing and the reason is worth
 * saying, and nothing at all otherwise.
 *
 * A top-level emitter on [putOffer]'s precedent, named for what it PUTS.
 */
internal fun JsonObjectBuilder.putIntendedSubjectOffer(field: IntendedField) {
  when (field) {
    // Nobody has asked. The one state the invitation is made in.
    IntendedField.NotAsked -> {
      putJsonObject(IntendedSubjectOffer.KEY) {
        put("field", IntendedSubjectOffer.FIELD)
        put("offer", IntendedSubjectOffer.OFFER)
      }
    }

    // We hold nothing, and the cause decides whether there is anything worth
    // saying about that.
    is IntendedField.Unavailable -> {
      IntendedSubjectOffer.noteFor(field.cause)?.let { put(IntendedSubjectOffer.UNAVAILABLE_KEY, it) }
    }

    // They told us. There is nothing left to invite and nothing to report; the
    // rows carry the answer.
    is IntendedField.OnFile -> {
      Unit
    }
  }
}
