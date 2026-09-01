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
  init {
    // [Codebook.emptyVocabularies] hand-types the FIELD names it drops from the
    // schema, and nothing checked those strings against the fields that exist.
    // A typo there ("carnegie_classes") would advertise the filter anyway and
    // then refuse every word sent for it — an unusable filter, offered, with no
    // failure anywhere to show for it. One `require` and the drift is a boot
    // crash instead.
    val unknown = codebook.emptyVocabularies - FIELD_NAMES
    require(unknown.isEmpty()) {
      "Codebook.emptyVocabularies names [${unknown.sorted().joinToString(", ")}], " +
        "which the filter vocabulary does not offer: a dropped field must be one of " +
        "[${FIELD_NAMES.joinToString(", ")}]"
    }
  }

  /** The filter fields this vocabulary owns; a tool adds its own around them. */
  val fieldNames: Set<String> = FIELD_NAMES

  /**
   * The JSON-Schema `properties` entries for [fieldNames], in the order a model
   * reads them. Every word enum is the loaded codebook's own list, so a codebook
   * the publisher extends widens the schema with no code change.
   *
   * A field whose vocabulary this database carries NO value for is not offered
   * at all ([Codebook.emptyVocabularies]). Offering it as a bare string would
   * invite a word that the parse then refuses with [Codebook.UNAVAILABLE] — an
   * advertised filter that cannot be used is worse than one the model is never
   * told about, and the boot-time warning names the same fields.
   */
  fun schemaProperties(): Map<String, JsonObject> =
    linkedMapOf<String, JsonObject>(
      "cipPrefix" to
        stringProperty(
          "A 2-, 4-, or 6-digit CIP code prefix to require a matching program " +
            "(e.g. \"26\" biology, \"2613\" ecology, \"260702\" marine biology). " +
            "The conventional dotted notation is also accepted -- \"26.13\" and " +
            "\"26.0702\" mean the same as \"2613\" and \"260702\" -- but the canonical " +
            "form is digits only.",
        ),
      "subject" to
        wordProperty(
          codebook.subjectSlugs,
          "A field of study, as one word from the published subject taxonomy " +
            "(e.g. \"nursing\", \"literature\", \"mechanical-engineering\"). " +
            "This is the RIGHT way to ask about what a student wants to study: " +
            "the word is expanded to the real set of federal program codes the " +
            "college is recorded as offering, so no code has to be guessed. " +
            "Prefer it over cipPrefix, which is an escape hatch.",
        ),
      "states" to
        arrayProperty(
          buildJsonObject { put("type", "string") },
          "Two-letter US state postal codes, as published (e.g. \"CA\", \"NY\"); matches institutions " +
            "in any of them (OR-set). Must name at least one state -- an empty array is refused, " +
            "not read as \"anywhere\".",
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
      // The IPEDS attribute filters (RFC 150 D54). Every one of them is a word
      // from a published reference table, and the search index stores that same
      // word, so the value bound is the value the model wrote.
      "test_policy" to
        wordProperty(
          codebook.testPolicySlugs,
          "The institution's admission-test policy, as a published word: " +
            vocabularyOf(codebook.testPolicySlugs) + ".",
        ),
      "religious_affiliation" to
        wordProperty(
          codebook.religiousAffiliationSlugs,
          "The institution's religious affiliation, as a published word. " +
            "Colleges that report no affiliation carry the published \"none\" word, " +
            "which is different from not reporting one at all.",
        ),
      "carnegie_class" to
        wordProperty(
          codebook.carnegieClassSlugs,
          "The 2021 Carnegie Basic classification, as a published word — what " +
            "KIND of institution it is (a doctoral university, a baccalaureate " +
            "college, and so on).",
        ),
      "carnegie_size" to
        wordProperty(
          codebook.carnegieSizeSlugs,
          "The 2021 Carnegie size-and-setting classification, as a published " +
            "word: how big the institution is and how residential it is.",
        ),
      "athletic_association" to
        wordProperty(
          codebook.athleticAssociationSlugs,
          "An athletic association the institution belongs to, as a published " +
            "word; a college may belong to several and matches if this is one of them.",
        ),
      "has_rotc" to
        booleanProperty(
          "Whether the institution offers ROTC. Colleges that do not report it " +
            "are excluded either way and counted in excluded_unknown -- not " +
            "silently treated as a \"no\".",
        ),
      "has_study_abroad" to
        booleanProperty(
          "Whether the institution offers study abroad. Unreported is excluded " +
            "and counted, never read as \"no\".",
        ),
      "has_housing" to
        booleanProperty(
          "Whether the institution offers on-campus housing. Unreported is " +
            "excluded and counted, never read as \"no\".",
        ),
      // The default universe, overridable per call (D56).
      "is_active" to
        booleanProperty(
          "Whether the institution is currently operating. Defaults to true: a " +
            "closed school is not a school a student can apply to. Pass false to " +
            "look at closed institutions deliberately.",
        ),
      "is_four_year" to
        booleanProperty(
          "Whether the institution is a four-year institution. By default this " +
            "is left open in the honest sense -- four-year and unknown-level " +
            "institutions are both included, and only institutions KNOWN to be " +
            "two-year are excluded. Pass true for four-year only, false for " +
            "two-year only; either way, institutions that do not report their " +
            "level are excluded and counted in excluded_unknown, never read as " +
            "a \"no\".",
        ),
    ).filterKeys { it !in codebook.emptyVocabularies }

  /**
   * Reads every field of [fieldNames] out of [input] into a [CollegeQuery] with
   * the given [limit]. `sortBy` is left at its default — it is one tool's own
   * field, not shared vocabulary.
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

    val normalizedStates = parseStates(input).getOrElse { return Result.failure(it) }

    val subject = parseSlug(input, "subject", codebook.subjectSlugs).getOrElse { return Result.failure(it) }

    // The slug the model wrote IS the value the index stores and the SQL binds
    // (RFC 150 D61): there is no code lookup left on this path.
    val region = parseSlug(input, "region", codebook.regionSlugs).getOrElse { return Result.failure(it) }

    val locales = parseLocales(input).getOrElse { return Result.failure(it) }

    val control = parseControl(input).getOrElse { return Result.failure(it) }

    val testPolicy =
      parseSlug(input, "test_policy", codebook.testPolicySlugs).getOrElse { return Result.failure(it) }
    val religiousAffiliation =
      parseSlug(input, "religious_affiliation", codebook.religiousAffiliationSlugs)
        .getOrElse { return Result.failure(it) }
    val carnegieClass =
      parseSlug(input, "carnegie_class", codebook.carnegieClassSlugs).getOrElse { return Result.failure(it) }
    val carnegieSize =
      parseSlug(input, "carnegie_size", codebook.carnegieSizeSlugs).getOrElse { return Result.failure(it) }
    val athleticAssociation =
      parseSlug(input, "athletic_association", codebook.athleticAssociationSlugs)
        .getOrElse { return Result.failure(it) }

    val hasRotc = optBoolean(input, "has_rotc").getOrElse { return Result.failure(it) }
    val hasStudyAbroad = optBoolean(input, "has_study_abroad").getOrElse { return Result.failure(it) }
    val hasHousing = optBoolean(input, "has_housing").getOrElse { return Result.failure(it) }
    val isActive = optBoolean(input, "is_active").getOrElse { return Result.failure(it) }
    val isFourYear = optBoolean(input, "is_four_year").getOrElse { return Result.failure(it) }

    // Read through the two numeric DOMAIN readers, so this function only
    // assembles: a count is >= 0 and a share is 0.0-1.0 wherever it appears.
    val minUndergrad = optCount(input, "minUndergradEnrollmentHeadcount").getOrElse { return Result.failure(it) }
    val maxUndergrad = optCount(input, "maxUndergradEnrollmentHeadcount").getOrElse { return Result.failure(it) }

    val minAdmission = optShare(input, "minAdmissionRateShare").getOrElse { return Result.failure(it) }
    val maxAdmission = optShare(input, "maxAdmissionRateShare").getOrElse { return Result.failure(it) }

    val maxNetPrice = optUsd(input, "maxNetPricePerYearUsd").getOrElse { return Result.failure(it) }

    val minCompletion = optShare(input, "minCompletionRate150pct4yrShare").getOrElse { return Result.failure(it) }

    return Result.success(
      CollegeQuery(
        cipPrefix = cipPrefix,
        subject = subject,
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
        testPolicy = testPolicy,
        religiousAffiliation = religiousAffiliation,
        carnegieClass = carnegieClass,
        carnegieSize = carnegieSize,
        athleticAssociation = athleticAssociation,
        hasRotc = hasRotc,
        hasStudyAbroad = hasStudyAbroad,
        hasHousing = hasHousing,
        // An absent `is_active` keeps the DEFAULT (true), not "unconstrained":
        // the universe is a default, and a caller who says nothing gets it.
        isActive = isActive ?: true,
        isFourYear = isFourYear,
        limit = limit,
      ),
    )
  }

  /**
   * Every filter this vocabulary parsed, back in WORDS (RFC 153 D70), in schema
   * order — the sentences a tool that reports "the constraints I ran under"
   * reads out.
   *
   * It lives here, beside the parser, for D69's reason: the words a filter is
   * ASKED in and the words it is REPORTED in are one vocabulary, so a filter
   * added to [parse] is reported without a second list somewhere else to
   * forget. A tool that hand-listed its own sentences reported five of the
   * twenty-three filters it had actually applied, which is the acceptance
   * criterion "every response names its axes and constraints" failing rather
   * than a matter of polish.
   *
   * The two UNIVERSE axes (`is_active`, `is_four_year`) are deliberately absent:
   * their default IS the default universe, which the caller states in that
   * universe's own words rather than as two filters among the rest.
   */
  fun listConstraintSentences(filters: CollegeQuery): List<String> =
    buildList {
      filters.cipPrefix?.let { add("programs under CIP prefix [$it]") }
      filters.subject?.let { add("offering the subject [$it]") }
      filters.states?.let { add("in ${it.joinToString(", ")}") }
      filters.region?.let { add("in the [$it] region") }
      filters.locales?.let { slugs ->
        val words = slugs.mapNotNull { slug -> localeOf(slug)?.let { "${it.type.word}/${it.detail.word}" } }
        add("campus setting: ${(words.takeIf { it.isNotEmpty() } ?: slugs).joinToString(", ")}")
      }
      filters.control?.let { add("run as: ${it.joinToString(", ") { control -> control.label }}") }
      filters.minUndergradEnrollmentHeadcount?.let { add("at least $it undergraduates") }
      filters.maxUndergradEnrollmentHeadcount?.let { add("at most $it undergraduates") }
      filters.minAdmissionRateShare?.let { add("admission rate at or above ${mapShareToSpokenPercent(it)}") }
      filters.maxAdmissionRateShare?.let { add("admission rate at or below ${mapShareToSpokenPercent(it)}") }
      filters.maxNetPricePerYearUsd?.let { add("average annual net price at or below ${mapUsdToSpoken(it)}") }
      filters.minCompletionRate150pct4yrShare?.let { add("six-year completion rate at or above ${mapShareToSpokenPercent(it)}") }
      filters.testPolicy?.let { add("testing policy [$it]") }
      filters.religiousAffiliation?.let { add("religious affiliation [$it]") }
      filters.carnegieClass?.let { add("Carnegie classification [$it]") }
      filters.carnegieSize?.let { add("Carnegie size [$it]") }
      filters.athleticAssociation?.let { add("athletic association [$it]") }
      filters.hasRotc?.let { add(if (it) "offering ROTC" else "not offering ROTC") }
      filters.hasStudyAbroad?.let { add(if (it) "offering study abroad" else "not offering study abroad") }
      filters.hasHousing?.let { add(if (it) "offering on-campus housing" else "not offering on-campus housing") }
    }

  /**
   * The two published halves of a `college_search_index.locale` SLUG — the only
   * OUTPUT-side resolution left in this class.
   *
   * The code-facing half is GONE (RFC 150 D54/D61): `regionCode`, `regionWord`,
   * `localeOf(code)` and `unknownLocaleWord` had no caller once the index
   * started storing slugs with real foreign keys. `region` needs no rendering
   * at all now — it comes off the index as the word — and the
   * `unknown (region [N])` shape those functions existed for cannot occur on
   * this path, because a code the codebook does not name is NULL on the index,
   * not a number.
   */
  fun localeOf(slug: String?): Codebook.Locale? = slug?.let { codebook.locale(it) }

  // ---------------------------------------------------------------------------
  // Word resolution
  // ---------------------------------------------------------------------------

  /**
   * The one shape every unresolvable word gets: a NAMED failure listing the
   * vocabulary it was refused against, or saying plainly that none is loaded.
   * Never a dropped filter — a filter that silently disappears answers a
   * narrower question with a wider answer.
   */
  private fun unknownWord(
    field: String,
    word: String,
    vocabulary: List<String>,
  ): String =
    if (vocabulary.isEmpty()) {
      "[$field] cannot be resolved: ${Codebook.UNAVAILABLE}; got [$word]"
    } else {
      "[$field] must be one of [${vocabulary.joinToString(", ")}]; got [$word]"
    }

  /** An optional slug field checked against a closed, loaded word list. */
  private fun parseSlug(
    input: JsonObject,
    key: String,
    vocabulary: List<String>,
  ): Result<String?> {
    val word = optString(input, key).getOrElse { return Result.failure(it) } ?: return Result.success(null)
    if (word !in vocabulary) return fail(unknownWord(key, word, vocabulary))
    return Result.success(word)
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
  private fun parseLocales(input: JsonObject): Result<List<String>?> {
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

    val codes = codebook.localeSlugs(type, detail)
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

  /**
   * `states` -> the UPPERCASE postal codes the index stores.
   *
   * Three refusals, and the third is the one that was missing: an EMPTY array,
   * a code that is not two letters, and a code the LOADED `us_states`
   * vocabulary does not publish. "ZZ" used to pass the regex and come back as a
   * genuine zero-match, which reads to a family as "there are no colleges
   * there" — the same silent narrowing an unknown `region` word has always been
   * refused for, on the one field a model writes most often.
   *
   * State is stored UPPERCASE (Scorecard STABBR) and bound into a
   * case-sensitive SQL comparison, so the case fold happens here: a model
   * emitting "ca" must still match a "CA" row, and must be checked against the
   * vocabulary in the same case the vocabulary is in.
   */
  private fun parseStates(input: JsonObject): Result<List<String>?> {
    val states = optStringList(input, "states").getOrElse { return Result.failure(it) } ?: return Result.success(null)
    if (states.isEmpty()) return fail(EMPTY_SET_ERROR("states"))
    val normalized = states.map { it.uppercase() }
    normalized.forEach { code ->
      if (!STATE_CODE_REGEX.matches(code)) {
        return fail("states must be 2-letter US state postal codes; got [$code]")
      }
      // Checked only against a LOADED `us_states`. Unlike `region`, whose
      // stored value IS a codebook slug, `colleges.state` is a real postal code
      // written by the Scorecard ingest and needs no codebook to match — so on
      // a database that has never run the `codebooks` phase this filter still
      // works, and refusing every code there would delete a working filter
      // rather than protect anyone. The shape check above stands either way.
      if (codebook.stateCodes.isNotEmpty() && code !in codebook.stateCodes) {
        return fail(unknownWord("states", code, codebook.stateCodes))
      }
    }
    return Result.success(normalized)
  }

  /**
   * `control` words -> [InstitutionControl] itself (RFC 150 D61a). There is no
   * `control` codebook table; the enum IS the vocabulary, and the index column
   * carries its underscored label verbatim under a CHECK. The resolved entry is
   * what the query carries, so the guarantee this parse just proved survives to
   * the bind instead of decaying back into an unchecked string.
   */
  private fun parseControl(input: JsonObject): Result<List<InstitutionControl>?> {
    val words = optStringList(input, "control").getOrElse { return Result.failure(it) }
    if (words == null) return Result.success(null)
    if (words.isEmpty()) return fail(EMPTY_SET_ERROR("control"))
    val controls =
      words.map { word ->
        InstitutionControl.entries.firstOrNull { it.label == word }
          ?: return fail(
            "control must be one of [${InstitutionControl.entries.joinToString(", ") { it.label }}]; got [$word]",
          )
      }
    return Result.success(controls)
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
        "subject",
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
        "test_policy",
        "religious_affiliation",
        "carnegie_class",
        "carnegie_size",
        "athletic_association",
        "has_rotc",
        "has_study_abroad",
        "has_housing",
        "is_active",
        "is_four_year",
      )

    private const val CIP_PREFIX_ERROR =
      "cipPrefix must be a 2-, 4-, or 6-digit CIP code, with or without the " +
        "conventional dot (e.g. \"26\", \"2607\" or \"26.07\", \"260702\" or \"26.0702\")"

    private val STATE_CODE_REGEX = Regex("^[A-Za-z]{2}$")

    /**
     * What an EMPTY OR-set array is refused with.
     *
     * `{"states": []}` and `{"control": []}` parsed clean and were then skipped
     * by an `isNotEmpty()` guard at the bind, so a model that meant to narrow
     * the search got every college in the country back and no sign that its
     * filter had gone. That is the exact failure this file refuses a bad WORD
     * for; an empty set is the same failure with no word to name. There is no
     * useful reading of "match none of these": a caller who wants every state
     * omits the field.
     */
    private val EMPTY_SET_ERROR: (String) -> String = { key ->
      "[$key] must name at least one value; got []. An empty array is not a filter -- " +
        "omit [$key] entirely to leave that axis unrestricted"
    }

    /** What an advertised word list says when the codebook carries none. */
    private const val NONE_LOADED = "none are loaded in this database"
  }
}

// ---------------------------------------------------------------------------
// The JSON readers and schema builders both boundaries share. File-scope and
// `internal` so CollegeSearchTool's own fields (sort_by, limit) are read by
// exactly the same code as the shared ones.
// ---------------------------------------------------------------------------

internal fun fail(reason: String): Result<Nothing> = Result.failure(IllegalArgumentException(reason))

internal fun field(
  input: JsonObject,
  key: String,
): JsonElement? = input[key]?.takeUnless { it is JsonNull }

/**
 * The ONE shape a type refusal takes: what the field must be, and WHAT WAS
 * WRITTEN. These strings are the model's only means of self-correction, and
 * without the offending element a `has_rotc` of 1 and a `has_rotc` of "yes"
 * produced the identical sentence — the same defect the word fields solved
 * years ago with their "got [word]" tail. [element] renders as JSON, so a
 * number, a quoted string and an array are visibly different.
 */
internal fun failTypeMismatch(
  key: String,
  expected: String,
  element: JsonElement,
): Result<Nothing> = fail("[$key] must be $expected; got [$element]")

internal fun optInt(
  input: JsonObject,
  key: String,
): Result<Int?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return failTypeMismatch(key, "an integer", el)
  if (prim.isString) return failTypeMismatch(key, "an integer", el)
  return Result.success(prim.content.toIntOrNull() ?: return failTypeMismatch(key, "an integer", el))
}

internal fun optDouble(
  input: JsonObject,
  key: String,
): Result<Double?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return failTypeMismatch(key, "a number", el)
  if (prim.isString) return failTypeMismatch(key, "a number", el)
  return Result.success(prim.content.toDoubleOrNull() ?: return failTypeMismatch(key, "a number", el))
}

/**
 * A non-negative count: absent is null, a negative value is a named refusal.
 * Beside [optInt] rather than inlined at each call site, so "a count is >= 0" is
 * stated once for every field that is one.
 */
internal fun optCount(
  input: JsonObject,
  key: String,
): Result<Int?> {
  val value = optInt(input, key).getOrElse { return Result.failure(it) } ?: return Result.success(null)
  if (value < 0) return fail("[$key] must be >= 0")
  return Result.success(value)
}

/**
 * An amount of MONEY in whole US dollars: absent is null, a negative amount is a
 * named refusal.
 *
 * The same bound as [optCount] and deliberately not the same function. A price
 * is not a count of anything, and reading the one money filter this vocabulary
 * has through a helper whose contract reads "a non-negative count" is how the
 * two drift: the day a dollar figure needs a different bound — cents, a ceiling,
 * a currency — the money reading has a home to change instead of a shared count
 * to fork.
 */
internal fun optUsd(
  input: JsonObject,
  key: String,
): Result<Int?> {
  val value = optInt(input, key).getOrElse { return Result.failure(it) } ?: return Result.success(null)
  if (value < 0) return fail("[$key] must be >= 0")
  return Result.success(value)
}

/** A share: absent is null, a value outside 0.0-1.0 is a named refusal. */
internal fun optShare(
  input: JsonObject,
  key: String,
): Result<Double?> {
  val value = optDouble(input, key).getOrElse { return Result.failure(it) } ?: return Result.success(null)
  if (value !in 0.0..1.0) return fail("[$key] must be 0.0-1.0")
  return Result.success(value)
}

/**
 * A JSON string element, or null when it is absent or not a string. The ONE
 * element-level string reader the `college` package has: the loaders
 * ([SubjectLoader], [CodebookLoader]) read authored/generated files with it and
 * report a miss in their own voice, exactly as [optString] does for the
 * model-facing boundary.
 */
internal fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON array of strings, or null when it is absent, not an array, or holds a non-string. */
internal fun stringListOf(element: JsonElement?): List<String>? {
  val array = element as? JsonArray ?: return null
  return array.map { item -> stringOf(item) ?: return null }
}

internal fun optString(
  input: JsonObject,
  key: String,
): Result<String?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return failTypeMismatch(key, "a string", el)
  if (!prim.isString) return failTypeMismatch(key, "a string", el)
  return Result.success(prim.content)
}

internal fun optStringList(
  input: JsonObject,
  key: String,
): Result<List<String>?> {
  val el = field(input, key) ?: return Result.success(null)
  val arr = el as? JsonArray ?: return failTypeMismatch(key, "an array of strings", el)
  val out = mutableListOf<String>()
  for (item in arr) {
    // The whole array is echoed, not the one bad element: the model has to
    // rewrite the array, and seeing it whole is what tells it which one is bad.
    val prim = item as? JsonPrimitive ?: return failTypeMismatch(key, "an array of strings", el)
    if (!prim.isString) return failTypeMismatch(key, "an array of strings", el)
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

internal fun optBoolean(
  input: JsonObject,
  key: String,
): Result<Boolean?> {
  val el = field(input, key) ?: return Result.success(null)
  val prim = el as? JsonPrimitive ?: return failTypeMismatch(key, "a boolean", el)
  if (prim.isString) return failTypeMismatch(key, "a boolean", el)
  return Result.success(
    when (prim.content) {
      "true" -> true
      "false" -> false
      else -> return failTypeMismatch(key, "a boolean", el)
    },
  )
}

internal fun booleanProperty(description: String): JsonObject =
  buildJsonObject {
    put("type", "boolean")
    put("description", description)
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
