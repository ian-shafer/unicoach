package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.costs.canonical.FigureGapOwner
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.costs.canonical.figureGroup
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.ProgramMoneyMeasure
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putIncomeBand
import ed.unicoach.db.models.putLivingPlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import ed.unicoach.db.models.putResidencyTiers as putResidencyTierPair

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
      putJsonObject("input_schema") { putCollegeIdsSchema() }
    }

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val collegeIds =
      when (val parsed = readCollegeIds(input)) {
        is CollegeIdsInput.Ok -> parsed.collegeIds
        is CollegeIdsInput.Invalid -> return errorObject(parsed.reason)
      }

    val profile =
      service
        .getForStudent(studentId, collegeIds)
        .getOrElse { e ->
          logger.warn("tool [{}] cost read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("college cost read failed")
        }

    return payloadOf(profile)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostChatTool::class.java)

    /**
     * The full structured result: one cost object per college, the money-profile
     * echo, and the attribution.
     *
     * THE payload builder for a cost picture (RFC 258): `college_cost_profile`
     * renders the student's own picture through it, and the supporter's
     * `family_cost_picture` renders the linked student's shared picture through
     * it, so both coaches read the same keys and the same words. It reads no
     * instance state, which is why it lives here and not on the tool.
     */
    fun payloadOf(profile: CollegeCostProfile): JsonObject =
      buildJsonObject {
        putJsonArray("colleges") { profile.colleges.forEach { add(collegeObject(profile, it)) } }
        // The major answer (RFC 231): absent, never empty, when the intended
        // subjects are unanswered, declined or undecided. Present it is the
        // colleges re-keyed by subject and then by matched program --
        // subject_slug -> colleges -> programs -> figures.
        profile.programReturn?.let { put(PROGRAM_RETURN_KEY, programReturnArray(it)) }
        put("count", profile.colleges.size)
        if (profile.unknownCollegeIds.isNotEmpty()) {
          putJsonArray("unknown_college_ids") {
            profile.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
          }
        }
        put("money_profile", moneyProfileObject(profile.moneyProfile))
        // The comparison contract (RFC 151), present only when the answer carries
        // two or more colleges -- an absent key, never an empty object, so its
        // mere presence tells the model a side-by-side is being made.
        profile.comparisonBasis?.let { put(COMPARISON_BASIS_KEY, comparisonBasisObject(it)) }
        // ABSENT, never empty, exactly like every other key here: a payload with
        // no money figure on it has no publisher to name, and the constant this
        // replaced named one anyway (RFC 177).
        MoneySourceCopy.spokenListOf(profile.moneySources)?.let { put(SOURCE_KEY, it) }
      }

    /**
     * The `program_return` array (RFC 231), in the stored answer's own order: one
     * entry per subject, then one entry per selected college, then one entry per
     * matched program in CIP order, then the figures in [ProgramMoneyMeasure]
     * declaration order.
     */
    private fun programReturnArray(section: ProgramReturn): JsonArray = JsonArray(section.subjects.map(::programReturnSubjectObject))

    private fun programReturnSubjectObject(subject: ProgramReturnSubject): JsonObject =
      buildJsonObject {
        put(PROGRAM_SUBJECT_SLUG_KEY, subject.subjectSlug)
        put(PROGRAM_SUBJECT_NAME_KEY, subject.subjectName)
        putJsonArray(PROGRAM_COLLEGES_KEY) { subject.colleges.forEach { add(programReturnCollegeObject(it)) } }
      }

    /**
     * One college's slice: its matched programs, or the ONE sentence that says no
     * program matched -- never an empty `programs` array.
     */
    private fun programReturnCollegeObject(college: ProgramReturnCollege): JsonObject =
      buildJsonObject {
        put(COLLEGE_ID_KEY, college.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, college.name)
        when (college) {
          is ProgramReturnCollege.WithPrograms -> {
            putJsonArray(PROGRAM_PROGRAMS_KEY) { college.programs.forEach { add(programObject(it)) } }
          }

          // No matched bachelor's program: the name and the sentence, never an
          // empty programs array.
          is ProgramReturnCollege.NoProgram -> {
            putJsonObject(PROGRAM_AVAILABILITY_KEY) {
              put(BASIS_KEY, PROGRAM_NO_PROGRAM_BASIS)
              put(STATEMENT_KEY, ProgramReturnCopy.NO_PROGRAM_STATEMENT)
            }
          }
        }
      }

    /**
     * One matched program, keyed by the CIP code the publisher publishes at: its
     * figures, or the sentence that says we hold none of them.
     *
     * The code rides on EVERY entry, including the no-figures one, because it is
     * the program's identity: a college running two programs one subject reaches
     * carries two entries, and an entry with no code would leave a family unable
     * to tell which major it is about (RFC 231).
     */
    private fun programObject(program: ProgramReturnProgram): JsonObject =
      buildJsonObject {
        put(PROGRAM_CIP_CODE_KEY, program.cipCode.value)
        put(PROGRAM_TITLE_KEY, program.title)
        when (program) {
          is ProgramReturnProgram.WithFigures -> {
            putJsonArray(PROGRAM_FIGURES_KEY) { program.figures.forEach { add(programReturnFigureObject(it)) } }
          }

          // The school RUNS the program and the publisher gave us nothing for it
          // -- its own basis, so it can never be read as the school not running
          // it and never as a publisher's suppression.
          is ProgramReturnProgram.NoPublishedFigures -> {
            putJsonObject(PROGRAM_FIGURES_AVAILABILITY_KEY) {
              put(BASIS_KEY, PROGRAM_NO_PUBLISHED_FIGURES_BASIS)
              put(STATEMENT_KEY, ProgramReturnCopy.NO_PUBLISHED_FIGURES_STATEMENT)
            }
          }
        }
      }

    /**
     * One measure's entry. EVERY measure gets one, so a measure we hold no row for
     * says so in its own words rather than leaving a short array a reader has to
     * diff against the measure list to notice.
     */
    private fun programReturnFigureObject(figure: ProgramReturnFigure): JsonObject =
      buildJsonObject {
        put(PROGRAM_MEASURE_KEY, figure.measure.value)
        when (figure) {
          is ProgramReturnFigure.Held -> {
            putHeldProgramFigure(figure)
          }

          is ProgramReturnFigure.NoFigure -> {
            putJsonObject(PROGRAM_MEASURE_AVAILABILITY_KEY) {
              put(BASIS_KEY, PROGRAM_NO_FIGURE_BASIS)
              put(STATEMENT_KEY, ProgramReturnCopy.NO_FIGURE_STATEMENT)
            }
          }
        }
      }

    /**
     * A figure we hold a row for: its status, its number where the status bears
     * one, and its provenance.
     *
     * The status is the ARM's own ([ProgramReturnFigure.Held.status]), so no
     * silence can be stamped with another's code. The `when` is exhaustive with no
     * `else` over the whole `figure_statuses` vocabulary, because the amount rides
     * on exactly the two value-bearing statuses and a seventh status must be
     * decided here before it compiles -- the seam that once threw for three of the
     * six legal values and took the whole cost answer down with it.
     */
    private fun JsonObjectBuilder.putHeldProgramFigure(figure: ProgramReturnFigure.Held) {
      when (figure) {
        is ProgramReturnFigure.Valued -> {
          put(PROGRAM_AMOUNT_USD_KEY, figure.amountUsd)
        }

        is ProgramReturnFigure.Suppressed,
        is ProgramReturnFigure.NotReported,
        is ProgramReturnFigure.NotApplicable,
        is ProgramReturnFigure.NotCollectedByUs,
        -> {
          Unit
        }
      }
      put(PROGRAM_STATUS_KEY, figure.status.value)
      put(PROGRAM_PUBLISHER_KEY, figure.provenance.publisher)
      put(PROGRAM_COHORT_FIRST_YEAR_KEY, figure.provenance.cohortFirstYear.firstCalendarYear)
      put(PROGRAM_COHORT_LAST_YEAR_KEY, figure.provenance.cohortLastYear.firstCalendarYear)
      put(PROGRAM_COHORT_SENTENCE_KEY, figure.provenance.cohortSentence)
      figure.provenance.inflationBaseYear?.let { put(PROGRAM_INFLATION_BASE_YEAR_KEY, it.year) }
    }

    /**
     * The five facts a side-by-side holds constant (RFC 151), each rendered as a
     * stable code AND the sentence the coach may say for it.
     *
     * A renderer and nothing else: every code and every statement is read off the
     * [ComparisonBasis] the service assembled, so this file cannot state a basis
     * the domain did not decide.
     */
    private fun comparisonBasisObject(basis: ComparisonBasis): JsonObject =
      buildJsonObject {
        putJsonObject("population") {
          put(BASIS_KEY, basis.population.code)
          put(STATEMENT_KEY, basis.population.statement)
        }
        put("residency", residencyBasisObject(basis.residency))
        // The SIXTH fact (RFC 157 D-C), emitted directly after the residency fact
        // it completes: the residency line is about the tuition and fees column,
        // and this one says which residency the two blended figures are on and
        // which schools here they therefore do not describe.
        put(BLENDED_FIGURE_BASIS_KEY, blendedFigureBasisObject(basis.blendedFigures))
        put("living_arrangement", arrangementBasisObject(basis.livingArrangement))
        // Absent, never empty -- the same convention `comparable` and
        // `incomplete_by_college` follow below, and the one D-B gates the whole
        // object on. A call whose schools report only undated figures (median
        // debt, median earnings) dates nothing, and an empty array would read as
        // a year list nobody could name.
        if (basis.academicYears.isNotEmpty()) {
          putJsonArray("academic_years") { basis.academicYears.forEach { add(datedFiguresObject(it)) } }
        }
        putJsonObject("aid") {
          put(BASIS_KEY, basis.aid.code)
          put(STATEMENT_KEY, basis.aid.statement)
        }
      }

    /**
     * The residency held constant, and the one per-school element inside the
     * per-call object (RFC 151 D-A): the state rides only when it is answered,
     * exactly as the money-profile echo renders it.
     */
    private fun residencyBasisObject(residency: ResidencyBasis): JsonObject =
      // WHICH schools here charge by residency: the decision the statement speaks,
      // shipped as a code beside it (D-D), so no reader parses the sentence to
      // learn whether this table is all-public, mixed, or none.
      residencyAnsweredBasisObject(
        scope = residency.scope.value,
        answer = residency.answer,
        statement = residency.statement,
        namedPerCollege = residency.namedPerCollege,
        objectOfEntry = ::collegeResidencyObject,
      )

    /**
     * The shape EVERY residency-answered per-call fact rides in (RFC 151 D-D):
     * the scope code, the family's own answer, the sentence the coach may say, and
     * -- only where a school is not fully described by that sentence (RFC 189 D2)
     * -- one entry per such school.
     *
     * Written ONCE, so a key added here reaches the residency fact and the
     * blended-figure fact together rather than being remembered for one of them.
     */
    private fun <T> residencyAnsweredBasisObject(
      scope: String,
      answer: ComparedResidency,
      statement: String,
      namedPerCollege: List<T>,
      objectOfEntry: (T) -> JsonObject,
    ): JsonObject =
      buildJsonObject {
        put(BASIS_KEY, scope)
        put(STATUS_KEY, answer.status.value)
        // The state rides on the answered case and nowhere else, and EXHAUSTIVELY
        // so: a case added to [ComparedResidency] must fail to compile here rather
        // than silently ship without the state it carries.
        when (answer) {
          is ComparedResidency.Answered -> put("residency_state", answer.state)
          ComparedResidency.Unanswered, ComparedResidency.Declined -> Unit
        }
        put(STATEMENT_KEY, statement)
        // CONDITIONAL, the same rule [CHOSEN_BY_COLLEGE_KEY] has carried for the
        // same shape (RFC 189 D2): the array is written only where a school is not
        // fully described by the sentence above it. With every school on one
        // basis, an unconditional array stated the one fact 1 + N times and
        // invited the coach to narrate a difference there is none of. The DOMAIN
        // decides which schools those are ([ResidencyBasis.namedPerCollege],
        // [BlendedFigureBasis.namedPerCollege]), so the renderer never re-derives
        // the condition -- absent, never empty, like every other list here.
        if (namedPerCollege.isNotEmpty()) {
          putJsonArray(BY_COLLEGE_KEY) { namedPerCollege.forEach { add(objectOfEntry(it)) } }
        }
      }

    /**
     * One school's residency line inside the per-call object: WHICH published
     * tuition figure this comparison holds constant there.
     *
     * [TUITION_BASIS_KEY] is its own key with its own five-code vocabulary, not
     * the per-college `tuition_applicable`: that one is the public-only
     * in-state/out-of-state/unknown fact, while this one also answers what kind of
     * school it is.
     */
    private fun collegeResidencyObject(college: CollegeResidencyBasis): JsonObject =
      buildJsonObject {
        put(COLLEGE_ID_KEY, college.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, college.name)
        put(TUITION_BASIS_KEY, college.tuition.code)
        // The raw control code that defeated the residency line, beside the
        // failure it caused: an operator reading this entry never has to open the
        // row to learn WHICH value we could not place.
        when (val tuition = college.tuition) {
          is ComparedTuition.PublishedPriceUnknown -> put(SOURCE_CONTROL_KEY, tuition.sourceControl.label)
          is ComparedTuition.Public, ComparedTuition.SinglePublishedPrice -> Unit
        }
        put(STATEMENT_KEY, college.statement)
      }

    /**
     * Which residency the published price and the price after a financial aid
     * offer are on, and which schools in this call they do not describe (RFC 157
     * D-C).
     *
     * The same shape as every other basis fact: a [BASIS_KEY] code, the family's
     * own answer echoed, the [STATEMENT_KEY] the coach may say, and -- where the
     * sentence is not the whole truth about a school -- that school's own entry,
     * so a reader never has to work out from an absent figure that a figure was
     * withheld.
     */
    private fun blendedFigureBasisObject(basis: BlendedFigureBasis): JsonObject =
      residencyAnsweredBasisObject(
        scope = basis.scope.value,
        answer = basis.answer,
        statement = basis.statement,
        namedPerCollege = basis.namedPerCollege,
        objectOfEntry = ::collegeBlendedFigureObject,
      )

    /**
     * One school's blended-figure line: the code, whether the two figures are this
     * family's, and the sentence. [putBlendedFiguresApply] owns the known-only
     * rule for [APPLIES_KEY] and says why.
     */
    private fun collegeBlendedFigureObject(college: CollegeBlendedFigureBasis): JsonObject =
      buildJsonObject {
        put(COLLEGE_ID_KEY, college.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, college.name)
        put(BASIS_KEY, college.code.value)
        putBlendedFiguresApply(college.applies)
        put(STATEMENT_KEY, college.statement)
      }

    /**
     * Whether the two blended figures are this family's at one school, on the wire
     * in ONE form wherever it is said (RFC 157 D-B).
     *
     * TWO keys, and both are load-bearing. [APPLIES_BASIS_KEY] carries the state
     * itself and is always written, so the payload is self-describing and no
     * reader infers an outcome from a key that is not there. [APPLIES_KEY] stays
     * the known-only boolean -- the `offers_on_campus_housing` convention (RFC 149
     * D-B) -- because an open residency question is not a `false`, and writing one
     * would tell the coach these figures are somebody else's when all we know is
     * that we have not asked.
     *
     * Exhaustive, so a fourth outcome must say what boolean it ships rather than
     * falling through to silence.
     */
    private fun JsonObjectBuilder.putBlendedFiguresApply(applies: BlendedFigureApplicability) {
      // The state ITSELF, always present: three outcomes cannot ride on a boolean
      // plus an absence, and an absent key has two causes (an open residency
      // question, an unrecognised control) that a reader cannot tell apart.
      put(APPLIES_BASIS_KEY, applies.value)
      when (applies) {
        BlendedFigureApplicability.APPLIES -> put(APPLIES_KEY, true)
        BlendedFigureApplicability.WITHHELD -> put(APPLIES_KEY, false)
        BlendedFigureApplicability.BASIS_STATED -> Unit
      }
    }

    /**
     * The living arrangement held constant (RFC 151 D-E, RFC 152 D5): what the
     * family answered, what each school resolved to, the arrangements every
     * school in the call is priced for, and the schools that lack one another
     * school has.
     *
     * The lists are emitted only when they carry something -- the payload's
     * absent-never-empty convention -- and the statement says which case it is in
     * words, beside the [BASIS_KEY] code that says it as a fact, so an absent
     * list is never read as a fact nobody stated.
     *
     * [CHOSEN_BY_COLLEGE_KEY] is emitted only when the schools are NOT all on one
     * plan or one of them is not priced for it: with one plan everywhere, the
     * statement and the per-college keys already say it, and a second per-school
     * list would invite the coach to narrate a difference there is none of.
     */
    private fun arrangementBasisObject(arrangement: ArrangementBasis): JsonObject =
      buildJsonObject {
        put(BASIS_KEY, arrangement.scope.value)
        putComparedLivingPlan(arrangement.answer)
        if (arrangement.planNamedPerCollege.isNotEmpty()) {
          putJsonArray(CHOSEN_BY_COLLEGE_KEY) {
            arrangement.planNamedPerCollege.forEach { add(collegeLivingPlanObject(it)) }
          }
        }
        if (arrangement.comparable.isNotEmpty()) {
          putJsonArray("comparable") { arrangement.comparable.forEach { add(JsonPrimitive(it.value)) } }
        }
        if (arrangement.incompleteByCollege.isNotEmpty()) {
          putJsonArray("incomplete_by_college") {
            arrangement.incompleteByCollege.forEach { add(incompleteArrangementObject(it)) }
          }
        }
        put(STATEMENT_KEY, arrangement.statement)
      }

    /**
     * One school's resolved plan inside the comparison: which way of living it is
     * quoted at, where that plan came from, and -- when the school is not priced
     * for it -- the reason, never a substitute.
     *
     * A school with no plan resolved carries its identity and the
     * [LivingPlanPricing.NOT_CHOSEN] code, so "nothing is on file for it" is
     * stated rather than left to be noticed in an absent key.
     */
    private fun collegeLivingPlanObject(row: CollegeLivingPlan): JsonObject =
      buildJsonObject {
        put(COLLEGE_ID_KEY, row.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, row.name)
        putChosenLivingPlan(row.chosen)
      }

    /**
     * The resolved plan itself: which way of living, in the student's words,
     * where the plan came from -- and the reason when this school is not priced
     * for it, never a substitute.
     *
     * ONE emitter for the two places the sub-object appears (the per-college
     * answer and the comparison's [CHOSEN_BY_COLLEGE_KEY]), so the coach can never
     * meet two spellings of one shape.
     *
     * Every case writes its own [PRICING_KEY] code (RFC 152): the three shapes
     * used to be told apart only by which sibling keys were absent, which made the
     * coach infer a fact from a silence -- the very thing a code exists to
     * prevent. A school with no plan resolved now says so as a code too, instead
     * of being an identity-only row.
     */
    private fun JsonObjectBuilder.putChosenLivingPlan(chosen: ChosenLivingPlan) {
      when (chosen) {
        is ChosenLivingPlan.Priced -> {
          put(ARRANGEMENT_KEY, chosen.plan.value)
          put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
          put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
          put(PRICING_KEY, chosen.pricing.value)
        }

        is ChosenLivingPlan.NoTotalHere -> {
          put(ARRANGEMENT_KEY, chosen.plan.value)
          put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
          put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
          put(PRICING_KEY, chosen.pricing.value)
          // OUR vocabulary, never the school's: two of this reason's three causes
          // are gaps of ours, so it rides under its own key and never under the
          // one that says what the school published.
          put(NO_TOTAL_REASON_KEY, chosen.reason.value)
        }

        is ChosenLivingPlan.NotPricedHere -> {
          put(ARRANGEMENT_KEY, chosen.plan.value)
          put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
          put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
          put(PRICING_KEY, chosen.pricing.value)
          // A reason code, never a substituted arrangement and never a
          // neighbour's figure. No total, because there is none to give.
          put(ARRANGEMENT_REASON_KEY, chosen.reason.value)
        }

        // No plan, so no arrangement to name -- but the code is written, so a row
        // with nothing on file states that rather than leaving it to be noticed.
        ChosenLivingPlan.NotChosen -> {
          put(PRICING_KEY, chosen.pricing.value)
        }
      }
    }

    /**
     * One school's gap in the arrangements the other schools are priced for: the
     * arrangements it lacks, and the one reason they are missing for (RFC 151
     * D-E). A school with two kinds of gap is two entries, so one entry is always
     * "these arrangements, for this reason".
     */
    private fun incompleteArrangementObject(entry: IncompleteArrangement): JsonObject =
      buildJsonObject {
        put(COLLEGE_ID_KEY, entry.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, entry.name)
        putJsonArray("missing") { entry.missing.forEach { add(JsonPrimitive(it.value)) } }
        put("reason", entry.reason.value)
      }

    /**
     * One academic year in the call, the schools it is true of, and the figures it
     * dates -- the same `{academic_year, figures}` shape the per-college vintage
     * labels carry, so the model reads one convention rather than two.
     *
     * [DATED_COLLEGES_KEY] is the subject, and it is not decoration: since RFC 166
     * the year is a fact about ONE SCHOOL, so a comparison of a Scorecard-only
     * school and an IC_AY school carries TWO of these objects. Without the schools
     * named, the model is handed the same figure names under two different years
     * and no way to tell which is which -- while this tool's own description tells
     * it to quote a number with the year of the key that lists it.
     *
     * Each subject is emitted as `{college_id, name}`, the shape every other
     * school reference in this payload uses, rather than a bare name: names
     * collide in the corpus, and the model is asked here to MATCH the subject
     * against the colleges it was given. Matching on display copy is how the
     * wrong year gets attached to a column of dollars.
     */
    private fun datedFiguresObject(dated: DatedFigures): JsonObject =
      buildJsonObject {
        put(BASIS_KEY, dated.basis)
        put(ACADEMIC_YEAR_KEY, dated.academicYear)
        putJsonArray(DATED_COLLEGES_KEY) {
          dated.colleges.forEach {
            add(
              buildJsonObject {
                put(COLLEGE_ID_KEY, it.collegeId.value.toString())
                put(COLLEGE_NAME_KEY, it.name)
              },
            )
          }
        }
        putJsonArray(DATED_FIGURES_KEY) { dated.figures.forEach { add(JsonPrimitive(it.wireName)) } }
        put(STATEMENT_KEY, dated.statement)
      }

    private fun collegeObject(
      profile: CollegeCostProfile,
      cost: CollegeCost,
    ): JsonObject {
      // The vintage labels are derived from what this object ACTUALLY carries.
      // Every step below that emits a money figure RETURNS the fields it emitted
      // and the record is built HERE from those results, so the emit and the
      // record are one expression: a second list restating the same emit
      // conditions is how a figure comes to be labelled with a year no key beside
      // it describes, or to lose its label entirely -- and nothing would fail for
      // it.
      val emitted = mutableSetOf<CostField>()

      return buildJsonObject {
        put(COLLEGE_ID_KEY, cost.collegeId.value.toString())
        put(COLLEGE_NAME_KEY, cost.name)
        put("city", cost.city)
        put("state", cost.state)
        put("control", cost.control.label)
        put("list_status", cost.listStatus.value)
        emitted += putFigure(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, cost.stickerCostOfAttendancePerYearUsd)
        emitted += putTuitionTiers(cost)
        emitted += putFeesOnly(cost)
        putResidencyTiers(cost)
        // Present only on the public case; the model makes the distinction
        // uncarryable by a private college, so it cannot be misread onto one.
        (cost.control as? CollegeControl.Public)?.let { put("tuition_applicable", it.tuitionApplicable.value) }
        emitted += putNetPrice(cost.netPrice)
        putPrecisionOffers(profile, cost)
        emitted += putFigure(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, cost.medianDebtAtCompletionUsd)
        emitted += putFigure(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD, cost.medianEarnings10yAfterEntryUsd)
        // The published price split by living arrangement (RFC 149). Absent, not
        // empty, when this school reports no component at all. Its lines are
        // recorded here, at the one place they are rendered.
        cost.breakdown?.let { emitted += putBreakdown(it) }
        // AFTER the breakdown and BEFORE putVintageLabels, and that ordering is
        // load-bearing (RFC 152). It re-keys a figure the breakdown already
        // emitted -- the resolved arrangement's total -- so it must sit above the
        // vintage labels for the rule "the keys after it emit no CostField" to
        // stay true; and it sits below the breakdown because it names one of the
        // arrangements that object rendered, so a reader meets the three before
        // being told which one to lead with.
        putChosenLivingArrangement(cost.chosen)
        putOffersOnCampusHousing(cost)
        // LAST of the figure-bearing keys, and it must stay there: it reads the
        // [emitted] set the steps above returned into, so a figure emitted BELOW
        // this line would ride with no academic year beside it and nothing -- no
        // type, no test -- would fail for it. The keys after it emit no CostField,
        // which is the only reason they may sit where they do.
        putVintageLabels(cost, emitted)
        // Purely additive (RFC 148 D7): present only when the school reports it,
        // and carrying its OWN citation, because merit aid is not a Scorecard
        // fact and must never fold into the payload's Scorecard `source` string.
        cost.meritAid?.let { put(MeritAidWire.KEY, MeritAidWire.objectOf(it)) }
        // The RFC 170 aid-policy section, additive in exactly the same way, and
        // with the coverage-honest line when the corpus carries no filing for
        // this school: silence about a school's aid policy is a fact about OUR
        // corpus, and leaving it unsaid invites the model to fill it.
        putAidPolicy(cost)
        putBorrowing(cost)
        putFieldsWithNoAmount(cost)
        putWithheldFigures(cost)
        putFigureStatuses(cost)
        // The blended-figure basis for THIS school, stated per college as well as
        // in COMPARISON_BASIS_KEY: that fact needs two colleges, and a one-college
        // result is exactly where the basis would otherwise go unsaid (RFC 157
        // D-B).
        putBlendedFiguresApply(cost.blendedFiguresApply)
      }
    }

    /**
     * One scalar money figure, RETURNING the field it emitted so the caller's
     * record of what this payload carries IS this function's result rather than a
     * side-effect on a set it was handed. An absent amount writes no key and
     * returns nothing: a key with no number is what `data_availability` and
     * `figure_statuses` are for.
     */
    private fun JsonObjectBuilder.putFigure(
      field: CostField,
      amountUsd: Int?,
    ): Set<CostField> {
      if (amountUsd == null) return emptySet()
      put(field.wireName, amountUsd)
      return setOf(field)
    }

    /**
     * The tuition tiers, in declaration order, from the ONE derivation of which of
     * them this school publishes ([CollegeCost.publishedTuitionTiers], RFC 166 §4)
     * -- including the third tier, which no consumer surface showed before this
     * RFC. Iterated rather than hand-listed so the payload cannot disagree with
     * `residency_tiers` beside it, and so a fourth tier is emitted without a
     * second list saying it should be. A school with no value-bearing figure at a
     * tier emits no key for it at all, which is how the in-district tier stays
     * silent at the ~2,300 institutions whose publisher never separated one.
     */
    private fun JsonObjectBuilder.putTuitionTiers(cost: CollegeCost): Set<CostField> =
      cost.publishedTuitionTiers.flatMapTo(mutableSetOf()) { putFigure(it, cost.publishedAmountOf(it)) }

    /**
     * The fees split (RFC 166 §5): the fees PART of the combined tuition figure,
     * never a cost beside it. It is deliberately not a component of any
     * arrangement -- adding it to one would count fees twice inside every total --
     * and the description says so in words.
     *
     * ITERATED over [CostField.FEES_ONLY_FIELDS], for the reason the tuition tiers above are
     * iterated: three hand-written call sites are three places a fourth fees tier
     * would not reach.
     */
    private fun JsonObjectBuilder.putFeesOnly(cost: CollegeCost): Set<CostField> =
      CostField.FEES_ONLY_FIELDS.flatMapTo(mutableSetOf()) { putFigure(it, cost.publishedAmountOf(it)) }

    /**
     * WHICH tuition tiers this school publishes, as a code and the domain's own
     * sentence ([ResidencyTierBasis], RFC 166 §4), on every college.
     *
     * Emits no [CostField]: it names the tuition keys beside it and adds no figure
     * of its own, so it takes no part in the vintage labels.
     */
    private fun JsonObjectBuilder.putResidencyTiers(cost: CollegeCost) {
      // The ONE emitter, beside the vocabulary in `:db` (RFC 169, brief 0006 D19):
      // college search reports the same fact about the same school, and a second
      // writer here would be a second way to spell one payload.
      putResidencyTierPair(cost.residencyTiers)
    }

    /**
     * The one figure that is not a bare scalar: it keys an OBJECT (the amount, its
     * basis, and the reason when we hold it back), so it cannot go through
     * [putFigure]. Its emit and its record sit in one function all the same --
     * they used to be two statements in the orchestrator, the only place the
     * record was maintained by hand.
     */
    private fun JsonObjectBuilder.putNetPrice(netPrice: NetPrice): Set<CostField> {
      put(CostField.NET_PRICE.wireName, netPriceObject(netPrice))
      return if (netPrice.amount == null) emptySet() else setOf(CostField.NET_PRICE)
    }

    /**
     * The upgrade invitations for this college: emitted only when there is
     * something to offer -- an absent key, never an empty array, so its mere
     * presence stays meaningful to the model.
     */
    private fun JsonObjectBuilder.putPrecisionOffers(
      profile: CollegeCostProfile,
      cost: CollegeCost,
    ) {
      val offers = profile.precisionOffersFor(cost)
      if (offers.isNotEmpty()) {
        putJsonArray(PRECISION_OFFER_KEY) { offers.forEach { add(precisionOfferObject(it, cost)) } }
      }
    }

    /**
     * The aid-policy section, or the one sentence saying why there is none (RFC
     * 170, D11).
     *
     * The absence line is NOT a [DATA_AVAILABILITY_KEY] entry: that list speaks
     * the [CostField] vocabulary, which means "this college does not report this
     * SCORECARD cost field", and the Common Data Set is a different publisher
     * with its own silences (the rule [CollegeCost.meritAid] already states).
     * Mixing them would misattribute whose silence it is. So the sentence keys
     * itself, and says which of the two silences this is: we hold no filing for
     * the school, rather than the school having reported nothing.
     */
    private fun JsonObjectBuilder.putAidPolicy(cost: CollegeCost) {
      val policy = cost.aidPolicy
      when {
        // Two silences, two sentences. "We hold no filing" said about a school
        // whose filing we DO hold is a false statement about our own coverage,
        // and it is the one the model would repeat to a family.
        policy == null -> put(AID_POLICY_AVAILABILITY_KEY, AID_POLICY_NO_FILING)

        !policy.hasFact -> put(AID_POLICY_AVAILABILITY_KEY, AID_POLICY_NO_FACT_IN_FILING)

        else -> put(AidPolicyWire.KEY, AidPolicyWire.objectOf(policy))
      }
    }

    /**
     * The borrowing section, or the one sentence saying why there is none (RFC
     * 175) -- [putAidPolicy]'s arms plus a fourth, because there are THREE
     * silences here and D7 turns on whose each one is: no filing, a filing with
     * no borrowing block (the school's silence), and a filing whose borrowing
     * cells we could not read (OURS). Speaking the third as the second tells a
     * family this school does not report a figure it does report.
     *
     * The four states arrive already told apart, as [BorrowingCoverage], so this
     * `when` is exhaustive over a sealed type rather than a ladder whose ARM
     * ORDER decided whose silence a filing's was. A partly unread filing is
     * [BorrowingCoverage.Reported] and says both things.
     *
     * None of the three silences is a [DATA_AVAILABILITY_KEY] entry: that list
     * speaks the [CostField] vocabulary and means "this college does not report
     * this SCORECARD cost field". The federal-only median debt figure elsewhere
     * in this result belongs to that list; these sentences are about a different
     * publisher's silence, and folding them together would misattribute it.
     */
    private fun JsonObjectBuilder.putBorrowing(cost: CollegeCost) {
      when (val borrowing = cost.borrowing) {
        BorrowingCoverage.NoFiling -> {
          putBorrowingAvailability(BORROWING_NO_FILING_BASIS, BORROWING_NO_FILING)
        }

        is BorrowingCoverage.NoBlock -> {
          // WHICH silence this filing's empty block is. A publisher that withheld
          // the cells, or a source that says the question does not apply, is not
          // the school declining to report -- and the plain sentence would say it
          // was.
          val withheld = borrowing.gapStatuses.any { FigureStatusCopy.ownerOf(it) == FigureGapOwner.PUBLISHER }
          val notApplicable = borrowing.gapStatuses.contains(FigureStatus.NOT_APPLICABLE)
          when {
            // OURS first, as [BorrowingCoverage.of] already puts OUR unread cells
            // ahead of the other silences: a filing whose cells we hold and could
            // not reconcile (D6) has ANSWERED, so every sentence below would say
            // someone else's silence about a school that reported two figures.
            borrowing.contradicted.isNotEmpty() -> {
              putBorrowingAvailability(BORROWING_NOT_RECONCILED_BY_US_BASIS, BORROWING_NOT_RECONCILED_BY_US)
            }

            withheld -> {
              putBorrowingAvailability(BORROWING_WITHHELD_BASIS, BORROWING_WITHHELD_BY_PUBLISHER)
            }

            notApplicable -> {
              putBorrowingAvailability(BORROWING_NOT_APPLICABLE_BASIS, BORROWING_NOT_APPLICABLE_IN_FILING)
            }

            else -> {
              putBorrowingAvailability(BORROWING_NO_FACT_IN_FILING_BASIS, BORROWING_NO_FACT_IN_FILING)
            }
          }
        }

        BorrowingCoverage.NotReadByUs -> {
          putBorrowingAvailability(BORROWING_NOT_READ_BY_US_BASIS, BORROWING_NOT_READ_BY_US)
        }

        // The section, WITH the value-less columns of the same filing said inside
        // it per loan type ([BorrowingWire.gapKey]). A partly readable filing is
        // a filing with figures, so it renders them and explains its own gaps.
        is BorrowingCoverage.Reported -> {
          put(BorrowingWire.KEY, BorrowingWire.objectOf(borrowing))
        }
      }
    }

    /**
     * WHY there is no borrowing section, as a CODE and the sentence to say it in
     * -- the [RESIDENCY_TIERS_KEY] convention this file already follows
     * everywhere else.
     *
     * The code is the load-bearing half: whose silence this is (D7) is the fact
     * the whole borrowing surface turns on, and emitted as a bare sentence it was
     * readable only by matching English copy.
     */
    private fun JsonObjectBuilder.putBorrowingAvailability(
      basis: String,
      statement: String,
    ) {
      putJsonObject(BORROWING_AVAILABILITY_KEY) {
        put(BASIS_KEY, basis)
        put(STATEMENT_KEY, statement)
      }
    }

    /**
     * The figures the coach has NO number for here, whichever of the two reasons
     * it is: the school did not report it, or it is not a figure for this family
     * (RFC 157 D-A).
     *
     * ONE list, because the instruction it drives is one instruction -- say so
     * plainly, never estimate -- and a field withheld from a list called
     * `data_availability` would be a silence with nothing said about it. WHICH
     * reason applies is [putWithheldFigures]' to say, with the words for it.
     *
     * The union is the DOMAIN's ([CollegeCost.fieldsWithNoAmount]), already in
     * enum declaration order: this writer spells out a set the cost answer names,
     * and never derives a third field category the page and the service cannot
     * share.
     *
     * ABSENT, never empty -- the payload's own convention, the one
     * [putWithheldFigures] follows and the one every other list here follows. An
     * empty array would say "we checked" in a shape a reader has to open to learn
     * it says nothing.
     */
    private fun JsonObjectBuilder.putFieldsWithNoAmount(cost: CollegeCost) {
      if (cost.fieldsWithNoAmount.isEmpty()) return
      putJsonArray(DATA_AVAILABILITY_KEY) { cost.fieldsWithNoAmount.forEach { add(JsonPrimitive(it.wireName)) } }
    }

    /**
     * The figures we hold and are not showing this family, each with its reason
     * (RFC 157 D-A).
     *
     * Absent, never empty. Its presence means exactly one thing: a
     * `data_availability` entry here is OURS rather than the school's silence.
     */
    private fun JsonObjectBuilder.putWithheldFigures(cost: CollegeCost) {
      if (cost.withheld.isEmpty()) return
      putJsonArray(WITHHELD_FIGURES_KEY) { cost.withheld.forEach { add(withheldFigureObject(it)) } }
    }

    /**
     * WHY a figure carries no number, in the store's own six statuses (RFC 166
     * §6) -- the reason BESIDE [DATA_AVAILABILITY_KEY], never inside it.
     *
     * `data_availability` keeps its exact shape (a bare list of wire names,
     * absent when empty): it answers WHICH figures are blank, and every reader of
     * it -- the prompt, the report page, this tool's own description -- reads that
     * one shape. This key answers WHY, in the `withheld_figures` shape the payload
     * already uses, so the coach meets one convention rather than two.
     *
     * A figure the publisher ESTIMATED is here as well as shown: it is a number,
     * and a family reading the publisher's estimate as the school's own figure
     * would be reading it wrong.
     *
     * Absent, never empty -- this payload's convention everywhere.
     *
     * It writes TWO keys since RFC 189 D1, and they are one fact: the pair most of
     * this college's figures are on ([FIGURE_PROVENANCE_KEY]) and the fields that
     * have something of their own to say ([FIGURE_STATUSES_KEY]). One function,
     * because "an exception can never exist without a default" is the invariant
     * the compression rests on, and two emitters would be two places for the
     * default to go missing.
     */
    private fun JsonObjectBuilder.putFigureStatuses(cost: CollegeCost) {
      // The default and the exceptions ride in ONE type, and the default field is
      // non-null (RFC 189 D1), so this is the only branch there is: either both
      // keys' subject exists or neither does. A payload carrying an exception with
      // no default is a state the domain cannot build.
      val provenance = cost.figureProvenance ?: return
      put(FIGURE_PROVENANCE_KEY, figureProvenanceObject(provenance.default))
      if (provenance.exceptions.isEmpty()) return
      putJsonArray(FIGURE_STATUSES_KEY) { provenance.exceptions.forEach { add(figureStatusObject(it)) } }
    }

    /**
     * The (tier, publisher) pair most of this college's figures are on, said ONCE
     * (RFC 189 D1) -- the default every [FIGURE_STATUSES_KEY] entry resolves its
     * unstated provenance keys against.
     *
     * ALL THREE KEYS, always, where an entry carries only the ones it differs on:
     * the default is what the resolution rule falls back to, so a key missing here
     * would leave a figure with nowhere to resolve from. The tier and its sentence
     * come off ONE value ([SpokenAssurance]), so this object cannot name a tier
     * the sentence beside it does not say; and that sentence is
     * [AssuranceTierCopy]'s own, never re-worded here and never ranked or scored
     * (RFC 179 D10).
     */
    private fun figureProvenanceObject(default: FigureProvenanceDefault): JsonObject =
      buildJsonObject {
        put(ASSURANCE_KEY, default.assurance.tier.value)
        put(ASSURANCE_STATEMENT_KEY, default.assurance.statement)
        put(FIGURE_SOURCE_KEY, default.source.value)
      }

    /**
     * One field's status: the field, the code, and the DOMAIN's own sentence for
     * it ([FigureStatusCopy]), never re-worded here -- the `income_band` +
     * `income_band_label` convention (RFC 151 D-D) applied to the six statuses.
     *
     * An entry exists only for a field with something of its OWN to say (RFC 189
     * D1), and it repeats a provenance key only where THAT KEY differs from the
     * default: three keys stated once per college replaced three keys stated once
     * per field, and the facts are the same ones because each of the two -- the
     * tier and the publisher -- resolves as "this entry's own value, else the
     * default's".
     *
     * WHICH keys differ is the DOMAIN's answer ([FigureProvenanceException]), not
     * a comparison redone here: the exception carries the overrides already
     * narrowed, so this writer cannot disagree with the summary about what the
     * default is.
     */
    private fun figureStatusObject(exception: FigureProvenanceException): JsonObject =
      buildJsonObject {
        val note = exception.note
        put("field", note.field.wireName)
        put(STATUS_KEY, note.status.value)
        // ABSENT rather than empty where the note has no status sentence -- a
        // shown, plainly reported figure (RFC 179 D6). Absence has ONE
        // representation on this payload, and an empty string would be a second.
        note.statement?.let { put(STATEMENT_KEY, it) }
        // What KIND of number this figure is, as a CODE and as the sentence to say
        // it in (RFC 179 D7) -- the `income_band` + `income_band_label`
        // convention a third time. A reader must not have to parse our English to
        // learn which tier a figure is on, and the two keys are read as a PAIR
        // with `status`: an imputed IPEDS cell and a self-reported one are soft in
        // different ways.
        //
        // PER KEY (RFC 189 D1): an entry carries a provenance key only where it
        // DIFFERS from [FIGURE_PROVENANCE_KEY], and a key it does not carry IS the
        // default's. The two facts resolve independently because they vary
        // independently -- the Scorecard relays IPEDS cells (the same tier, a
        // different publisher) and also originates federal administrative records
        // (a different tier, the same publisher).
        //
        // The tier's two keys cannot split: they are read off ONE
        // [SpokenAssurance], whose sentence is derived from its own code, so a
        // code with no sentence is not a state this writer can be handed.
        exception.assurance?.let {
          put(ASSURANCE_KEY, it.tier.value)
          put(ASSURANCE_STATEMENT_KEY, it.statement)
        }
        // The publisher whose row won this cell, as a CODE beside the sentence
        // (RFC 177 D4). `imputed_by_publisher` and `suppressed_by_publisher` are
        // acts of a publisher, so a reader that needs to know which one acted must
        // not have to re-parse [STATEMENT_KEY]'s English to find out.
        exception.source?.let { put(FIGURE_SOURCE_KEY, it.value) }
        // A YEAR GAP: this school DID publish the figure, at a year that is not
        // the one its price is quoted at. Absent on every other note, so its
        // PRESENCE is the fact -- `status` cannot carry it, because a year gap and
        // a cell we have never collected are both `not_collected_by_us`. Read off
        // [FigureStatusNote.heldAcademicYear] rather than off the sentence, which
        // is the whole point of the field: the year travels as data.
        note.heldAcademicYear?.let { put(HELD_ACADEMIC_YEAR_KEY, it) }
      }

    /**
     * One withheld figure: which field, the reason code, and the sentence that
     * both names the reason and points at the figures that ARE this family's (RFC
     * 157 D-A).
     *
     * The statement is the [WithheldReason]'s own, never re-worded HERE, so the
     * coach explains the blank in the domain's words. The report page prints the
     * same reason in [WithheldReason.cellPhrase], the short form beside this one,
     * with its own pointer at the table the reader is looking at (RFC 157 D-A):
     * one vocabulary, two lengths, and neither file invents a third.
     */
    private fun withheldFigureObject(withheld: WithheldFigure): JsonObject =
      buildJsonObject {
        put("field", withheld.field.wireName)
        put(WITHHELD_REASON_KEY, withheld.reason.value)
        put(STATEMENT_KEY, withheld.reason.statement)
      }

    /**
     * The per-arrangement split, RETURNING the fields it rendered so the vintage
     * labels below follow exactly what this object carries.
     *
     * It returns them rather than writing into a set the caller passes in: the
     * emit set decides which figures get an `academic_year` stamped on them, and a
     * `Unit`-returning function that reaches into the caller's own collection is
     * how a figure comes to be rendered without being recorded -- said to a family
     * with no year beside it, with no type and no test failing for it.
     */
    private fun JsonObjectBuilder.putBreakdown(breakdown: CostBreakdown): Set<CostField> {
      put(BREAKDOWN_KEY, breakdownObject(breakdown))
      // The COMPONENTS, not `lines`: since RFC 189 D3 this object no longer writes
      // the tuition figure, it NAMES the top-level key that carries it, and that
      // key was recorded by [putTuitionTiers] above. Returning the tuition field
      // from here would have the vintage labels date a figure written by another
      // step, which is the drift the record-what-you-emit rule exists against.
      return breakdown.arrangements.flatMap { it.componentLines }.mapTo(mutableSetOf()) { it.field }
    }

    /**
     * The way of living this answer LEADS with (RFC 152), when the family has
     * said where they plan to live.
     *
     * ABSENT, never null, when nothing is resolved: the plan is unanswered or
     * declined and this school carries no plan of its own, so the answer is
     * exactly what it was before this key existed -- all three arrangements, each
     * named and labelled (D3).
     *
     * It never narrows the breakdown beside it (D2): the other ways of living
     * stay in the payload as true facts, and a "what if we lived at home
     * instead?" stays answerable from the same result.
     *
     * The key emits no CostField into [emitted] because it emits no NEW figure:
     * the total it carries is the one the breakdown already rendered for the same
     * arrangement, already dated by the vintage labels below.
     */
    private fun JsonObjectBuilder.putChosenLivingArrangement(chosen: ChosenLivingPlan) {
      // Exhaustive with no `else`: a fourth case would otherwise fall silently
      // out of the payload, and this key is the whole of what the coach leads
      // with.
      when (chosen) {
        is ChosenLivingPlan.Priced -> {
          putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
            putChosenLivingPlan(chosen)
            // Always present on this case: a plan is only PRICED when this school
            // prices it, which means it carries a total (RFC 152 D2a). An
            // arrangement this school shows but cannot total is emitted as
            // no_total_here with its reason instead, so this key never ships
            // without a number or a reason.
            put(TOTAL_KEY, chosen.totalPerYearUsd)
            put(STATEMENT_KEY, statementOf(chosen))
          }
        }

        is ChosenLivingPlan.NoTotalHere -> {
          putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
            putChosenLivingPlan(chosen)
            // No total, because this school has none for it -- and the statement
            // says so, so the key never ships a plan with a silent blank where
            // its number should be.
            put(STATEMENT_KEY, statementOf(chosen))
          }
        }

        is ChosenLivingPlan.NotPricedHere -> {
          putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
            putChosenLivingPlan(chosen)
            put(STATEMENT_KEY, statementOf(chosen))
          }
        }

        ChosenLivingPlan.NotChosen -> {}
      }
    }

    /**
     * The sentence for a resolved, priced plan. [LivingPlanSource] decides which
     * one: a per-college plan is something the family SAID about this school, and
     * their usual plan is an ASSUMPTION being made here -- which the coach must
     * name in the same breath, because `with_family` is never inferred by us
     * (RFC 152 D2a).
     */
    private fun statementOf(chosen: ChosenLivingPlan.Priced): String =
      when (chosen.source) {
        LivingPlanSource.PER_COLLEGE -> {
          "The student said they plan on ${chosen.plan.label} at this school, so lead with that way of " +
            "living and name it. The other ways of living are still here and still true if they ask."
        }

        LivingPlanSource.PROFILE_DEFAULT -> {
          "${chosen.plan.label.replaceFirstChar { it.uppercase() }} is the student's usual plan, assumed " +
            "for this school - say the assumption in the same breath as the number, and if they correct it, " +
            "record it as this school's own plan. The other ways of living are still here and still true."
        }
      }

    /**
     * The sentence for a resolved plan this school shows but cannot total.
     *
     * One sentence per [NoTotalReason], because the causes are not one fact: a gap
     * of OURS says so and says what would close it, and only the school's own
     * silence is stated as the school's. Saying "this school has no total for that
     * way of living" because WE have not asked which state the student lives in
     * would blame a price list for our own question (RFC 149 D-B).
     *
     * Every case ends the same way: quote the parts that are here, and never add
     * up what is there and call it the total.
     */
    private fun statementOf(chosen: ChosenLivingPlan.NoTotalHere): String =
      when (chosen.reason) {
        NoTotalReason.AWAITING_RESIDENCY_ANSWER -> {
          "The student plans on ${chosen.plan.label}, and this school's published price depends on which state " +
            "the student is a resident of - which we have not been told, so the gap is ours and not this " +
            "school's. Ask where the student is a resident and the total follows; meanwhile quote the parts " +
            "that are here and never add up what is there and call it the total."
        }

        NoTotalReason.TUITION_APPLICABILITY_UNKNOWN -> {
          "The student plans on ${chosen.plan.label}, but we cannot tell which of this school's published " +
            "prices applies to this student, so the missing total is ours and not this school's. Quote the " +
            "parts that are here and never add up what is there and call it the total."
        }

        NoTotalReason.PART_NOT_PUBLISHED -> {
          "The student plans on ${chosen.plan.label}, but this school does not publish every part of what that " +
            "way of living costs, so there is no total for it. Quote the parts of it that are here, say which " +
            "part is missing, and never add up what is there and call it the total."
        }

        // OURS. The school is not named, because it may publish the part
        // perfectly well and we are the ones who do not hold it for the year the
        // rest of this school's price is quoted at (RFC 166 §3, §6).
        NoTotalReason.PART_NOT_COLLECTED_BY_US -> {
          "The student plans on ${chosen.plan.label}, but we have not collected every part of what that way of " +
            "living costs at this school, so there is no total for it. The gap is ours, not this school's - " +
            "never say the school does not publish it. Quote the parts of it that are here and never add up " +
            "what is there and call it the total."
        }
      }

    /**
     * The sentence for a resolved plan this school is not priced for. Two
     * reasons, two sentences (the [ArrangementGap] split RFC 151 landed): a
     * school with no residence halls has ANSWERED, and a school that published no
     * figure has stayed silent. Neither becomes a substituted arrangement.
     */
    private fun statementOf(chosen: ChosenLivingPlan.NotPricedHere): String {
      // The gap's own spoken phrase ([ArrangementGap.phrase]), never a second
      // wording of it: the comparison says the same silence the same way.
      return "The student plans on ${chosen.plan.label}, but this school has ${chosen.reason.phrase}. Say that " +
        "plainly, and never quote another way of living in its place or carry a figure across from another school."
    }

    /**
     * The no-dorms answer (RFC 149 D-B), emitted whenever IPEDS ANSWERED -- true
     * as well as false.
     *
     * `false` is "this school has no residence halls"; `true` is the reported
     * fact that an absent `on_campus` arrangement is this school's silence about
     * its components rather than its having no dorms. A rendered arrangement is
     * NOT a surrogate for the flag: a school with dorms that publishes no
     * on-campus figure carries no arrangement, so the two are different facts and
     * collapsing them left the model unable to tell a known fact from a gap.
     *
     * Only an unknown flag is absent -- no IPEDS row is not evidence either way.
     * The key rides even when the school publishes on-campus figures in spite of
     * a `false` flag (D-B): both facts are true and the coach is owed both.
     *
     * Exhaustive on the nullable, so a future fourth state cannot fall through
     * this branch silently.
     */
    private fun JsonObjectBuilder.putOffersOnCampusHousing(cost: CollegeCost) {
      when (cost.offersOnCampusHousing) {
        true -> put(OFFERS_ON_CAMPUS_HOUSING_KEY, true)
        false -> put(OFFERS_ON_CAMPUS_HOUSING_KEY, false)
        null -> Unit
      }
    }

    /**
     * One object per vintage this college actually carries a figure of (RFC 149
     * D-E): the academic year, and the wire names that year dates.
     *
     * The year NAMES ITS FIGURES rather than sitting beside the college as a bare
     * label. Membership -- "the components are the published price, the sticker
     * and the net price are the blend" -- otherwise lived only in
     * `CostField.figureGroup` and in prose, so a reader that did not carry the
     * convention could attach either year to any figure, and the prompt's
     * instruction to quote the year beside a figure had no year beside any
     * figure.
     *
     * Both the key and its [DATED_FIGURES_KEY] list are read off the SAME
     * recorded [emitted] set, so the label can never date a figure this payload
     * did not render. A field whose vintage is null appears under no key and is
     * therefore said with no year at all.
     *
     * MUST be called AFTER every figure this object emits. [emitted] is complete
     * only because each `put*` above it recorded into it; a figure put after this
     * call is a figure said with no academic year, and neither the types nor a
     * test would notice.
     */
    private fun JsonObjectBuilder.putVintageLabels(
      cost: CollegeCost,
      emitted: Set<CostField>,
    ) {
      FigureGroup.entries.forEach { group ->
        val dated = emitted.filter { it.figureGroup == group }
        // The year is THIS school's, read off the rows it served (RFC 166 §3
        // rule 4) rather than off a Kotlin constant: a school served from the
        // Scorecard says 2024-25 where a school served from IC_AY says 2023-24
        // (RFC 183), and one constant could only ever have said one of them. A group can carry figures and NO year: two
        // blended rows at different vintages resolve to null rather than to the
        // newer one (RFC 166 §8), and then the figures ride with no label instead
        // of a wrong one.
        val academicYear = cost.academicYearOf(group)
        if (dated.isNotEmpty() && academicYear != null) {
          putJsonObject(group.wireName) {
            put(ACADEMIC_YEAR_KEY, academicYear)
            // Enum declaration order, not the order they happened to be emitted
            // in: the list is a fact about the payload, so it must not depend on
            // set iteration.
            putJsonArray(DATED_FIGURES_KEY) {
              CostField.listInDeclarationOrder(dated).forEach { add(JsonPrimitive(it.wireName)) }
            }
          }
        }
      }
    }

    /**
     * The `cost_by_living_arrangement` object: one entry per arrangement this
     * school can be priced for, keyed by the arrangement's wire name.
     *
     * The component keys inside are [CostField] wire names -- identical to the
     * column names -- so the JSON, `data_availability` and the schema all speak
     * one vocabulary. They look redundant nested inside an arrangement object on
     * purpose: the alternative is a second set of arrangement-local names that
     * nothing else shares, and a `data_availability` entry no key in the payload
     * matches.
     */
    private fun breakdownObject(breakdown: CostBreakdown): JsonObject =
      buildJsonObject {
        // The ONE tuition figure every total in this object includes, named rather
        // than repeated (RFC 189 D3). It used to be written inside all three
        // arrangement objects AND as the top-level tuition-tier key -- the same
        // residency-selected figure under the same wire key four times. The figure
        // stays exactly where [putTuitionTiers] already writes it and the
        // composition of each total stays derivable: total = this key's amount
        // plus the components listed beside it.
        //
        // MANDATORY whenever any arrangement carries a total: a total is null
        // unless the tuition line is present ([ArrangementCost.totalPerYearUsd]),
        // so a total with no tuition field named is a state the domain cannot
        // build.
        putTuitionFieldInTotals(breakdown)
        breakdown.arrangements.forEach { arrangement ->
          putJsonObject(arrangement.arrangement.value) {
            // The arrangement's OWN parts. The tuition line is excluded by reading
            // [ArrangementCost.componentLines] rather than by filtering
            // `lines` against a field list: the type is what decides which line is
            // the tuition line, and a filter here would be a second answer.
            arrangement.componentLines.forEach { put(it.field.wireName, it.amountUsd) }
            // Absent whenever any part is missing (RFC 149 D-C): a partial sum is
            // not a total, and neither is a sum that guessed at the student's
            // residency.
            arrangement.totalPerYearUsd?.let { put(TOTAL_KEY, it) }
            putAssumedByUnicoach(arrangement)
          }
        }
      }

    /**
     * The top-level tuition key whose amount every total in this breakdown
     * includes (RFC 189 D3), or nothing when no arrangement here has a tuition
     * line at all -- and then no arrangement has a total either.
     *
     * ONE key for the whole object, not one per arrangement: [CostBreakdown] is
     * built from a single tuition line ([tuitionLineOf]), the figure the family's
     * own residency answer selected, so three copies of the name could only ever
     * have said the same thing three times.
     */
    private fun JsonObjectBuilder.putTuitionFieldInTotals(breakdown: CostBreakdown) {
      val field = breakdown.arrangements.firstNotNullOfOrNull { it.tuitionLine }?.field ?: return
      put(TUITION_FIELD_IN_TOTALS_KEY, field.wireName)
    }

    /**
     * WHICH lines in this arrangement carry an amount of OURS rather than the
     * school's (RFC 166 §7, gate-2 D17).
     *
     * Read off [CostLine.origin], never a hand-written list: the type is what
     * decides whose a number is, and a second list here would be a chance for the
     * wire to attribute to a school a figure the domain says is ours. Today that
     * is exactly the at-home `$0` food-and-housing line, so the key appears on
     * `with_family` and nowhere else -- absent, never empty, like every other list
     * in this payload.
     *
     * It is the wire half of the sentence the basis facts carry
     * ([AT_HOME_ASSUMPTION_STATEMENT]): a model reading this payload can never
     * report the zero as something the school published or failed to publish.
     */
    private fun JsonObjectBuilder.putAssumedByUnicoach(arrangement: ArrangementCost) {
      val assumed = arrangement.lines.filter { it.origin == LineOrigin.ASSUMED_BY_UNICOACH }
      if (assumed.isEmpty()) return
      putJsonArray(ASSUMED_BY_UNICOACH_KEY) { assumed.forEach { add(JsonPrimitive(it.field.wireName)) } }
    }

    /** One invitation: the money-profile field it would fill, and the sentence the coach may say for it. */
    private fun precisionOfferObject(
      offer: PrecisionOffer,
      cost: CollegeCost,
    ): JsonObject =
      buildJsonObject {
        put("field", offer.field)
        put("offer", offerCopy(offer, cost))
      }

    /**
     * The sentence the coach may say for one offer. Exhaustive on purpose: a new
     * [PrecisionOffer] member must fail to compile here — the one site that owes
     * it copy — rather than ship an invitation with no words in it.
     *
     * The residency invitation has TWO wordings and the SCHOOL decides which (RFC
     * 166 §4). The two-price copy is wrong at a community college, where there are
     * three; the three-tier copy is wrong everywhere else, where it would name a
     * district price the school does not publish. So the renderer READS the
     * domain's decision ([ResidencyTierBasis], the same code and sentence this
     * payload carries under `residency_tiers`) rather than re-deriving the tier
     * shape from one amount: the second derivation promised three prices at a
     * school whose own statement in the same object said it does not publish all
     * three, and named an out-of-state price that does not exist.
     *
     * Exhaustive on the basis with no `else`, exactly as it is on [PrecisionOffer]:
     * a SEVENTH basis must decide its own invitation before it compiles.
     */
    private fun offerCopy(
      offer: PrecisionOffer,
      cost: CollegeCost,
    ): String =
      when (offer) {
        PrecisionOffer.RESIDENCY -> {
          when (cost.residencyTiers) {
            ResidencyTierBasis.THREE_TIERS_PUBLISHED -> RESIDENCY_OFFER_THREE_TIERS

            // Every other basis publishes at most two tuition prices, so the
            // three-tier words would name a tier this payload carries no key for.
            ResidencyTierBasis.TWO_TIERS_PUBLISHED,
            ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT,
            ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER,
            ResidencyTierBasis.SINGLE_PUBLISHED_PRICE,
            -> RESIDENCY_OFFER

            // Its own arm, and not folded in above: the reason the others share
            // is "at most two prices", and this school publishes NONE, so the
            // general copy is chosen here for a different reason. The offer
            // cannot reach it today -- [PrecisionOffer.RESIDENCY] is admitted
            // only where [CollegeCost.reportsPublishedTuition], which reads the
            // same tier list this basis is derived from and is false exactly when
            // it is empty -- and if that gate ever widens, the general invitation
            // promises which price applies rather than naming a tier we hold no
            // key for.
            ResidencyTierBasis.NO_PUBLISHED_TUITION -> RESIDENCY_OFFER
          }
        }

        PrecisionOffer.INCOME_BAND -> {
          INCOME_BAND_OFFER
        }

        PrecisionOffer.LIVING_PLAN -> {
          LIVING_PLAN_OFFER
        }
      }

    /**
     * The `net_price` sub-object: `amount_usd` when reported, the basis label, the
     * band's code and its spoken dollar range on a band-specific basis
     * (`IncomeBand.bracket`, RFC 142), and — when we hold the figure back — the
     * reason and the words for it. The label rides beside the code so the model
     * never has to invent a phrase for the bucket it is naming aloud.
     *
     * A withheld figure SAYS SO HERE, and not only in [WITHHELD_FIGURES_KEY]: an
     * object carrying a basis and no amount is otherwise indistinguishable from
     * the school's own silence, which is the very conflation the domain type
     * removed.
     */
    private fun netPriceObject(netPrice: NetPrice): JsonObject =
      buildJsonObject {
        netPrice.amount?.let { put("amount_usd", it) }
        put("basis", netPrice.basis)
        // Exhaustive on purpose: an overall average deliberately emits no
        // qualifier, and a future NetPrice case must fail to compile here rather
        // than ship an unlabeled basis. Do not collapse this to an `if`.
        when (netPrice) {
          is NetPrice.BandSpecific -> {
            putIncomeBand(netPrice.band)
          }

          is NetPrice.OverallAverage -> {}

          // The basis the family's own answer selected rides above, so the label
          // that basis PROMISES rides with it -- a `your_income_band` object with
          // no band label would break an invariant the prompt states. Only the
          // amount is withheld, and the reason for that is said right here.
          is NetPrice.Withheld -> {
            putBasisLabel(netPrice.publishedBasis)
            put(WITHHELD_REASON_KEY, netPrice.reason.value)
            put(STATEMENT_KEY, netPrice.reason.statement)
          }
        }
      }

    /**
     * The qualifier one net-price BASIS owes, whether or not an amount came with
     * it: the band's code and spoken range on the band-specific basis, nothing on
     * the all-family average.
     *
     * Exhaustive, so a basis added to the vocabulary must say what label it ships.
     */
    private fun JsonObjectBuilder.putBasisLabel(basis: NetPriceBasis) {
      when (basis) {
        is NetPriceBasis.YourIncomeBand -> putIncomeBand(basis.band)
        NetPriceBasis.OverallAverage -> Unit
      }
    }

    /**
     * The money-profile echo: all three field statuses, values only when answered.
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
        putComparedLivingPlan(profile.living)
      }

    /**
     * The living-plan answer as the coach reads it: the status always, and the
     * plan itself only on the answered case.
     *
     * One emitter for the two objects that echo it (the money-profile block and
     * the comparison's arrangement basis), and exhaustive with no `else`, so the
     * plan is reachable only through [ComparedLivingPlan.Answered] -- which is the
     * whole reason that type is sealed.
     */
    private fun JsonObjectBuilder.putComparedLivingPlan(answer: ComparedLivingPlan) {
      put("living_plan_status", answer.status.value)
      when (answer) {
        is ComparedLivingPlan.Answered -> {
          putLivingPlan(answer.plan)
        }

        ComparedLivingPlan.Unanswered, ComparedLivingPlan.Declined -> {}
      }
    }

    const val TOOL_NAME = "college_cost_profile"

    /**
     * The two keys that IDENTIFY a college wherever this payload names one --
     * the top-level results, the dated-college lists, the per-school basis
     * entries, and the major answer's own college entries.
     *
     * Constants rather than seven copies of two literals, like every other key
     * the DESCRIPTION quotes back to the model (see [ARRANGEMENT_KEY]): a payload
     * that spelled one of them differently in one section would be two wire
     * vocabularies in one answer, and only the reader diffing sections would
     * know.
     */
    const val COLLEGE_ID_KEY = "college_id"
    const val COLLEGE_NAME_KEY = "name"

    /**
     * The wire key naming the publishers this answer's own figures came from
     * (RFC 177).
     *
     * It used to be a CONSTANT -- one hand-typed publisher's name for every
     * figure in every payload, which the loader's own precedence order
     * contradicted for most of them. It is now derived from the rows that
     * actually won ([CollegeCost.moneySources]), so the payload cannot name a
     * publisher whose figure it does not carry.
     *
     * It names the source and nothing else (RFC 149 D-E). It used to append
     * "(data ingested 2026)", which was `colleges.updated_at` -- WHEN WE LOADED
     * THE FILE, not the year of the figures -- and the coach read it aloud as a
     * vintage. The real vintage rides per college, beside the figures it
     * governs, as a per-college academic-year label grouped by [FigureGroup].
     */
    const val SOURCE_KEY = "source"

    /** The wire key carrying the per-arrangement price split (RFC 149). */
    const val BREAKDOWN_KEY = "cost_by_living_arrangement"

    /**
     * The top-level tuition key whose amount every total inside
     * [BREAKDOWN_KEY] includes (RFC 189 D3).
     *
     * A NAME, not a figure: the one residency-selected tuition amount used to be
     * written inside each of the three arrangement objects as well as at the
     * top level, so the payload stated it four times. The arrangement objects
     * keep their own components, their [TOTAL_KEY] and their
     * [ASSUMED_BY_UNICOACH_KEY], and each total stays derivable as this key's
     * amount plus the components listed beside it.
     *
     * Present whenever any arrangement carries a total, because a total is null
     * unless the tuition line is present -- so a total with no tuition field
     * named is a state the domain cannot build.
     */
    const val TUITION_FIELD_IN_TOTALS_KEY = "tuition_field_in_totals"

    /**
     * The way of living this school's answer leads with (RFC 152) -- absent
     * when the family has said nothing about where they plan to live.
     */
    const val CHOSEN_ARRANGEMENT_KEY = "chosen_living_arrangement"

    /**
     * The resolved way of living itself, inside [CHOSEN_ARRANGEMENT_KEY] and
     * inside [CHOSEN_BY_COLLEGE_KEY]: the wire name of the arrangement.
     *
     * Constants rather than literals, like every other key the DESCRIPTION
     * quotes back to the model: a rename must move the payload and the prompt
     * together, or the coach is told to read a key nothing emits.
     */
    const val ARRANGEMENT_KEY = "arrangement"

    /** The resolved arrangement in the student's own words -- the paired-label convention. */
    const val ARRANGEMENT_LABEL_KEY = "label"

    /** Where the resolved plan came from ([LivingPlanSource]): the family said it here, or it is their usual plan. */
    const val ARRANGEMENT_SOURCE_KEY = "source"

    /** WHICH of the four resolved-plan cases this object is in ([LivingPlanPricing]) -- the code beside the statement. */
    const val PRICING_KEY = "pricing"

    /** Why this SCHOOL is not priced for the resolved plan ([ArrangementGap]) -- never a substituted arrangement. */
    const val ARRANGEMENT_REASON_KEY = "reason"

    /**
     * Why a plan this school DOES show carries no total ([NoTotalReason]) -- its
     * own key, because two of its three causes are gaps of ours and
     * [ARRANGEMENT_REASON_KEY]'s vocabulary states what the school published.
     * One key per vocabulary, so no reader has to know which case it is in to
     * know which words it is reading.
     */
    const val NO_TOTAL_REASON_KEY = "no_total_reason"

    /** The per-school plans inside the comparison basis: emitted only when the schools are not all on one plan. */
    const val CHOSEN_BY_COLLEGE_KEY = "chosen_by_college"

    /**
     * The per-school entries inside one basis fact (RFC 189 D2) -- present only
     * where a school is not fully described by the [STATEMENT_KEY] above it.
     *
     * A constant rather than a literal, like every other key the DESCRIPTION
     * quotes back to the model (see [ARRANGEMENT_KEY]): a rename must move the
     * payload and the prompt together.
     */
    const val BY_COLLEGE_KEY = "by_college"

    /** The IPEDS housing answer's own key -- emitted whenever the flag is known, true or false (RFC 149 D-B). */
    const val OFFERS_ON_CAMPUS_HOUSING_KEY = "offers_on_campus_housing"

    /** The academic year one vintage names -- a label ("2024-25"), never a bare year. */
    const val ACADEMIC_YEAR_KEY = "academic_year"

    /**
     * The year we hold ONE figure for, when it is not the year this school's
     * price is quoted at -- inside a [FIGURE_STATUSES_KEY] entry (RFC 166 §3).
     *
     * Its own key because [FigureStatus] cannot carry the fact: a year gap and a
     * cell we have genuinely never collected are BOTH `not_collected_by_us`. The
     * two years were readable only by substring-matching our own English
     * sentence, so a reader that wanted the held year had to parse prose about
     * somebody's money.
     *
     * There is no `served_academic_year` beside it. The served year is already
     * on the college object, under the vintage label that dates the very figures
     * this entry is about ([ACADEMIC_YEAR_KEY]), and a second copy is a second
     * place for it to be wrong.
     */
    const val HELD_ACADEMIC_YEAR_KEY = "held_academic_year"

    /**
     * WHOSE row this figure's status came from -- the publisher's own code
     * ([MoneySource.value]), inside a [FIGURE_STATUSES_KEY] entry (RFC 177 D4).
     *
     * The code beside the sentence, the `income_band` + `income_band_label`
     * convention this payload has followed since RFC 151 D-D. Two of the six
     * statuses are the PUBLISHER's own act -- it estimated the figure, or it
     * withheld it -- so which publisher acted is the fact of the entry, and
     * without this key a reader could recover it only by parsing the English in
     * [STATEMENT_KEY].
     *
     * Note the top-level source phrase stays PROSE and gains no parallel code
     * array (D5): it is what the coach reads aloud, and one figure's publisher
     * is a different question from the page's source list.
     */
    const val FIGURE_SOURCE_KEY = "source"

    /**
     * WHAT KIND of number a figure is ([AssuranceTier.value]), inside a
     * [FIGURE_STATUSES_KEY] entry (RFC 179 D7).
     *
     * Beside [FIGURE_SOURCE_KEY] and not folded into it: WHO published a figure
     * and WHAT INSTRUMENT produced it are two facts, and the Scorecard is
     * exactly where they come apart -- it relays sixteen IPEDS cells and
     * originates two federal administrative records, so the publisher does not
     * decide the tier.
     *
     * Read as a PAIR with `status`, never instead of it: `imputed_by_publisher`
     * sits ON TOP of whichever tier the cell is on. Never a score, a number or
     * a rating -- three slugs, three sentences, and no ranking between them.
     */
    const val ASSURANCE_KEY = "assurance"

    /** The sentence that says [ASSURANCE_KEY], from the domain's one home for it -- said, never re-worded. */
    const val ASSURANCE_STATEMENT_KEY = "assurance_statement"

    /** The wire names one vintage dates, so no reader infers membership from a naming convention. */
    const val DATED_FIGURES_KEY = "figures"

    /**
     * The schools one academic year inside `comparison_basis` is true of -- the
     * subject the year has carried since it became a per-school fact (RFC 166
     * §3). Each element is a `{college_id, name}` object, so the subject is
     * matched by id and only read aloud by name.
     */
    const val DATED_COLLEGES_KEY = "colleges"

    /**
     * One arrangement's total. Unsuffixed by measure but not by unit: it is a
     * scalar, so it says its own unit, exactly like every other dollar key here.
     */
    const val TOTAL_KEY = "total_per_year_usd"

    /** The wire key carrying the upgrade invitations — one home for the emit site and the description. */
    const val PRECISION_OFFER_KEY = "precision_offer"

    /**
     * The wire key carrying the assumptions a side-by-side holds constant (RFC
     * 151) — present only when the answer carries two or more colleges.
     */
    const val COMPARISON_BASIS_KEY = "comparison_basis"

    /** The stable code one basis fact rides under; its spoken twin is [STATEMENT_KEY]. */
    const val BASIS_KEY = "basis"

    /**
     * The SIXTH fact inside [COMPARISON_BASIS_KEY] (RFC 157 D-C): which
     * residency the published price and the price after a financial aid offer
     * are on, and which schools in this call they do not describe.
     *
     * Its own key beside `residency` rather than folded into it, because they
     * are two different facts about two different columns: `residency` says
     * which of a PAIR of published tuition figures this family is charged, and
     * this one says that the two blended figures have no pair at all.
     */
    const val BLENDED_FIGURE_BASIS_KEY = "blended_figure_basis"

    /**
     * Whether the two blended figures describe THIS family at one school --
     * written only when the answer is known. [putBlendedFiguresApply] owns that
     * rule and says why.
     */
    const val APPLIES_KEY = "applies_to_this_family"

    /**
     * The figures we hold and are not showing this family, each with its reason
     * (RFC 157 D-A) -- absent, never empty. Its presence is the ONE signal that
     * a [DATA_AVAILABILITY_KEY] entry is ours rather than the school's silence.
     */
    const val WITHHELD_FIGURES_KEY = "withheld_figures"

    /**
     * The fields this answer carries NO number for, whichever of the two reasons
     * it is -- absent, never empty, like every other list in this payload.
     */
    const val DATA_AVAILABILITY_KEY = "data_availability"

    /**
     * Why a college result carries no [AidPolicyWire.KEY] section (RFC 170):
     * present exactly when that section is absent, so the model never has to
     * infer a school's aid policy from a missing key.
     */
    const val AID_POLICY_AVAILABILITY_KEY = "aid_policy_availability"

    /**
     * Why a college result carries no [BorrowingWire.KEY] section (RFC 175):
     * present exactly when that section is absent, on the
     * [AID_POLICY_AVAILABILITY_KEY] rule, so the model never has to infer a
     * school's borrowing from a missing key.
     */
    const val BORROWING_AVAILABILITY_KEY = "borrowing_availability"

    /**
     * The keys whose value is a NUMBER by contract -- the RFC 143 guard's
     * allowlist for this tool's payload.
     *
     * On the TOOL, as `CollegeAdmissionsChatTool.NUMBERS_BY_CONTRACT` already
     * is: it is a statement about what this tool emits, so a test-only copy
     * would be a production contract asserted from a duplicate. Every measure
     * list is read from its own home ([CostField], the two wire objects), so a
     * new figure joins by being declared rather than by being remembered here.
     */
    val NUMBERS_BY_CONTRACT: Set<String> =
      CostField.entries.map { it.wireName }.toSet() +
        setOf("count", "amount_usd", TOTAL_KEY) +
        MeritAidWire.NUMERIC_KEYS +
        AidPolicyWire.NUMERIC_KEYS +
        BorrowingWire.NUMERIC_KEYS

    /** OUR coverage: the corpus carries no filing for this school at all. */
    const val AID_POLICY_NO_FILING: String =
      "We hold no Common Data Set filing for this school, so we have nothing to say about how it treats " +
        "financial need or which aid forms it requires. Say that plainly and point the family at the school's " +
        "financial aid office; never estimate it from another school."

    /**
     * THEIR filing, and nothing in it we can read -- a different fact from
     * having no filing, and it must not be spoken as one. It is also not the
     * school saying it requires nothing: an absent form is absent from the
     * filing (D5).
     */
    const val AID_POLICY_NO_FACT_IN_FILING: String =
      "We hold this school's Common Data Set filing, but it reports none of the need figures or aid forms we " +
        "read out of it. Say that we have nothing on this school's aid policy rather than that the school " +
        "requires nothing, and point the family at its financial aid office."

    /** OUR coverage again: no filing at all, so nothing to say about borrowing (RFC 175). */
    const val BORROWING_NO_FILING: String =
      "We hold no Common Data Set filing for this school, so we have nothing to say about what its graduates " +
        "borrowed. Say that plainly; never estimate it from another school, and never treat the federal-loan " +
        "figure elsewhere in this result as the whole of what students here borrow."

    /**
     * THEIR filing, with no borrowing block in it -- a different fact from
     * having no filing, and it must not be spoken as one. It is also not the
     * school saying its graduates borrowed nothing.
     */
    const val BORROWING_NO_FACT_IN_FILING: String =
      "We hold this school's Common Data Set filing, but it reports none of the borrowing figures we read out " +
        "of it. Say that this school does not report them rather than that its graduates borrow nothing, and " +
        "point the family at its financial aid office."

    /**
     * THEIR filing ANSWERS the borrowing questions and WE could not read the
     * answers out of it -- OUR gap (D7), and the one silence of the three that
     * is not the school's.
     *
     * Its own constant because it is its own fact: told as
     * [BORROWING_NO_FACT_IN_FILING], it would put our failure in the school's
     * mouth and tell a family this school does not report a figure it does.
     */
    const val BORROWING_NOT_READ_BY_US: String =
      "We hold this school's Common Data Set filing and it answers the borrowing questions, but we could not " +
        "read those answers out of it. Say that WE could not read them, never that this school does not " +
        "report them, and point the family at its financial aid office."

    /**
     * THE PUBLISHER's own withholding: the filing answers the borrowing
     * questions and the figures are suppressed. Neither ours nor the school's
     * plain silence, so it is neither of the two sentences above.
     */
    const val BORROWING_WITHHELD_BY_PUBLISHER: String =
      "We hold this school's Common Data Set filing and its borrowing figures are withheld by the publisher. " +
        "Say that they are withheld, never that this school does not report them and never that its graduates " +
        "borrow nothing, and point the family at its financial aid office."

    /**
     * THE SOURCE itself says the question does not apply here -- a stated fact
     * about this school, and not a silence of anyone's.
     */
    const val BORROWING_NOT_APPLICABLE_IN_FILING: String =
      "We hold this school's Common Data Set filing and it marks the borrowing questions as not applicable to " +
        "this school. Say that the source says they do not apply here, never that the school declined to " +
        "report them, and point the family at its financial aid office."

    /**
     * OURS (D6): this filing DOES report borrowing figures, and the only ones
     * it reports are a borrower count and a graduating class that cannot both
     * be true. We hold the pair, we could not reconcile it, and so we are not
     * showing it.
     *
     * Its own constant because the withholding is OURS. Told as
     * [BORROWING_NO_FACT_IN_FILING] -- which is what a state that dropped the
     * contradiction said -- it reports a school that answered two borrowing
     * cells as reporting none of them, which is the same inversion D7 forbids
     * with the reasons reversed.
     */
    const val BORROWING_NOT_RECONCILED_BY_US: String =
      "We hold this school's Common Data Set filing and it does report borrowing figures, but the ones it " +
        "reports cannot be reconciled with the graduating class in the same filing, so WE are not showing " +
        "them. Say that we hold figures we could not check and are withholding them, never that this school " +
        "does not report them and never that its graduates borrow nothing, and point the family at its " +
        "financial aid office."

    /**
     * The CODE beside each sentence above, so whose silence a school's is can
     * be read as a fact rather than matched out of copy (the
     * [RESIDENCY_TIERS_KEY] convention).
     */
    const val BORROWING_NO_FILING_BASIS: String = "no_filing"

    /** THEIR silence: a filing with no borrowing figure in it. */
    const val BORROWING_NO_FACT_IN_FILING_BASIS: String = "no_fact_in_filing"

    /** OURS (D7): the filing answers the borrowing questions and we could not read the answers. */
    const val BORROWING_NOT_READ_BY_US_BASIS: String = "not_read_by_us"

    /** THE PUBLISHER's: the figures are suppressed. */
    const val BORROWING_WITHHELD_BASIS: String = "withheld_by_publisher"

    /** THE SOURCE's own statement that the questions do not apply to this school. */
    const val BORROWING_NOT_APPLICABLE_BASIS: String = "not_applicable"

    /** OURS again (D6): the filing's only borrowing cells are a pair we could not reconcile, so WE withhold them. */
    const val BORROWING_NOT_RECONCILED_BY_US_BASIS: String = "not_reconciled_by_us"

    /**
     * WHICH of the three blended-figure outcomes applies at one school
     * ([BlendedFigureApplicability]), ALWAYS written beside the known-only
     * [APPLIES_KEY] boolean.
     *
     * The boolean has two readable values and the fact has three, so its
     * absence would have to carry the third -- and absence has two causes here
     * (the residency question is open, or we did not recognise the control),
     * which a reader cannot tell apart. The code says which, in one field, with
     * nothing to remember.
     */
    const val APPLIES_BASIS_KEY = "applies_to_this_family_basis"

    /** Why one figure is withheld ([WithheldReason]) -- beside the field it is withheld for. */
    const val WITHHELD_REASON_KEY = "reason"

    /**
     * WHY a figure carries no number, in the store's six statuses (RFC 166 §6)
     * -- the reason BESIDE [DATA_AVAILABILITY_KEY] rather than inside it, so
     * that key keeps the exact shape every reader of it already has.
     */
    const val FIGURE_STATUSES_KEY = "figure_statuses"

    /**
     * WHY a figure carries no number ([FigureStatus]) -- on a
     * [FIGURE_STATUSES_KEY] entry and on a basis fact.
     *
     * A constant rather than a literal, like every other key the DESCRIPTION
     * quotes back to the model (see [ARRANGEMENT_KEY]): a rename must move the
     * payload and the prompt together.
     */
    const val STATUS_KEY = "status"

    /**
     * The (tier, publisher) pair most of this college's figures are on, said
     * ONCE per college (RFC 189 D1) -- the default a [FIGURE_STATUSES_KEY] entry
     * with no pair of its own resolves to.
     *
     * It carries all three keys ([ASSURANCE_KEY], [ASSURANCE_STATEMENT_KEY],
     * [FIGURE_SOURCE_KEY]) ALWAYS, where an entry carries only the ones it
     * differs on, so a reader applies ONE rule per key: a figure's tier is its
     * own entry's [ASSURANCE_KEY] if the entry has one and this object's
     * otherwise, and its publisher is its own entry's [FIGURE_SOURCE_KEY] if the
     * entry has one and this object's otherwise.
     *
     * PRESENT whenever this college holds any figure provenance at all, and
     * absent only where it holds none -- the case with no figure to resolve. A
     * default over an empty set would name a tier and a publisher for no figure,
     * and an invented value is worse than an absence. There is no payload with
     * an exception and no default: the two ride in one type
     * ([FigureProvenanceSummary]) whose default field is non-null.
     */
    const val FIGURE_PROVENANCE_KEY = "figure_provenance"

    /**
     * WHICH tuition tiers this school publishes ([ResidencyTierBasis]) -- the
     * code and the sentence, on every college.
     *
     * Its own key rather than a widening of `tuition_applicable`: that one says
     * which published price applies to THIS student, and this one says how many
     * prices there are to apply. A school whose publisher never separated a
     * district price says exactly that here, which is the only way an in-state
     * figure can be shown without ever being re-labelled an in-district one.
     *
     * A school we hold NO tuition price for says THAT, in
     * [ResidencyTierBasis.NO_PUBLISHED_TUITION]'s own words. The key is still
     * present, because "we hold no price" is an answer; what it may never carry
     * is `single_published_price`, which announced a price list this payload has
     * no tuition key for.
     *
     * The one HOME of the key is `:db`, beside the vocabulary it names (RFC 169):
     * college search reports the same fact under the same key, and two constants
     * for one wire key is the drift the shared emitter exists to prevent. This
     * stays as an alias because the description and the tests read it by name.
     */
    const val RESIDENCY_TIERS_KEY = ed.unicoach.db.models.RESIDENCY_TIERS_KEY

    /**
     * The lines inside one arrangement whose amount is OURS (RFC 166 §7) --
     * absent, never empty, so it appears on `with_family` and nowhere else.
     */
    const val ASSUMED_BY_UNICOACH_KEY = "assumed_by_unicoach"

    /**
     * WHICH published tuition figure the comparison holds constant at ONE school
     * (RFC 151), inside `comparison_basis.residency.by_college`.
     *
     * Its own key with its own five-code vocabulary — `in_state`,
     * `out_of_state`, `unknown`, `single_published_price`,
     * `published_price_unknown` — deliberately NOT the per-college
     * `tuition_applicable`, which is the public-only in/out/unknown fact. The
     * two answer different questions, so a reader never has to remember which
     * object it is looking at to know which vocabulary it is reading.
     */
    const val TUITION_BASIS_KEY = "tuition_basis"

    /**
     * The `colleges.control` value we could not place, beside the
     * `published_price_unknown` entry it produced — the one datum that says
     * WHICH value defeated this school's residency line.
     *
     * Rendered as [CollegeControl.Unrecognized]'s label ("unknown (control
     * [9])"), the same form the per-college `control` key carries: the code
     * stays readable, and no bare source code goes on the wire (RFC 143).
     */
    const val SOURCE_CONTROL_KEY = "source_control"

    /**
     * The sentence the coach may say for one basis fact — the paired-label
     * convention (`income_band` + `income_band_label`) applied to the comparison
     * contract: whenever a code goes on the wire, the words go with it, from the
     * same construct.
     *
     * ONE exception, and it is not a gap: a `figure_statuses` entry for a SHOWN
     * `reported` figure omits this key entirely (RFC 179 D6). Such a figure says
     * nothing new about its status, and inventing prose to keep the pairing
     * would be new copy about a status that has nothing to say. The entry still
     * pairs its OTHER code — `assurance` always ships with `assurance_statement`.
     */
    const val STATEMENT_KEY = "statement"

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
     * The residency invitation at a school that publishes THREE tuition prices
     * (RFC 166 §4) -- used exactly when this college carries a value-bearing
     * in-district figure, which is the same condition the in-district key is
     * emitted on.
     *
     * [RESIDENCY_OFFER] names two prices, which is simply wrong at a community
     * college. This one names three and is careful about what the answer buys:
     * the state answer selects between the two STATE-level prices only, because
     * a state does not answer a district. NO district question is asked -- there
     * is no money-profile field for one and this RFC adds none -- so the
     * district price stays a labelled tier the coach may quote, never a price
     * this student is sorted into.
     */
    const val RESIDENCY_OFFER_THREE_TIERS =
      "This is a public school, and it publishes three tuition and fees prices: one for students living in " +
        "its district, one for other students living in its state, and one for students from outside the " +
        "state. If the student shares the state they live in (record it with " +
        "${MoneyProfileChatTool.TOOL_NAME}), you can say which of the two state-level prices applies to them " +
        "- the in-state one or the out-of-state one - and say plainly when this school does not report the " +
        "one that applies. A district is never asked about: a state answer does not answer a district, so " +
        "quote the in-district price as the labelled tier it is and never as this student's price."

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

    /**
     * The living-plan invitation (RFC 152 D4): present exactly when the plan is
     * unanswered AND this school is priced for at least two ways of living, so
     * the question always has something to choose between -- and absent after a
     * decline, so the coach is never cued to reopen a closed topic
     * ([CollegeCostProfile.precisionOffersFor]).
     *
     * It promises no number, only a narrowing: three price pictures become one.
     */
    const val LIVING_PLAN_OFFER =
      "This school is priced for more than one way of living, and the student's USUAL plan is not on file. " +
        "If they say where they plan to live when they have the choice (record it with " +
        "${MoneyProfileChatTool.TOOL_NAME}), every school they have not decided separately about can lead " +
        "with that one price picture instead of three."

    /** The major answer's own section (RFC 231): present only when the intended-subjects answer is `answered`. */
    const val PROGRAM_RETURN_KEY = "program_return"

    /** The stored subject's slug and resolved name, at the head of one `program_return` entry. */
    const val PROGRAM_SUBJECT_SLUG_KEY = "subject_slug"
    const val PROGRAM_SUBJECT_NAME_KEY = "subject_name"

    /** One entry per selected college, inside one subject entry -- the section's own array. */
    const val PROGRAM_COLLEGES_KEY = "colleges"

    /** One entry per MATCHED program inside one college entry: the publisher's own grain. */
    const val PROGRAM_PROGRAMS_KEY = "programs"

    /** The matched program's own CIP code, which is its identity inside one college. */
    const val PROGRAM_CIP_CODE_KEY = "cip_code"

    /** The matched program's published title, verbatim (RFC 251), so the coach names it rather than reading a code. */
    const val PROGRAM_TITLE_KEY = "program_title"

    /** The per-program figures: one entry per `ProgramMoneyMeasure`, always. */
    const val PROGRAM_FIGURES_KEY = "figures"

    /** One figure's measure, one of the `ProgramMoneyMeasure` values. */
    const val PROGRAM_MEASURE_KEY = "measure"

    /**
     * Whole US dollars; present on exactly the two value-bearing statuses
     * (`reported` and `imputed_by_publisher`), and on no other.
     *
     * The PARTITION rather than one member of it: the `when` in
     * [putHeldProgramFigure] decides this over
     * [ProgramReturnFigure.Valued], so a doc naming one status drifts behind
     * the code the moment the other one is stored.
     */
    const val PROGRAM_AMOUNT_USD_KEY = "amount_usd"

    /** The figure's silence arm or `reported`; the code beside the amount. */
    const val PROGRAM_STATUS_KEY = "status"

    /** Who published the figure, from that variable's `ScorecardFieldOfStudyColumns` entry. */
    const val PROGRAM_PUBLISHER_KEY = "publisher"

    /** The first and last academic year the publisher's cohort pools, each as its start year. */
    const val PROGRAM_COHORT_FIRST_YEAR_KEY = "cohort_first_year"
    const val PROGRAM_COHORT_LAST_YEAR_KEY = "cohort_last_year"

    /** The publisher's own cohort cell, verbatim -- the evidence for the years and the dollar base. */
    const val PROGRAM_COHORT_SENTENCE_KEY = "cohort_sentence"

    /** The calendar year whose dollars the figure is in, where its registry entry names one. */
    const val PROGRAM_INFLATION_BASE_YEAR_KEY = "inflation_base_year"

    /**
     * THE SCHOOL's: no bachelor's program matched the subject's prefixes here, so
     * the college entry carries no `programs` array at all.
     *
     * The FIRST of three absences this section keeps apart, and the only one that
     * is a statement about a school's offering. The other two are gaps in what we
     * HOLD about a program the school does run, and collapsing any two of them
     * would turn one into the other in front of a family.
     */
    const val PROGRAM_AVAILABILITY_KEY = "program_availability"
    const val PROGRAM_NO_PROGRAM_BASIS = "no_program"

    /**
     * OURS, at the PROGRAM: the school runs the matched program and we hold no
     * published figure for any of its measures.
     *
     * Its own key and its own basis code, one level in from
     * [PROGRAM_AVAILABILITY_KEY]: a school that runs a major we have no figures
     * for must never be described as a school that does not run it.
     */
    const val PROGRAM_FIGURES_AVAILABILITY_KEY = "program_figures_availability"
    const val PROGRAM_NO_PUBLISHED_FIGURES_BASIS = "no_published_figures"

    /**
     * OURS, at ONE MEASURE: we hold no row for this measure of a program whose
     * other measures we do hold.
     *
     * The THIRD absence, and the reason `figures` is never short: an entry that
     * simply vanished would be an absence with no reason, indistinguishable from
     * a measure the publisher suppressed.
     */
    const val PROGRAM_MEASURE_AVAILABILITY_KEY = "measure_availability"
    const val PROGRAM_NO_FIGURE_BASIS = "no_figure_held"

    // The legend is the tool's whole job (RFC 192): one entry per wire key, what
    // it means, and what values it takes, in payload order. Every behavioural
    // rule -- what to SAY to the family -- lives in the coach system prompt, and
    // an entry here says only how to READ a key.
    // Not `const`: the example band range is rendered from IncomeBand.bracket,
    // the one home for that copy (RFC 142), so the description can never quote
    // a range the results themselves no longer carry.
    val DESCRIPTION by lazy {
      "Real cost facts for the colleges on the student's list: sticker cost, tuition and fees, the net " +
        "price their family would actually pay, median debt, median earnings. A legend of the payload's " +
        "keys follows, in the order they appear.\n" +
        PAYLOAD_LEGEND
    }

    /**
     * The legend of [payloadOf]'s keys, in the order they appear — the part of
     * [DESCRIPTION] that describes the PAYLOAD rather than this tool. Split out
     * (RFC 258) because `family_cost_picture` returns the same payload to a
     * supporter's coach, which never sees this tool's description, so it must
     * carry the same legend; one copy, so the two cannot describe one key two
     * ways. [DESCRIPTION] is byte-identical to what it was before the split.
     */
    val PAYLOAD_LEGEND =
      "$SOURCE_KEY - the publishers this payload's own figures came from.\n" +
        "$DATA_AVAILABILITY_KEY - fields with no number in this result (the college does not report it, or " +
        "it is a figure that does not describe this family and was withheld).\n" +
        "$WITHHELD_FIGURES_KEY - the entry naming and explaining a withheld figure.\n" +
        "$FIGURE_PROVENANCE_KEY - the college-wide DEFAULT kind and publisher of its figures: " +
        "$ASSURANCE_KEY, $ASSURANCE_STATEMENT_KEY, $FIGURE_SOURCE_KEY.\n" +
        "$ASSURANCE_KEY - kind of number: \"${AssuranceTier.ADMINISTRATIVE_RECORD.value}\" (a federal file " +
        "about students) | \"${AssuranceTier.MANDATORY_SURVEY.value}\" (a compelled, edit-checked filing) | " +
        "\"${AssuranceTier.VOLUNTARY_SELF_REPORT.value}\" (the college's own unchecked publication).\n" +
        "$ASSURANCE_STATEMENT_KEY - the sentence for that tier; it always travels with $ASSURANCE_KEY.\n" +
        "$FIGURE_STATUSES_KEY - per-field entry, present only where a field differs from the default or " +
        "has no number: $STATUS_KEY, $STATEMENT_KEY, $HELD_ACADEMIC_YEAR_KEY, $ASSURANCE_KEY or " +
        "$FIGURE_SOURCE_KEY overrides. A figure's $ASSURANCE_KEY is its own entry's when the entry has one " +
        "and $FIGURE_PROVENANCE_KEY's otherwise; $FIGURE_SOURCE_KEY resolves the same way.\n" +
        "$STATEMENT_KEY - the sentence to say for that entry.\n" +
        // The vocabulary, code AND gloss, read off FigureStatusCopy: this
        // legend and the major answer's below list the same six statuses, and
        // they described them in two different wordings while each typed its
        // own.
        "$STATUS_KEY - " + FigureStatusCopy.LEGEND_VOCABULARY + ".\n" +
        "$HELD_ACADEMIC_YEAR_KEY - on a \"${FigureStatus.NOT_COLLECTED_BY_US.value}\" entry: the year we " +
        "DO hold that figure for.\n" +
        "$ASSUMED_BY_UNICOACH_KEY - keys whose amount is OURS: on no publisher's tier, and no " +
        "$FIGURE_SOURCE_KEY.\n" +
        "$APPLIES_BASIS_KEY - whether the published price and the price after a financial aid offer are " +
        "this family's: \"${BlendedFigureApplicability.APPLIES.value}\" (they are) | " +
        "\"${BlendedFigureApplicability.WITHHELD.value}\" (they are not, and are held back) | " +
        "\"${BlendedFigureApplicability.BASIS_STATED.value}\" (residency or school control unknown; both " +
        "shown on the in-state basis).\n" +
        "$APPLIES_KEY - true/false, only when known.\n" +
        "${CostField.NET_PRICE.wireName} - amount_usd (whole USD per academic year), $BASIS_KEY " +
        "(\"your_income_band\" | \"overall_average\"), income_band_label (the band's dollar range in plain " +
        "words, e.g. \"${IncomeBand.OVER_110K.bracket}\"), $WITHHELD_REASON_KEY (a figure WE withheld; it keeps " +
        "its basis and label).\n" +
        "$PRECISION_OFFER_KEY - ordered upgrade invitations, each naming a money-profile field " +
        "(${MoneyProfileChatTool.TOOL_NAME} records them) and the sentence to say: " +
        "${PrecisionOffer.RESIDENCY.field}, ${PrecisionOffer.INCOME_BAND.field}, " +
        "${PrecisionOffer.LIVING_PLAN.field}.\n" +
        "money_profile.residency_status / income_band_status / living_plan_status - \"declined\" | " +
        "\"answered\": the authority on whether to raise residency, income and the living plan.\n" +
        "${MeritAidWire.KEY} - from that school's Common Data Set: ${MeritAidWire.SHARE_KEY} (of ALL " +
        "full-time freshmen), ${MeritAidWire.AVERAGE_KEY} (last year's recipients). Absent = not " +
        "reported.\n" +
        "${CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName} / " +
        "${CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD.wireName} / " +
        "${CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName} - published tuition and fees, one " +
        "key per residency tier.\n" +
        "${CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName} / " +
        "${CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD.wireName} / " +
        "${CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD.wireName} - the fees PART of the matching combined " +
        "figure, not a cost beside it.\n" +
        "$RESIDENCY_TIERS_KEY - $BASIS_KEY + $STATEMENT_KEY: " +
        "\"${ResidencyTierBasis.THREE_TIERS_PUBLISHED.value}\" | " +
        "\"${ResidencyTierBasis.TWO_TIERS_PUBLISHED.value}\" | " +
        "\"${ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.value}\" (the in-state figure is " +
        "shown exactly as published) | \"${ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER.value}\" | " +
        "\"${ResidencyTierBasis.SINGLE_PUBLISHED_PRICE.value}\" (the one tuition key beside it says which " +
        "residency that price is for) | \"${ResidencyTierBasis.NO_PUBLISHED_TUITION.value}\" (no tuition " +
        "key at all).\n" +
        "${AidPolicyWire.KEY} - from the same Common Data Set: ${AidPolicyWire.NEED_MET_SHARE_KEY} and " +
        "${AidPolicyWire.FULLY_MET_SHARE_KEY} (both about the freshmen given need-based aid), " +
        "${AidPolicyWire.FORMS_KEY}, ${AidPolicyWire.FORMS_NOTE_KEY}, " +
        "${AidPolicyWire.FORMS_NOT_COLLECTED_KEY} (the filing answered; WE could not read it).\n" +
        "$AID_POLICY_AVAILABILITY_KEY - why the ${AidPolicyWire.KEY} section is absent.\n" +
        "${BorrowingWire.KEY} - from the same filing: what the students who GRADUATED in the year " +
        "${BorrowingWire.COHORT_LABEL_KEY} names borrowed, each loan type its own figure.\n" +
        "$BORROWING_AVAILABILITY_KEY - $BASIS_KEY + $STATEMENT_KEY: why that section is absent.\n" +
        "${CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName} - federal-only median, a different cohort, no " +
        "academic year.\n" +
        "${CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName} - cohort figure, no academic year.\n" +
        "$BREAKDOWN_KEY - the published price split by where the student would live: " +
        "\"${LivingArrangement.ON_CAMPUS.value}\", \"${LivingArrangement.OFF_CAMPUS.value}\", " +
        "\"${LivingArrangement.WITH_FAMILY.value}\". Each carries the school's allowances, and a $TOTAL_KEY " +
        "only when every part is reported.\n" +
        "$TUITION_FIELD_IN_TOTALS_KEY - NAMES the top-level tuition key whose amount every $TOTAL_KEY here " +
        "already includes; a total is that amount plus the allowances beside it.\n" +
        "${CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName} - 0 under " +
        "${LivingArrangement.WITH_FAMILY.value}, and that zero is ours (it is named under " +
        "$ASSUMED_BY_UNICOACH_KEY); no publisher collects the figure.\n" +
        "${CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName} - an older average blended across " +
        "all three arrangements; at a public school, built on in-state tuition. " +
        "${CostField.NET_PRICE.wireName} is on that same in-state basis, and both are absent for an " +
        "out-of-state family at a public school.\n" +
        // The three lines below name IPEDS in a LIVE string: the housing flag is
        // a survey answer, not a money figure. Their `money-attribution-exempt`
        // markers are VESTIGIAL as of RFC 231 -- the source-text sweep that read
        // them is deleted, and D7's surviving half is the compile-time one
        // (`MoneySourceCopy`'s `when`s with no `else`), which no comment can arm
        // or disarm. They are left in place as a recorded follow-up, so nothing
        // here depends on where these literals are split.
        "$OFFERS_ON_CAMPUS_HOUSING_KEY - \"$OFFERS_ON_CAMPUS_HOUSING_KEY\": true " +
        "(IPEDS: has halls) | false " + // money-attribution-exempt: IPEDS housing flag, not a money figure
        "(IPEDS: no residence halls) | absent " + // money-attribution-exempt: the same flag
        "(IPEDS says neither way). A school flagged " + // money-attribution-exempt: the same flag, absent
        "false that still carries an ${LivingArrangement.ON_CAMPUS.value} arrangement published those " +
        "figures itself.\n" +
        "$CHOSEN_ARRANGEMENT_KEY - the one way of living the family plans at this school: " +
        "$ARRANGEMENT_LABEL_KEY, $ARRANGEMENT_SOURCE_KEY (\"${LivingPlanSource.PER_COLLEGE.value}\" | " +
        "\"${LivingPlanSource.PROFILE_DEFAULT.value}\", their usual plan assumed here), $PRICING_KEY, " +
        "$STATEMENT_KEY. The other ways of living stay in $BREAKDOWN_KEY and stay true; an absent key " +
        "means the family has said nothing.\n" +
        "$PRICING_KEY - \"${LivingPlanPricing.PRICED.value}\" (carries $TOTAL_KEY) | " +
        "\"${LivingPlanPricing.NOT_PRICED_HERE.value}\" (carries $ARRANGEMENT_REASON_KEY, no price) | " +
        "\"${LivingPlanPricing.NO_TOTAL_HERE.value}\" (carries $NO_TOTAL_REASON_KEY).\n" +
        "$NO_TOTAL_REASON_KEY - \"${NoTotalReason.AWAITING_RESIDENCY_ANSWER.value}\" | " +
        "\"${NoTotalReason.TUITION_APPLICABILITY_UNKNOWN.value}\" | " +
        "\"${NoTotalReason.PART_NOT_COLLECTED_BY_US.value}\" (OUR gaps; we may hold the part only for " +
        "another academic year) | \"${NoTotalReason.PART_NOT_PUBLISHED.value}\" (the school's gap).\n" +
        "${FigureGroup.PUBLISHED_PRICE.wireName} / ${FigureGroup.BLENDED_AVERAGE.wireName} - each carries " +
        "an $ACADEMIC_YEAR_KEY and the $DATED_FIGURES_KEY it dates; the year is that SCHOOL's. A figure " +
        "named by neither has no academic year.\n" +
        "$COMPARISON_BASIS_KEY - present with two or more colleges: who the figures describe, the " +
        "residency, $BLENDED_FIGURE_BASIS_KEY (with $APPLIES_KEY), the way of living, the academic years, " +
        "and what aid means in a net price. Each carries $BASIS_KEY + $STATEMENT_KEY, and a dated fact " +
        "names its $DATED_COLLEGES_KEY by $COLLEGE_ID_KEY and $COLLEGE_NAME_KEY.\n" +
        "$BY_COLLEGE_KEY - present inside a fact only where the schools differ or one has its own thing " +
        "to say; where it is absent the $STATEMENT_KEY above it is true of every school in the call.\n" +
        "$PROGRAM_RETURN_KEY - present only when the intended-subjects answer is \"answered\": the selected " +
        "colleges re-keyed by subject. Each entry carries $PROGRAM_SUBJECT_SLUG_KEY, $PROGRAM_SUBJECT_NAME_KEY " +
        "and a \"$PROGRAM_COLLEGES_KEY\" array of one entry per selected college ($COLLEGE_ID_KEY, " +
        "$COLLEGE_NAME_KEY, and either " +
        "$PROGRAM_PROGRAMS_KEY or $PROGRAM_AVAILABILITY_KEY).\n" +
        "$PROGRAM_PROGRAMS_KEY - one entry per matched bachelor's program at that college, in $PROGRAM_CIP_CODE_KEY " +
        "order; a school can run two programs one subject reaches, and each carries its own figures.\n" +
        "$PROGRAM_AVAILABILITY_KEY - $BASIS_KEY + $STATEMENT_KEY on a COLLEGE entry, instead of " +
        "$PROGRAM_PROGRAMS_KEY (\"$PROGRAM_NO_PROGRAM_BASIS\": \"${ProgramReturnCopy.NO_PROGRAM_STATEMENT}\").\n" +
        "$PROGRAM_CIP_CODE_KEY - the program's own CIP code, which is which program the entry is about.\n" +
        "$PROGRAM_TITLE_KEY - the program's published title, verbatim; name the program by it, never by its code.\n" +
        "$PROGRAM_FIGURES_KEY - one entry per measure, ALWAYS every measure and in this order: " +
        ProgramMoneyMeasure.entries.joinToString(", ") { "\"${it.value}\"" } + ". Each carries " +
        "$PROGRAM_MEASURE_KEY and then either $PROGRAM_STATUS_KEY with its provenance " +
        "($PROGRAM_PUBLISHER_KEY, $PROGRAM_COHORT_FIRST_YEAR_KEY, $PROGRAM_COHORT_LAST_YEAR_KEY, " +
        "$PROGRAM_COHORT_SENTENCE_KEY, and $PROGRAM_INFLATION_BASE_YEAR_KEY on the measures named for it below) or " +
        "$PROGRAM_MEASURE_AVAILABILITY_KEY.\n" +
        "$PROGRAM_FIGURES_AVAILABILITY_KEY - $BASIS_KEY + $STATEMENT_KEY on a PROGRAM entry, instead of " +
        "$PROGRAM_FIGURES_KEY (\"$PROGRAM_NO_PUBLISHED_FIGURES_BASIS\": " +
        "\"${ProgramReturnCopy.NO_PUBLISHED_FIGURES_STATEMENT}\").\n" +
        // The value-bearing HALF of the partition, from the property FigureStatus
        // owns: naming its two members here is a rule that goes quietly wrong
        // the moment a seventh status carries a number.
        "$PROGRAM_AMOUNT_USD_KEY - whole US dollars; present only when $PROGRAM_STATUS_KEY is " +
        FigureStatus.entries.filter { it.valueBearing }.joinToString(" or ") { "\"${it.value}\"" } +
        ".\n" +
        "$PROGRAM_STATUS_KEY - " + FigureStatusCopy.LEGEND_VOCABULARY + ".\n" +
        "$PROGRAM_PUBLISHER_KEY - " +
        ScorecardFieldOfStudyColumns.VARIABLES.joinToString(" | ") { "\"${it.publisher}\" (${it.measure.value})" } +
        ".\n" +
        "$PROGRAM_COHORT_FIRST_YEAR_KEY - the first academic year the publisher's cohort pools, as its start " +
        "year; this ROW's own cohort, which the store may hold at more than one date.\n" +
        "$PROGRAM_COHORT_LAST_YEAR_KEY - the last academic year the publisher's cohort pools, as its start " +
        "year.\n" +
        "$PROGRAM_COHORT_SENTENCE_KEY - the publisher's own cohort cell, verbatim, for the figure beside it; it " +
        "describes the two years above.\n" +
        "$PROGRAM_INFLATION_BASE_YEAR_KEY - the calendar year whose dollars the figure is in; carried only on " +
        ScorecardFieldOfStudyColumns.VARIABLES
          .filter { it.inflationBaseYear != null }
          .joinToString(", ") { "\"${it.measure.value}\"" } +
        ", because the publisher names a dollar year for no other measure.\n" +
        "$PROGRAM_MEASURE_AVAILABILITY_KEY - $BASIS_KEY + $STATEMENT_KEY on a FIGURE entry, instead of " +
        "$PROGRAM_STATUS_KEY (\"$PROGRAM_NO_FIGURE_BASIS\": \"${ProgramReturnCopy.NO_FIGURE_STATEMENT}\").\n" +
        "Read-only: this tool changes nothing."
  }
}
