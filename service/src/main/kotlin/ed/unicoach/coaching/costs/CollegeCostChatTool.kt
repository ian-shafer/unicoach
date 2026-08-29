package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putIncomeBand
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
      // Emitted only when there is something to offer: an absent key, never an
      // empty array, so its mere presence stays meaningful to the model.
      val offers = profile.precisionOffersFor(cost)
      if (offers.isNotEmpty()) {
        putJsonArray(PRECISION_OFFER_KEY) { offers.forEach { add(precisionOfferObject(it)) } }
      }
      cost.medianDebt?.let { put(CostField.MEDIAN_DEBT.wireName, it) }
      cost.medianEarnings?.let { put(CostField.MEDIAN_EARNINGS.wireName, it) }
      putJsonArray("data_availability") {
        cost.notReported.forEach { add(JsonPrimitive(it.wireName)) }
      }
    }

  /** One invitation: the money-profile field it would fill, and the sentence the coach may say for it. */
  private fun precisionOfferObject(offer: PrecisionOffer): JsonObject =
    buildJsonObject {
      put("field", offer.field)
      put("offer", offerCopy(offer))
    }

  /**
   * The sentence the coach may say for one offer. Exhaustive on purpose: a new
   * [PrecisionOffer] member must fail to compile here — the one site that owes
   * it copy — rather than ship an invitation with no words in it.
   */
  private fun offerCopy(offer: PrecisionOffer): String =
    when (offer) {
      PrecisionOffer.RESIDENCY -> RESIDENCY_OFFER
      PrecisionOffer.INCOME_BAND -> INCOME_BAND_OFFER
    }

  /**
   * The `net_price` sub-object: amount when reported, the basis label, and —
   * only on the band-specific case — the band's code and its spoken dollar
   * range (`IncomeBand.bracket`, RFC 142). The label rides beside the code so
   * the model never has to invent a phrase for the bucket it is naming aloud.
   */
  private fun netPriceObject(netPrice: NetPrice): JsonObject =
    buildJsonObject {
      netPrice.amount?.let { put("amount", it) }
      put("basis", netPrice.basis)
      // Exhaustive on purpose: an overall average deliberately emits no
      // qualifier, and a future NetPrice case must fail to compile here rather
      // than ship an unlabeled basis. Do not collapse this to an `if`.
      when (netPrice) {
        is NetPrice.BandSpecific -> {
          putIncomeBand(netPrice.band)
        }

        is NetPrice.OverallAverage -> {}
      }
    }

  /**
   * The money-profile echo: both field statuses, values only when answered.
   * An answered band carries its spoken dollar range alongside its code
   * (`IncomeBand.bracket`, RFC 142); an unanswered or declined band carries
   * neither.
   */
  private fun moneyProfileObject(profile: MoneyProfileStatuses): JsonObject =
    buildJsonObject {
      put("income_band_status", profile.incomeBandStatus.value)
      profile.incomeBand?.let { putIncomeBand(it) }
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

    /** The wire key carrying the upgrade invitations — one home for the emit site and the description. */
    const val PRECISION_OFFER_KEY = "precision_offer"

    /**
     * The residency invitation (RFC 145): present on a public college's result
     * exactly when residency is unanswered and the college publishes a tuition
     * figure the answer would select — and absent after a decline, so the
     * coach is never cued to reopen a closed topic
     * ([CollegeCostProfile.precisionOffersFor]). It says what the answer
     * unlocks: which of this school's published prices applies to this family.
     *
     * The promise is deliberately no wider than the data: the offer is admitted
     * when EITHER figure is published (residency still decides which one
     * applies), so the copy cannot promise a number — a family sorted onto the
     * side this school does not report gets the ordinary `data_availability`
     * answer, said plainly, instead of an invented one.
     */
    const val RESIDENCY_OFFER =
      "This is a public school, so its published tuition and fees depend on where the family lives. " +
        "If the student shares the state they live in (record it with ${MoneyProfileChatTool.TOOL_NAME}), " +
        "you can say which of this school's published prices applies to them - the in-state one or the " +
        "out-of-state one - and say plainly when this school does not report the one that applies."

    /**
     * The in-answer invitation (RFC 135): present on a college result exactly
     * when the income band is unanswered and that college reports band
     * pricing, so the coach can offer the upgrade right in the conversation —
     * and absent after a decline, so the coach is never cued to reopen a
     * closed topic ([CollegeCostProfile.precisionOffersFor]).
     */
    const val INCOME_BAND_OFFER =
      "This net price is the overall average. If the student shares their household income band " +
        "(record it with ${MoneyProfileChatTool.TOOL_NAME}), it becomes the family-specific price for their bracket."

    // The ethos contract rides the tool description (RFC 135): real numbers
    // with a named source, the basis always labeled, never re-raise a decline.
    // Not `const`: the example band range is rendered from IncomeBand.bracket,
    // the one home for that copy (RFC 142), so the description can never quote
    // a range the results themselves no longer carry.
    val DESCRIPTION =
      "Read the real cost facts for the colleges on the student's list: sticker cost, tuition, " +
        "the net price their family would actually pay, median debt and median earnings. " +
        "Data comes from the U.S. Department of Education College Scorecard - always attribute figures " +
        "to it, and when a field appears in data_availability the college does not report it: say so " +
        "plainly, never estimate. Each net_price is labeled with its basis: your_income_band means it is " +
        "specific to the student's answered household income band; overall_average means the band is not " +
        "on file and the figure is the all-family average - say which it is. When a net price is band-specific it " +
        "also carries income_band_label, the band's dollar range in plain words (e.g. \"${IncomeBand.OVER_110K.bracket}\") - say that " +
        "range when you name the band aloud, never the income_band code and never a data-source bucket name. " +
        "When a college's result carries " +
        "$PRECISION_OFFER_KEY, it is a list of upgrade invitations for that result, each naming the money-profile field " +
        "it would fill (${MoneyProfileChatTool.TOOL_NAME} records them) and carrying the sentence you may say. Raise them " +
        "in the order given: ${PrecisionOffer.RESIDENCY.field} sorts first because it is the cheaper question and the " +
        "bigger correction - it selects which of the school's published prices applies - while " +
        "${PrecisionOffer.INCOME_BAND.field} makes " +
        "the net price family-specific. money_profile.residency_status is the authority on whether to raise residency, " +
        "and money_profile.income_band_status the authority on whether to raise income: " +
        "declined means the student said no - never re-raise it yourself; answered means the field is already on file. " +
        "Read-only: this tool changes nothing."
  }
}
