package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.Share

/**
 * One college's Common Data Set aid policy for one cycle (RFC 170): what it
 * asks a family to FILE, and how it treated need.
 *
 * A READ shape assembled from three canonical tables, not a table of its own --
 * which is the point of D2: the statistics live where cohort statistics live
 * and the requirements are a relation. Every figure is nullable because the
 * school reports what it reports; a null is silence, and the read layer says so
 * rather than filling it.
 *
 * The two fully-met headcounts travel as ONE value ([FullyMetNeedCounts]) for
 * the [FigureReading] reason: half a pair is not a figure anyone may publish,
 * so it must not be a value anyone can build. The share it derives is computed
 * at read time and never stored (D3, D6).
 */
data class CollegeAidPolicy(
  val collegeId: CollegeId,
  /** The CDS cycle these facts come from; its 'YYYY-YY' label is rendered, never stored. */
  val academicYear: AcademicYear,
  val sourceUrl: String,
  val archiveUrl: String?,
  /**
   * CDS H2 line i: the average share of need met, over the freshmen the school
   * gave need-based scholarship or grant aid (line e) -- NOT over the larger
   * line-d population below.
   *
   * A [Share], not a bare ratio: the store holds 0-1 and the coach says a
   * percent, and the one type that converts between them is the one that also
   * says it aloud. A `Double` crossing this boundary is the 100x hazard with
   * nothing to catch it.
   */
  val averageNeedMet: Share?,
  /** CDS H2 line k: the average need-based grant of those in line e, whole US dollars. */
  val averageNeedBasedGrantUsd: Int?,
  /**
   * CDS H2 lines d and h as ONE fact, or null when the school reports neither:
   * the freshmen who were awarded any financial aid, and those of them whose
   * need was met in full.
   */
  val fullyMetNeed: FullyMetNeedCounts?,
  /**
   * The forms this school's CDS lists as required of domestic first-year aid
   * applicants. A form ABSENT from both this list and [notCollectedForms] is
   * not listed in that filing -- which is not the same claim as "not required"
   * (D5).
   */
  val requiredForms: List<AidForm>,
  /**
   * The forms this school's filing DOES answer and we failed to read (D7):
   * status `not_collected_by_us`, so the row bears no value.
   *
   * Carried rather than dropped, because dropping it turns OUR gap into the
   * school's silence -- the read would then say "this form is not listed in
   * that filing" about a form the filing lists. That inversion is the one D7
   * exists to prevent.
   */
  val notCollectedForms: List<AidForm>,
)

/**
 * CDS H2 lines d and h as ONE fact (RFC 170): the freshmen who were awarded any
 * financial aid, and those of them whose need was met in full.
 *
 * The two travel together by construction, on the [FigureReading] rule: a
 * fully-met headcount with no denominator beside it is not a figure anyone may
 * publish, so it is not a value anyone can build -- and no renderer has to
 * re-assert the pair at run time. [share] is derived here and never stored (the
 * schema conventions' derived-figures rule); it is null only where
 * [Share.ofOrNull] refuses the denominator, which is the one place a share of
 * nobody is ruled on.
 */
data class FullyMetNeedCounts(
  val freshmenAwardedAnyAid: Int,
  val freshmenNeedFullyMet: Int,
) {
  val share: Share? = Share.ofOrNull(part = freshmenNeedFullyMet, whole = freshmenAwardedAnyAid)
}
