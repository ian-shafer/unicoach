package ed.unicoach.coaching

import ed.unicoach.db.Database
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.IntendedSubjectWrite
import ed.unicoach.db.models.StudentId

/**
 * What the caller asked us to store (RFC 205 D17). One operation, four shapes,
 * and NO clear — the student cannot put the question back to "never asked".
 */
sealed interface IntendedSubjectsUpdate {
  /** "Nursing, and maybe biology." Zero to three slugs; ZERO is the D17 case below. */
  data class Subjects(
    val slugs: List<String>,
  ) : IntendedSubjectsUpdate

  /** "I'd rather not say." */
  data object Declined : IntendedSubjectsUpdate

  /** "I don't know yet." */
  data object Undecided : IntendedSubjectsUpdate
}

/**
 * WHICH of the three answers one caller named — the shared reading of "exactly
 * one of subjects / declined / undecided" (RFC 205 D17).
 *
 * [Subjects] carries the words as the door received them, because the two doors
 * do not receive the same thing: REST is given slugs, the chat tool is given the
 * student's own terms and must resolve them first. Everything that needs no
 * resolving arrives as [Stated], already an [IntendedSubjectsUpdate].
 */
sealed interface IntendedSubjectsChoice<out T> {
  /** Subjects were named. [subjects] is whatever the door reads; it still has to become slugs. */
  data class Subjects<T>(
    val subjects: T,
  ) : IntendedSubjectsChoice<T>

  /** "I'd rather not say" or "I don't know yet": the update needs nothing further from the door. */
  data class Stated(
    val update: IntendedSubjectsUpdate,
  ) : IntendedSubjectsChoice<Nothing>

  /** No key was set. This write replaces the WHOLE answer, so an empty call is not a no-op. */
  data object None : IntendedSubjectsChoice<Nothing>

  /** More than one key was set, and they are three different sentences. */
  data object Several : IntendedSubjectsChoice<Nothing>
}

/**
 * Reads one call's three keys as one answer, for EVERY door.
 *
 * Both doors — `PUT /api/v1/students/me/intended-subjects` and the
 * `update_intended_subjects` chat tool — have to agree about what an answer IS,
 * and two hand-rolled copies of the count make that agreement a coincidence.
 * The DECISION is shared here; the RENDERING is not, because a route owes the
 * client a pooled 400 and a tool owes the model a sentence. So [None] and
 * [Several] come back as facts, not as messages.
 */
fun <T : Any> intendedSubjectsChoice(
  subjects: T?,
  declined: Boolean,
  undecided: Boolean,
): IntendedSubjectsChoice<T> {
  val selections = listOf(subjects != null, declined, undecided).count { it }
  if (selections > 1) return IntendedSubjectsChoice.Several
  if (selections == 0) return IntendedSubjectsChoice.None
  // One named arm per answer, no catch-all over the three: `undecided` is a
  // fourth answer STATE and not the remainder of the other two, so a fifth state
  // added later must be written here rather than silently inheriting this arm.
  // The `else` is only the arithmetically unreachable tail of a count already
  // known to be exactly 1.
  return when {
    subjects != null -> IntendedSubjectsChoice.Subjects(subjects)
    declined -> IntendedSubjectsChoice.Stated(IntendedSubjectsUpdate.Declined)
    undecided -> IntendedSubjectsChoice.Stated(IntendedSubjectsUpdate.Undecided)
    else -> IntendedSubjectsChoice.None
  }
}

/** The outcome of a write: the stored answer, or the reasons it was refused. */
sealed interface UpdateIntendedSubjectsResult {
  data class Success(
    val answer: IntendedSubjectAnswer,
  ) : UpdateIntendedSubjectsResult

  /**
   * The input is not storable, with a REASON per offending value — never a bare
   * "invalid". Every reason names the input it is about, so a caller can render
   * "biolgy is not a subject on the list" rather than "bad request".
   */
  data class Rejected(
    val reasons: List<Reason>,
  ) : UpdateIntendedSubjectsResult

  /** One refusal, about one input. */
  sealed interface Reason {
    /** More than [IntendedSubjectsDao.MAX_SUBJECTS] slugs were sent. */
    data class TooMany(
      val submitted: Int,
    ) : Reason

    /** A slug no `subjects` row carries at all. */
    data class NotASubject(
      val slug: String,
    ) : Reason

    /**
     * A DEPRECATED slug the student does not already hold. A deprecated subject
     * is offered to nobody, so choosing one NEW is refused — but a student who
     * already holds it keeps it, which is the whole point of deprecating rather
     * than deleting (D40).
     */
    data class NoLongerOffered(
      val slug: String,
    ) : Reason

    /** The same slug twice. Refused rather than silently de-duplicated: the student should see what they sent. */
    data class Duplicate(
      val slug: String,
    ) : Reason
  }
}

/**
 * Domain layer over [IntendedSubjectsDao] (RFC 205), the
 * [ed.unicoach.coaching.moneyprofile.MoneyProfileService] shape: one
 * [Database]-backed class, `Result<sealed outcome>` per operation, no HTTP or
 * Ktor import anywhere in it.
 *
 * Both writers — the REST `PUT` and the chat tool — reduce to [replace], so the
 * validation and the D17 empty-set rule have exactly one implementation.
 */
class IntendedSubjectsService(
  private val database: Database,
  private val vocabulary: SubjectVocabularyService,
) {
  /**
   * A student's whole answer. Total: a student nobody has asked reads as
   * [AnswerStatus.UNANSWERED] with no subjects, which is an ANSWER about them
   * and not a missing row, so there is no NotFound case and no 404.
   */
  suspend fun getForStudent(studentId: StudentId): Result<IntendedSubjectAnswer> =
    try {
      database.withConnection { session -> IntendedSubjectsDao.readAnswer(session, studentId) }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /**
   * Replaces the student's whole answer.
   *
   * **The D17 rule lives here, and only here.** An EMPTY slug list from a
   * student who had subjects lands on [AnswerStatus.UNDECIDED], never back on
   * `unanswered`: we do not re-arm an invitation the family already answered.
   * `unanswered` is only ever the state of a student who was never asked, and
   * this method is the only writer, so no caller can set it.
   *
   * The cap is checked HERE as well as by the schema's deferred constraint
   * trigger, so an ordinary caller gets a refusal with a reason rather than a
   * constraint violation. The trigger is not thereby redundant: it is the
   * backstop a SECOND concurrent writer cannot get past, and the DAO takes the
   * student's status row `FOR UPDATE` first so the two writers serialise instead
   * of both reading "room for one more".
   */
  suspend fun replace(
    studentId: StudentId,
    update: IntendedSubjectsUpdate,
  ): Result<UpdateIntendedSubjectsResult> =
    try {
      when (update) {
        IntendedSubjectsUpdate.Declined -> write(studentId, IntendedSubjectWrite.Declined)
        IntendedSubjectsUpdate.Undecided -> write(studentId, IntendedSubjectWrite.Undecided)
        is IntendedSubjectsUpdate.Subjects -> replaceSubjects(studentId, update.slugs)
      }
    } catch (e: Exception) {
      // Mapped HERE, not merely in the DAO. This table's two invariants are
      // DEFERRABLE INITIALLY DEFERRED constraint triggers, so they fire at
      // COMMIT -- inside `withConnection`, after every DAO error mapper has
      // already run and returned. A raw `PSQLException` reaching the caller is
      // neither a PermanentError nor a TransientError, so StatusPages answers a
      // flat 500 and a retryable failure is never retried.
      Result.failure(mapDatabaseError(e))
    }

  private suspend fun replaceSubjects(
    studentId: StudentId,
    slugs: List<String>,
  ): Result<UpdateIntendedSubjectsResult> {
    // The empty set is not a validation failure — it is an ANSWER, and D17 says
    // which one. A student who removes their last subject is undecided.
    if (slugs.isEmpty()) return write(studentId, IntendedSubjectWrite.Undecided)

    val distinct = slugs.distinct()
    val reasons =
      listShapeReasons(slugs) + listVocabularyReasons(studentId, distinct).getOrElse { return Result.failure(it) }

    // Every refusal at once — one response naming all of them, never just the
    // first, the money route's pooling convention.
    if (reasons.isNotEmpty()) return Result.success(UpdateIntendedSubjectsResult.Rejected(reasons))
    return write(studentId, IntendedSubjectWrite.Subjects(distinct))
  }

  /**
   * What the submitted LIST says about itself: too many, and the same slug more
   * than once. Reads nothing, so it cannot fail.
   *
   * A duplicate is refused rather than silently de-duplicated — the student
   * should see what they sent — and the offenders are sorted so one input always
   * produces one sentence.
   */
  private fun listShapeReasons(slugs: List<String>): List<UpdateIntendedSubjectsResult.Reason> {
    val reasons = mutableListOf<UpdateIntendedSubjectsResult.Reason>()
    if (slugs.size > IntendedSubjectsDao.MAX_SUBJECTS) {
      reasons.add(UpdateIntendedSubjectsResult.Reason.TooMany(slugs.size))
    }
    slugs
      .groupBy { it }
      .filterValues { it.size > 1 }
      .keys
      .sorted()
      .forEach { reasons.add(UpdateIntendedSubjectsResult.Reason.Duplicate(it)) }
    return reasons
  }

  /**
   * What the TAXONOMY and the student's own answer say about each distinct slug:
   * unknown, or offered to nobody any more.
   *
   * Three reads, so it returns a `Result`: a failed taxonomy read is not a
   * refusal of the student's input and must never be reported as one.
   */
  private suspend fun listVocabularyReasons(
    studentId: StudentId,
    distinct: List<String>,
  ): Result<List<UpdateIntendedSubjectsResult.Reason>> {
    val known = vocabulary.namesFor(distinct).getOrElse { return Result.failure(it) }
    val active = vocabulary.activeSlugs(distinct).getOrElse { return Result.failure(it) }
    val held =
      getForStudent(studentId)
        .getOrElse { return Result.failure(it) }
        .subjects
        .map { it.slug }
        .toSet()

    return Result.success(
      distinct.mapNotNull { slug ->
        when {
          slug !in known -> UpdateIntendedSubjectsResult.Reason.NotASubject(slug)

          // A deprecated slug is refused only when it would be a NEW choice. The
          // student who already holds it keeps it; that is what deprecation buys.
          slug !in active && slug !in held -> UpdateIntendedSubjectsResult.Reason.NoLongerOffered(slug)

          else -> null
        }
      },
    )
  }

  private suspend fun write(
    studentId: StudentId,
    write: IntendedSubjectWrite,
  ): Result<UpdateIntendedSubjectsResult> =
    // The whole call is a success or it THROWS, and [replace]'s catch arm maps
    // what comes out. `withConnection` returns the block's value, not a Result,
    // so there is no failed Result to hand back from here.
    Result.success(
      UpdateIntendedSubjectsResult.Success(
        database.withConnection { session ->
          // `.getOrThrow()` INSIDE the block, not outside it. `replaceAnswer` is a
          // multi-statement write wrapped in `runCatching`, so a mid-sequence
          // failure comes back as a failed Result rather than an exception --
          // and `withConnection` rolls back only when the block THROWS. Returning
          // the failed Result would therefore COMMIT the half-applied answer
          // (status flipped, some subjects deleted, the rest never inserted) and
          // then report failure to the caller. Throwing here is what makes the
          // write all-or-nothing in this process, rather than relying on
          // PostgreSQL's own transaction abort, which covers SQL errors only.
          // `SubjectLoader.load` takes the same shape for the same reason.
          IntendedSubjectsDao.replaceAnswer(session, studentId, write).getOrThrow()
        },
      ),
    )
}
