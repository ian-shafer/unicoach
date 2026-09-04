package ed.unicoach.coaching.aid

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putDependency
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

/**
 * The `federal_aid_policy` chat tool (RFC 159): the coach's read of the
 * federal Pell Grant and Direct Loan policy parameters, resolved per topic to
 * the latest verified award year, with per-document citations and the
 * student's dependency answer.
 *
 * A thin adapter by design: [execute] only orchestrates read -> render; every
 * composition rule (latest-year resolution, the July 1 boundary, the
 * prior-year sentence, the dependency echo) lives in [FederalAidPolicyService].
 * It takes NO input at all -- federal policy is the same at every college and
 * for every student; only the narration differs.
 *
 * House payload conventions throughout: absent keys, never empty shells;
 * code+statement pairs (`award_year_status` rides with `award_year_statement`
 * whenever it is `prior_year`); the tri-state dependency echo names its status
 * always and its value only when answered -- never infer a fact from a
 * silence.
 */
class FederalAidPolicyChatTool(
  private val service: FederalAidPolicyService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    if (input.isNotEmpty()) {
      return errorObject(unknownFieldsReason(input, emptySet())!!)
    }

    val policy =
      service
        .getForStudent(studentId)
        .getOrElse { e ->
          logger.warn("tool [{}] federal aid policy read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("federal aid policy read failed")
        }

    return policyObject(policy)
  }

  private fun policyObject(policy: FederalAidPolicy): JsonObject =
    buildJsonObject {
      put(CURRENT_AWARD_YEAR_KEY, policy.currentAwardYear.label)
      policy.pell?.let { put(PELL_KEY, pellObject(it, policy.currentAwardYear)) }
      policy.undergradLoans?.let { put(UNDERGRAD_LOANS_KEY, undergradLoansObject(it, policy.currentAwardYear)) }
      policy.gradLoans?.let { put(GRAD_LOANS_KEY, gradLoansObject(it, policy.currentAwardYear)) }
      put(MONEY_PROFILE_KEY, dependencyObject(policy.dependency))
      put(SOURCE_NOTE_KEY, SOURCE_NOTE)
    }

  /** The award-year facts every topic carries: the year label, its currency code, and the prior-year sentence when it applies. */
  private fun JsonObjectBuilder.putAwardYear(
    awardYear: ed.unicoach.coaching.AcademicYear,
    currency: AwardYearCurrency,
    current: ed.unicoach.coaching.AcademicYear,
  ) {
    put(AWARD_YEAR_KEY, awardYear.label)
    put(AWARD_YEAR_STATUS_KEY, currency.value)
    if (currency is AwardYearCurrency.PriorYear) put(AWARD_YEAR_STATEMENT_KEY, priorYearStatement(awardYear, current))
  }

  /** The per-document citations, CdsCitation's `{cited_as, url}` shape -- absent is impossible here, empty never emitted. */
  private fun JsonObjectBuilder.putSources(sources: List<PolicySource>) {
    if (sources.isEmpty()) return
    putJsonArray(SOURCES_KEY) {
      sources.forEach { source ->
        add(
          buildJsonObject {
            put("cited_as", source.citedAs)
            put("url", source.url)
          },
        )
      }
    }
  }

  private fun pellObject(
    pell: PellParameters,
    current: ed.unicoach.coaching.AcademicYear,
  ): JsonObject =
    buildJsonObject {
      putAwardYear(pell.awardYear, pell.currency, current)
      put(PELL_MAX_AWARD_KEY, pell.maxAwardUsd)
      put(PELL_MIN_AWARD_KEY, pell.minAwardUsd)
      put(SAI_FLOOR_KEY, pell.saiFloor)
      put(MAX_PELL_AGI_TESTS_KEY, maxPellAgiTestsObject(pell))
      put(FRAMING_STATEMENT_KEY, PELL_FRAMING_STATEMENT)
      putSources(pell.sources)
    }

  /** The max-Pell AGI tests: the two served percents and the sentence built from them. */
  private fun maxPellAgiTestsObject(pell: PellParameters): JsonObject =
    buildJsonObject {
      put(AGI_SINGLE_PARENT_KEY, pell.dependentSingleParentPct)
      put(AGI_NON_SINGLE_PARENT_KEY, pell.dependentNonSingleParentPct)
      put(STATEMENT_KEY, maxPellAgiTestStatement(pell.dependentSingleParentPct, pell.dependentNonSingleParentPct))
    }

  private fun undergradLoansObject(
    loans: UndergradLoanParameters,
    current: ed.unicoach.coaching.AcademicYear,
  ): JsonObject =
    buildJsonObject {
      putAwardYear(loans.awardYear, loans.currency, current)
      put(DEPENDENT_KEY, loanTableObject(loans.dependent))
      put(INDEPENDENT_KEY, loanTableObject(loans.independent))
      put(LOAN_FRAMING_STATEMENT_KEY, LOAN_FRAMING_STATEMENT)
      putSources(loans.sources)
    }

  private fun loanTableObject(table: LoanTable): JsonObject =
    buildJsonObject {
      put(ANNUAL_KEY, buildJsonArray { table.annual.forEach { add(annualLoanLimitObject(it)) } })
      put(AGGREGATE_TOTAL_KEY, table.aggregateTotalUsd)
      put(AGGREGATE_SUBSIDIZED_MAX_KEY, table.aggregateSubsidizedMaxUsd)
    }

  /** One annual grid line: the year level and its two caps. */
  private fun annualLoanLimitObject(line: AnnualLoanLimit): JsonObject =
    buildJsonObject {
      put(YEAR_LEVEL_KEY, line.yearLevel.value)
      put(TOTAL_KEY, line.totalUsd)
      put(SUBSIDIZED_MAX_KEY, line.subsidizedMaxUsd)
    }

  private fun gradLoansObject(
    loans: GradLoanParameters,
    current: ed.unicoach.coaching.AcademicYear,
  ): JsonObject =
    buildJsonObject {
      putAwardYear(loans.awardYear, loans.currency, current)
      put(GRAD_ANNUAL_UNSUBSIDIZED_KEY, loans.annualUnsubsidizedUsd)
      put(AGGREGATE_TOTAL_KEY, loans.aggregateTotalUsd)
      put(AGGREGATE_SUBSIDIZED_MAX_KEY, loans.aggregateSubsidizedMaxUsd)
      put(LOAN_FRAMING_STATEMENT_KEY, LOAN_FRAMING_STATEMENT)
      putSources(loans.sources)
    }

  /** The tri-state dependency echo: the status always, the value (with its spoken label) only when answered. */
  private fun dependencyObject(answer: DependencyAnswer): JsonObject =
    buildJsonObject {
      put(DEPENDENCY_STATUS_KEY, answer.status.value)
      answer.dependency?.let { putDependency(it) }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(FederalAidPolicyChatTool::class.java)

    const val TOOL_NAME = "federal_aid_policy"

    // Wire keys as consts because the DESCRIPTION quotes them (the
    // CollegeCostChatTool precedent): the prompt copy the model reads and the
    // payload it receives can never drift apart.
    const val CURRENT_AWARD_YEAR_KEY = "current_award_year"
    const val PELL_KEY = "pell"
    const val UNDERGRAD_LOANS_KEY = "undergrad_loans"
    const val GRAD_LOANS_KEY = "grad_loans"
    const val MONEY_PROFILE_KEY = "money_profile"
    const val SOURCE_NOTE_KEY = "source_note"
    const val AWARD_YEAR_KEY = "award_year"
    const val AWARD_YEAR_STATUS_KEY = "award_year_status"
    const val AWARD_YEAR_STATEMENT_KEY = "award_year_statement"
    const val SOURCES_KEY = "sources"
    const val STATEMENT_KEY = "statement"
    const val PELL_MAX_AWARD_KEY = "max_award_usd"
    const val PELL_MIN_AWARD_KEY = "min_award_usd"
    const val SAI_FLOOR_KEY = "sai_floor"
    const val MAX_PELL_AGI_TESTS_KEY = "max_pell_agi_tests"
    const val AGI_SINGLE_PARENT_KEY = "dependent_single_parent_pct_of_poverty_guideline"
    const val AGI_NON_SINGLE_PARENT_KEY = "dependent_non_single_parent_pct_of_poverty_guideline"
    const val FRAMING_STATEMENT_KEY = "framing_statement"
    const val LOAN_FRAMING_STATEMENT_KEY = "loan_framing_statement"
    const val DEPENDENT_KEY = "dependent"
    const val INDEPENDENT_KEY = "independent"
    const val ANNUAL_KEY = "annual"
    const val YEAR_LEVEL_KEY = "year_level"
    const val TOTAL_KEY = "total_usd"
    const val SUBSIDIZED_MAX_KEY = "subsidized_max_usd"
    const val AGGREGATE_TOTAL_KEY = "aggregate_total_usd"
    const val AGGREGATE_SUBSIDIZED_MAX_KEY = "aggregate_subsidized_max_usd"
    const val GRAD_ANNUAL_UNSUBSIDIZED_KEY = "annual_unsubsidized_usd"
    const val DEPENDENCY_STATUS_KEY = "dependency_status"

    // The coach-facing copy lives HERE, beside DESCRIPTION: the service
    // resolves policy facts; this tool owns everything the model reads.

    /**
     * The Pell framing the coach speaks (RFC 159 D-D): eligibility and range,
     * never a promised amount. A const so the prompt copy and the payload can
     * never drift apart.
     */
    const val PELL_FRAMING_STATEMENT =
      "Pell eligibility is set by the FAFSA's Student Aid Index and income tests; " +
        "the coach can describe the range, never promise a specific amount."

    /** The loan framing (brief 0003): a loan is repaid, and a limit is a cap, never a discount. */
    const val LOAN_FRAMING_STATEMENT =
      "A loan is money the family repays with interest; loan limits are caps on borrowing and are " +
        "never subtracted from any price."

    /** Why no college id rides this tool: federal policy is the same at every college. */
    const val SOURCE_NOTE =
      "Figures are federal policy for the named award year; they are the same at every college."

    /**
     * The prior-year sentence a group earns when its served year trails the
     * current one -- coach copy, so it lives here beside the rest, built from
     * the two years it glosses (RFC 159 D-G: never silently served).
     */
    fun priorYearStatement(
      served: ed.unicoach.coaching.AcademicYear,
      current: ed.unicoach.coaching.AcademicYear,
    ): String =
      "These are the ${served.label} award year figures; Federal Student Aid has not yet published " +
        "figures we have verified for the ${current.label} award year."

    /**
     * The max-Pell AGI tests said as one sentence, built from the SERVED
     * percent figures -- the rows own the numbers, so a corrected seed can
     * never contradict its own gloss.
     */
    fun maxPellAgiTestStatement(
      dependentSingleParentPct: Int,
      dependentNonSingleParentPct: Int,
    ): String =
      "A dependent student qualifies for the maximum Pell Grant if the parents were not required to " +
        "file a federal tax return, or if the parents' income is at or below $dependentSingleParentPct% " +
        "(single parent) or $dependentNonSingleParentPct% (other parents) of the federal poverty " +
        "guideline for the family's size and state."

    /**
     * What the model is told, built from the very consts the payload is keyed
     * by. The dependency invitation names its value (RFC 159 D-F: value before
     * ask) and the answer NEVER waits on it -- both tables are always served.
     */
    const val DESCRIPTION =
      "Federal Pell Grant and Direct Loan policy parameters from Federal Student Aid: the Pell maximum " +
        "and minimum, the Student Aid Index floor, the maximum-Pell income tests, and the Direct Loan " +
        "annual and aggregate limits. These are federal policy, identical at every college, so the tool " +
        "takes no input. Use it whenever Pell Grants, federal loans, borrowing limits or the FAFSA's aid " +
        "outcome come up. Every topic ($PELL_KEY, $UNDERGRAD_LOANS_KEY, $GRAD_LOANS_KEY) carries its " +
        "$AWARD_YEAR_KEY and per-document $SOURCES_KEY: always say which award year a figure is for and " +
        "attribute it to the cited source. When a topic's $AWARD_YEAR_STATUS_KEY is prior_year, say its " +
        "$AWARD_YEAR_STATEMENT_KEY plainly rather than presenting the figures as this year's. Frame Pell " +
        "as eligibility and a range, never a promised amount, and state loan limits as caps the family " +
        "would repay with interest -- never take a loan or a loan limit off any price. The " +
        "$MONEY_PROFILE_KEY block echoes whether the student is dependent or independent for federal " +
        "aid: when $DEPENDENCY_STATUS_KEY is unanswered you MAY invite that answer once, saying what it " +
        "unlocks (most students applying from high school are dependent for federal aid; knowing which " +
        "applies picks the right loan limits), but MUST still answer fully without it by presenting both " +
        "the $DEPENDENT_KEY and $INDEPENDENT_KEY tables; when it is declined, present both tables and " +
        "never raise the question again yourself."
  }
}
