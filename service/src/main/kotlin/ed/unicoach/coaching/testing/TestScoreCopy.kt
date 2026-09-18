package ed.unicoach.coaching.testing

import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.usDate

/**
 * The words a family hears (or the coach reads) about the student's own score
 * (RFC 207).
 *
 * Every sentence is a CONSTANT or a pure function of the row, on
 * [ed.unicoach.coaching.admissions.ScoreBandCopy]'s precedent: the sentence the
 * coach reads and the figures it came from cannot drift apart. Nothing here is
 * typed beside the data at a call site, and nothing here says a student NEEDS a
 * score, SHOULD score one, is COMPETITIVE, or has a CHANCE -- D9, which
 * `TestScoreCopyTest` asserts on these constants.
 */
object TestScoreCopy {
  /** What every stored number is, and never is: the student told us, not an agency. */
  const val SELF_REPORTED_NOTE: String = "self-reported, never an official score from the testing agency"

  /** The block header when she has shared at least one score. */
  const val ANSWERED_HEADER: String = "Test scores the student has told us about ($SELF_REPORTED_NOTE; never gate an answer on one):"

  /** The block when she declined; the coach must not re-ask, which is the whole reason the refusal is stored. */
  const val DECLINED_LINE: String = "Test scores: declined (asked and declined — never re-ask it unless the student reopens the topic)"

  /** "the day is not recorded" -- OUR gap, said once, shared with the graduation block's wording. */
  const val DAY_NOT_RECORDED: String = "the day is not recorded"

  /** "only the year is recorded" -- the year-precision gap. */
  const val ONLY_YEAR_RECORDED: String = "only the year is recorded — no month, no day"

  /**
   * One score line: the component's spoken name, the number, and the sitting in
   * the ONE shape a human reads ([PartialDate.usDate], RFC 227), with the
   * missing-precision note beside it so the coach never invents a month we do
   * not hold (RFC 186's rule, carried to the scores block).
   */
  fun scoreLineOf(
    component: AdmissionTestScoreComponent,
    score: Int,
    sitting: PartialDate,
  ): String = "- ${component.label} $score, sat ${sittingPhrase(sitting)}"

  private fun sittingPhrase(sitting: PartialDate): String =
    when (sitting) {
      is PartialDate.FullDate -> "on ${sitting.usDate()}"
      is PartialDate.YearAndMonth -> "in ${sitting.usDate()} ($DAY_NOT_RECORDED)"
      is PartialDate.YearOnly -> "in ${sitting.usDate()} ($ONLY_YEAR_RECORDED)"
    }
}
