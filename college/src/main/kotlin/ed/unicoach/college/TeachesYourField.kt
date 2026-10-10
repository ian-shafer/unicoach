package ed.unicoach.college

import ed.unicoach.db.models.ProgrammeCensus

/**
 * What a school's programme census says about the subjects THIS student stored
 * (RFC 245 A1) -- the answer and, for a yes, the subject words that produced it,
 * because the two are ONE fact.
 *
 * It is a LABEL, not a ranking and not a filter. Nothing here reaches SQL: the
 * rule runs over a row the search already returned, so a stored subject can
 * never remove a school from a page, shorten it by `LIMIT`, or reorder it.
 *
 * The three answers are the three states [ProgrammeCensus] names (schema
 * `0064`, columns `:178-179`, comments `:222-230`):
 *
 * - [ProgrammeCensus.NotReported] -- this school reported no programme census at
 *   all, so the question cannot be judged. [Unknown], and never spoken as a no:
 *   a school that does not report something has not been shown to fail it, which
 *   is the rule the search already holds for every other unjudgeable column;
 * - [ProgrammeCensus.OnFile] with no subjects -- the census is on file and none
 *   of its programs belongs to any taxonomy subject. A judged [No], not a
 *   silence;
 * - [ProgrammeCensus.OnFile] with subjects -- what this school is recorded as
 *   teaching. [Yes] when any of them is one of the student's own, carrying
 *   WHICH.
 *
 * `cip_codes` has no empty-array state, which is why the label reads
 * `subject_slugs` and nothing else.
 *
 * A SEALED TYPE and not an enum since tier 1 of this RFC: the matched subjects
 * are part of the yes, so "yes, but I cannot name which" -- the exact statement
 * decision A3 exists to prevent -- is no longer something a caller can hold.
 */
sealed interface TeachesYourField {
  /** The WORD this answer rides under on the wire. */
  val value: String

  /**
   * This school's census records at least one of the student's OWN subjects,
   * and [subjectsTaught] is which: the intersection of the two sets, sorted so
   * one school's list is stable from one call to the next.
   *
   * NEVER empty. [of] is the one site that builds this, and it builds it only
   * from an overlap it has already found non-empty; the guard is what keeps a
   * second site honest, on `IntendedSubjectWrite.Subjects`' precedent.
   */
  data class Yes(
    val subjectsTaught: List<String>,
  ) : TeachesYourField {
    init {
      require(subjectsTaught.isNotEmpty()) {
        "a yes names the subjects that matched; a yes naming none is the guess this label exists to remove"
      }
    }

    override val value: String get() = YES
  }

  /**
   * The census is ON FILE and names none of this student's subjects: a judged
   * no, which is why it carries no subject list. A `no` beside an empty array
   * would read as a claim about this school's programs rather than about this
   * student's question.
   */
  data object No : TeachesYourField {
    override val value: String get() = NO
  }

  /**
   * The question could not be judged: OUR silence, never the school's answer,
   * and [cause] says on WHICH side the silence falls.
   *
   * A school that filed no programme census and a student holding a subject no
   * census can name are two different facts, and only one of them is about the
   * school. Both ride the SAME wire word, so nothing downstream has a fourth
   * answer to read -- but a log, a report or a later surface can tell them
   * apart without re-deriving the two sets.
   */
  data class Unknown(
    val cause: Cause,
  ) : TeachesYourField {
    override val value: String get() = UNKNOWN
  }

  /** WHY a question could not be judged. */
  enum class Cause {
    /** This school reported no programme census at all: the SCHOOL's silence. */
    NO_CENSUS,

    /**
     * One of this student's stored subjects is outside the taxonomy this process
     * loaded -- retired, or newer than the snapshot -- so no census can name it
     * and no census can be said to name none of them. OUR silence.
     */
    SUBJECT_NOT_JUDGEABLE,
  }

  companion object {
    /**
     * The wire key the label rides under.
     *
     * Here, on the type that owns the vocabulary, rather than beside the other
     * row keys in `CollegeMatchRow`: the row renderer, the shared tool-description
     * note and the `:service` tests that read the label back all need to spell
     * it, and two of those three live outside this module's `internal`.
     */
    const val WIRE_KEY: String = "teaches_your_field"

    /**
     * The wire key of the matched subjects, present only beside a [Yes].
     *
     * The student may have stored up to three subjects, so `"yes"` alone would
     * force the coach to guess WHICH one matched -- and guessing is the thing
     * this whole surface exists to remove.
     */
    const val SUBJECTS_TAUGHT_WIRE_KEY: String = "your_subjects_taught"

    /** The three words, on the wire, unchanged by anything above them. */
    const val YES: String = "yes"

    /** @see YES */
    const val NO: String = "no"

    /** @see YES */
    const val UNKNOWN: String = "unknown"

    /**
     * The one rule, pure and database-free: what [census] --
     * `college_search_index.subject_slugs` for one school, in both of its states
     * -- says about [storedSubjectSlugs], the subjects this student stored.
     *
     * The overlap is computed ONCE and the answer is built from it, so the label
     * and the list beside it cannot disagree about the same two sets: the answer
     * is a [Yes] exactly when that overlap is non-empty, by construction rather
     * than by a test holding two halves together.
     *
     * NULL means ONE thing: this student stored no subject, so no question was
     * asked and the row writes no key (D20). Every OTHER way of failing to
     * judge is our silence and says so as [Unknown] -- a stored subject can
     * only be judged against [taxonomy], the loaded taxonomy the index is built
     * from, so a student holding only subjects it does not carry
     * is told we could not judge, never that we hold nothing of theirs. The two
     * sentences a coach would say are different, and so are the facts.
     */
    fun of(
      census: ProgrammeCensus,
      storedSubjectSlugs: Set<String>,
      taxonomy: SubjectTaxonomy,
    ): TeachesYourField? {
      // The ONE absence: nothing was stored, so there is nothing to answer and
      // the row is byte-identical to the page that shipped before this RFC
      // (D20). It is NOT the answer for a student who stored subjects this
      // taxonomy cannot judge -- that is ours to own, below.
      if (storedSubjectSlugs.isEmpty()) return null

      val judgeable = storedSubjectSlugs intersect taxonomy.slugs

      return when (census) {
        // No programme census at all. Unjudgeable on the SCHOOL's side, never a
        // no -- and answered BEFORE anything reads a list, so no ordering of the
        // arms below can turn this silence into the school's answer.
        ProgrammeCensus.NotReported -> {
          Unknown(Cause.NO_CENSUS)
        }

        is ProgrammeCensus.OnFile -> {
          val taught =
            census.subjectSlugs
              .filter { it in judgeable }
              .distinct()
              .sorted()
          when {
            taught.isNotEmpty() -> Yes(taught)

            // The census is on file and names none of the subjects we can judge
            // -- but one of this student's is a subject this taxonomy does not
            // carry, which no census carries either. "Its census names none of
            // your subjects" is then a claim we cannot make, and the one error
            // this whole surface exists to prevent is speaking our silence as
            // the school's answer. This arm also holds the student NOTHING of
            // whose stored subjects is judgeable, including the one whose
            // taxonomy never loaded.
            judgeable.size < storedSubjectSlugs.size -> Unknown(Cause.SUBJECT_NOT_JUDGEABLE)

            // Every stored subject was judgeable and the census named none of
            // them: a judged no, including the judged empty array.
            else -> No
          }
        }
      }
    }
  }
}
