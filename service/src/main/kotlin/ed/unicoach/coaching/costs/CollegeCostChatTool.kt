package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The `college_cost_profile` chat tool (RFC 135): the coach's read path into
 * what the student's listed schools actually cost their family. Read-only —
 * it writes nothing. Total by the [ed.unicoach.chat.ChatTool] contract:
 * malformed input returns a structured `{ "error": ... }` object the model
 * reads, never a throw.
 *
 * A thin adapter by design ([MoneyProfileChatTool]'s shape): [execute] only
 * orchestrates parse -> read -> render; the composition (basis selection,
 * tuition applicability, the precision-offer derivation) lives in
 * [CollegeCostService].
 */
class CollegeCostChatTool(
  private val service: CollegeCostService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("college_ids") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put(
              "description",
              "Optional subset of college ids (from the student's list) to read; " +
                "omit the field entirely to read the whole active list. " +
                "At most $MAX_COLLEGE_IDS entries; duplicate ids are read once.",
            )
          }
        }
        putJsonArray("required") {}
      }
    }

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val collegeIds =
      when (val parsed = parseInput(input)) {
        is ParsedInput.Ok -> parsed.collegeIds
        is ParsedInput.Invalid -> return errorObject(parsed.reason)
      }

    val profile =
      service
        .getForStudent(studentId, collegeIds)
        .getOrElse { e ->
          logger.warn("tool [{}] cost read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("college cost read failed")
        }

    return profileObject(profile)
  }

  /** The parse outcome for one tool call: the optional subset filter or the reason the call is malformed. */
  private sealed interface ParsedInput {
    data class Ok(
      val collegeIds: List<CollegeId>?,
    ) : ParsedInput

    data class Invalid(
      val reason: String,
    ) : ParsedInput
  }

  private fun parseInput(input: JsonObject): ParsedInput {
    unknownFieldsReason(input, KNOWN_FIELDS)?.let { return ParsedInput.Invalid(it) }

    // Absence and emptiness are different reads: an omitted field is a null
    // filter meaning the whole active list, while `[]` is a literal empty
    // subset and must stay one. Never normalise the empty list back to null --
    // that silently turns "these zero schools" into "all of them".
    val element = input["college_ids"] ?: return ParsedInput.Ok(null)
    val array =
      element as? JsonArray
        ?: return ParsedInput.Invalid("college_ids must be an array of uuid strings, got: [$element]")
    if (array.size > MAX_COLLEGE_IDS) {
      return ParsedInput.Invalid("college_ids must contain at most [$MAX_COLLEGE_IDS] entries, got [${array.size}]")
    }
    val ids =
      array.mapIndexed { index, item ->
        when (val parsed = parseCollegeId(item, index)) {
          is IdParse.Ok -> parsed.id
          is IdParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
        }
      }
    return ParsedInput.Ok(ids)
  }

  /** The per-element parse outcome: one array entry as a [CollegeId], or the reason it is malformed. */
  private sealed interface IdParse {
    data class Ok(
      val id: CollegeId,
    ) : IdParse

    data class Invalid(
      val reason: String,
    ) : IdParse
  }

  /** Parses one `college_ids` entry; a rejection names the offending element and its index. */
  private fun parseCollegeId(
    item: JsonElement,
    index: Int,
  ): IdParse {
    val primitive = item as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
      return IdParse.Invalid("college_ids entry is not a uuid string: [$item] at index [$index]")
    }
    return try {
      IdParse.Ok(CollegeId(UUID.fromString(primitive.content)))
    } catch (_: IllegalArgumentException) {
      IdParse.Invalid("college_ids entry is not a uuid: [${primitive.content}] at index [$index]")
    }
  }

  /** The full structured result: one cost object per college, the money-profile echo, and the attribution. */
  private fun profileObject(profile: CollegeCostProfile): JsonObject =
    buildJsonObject {
      putJsonArray("colleges") { profile.colleges.forEach { add(collegeObject(profile, it)) } }
      put("count", profile.colleges.size)
      if (profile.unknownCollegeIds.isNotEmpty()) {
        putJsonArray("unknown_college_ids") {
          profile.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
        }
      }
      put("money_profile", moneyProfileObject(profile.moneyProfile))
      put("source", sourceAttribution(profile.ingestYear))
    }

  private fun collegeObject(
    profile: CollegeCostProfile,
    cost: CollegeCost,
  ): JsonObject =
    buildJsonObject {
      put("college_id", cost.collegeId.value.toString())
      put("name", cost.name)
      put("city", cost.city)
      put("state", cost.state)
      put("control", cost.control.label)
      put("list_status", cost.listStatus.value)
      cost.stickerCostAttendance?.let { put(CostField.STICKER_COST_ATTENDANCE.wireName, it) }
      cost.tuitionInState?.let { put(CostField.TUITION_IN_STATE.wireName, it) }
      cost.tuitionOutState?.let { put(CostField.TUITION_OUT_STATE.wireName, it) }
      // Present only on the public case; the model makes the distinction
      // uncarryable by a private college, so it cannot be misread onto one.
      (cost.control as? CollegeControl.Public)?.let { put("tuition_applicable", it.tuitionApplicable.value) }
      put(CostField.NET_PRICE.wireName, netPriceObject(cost.netPrice))
      if (profile.precisionOfferFor(cost)) put("precision_offer", PRECISION_OFFER)
      cost.medianDebt?.let { put(CostField.MEDIAN_DEBT.wireName, it) }
      cost.medianEarnings?.let { put(CostField.MEDIAN_EARNINGS.wireName, it) }
      putJsonArray("data_availability") {
        cost.notReported.forEach { add(JsonPrimitive(it.wireName)) }
      }
    }

  /** The `net_price` sub-object: amount when reported, the basis label, the band only on the band-specific case. */
  private fun netPriceObject(netPrice: NetPrice): JsonObject =
    buildJsonObject {
      netPrice.amount?.let { put("amount", it) }
      put("basis", netPrice.basis)
      // Exhaustive on purpose: an overall average deliberately emits no
      // qualifier, and a future NetPrice case must fail to compile here rather
      // than ship an unlabeled basis. Do not collapse this to an `if`.
      when (netPrice) {
        is NetPrice.BandSpecific -> {
          put("income_band", netPrice.band.value)
        }

        is NetPrice.OverallAverage -> {}
      }
    }

  /** The money-profile echo: both field statuses, values only when answered. */
  private fun moneyProfileObject(profile: MoneyProfileStatuses): JsonObject =
    buildJsonObject {
      put("income_band_status", profile.incomeBandStatus.value)
      profile.incomeBand?.let { put("income_band", it.value) }
      put("residency_status", profile.residencyStatus.value)
      profile.residencyState?.let { put("residency_state", it) }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostChatTool::class.java)

    const val TOOL_NAME = "college_cost_profile"

    private val KNOWN_FIELDS = setOf("college_ids")

    /** The subset filter reads from the student's own list; anything larger is malformed, not a bigger read. */
    const val MAX_COLLEGE_IDS = 50

    /** The attribution the coach must quote when using these numbers. */
    fun sourceAttribution(ingestYear: Int?): String =
      "U.S. Department of Education College Scorecard" + (ingestYear?.let { " (data ingested $it)" } ?: "")

    /**
     * The in-answer invitation (RFC 135): present on a college result exactly
     * when the income band is unanswered and that college reports band
     * pricing, so the coach can offer the upgrade right in the conversation —
     * and absent after a decline, so the coach is never cued to reopen a
     * closed topic ([CollegeCostProfile.precisionOfferFor]).
     */
    const val PRECISION_OFFER =
      "This net price is the overall average. If the student shares their household income band " +
        "(record it with ${MoneyProfileChatTool.TOOL_NAME}), it becomes the family-specific price for their bracket."

    // The ethos contract rides the tool description (RFC 135): real numbers
    // with a named source, the basis always labeled, never re-raise a decline.
    const val DESCRIPTION =
      "Read the real cost facts for the colleges on the student's list: sticker cost, tuition, " +
        "the net price their family would actually pay, median debt and median earnings. " +
        "Data comes from the U.S. Department of Education College Scorecard - always attribute figures " +
        "to it, and when a field appears in data_availability the college does not report it: say so " +
        "plainly, never estimate. Each net_price is labeled with its basis: your_income_band means it is " +
        "specific to the student's answered household income band; overall_average means the band is not " +
        "on file and the figure is the all-family average - say which it is. When a college's result carries " +
        "precision_offer, you may offer to record the income band (${MoneyProfileChatTool.TOOL_NAME}) so the numbers " +
        "become family-specific. money_profile.income_band_status is the authority on whether to raise income: " +
        "declined means the student said no - never re-raise it yourself; answered means the band is already on file. " +
        "Read-only: this tool changes nothing."
  }
}
