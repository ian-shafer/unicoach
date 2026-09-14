package ed.unicoach.coaching.testing

import ed.unicoach.db.models.GradeRange
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolYearBasis
import ed.unicoach.db.models.TestChooser

/**
 * How a family hears what their STATE does about the school-day SAT and ACT
 * (RFC 208 sections 5 and 6).
 *
 * THE ONE HOME FOR THESE WORDS, on the [AdmissionTestCopy] precedent beside it.
 * [SchoolDayParticipation], [TestChooser] and [SchoolYearBasis] are `:db`
 * storage enums -- the codes the schema's own CHECKs admit -- and English a
 * family reads is a rendering decision, so it lives here in `:service` and never
 * on a storage enum. A `:db` enum carrying sentences puts model-facing copy
 * under a layer where no renderer owns it and no rendering test reaches it.
 *
 * Every `when` here is exhaustive with NO `else`: a third participation kind or
 * a fourth chooser must fail to compile at this seam rather than reach a family
 * as whichever arm happened to be last.
 *
 * NOTHING HERE NAMES A SCHOOL, and that is the rule these particular words exist
 * under. The table is a fact about the STATE; whether one named high school runs
 * its test day, and on what date, is not in it and cannot be inferred from it.
 * [schoolMustOfferClauseOf] is the closest this copy gets, and it is still a
 * sentence about what the state requires of every school rather than about the
 * student's own.
 */
object SchoolDayCopy {
  /**
   * Who must sit it -- and never who chooses it, which is the independent fact
   * [chooserLabelOf] speaks.
   *
   * [SchoolDayParticipation.OFFERED] is "rather than requiring it" and not "no
   * student must sit it": the state not requiring a student to sit it is what
   * the column says, and any stronger phrasing starts sounding like the state
   * discouraging it.
   */
  fun participationClauseOf(participation: SchoolDayParticipation): String =
    when (participation) {
      SchoolDayParticipation.REQUIRED -> "the state requires the school-day sitting"
      SchoolDayParticipation.OFFERED -> "the state offers the school-day sitting rather than requiring it"
    }

  /**
   * WHO picks which test, which a family hears as three different sentences --
   * and where it is not the state, the state has chosen no test and the copy
   * that names a test is unreachable by construction
   * ([ed.unicoach.db.models.SchoolDayTestChoice]).
   */
  fun chooserLabelOf(chooser: TestChooser): String =
    when (chooser) {
      TestChooser.STATE -> "the state chooses which test"
      TestChooser.DISTRICT -> "the district chooses which test"
      TestChooser.STUDENT -> "the student chooses which test"
    }

  /**
   * The grade or grades the sitting happens in, or the statement that the page
   * named none.
   *
   * A null range is SAID and never completed: "your junior year" is the sentence
   * a guessed grade breaks, and the pair is null exactly when the source named
   * no grade at all.
   */
  fun gradesClauseOf(grades: GradeRange?): String =
    when {
      grades == null -> "the state's page names no grade, and none is guessed here"
      grades.lowest == grades.highest -> "it happens in grade ${grades.lowest}"
      else -> "it happens in grades ${grades.lowest} to ${grades.highest}"
    }

  /**
   * Who pays -- three answers, because null is "the page does not say" and is
   * the commonest of the three.
   *
   * A null is NEVER spoken as "the sitting is free". That is the trust-losing
   * answer in the other direction, and nothing in this corpus records a price at
   * all: "the state pays" and "the page does not say" are the only two things
   * these sources support.
   */
  fun fundingClauseOf(stateFunded: Boolean?): String =
    when (stateFunded) {
      true -> "the state pays for that sitting"
      false -> "the state's page says the state does not pay for it"
      null -> "the state's page does not say who pays, so we do not know who pays"
    }

  /**
   * The one thing this corpus can say that comes near a school-level answer, and
   * ONLY when the column is `true`: the state requires every public high school
   * to HOLD the sitting (Virginia's law is why the column exists).
   *
   * NULL RETURNS NULL, and the caller says nothing either way. Null is UNKNOWN
   * -- we did not read it for most states -- and "the state does not require
   * schools to hold it" is a different claim, needing its own page. A copy
   * function that spoke a sentence for null would be exactly that invention.
   */
  fun schoolMustOfferClauseOf(schoolMustOffer: Boolean?): String? =
    when (schoolMustOffer) {
      true -> "the state requires every public high school to hold the sitting"
      false -> "the state does not require every public high school to hold it"
      null -> null
    }

  /**
   * WHOSE school year the row's year is, in the words the difference actually
   * matters in: "the page names this year" and "this is the year we read it in"
   * are different claims, and only the first is the state's.
   *
   * It names no year itself. The year rides beside it on the wire as
   * [ed.unicoach.common.util.AcademicYear]'s own label, which is the one home
   * for `2026-27`, so this label cannot disagree with it.
   */
  fun schoolYearBasisLabelOf(basis: SchoolYearBasis): String =
    when (basis) {
      SchoolYearBasis.STATED_BY_SOURCE -> "the state's page names this school year"
      SchoolYearBasis.READING_YEAR -> "the state's page names no school year; this is the year we read it in"
    }

  /**
   * The question this table cannot answer, said in the same object as every
   * answer it CAN give (RFC 208 section 6).
   *
   * It ships as a field rather than as a rule in the prompt alone, because the
   * boundary is the most misreadable thing about this section: a state programme
   * is not a school's plan, and the model reads a field beside the data more
   * reliably than a sentence a thousand lines away.
   */
  const val WHAT_THE_STATE_CANNOT_ANSWER: String = "whether this student's own school runs it, and on what date"
}
