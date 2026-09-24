package ed.unicoach.coaching.aid

import java.time.LocalDate

/**
 * THE ONE HOME FOR THE FAFSA PROCESS RULES THE COACH MAY SPEAK (RFC 216), and
 * for the document each one was read from.
 *
 * The house pattern at this edge is `AdmissionTestCopy` + `checked_on`
 * (RFC 197): the publisher's own words, the publisher's own https page, and the
 * day WE read it -- which is a different date from the document's own edition
 * year. `public-web`'s typed `ExplainerSource` is the stricter cousin and is not
 * reachable from `:service`, so its shape is copied here rather than imported.
 *
 * Every rule below was transcribed from the harvested inventory of
 * `product/0012-fafsa-support/research/`, each with a publisher, a document, an
 * https URL and the fetch date 2026-09-14. Claims the research left without a
 * URL are NOT here at all, and neither is anything dated: the 2024 rollout
 * audit, the corrections deadlines and the in-progress redesign of the no-SSN
 * path are measurements of one moment, not standing rules, so where the current
 * process is genuinely uncertain the copy names the college's financial aid
 * office and what to ask it.
 */
object FafsaProcessCopy {
  /** The day WE read every document cited below -- not the edition year of the document itself. */
  val CHECKED_ON: LocalDate = LocalDate.parse("2026-09-14")

  /**
   * The six questions families actually ask, in the order the payload emits
   * them. The value is the wire code; nothing else names these strings.
   */
  enum class FafsaProcessTopic(
    val value: String,
  ) {
    WHO_FILES("who_files"),
    DIVORCED_OR_SEPARATED_PARENTS("divorced_or_separated_parents"),
    CONTRIBUTOR_WITHOUT_AN_SSN("contributor_without_an_ssn"),
    DEPENDENCY("dependency"),
    AFTER_YOU_SUBMIT("after_you_submit"),
    THE_FORM_IS_FREE("the_form_is_free"),
  }

  /**
   * One rule the coach may state, with the document it came from.
   *
   * The `init` block is the guard, not a style check: a load-bearing sentence
   * with no publisher and no https page does not construct, so it cannot ship.
   */
  data class FafsaRule(
    val topic: FafsaProcessTopic,
    val statement: String,
    val citedAs: String,
    val url: String,
    val checkedOn: LocalDate = CHECKED_ON,
  ) {
    init {
      require(url.startsWith("https://")) { "a FAFSA rule must cite an https page, got [$url]" }
      require(statement.isNotBlank()) { "a FAFSA rule must carry a statement" }
      require(citedAs.isNotBlank()) { "a FAFSA rule must name the document it came from: [$statement]" }
    }
  }

  // ---------------------------------------------------------------------------
  // The documents. Named once, cited many times.
  // ---------------------------------------------------------------------------

  private const val DCL_CITED_AS =
    "Federal Student Aid, Dear Colleague Letter GEN-23-11, FAFSA Simplification Act Changes for " +
      "Implementation in the 2024-25 Award Year"
  private const val DCL_URL =
    "https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2023-08-04/" +
      "fafsa-simplification-act-changes-implementation-2024-25"

  private const val AVG_CH1_CITED_AS =
    "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 1"
  private const val AVG_CH1_URL =
    "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
      "ch1-application-process-fafsa-isir"

  private const val AVG_CH2_CITED_AS =
    "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 2"
  private const val AVG_CH2_URL =
    "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
      "ch2-filling-out-fafsa-form"

  private const val AVG_CH2_PRIOR_CITED_AS =
    "Federal Student Aid, 2024-25 FSA Handbook, Application and Verification Guide, Ch. 2"
  private const val AVG_CH2_PRIOR_URL =
    "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide/" +
      "ch2-filling-out-fafsa"

  private const val AVG_CH4_CITED_AS =
    "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 4"
  private const val AVG_CH4_URL =
    "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
      "ch4-verification-updates-and-corrections"

  private const val AVG_CH5_CITED_AS =
    "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 5"
  private const val AVG_CH5_URL =
    "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
      "ch5-special-cases"

  /** The free-form rule's own subsection; the preparer conditions are (d)(2), and the duty to disclose is (d)(2)(C). */
  private const val FREE_FORM_CITED_AS =
    "Cornell Law School Legal Information Institute, 20 U.S.C. § 1090(d)(3)"
  private const val PREPARER_CITED_AS =
    "Cornell Law School Legal Information Institute, 20 U.S.C. § 1090(d)(2), free preparation " +
      "authorized; the duty to tell a family the form is free is § 1090(d)(2)(C)"
  private const val STATUTE_URL = "https://www.law.cornell.edu/uscode/text/20/1090"

  /**
   * The whole rulebook, grouped in emission order.
   *
   * Thirty rules, every one classified STABLE STATUTORY by the research, every
   * one with its own document. There is no expiry and no dated build failure
   * here on purpose: a timebomb would fire on facts that did not move.
   */
  val RULES: List<FafsaRule> =
    listOf(
      // --- who_files -------------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "A contributor is anyone -- usually a parent or a spouse -- who has to put their own information on " +
          "the student's FAFSA, and who that is depends on the student's dependency and marital status.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The student and every contributor each need their own StudentAid.gov account; one login cannot be " +
          "shared between them.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Those accounts can be created before the form opens.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Each contributor must consent to the IRS sending their federal tax information to the FAFSA, and " +
          "that consent is a condition of receiving federal student aid.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Only the contributor can give that consent: nobody at a college can give it on a family's behalf.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "If even one required contributor refuses consent, the FAFSA is rejected, no Student Aid Index is " +
          "calculated, and the student cannot receive federal student aid -- the Pell Grant included -- until " +
          "that consent is given.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The federal tax information pulled from the IRS is not shown on the family's own FAFSA Submission " +
          "Summary.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The student themselves must have a Social Security number, except for citizens of the Freely " +
          "Associated States.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      // --- divorced_or_separated_parents -----------------------------------
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "When parents are divorced, separated, or never married and not living together, the parent who " +
          "provided more than half of the student's financial support over the last 12 months is the parent " +
          "whose information goes on the FAFSA.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "The old rule -- use the parent you lived with most -- is no longer correct: support decides, not the " +
          "living arrangement.",
        AVG_CH2_PRIOR_CITED_AS,
        AVG_CH2_PRIOR_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If neither parent provided more than half of the support, the parent with the greater income and " +
          "assets -- and that parent's current spouse, if there is one -- is the required contributor.",
        AVG_CH2_PRIOR_CITED_AS,
        AVG_CH2_PRIOR_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "Child support that a parent pays counts toward that parent's share of the student's support.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If the parent whose information goes on the form has remarried, that parent's current spouse -- the " +
          "stepparent -- is also a required contributor and gives their own information and consent.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "The other parent is not a FAFSA contributor at all.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If the parent whose information was used dies, the student's dependency moves to the surviving " +
          "biological parent, not to the stepparent.",
        AVG_CH2_PRIOR_CITED_AS,
        AVG_CH2_PRIOR_URL,
      ),
      // --- contributor_without_an_ssn --------------------------------------
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "A parent or a spouse who has no Social Security number can still create a StudentAid.gov account and " +
          "be a contributor.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "Such a contributor proves who they are through a knowledge-based identity check instead of a Social " +
          "Security match.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "The detailed mechanics -- the paper form, the ITIN field, and how consent is given -- are written in " +
          "a practitioner handbook families never read, so the college's financial aid office is the place to " +
          "ask what this family should do.",
        AVG_CH2_PRIOR_CITED_AS,
        AVG_CH2_PRIOR_URL,
      ),
      // --- dependency ------------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "A student under 24 who is unmarried and an undergraduate is a dependent student, even if they live " +
          "alone and their parents do not claim them on a tax return.",
        AVG_CH2_CITED_AS,
        AVG_CH2_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "A student is independent only by meeting one of the criteria written into the law: born before the " +
          "cutoff date, married, a graduate student, on active duty or a veteran, having dependents of their " +
          "own, an orphan or in foster care or a ward of the court, emancipated or under legal guardianship, " +
          "or an unaccompanied homeless youth.",
        AVG_CH5_CITED_AS,
        AVG_CH5_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "Parents refusing to pay, or refusing to give their information, does not by itself make a student " +
          "independent.",
        AVG_CH5_CITED_AS,
        AVG_CH5_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "In unusual circumstances a financial aid administrator can grant a documented dependency override; " +
          "that is the college's decision to make, never a box the family ticks.",
        AVG_CH5_CITED_AS,
        AVG_CH5_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "On the current form a separated student is not counted as married.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      // --- after_you_submit -------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "After the form is submitted the family gets a FAFSA Submission Summary, the document that replaced " +
          "the Student Aid Report, showing the Student Aid Index and the estimated Pell Grant eligibility.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "The Student Aid Index is calculated, and every college listed on the form receives the student's " +
          "data electronically.",
        AVG_CH1_CITED_AS,
        AVG_CH1_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "The Student Aid Index replaced the Expected Family Contribution and can go as low as -1,500, so " +
          "colleges can tell apart the families with the deepest need.",
        DCL_CITED_AS,
        DCL_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "Some applications are selected for verification: the college asks for documents, though information " +
          "that came straight from the IRS generally does not have to be proved again.",
        AVG_CH4_CITED_AS,
        AVG_CH4_URL,
      ),
      // --- the_form_is_free -------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.THE_FORM_IS_FREE,
        // The line below names the Department of Education as the party a family
        // may NOT be charged by (20 U.S.C. 1090(d)(3)), never as the publisher of
        // a money figure. Its `money-attribution-exempt` marker is VESTIGIAL as
        // of RFC 231 -- the source-text sweep that read it is deleted, and D7's
        // surviving half is the compile-time one (`MoneySourceCopy`'s `when`s
        // with no `else`), which no comment can arm or disarm. The marker is left
        // in place as a recorded follow-up.
        "The FAFSA is free: no one -- not the " +
          "Department of Education, not a contractor, " + // money-attribution-exempt: a fee ban, not an attribution
          "not a servicer or a software provider -- may charge a family for collecting, processing or " +
          "delivering it.",
        FREE_FORM_CITED_AS,
        STATUTE_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.THE_FORM_IS_FREE,
        "Eligibility for federal student aid may be worked out only from the FAFSA; no other form stands in " +
          "for it.",
        FREE_FORM_CITED_AS,
        STATUTE_URL,
      ),
      FafsaRule(
        FafsaProcessTopic.THE_FORM_IS_FREE,
        "Anyone paid to prepare a FAFSA must put their own name and identifying details on the form, faces " +
          "the same penalties as the applicant for knowingly giving false information, must tell the family at " +
          "first contact that the FAFSA is free and can be completed without professional help, and may not " +
          "produce a form of their own for applying for federal student aid.",
        PREPARER_CITED_AS,
        STATUTE_URL,
      ),
    )

  /** The rules of one topic, in the order they were written. */
  fun rulesOf(topic: FafsaProcessTopic): List<FafsaRule> = RULES.filter { it.topic == topic }
}
