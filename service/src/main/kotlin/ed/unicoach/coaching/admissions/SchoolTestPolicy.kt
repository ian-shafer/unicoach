package ed.unicoach.coaching.admissions

import ed.unicoach.college.AdmissionTestPolicy
import ed.unicoach.db.models.CollegeTestPolicyRow
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * What ONE school told the federal admissions survey about admission test
 * scores (RFC 187): the fourth section of `college_admissions_profile`, and the
 * one fact that decides whether any of the testing conversation applies to this
 * student at all.
 *
 * The data has been stored since RFC 144 -- `college_ipeds.test_policy`, IPEDS
 * `ADMCON7` -- and has been a search FILTER since RFC 150. What did not exist
 * is the per-school READ: nothing in the coach's context could say what one
 * named school on the student's list does. This type is that answer, and every
 * one of its three properties exists to make a dishonest version of it
 * unrepresentable.
 *
 * - [policy] is the loaded codebook member, never the raw `1`/`3`/`5`. A code
 *   outside the three published values cannot reach this type: the service maps
 *   through [AdmissionTestPolicy.fromCode] and treats a null mapping exactly
 *   like a missing row -- the section is absent and the school is named in
 *   `data_availability`. The column's own CHECK already forbids a fourth code;
 *   this is what happens if it is ever relaxed.
 * - [statement] is COMPUTED from [policy] by [TestPolicyCopy], never stored and
 *   never passed in, so the sentence the coach reads aloud and the code it came
 *   from cannot drift apart (the [CdsCitation.citedAs] precedent).
 * - [source] carries the survey YEAR, so the year in the sentence is the year
 *   of the row that produced the sentence. A test policy is exactly the kind of
 *   fact a school changes between cycles, so a claim without its year is a
 *   claim about a year nobody named.
 *
 * A test-policy fact carries NO `FigureStatus` and no RFC 179 assurance tier.
 * That vocabulary belongs to the canonical money tables, whose rows record a
 * publisher's own suppression and imputation flags; IPEDS publishes none of
 * that for `ADMCON7`, and borrowing the words would claim a provenance model
 * this datum does not have.
 */
data class SchoolTestPolicy(
  val policy: AdmissionTestPolicy,
  val source: IpedsAdmissionsCitation,
) {
  /** The sentence a family hears for this policy -- owned by [TestPolicyCopy], never retyped at a call site. */
  val statement: String get() = TestPolicyCopy.statementOf(policy)

  companion object {
    /**
     * One stored row as a test-policy section, or null when its raw `ADMCON7`
     * code maps to no published [AdmissionTestPolicy] member -- the caller then
     * names [AdmissionsField.TEST_POLICY] in `data_availability` instead of
     * rendering a policy nobody published.
     *
     * The decode belongs HERE, on the type, for the same reason
     * [MeritPractice.from] owns its own emptiness rule: the class KDoc above
     * promises that a code outside the three published values cannot reach this
     * type, and a promise a type makes about its own contents has to be kept by
     * the type. Left in the service it would be one orchestrator's habit, and
     * the second caller would decode the raw code its own way. The caller's job
     * is then only to LOG the suppression, exactly as it does for merit and
     * factors.
     */
    fun from(row: CollegeTestPolicyRow): SchoolTestPolicy? =
      AdmissionTestPolicy.fromCode(row.code)?.let { policy ->
        SchoolTestPolicy(policy = policy, source = IpedsAdmissionsCitation(row.surveyYear))
      }
  }
}

/**
 * The words a family hears for each published `ADMCON7` code, and the two
 * standing sentences no policy claim may be made without (RFC 187).
 *
 * The statements are OURS, deliberately, and not the survey's own `label_raw`
 * ("Not considered for admission, even if submitted (Test Blind)"). A
 * parenthesised source label with a survey's jargon in it is precisely what the
 * tool boundary exists to translate: CLAUDE.md's schema convention is that
 * source-defined codes are stored raw and human-readable labels appear only at
 * the boundary, and this object IS that boundary. The SLUG -- `required`,
 * `test-optional`, `test-blind` -- still rides the wire beside the sentence,
 * because it is a plain English word rather than a source code, and it is the
 * same word the search filter already accepts: a student who filtered on it and
 * then asked about one school reads the same word twice.
 *
 * Every statement is in the PAST tense. A survey collected in one year is a
 * statement about that year and not a promise about this application cycle, and
 * the tense is the cheapest place to say so -- it is carried by the sentence
 * itself, so it survives being quoted without [CONFIRM_WITH_SCHOOL] beside it.
 */
object TestPolicyCopy {
  /**
   * The sentence for one published policy. Exhaustive `when` with NO `else`:
   * adding a fourth member to [AdmissionTestPolicy] must fail to compile here,
   * because the failure mode of an `else` is a real school getting some other
   * school's sentence.
   */
  fun statementOf(policy: AdmissionTestPolicy): String =
    when (policy) {
      AdmissionTestPolicy.REQUIRED -> {
        "this school required an admission test score to be considered for admission"
      }

      AdmissionTestPolicy.TEST_OPTIONAL -> {
        "this school did not require an admission test score, but considered one if it was submitted"
      }

      AdmissionTestPolicy.TEST_BLIND -> {
        "this school did not consider admission test scores at all, even if they were submitted"
      }
    }

  /**
   * The CLAUSES the two standing sentences below are assembled from, and the
   * ONE home of each claim.
   *
   * Declared rather than left inside the finished sentences because the coach
   * prompt states the same claims in its own words, and
   * `SystemPromptCatalogTest` has to hold the two surfaces together. Its probes
   * used to be CUT OUT of the finished sentences -- split on `", so "`, and cut
   * from between two hand-typed markers -- which made punctuation a contract
   * between a copy constant and a test, and `substringBefore` is a silent no-op
   * when its delimiter moves. The clauses are properties now, so the test reads
   * them instead of parsing them, and rewording any one of them fails there.
   *
   * They are declared BEFORE the sentences that interpolate them: a `const val`
   * is resolved in declaration order, so the assembled sentence cannot compile
   * above its parts.
   */
  const val POLICY_CAN_CHANGE: String = "A school can change its test policy from year to year"

  /** Where a family goes to check it -- the second half of [CONFIRM_WITH_SCHOOL]. */
  const val CONFIRM_ON_THE_SCHOOLS_PAGE: String =
    "the school's own admissions page is where to confirm it before acting on it"

  /**
   * Whose silence an absent policy is -- the claim the wire and the prompt must
   * never drift apart on: our gap, never the school's statement.
   */
  const val POLICY_GAP_IS_OURS: String = "a gap in our data"

  /**
   * The sentence that rides with EVERY rendered policy (D17). It is named by
   * the section rather than by the tool description on purpose: a rule stated
   * once in a tool's prose is a rule the model may or may not have in mind when
   * it reaches the fact, whereas this string arrives in the model's context
   * attached to the very claim it qualifies.
   */
  const val CONFIRM_WITH_SCHOOL: String = "$POLICY_CAN_CHANGE, so $CONFIRM_ON_THE_SCHOOLS_PAGE."

  /**
   * The sentence for an absent policy, carried on the tool DESCRIPTION.
   *
   * It does NOT ride the coach prompt: the prompt states the same rule in its
   * own words, because it is addressing the coach rather than qualifying one
   * result. The two are held together from the prompt side --
   * `SystemPromptCatalogTest` asserts the prompt block carries
   * [POLICY_GAP_IS_OURS], so rewording that clause fails there rather than
   * letting the wire and the prompt drift into saying different things about
   * the same silence.
   *
   * An absent policy says something about US and never about the school: IPEDS
   * has no admissions row for most of the corpus, so "we do not have this
   * school's policy" is the truth and "this school does not require a test" is
   * a fabrication -- the one this section exists to make impossible.
   */
  const val NOT_REPORTED: String =
    "we do not have this school's test policy, which is $POLICY_GAP_IS_OURS and not a statement that the " +
      "school requires no test score; the school's own admissions page is where to find it."
}

/**
 * The citation a test-policy section carries: one federal survey, named with
 * the year it collected the answer in (RFC 187 D17).
 *
 * [CdsCitation] cannot be reused and is not a near miss. Every CDS fact is a
 * different school's own document, at its own URL, in its own cycle; this is
 * ONE survey run by one publisher, with no per-school document behind it.
 *
 * [surveyYear] comes from the row (`college_ipeds.survey_year`) and never from
 * a literal `2023`: when the ingest is re-run against a later ADM file the
 * sentence moves with the data instead of lying in a constant.
 *
 * There is deliberately NO url key. IPEDS publishes no per-school page for
 * `ADMCON7`, and a survey landing page invented here would be a citation in
 * which nobody can check this school's own answer. The year lives INSIDE the
 * sentence, so no bare numeric year key reaches the wire and the RFC 143
 * numeric guard keeps its meaning -- a lone `2023` under a `collection_year`
 * key is exactly a number with no measure.
 *
 * The wording echoes `MoneySourceCopy.labelOf` ("the U.S. Department of
 * Education's IPEDS survey of college costs") so a family hears one publisher
 * named one way -- but it is a separate type in the admissions package, because
 * importing the money seam's copy object is how a testing figure ends up inside
 * the money vocabulary.
 */
data class IpedsAdmissionsCitation(
  val surveyYear: Int,
) {
  /** The sentence fragment the coach says, with the collection year in it rather than beside it. */
  val citedAs: String
    // The publisher is named in a LIVE string, and the RFC 177 ban is on
    // naming a publisher of a canonical MONEY row. A test policy is not a
    // money figure and this survey publishes none, so the line carries its own
    // exemption rather than deriving words from MoneySourceCopy -- reaching
    // into the money seam's copy object is exactly how a testing fact would
    // end up inside the money vocabulary.
    get() =
      "the U.S. Department of Education's IPEDS admissions survey, " + // money-attribution-exempt: a test policy is not a money figure
        "collected in $surveyYear"
}

/**
 * The test-policy section's own source object on the wire, beside its type for
 * the same reason [putCitation] sits beside [CdsCitation]: one file owns a
 * citation and the words it puts on the wire, so no section has to reach into
 * another section's file to cite itself.
 *
 * An OVERLOAD rather than a name that repeats its parameter's type: both
 * functions put a source object on the wire, the argument says which citation,
 * and a call site reads `putCitation(section.source)` either way. The BODIES
 * cannot be shared -- [CdsCitation]'s renderer requires a `url` and this survey
 * publishes no per-school page -- which is a different statement from the two
 * operations having different names.
 *
 * The year is IN the sentence, so this object is one key and emits no bare
 * numeric year -- which is what keeps
 * `CollegeAdmissionsChatTool.NUMBERS_BY_CONTRACT` unchanged and the RFC 143
 * guard meaningful.
 */
fun JsonObjectBuilder.putCitation(citation: IpedsAdmissionsCitation) {
  put("cited_as", citation.citedAs)
}
