package ed.unicoach.db.models

import ed.unicoach.db.dao.IntendedSubjectsDao

/**
 * A student's WHOLE answer to "what do you want to study?" (RFC 205) — the
 * `intended_subject_status` row and the `intended_subjects` set read together,
 * because neither half means anything alone.
 *
 * The two halves are two things, not one deformed thing (D36/D37): a zero-length
 * [subjects] does not say which of three answers was given, so [status] says it.
 * The pair is exactly what the schema's deferred `status iff subjects`
 * constraint trigger guarantees: [subjects] is non-empty EXACTLY when [status]
 * is [AnswerStatus.ANSWERED].
 *
 * [AnswerStatus.UNDECIDED] — "I don't know yet" — is reachable here and nowhere
 * else; the three `money_profiles` status CHECKs forbid it.
 */
data class IntendedSubjectAnswer(
  val studentId: StudentId,
  val status: AnswerStatus,
  val subjects: List<IntendedSubjectName>,
) {
  init {
    // A reconstruction guard: this value is built straight out of two tables, so
    // a throw here means those rows disagree. It therefore names the STUDENT and
    // the slugs — the two things that locate the bad data — and not only the
    // rule, which the reader already has in front of them.
    require(subjects.isNotEmpty() == (status == AnswerStatus.ANSWERED)) {
      "An intended-subject answer holds subjects exactly when its status is answered; " +
        "student [${studentId.asString}] has status=[${status.value}] with [${subjects.size}] " +
        "subject(s) [${subjects.joinToString(", ") { it.slug }}]"
    }
  }
}

/**
 * One named subject in a student's answer: the identity the client sends back
 * and the label a person reads (D39).
 *
 * [name] is resolved at READ time from `subjects`, never frozen into the
 * student's row, so a taxonomy rename never touches what the student answered —
 * and a DEPRECATED subject the student already holds still resolves to its name.
 */
data class IntendedSubjectName(
  val slug: String,
  val name: String,
)

/**
 * The one write this surface has (D17): a student's WHOLE answer, replaced.
 *
 * There is no add-one, no remove-one and no clear. Sealed, so the schema's
 * `status iff subjects` invariant is a compile-time fact here too: only
 * [Subjects] can carry slugs, and no member of this type can ask for
 * `unanswered` — that is only ever the state of a student who was never asked,
 * and no caller can set it.
 */
sealed interface IntendedSubjectWrite {
  /**
   * "Nursing, and maybe biology." One to three subjects, unordered and unranked.
   *
   * An EMPTY list is deliberately NOT representable here: a student who removes
   * their last subject lands on [Undecided], never back on `unanswered` — we do
   * not re-arm an invitation the family already answered (D17). The service
   * makes that translation before it reaches this type.
   */
  data class Subjects(
    val slugs: List<String>,
  ) : IntendedSubjectWrite {
    init {
      // BOTH bounds, from the one source that owns the cap. This type is the
      // last thing between a caller and the schema's deferred cap trigger, which
      // fires at COMMIT, names no row and cannot be told apart from any other
      // late failure — so an over-long list is refused here, naming what it was.
      require(slugs.size in 1..IntendedSubjectsDao.MAX_SUBJECTS) {
        "An answered intended-subject write carries 1..${IntendedSubjectsDao.MAX_SUBJECTS} subjects; " +
          "got ${slugs.size}: [${slugs.joinToString(", ")}]"
      }
      // Distinct, because `intended_subjects` is a SET: the same slug twice is
      // one subject asked for twice, which
      // `intended_subjects_student_subject_active_idx` refuses at the write
      // anyway — as a unique-violation naming an index, which no caller meant
      // and no student can be told.
      require(slugs.distinct().size == slugs.size) {
        val repeated =
          slugs
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
        "An answered intended-subject write names each subject once; repeated: " +
          "[${repeated.joinToString(", ")}] in [${slugs.joinToString(", ")}]"
      }
    }
  }

  /** "I'd rather not say." A stance, and a different sentence from [Undecided]. */
  data object Declined : IntendedSubjectWrite

  /** "I don't know yet." A real answer at 16, recorded as one — not silence, and not a refusal. */
  data object Undecided : IntendedSubjectWrite
}
