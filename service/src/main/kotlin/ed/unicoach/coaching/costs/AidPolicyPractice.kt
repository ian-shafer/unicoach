package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AssuredFigure
import ed.unicoach.db.models.CollegeAidPolicy
import ed.unicoach.db.models.FullyMetNeedCounts

/**
 * How one school treats need, and what it asks a family to file (RFC 170).
 *
 * The read-side twin of [ed.unicoach.db.models.CollegeAidPolicy], with the two
 * things the store deliberately does NOT hold:
 *
 * - [shareOfFreshmenFullyMet], derived here from two stored counts and only
 *   when BOTH exist (D6, and RFC 148's rule about denominators). No source
 *   publishes it; a stored figure would be a derived one.
 * - `meets_full_need` -- which does not exist here at all, and must not. No
 *   source publishes that boolean either, and a school that meets full need
 *   for the students it admits is a claim assembled from the average share of
 *   need met and the fully-met headcount, each cited and each naming its
 *   cohort. Manufacturing the yes/no is the lie the honesty rules forbid.
 *
 * A form ABSENT from [requiredForms] is not listed in this school's filing
 * (D5). It is never rendered as "not required": the corpus's forms block
 * carries ticked boxes and no unticked ones at all, so "not listed" is the
 * whole of what absence says.
 */
data class AidPolicyPractice(
  /**
   * The average share of need met, over the freshmen who received need-based aid
   * (CDS H2 line i, over line e) -- WITH what kind of number it is (RFC 179).
   *
   * Enveloped, and the tier carried rather than re-derived: the tier is a
   * function of the row's own `(source, source_variable)` pair, which this type
   * does not hold. A renderer that re-derived it from "this is a Common Data Set
   * section" would be asserting the softest tier by position on the page rather
   * than reading it off the row -- and a tier kept in a side-map beside the
   * figure could simply be missing while the figure was served.
   */
  val averageNeedMet: AssuredFigure<Share>?,
  /** The average need-based grant of the freshmen who received one, whole US dollars (line k, over line e), and its tier. */
  val averageNeedBasedGrantUsd: AssuredFigure<Int>?,
  /**
   * Lines d and h as one value: the freshmen who received ANY financial aid,
   * and those of them met in full. Null when the school reports neither, and
   * never half a pair -- the type holds that rule so no renderer re-asserts it.
   * The two averages above are over the smaller line-e population, which is why
   * they are not derived from this count and must not be spoken over it.
   */
  val fullyMetNeed: FullyMetNeedCounts?,
  /** The forms this school's CDS lists, in the vocabulary's own order. */
  val requiredForms: List<AidForm>,
  /**
   * The forms this school's filing answers and WE could not read (D7).
   *
   * Said in its own words on the wire, never folded into "not listed": that
   * would report our own gap as the school's silence, which is the inversion
   * D7 exists to prevent.
   */
  val notCollectedForms: List<AidForm>,
  val source: CdsCitation,
) {
  /**
   * The share of the freshmen who RECEIVED ANY FINANCIAL AID whose need was met
   * in full (lines h over d) -- derived by [FullyMetNeedCounts], which is also
   * where the zero denominator is ruled on.
   *
   * Emitted with both of its counts and with its cohort named in the same
   * breath (the wire's label), never as a bare percentage: "84% fully met"
   * invites the reading "84% of freshmen", which is a different and much
   * larger population.
   */
  val shareOfFreshmenFullyMet: Share? get() = fullyMetNeed?.share

  /**
   * True when this school's filing carries at least one fact we can render.
   *
   * PUBLIC, and the reason is the whole point of the slice: a filing with
   * nothing usable in it and NO filing at all are two different silences, and
   * the caller says each in its own words. Collapsing the first onto the
   * second told a family "we hold no Common Data Set filing for this school"
   * about four schools whose filing we do hold.
   */
  val hasFact: Boolean
    get() =
      averageNeedMet != null ||
        averageNeedBasedGrantUsd != null ||
        shareOfFreshmenFullyMet != null ||
        requiredForms.isNotEmpty() ||
        // A filing whose only aid-policy row is one WE failed to read still has
        // something to say to a family: which form we could not read. That is a
        // fact about our own coverage, and saying it is the point of D7.
        notCollectedForms.isNotEmpty()

  companion object {
    /**
     * One college's stored aid policy. Always built when a filing exists --
     * whether it carries a renderable fact is [hasFact]'s question, and the
     * caller needs the difference to say which silence this is.
     */
    fun from(
      collegeName: String,
      row: CollegeAidPolicy,
    ): AidPolicyPractice =
      AidPolicyPractice(
        // The store holds the 0-1 share every share is stored as; Share holds
        // the percent it is SAID as. One conversion, here.
        averageNeedMet = row.averageNeedMet,
        averageNeedBasedGrantUsd = row.averageNeedBasedGrantUsd,
        fullyMetNeed = row.fullyMetNeed,
        requiredForms = row.requiredForms.sortedBy { AidForm.entries.indexOf(it) },
        notCollectedForms = row.notCollectedForms.sortedBy { AidForm.entries.indexOf(it) },
        source =
          CdsCitation(
            collegeName = collegeName,
            sourceYear = row.academicYear.firstCalendarYear,
            url = row.sourceUrl,
            archiveUrl = row.archiveUrl,
          ),
      )
  }
}
