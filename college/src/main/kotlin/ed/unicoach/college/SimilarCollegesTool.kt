package ed.unicoach.college

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSimilarityOutcome
import ed.unicoach.db.models.CollegeSimilarityPage
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.db.models.DEFAULT_UNIVERSE_SENTENCE
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.SimilarityAnchor
import ed.unicoach.db.models.SimilarityAnchorOutcome
import ed.unicoach.db.models.SimilarityAxis
import ed.unicoach.db.models.SimilarityMatch
import ed.unicoach.db.models.SimilarityQuery
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "What schools are like Bowdoin?" as a chat tool (RFC 153).
 *
 * The same shape [CollegeSearchTool] has — a definition built once at
 * construction, a TOTAL [execute] that returns `{ "error": ... }` rather than
 * throwing, and the shared [CollegeQueryVocabulary] for every filter — with one
 * difference that is the whole slice: there is an ANCHOR, and "similar" is a
 * per-call choice of axes and weights rather than a stored method (D8 as
 * amended, D62). Nothing is precomputed and nothing is written: one `SELECT`
 * over the percentile columns RFC 150 landed, ranked by a distance the caller
 * can see in the response.
 *
 * Three refusals it makes rather than guess, because each of them is a wrong
 * answer the student could not detect: an ambiguous name (D63), an anchor with
 * no position to measure from (D64), and an axis the anchor cannot be measured
 * on, which is DROPPED and named rather than scored as a zero (D67).
 */
class SimilarCollegesTool(
  private val service: CollegeSearchService,
  codebook: Codebook,
) {
  private val vocabulary = CollegeQueryVocabulary(codebook)

  val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        putJsonObject("properties") {
          put(
            "college_id",
            stringProperty(
              "The anchor college's stable identifier, copied verbatim from another tool's result. " +
                "Write this OR `name`, never both.",
            ),
          )
          put(
            "name",
            stringProperty(
              "The anchor college's name, when you have no `college_id` for it. If the name matches more " +
                "than one college the tool refuses and lists them, so you can ask the student which one " +
                "they mean rather than guessing.",
            ),
          )
          put(
            "axes",
            arrayProperty(
              wordProperty(AXIS_WORDS.keys, null),
              "What \"similar\" means for this question. Defaults to [" +
                SimilarityAxis.DEFAULTS.joinToString(", ") { it.word } +
                "], the character of a school rather than its budget. \"size\" is undergraduate " +
                "enrollment, \"selectivity\" is admission rate and SAT average together, \"price\" is " +
                "average annual net price, \"setting\" is the urbanization of where the campus is, and " +
                "\"subjects\" is the overlap in fields of study offered. Name \"price\" only when the " +
                "student asked about cost.",
            ),
          )
          put(
            "weights",
            weightsProperty(),
          )
          put(
            "cheaper_than_anchor",
            booleanProperty(
              "Keep only colleges whose average annual net price is below the anchor's. The tool reads " +
                "the anchor's own price and says the figure back in `constraints_used`. A college that " +
                "does not report a net price is excluded and counted, never kept as maybe-cheaper.",
            ),
          )
          put(
            "easier_to_admit_than_anchor",
            booleanProperty(
              "Keep only colleges whose admission rate is higher than the anchor's. This also DROPS the " +
                "\"selectivity\" axis for the call, because ranking toward the anchor's selectivity while " +
                "the constraint pushes away from it would return the least-relaxed schools that qualify. " +
                "The drop is reported in `axes_dropped`.",
            ),
          )
          // The shared filter vocabulary, unchanged (D69): the same words,
          // the same refusals, no second dialect for the same concepts.
          putProperties(vocabulary.schemaProperties())
          put(
            "limit",
            intProperty(
              "Maximum number of colleges to return; clamped to $MIN_LIMIT..$MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
            ),
          )
        }
        put("type", "object")
        // Nothing is required by the schema: the anchor rule (exactly one of
        // `college_id` and `name`) is a SENTENCE the tool answers with, because
        // "one of these two" is not something a JSON Schema `required` states.
        putJsonArray("required") {}
      }
    }

  /**
   * Resolves the anchor, plans the query, runs it, and serializes the ranked
   * peers. Four steps and no more; every per-call decision of D65-D68 lives in
   * [createQueryPlan], and all ranking is SQL.
   */
  suspend fun execute(input: JsonObject): JsonObject {
    val unknown = input.keys - KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
      return errorObject("unknown field(s): [${unknown.sorted().joinToString(", ")}]")
    }

    val anchor =
      when (val resolved = findAnchor(input)) {
        is AnchorResolution.Refused -> return resolved.refusal
        is AnchorResolution.Resolved -> resolved.anchor
      }

    // D64: an anchor outside the default universe carries NULL on every
    // percentile, so a distance against it would rank the whole database by
    // nothing. A named refusal beats a zero page (the INDEX_NOT_BUILT
    // precedent), and it is the one sentence that tells the coach WHY.
    if (!anchor.inDefaultUniverse) {
      return anchorNotRankableObject(
        "[${anchor.name}] is not an active four-year institution, so it has no comparable size, " +
          "selectivity or price position",
      )
    }

    val planned =
      when (val plan = createQueryPlan(input, anchor)) {
        is PlanResolution.Refused -> return plan.refusal
        is PlanResolution.Planned -> plan
      }

    val outcome =
      service.findSimilar(planned.query).getOrElse { error -> return searchFailureObject(error) }
    val page =
      when (outcome) {
        is CollegeSimilarityOutcome.UnresolvableProgramFilter -> return errorObject(refusalSentence(outcome.refusal))
        is CollegeSimilarityOutcome.IndexNotBuilt -> return errorObject(INDEX_NOT_BUILT)
        is CollegeSimilarityOutcome.Page -> outcome.page
      }

    return resultObject(planned.query, planned.dropped, page)
  }

  // ---------------------------------------------------------------------------
  // The anchor (D63)
  // ---------------------------------------------------------------------------

  /** Either the anchor, or the finished refusal that replaces the whole answer. */
  private sealed interface AnchorResolution {
    data class Resolved(
      val anchor: SimilarityAnchor,
    ) : AnchorResolution

    data class Refused(
      val refusal: JsonObject,
    ) : AnchorResolution
  }

  /**
   * The anchor, by id or by name — the existing name path (D63), never a second
   * notion of "which college did they mean".
   *
   * Ambiguity REFUSES and lists the candidates. Guessing the top match is the
   * one failure the student cannot detect: a peer list for the wrong Washington
   * reads perfectly fluent and is entirely wrong, and the refusal costs one
   * conversational turn.
   */
  private suspend fun findAnchor(input: JsonObject): AnchorResolution {
    val id = optString(input, "college_id").getOrElse { return createRefusal(mapErrorReason(it)) }
    val name = optString(input, "name").getOrElse { return createRefusal(mapErrorReason(it)) }

    // A blank name SATISFIES "exactly one of the two" and then reaches the name
    // search, which answers "no college matches the name [   ]" -- a statement
    // about the database, for an input that named no college at all.
    if (name != null && name.isBlank()) {
      return createRefusal("[name] must be the college's name; got a blank string, which names no college")
    }

    return when {
      id != null && name != null -> {
        createRefusal("write [college_id] or [name], never both -- they can name two different colleges")
      }

      id != null -> {
        findAnchorById(id)
      }

      name != null -> {
        findAnchorByName(name)
      }

      else -> {
        createRefusal("name the anchor college: write its [college_id] if you have one, otherwise its [name]")
      }
    }
  }

  /** The anchor by id: the identifier another tool's result handed the model, verbatim. */
  private suspend fun findAnchorById(id: String): AnchorResolution {
    val uuid =
      runCatching { UUID.fromString(id) }.getOrNull() ?: return createRefusal(
        "[college_id] must be a college identifier copied verbatim from a tool result; got [$id]",
      )
    return loadAnchor(CollegeId(uuid), id)
  }

  /**
   * The anchor by name (D63): the existing fuzzy resolver, refusing rather than
   * guessing.
   *
   * The three failure states are ASKED OF THE SERVICE in order — an unbuilt
   * index, a rejected input, a search that actually failed — never type-tested
   * here (RFC 154's binding convention). The exception classes are `:db`'s and
   * the service is the boundary that owns what `:db` means; a tool that tested
   * them itself would break the day the DAO reported the state some other way,
   * and `[handleFailures]` catches `Exception` around the JDBC driver, so a
   * class test here would also answer a different question from the one asked.
   */
  private suspend fun findAnchorByName(name: String): AnchorResolution {
    val candidates =
      service.searchByName(name, ANCHOR_CANDIDATES).getOrElse { error ->
        // Never a page of zero, and never "that school does not exist": an
        // unbuilt index is a DEPLOYMENT state, said in the one sentence every
        // college tool says it in.
        if (service.isIndexNotBuilt(error)) return createRefusal(INDEX_NOT_BUILT)
        // A REJECTED INPUT is not a failed SEARCH: the service owns the length
        // rule and hands the rejection up as data, worded here for the field
        // this tool actually takes.
        service.rejectedInput(error)?.let { return createRefusal(refusalSentence(it)) }
        return createRefusal(searchFailureObject(error))
      }
    if (candidates.isEmpty()) return createRefusal("no college matches the name [$name]")

    val chosen = getCandidate(name, candidates) ?: return createRefusal(anchorAmbiguousObject(name, candidates))
    return loadAnchor(chosen.id, chosen.id.value.toString())
  }

  /**
   * D63's choice, alone and pure: the student's own words matching one college's
   * own name is not a guess, and a single candidate is the only college that
   * could be meant. Anything else is genuinely ambiguous, and is refused rather
   * than picked.
   */
  private fun getCandidate(
    name: String,
    candidates: List<CollegeSummary>,
  ): CollegeSummary? {
    val exact = candidates.filter { it.name.equals(name.trim(), ignoreCase = true) }
    return when {
      exact.size == 1 -> exact.single()
      candidates.size == 1 -> candidates.single()
      else -> null
    }
  }

  /**
   * The anchor's index row, or the refusal that replaces the answer — one arm
   * per outcome, so the DEPLOYMENT state and the DOMAIN state are answered with
   * different sentences.
   *
   * An unbuilt index used to arrive here as the same null "this database holds
   * no such college" does, so a database that had never been ingested told the
   * coach the student's school does not exist: the false zero RFC 150 forbids,
   * and the one the NAME path had already been taught to refuse.
   */
  private suspend fun loadAnchor(
    id: CollegeId,
    written: String,
  ): AnchorResolution =
    when (
      val outcome =
        service.findSimilarityAnchor(id).getOrElse { return createRefusal(searchFailureObject(it)) }
    ) {
      is SimilarityAnchorOutcome.IndexNotBuilt -> createRefusal(INDEX_NOT_BUILT)
      is SimilarityAnchorOutcome.NoSuchCollege -> createRefusal("no college has [college_id] [$written]")
      is SimilarityAnchorOutcome.Found -> AnchorResolution.Resolved(outcome.anchor)
    }

  private fun createRefusal(sentence: String): AnchorResolution = AnchorResolution.Refused(errorObject(sentence))

  private fun createRefusal(refusal: JsonObject): AnchorResolution = AnchorResolution.Refused(refusal)

  // ---------------------------------------------------------------------------
  // The plan: every per-call decision (D65-D68)
  // ---------------------------------------------------------------------------

  /** Either the query this call will run, with the axes it had to drop, or the finished refusal. */
  private sealed interface PlanResolution {
    data class Planned(
      val query: SimilarityQuery,
      val dropped: List<DroppedAxis>,
    ) : PlanResolution

    data class Refused(
      val refusal: JsonObject,
    ) : PlanResolution
  }

  /**
   * Every per-call decision in ONE place: the axes and their weights, the
   * anchor-relative constraints, the axes dropped with their reasons, and the
   * shared filter vocabulary.
   *
   * The anchor-relative constraints are validated BEFORE the drops they cause.
   * `easier_to_admit_than_anchor` against an anchor that reports no admission
   * rate used to be answered with the selectivity DROP's sentence — a refusal
   * about ranking, for a constraint that could not be honoured — so the caller
   * had to retry to learn the real reason.
   */
  private fun createQueryPlan(
    input: JsonObject,
    anchor: SimilarityAnchor,
  ): PlanResolution {
    val requested =
      when (val selection = parseAxes(input)) {
        is AxisSelection.Refused -> return PlanResolution.Refused(selection.refusal)
        is AxisSelection.Selected -> selection.axes
      }
    val weights =
      when (val selection = parseWeights(input, requested)) {
        is WeightSelection.Refused -> return PlanResolution.Refused(selection.refusal)
        is WeightSelection.Weighted -> selection.weights
      }
    val easier =
      optBoolean(input, "easier_to_admit_than_anchor").getOrElse {
        return PlanResolution.Refused(errorObject(mapErrorReason(it)))
      } ?: false
    val cheaper =
      optBoolean(input, "cheaper_than_anchor").getOrElse { return PlanResolution.Refused(errorObject(mapErrorReason(it))) }
        ?: false
    val written = optInt(input, "limit").getOrElse { return PlanResolution.Refused(errorObject(mapErrorReason(it))) }
    // The schema advertises `$MIN_LIMIT..$MAX_LIMIT`, and `0` is "return
    // nothing" -- an instruction the service's own clamp used to answer with
    // ONE college, which is neither what was asked nor a refusal of it.
    if (written != null && written < MIN_LIMIT) {
      return PlanResolution.Refused(
        errorObject("[limit] must be at least [$MIN_LIMIT]; got [$written]. A peer list of none is not an answer"),
      )
    }
    val limit = written ?: DEFAULT_LIMIT

    val constraints =
      when (val expanded = expandConstraints(anchor, cheaper = cheaper, easier = easier)) {
        is ConstraintResolution.Refused -> return PlanResolution.Refused(expanded.refusal)
        is ConstraintResolution.Expanded -> expanded
      }

    val dropped = listDroppedAxes(requested, anchor, easier)
    // The axis and the anchor's value on it, resolved together: an axis that
    // survives the drops carries the figure the SQL binds, so no dropped axis
    // can reach the distance expression at all.
    val axes = requested.filterNot { axis -> dropped.any { it.axis == axis } }.mapNotNull { anchor.anchoredOn(it) }
    if (axes.isEmpty()) {
      // A DIFFERENT fact from D64's, so a different kind: the anchor is
      // rankable, but nothing this call asked to rank on survived. One
      // machine-readable kind meaning two things is not machine-readable.
      return PlanResolution.Refused(noRankableAxisObject(dropped, anchor))
    }

    val filters = vocabulary.parse(input, limit).getOrElse { return PlanResolution.Refused(errorObject(mapErrorReason(it))) }
    return PlanResolution.Planned(
      SimilarityQuery(
        anchor = anchor,
        axes = axes.associateWith { anchored -> weights[anchored.axis] ?: SimilarityAxis.DEFAULT_WEIGHT },
        // D65's default constraint: the same control as the anchor, unless the
        // caller wrote a `control` filter of its own, which is a narrower
        // question the student actually asked.
        filters = if (filters.control == null) filters.copy(control = getControlFilter(anchor)) else filters,
        cheaperThanUsd = constraints.cheaperThanUsd,
        easierToAdmitThanShare = constraints.easierToAdmitThanShare,
      ),
      dropped,
    )
  }

  /** Either D68's two anchor-relative FIGURES, or the refusal that names the one the anchor cannot state. */
  private sealed interface ConstraintResolution {
    data class Expanded(
      val cheaperThanUsd: Int?,
      val easierToAdmitThanShare: Double?,
    ) : ConstraintResolution

    data class Refused(
      val refusal: JsonObject,
    ) : ConstraintResolution
  }

  /**
   * D68's constraints as the anchor's own FIGURES: the coach cannot know
   * Bowdoin's net price before it calls, so the flag is expanded here and the
   * number is what the query carries.
   *
   * A constraint the anchor states no figure for is refused BY NAME, never
   * answered with a sentence about ranking.
   */
  private fun expandConstraints(
    anchor: SimilarityAnchor,
    cheaper: Boolean,
    easier: Boolean,
  ): ConstraintResolution {
    val price =
      if (!cheaper) {
        null
      } else {
        anchor.netPricePerYearUsd ?: return ConstraintResolution.Refused(
          errorObject(missingFigureReason("cheaper_than_anchor", anchor.name, "an average annual net price", "cheaper")),
        )
      }
    val rate =
      if (!easier) {
        null
      } else {
        anchor.admissionRateShare ?: return ConstraintResolution.Refused(
          errorObject(missingFigureReason("easier_to_admit_than_anchor", anchor.name, "an admission rate", "easier")),
        )
      }
    return ConstraintResolution.Expanded(price, rate)
  }

  /** The one sentence both anchor-relative refusals are said in. */
  private fun missingFigureReason(
    field: String,
    anchorName: String,
    figure: String,
    comparison: String,
  ): String =
    "[$field] cannot be answered: [$anchorName] does not report $figure, so there is no figure to be " +
      "$comparison than"

  // ---------------------------------------------------------------------------
  // Axes and weights (D65-D67)
  // ---------------------------------------------------------------------------

  /** Either the axes this call ranks on, or the finished refusal that replaces the whole answer. */
  private sealed interface AxisSelection {
    data class Selected(
      val axes: List<SimilarityAxis>,
    ) : AxisSelection

    data class Refused(
      val refusal: JsonObject,
    ) : AxisSelection
  }

  /** Either the per-axis weights this call was given, or the finished refusal. */
  private sealed interface WeightSelection {
    data class Weighted(
      val weights: Map<SimilarityAxis, Double>,
    ) : WeightSelection

    data class Refused(
      val refusal: JsonObject,
    ) : WeightSelection
  }

  /**
   * The axes the caller named, or the defaults; an unknown word is refused BY
   * NAME.
   *
   * A refused axis is an expected QUESTION, not a system failure, so the
   * refusal travels as the payload it will be answered with — the shape
   * [AnchorResolution] already uses — rather than as an exception whose message
   * has to be mined back out with a fallback that can never be right.
   */
  private fun parseAxes(input: JsonObject): AxisSelection {
    val words =
      optStringList(input, "axes").getOrElse { return AxisSelection.Refused(errorObject(mapErrorReason(it))) }
        ?: return AxisSelection.Selected(SimilarityAxis.DEFAULTS)
    if (words.isEmpty()) {
      return AxisSelection.Refused(
        errorObject(
          "[axes] must name at least one axis; got []. An empty array is not a question -- omit [axes] to " +
            "rank on [${SimilarityAxis.DEFAULTS.joinToString(", ") { it.word }}]",
        ),
      )
    }
    val axes = mutableListOf<SimilarityAxis>()
    for (word in words) {
      val axis =
        AXIS_WORDS[word] ?: return AxisSelection.Refused(
          errorObject("[axes] must be one of [${AXIS_WORDS.keys.joinToString(", ")}]; got [$word]"),
        )
      // A repeat is not a narrower question, it is a mis-generated array: the
      // dedupe that used to absorb it left the caller believing it had asked
      // for something the tool silently did not do.
      if (axis in axes) {
        return AxisSelection.Refused(
          errorObject("[axes] names [$word] more than once; write each axis exactly once"),
        )
      }
      axes += axis
    }
    return AxisSelection.Selected(axes.toList())
  }

  /**
   * Per-axis weights, clamped to `[MIN_WEIGHT, MAX_WEIGHT]` and then normalised
   * by the distance's own denominator — so weights are RATIOS, and scaling all
   * of them changes nothing WHILE THEY STAY IN RANGE. The clamp runs first and
   * is not scale-preserving: `{size: 10, price: 1}` scaled by ten becomes
   * `{10, 10}`, an equal weighting the caller never wrote. A weight naming an
   * axis this call does not rank on is a refusal, not a silent no-op, and so is
   * a weight with no number: `{"size": null}` is the caller saying nothing
   * about a weight it meant to set, which the model can only fix if it is told.
   */
  private fun parseWeights(
    input: JsonObject,
    axes: List<SimilarityAxis>,
  ): WeightSelection {
    val element = field(input, "weights") ?: return WeightSelection.Weighted(emptyMap())
    val obj =
      element as? JsonObject ?: return WeightSelection.Refused(
        errorObject("[weights] must be an object of axis words to numbers; got [$element]"),
      )
    // `{}` is the same caller mistake `[axes]: []` is refused for, with no
    // member to name: an object that weights nothing was read as "no weights
    // supplied", so a model that meant to weight an axis was never told.
    if (obj.isEmpty()) {
      return WeightSelection.Refused(
        errorObject(
          "[weights] must weight at least one axis; got {}. An empty object is not a weighting -- omit " +
            "[weights] entirely to count every axis equally",
        ),
      )
    }
    val weights = mutableMapOf<SimilarityAxis, Double>()
    for ((word, value) in obj) {
      val axis =
        AXIS_WORDS[word] ?: return WeightSelection.Refused(
          errorObject("[weights] must be one of [${AXIS_WORDS.keys.joinToString(", ")}]; got [$word]"),
        )
      if (axis !in axes) {
        return WeightSelection.Refused(
          errorObject("[weights] names [$word], which is not among the axes this call ranks on"),
        )
      }
      val number =
        optDouble(obj, word).getOrElse { return WeightSelection.Refused(errorObject(mapErrorReason(it))) }
          ?: return WeightSelection.Refused(errorObject("[weights] [$word] must be a number; got [$value]"))
      // A NEGATIVE weight is not a small ratio, it is the opposite
      // instruction; clamping it to the minimum invented an input the model
      // never wrote and then reported it back in `axes_used` as fact. The clamp
      // stays, inside the domain a weight can have.
      if (number < 0.0) {
        return WeightSelection.Refused(
          errorObject(
            "[weights] [$word] must not be negative; got [$number]. A weight is a RATIO of how much an axis " +
              "counts, so there is no reading of a negative one -- write 0 to count it as little as possible",
          ),
        )
      }
      weights[axis] = number.coerceIn(SimilarityAxis.MIN_WEIGHT, SimilarityAxis.MAX_WEIGHT)
    }
    return WeightSelection.Weighted(weights.toMap())
  }

  /** One axis this call cannot rank on, and WHY — the FACT, not its wording. */
  private data class DroppedAxis(
    val axis: SimilarityAxis,
    val reason: DropReason,
  )

  /**
   * The two distinct facts that drop an axis (D67, D68): the anchor carries no
   * figure on it, or the caller's own relaxation contradicts ranking on it.
   *
   * They used to be one rendered sentence composed during planning, so nothing
   * downstream could branch on, count or log the difference — and several of
   * those sentences never name their own axis.
   */
  private sealed interface DropReason {
    /** The ANCHOR cannot be measured on the axis, so no candidate can be judged against it. */
    data object AnchorUnmeasured : DropReason

    /** `easier_to_admit_than_anchor` relaxes selectivity, so ranking on it would pull the results back. */
    data object RelaxedByConstraint : DropReason
  }

  /** The drop in the words the model reads, composed at serialization and never before. */
  private fun mapDropToSentence(
    drop: DroppedAxis,
    anchor: SimilarityAnchor,
  ): String =
    when (drop.reason) {
      DropReason.RelaxedByConstraint -> {
        "you asked for schools easier to get into than ${anchor.name}, so ranking on selectivity " +
          "would pull the results back toward it -- relaxing selectivity means relaxing it"
      }

      DropReason.AnchorUnmeasured -> {
        missingAxisReason(drop.axis, anchor)
      }
    }

  /**
   * D67's first case, and D68's deliberate relaxation: an axis the ANCHOR
   * cannot be measured on is dropped for the whole query, so every candidate is
   * judged on the same footing; and `easier_to_admit_than_anchor` drops
   * selectivity, because a ranking pulling candidates back toward the anchor
   * while the constraint pushes them away would return the least-relaxed
   * schools that technically qualify. Both are NAMED — never silent.
   */
  private fun listDroppedAxes(
    requested: List<SimilarityAxis>,
    anchor: SimilarityAnchor,
    easierToAdmit: Boolean,
  ): List<DroppedAxis> =
    requested.mapNotNull { axis ->
      when {
        axis == SimilarityAxis.SELECTIVITY && easierToAdmit -> {
          DroppedAxis(axis, DropReason.RelaxedByConstraint)
        }

        anchor.anchoredOn(axis) == null -> {
          DroppedAxis(axis, DropReason.AnchorUnmeasured)
        }

        else -> {
          null
        }
      }
    }

  private fun missingAxisReason(
    axis: SimilarityAxis,
    anchor: SimilarityAnchor,
  ): String =
    when (axis) {
      SimilarityAxis.SIZE -> {
        "${anchor.name}'s undergraduate enrollment is unreported"
      }

      SimilarityAxis.SELECTIVITY -> {
        "${anchor.name}'s admission rate and SAT average are both unreported"
      }

      SimilarityAxis.PRICE -> {
        "${anchor.name}'s average annual net price is unreported"
      }

      SimilarityAxis.SETTING -> {
        "${anchor.name}'s campus setting is unreported"
      }

      // `{}` is NOT "reports no programs": schema 0064 stores NULL for "no
      // program census at all" and `{}` for "the programs are known and none of
      // them is a taxonomy subject". Both drop the axis, for different reasons,
      // and saying the wrong one is a false statement about the college.
      SimilarityAxis.SUBJECTS -> {
        if (anchor.subjectSlugs == null) {
          "${anchor.name} reports no programs, so there is no field of study to compare"
        } else {
          "${anchor.name}'s reported programs name no subject in the taxonomy, so there is nothing to compare"
        }
      }
    }

  /**
   * The anchor's own control, as the default same-control constraint (D65), or
   * null when the anchor's stored label is not one this vocabulary defines.
   *
   * `InstitutionControl.labelFor` is TOTAL: an extended source code arrives as
   * `unknown (control [N])`, which names no control. The miss is AUDIBLE rather
   * than silent — [controlSentence] says the constraint could not be applied —
   * because a wider peer set the response still claimed was same-control is
   * exactly the invisible wrongness D67's posture refuses everywhere else.
   */
  private fun getControlFilter(anchor: SimilarityAnchor): List<InstitutionControl>? = anchor.control?.let { listOf(it) }

  // ---------------------------------------------------------------------------
  // Output serialization (D70)
  // ---------------------------------------------------------------------------

  private fun resultObject(
    query: SimilarityQuery,
    dropped: List<DroppedAxis>,
    page: CollegeSimilarityPage,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("anchor") {
        put(
          "college_id",
          query.anchor.id.value
            .toString(),
        )
        put("name", query.anchor.name)
        put("state", query.anchor.state)
        put("control", query.anchor.controlLabel)
      }
      putJsonArray("axes_used") {
        query.axes.forEach { (anchored, weight) ->
          add(
            buildJsonObject {
              put("axis", anchored.axis.word)
              put("weight", weight)
            },
          )
        }
      }
      // Never silent: an axis nobody could measure is said, with its reason, on
      // every response that dropped one.
      putAxesDropped(dropped, query.anchor)
      putJsonArray("constraints_used") {
        listConstraintSentences(query).forEach { add(it) }
      }
      putJsonArray("colleges") {
        page.matches.forEach { add(similarObject(it)) }
      }
      put("total_candidates", page.totalCandidates)
      putExcludedUnknown(page.excludedUnknown)
      putSourceYears(page.sourceYears)
    }

  /**
   * The ONE rendering of `axes_dropped`: every axis this call could not rank
   * on, named with its reason — the same array whether the call was answered or
   * refused for having no axis left.
   */
  private fun JsonObjectBuilder.putAxesDropped(
    dropped: List<DroppedAxis>,
    anchor: SimilarityAnchor,
  ) {
    putJsonArray("axes_dropped") {
      dropped.forEach { drop ->
        add(
          buildJsonObject {
            put("axis", drop.axis.word)
            put("reason", mapDropToSentence(drop, anchor))
          },
        )
      }
    }
  }

  /**
   * The query, in words (D70). EVERY constraint the SQL applied says itself:
   * the universe, the way the peers are run, the shared vocabulary's own
   * filters, and the two the tool expanded against the anchor's own figures —
   * the coach could not have known those numbers before the call, so it must be
   * able to read them back.
   */
  private fun listConstraintSentences(query: SimilarityQuery): List<String> =
    buildList {
      addAll(universeSentences(query.filters))
      add(controlSentence(query))
      // The shared vocabulary renders its OWN filters (D69: one dialect), so a
      // filter added there is reported here without a second list to forget.
      // `control` is stated above instead, because only this tool knows whether
      // it came from the anchor or from the caller.
      addAll(vocabulary.listConstraintSentences(query.filters.copy(control = null)))
      query.cheaperThanUsd?.let { price ->
        add("average annual net price below ${mapUsdToSpoken(price)} (${query.anchor.name}'s own net price)")
      }
      query.easierToAdmitThanShare?.let { rate ->
        add("admission rate above ${mapShareToSpokenPercent(rate)} (${query.anchor.name}'s own admission rate)")
      }
      add("the anchor itself is never listed among its own peers")
    }

  /**
   * The corpus the peers were drawn from, in the universe's own published
   * words ([DEFAULT_UNIVERSE_SENTENCE]) —
   * never a hand-written copy of what its predicate means, which said two of
   * its three axes.
   *
   * The universe is a default, not a wall (RFC 150 D56): a caller that
   * overrode `is_active` or `is_four_year` is told what it actually got.
   */
  private fun universeSentences(filters: CollegeQuery): List<String> =
    buildList {
      if (filters.isActive == true && filters.isFourYear == null) {
        add("$DEFAULT_UNIVERSE_SENTENCE only")
      } else {
        add(
          when (filters.isActive) {
            true -> "active institutions only"
            false -> "closed institutions only"
            null -> "institutions of any operating status"
          },
        )
        add(
          when (filters.isFourYear) {
            true -> "four-year institutions only"
            false -> "two-year institutions only"
            null -> "four-year institutions, and institutions whose level is unreported"
          },
        )
        add("system central offices excluded")
      }
    }

  /**
   * The same-control constraint (D65), said as the constraint it actually is.
   *
   * The sentence used to claim "run the same way as the anchor" for whatever
   * `control` the query carried — including a control the CALLER wrote against
   * an anchor of a different one, which is exactly backwards — and said nothing
   * at all when the anchor's own control could not be resolved, leaving the
   * response claiming a constraint the SQL never applied.
   *
   * `internal` for its test: `college_search_index.control` carries a CHECK
   * over this vocabulary's own labels, so the unresolvable-label arm cannot be
   * reached through a seeded database at all — and an arm no test can reach
   * through the front door is asserted at this one.
   */
  internal fun controlSentence(query: SimilarityQuery): String {
    val control = query.filters.control
    val anchorControl = query.anchor.control
    return when {
      control == null -> {
        // The offending LABEL is what an operator needs to fix the CHECK or the
        // enum, and it is the one value this arm used to withhold.
        "no same-way-of-running constraint: ${query.anchor.name}'s control [${query.anchor.controlLabel}] is " +
          "not one of the categories this vocabulary defines (" +
          InstitutionControl.entries.joinToString(", ") { it.label } +
          "), so peers of every control are considered"
      }

      // Matched on the RESOLVED control, never on two nulls: a caller's
      // multi-control filter against an unresolvable anchor label made both
      // sides null, so the arm fired and the sentence rendered the literal
      // "null" in place of the controls the SQL actually applied.
      anchorControl != null && control.singleOrNull() == anchorControl -> {
        "run the same way as the anchor: ${anchorControl.label}"
      }

      else -> {
        "run as: ${control.joinToString(", ") { it.label }} -- what you asked for, not the anchor's own " +
          query.anchor.controlLabel
      }
    }
  }

  /**
   * One ranked peer: the SHARED result row every college tool returns, plus the
   * two facts that make this tool's answer readable — how far it sits from the
   * anchor, and which axes that number was computed over.
   */
  private fun similarObject(match: SimilarityMatch): JsonObject =
    buildJsonObject {
      matchObject(match.match, vocabulary).forEach { (key, value) -> put(key, value) }
      put("distance", (match.distance * DISTANCE_SCALE).roundToInt() / DISTANCE_SCALE)
      putJsonArray(AXES_SCORED_KEY) {
        match.axesScored.forEach { add(it.word) }
      }
    }

  // ---------------------------------------------------------------------------
  // Refusals
  // ---------------------------------------------------------------------------

  /**
   * The sentence a shared reader's failure carries. A message-less throwable
   * falls back to its own CLASS, as `searchFailureObject` already does: a
   * `NullPointerException` and a genuine validation miss used to reach the
   * model as the same two words, "invalid input".
   */
  private fun mapErrorReason(error: Throwable): String = error.message ?: error::class.simpleName ?: "invalid input"

  /**
   * D63's ambiguity refusal: the candidates, so the coach can ask WHICH school
   * the student meant rather than picking one and being fluently wrong.
   */
  private fun anchorAmbiguousObject(
    name: String,
    candidates: List<CollegeSummary>,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("error") {
        put("kind", ANCHOR_AMBIGUOUS)
        put(
          "detail",
          "[$name] matches more than one college; ask the student which one they mean and call again " +
            "with its college_id",
        )
        putJsonArray("candidates") {
          candidates.forEach { candidate ->
            add(
              buildJsonObject {
                put("college_id", candidate.id.value.toString())
                put("name", candidate.name)
                put("city", candidate.city)
                put("state", candidate.state)
              },
            )
          }
        }
      }
    }

  /** D64's refusal: named, with the reason in words, never an empty page. */
  private fun anchorNotRankableObject(detail: String): JsonObject =
    buildJsonObject {
      putJsonObject("error") {
        put("kind", ANCHOR_NOT_RANKABLE)
        put("detail", detail)
      }
    }

  /**
   * D64's sibling refusal: the anchor CAN be ranked, but every axis this call
   * asked for was dropped, so there is nothing left to rank by. The reasons are
   * the drop reasons, in words — and, beside them, the structured
   * `axes_dropped` array the success path emits.
   */
  private fun noRankableAxisObject(
    dropped: List<DroppedAxis>,
    anchor: SimilarityAnchor,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("error") {
        put("kind", NO_RANKABLE_AXIS)
        put("detail", "no axis is left to rank on: " + dropped.joinToString("; ") { mapDropToSentence(it, anchor) })
        // The drops ARE the answer here, so they travel as the SAME
        // `{axis, reason}` rows a successful response reports rather than as a
        // semicolon blob whose axis words a reader would have to re-parse.
        putAxesDropped(dropped, anchor)
      }
    }

  /** The `weights` schema: an object keyed by the axis words, values in the clamp range. */
  private fun weightsProperty(): JsonObject =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        AXIS_WORDS.keys.forEach { word ->
          put(
            word,
            numberProperty(
              "How much the \"$word\" axis counts, relative to the others. Clamped to " +
                "${SimilarityAxis.MIN_WEIGHT}..${SimilarityAxis.MAX_WEIGHT}; every axis defaults to " +
                "${SimilarityAxis.DEFAULT_WEIGHT}.",
            ),
          )
        }
      }
      put(
        "description",
        "Per-axis weights, as RATIOS: only the proportions matter, so scaling all of them changes " +
          "nothing -- as long as every weight stays within " +
          "${SimilarityAxis.MIN_WEIGHT}..${SimilarityAxis.MAX_WEIGHT}. A weight outside that range is " +
          "clamped into it, which does change the proportions you asked for. Weight only the axes " +
          "this call ranks on.",
      )
    }

  companion object {
    const val TOOL_NAME = "similar_colleges"

    /**
     * The payload key carrying the axes a college was actually judged on, named
     * ONCE here so the seeded coach prompt that tells the coach to read it
     * aloud can be pinned to it rather than to a literal of its own.
     */
    const val AXES_SCORED_KEY = "axes_scored"

    /** The refusal kind D63 returns when a name matches more than one college. */
    const val ANCHOR_AMBIGUOUS = "anchor_ambiguous"

    /** The refusal kind D64 returns when the anchor has no position to measure from. */
    const val ANCHOR_NOT_RANKABLE = "anchor_not_rankable"

    /**
     * The refusal kind D64 returns when the anchor is rankable but every axis
     * the call asked for was dropped — a different fact from [ANCHOR_NOT_RANKABLE],
     * so a different kind.
     */
    const val NO_RANKABLE_AXIS = "no_rankable_axis"

    /**
     * Three decimals, a rank aid and nothing else: this design cannot honestly
     * support "87% similar", so the description forbids rendering it that way.
     * The DECISION is named here, and the scale is derived from it, rather than
     * living as a multiplier and a divisor that can drift apart.
     */
    const val DISTANCE_DECIMALS = 3

    private val DISTANCE_SCALE: Double = 10.0.pow(DISTANCE_DECIMALS)

    /** A peer list is read, not scrolled (D70). */
    const val DEFAULT_LIMIT = 5
    const val MIN_LIMIT = CollegeSearchService.MIN_LIMIT
    const val MAX_LIMIT = CollegeSearchService.MAX_SIMILAR_LIMIT

    /**
     * How many candidates the ambiguity refusal may list — `searchByName`'s page
     * size. It is NOT the refusal threshold: [getCandidate] refuses at two
     * candidates. It is the cap on the names a coach is handed to ask the
     * student about, because a longer list is a longer question, not a better
     * one.
     */
    const val ANCHOR_CANDIDATES = 5

    /**
     * The axis word enum, built by an exhaustive pass over [SimilarityAxis], so
     * an axis added to the enum is offerable to the model without a second list
     * here to forget.
     */
    private val AXIS_WORDS: Map<String, SimilarityAxis> = SimilarityAxis.entries.associateBy { it.word }

    /**
     * The shared filter vocabulary plus this tool's own fields. Unknown-key
     * refusal is the TOOL's job, not the vocabulary's: two tools offer
     * different field sets around the same filters.
     */
    private val KNOWN_FIELDS: Set<String> =
      CollegeQueryVocabulary.FIELD_NAMES +
        setOf(
          "college_id",
          "name",
          "axes",
          "weights",
          "cheaper_than_anchor",
          "easier_to_admit_than_anchor",
          "limit",
        )

    private val DESCRIPTION =
      "Find real US colleges SIMILAR to one the student already named -- \"schools like Bowdoin\". " +
        "Name the anchor with `college_id` (copied verbatim from another tool's result) or with " +
        "`name`; if the name matches more than one college the tool refuses and lists them, so ask " +
        "which one the student meant rather than guessing. What \"similar\" means is decided PER CALL, " +
        "not stored: `axes` chooses among " + SimilarityAxis.entries.joinToString(", ") { it.word } +
        ", defaulting to [" + SimilarityAxis.DEFAULTS.joinToString(", ") { it.word } + "], and " +
        "`weights` sets their proportions. Results are ranked by `distance`, the average gap across " +
        "those axes: 0 means the two schools sit in the same place on every axis measured. It is a " +
        "RANK AID, not a percentage and not a quality score -- never say it to a student as " +
        "\"87% similar\" or read it as how good a school is. Each result also carries `axes_scored`, " +
        "the axes that college was actually judged on: a school matched on one axis out of three is " +
        "not making the same claim as one matched on three, so say which. The response names the " +
        "`axes_used`, every axis in `axes_dropped` with the reason it could not be used, and every " +
        "constraint in `constraints_used` -- state them; an unreported figure is never scored as a " +
        "zero and never guessed at. `cheaper_than_anchor` and `easier_to_admit_than_anchor` are " +
        "expanded against the anchor's own figures and said back to you in words, so you can ask " +
        "for \"like Bowdoin but cheaper\" without knowing Bowdoin's net price. Every other filter is " +
        "the same vocabulary `search_colleges` takes, in the same words. By default the peers are " +
        "run the same way as the anchor (public, private nonprofit or private for-profit) and are " +
        "active four-year institutions; the anchor never appears among its own peers. Cite " +
        "`total_candidates` when saying how many schools were considered, and say " +
        "`excluded_unknown` in plain words -- those schools could not be judged on that axis, which " +
        "is not the same as failing it."
  }
}
