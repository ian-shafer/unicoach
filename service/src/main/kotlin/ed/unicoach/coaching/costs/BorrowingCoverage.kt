package ed.unicoach.coaching.costs

import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType

/**
 * WHAT we can say about one school's borrowing at graduation (RFC 175, D7):
 * four states, and whose silence each one is.
 *
 * A sealed type rather than a nullable beside two booleans, because that shape
 * told the four states apart by the ORDER of a `when`'s arms -- and reading
 * them in the wrong order says OUR gap as the school declining to report,
 * which is the inversion D7 exists to prevent. It also made the useful state
 * unrepresentable in practice: a filing with one readable figure AND an unread
 * cell was routed to the figures arm, and the unread cell was never mentioned.
 *
 * The states are decided ONCE, by [of]. Every consumer answers all four or
 * does not compile.
 */
sealed interface BorrowingCoverage {
  /** OUR coverage: the corpus holds no Common Data Set filing for this school at all. */
  data object NoFiling : BorrowingCoverage

  /**
   * THEIR filing, with no borrowing figure we can render in it -- a different
   * fact from having no filing.
   *
   * A data class rather than an object, because "we can render nothing" has
   * more than one reason and they are not the school's silence in equal
   * measure. A filing that reports H.401 and a borrower count that contradicts
   * it reported TWO figures; told as the bare object, the coach said "it
   * reports none of the borrowing figures" about it. A filing whose cells the
   * PUBLISHER withheld is not the school's silence either.
   */
  data class NoBlock(
    /**
     * The loan types whose borrower counts contradict this filing's own
     * graduating class (D6). The FIGURES are never spoken to a family -- that
     * decision stands -- but their existence is, because this filing answered.
     *
     * Read by `CollegeCostChatTool.putBorrowing`, which says
     * `not_reconciled_by_us` for a filing whose only borrowing cells are these:
     * OUR withholding of a pair we could not reconcile, never the school's
     * silence. A state that dropped this set could not tell such a filing from
     * one that answered nothing at all, and said "it reports none of the
     * borrowing figures" about a school that reported two.
     */
    val contradicted: Set<LoanType> = emptySet(),
    /**
     * The statuses of this filing's value-less borrowing cells, so a caller can
     * say a publisher's withholding or a `not_applicable` cell as what it is
     * rather than as the school declining to report.
     */
    val gapStatuses: Set<FigureStatus> = emptySet(),
  ) : BorrowingCoverage

  /**
   * THEIR filing ANSWERS the borrowing questions and WE could not read a
   * single answer out of it -- OUR gap, never spoken as the school reporting
   * nothing.
   */
  data object NotReadByUs : BorrowingCoverage

  /**
   * At least one figure we can render, WITH the value-less cells of the same
   * filing and whose silence each one is.
   *
   * The two travel together because they are one filing: a school that filed a
   * federal average we read and a private cell we did not must have its
   * federal figure rendered AND our own gap said about private. Carried here
   * rather than on [BorrowingAtGraduation] so no renderer can be handed the
   * figures without the gaps beside them.
   */
  data class Reported(
    val figures: BorrowingAtGraduation,
    /**
     * WHOSE silence each value-less cell is, per loan type -- a STATUS and not
     * a set of "unread" types, because `not_collected_by_us` (ours, D7),
     * `suppressed_by_publisher` (the publisher's) and `not_applicable` (the
     * source's) are three different sentences and only the fourth,
     * `not_reported_by_institution`, is the school's plain silence.
     */
    val gapStatusByLoanType: Map<LoanType, FigureStatus>,
    /**
     * The same silence at the one cell that names no loan type, CDS H.401.
     *
     * Carried beside the loan types rather than dropped, because it is the
     * DENOMINATOR every share divides by: without it no share is derived and no
     * borrower count is spoken, and a reader told nothing reads that as the
     * school's silence.
     */
    val graduatingClassGapStatus: FigureStatus?,
  ) : BorrowingCoverage {
    /** The loan types whose silence is OURS (D7) -- the gap a family must never hear as the school's. */
    val notReadLoanTypes: Set<LoanType>
      get() = gapStatusByLoanType.filterValues { it == FigureStatus.NOT_COLLECTED_BY_US }.keys

    /** True when this filing answers CDS H.401 and WE could not read the answer. */
    val graduatingClassNotReadByUs: Boolean
      get() = graduatingClassGapStatus == FigureStatus.NOT_COLLECTED_BY_US
  }

  companion object {
    /**
     * WHICH of the four states one school is in, decided in this one place.
     *
     * The arms are ordered here, once, and nowhere else: a filing that carries
     * a renderable figure is REPORTED even when some of its cells are ours to
     * explain, because the figures it does carry are owed to the family and
     * the gaps ride along in [Reported.gapStatusByLoanType].
     */
    fun of(
      collegeName: String,
      row: CollegeBorrowing?,
    ): BorrowingCoverage {
      if (row == null) return NoFiling
      val figures = BorrowingAtGraduation.from(collegeName, row)
      return when {
        figures.hasFact -> {
          Reported(figures, row.gapStatusByLoanType, row.graduatingClassGapStatus)
        }

        row.notReadByUs -> {
          NotReadByUs
        }

        else -> {
          NoBlock(
            contradicted =
              row.byLoanType
                .filterValues { it.borrowers is BorrowerCounts.ContradictsGraduatingClass }
                .keys,
            gapStatuses =
              (row.gapStatusByLoanType.values + listOfNotNull(row.graduatingClassGapStatus)).toSet(),
          )
        }
      }
    }
  }
}
