package ed.unicoach.family

import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.NetPriceBasis
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.common.util.WholeCount
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeLevel
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.INCOME_BAND_LABEL_KEY
import ed.unicoach.db.models.putIncomeBand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

/**
 * `family_money_plan` (RFC 261): what each school on the student's list costs
 * over all its years, for a supporter's coach -- the arithmetic done here, so
 * the model never multiplies, adds or estimates a figure itself.
 *
 * It reads the cost picture through [SubjectReadService.costPicture], the seam
 * `family_cost_picture` reads through, so the live link is re-checked on every
 * call; and it reads each listed school's level from `college_ipeds`, which is
 * reference data about the school and not the student's, so that read does not
 * go through the seam. [MoneyPlan.of] turns the two into the plan, and this
 * tool only renders it.
 *
 * Takes no argument: the subject is the conversation's, injected by the
 * dispatcher (brief 0014 D15). It writes nothing.
 */
class FamilyMoneyPlanChatTool(
  private val subjects: SubjectReadService,
  private val database: Database,
) : SubjectScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    subject: Subject,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val read =
      subjects.costPicture(subject.viewer, subject.studentId).getOrElse { e ->
        logReadFailure(subject, "cost", e)
        return errorObject(READ_FAILED_REASON)
      }
    val picture =
      when (read) {
        SubjectRead.NotFound -> return studentNotFound()
        is SubjectRead.Found -> read.value
      }
    val profile = profileOf(picture)
    val collegeIds = profile.colleges.map { it.collegeId }
    val levels =
      levelsOf(collegeIds).getOrElse { e ->
        logReadFailure(subject, "level (college_ids=${collegeIds.map { it.value }})", e)
        return errorObject(READ_FAILED_REASON)
      }
    return payloadOf(MoneyPlan.of(profile, levels))
  }

  /** The list's cost profile, whichever sharing case the picture is in: the plan reads only the colleges and publishers. */
  private fun profileOf(picture: SharedCostPicture): CollegeCostProfile =
    when (picture) {
      is SharedCostPicture.Full -> picture.profile
      is SharedCostPicture.SubjectsWithheld -> picture.profile
    }

  /** Each listed school's level, in one query; reference data, read outside the subject seam. */
  private suspend fun levelsOf(collegeIds: List<CollegeId>): Result<Map<CollegeId, CollegeLevel>> =
    database.withConnectionCatching { session -> CollegeIpedsDao.levelsByCollegeId(session, collegeIds).getOrThrow() }

  private fun logReadFailure(
    subject: Subject,
    what: String,
    e: Throwable,
  ) {
    logger.warn(
      "tool [{}] {} read failed for viewer=[{}] student=[{}]",
      TOOL_NAME,
      what,
      subject.viewer.asString,
      subject.studentId.asString,
      e,
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(FamilyMoneyPlanChatTool::class.java)

    const val TOOL_NAME = "family_money_plan"

    const val READ_FAILED_REASON = "family money plan read failed"

    const val COLLEGES_KEY = "colleges"
    const val YEARS_KEY = "years"
    const val PUBLISHED_PRICE_KEY = "published_price"
    const val PUBLISHED_PRICE_AVAILABILITY_KEY = "published_price_availability"
    const val PRICE_AFTER_GRANTS_KEY = "price_after_grants"
    const val PRICE_AFTER_GRANTS_AVAILABILITY_KEY = "price_after_grants_availability"
    const val ALL_YEARS_AVAILABILITY_KEY = "all_years_availability"
    const val WAY_OF_LIVING_KEY = "way_of_living"
    const val CHOSEN_KEY = "chosen"
    const val PER_YEAR_KEY = "per_year_usd"
    const val ALL_YEARS_KEY = "all_years_usd"
    const val PROVENANCE_KEY = "provenance"
    const val ALL_YEARS_STATEMENT_KEY = "all_years_statement"

    /** Said once per payload: whose every all-years figure is, and what it leaves out. */
    val ALL_YEARS_STATEMENT =
      "Every $ALL_YEARS_KEY is ours, not a figure any school publishes: the one-year figure beside it times the " +
        "years, at that one year's prices. Many students take longer than " +
        "${WholeCount.inWords(MoneyPlan.FOUR_YEAR_SCHOOL_YEARS)} years, and prices usually rise from year to year."

    /** The plan as the coach reads it: one entry per school in the list's order, the statement, the publishers. */
    fun payloadOf(plan: MoneyPlan): JsonObject =
      buildJsonObject {
        putJsonArray(COLLEGES_KEY) { plan.colleges.forEach { add(collegeObject(it)) } }
        put(ALL_YEARS_STATEMENT_KEY, ALL_YEARS_STATEMENT)
        // ABSENT, never empty: a list with no money figure has no publisher to name.
        MoneySourceCopy.spokenListOf(plan.sources)?.let { put(CollegeCostChatTool.SOURCE_KEY, it) }
      }

    private fun collegeObject(entry: MoneyPlanCollege): JsonObject =
      buildJsonObject {
        val college = entry.college
        put(CollegeCostChatTool.COLLEGE_ID_KEY, college.collegeId.value.toString())
        put(CollegeCostChatTool.COLLEGE_NAME_KEY, college.name)
        // Exhaustive with no `else`: a school either carries its figures or the
        // reason it has none, never both and never neither.
        when (entry) {
          is MoneyPlanCollege.Totalled -> {
            put(YEARS_KEY, entry.years)
            putPublishedPrice(entry)
            putPriceAfterGrants(entry)
          }

          is MoneyPlanCollege.NotTotalled -> {
            with(CollegeCostChatTool) {
              putAvailability(ALL_YEARS_AVAILABILITY_KEY, entry.reason.value, entry.reason.statement)
            }
          }
        }
      }

    private fun JsonObjectBuilder.putPublishedPrice(school: MoneyPlanCollege.Totalled) {
      // `chosen` is said by the CASE, never carried beside it: the one total of
      // a chosen plan is the family's, and every total listed because they
      // chose none is not.
      when (val published = school.publishedPrice) {
        is PublishedPricePlan.Chosen -> {
          putJsonArray(PUBLISHED_PRICE_KEY) { add(arrangementObject(school, published.total, chosen = true)) }
        }

        is PublishedPricePlan.EveryWayOfLiving -> {
          putJsonArray(PUBLISHED_PRICE_KEY) {
            published.totals.forEach { add(arrangementObject(school, it, chosen = false)) }
          }
        }

        is PublishedPricePlan.NoTotal -> {
          with(CollegeCostChatTool) {
            putAvailability(PUBLISHED_PRICE_AVAILABILITY_KEY, published.pricing.value, published.statement)
          }
        }
      }
    }

    private fun arrangementObject(
      school: MoneyPlanCollege.Totalled,
      total: ArrangementAllYears,
      chosen: Boolean,
    ): JsonObject =
      buildJsonObject {
        put(WAY_OF_LIVING_KEY, total.arrangement.arrangement.value)
        put(CollegeCostChatTool.ARRANGEMENT_LABEL_KEY, total.arrangement.arrangement.label)
        put(CHOSEN_KEY, chosen)
        put(PER_YEAR_KEY, total.figure.perYearUsd)
        put(CollegeCostChatTool.ACADEMIC_YEAR_KEY, total.academicYear)
        putProvenance(total.provenance)
        // The at-home `$0` is OURS, named the way the cost picture names it.
        with(CollegeCostChatTool) { putAssumedByUnicoach(total.arrangement) }
        put(ALL_YEARS_KEY, school.allYearsUsdOf(total.figure))
      }

    private fun JsonObjectBuilder.putPriceAfterGrants(school: MoneyPlanCollege.Totalled) {
      when (val afterGrants = school.priceAfterGrants) {
        is PriceAfterGrantsPlan.Totalled -> {
          put(PRICE_AFTER_GRANTS_KEY, priceAfterGrantsObject(school, afterGrants))
        }

        // The basis is the CODE the sentence was derived from -- the store's
        // status, or the withheld reason -- never a code of ours typed beside
        // it, so the two can never disagree.
        is PriceAfterGrantsPlan.NotReported -> {
          with(CollegeCostChatTool) {
            putAvailability(PRICE_AFTER_GRANTS_AVAILABILITY_KEY, afterGrants.status.value, afterGrants.statement)
          }
        }

        is PriceAfterGrantsPlan.Withheld -> {
          with(CollegeCostChatTool) {
            putAvailability(PRICE_AFTER_GRANTS_AVAILABILITY_KEY, afterGrants.reason.value, afterGrants.reason.statement)
          }
        }
      }
    }

    private fun priceAfterGrantsObject(
      school: MoneyPlanCollege.Totalled,
      afterGrants: PriceAfterGrantsPlan.Totalled,
    ): JsonObject =
      buildJsonObject {
        put(PER_YEAR_KEY, afterGrants.figure.perYearUsd)
        // Absent when this school's blended rows disagree on a year: said
        // with no year rather than a wrong one, as the cost picture does.
        afterGrants.academicYear?.let { put(CollegeCostChatTool.ACADEMIC_YEAR_KEY, it) }
        put(CollegeCostChatTool.BASIS_KEY, afterGrants.netPrice.basis)
        // Exhaustive: an overall average deliberately carries no band.
        when (val netPrice = afterGrants.netPrice) {
          is NetPrice.BandSpecific -> putIncomeBand(netPrice.band)
          is NetPrice.OverallAverage -> Unit
        }
        with(CollegeCostChatTool) { putBlendedFiguresApply(afterGrants.applies) }
        putProvenance(afterGrants.provenance)
        put(ALL_YEARS_KEY, school.allYearsUsdOf(afterGrants.figure))
      }

    /** One entry per (tier, publisher) pair, through the cost picture's own emitter; absent, never empty. */
    private fun JsonObjectBuilder.putProvenance(provenance: List<FigureProvenancePair>) {
      if (provenance.isEmpty()) return
      putJsonArray(PROVENANCE_KEY) {
        provenance.forEach { add(CollegeCostChatTool.provenanceObjectOf(it.assurance, it.source)) }
      }
    }

    /**
     * Every code a valueless net price's basis can be: the statuses that carry
     * no value, each with the one gloss [FigureStatusCopy] owns.
     */
    private val NO_NET_PRICE_STATUS_VOCABULARY: String =
      FigureStatus.entries
        .filterNot { it.valueBearing }
        .joinToString(" | ") { "\"${it.value}\" (${FigureStatusCopy.legendGlossOf(it)})" }

    /** Every code a withheld net price's basis can be, from its owner. */
    private val WITHHELD_REASON_VOCABULARY: String = WithheldReason.entries.joinToString(" | ") { "\"${it.value}\"" }

    // How to READ each key (RFC 192); what to SAY lives in the parent prompt.
    val DESCRIPTION by lazy {
      "What each college on the student's list costs over all its years, worked out from the one-year figures " +
        "in the cost picture, so you never multiply, add or estimate a figure yourself. It takes no input; the " +
        "student is the one this conversation is about. It changes nothing. A legend of the payload's keys " +
        "follows, in the order they appear.\n" +
        "$COLLEGES_KEY - one entry per school on the list, each with ${CollegeCostChatTool.COLLEGE_ID_KEY} and " +
        "${CollegeCostChatTool.COLLEGE_NAME_KEY}, and then EITHER $YEARS_KEY with the figures below OR " +
        "$ALL_YEARS_AVAILABILITY_KEY and no dollar amount at all.\n" +
        "$YEARS_KEY - how many years every $ALL_YEARS_KEY at that school spans.\n" +
        "$PUBLISHED_PRICE_KEY - the published price for the way the family plans to live there, or one entry " +
        "per way of living the school prices when the family has chosen none: $WAY_OF_LIVING_KEY, " +
        "${CollegeCostChatTool.ARRANGEMENT_LABEL_KEY} (the words for it), $CHOSEN_KEY (true for the family's " +
        "chosen way of living), $PER_YEAR_KEY (the published one-year price), " +
        "${CollegeCostChatTool.ACADEMIC_YEAR_KEY} (the year of that price), $PROVENANCE_KEY, " +
        "${CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY}, $ALL_YEARS_KEY.\n" +
        "$PUBLISHED_PRICE_AVAILABILITY_KEY - ${CollegeCostChatTool.BASIS_KEY} + " +
        "${CollegeCostChatTool.STATEMENT_KEY}, instead of $PUBLISHED_PRICE_KEY: why there is no published-price " +
        "total.\n" +
        "$PRICE_AFTER_GRANTS_KEY - the school's own published price after grants and scholarships: " +
        "$PER_YEAR_KEY, ${CollegeCostChatTool.ACADEMIC_YEAR_KEY}, ${CollegeCostChatTool.BASIS_KEY} " +
        "(\"${NetPriceBasis.YourIncomeBand.VALUE}\" | \"${NetPriceBasis.OverallAverage.value}\"), " +
        "$INCOME_BAND_LABEL_KEY (the band's dollar range), " +
        "${CollegeCostChatTool.APPLIES_BASIS_KEY} and ${CollegeCostChatTool.APPLIES_KEY} (as in the cost " +
        "picture), $PROVENANCE_KEY, $ALL_YEARS_KEY.\n" +
        "$PRICE_AFTER_GRANTS_AVAILABILITY_KEY - ${CollegeCostChatTool.BASIS_KEY} + " +
        "${CollegeCostChatTool.STATEMENT_KEY}, instead of $PRICE_AFTER_GRANTS_KEY: why there is no price after " +
        "grants. The basis is the figure status of the school's net price when it gives no number " +
        "($NO_NET_PRICE_STATUS_VOCABULARY), or the reason a net price it publishes is held back " +
        "($WITHHELD_REASON_VOCABULARY).\n" +
        "$ALL_YEARS_AVAILABILITY_KEY - ${CollegeCostChatTool.BASIS_KEY} " +
        "(\"${NotTotalledReason.NOT_FOUR_YEAR.value}\" | \"${NotTotalledReason.LEVEL_NOT_HELD.value}\") + " +
        "${CollegeCostChatTool.STATEMENT_KEY}: why that school has no all-years figure.\n" +
        "$PROVENANCE_KEY - one entry per kind of number and publisher the figure is built from: " +
        "${CollegeCostChatTool.ASSURANCE_KEY}, ${CollegeCostChatTool.ASSURANCE_STATEMENT_KEY}, " +
        "${CollegeCostChatTool.FIGURE_SOURCE_KEY}.\n" +
        "$ALL_YEARS_KEY - $PER_YEAR_KEY times $YEARS_KEY, in whole US dollars: a figure we derived, never one a " +
        "school published. No key adds schools together.\n" +
        "$ALL_YEARS_STATEMENT_KEY - the sentence that says what every $ALL_YEARS_KEY is.\n" +
        "${CollegeCostChatTool.SOURCE_KEY} - the publishers the one-year figures came from.\n" +
        "Read-only: this tool changes nothing."
    }
  }
}
