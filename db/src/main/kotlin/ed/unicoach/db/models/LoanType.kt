package ed.unicoach.db.models

/**
 * One kind of loan a student can graduate owing, and WHERE the canonical store
 * holds each figure about it (RFC 175).
 *
 * The store has no loan-type axis and gains none (D1): loan type is part of the
 * MEASURE for an average, and part of the POPULATION slug for a headcount,
 * because `cohort_population_counts`' natural key carries no measure column.
 * That leaves the loan type spelled in two vocabularies at once, and this enum
 * is the ONE place the two halves are tied together -- the read that narrows on
 * a population and the read that narrows on a measure ask the same member, so
 * they cannot come to disagree about which loan type they are speaking about.
 *
 * [aidScope] is the DENOMINATOR of [debtAverage], never a convenient constant:
 * every average is a mean over the borrowers OF THIS LOAN TYPE, which is the
 * 0085 rule and the defect RFC 148, 162 and 170 each hit. Two of the five
 * scopes already existed and mean exactly the right set, so they are reused
 * (D4).
 *
 * The members are NOT summable and nothing here invites it: [ANY] is the
 * school's own reported any-loan figure, not our addition of the other four
 * (D3). The four typed sets overlap -- a student may borrow federally and
 * privately -- so adding them would count that student twice.
 */
enum class LoanType(
  /**
   * This loan type's name in the vocabularies that must SPELL one out: the
   * seed's `fact` column (`federal_loan_debt_avg_usd`) and the coach payload's
   * keys (`federal_loan_average_debt_usd`).
   *
   * A NAME FRAGMENT, and NOT a stored value -- unlike every other `value` in
   * this package. The store has no loan-type column (D1): a loan type reaches
   * it inside [debtAverage] or [borrowers], so a reader who takes this for the
   * persisted slug will go looking for a column that does not exist.
   */
  val slug: String,
  /** The average cumulative principal borrowed by graduation, for this loan type. */
  val debtAverage: MoneyMeasure,
  /** The set that average divides by: the graduating class members who took THIS kind of loan. */
  val aidScope: CohortAidScope,
  /** The cohort a borrower HEADCOUNT for this loan type is filed under. */
  val borrowers: CohortPopulation,
) {
  /** Any loan at all -- federal, institutional, state or private (CDS H.501 / H.511). */
  ANY(
    "any_loan",
    MoneyMeasure.ANY_LOAN_DEBT_AVERAGE,
    CohortAidScope.LOAN_RECEIVING,
    CohortPopulation.GRADUATING_CLASS_BORROWERS_ANY_LOAN,
  ),

  /** Federal loans (CDS H.502 / H.512). */
  FEDERAL(
    "federal_loan",
    MoneyMeasure.FEDERAL_LOAN_DEBT_AVERAGE,
    CohortAidScope.FEDERAL_LOAN_BORROWING,
    CohortPopulation.GRADUATING_CLASS_BORROWERS_FEDERAL_LOAN,
  ),

  /** Loans made by the school itself (CDS H.503 / H.513). */
  INSTITUTIONAL(
    "institutional_loan",
    MoneyMeasure.INSTITUTIONAL_LOAN_DEBT_AVERAGE,
    CohortAidScope.INSTITUTIONAL_LOAN_BORROWING,
    CohortPopulation.GRADUATING_CLASS_BORROWERS_INSTITUTIONAL_LOAN,
  ),

  /** State loan programs (CDS H.504 / H.514). */
  STATE(
    "state_loan",
    MoneyMeasure.STATE_LOAN_DEBT_AVERAGE,
    CohortAidScope.STATE_LOAN_BORROWING,
    CohortPopulation.GRADUATING_CLASS_BORROWERS_STATE_LOAN,
  ),

  /**
   * Private loans, with none of the federal protections and no income-driven
   * repayment (CDS H.505 / H.515) -- the half of the debt picture the
   * Scorecard's federal-only median cannot see, and the reason this slice
   * exists.
   */
  PRIVATE(
    "private_loan",
    MoneyMeasure.PRIVATE_LOAN_DEBT_AVERAGE,
    CohortAidScope.PRIVATE_LOAN_BORROWING,
    CohortPopulation.GRADUATING_CLASS_BORROWERS_PRIVATE_LOAN,
  ),
  ;

  companion object {
    /**
     * The measure of every borrowing average, for a read that narrows on
     * measures.
     *
     * SEALED by `buildList`, like its two siblings below: these are
     * process-wide singletons read by a SQL bind, the seed table and both
     * renderers, and `entries.map` answers an `ArrayList` a downcasting caller
     * could mutate for every request in the process.
     */
    val DEBT_AVERAGES: List<MoneyMeasure> = buildList { entries.forEach { add(it.debtAverage) } }

    /**
     * The populations a borrowing read counts, the graduating class FIRST: the
     * five borrower cohorts and the denominator they are all reported over.
     */
    val COUNTED_POPULATIONS: List<CohortPopulation> =
      buildList {
        add(CohortPopulation.GRADUATING_CLASS)
        entries.forEach { add(it.borrowers) }
      }

    /**
     * The order a family HEARS the loan types in: federal FIRST and private
     * beside it, because the point of a borrowing section is that the
     * federal-only figure published elsewhere is half the picture.
     *
     * Declared here, on the vocabulary, rather than at a renderer: the coach
     * payload and the parent-facing report page were each free to list the
     * five themselves, and they already disagreed -- the same filing narrated
     * in two different orders by two surfaces. DERIVED from [entries] rather
     * than written out, so a sixth loan type is spoken by every surface by
     * being declared, and cannot be silently dropped by whichever list forgot
     * it.
     */
    val SPOKEN_ORDER: List<LoanType> =
      buildList {
        add(FEDERAL)
        add(PRIVATE)
        entries.filterNot { it == FEDERAL || it == PRIVATE }.forEach { add(it) }
      }

    /** The loan type this measure belongs to, or null for a measure that is not one. */
    fun of(measure: MoneyMeasure): LoanType? = entries.find { it.debtAverage == measure }

    /** The loan type this borrower population counts, or null for a population that is not one. */
    fun ofBorrowers(population: CohortPopulation): LoanType? = entries.find { it.borrowers == population }
  }
}
