package ed.unicoach.college

import ed.unicoach.db.models.CollegeSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The chat-tool contract for finding a college BY NAME (RFC 154): the same RFC
 * 67 shape [CollegeSearchTool] has — an Anthropic tool [definition] plus a
 * total [execute] that speaks plain [JsonObject] on both ends, with no
 * `:chat` dependency — over [CollegeSearchService.searchByName], the one fuzzy
 * name path in the system and the one the iOS picker already uses.
 *
 * It exists because a school NAMED in words had no route to a `college_id` in
 * chat: [CollegeSearchTool] filters on structured attributes only, and every
 * writer tool (`update_college_list`, the cost and admissions tools) takes an
 * id it will not construct. This tool is that route and nothing more — no
 * filters, no ranking vocabulary, and so no [Codebook]: a name lookup has no
 * coded axis to render.
 *
 * [execute] is total. A zero-match name is `{ "colleges": [], "count": 0 }` — a
 * valid domain outcome — but an UNBUILT index is never rendered that way: it
 * returns the same [INDEX_NOT_BUILT] sentence the structured
 * search uses, because an empty answer out of a full database is a fact no
 * reader could tell from a real zero (RFC 150).
 *
 * The LENGTH rule stays the service's ([CollegeSearchService.MAX_QUERY_LENGTH],
 * asked for through [CollegeSearchService.rejectedInput]); this tool only
 * chooses the shape it is shown in and words it for its own field. A BLANK
 * name is refused here and only here: the service answers a blank query with an
 * empty success for the picker, whose user can see their own empty box, while
 * the coach reading `{ "colleges": [], "count": 0 }` would take it as "no
 * school by that name exists" for an input that named nothing.
 */
class FindCollegeTool(
  private val service: CollegeSearchService,
) {
  val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          put(
            "name",
            stringProperty(
              "The school as the student said it -- the full name, a nickname (\"Mizzou\"), an " +
                "abbreviation (\"UCLA\") or a misspelling. Matching tolerates one keystroke per word " +
                "and matches on fragments and known nicknames, so write the words the student used " +
                "rather than a name you corrected for them.",
            ),
          )
          put(
            "limit",
            intProperty(
              "Maximum number of matches to return; clamped to $MIN_LIMIT..$MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
            ),
          )
        }
        putJsonArray("required") { add("name") }
      }
    }

  /**
   * Looks [input]'s `name` up and serializes the matches. Three same-level
   * steps — read the request, run the lookup, render the answer — with every
   * refusal shape one level down. Unknown fields and type mismatches yield
   * `{ "error": "<reason>" }`; the executor never throws.
   */
  suspend fun execute(input: JsonObject): JsonObject {
    val request = parseRequest(input).getOrElse { return inputErrorObject(it) }
    val matches = service.searchByName(request.name, request.limit).getOrElse { return failureObject(request, it) }
    return matchesObject(matches)
  }

  /** Already defaulted, so [execute] never re-decides [limit]. */
  private data class Request(
    val name: String,
    val limit: Int,
  )

  /**
   * Reads [input] into a [Request], or fails with the sentence the model is
   * shown. Every rejection here is about the WORDS WRITTEN, not the search.
   */
  private fun parseRequest(input: JsonObject): Result<Request> {
    // Refused BY NAME rather than ignored, exactly as `search_colleges` does
    // (RFC 150 D53) -- and through the SAME helper, so "exactly as" is a fact
    // rather than a comment: a model that writes a filter word here is told
    // which tool takes it instead of being silently answered with a bare name
    // lookup it did not ask for.
    unknownFieldsReason(input, KNOWN_FIELDS)?.let { return fail(it) }

    val name = optString(input, "name").getOrElse { return Result.failure(it) } ?: return fail(NAME_REQUIRED)
    // A whitespace-only name is refused HERE, at the tool boundary, and only
    // here: the service still answers a blank query with an empty success
    // (nothing can match nothing) and that rule is untouched (RFC 154 D-C).
    // What differs is the READER. The picker's user sees their own empty box;
    // the coach would see `{"colleges": [], "count": 0}` and read it as "no
    // school by that name exists" -- a factual claim about a name that was
    // never given. The tool picks the honest shape for its own caller.
    if (name.isBlank()) return fail(NAME_BLANK)
    val limit = optInt(input, "limit").getOrElse { return Result.failure(it) } ?: DEFAULT_LIMIT

    return Result.success(Request(name, limit))
  }

  /**
   * The failure map (RFC 154 D-C): an unbuilt index, a rejected input, or a
   * search that actually failed — asked of the service in that order, never
   * type-tested here.
   */
  private fun failureObject(
    request: Request,
    error: Throwable,
  ): JsonObject {
    // Never a page of zero: an unbuilt index would report "no college by that
    // name" out of a full database. Asked of the service rather than
    // type-tested here, because the exception class is `:db`'s and the service
    // is the boundary that owns what `:db` means. The service logs it.
    if (service.isIndexNotBuilt(error)) return errorObject(INDEX_NOT_BUILT)

    // A REJECTED INPUT is not a failed SEARCH. The service owns the length rule
    // (D-C) and hands the rejection up as data; reporting it as `search_failed`
    // / `permanent` would tell the coach the search is DOWN when in fact the
    // words it wrote were too long. It gets the same flat input-error shape a
    // malformed field does, worded for THIS tool's field.
    service.rejectedInput(error)?.let { return errorObject(refusalSentence(it)) }

    // Everything left is the search failing, and gets the `search_failed`
    // shape. Logged with what was asked, because nothing else records it: the
    // envelope carries no name or limit, and the coaching loop logs a tool's
    // input only when the tool THROWS, which this total `execute` never does.
    logger.error(
      "find_college lookup failed: name=[{}] limit=[{}] -- the model was told the search failed",
      request.name,
      request.limit,
      error,
    )
    return searchFailureObject(error)
  }

  private fun matchesObject(matches: List<CollegeSummary>): JsonObject =
    buildJsonObject {
      putJsonArray("colleges") {
        matches.forEach { add(collegeObject(it)) }
      }
      put("count", matches.size)
    }

  private fun collegeObject(college: CollegeSummary): JsonObject =
    buildJsonObject {
      // First key by design, and the SAME word `search_colleges` results use:
      // the id travels between tools under one name, so the model copies rather
      // than translates.
      put("college_id", college.id.value.toString())
      put("name", college.name)
      // City and state are here to TELL TWO MATCHES APART, which is the only
      // question a name lookup leaves open.
      put("city", college.city)
      put("state", college.state)
    }

  companion object {
    const val TOOL_NAME = "find_college"

    /**
     * Deliberately below [CollegeSearchTool.DEFAULT_LIMIT]: these matches are
     * read back to the student to disambiguate a NAME, not browsed.
     */
    const val DEFAULT_LIMIT = 5
    const val MIN_LIMIT = CollegeSearchService.MIN_LIMIT
    const val MAX_LIMIT = CollegeSearchService.MAX_LIMIT

    /** The two fields this tool takes; anything else is refused by name. */
    private val KNOWN_FIELDS: Set<String> = setOf("name", "limit")

    private val logger = LoggerFactory.getLogger(FindCollegeTool::class.java)

    private const val NAME_REQUIRED = "[name] is required"

    /**
     * What the tool says when `name` holds only whitespace. It names the field
     * and asks for the words back, because the caller has an answer to give —
     * unlike an empty result, which would read as a fact about the world.
     */
    private const val NAME_BLANK =
      "[name] must not be blank -- write the school's name as the student said it; " +
        "there is nothing to look up otherwise"

    private const val DESCRIPTION =
      "Look up a real US college BY NAME and get its college_id. Use this whenever the student " +
        "names a school in words -- \"add Mizzou to my list\", \"what does Amherst cost\" -- including " +
        "nicknames, abbreviations and misspellings. Use `${CollegeSearchTool.TOOL_NAME}` instead when the student " +
        "describes the KIND of school they want (by subject, place, size, selectivity or price) " +
        "rather than naming one. Each match carries college_id, the college's stable identifier, " +
        "alongside its name, city and state; copy that college_id verbatim into every other college " +
        "tool that takes one, and never construct or guess an id. Several matches means the name is " +
        "ambiguous: ask the student which one they mean, using the city and state to tell them " +
        "apart. An empty list means no college matched those words."
  }
}
