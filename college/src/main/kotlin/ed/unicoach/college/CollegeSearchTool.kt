package ed.unicoach.college

import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CredentialLevel
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.putIncomeBand
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import kotlinx.serialization.json.JsonObject
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
 *
 * Every coded axis is WORDS in both directions (RFC 147 D45): the filter
 * vocabulary — region, locale, control — is [CollegeQueryVocabulary]'s, shared
 * with the fit lens, and the results render the same words a filter accepts. No
 * published code enters or leaves this tool as a number.
 */
class CollegeSearchTool(
  private val service: CollegeSearchService,
  codebook: Codebook,
) {
  private val vocabulary = CollegeQueryVocabulary(codebook)

  val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          // The shared filter vocabulary first, then this tool's own three
          // fields: ordering, program credential, and the result cap.
          putProperties(vocabulary.schemaProperties())
          put(
            "sort_by",
            wordProperty(
              SORT_BY_WORDS.keys,
              "Result ordering. \"enrollment\" (default): largest undergraduate " +
                "enrollment first; \"admission_rate_share\": most selective first; " +
                "\"net_price_per_year_usd\": cheapest first; \"completion_rate_150pct_4yr_share\": best completion " +
                "first; \"name\": alphabetical. Sorting never filters: colleges " +
                "missing the sort field are listed last, not dropped.",
            ),
          )
          put(
            "credential_level",
            wordProperty(
              CREDENTIAL_LEVEL_WORDS.keys,
              "Require the cipPrefix program at this credential level (e.g. " +
                "\"bachelors\" for a bachelor's degree in the program). Only valid " +
                "together with cipPrefix.",
            ),
          )
          put(
            "limit",
            intProperty(
              "Maximum number of colleges to return; clamped to $MIN_LIMIT..$MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
            ),
          )
        }
        // All fields optional; an implicit result cap is enforced server-side.
        putJsonArray("required") {}
      }
    }

  /**
   * Parses [input] into a [CollegeQuery], runs the search, and serializes the
   * matches. Unknown fields, type mismatches and unknown codebook words yield
   * `{ "error": "<reason>" }`; the executor never throws.
   */
  suspend fun execute(input: JsonObject): JsonObject {
    val query = parseQuery(input).getOrElse { return errorObject(it.message ?: "invalid input") }

    val page =
      service.search(query).getOrElse { error ->
        return searchFailureObject(error)
      }

    return buildJsonObject {
      putJsonArray("colleges") {
        page.matches.forEach { add(matchObject(it)) }
      }
      put("count", page.matches.size)
      // The honest population count (RFC 139): unclamped, so the model can say
      // "total_matches match; showing count".
      put("total_matches", page.totalMatches)
    }
  }

  // ---------------------------------------------------------------------------
  // Input parsing (total — never throws)
  // ---------------------------------------------------------------------------

  /**
   * The shared filter parse plus this tool's own three fields. Unknown keys are
   * refused here rather than in the vocabulary: the fit lens's forced tool and
   * this one offer different field sets around the same filters, so which keys
   * are known is the TOOL's question, not the vocabulary's.
   */
  private fun parseQuery(input: JsonObject): Result<CollegeQuery> {
    val unknown = input.keys - KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
      return fail("unknown field(s): [${unknown.sorted().joinToString(", ")}]")
    }

    val limit = optInt(input, "limit").getOrElse { return Result.failure(it) } ?: DEFAULT_LIMIT
    val sortBy =
      optWordEnum(input, "sort_by", SORT_BY_WORDS).getOrElse { return Result.failure(it) }
        ?: CollegeQuery.SortBy.ENROLLMENT_DESC
    // The word-to-level mapping lives here, at the boundary: raw CREDLEV codes
    // never appear in the tool schema or reach the LLM (brief 0004 amendment).
    val credentialLevel = optWordEnum(input, "credential_level", CREDENTIAL_LEVEL_WORDS).getOrElse { return Result.failure(it) }

    val filters = vocabulary.parse(input, limit).getOrElse { return Result.failure(it) }
    if (credentialLevel != null && filters.cipPrefix == null) {
      return fail("credential_level is only valid together with cipPrefix")
    }

    return Result.success(filters.copy(sortBy = sortBy, credentialLevel = credentialLevel))
  }

  // ---------------------------------------------------------------------------
  // Output serialization
  // ---------------------------------------------------------------------------

  private fun matchObject(match: CollegeMatch): JsonObject =
    buildJsonObject {
      // First key by design: the id the model must copy into
      // `update_college_list`'s `college_id`, ahead of the name — the only
      // other field it could mistake for a handle on the school.
      put("college_id", match.id.value.toString())
      put("name", match.name)
      put("city", match.city)
      put("state", match.state)
      // The label, never the raw IPEDS code (RFC 143): the model sees the
      // vocabulary the coach speaks, from its one home.
      put("control", InstitutionControl.labelFor(match.control))
      // The same words the `region`/`locale_type`/`locale_detail` filters take
      // (RFC 147 D45), so what the model reads back is what it can ask for.
      putOrNull("region", vocabulary.regionWord(match.region))
      val localeCode = match.locale
      val locale = vocabulary.localeOf(localeCode)
      // A stored code the loaded codebook does not carry is named as unknown and
      // still never a bare number -- the InstitutionControl.unknownLabel shape.
      putOrNull(
        "locale_type",
        locale?.type?.word ?: localeCode?.let { vocabulary.unknownLocaleWord(it) },
      )
      putOrNull("locale_detail", locale?.detail?.word)
      putOrNull("undergrad_enrollment_headcount", match.undergradEnrollmentHeadcount)
      putOrNull("admission_rate_share", match.admissionRateShare)
      putOrNull("net_price_per_year_usd", match.netPricePerYearUsd)
      // One self-describing array rather than five opaque `net_price_per_year_income_qN_usd` keys
      // (RFC 142): every amount arrives beside the band code AND the dollar
      // range a coach says aloud, so the model never has to translate a source
      // bucket name into English -- and cannot say "Q5" because it never saw it.
      putJsonArray("net_price_by_income_band") {
        IncomeBand.entries.forEach { band ->
          // An unreported bracket is omitted entirely rather than carried as a
          // labelled null: the array names the bands this college actually
          // reports, so there is nothing to mistake for a price of zero.
          band.netPriceFor(match)?.let { amount ->
            add(
              buildJsonObject {
                putIncomeBand(band)
                put("net_price_per_year_usd", amount)
              },
            )
          }
        }
      }
      putOrNull("completion_rate_150pct_4yr_share", match.completionRate150pct4yrShare)
      putOrNull("median_earnings_10y_after_entry_usd", match.medianEarnings10yAfterEntryUsd)
      putOrNull("median_debt_at_completion_usd", match.medianDebtAtCompletionUsd)
      putOrNull("pell_share", match.pellShare)
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

    /** The shared filter fields plus this tool's own three. */
    private val KNOWN_FIELDS: Set<String> =
      CollegeQueryVocabulary.FIELD_NAMES + setOf("sort_by", "credential_level", "limit")

    /**
     * The `sort_by` word enum → [CollegeQuery.SortBy]. LinkedHashMap order is
     * the schema's enum order (default first).
     */
    private val SORT_BY_WORDS: Map<String, CollegeQuery.SortBy> =
      CollegeQuery.SortBy.entries.associateBy { sortBy ->
        // Exhaustive `when` over the enum, so a sort added to CollegeQuery
        // fails THIS compile rather than silently going unofferable to the LLM.
        when (sortBy) {
          CollegeQuery.SortBy.ENROLLMENT_DESC -> "enrollment"
          CollegeQuery.SortBy.ADMISSION_RATE_SHARE_ASC -> "admission_rate_share"
          CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC -> "net_price_per_year_usd"
          CollegeQuery.SortBy.COMPLETION_RATE_150PCT_4YR_SHARE_DESC -> "completion_rate_150pct_4yr_share"
          CollegeQuery.SortBy.NAME_ASC -> "name"
        }
      }

    /**
     * The `credential_level` word enum → [CredentialLevel], the named level the
     * query carries; the Scorecard CREDLEV code lives only in that enum, so raw
     * codes reach neither the LLM nor this file. [CredentialLevel] deliberately
     * omits 4 (post-bacc certificate), 6 (post-master's certificate) and 8
     * (first professional): real codes, not offered as words.
     */
    private val CREDENTIAL_LEVEL_WORDS: Map<String, CredentialLevel> =
      linkedMapOf(
        "certificate" to CredentialLevel.CERTIFICATE,
        "associate" to CredentialLevel.ASSOCIATE,
        "bachelors" to CredentialLevel.BACHELORS,
        "masters" to CredentialLevel.MASTERS,
        "doctoral" to CredentialLevel.DOCTORAL,
      )

    // Not `const`: the income-band ranges are rendered from IncomeBand.bracket,
    // the one home for that copy (RFC 142), so the description can never drift
    // from the labels the results themselves carry.
    private val DESCRIPTION =
      "Search the College Scorecard dataset of real US colleges by structured " +
        "filters: program of study (CIP code prefix), location (US states, " +
        "region, urbanization), who runs the institution (public/private), " +
        "undergraduate enrollment size, admission rate (selectivity), maximum net " +
        "price (affordability), and minimum completion rate — plus an optional " +
        "`sort_by` ordering and a `credential_level` word for the program filter. " +
        "Every location and control filter is named in plain words, and results " +
        "carry those same words back, so no data-source code is ever read or written. " +
        "The response carries `count` (returned rows, capped at $MAX_LIMIT) and " +
        "`total_matches` (every college matching the filters, uncapped) — cite " +
        "`total_matches` when stating how many schools match. Returns matching real " +
        "institutions with size, selectivity, net price, and outcome context " +
        "(completion rate within 150% of normal time, median earnings, Pell share). Each result carries " +
        "`college_id`, the college's stable identifier — exactly what the " +
        "`update_college_list` tool's `college_id` parameter takes, so copy it " +
        "verbatim from a result and never construct or guess one. Each result also carries " +
        "net_price_by_income_band, the average annual net price for each household income band " +
        "the college reports: one entry per band carrying income_band_label, the band's income " +
        "range in plain words -- " +
        IncomeBand.entries.joinToString(" / ") { it.bracket } +
        " -- alongside the net_price_per_year_usd a family in that band pays. Name a band by that dollar " +
        "range when you say it aloud, never by a data source's own bucket name. Each result also " +
        "carries median_debt_at_completion_usd, the median cumulative federal loan debt of graduates, so " +
        "cost answers can cite the band matching the family's income. This tool filters on " +
        "structured fields only; it CANNOT reason about geographic distance, " +
        "proximity to the coastline, or how close two places are — to approximate " +
        "\"near the ocean\" or a region, pass the relevant set of coastal/nearby " +
        "state codes in `states`."
  }
}
