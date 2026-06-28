package ed.unicoach.college

import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The chat-tool contract for college search (RFC 67): an Anthropic tool
 * [definition] (name + description + JSON-Schema `input_schema`) plus a pure,
 * total [execute] adapter. It has no chat-module dependency — it speaks plain
 * [JsonObject] on both ends — so a future agentic-loop RFC registers it as pure
 * wiring. The same `definition` + total-`execute` shape an MCP server's
 * `list_tools`/`call_tool` expose, deliberately, so it could later be wrapped in
 * an MCP server with no rework.
 *
 * [execute] is total: malformed input returns a structured `{ "error": ... }`
 * result rather than throwing into the (future) turn loop, and a zero-match query
 * returns `{ "colleges": [], "count": 0 }` — an empty result is a valid domain
 * outcome, not an error.
 */
class CollegeSearchTool(
  private val service: CollegeSearchService,
) {
  val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("cipPrefix") {
            put("type", "string")
            put(
              "description",
              "A 2-, 4-, or 6-digit CIP code prefix to require a matching program " +
                "(e.g. \"26\" biology, \"2613\" ecology, \"260702\" marine biology).",
            )
          }
          putStringArrayProperty(
            "states",
            "Two-letter US state postal codes; matches institutions in any of them (OR-set).",
          )
          putJsonObject("region") {
            put("type", "integer")
            put("description", "IPEDS region code (0-9).")
          }
          putIntArrayProperty("locales", "IPEDS urbanization locale codes (11-43); matches any (OR-set).")
          putIntArrayProperty("control", "Control codes: 1 public, 2 private nonprofit, 3 private for-profit; matches any.")
          putIntProperty("minUndergradEnrollment", "Minimum degree-seeking undergraduate enrollment.")
          putIntProperty("maxUndergradEnrollment", "Maximum degree-seeking undergraduate enrollment.")
          putNumberProperty("minAdmissionRate", "Minimum admission rate (0-1).")
          putNumberProperty("maxAdmissionRate", "Maximum admission rate (0-1).")
          putIntProperty("maxNetPrice", "Maximum average annual net price, USD.")
          putNumberProperty("minGraduationRate", "Minimum 6-year graduation rate (0-1).")
          putJsonObject("limit") {
            put("type", "integer")
            put(
              "description",
              "Maximum number of colleges to return; clamped to $MIN_LIMIT..$MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
            )
          }
        }
        // All fields optional; an implicit result cap is enforced server-side.
        putJsonArray("required") {}
      }
    }

  /**
   * Parses [input] into a [CollegeQuery], runs the search, and serializes the
   * matches. Unknown fields and type mismatches yield `{ "error": "<reason>" }`;
   * the executor never throws.
   */
  suspend fun execute(input: JsonObject): JsonObject {
    val query =
      when (val parsed = parseQuery(input)) {
        is ParseResult.Ok -> parsed.query
        is ParseResult.Err -> return errorObject(parsed.reason)
      }

    val matches =
      service.search(query).getOrElse { error ->
        return searchFailureObject(error)
      }

    return buildJsonObject {
      putJsonArray("colleges") {
        matches.forEach { add(matchObject(it)) }
      }
      put("count", matches.size)
    }
  }

  // ---------------------------------------------------------------------------
  // Input parsing (total — never throws)
  // ---------------------------------------------------------------------------

  private sealed interface ParseResult {
    data class Ok(
      val query: CollegeQuery,
    ) : ParseResult

    data class Err(
      val reason: String,
    ) : ParseResult
  }

  private fun parseQuery(input: JsonObject): ParseResult {
    val unknown = input.keys - KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
      return ParseResult.Err("unknown field(s): ${unknown.sorted().joinToString(", ")}")
    }

    val cipPrefix =
      when (val r = optString(input, "cipPrefix")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (cipPrefix != null && !cipPrefix.matches(CIP_PREFIX_REGEX)) {
      return ParseResult.Err("cipPrefix must be 2, 4, or 6 digits")
    }

    val states =
      when (val r = optStringList(input, "states")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (states != null && states.any { !it.matches(STATE_CODE_REGEX) }) {
      return ParseResult.Err("states must be 2-letter US state postal codes")
    }
    // state is stored UPPERCASE (Scorecard STABBR) and bound into a case-sensitive
    // SQL IN, so normalize here — an LLM emitting "ca" must still match a "CA" row.
    val normalizedStates = states?.map { it.uppercase() }
    val region =
      when (val r = optInt(input, "region")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (region != null && region !in 0..9) {
      return ParseResult.Err("region must be 0-9")
    }
    val locales =
      when (val r = optIntList(input, "locales")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (locales != null && locales.any { it !in 11..43 }) {
      return ParseResult.Err("locales must be 11-43")
    }
    val control =
      when (val r = optIntList(input, "control")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (control != null && control.any { it !in 1..3 }) {
      return ParseResult.Err("control must be 1, 2, or 3")
    }
    val minUndergrad =
      when (val r = optInt(input, "minUndergradEnrollment")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (minUndergrad != null && minUndergrad < 0) {
      return ParseResult.Err("minUndergradEnrollment must be >= 0")
    }
    val maxUndergrad =
      when (val r = optInt(input, "maxUndergradEnrollment")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (maxUndergrad != null && maxUndergrad < 0) {
      return ParseResult.Err("maxUndergradEnrollment must be >= 0")
    }
    val minAdmission =
      when (val r = optDouble(input, "minAdmissionRate")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (minAdmission != null && minAdmission !in 0.0..1.0) {
      return ParseResult.Err("minAdmissionRate must be 0.0-1.0")
    }
    val maxAdmission =
      when (val r = optDouble(input, "maxAdmissionRate")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (maxAdmission != null && maxAdmission !in 0.0..1.0) {
      return ParseResult.Err("maxAdmissionRate must be 0.0-1.0")
    }
    val maxNetPrice =
      when (val r = optInt(input, "maxNetPrice")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (maxNetPrice != null && maxNetPrice < 0) {
      return ParseResult.Err("maxNetPrice must be >= 0")
    }
    val minGraduation =
      when (val r = optDouble(input, "minGraduationRate")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value
      }
    if (minGraduation != null && minGraduation !in 0.0..1.0) {
      return ParseResult.Err("minGraduationRate must be 0.0-1.0")
    }
    val limit =
      when (val r = optInt(input, "limit")) {
        is Parsed.Err -> return ParseResult.Err(r.reason)
        is Parsed.Ok -> r.value ?: DEFAULT_LIMIT
      }

    return ParseResult.Ok(
      CollegeQuery(
        cipPrefix = cipPrefix,
        states = normalizedStates,
        region = region,
        locales = locales,
        control = control,
        minUndergradEnrollment = minUndergrad,
        maxUndergradEnrollment = maxUndergrad,
        minAdmissionRate = minAdmission,
        maxAdmissionRate = maxAdmission,
        maxNetPrice = maxNetPrice,
        minGraduationRate = minGraduation,
        limit = limit,
      ),
    )
  }

  private sealed interface Parsed<out T> {
    data class Ok<T>(
      val value: T,
    ) : Parsed<T>

    data class Err(
      val reason: String,
    ) : Parsed<Nothing>
  }

  private fun field(
    input: JsonObject,
    key: String,
  ): JsonElement? = input[key]?.takeUnless { it is JsonNull }

  private fun optString(
    input: JsonObject,
    key: String,
  ): Parsed<String?> {
    val el = field(input, key) ?: return Parsed.Ok(null)
    val prim = el as? JsonPrimitive ?: return Parsed.Err("$key must be a string")
    if (!prim.isString) return Parsed.Err("$key must be a string")
    return Parsed.Ok(prim.content)
  }

  private fun optInt(
    input: JsonObject,
    key: String,
  ): Parsed<Int?> {
    val el = field(input, key) ?: return Parsed.Ok(null)
    val prim = el as? JsonPrimitive ?: return Parsed.Err("$key must be an integer")
    if (prim.isString) return Parsed.Err("$key must be an integer")
    val value = prim.content.toIntOrNull() ?: return Parsed.Err("$key must be an integer")
    return Parsed.Ok(value)
  }

  private fun optDouble(
    input: JsonObject,
    key: String,
  ): Parsed<Double?> {
    val el = field(input, key) ?: return Parsed.Ok(null)
    val prim = el as? JsonPrimitive ?: return Parsed.Err("$key must be a number")
    if (prim.isString) return Parsed.Err("$key must be a number")
    val value = prim.content.toDoubleOrNull() ?: return Parsed.Err("$key must be a number")
    return Parsed.Ok(value)
  }

  private fun optStringList(
    input: JsonObject,
    key: String,
  ): Parsed<List<String>?> {
    val el = field(input, key) ?: return Parsed.Ok(null)
    val arr = el as? JsonArray ?: return Parsed.Err("$key must be an array of strings")
    val out = mutableListOf<String>()
    for (item in arr) {
      val prim = item as? JsonPrimitive ?: return Parsed.Err("$key must be an array of strings")
      if (!prim.isString) return Parsed.Err("$key must be an array of strings")
      out += prim.content
    }
    return Parsed.Ok(out.toList())
  }

  private fun optIntList(
    input: JsonObject,
    key: String,
  ): Parsed<List<Int>?> {
    val el = field(input, key) ?: return Parsed.Ok(null)
    val arr = el as? JsonArray ?: return Parsed.Err("$key must be an array of integers")
    val out = mutableListOf<Int>()
    for (item in arr) {
      val prim = item as? JsonPrimitive ?: return Parsed.Err("$key must be an array of integers")
      if (prim.isString) return Parsed.Err("$key must be an array of integers")
      val value = prim.content.toIntOrNull() ?: return Parsed.Err("$key must be an array of integers")
      out += value
    }
    return Parsed.Ok(out.toList())
  }

  // ---------------------------------------------------------------------------
  // Output serialization
  // ---------------------------------------------------------------------------

  private fun matchObject(match: CollegeMatch): JsonObject =
    buildJsonObject {
      put("name", match.name)
      put("city", match.city)
      put("state", match.state)
      put("control", match.control)
      putOrNull("undergrad_enrollment", match.undergradEnrollment)
      putOrNull("admission_rate", match.admissionRate)
      putOrNull("net_price", match.netPrice)
      putOrNull("graduation_rate", match.graduationRate)
      putOrNull("median_earnings", match.medianEarnings)
      putOrNull("pct_pell", match.pctPell)
      putJsonArray("programs") {
        match.programTitles.forEach { add(it) }
      }
    }

  private fun errorObject(reason: String): JsonObject = buildJsonObject { put("error", reason) }

  /**
   * A structured error for a search-time DAO failure. Unlike a malformed-input
   * error (a precise validation string), a DAO failure carries a retryability
   * category: [TransientError] (a DB blip — the same query may succeed on retry)
   * vs [PermanentError] (a programming/SQL fault — retrying will not help). The
   * `transient` flag and the wrapper's cause message let the consumer branch on
   * the category instead of re-parsing a flattened string.
   */
  private fun searchFailureObject(error: Throwable): JsonObject =
    buildJsonObject {
      putJsonObject("error") {
        put("kind", "search_failed")
        put("category", error.errorCategory())
        put("transient", error is TransientError)
        put("detail", error.message ?: error::class.simpleName ?: "search failed")
        error.cause?.message?.let { put("cause", it) }
      }
    }

  companion object {
    const val TOOL_NAME = "search_colleges"
    const val DEFAULT_LIMIT = 10
    const val MIN_LIMIT = CollegeSearchService.MIN_LIMIT
    const val MAX_LIMIT = CollegeSearchService.MAX_LIMIT

    private val CIP_PREFIX_REGEX = Regex("^([0-9]{2}|[0-9]{4}|[0-9]{6})$")

    private val STATE_CODE_REGEX = Regex("^[A-Za-z]{2}$")

    private val KNOWN_FIELDS =
      setOf(
        "cipPrefix",
        "states",
        "region",
        "locales",
        "control",
        "minUndergradEnrollment",
        "maxUndergradEnrollment",
        "minAdmissionRate",
        "maxAdmissionRate",
        "maxNetPrice",
        "minGraduationRate",
        "limit",
      )

    private const val DESCRIPTION =
      "Search the College Scorecard dataset of real US colleges by structured " +
        "filters: program of study (CIP code prefix), location (US states, IPEDS " +
        "region, urbanization locale), institutional control (public/private), " +
        "undergraduate enrollment size, admission rate (selectivity), maximum net " +
        "price (affordability), and minimum graduation rate. Returns matching real " +
        "institutions with size, selectivity, net price, and outcome context " +
        "(graduation rate, median earnings, Pell share). This tool filters on " +
        "structured fields only; it CANNOT reason about geographic distance, " +
        "proximity to the coastline, or how close two places are — to approximate " +
        "\"near the ocean\" or a region, pass the relevant set of coastal/nearby " +
        "state codes in `states`."
  }
}

// JSON-builder helpers kept at file scope so the schema/output builders read
// linearly. Each writes one optional value or one typed property.

private fun kotlinx.serialization.json.JsonObjectBuilder.putOrNull(
  key: String,
  value: Int?,
) {
  if (value != null) put(key, value) else put(key, JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putOrNull(
  key: String,
  value: Double?,
) {
  if (value != null) put(key, value) else put(key, JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIntProperty(
  key: String,
  description: String,
) {
  putJsonObject(key) {
    put("type", "integer")
    put("description", description)
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNumberProperty(
  key: String,
  description: String,
) {
  putJsonObject(key) {
    put("type", "number")
    put("description", description)
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putStringArrayProperty(
  key: String,
  description: String,
) {
  putJsonObject(key) {
    put("type", "array")
    putJsonObject("items") { put("type", "string") }
    put("description", description)
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIntArrayProperty(
  key: String,
  description: String,
) {
  putJsonObject(key) {
    put("type", "array")
    putJsonObject("items") { put("type", "integer") }
    put("description", description)
  }
}
