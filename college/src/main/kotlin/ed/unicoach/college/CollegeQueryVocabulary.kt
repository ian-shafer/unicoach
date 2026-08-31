package ed.unicoach.college

import ed.unicoach.db.models.CipPrefix
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.InstitutionControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The ONE description of the [CollegeQuery] filter vocabulary a model may write:
 * the JSON-Schema properties it is offered, and the parse that turns what it
 * writes back into a query (RFC 147 D45).
 *
 * It exists because there were two. `search_colleges` advertised and parsed one
 * copy; the fit lens's `record_college_query` advertised and parsed a second,
 * and the two had already drifted — the fit lens's schema carried bare types
 * with no descriptions, so its codebook had to be re-stated as prose in a
 * seeded prompt. One home means neither can drift again, and the prompt does
 * not need a codebook in it at all.
 *
 * Every coded axis speaks WORDS in both directions:
 *
 * - `region` is a codebook slug (`new-england`), resolved through the LOADED
 *   `ipeds_regions` table by [Codebook], never a hand-written map.
 * - `locale_type`/`locale_detail` are the two published halves of the NCES
 *   locale label, resolved to the set of `LOCALE` codes they name.
 * - `control` is [InstitutionControl]'s own label, the same word the search
 *   result renders.
 *
 * An unreadable word is always a NAMED failure listing the vocabulary. It is
 * never dropped: a filter that silently disappears answers a narrower question
 * with a wider answer, which is worse than an error the model can correct.
 */
class CollegeQueryVocabulary(
  private val codebook: Codebook,
) {
  /** The filter fields this vocabulary owns; a tool adds its own around them. */
  val fieldNames: Set<String> = FIELD_NAMES

  /**
   * The JSON-Schema `properties` entries for [fieldNames], in the order a model
   * reads them. `region`'s enum is the loaded codebook's own word list, so a
   * codebook the publisher extends widens the schema with no code change; when
   * nothing is loaded the enum is omitted rather than published as an empty
   * list, and any word sent is refused with [Codebook.UNAVAILABLE].
   */
  fun schemaProperties(): Map<String, JsonObject> =
    linkedMapOf(
      "cipPrefix" to
        stringProperty(
          "A 2-, 4-, or 6-digit CIP code prefix to require a matching program " +
            "(e.g. \"26\" biology, \"2613\" ecology, \"260702\" marine biology). " +
            "The conventional dotted notation is also accepted -- \"26.13\" and " +
            "\"26.0702\" mean the same as \"2613\" and \"260702\" -- but the canonical " +
            "form is digits only.",
        ),
      "states" to
        arrayProperty(
          buildJsonObject { put("type", "string") },
          "Two-letter US state postal codes; matches institutions in any of them (OR-set).",
        ),
      "region" to
        wordProperty(
          codebook.regionSlugs,
          "The part of the country, as a published IPEDS region name: " +
            regionVocabulary() + ". Matches institutions in that region only.",
        ),
      // Both locale halves advertise what is LOADED, exactly as `region` does.
      // The Kotlin enums are the closed set the parse accepts; they are not the
      // set to offer, because on a database with no codebook every enum word
      // would be advertised and every one of them refused.
      "locale_type" to
        wordProperty(
          codebook.localeTypeWords,
          "How built-up the college's surroundings are, as the published NCES " +
            "urbanization type: " + vocabularyOf(codebook.localeTypeWords) + ". " +
            "Matches every size of that setting unless locale_detail narrows it.",
        ),
      "locale_detail" to
        wordProperty(
          codebook.localeDetailWords,
          "Narrows locale_type to one published size: " +
            vocabularyOf(codebook.localeDetailWords) + ". Only valid together " +
            "with locale_type, and only in a pairing the publisher defines -- " +
            "a city has no \"fringe\", and the error lists what each type does publish.",
        ),
      "control" to
        arrayProperty(
          wordProperty(InstitutionControl.entries.map { it.label }, null),
          "Who runs the institution: " +
            InstitutionControl.entries.joinToString(", ") { "\"${it.label}\"" } +
            "; matches any of them (OR-set).",
        ),
      "minUndergradEnrollmentHeadcount" to
        intProperty("Minimum undergraduate enrollment headcount (degree- and certificate-seeking)."),
      "maxUndergradEnrollmentHeadcount" to
        intProperty("Maximum undergraduate enrollment headcount (degree- and certificate-seeking)."),
      "minAdmissionRateShare" to numberProperty("Minimum admission rate, as a share 0.0-1.0."),
      "maxAdmissionRateShare" to numberProperty("Maximum admission rate, as a share 0.0-1.0."),
      "maxNetPricePerYearUsd" to intProperty("Maximum average annual net price, in whole US dollars."),
      "minCompletionRate150pct4yrShare" to
        numberProperty(
          "Minimum completion rate, as a share 0.0-1.0: first-time full-time students at a " +
            "four-year institution who finish within 150% of normal time (6 years). " +
            "Two-year colleges do not report it and are filtered out by this bound.",
        ),
    )

  /**
   * Reads every field of [fieldNames] out of [input] into a [CollegeQuery] with
   * the given [limit]. `sortBy` and `credentialLevel` are left at their defaults
   * — they are one tool's own fields, not shared vocabulary.
   *
   * Total in the sense that matters: it never throws, and it never accepts
   * half a filter. The failure carries the reason as an [IllegalArgumentException]
   * message, which each caller renders in its own error shape.
   */
  fun parse(
    input: JsonObject,
    limit: Int,
  ): Result<CollegeQuery> {
    val cipPrefix = parseCipPrefix(input).getOrElse { return Result.failure(it) }

    val states = optStringList(input, "states").getOrElse { return Result.failure(it) }
    if (states != null && states.any { !STATE_CODE_REGEX.matches(it) }) {
      return fail("states must be 2-letter US state postal codes")
    }
    // state is stored UPPERCASE (Scorecard STABBR) and bound into a case-sensitive
    // SQL IN, so normalize here — an LLM emitting "ca" must still match a "CA" row.
    val normalizedStates = states?.map { it.uppercase() }

    val regionSlug = optString(input, "region").getOrElse { return Result.failure(it) }
    val region =
      if (regionSlug == null) {
        null
      } else {
        codebook.regionCode(regionSlug) ?: return fail(unknownRegion(regionSlug))
      }

    val locales = parseLocales(input).getOrElse { return Result.failure(it) }

    val control = parseControl(input).getOrElse { return Result.failure(it) }

    val minUndergrad = optInt(input, "minUndergradEnrollmentHeadcount").getOrElse { return Result.failure(it) }
    if (minUndergrad != null && minUndergrad < 0) return fail("minUndergradEnrollmentHeadcount must be >= 0")
    val maxUndergrad = optInt(input, "maxUndergradEnrollmentHeadcount").getOrElse { return Result.failure(it) }
    if (maxUndergrad != null && maxUndergrad < 0) return fail("maxUndergradEnrollmentHeadcount must be >= 0")

    val minAdmission = optDouble(input, "minAdmissionRateShare").getOrElse { return Result.failure(it) }
    if (minAdmission != null && minAdmission !in 0.0..1.0) return fail("minAdmissionRateShare must be 0.0-1.0")
    val maxAdmission = optDouble(input, "maxAdmissionRateShare").getOrElse { return Result.failure(it) }
    if (maxAdmission != null && maxAdmission !in 0.0..1.0) return fail("maxAdmissionRateShare must be 0.0-1.0")

    val maxNetPrice = optInt(input, "maxNetPricePerYearUsd").getOrElse { return Result.failure(it) }
    if (maxNetPrice != null && maxNetPrice < 0) return fail("maxNetPricePerYearUsd must be >= 0")

    val minCompletion = optDouble(input, "minCompletionRate150pct4yrShare").getOrElse { return Result.failure(it) }
    if (minCompletion != null && minCompletion !in 0.0..1.0) return fail("minCompletionRate150pct4yrShare must be 0.0-1.0")

    return Result.success(
      CollegeQuery(
        cipPrefix = cipPrefix,
        states = normalizedStates,
        region = region,
        locales = locales,
        control = control,
        minUndergradEnrollmentHeadcount = minUndergrad,
        maxUndergradEnrollmentHeadcount = maxUndergrad,
        minAdmissionRateShare = minAdmission,
        maxAdmissionRateShare = maxAdmission,
        maxNetPricePerYearUsd = maxNetPrice,
        minCompletionRate150pct4yrShare = minCompletion,
        limit = limit,
      ),
    )
  }

  /**
   * The published words for a stored `colleges.region` code — the OUTPUT half of
   * the same vocabulary. A code with no codebook row renders as a named unknown
   * carrying the code, the [InstitutionControl.unknownLabel] shape: a source that
   * has grown a value stays visible instead of vanishing into a plausible word.
   */
  fun regionWord(code: Int?): String? =
    when (code) {
      null -> null
      else -> codebook.regionSlug(code) ?: "unknown (region [$code])"
    }

  /** The locale row a stored `colleges.locale` code names. See [regionWord]. */
  fun localeOf(code: Int?): Codebook.Locale? = code?.let { codebook.locale(it) }

  /** The word for a `colleges.locale` code the loaded codebook does not carry. */
  fun unknownLocaleWord(code: Int): String = "unknown (locale [$code])"

  // ---------------------------------------------------------------------------
  // Word resolution
  // ---------------------------------------------------------------------------

  private fun unknownRegion(word: String): String =
    if (codebook.regionSlugs.isEmpty()) {
      "region cannot be resolved: ${Codebook.UNAVAILABLE}; got [$word]"
    } else {
      "region must be one of [${codebook.regionSlugs.joinToString(", ")}]; got [$word]"
    }

  private fun regionVocabulary(): String =
    if (codebook.regions.isEmpty()) {
      NONE_LOADED
    } else {
      codebook.regions.joinToString(", ") { "\"${it.slug}\" (${it.name})" }
    }

  /** One rule for every advertised word list: what is loaded, or that none is. */
  private fun vocabularyOf(words: List<String>): String = if (words.isEmpty()) NONE_LOADED else words.joinToString(", ") { "\"$it\"" }

  /**
   * `locale_type` (+ optional `locale_detail`) -> the published `LOCALE` codes.
   * Every refusal names the closed set it is refusing against; a `locale_detail`
   * with no `locale_type`, and a pairing the publisher does not define, are BOTH
   * errors rather than an ignored field or an empty match.
   */
  private fun parseLocales(input: JsonObject): Result<List<Int>?> {
    val typeWord = optString(input, "locale_type").getOrElse { return Result.failure(it) }
    val detailWord = optString(input, "locale_detail").getOrElse { return Result.failure(it) }

    if (typeWord == null) {
      if (detailWord != null) {
        return fail("locale_detail is only valid together with locale_type")
      }
      return Result.success(null)
    }
    val type =
      NcesLocaleType.fromWord(typeWord)
        ?: return fail("locale_type must be one of [${NcesLocaleType.WORDS.joinToString(", ")}]; got [$typeWord]")
    val detail =
      if (detailWord == null) {
        null
      } else {
        NcesLocaleDetail.fromWord(detailWord)
          ?: return fail("locale_detail must be one of [${NcesLocaleDetail.WORDS.joinToString(", ")}]; got [$detailWord]")
      }

    val codes = codebook.localeCodes(type, detail)
    if (codes.isEmpty()) {
      val published = codebook.localeDetails(type)
      return if (published.isEmpty()) {
        fail("locale_type [$typeWord] cannot be resolved: ${Codebook.UNAVAILABLE}")
      } else {
        fail(
          "locale_detail [$detailWord] is not published for locale_type [$typeWord]; " +
            "it publishes [${published.joinToString(", ")}]",
        )
      }
    }
    return Result.success(codes)
  }

  /** `control` words -> the codes the column stores. See [InstitutionControl]. */
  private fun parseControl(input: JsonObject): Result<List<Int>?> {
    val words = optStringList(input, "control").getOrElse { return Result.failure(it) }
    if (words == null) return Result.success(null)
    val codes =
      words.map { word ->
        InstitutionControl.entries.firstOrNull { it.label == word }?.code
          ?: return fail(
            "control must be one of [${InstitutionControl.entries.joinToString(", ") { it.label }}]; got [$word]",
          )
      }
    return Result.success(codes)
  }

  /**
   * Reads `cipPrefix` deliberately more permissive about TYPE than the other
   * readers and stricter about VALUE. The schema says string, but a model
   * writing a dotted CIP code often drops the quotes (`{"cipPrefix": 26.07}`),
   * and the literal text of that number is still a readable prefix -- so any
   * scalar is read and canonicalized, and only an unreadable VALUE is refused.
   * Both model-facing surfaces now share this one reading, which is what they
   * were separately approximating before.
   */
  private fun parseCipPrefix(input: JsonObject): Result<String?> {
    val el = field(input, "cipPrefix") ?: return Result.success(null)
    val raw = (el as? JsonPrimitive)?.content ?: return fail("$CIP_PREFIX_ERROR; got [$el]")
    val canonical = CipPrefix.parseOrNull(raw) ?: return fail("$CIP_PREFIX_ERROR; got [$raw]")
    return Result.success(canonical)
  }

  companion object {
    /** The shared filter fields, in schema order. */
    val FIELD_NAMES: Set<String> =
      linkedSetOf(
        "cipPrefix",
        "states",
        "region",
        "locale_type",
        "locale_detail",
        "control",
        "minUndergradEnrollmentHeadcount",
        "maxUndergradEnrollmentHeadcount",
        "minAdmissionRateShare",
        "maxAdmissionRateShare",
        "maxNetPricePerYearUsd",
        "minCompletionRate150pct4yrShare",
      )

    private const val CIP_PREFIX_ERROR =
      "cipPrefix must be a 2-, 4-, or 6-digit CIP code, with or without the " +
        "conventional dot (e.g. \"26\", \"2607\" or \"26.07\", \"260702\" or \"26.0702\")"

    private val STATE_CODE_REGEX = Regex("^[A-Za-z]{2}$")

    /** What an advertised word list says when the codebook carries none. */
    private const val NONE_LOADED = "none are loaded in this database"
  }
}

// ---------------------------------------------------------------------------
// The JSON readers and schema builders both boundaries share. File-scope and
// `internal` so CollegeSearchTool's own fields (sort_by, credential_level,
// limit) are read by exactly the same code as the shared ones.
// ---------------------------------------------------------------------------

internal fun fail(reason: String): Result<Nothing> = Result.failure(IllegalArgumentException(reason))

internal fun field(
  input: JsonObject,
  key: String,
): JsonElement? = input[key]?.takeUnless { it is JsonNull }

internal fun optInt(
  input: JsonObject,
  key: String,
): Result<Int?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return fail("$key must be an integer")
  if (prim.isString) return fail("$key must be an integer")
  return Result.success(prim.content.toIntOrNull() ?: return fail("$key must be an integer"))
}

internal fun optDouble(
  input: JsonObject,
  key: String,
): Result<Double?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return fail("$key must be a number")
  if (prim.isString) return fail("$key must be a number")
  return Result.success(prim.content.toDoubleOrNull() ?: return fail("$key must be a number"))
}

internal fun optString(
  input: JsonObject,
  key: String,
): Result<String?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return fail("$key must be a string")
  if (!prim.isString) return fail("$key must be a string")
  return Result.success(prim.content)
}

internal fun optStringList(
  input: JsonObject,
  key: String,
): Result<List<String>?> {
  val el = field(input, key) ?: return Result.success(null)
  val arr = el as? JsonArray ?: return fail("$key must be an array of strings")
  val out = mutableListOf<String>()
  for (item in arr) {
    val prim = item as? JsonPrimitive ?: return fail("$key must be an array of strings")
    if (!prim.isString) return fail("$key must be an array of strings")
    out += prim.content
  }
  return Result.success(out.toList())
}

/** An optional word-enum field: absent is null, an unlisted word names the set. */
internal fun <T> optWordEnum(
  input: JsonObject,
  key: String,
  words: Map<String, T>,
): Result<T?> {
  val word = optString(input, key).getOrElse { return Result.failure(it) } ?: return Result.success(null)
  return Result.success(
    words[word] ?: return fail("$key must be one of [${words.keys.joinToString(", ")}]; got [$word]"),
  )
}

internal fun stringProperty(description: String): JsonObject =
  buildJsonObject {
    put("type", "string")
    put("description", description)
  }

internal fun intProperty(description: String): JsonObject =
  buildJsonObject {
    put("type", "integer")
    put("description", description)
  }

internal fun numberProperty(description: String): JsonObject =
  buildJsonObject {
    put("type", "number")
    put("description", description)
  }

internal fun wordProperty(
  words: Collection<String>,
  description: String?,
): JsonObject =
  buildJsonObject {
    put("type", "string")
    if (words.isNotEmpty()) putJsonArray("enum") { words.forEach { add(it) } }
    if (description != null) put("description", description)
  }

internal fun arrayProperty(
  items: JsonObject,
  description: String,
): JsonObject =
  buildJsonObject {
    put("type", "array")
    put("items", items)
    put("description", description)
  }

internal fun JsonObjectBuilder.putOrNull(
  key: String,
  value: Int?,
) {
  if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(
  key: String,
  value: Double?,
) {
  if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(
  key: String,
  value: String?,
) {
  if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putProperties(properties: Map<String, JsonObject>) {
  properties.forEach { (key, value) -> put(key, value) }
}
