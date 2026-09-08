package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.LoanTypeBorrowing

/**
 * What the students who GRADUATED from one school in one named year borrowed,
 * as that school reports it (RFC 175).
 *
 * The read-side twin of [ed.unicoach.db.models.CollegeBorrowing], plus the
 * citation, on the [AidPolicyPractice] pattern. It holds NO total and offers no
 * way to build one: the four typed loan sets overlap, so summing them
 * double-counts a student who borrowed twice, and [LoanType.ANY] is the
 * school's own reported any-loan figure and never our addition (D3).
 *
 * Nothing here may be subtracted from a price, and no figure here is a price
 * (brief 0003). Debt is an OUTCOME of the price conversation and sits beside
 * it, in its own sentence.
 */
data class BorrowingAtGraduation(
  /** CDS H.401: the class every figure below is reported over, or null when the filing omits it. */
  val graduatingClass: Int?,
  /** One entry per loan type this filing says anything about. Absence is silence, never zero. */
  val byLoanType: Map<LoanType, LoanTypeBorrowing>,
  val source: CdsCitation,
) {
  /** This loan type's figures, or null when this school's filing reports none of them. */
  fun of(loanType: LoanType): LoanTypeBorrowing? = byLoanType[loanType]

  /**
   * True when this filing carries at least one borrowing figure we can render.
   *
   * PUBLIC for the [AidPolicyPractice.hasFact] reason: a filing with no
   * borrowing block and NO filing at all are two different silences, and the
   * caller says each in its own words.
   */
  val hasFact: Boolean get() = byLoanType.values.any { it.hasFact }

  companion object {
    /**
     * One college's stored borrowing block. Always built when a filing exists;
     * WHICH of the four things we can say about it is
     * [BorrowingCoverage.of]'s question, and no caller decides it here.
     */
    fun from(
      collegeName: String,
      row: CollegeBorrowing,
    ): BorrowingAtGraduation =
      BorrowingAtGraduation(
        graduatingClass = row.graduatingClass,
        byLoanType = row.byLoanType,
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
