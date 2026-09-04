package ed.unicoach.coaching.aid

import ed.unicoach.coaching.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.PolicyParametersDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.PolicyParameter
import ed.unicoach.db.models.PolicyParameterRow
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Whether a topic's figures are the current award year's or a prior one's --
 * never silently the latter (RFC 159 D-G). A DATA fact only: the prior-year
 * sentence a family reads is coach copy, and coach copy lives at the tool edge
 * beside the rest of it ([FederalAidPolicyChatTool] renders it from the served
 * and current years, stated per group, never inferred from silence).
 */
sealed interface AwardYearCurrency {
  /** The wire code, the house code+statement convention. */
  val value: String

  data object Current : AwardYearCurrency {
    override val value: String = "current"
  }

  data object PriorYear : AwardYearCurrency {
    override val value: String = "prior_year"
  }
}

/**
 * The citation one parameter group carries: the SPOKEN document name straight
 * from `policy_parameters.source_name` and the primary FSA page it was
 * verified against -- rendered exactly the way CDS citations render
 * (`CdsCitation.putCitation`: `{cited_as, url}`), grouped per source document
 * rather than repeated per row (RFC 159 D-B).
 */
data class PolicySource(
  val citedAs: String,
  val url: String,
)

/** One annual Direct Loan line: the year level, the total cap, and the subsidized cap inside it. */
data class AnnualLoanLimit(
  val yearLevel: YearLevel,
  val totalUsd: Int,
  val subsidizedMaxUsd: Int,
)

/** The undergraduate year levels the federal loan-limit grid distinguishes. */
enum class YearLevel(
  val value: String,
) {
  FIRST_YEAR("first_year"),
  SECOND_YEAR("second_year"),
  THIRD_YEAR_AND_BEYOND("third_year_and_beyond"),
}

/** One dependency column of the undergraduate grid: the annual lines plus the aggregate caps. */
data class LoanTable(
  val annual: List<AnnualLoanLimit>,
  val aggregateTotalUsd: Int,
  val aggregateSubsidizedMaxUsd: Int,
)

/**
 * The Pell topic: awards, the SAI floor and the max-Pell AGI tests, resolved
 * to the latest verified award year. No estimate for any family is computed
 * here or anywhere -- the coach narrates eligibility and range from these
 * facts (RFC 159 D-D).
 */
data class PellParameters(
  val awardYear: AcademicYear,
  val currency: AwardYearCurrency,
  val maxAwardUsd: Int,
  val minAwardUsd: Int,
  val saiFloor: Int,
  val dependentSingleParentPct: Int,
  val dependentNonSingleParentPct: Int,
  val sources: List<PolicySource>,
)

/** The undergraduate Direct Loan topic: both dependency columns, so an unanswered dependency still gets a full answer. */
data class UndergradLoanParameters(
  val awardYear: AcademicYear,
  val currency: AwardYearCurrency,
  val dependent: LoanTable,
  val independent: LoanTable,
  val sources: List<PolicySource>,
)

/** The graduate Direct Loan topic: the annual unsubsidized cap and the aggregate caps. */
data class GradLoanParameters(
  val awardYear: AcademicYear,
  val currency: AwardYearCurrency,
  val annualUnsubsidizedUsd: Int,
  val aggregateTotalUsd: Int,
  val aggregateSubsidizedMaxUsd: Int,
  val sources: List<PolicySource>,
)

/** The student's dependency answer as the tool echoes it: the tri-state always, the value only when answered. */
data class DependencyAnswer(
  val status: AnswerStatus,
  val dependency: DependencyStatus?,
)

/** The whole federal-aid-policy read for one student (RFC 159). */
data class FederalAidPolicy(
  val currentAwardYear: AcademicYear,
  /** Absent (null) only when the store holds no servable rows for the topic -- never an empty shell. */
  val pell: PellParameters?,
  val undergradLoans: UndergradLoanParameters?,
  val gradLoans: GradLoanParameters?,
  val dependency: DependencyAnswer,
)

/**
 * Chat-free composition of the `policy_parameters` store (RFC 159): the
 * federal Pell and Direct Loan policy figures, resolved per topic to the
 * latest verified award year, with the award year's currency stated as a
 * first-class fact and the student's dependency answer beside it.
 *
 * Read-only, and it computes NOTHING with money: no SAI estimate, no Pell
 * amount, no loan-minus-anything. Every figure leaves exactly as it was
 * seeded; the coach narrates from the served facts at read time, and nothing
 * computed can therefore be stored (gate-1 never-store list). The
 * ForbiddenCostArithmetic scan covers this package to keep it that way.
 *
 * "Current" is derived from the injected [clock] through
 * [AcademicYear.currentFederalAwardYear]. A topic resolves to the LATEST
 * COMPLETE award year at or before the current one -- a year FSA has
 * published for the future is never served as this year's policy, and a year
 * seeded only in part never eclipses the last complete one -- and when the
 * served year is behind the current one, the group says so in a sentence
 * (RFC 159 D-G). At launch the loan topics really are behind (Vol 8 of the
 * 2026-27 Handbook is unpublished), so the mechanism ships exercised.
 */
class FederalAidPolicyService(
  private val database: Database,
  private val clock: Clock = Clock.systemUTC(),
) {
  suspend fun getForStudent(studentId: StudentId): Result<FederalAidPolicy> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed
      // (CollegeAdmissionsService's rule).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session, extracted so the batching contract is
   * assertable: one statement for the policy store, one for the money profile,
   * whatever the store holds. [getForStudent] is this function plus the
   * connection and the `Result` wrapper, and nothing else.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
  ): FederalAidPolicy {
    val rows = PolicyParametersDao.listAll(session).getOrThrow()
    val dependency = dependencyOf(session, studentId)
    val current = currentAwardYear()

    return FederalAidPolicy(
      currentAwardYear = current,
      pell = pellOf(rows, current),
      undergradLoans = undergradLoansOf(rows, current),
      gradLoans = gradLoansOf(rows, current),
      dependency = dependency,
    )
  }

  /** The award year running at [clock]'s instant -- the July 1 rule, owned by [AcademicYear]. */
  internal fun currentAwardYear(): AcademicYear = AcademicYear.currentFederalAwardYear(clock)

  private fun dependencyOf(
    session: SqlSession,
    studentId: StudentId,
  ): DependencyAnswer {
    val result = MoneyProfilesDao.findActiveByStudent(session, studentId)
    return when {
      result.isSuccess -> {
        val profile = result.getOrThrow()
        DependencyAnswer(status = profile.dependencyStatus, dependency = profile.dependency)
      }

      result.exceptionOrNull() is NotFoundException -> {
        DependencyAnswer(status = AnswerStatus.UNANSWERED, dependency = null)
      }

      else -> {
        throw result.exceptionOrNull()!!
      }
    }
  }

  /** One topic group resolved: its rows keyed by parameter, and the award-year facts every topic states (RFC 159 D-G). */
  private data class ResolvedGroup(
    val byParameter: Map<PolicyParameter, PolicyParameterRow>,
    val served: AcademicYear,
    val currency: AwardYearCurrency,
    val sources: List<PolicySource>,
  )

  private fun resolveGroup(
    topic: String,
    rows: List<PolicyParameterRow>,
    group: Set<PolicyParameter>,
    current: AcademicYear,
  ): ResolvedGroup? {
    val byParameter = latestServedYear(topic, rows, group, current) ?: return null
    val served = byParameter.values.first().year
    return ResolvedGroup(
      byParameter = byParameter,
      served = served,
      currency = currencyOf(served, current),
      sources = sourcesOf(byParameter.values),
    )
  }

  /**
   * The rows of [group]'s latest servable award year: the greatest award year
   * at or before [current] carrying EVERY member of the group. A year seeded
   * only in part -- FSA publishes the Pell DCL months before the AVG chapters
   * -- must not eclipse the last complete year, and a year published only for
   * the FUTURE is deliberately not served: next year's policy is not this
   * year's fact. A group with no complete servable year resolves to null
   * rather than an empty shell.
   */
  private fun latestServedYear(
    topic: String,
    rows: List<PolicyParameterRow>,
    group: Set<PolicyParameter>,
    current: AcademicYear,
  ): Map<PolicyParameter, PolicyParameterRow>? {
    val groupRows = rows.filter { it.parameter in group }
    val servableRows = groupRows.filter { it.year <= current }
    val latestComplete =
      servableRows
        .groupBy { it.awardYear }
        .filterValues { yearRows -> yearRows.map { it.parameter }.containsAll(group) }
        .maxByOrNull { it.key }
        ?.value
    if (latestComplete == null) {
      // An omitted topic is an operator problem, not a family one: say WHICH
      // absence this is, because "seed the missing rows" and "wait for FSA to
      // publish" are different fixes.
      val reason =
        when {
          groupRows.isEmpty() -> "the store has no rows for it"
          servableRows.isEmpty() -> "the store holds only future award years for it"
          else -> "no award year at or before ${current.label} carries every parameter of the group"
        }
      logger.warn("serving no [{}] topic: [{}]", topic, reason)
      return null
    }
    return latestComplete.associateBy { it.parameter }
  }

  /** The [AwardYearCurrency] of a served year; the sentence it earns is rendered at the tool edge. */
  private fun currencyOf(
    served: AcademicYear,
    current: AcademicYear,
  ): AwardYearCurrency = if (served == current) AwardYearCurrency.Current else AwardYearCurrency.PriorYear

  /** The distinct source documents behind [rows], in first-appearance order -- one citation per document, never per row. */
  private fun sourcesOf(rows: Collection<PolicyParameterRow>): List<PolicySource> =
    rows.map { PolicySource(citedAs = it.sourceName, url = it.sourceUrl) }.distinct()

  private fun pellOf(
    rows: List<PolicyParameterRow>,
    current: AcademicYear,
  ): PellParameters? {
    val resolved = resolveGroup("pell", rows, PELL_GROUP, current) ?: return null
    // getValue, not get: latestServedYear only returns a year carrying EVERY
    // member of the group, so a missing key is a broken invariant to throw on,
    // never a topic to silently drop.
    val byParameter = resolved.byParameter
    return PellParameters(
      awardYear = resolved.served,
      currency = resolved.currency,
      maxAwardUsd = byParameter.getValue(PolicyParameter.PELL_MAX_AWARD_USD).value,
      minAwardUsd = byParameter.getValue(PolicyParameter.PELL_MIN_AWARD_USD).value,
      saiFloor = byParameter.getValue(PolicyParameter.SAI_FLOOR).value,
      dependentSingleParentPct =
        byParameter.getValue(PolicyParameter.PELL_MAX_AGI_DEPENDENT_SINGLE_PARENT_PCT).value,
      dependentNonSingleParentPct =
        byParameter.getValue(PolicyParameter.PELL_MAX_AGI_DEPENDENT_NON_SINGLE_PARENT_PCT).value,
      sources = resolved.sources,
    )
  }

  private fun undergradLoansOf(
    rows: List<PolicyParameterRow>,
    current: AcademicYear,
  ): UndergradLoanParameters? {
    val resolved = resolveGroup("undergrad_loans", rows, UNDERGRAD_LOAN_GROUP, current) ?: return null
    return UndergradLoanParameters(
      awardYear = resolved.served,
      currency = resolved.currency,
      dependent = loanTableOf(resolved.byParameter, DEPENDENT_TABLE),
      independent = loanTableOf(resolved.byParameter, INDEPENDENT_TABLE),
      sources = resolved.sources,
    )
  }

  /** The eight parameters that make one dependency column of the undergraduate grid. */
  private data class LoanTableParameters(
    val y1Total: PolicyParameter,
    val y1Subsidized: PolicyParameter,
    val y2Total: PolicyParameter,
    val y2Subsidized: PolicyParameter,
    val y3PlusTotal: PolicyParameter,
    val y3PlusSubsidized: PolicyParameter,
    val aggregateTotal: PolicyParameter,
    val aggregateSubsidized: PolicyParameter,
  )

  /**
   * One dependency column built from its eight parameters -- the grid's shape
   * stated once for both columns. `getValue` because the resolved year is
   * complete by construction; a missing member is a broken invariant, never a
   * silently smaller table.
   */
  private fun loanTableOf(
    byParameter: Map<PolicyParameter, PolicyParameterRow>,
    parameters: LoanTableParameters,
  ): LoanTable {
    fun value(parameter: PolicyParameter): Int = byParameter.getValue(parameter).value
    return LoanTable(
      annual =
        listOf(
          AnnualLoanLimit(YearLevel.FIRST_YEAR, value(parameters.y1Total), value(parameters.y1Subsidized)),
          AnnualLoanLimit(YearLevel.SECOND_YEAR, value(parameters.y2Total), value(parameters.y2Subsidized)),
          AnnualLoanLimit(
            YearLevel.THIRD_YEAR_AND_BEYOND,
            value(parameters.y3PlusTotal),
            value(parameters.y3PlusSubsidized),
          ),
        ),
      aggregateTotalUsd = value(parameters.aggregateTotal),
      aggregateSubsidizedMaxUsd = value(parameters.aggregateSubsidized),
    )
  }

  private fun gradLoansOf(
    rows: List<PolicyParameterRow>,
    current: AcademicYear,
  ): GradLoanParameters? {
    val resolved = resolveGroup("grad_loans", rows, GRAD_LOAN_GROUP, current) ?: return null
    val byParameter = resolved.byParameter
    return GradLoanParameters(
      awardYear = resolved.served,
      currency = resolved.currency,
      annualUnsubsidizedUsd = byParameter.getValue(PolicyParameter.DIRECT_LOAN_ANNUAL_GRAD_UNSUBSIDIZED_USD).value,
      aggregateTotalUsd = byParameter.getValue(PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_TOTAL_USD).value,
      aggregateSubsidizedMaxUsd = byParameter.getValue(PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_SUBSIDIZED_USD).value,
      sources = resolved.sources,
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(FederalAidPolicyService::class.java)

    /**
     * The row's award year AS the domain type, wrapped at the read boundary so
     * every comparison and label downstream speaks [AcademicYear], never a bare
     * `Int` start-year convention.
     */
    private val PolicyParameterRow.year: AcademicYear
      get() = AcademicYear(awardYear)

    /** The parameters the `pell` topic serves. */
    internal val PELL_GROUP =
      setOf(
        PolicyParameter.PELL_MAX_AWARD_USD,
        PolicyParameter.PELL_MIN_AWARD_USD,
        PolicyParameter.PELL_MAX_AGI_DEPENDENT_SINGLE_PARENT_PCT,
        PolicyParameter.PELL_MAX_AGI_DEPENDENT_NON_SINGLE_PARENT_PCT,
        PolicyParameter.SAI_FLOOR,
      )

    /** The parameters the `undergrad_loans` topic serves. */
    internal val UNDERGRAD_LOAN_GROUP =
      setOf(
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_SUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_SUBSIDIZED_USD,
      )

    /** The parameters the `grad_loans` topic serves. */
    internal val GRAD_LOAN_GROUP =
      setOf(
        PolicyParameter.DIRECT_LOAN_ANNUAL_GRAD_UNSUBSIDIZED_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_TOTAL_USD,
        PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_SUBSIDIZED_USD,
      )

    /** The dependent column of the undergraduate grid, its eight parameters named once. */
    private val DEPENDENT_TABLE =
      LoanTableParameters(
        y1Total = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_TOTAL_USD,
        y1Subsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_SUBSIDIZED_USD,
        y2Total = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_TOTAL_USD,
        y2Subsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_SUBSIDIZED_USD,
        y3PlusTotal = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_TOTAL_USD,
        y3PlusSubsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_SUBSIDIZED_USD,
        aggregateTotal = PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_TOTAL_USD,
        aggregateSubsidized = PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_SUBSIDIZED_USD,
      )

    /** The independent column, likewise. */
    private val INDEPENDENT_TABLE =
      LoanTableParameters(
        y1Total = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_TOTAL_USD,
        y1Subsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_SUBSIDIZED_USD,
        y2Total = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_TOTAL_USD,
        y2Subsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_SUBSIDIZED_USD,
        y3PlusTotal = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_TOTAL_USD,
        y3PlusSubsidized = PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_SUBSIDIZED_USD,
        aggregateTotal = PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_TOTAL_USD,
        aggregateSubsidized = PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_SUBSIDIZED_USD,
      )
  }
}
