package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.Share

/**
 * What the students who GRADUATED from one school in one year actually
 * borrowed, as that school reports it in its own Common Data Set (RFC 175).
 *
 * A READ shape assembled from the two cohort tables, not a table of its own --
 * the [CollegeAidPolicy] rule, and for the same reason: the averages are cohort
 * statistics and the headcounts are cohort headcounts, and both already have a
 * home. Every figure is nullable because a school reports what it reports.
 *
 * The SHARE who borrowed is not here and is not stored anywhere (D2): the
 * source's own percent cells are corpus-typed text carrying a 0..100 percent
 * for some schools and a 0..1 fraction for others, so it is derived from the
 * counts, by [BorrowerCounts.Counted], and only where both counts exist.
 *
 * Two loan types are never added together, here or anywhere downstream (D3):
 * the four typed sets overlap, and [LoanType.ANY] is the school's own reported
 * any-loan figure.
 */
class CollegeBorrowing(
  val collegeId: CollegeId,
  /** The CDS cycle these figures come from; its 'YYYY-YY' label is rendered, never stored. */
  val academicYear: AcademicYear,
  val sourceUrl: String,
  val archiveUrl: String?,
  /**
   * CDS H.401: how many students this school says it awarded a bachelor's
   * degree to in this cycle -- the denominator of every borrowing share, and
   * the cohort every figure below is about.
   */
  val graduatingClass: Int?,
  /**
   * The average this filing reports per loan type, as a bare figure: the class
   * size it is spoken beside is [graduatingClass] and is held ONCE, above.
   */
  averageDebtUsdByLoanType: Map<LoanType, Int>,
  /**
   * The borrower HEADCOUNT this filing reports per loan type, as a bare figure
   * for the same reason. A caller cannot hand this class a second graduating
   * class: the pairs below are stitched here, against the one class size this
   * filing reports, so five loan types and the aggregate cannot come to
   * disagree about how large the graduating class was.
   */
  borrowersByLoanType: Map<LoanType, Int>,
  /**
   * The loan types whose cells this filing ANSWERS with no value, and the
   * STATUS the store holds for that answer -- NAMED rather than counted, on
   * the [CollegeAidPolicy.notCollectedForms] precedent.
   *
   * A STATUS and not a boolean, because there are four different silences here
   * and only three of them belong to the school. `not_collected_by_us` is OUR
   * gap (D7) and must never be spoken as the school declining to report;
   * `suppressed_by_publisher` is the publisher withholding the cell;
   * `not_applicable` is the source itself saying the question does not apply;
   * and `not_reported_by_institution` is the school's own plain silence. A
   * boolean flattened all four into "ours or theirs" and told a family that a
   * school does not report a figure whose cell the publisher withheld.
   *
   * Named per loan type because the sentence a reader says is about ONE
   * column: a filing with a readable federal average and an unread private
   * cell must say our gap about private and the school's figure about federal.
   *
   * A loan type has TWO cells (its average and its borrower count) and one
   * entry here, so when the two disagree the entry is the status whose
   * MISATTRIBUTION would be worst -- see [gapStatusOf]. The collapse is the
   * granularity of the sentence, which is per loan type.
   */
  gapStatusByLoanType: Map<LoanType, FigureStatus>,
  /**
   * The same silence at the one cell that names no loan type: CDS H.401, the
   * graduating class itself.
   *
   * Its own field because it is not a loan type and cannot be one: it is the
   * DENOMINATOR every share divides by. A filing whose only borrowing cell we
   * could not read is still a filing that answers a borrowing question, and
   * without this the reader would report the school as silent about a line it
   * filed.
   */
  val graduatingClassGapStatus: FigureStatus? = null,
) {
  /**
   * A SNAPSHOT of the gaps handed in, for the [byLoanType] reason: these decide
   * whose silence a filing's is, so no caller may keep a handle that changes
   * them after this filing was read.
   */
  val gapStatusByLoanType: Map<LoanType, FigureStatus> = gapStatusByLoanType.toMap()

  /**
   * One entry per loan type this filing says anything about; the rest are
   * absent, not zero.
   *
   * BUILT here rather than taken as a parameter, because every entry's
   * denominator is this filing's own [graduatingClass] and a constructor that
   * accepted the pairs ready-made accepted a second class size with them.
   */
  val byLoanType: Map<LoanType, LoanTypeBorrowing> =
    LoanType.entries
      .mapNotNull { loanType ->
        val built =
          LoanTypeBorrowing(
            averageDebtUsd = averageDebtUsdByLoanType[loanType],
            borrowers = borrowerCounts(borrowersByLoanType[loanType], graduatingClass),
          )
        if (built.averageDebtUsd != null || built.borrowers != null) loanType to built else null
      }.toMap()

  /**
   * The loan types whose silence is OURS: this filing answers the column and we
   * did not collect the answer (D7). DERIVED from [gapStatusByLoanType] rather
   * than stored beside it, so no caller can be told "nothing is unread" while a
   * loan type sits in the map under `not_collected_by_us`.
   */
  val notReadLoanTypes: Set<LoanType>
    get() = gapStatusByLoanType.filterValues { it == FigureStatus.NOT_COLLECTED_BY_US }.keys

  /** The same question at CDS H.401, the denominator every share divides by. */
  val graduatingClassNotReadByUs: Boolean get() = graduatingClassGapStatus == FigureStatus.NOT_COLLECTED_BY_US

  /** True when ANY cell of this filing's borrowing block is our own unread gap. */
  val notReadByUs: Boolean get() = notReadLoanTypes.isNotEmpty() || graduatingClassNotReadByUs
}

/**
 * WHICH of two silences at one loan type a reader is told about, when the
 * average and the borrower count are value-less for different reasons.
 *
 * Ordered by the cost of getting the attribution WRONG, not by severity: our
 * own uncollected cell first, because handing that to the school is the
 * inversion D7 exists to prevent; then the publisher's own withholding; then
 * the source saying the question does not apply; and the school's plain
 * silence last, because it is the only one that is safe to under-say.
 */
internal fun gapStatusOf(
  first: FigureStatus,
  second: FigureStatus,
): FigureStatus {
  // The four VALUE-LESS statuses, and all four of them: `reported` and
  // `imputed_by_publisher` cannot reach here. The caller passes only the
  // statuses of cells that carry no value, and
  // `cohort_money_stats_value_iff_status_check` (schema 0083) and
  // `cohort_population_counts_value_iff_status_check` (0085) both refuse a
  // value-less row at either of those two.
  val order =
    listOf(
      FigureStatus.NOT_COLLECTED_BY_US,
      FigureStatus.SUPPRESSED_BY_PUBLISHER,
      FigureStatus.NOT_APPLICABLE,
      FigureStatus.NOT_REPORTED_BY_INSTITUTION,
    )
  val firstRank = order.indexOf(first)
  val secondRank = order.indexOf(second)
  // `indexOf` answers -1 for an unranked status, which is a value-bearing one.
  // The `firstRank < 0` arm makes it YIELD to the other rather than outrank
  // every silence at index -1, which is what raw index arithmetic would do.
  return if (secondRank in 0..<firstRank || firstRank < 0) second else first
}

/**
 * This loan type's borrower count paired with the class size it is reported
 * over, or null when either half is missing -- and the SCHOOL's own
 * contradiction when the two cannot both be true.
 *
 * A count with no class size is not a value here: the only thing this surface
 * says about a headcount is the share it makes with the class size, and the
 * loan type's AVERAGE still speaks without it (acceptance criterion (c)).
 */
private fun borrowerCounts(
  borrowers: Int?,
  graduatingClass: Int?,
): BorrowerCounts? {
  if (borrowers == null || graduatingClass == null) return null
  return if (borrowers > graduatingClass) {
    BorrowerCounts.ContradictsGraduatingClass(borrowers = borrowers, graduatingClass = graduatingClass)
  } else {
    BorrowerCounts.Counted(borrowers = borrowers, graduatingClass = graduatingClass)
  }
}

/**
 * One loan type's half of a filing's borrowing block: the average cumulative
 * principal its borrowers owed at graduation, and how many of the graduating
 * class they were.
 *
 * [borrowers] carries its own denominator ([BorrowerCounts]) rather than a
 * bare headcount, so the derived share cannot be built -- and therefore cannot
 * be emitted -- without the class size it is a share OF.
 */
data class LoanTypeBorrowing(
  val averageDebtUsd: Int?,
  val borrowers: BorrowerCounts?,
) {
  /**
   * A figure we can RENDER -- the SAME question both renderers ask, not a
   * looser one. A contradicted pair is a fact we HOLD and never one we say, so
   * it does not make a loan type speakable on its own: a school whose only
   * borrowing cell is a count above its graduating class has nothing for a
   * family here. Nor does a consistent pair that yields no SHARE (a filing
   * whose H.401 is 0): both surfaces render the count only through its share,
   * so a predicate that admitted it promised a section neither could fill.
   */
  val hasFact: Boolean get() = averageDebtUsd != null || (borrowers as? BorrowerCounts.Counted)?.share != null
}

/**
 * How many of a graduating class borrowed one kind of loan, and how large that
 * class was -- ONE value (RFC 175, the [FullyMetNeedCounts] rule).
 *
 * The pair travels together by construction because the share is what a family
 * hears, and a share with no denominator beside it is the figure this codebase
 * refuses to publish. A borrower count with no class size still yields the
 * AVERAGE this loan type is about; it simply yields no share, and the type says
 * so by being absent rather than by a renderer remembering to check.
 *
 * A SEALED type rather than a pair, because the source publishes a third state
 * as well as reporting and not reporting: 14 of 249 live filings say more
 * students borrowed than graduated. That is the school's own contradiction, so
 * it is an OUTCOME this read returns and not an error it raises -- one such
 * filing must not deny a whole college list its price answer, which is exactly
 * what a throw inside this batched read did (the figures sit BESIDE the price
 * conversation, brief 0003, and may not take it away).
 */
sealed interface BorrowerCounts {
  /** The count and the class it is a share OF, consistent with each other. */
  data class Counted(
    val borrowers: Int,
    val graduatingClass: Int,
  ) : BorrowerCounts {
    val share: Share? = Share.ofOrNull(part = borrowers, whole = graduatingClass)
  }

  /**
   * This filing reports more borrowers of this loan type than graduates, so at
   * least one of the two cells is mis-extracted and NEITHER is spoken: no
   * share, no count, and nothing said to the family about the arithmetic of a
   * filing. The loan type's AVERAGE still stands -- that figure does not divide
   * by this denominator (D6).
   *
   * The ingest drops such a filing's block at fetch time; this is the state for
   * a pair that reached the store anyway.
   */
  data class ContradictsGraduatingClass(
    val borrowers: Int,
    val graduatingClass: Int,
  ) : BorrowerCounts
}
