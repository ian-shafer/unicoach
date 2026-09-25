package ed.unicoach.coaching.aid

import ed.unicoach.common.util.requireCitedPageUrl
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
   * ONE PUBLISHED DOCUMENT, KEYED BY ITS URL: who published it, what it is
   * called, and where it is.
   *
   * [publisher] and [title] are separate fields because the two doors need them
   * apart: the coach prints them joined ([FafsaRule.citedAs]), and the public
   * `/fafsa` page's `ExplainerSource` requires them separately. Held as one
   * string here, that split would have had to be re-typed in `:public-web` as
   * an eight-entry map -- a second home for a fact, and one of the eight titles
   * contains commas, so no split rule is even safe. The type it already implied
   * is cheaper than the map.
   *
   * ONE ENTRY PER URL. Two entries sharing a url is what the page's reference
   * list refuses ("one URL cited with two different readings"), and a document
   * IS its address; where two rules cite different PARTS of one document, the
   * part is the rule's ([FafsaRule.pinpoint]), not the document's.
   */
  data class FafsaDocument(
    val publisher: String,
    val title: String,
    val url: String,
  ) {
    init {
      require(publisher.isNotBlank()) { "a FAFSA document must name its publisher: [$url]" }
      require(title.isNotBlank()) { "a FAFSA document must carry a title: [$url]" }
      // THE ADDRESS RULE HAS ONE HOME ([requireCitedPageUrl]) and is not
      // re-typed per citing type: every document in this corpus is https in
      // fact, and the guard states the bar the store keeps rather than a
      // stricter one that a reader would then have to loosen in three places.
      requireCitedPageUrl(url, "a FAFSA document")
    }
  }

  /**
   * One rule the coach may state, with the document it came from.
   *
   * The `init` block is the guard, not a style check: a load-bearing sentence
   * with no publisher and no https page does not construct, so it cannot ship.
   * Since the document is a TYPE, most of that guard now lives in
   * [FafsaDocument] and cannot be skipped by a rule at all.
   */
  data class FafsaRule(
    val topic: FafsaProcessTopic,
    val statement: String,
    val document: FafsaDocument,
    /**
     * WHICH PART of [document] this rule is in, appended to the title exactly as
     * written -- a legal pinpoint, and NOTHING ELSE. NULL for a document cited
     * whole.
     *
     * It exists for the two statute rules, which cite § 1090(d)(3) and
     * § 1090(d)(2) of ONE page. Making those two separate documents would put
     * two readings on one url, which the page's reference list refuses; making
     * them one document with one title would change what the coach's `cited_as`
     * says for both. The pinpoint keeps the url single and every `cited_as`
     * byte-identical.
     *
     * NULL AND NOT `""`: "cited whole" is a fact about the rule, so the TYPE
     * says it rather than a reader comparing against a magic empty string -- and
     * a present pinpoint is therefore never blank, which the guard below
     * enforces. Six of the eight documents are cited whole.
     */
    val pinpoint: String? = null,
    /**
     * What a reader of the citation must be told BEYOND the pinpoint, appended
     * after it -- a cross-reference to where a related duty sits.
     *
     * ITS OWN FIELD, because a pinpoint is a pinpoint: one value packed a
     * subsection, a status and a cross-reference into the pinpoint string, so
     * anything wanting `(d)(2)` alone -- a page, a footnote, a second door --
     * had to parse the sentence back out of it.
     */
    val pinpointNote: String? = null,
    val checkedOn: LocalDate = CHECKED_ON,
  ) {
    init {
      require(statement.isNotBlank()) { "a FAFSA rule must carry a statement" }
      require(pinpoint == null || pinpoint.isNotBlank()) {
        "a FAFSA rule pinpoints a part of [${document.url}] with a blank string: [$statement]"
      }
      require(pinpointNote == null || pinpoint != null) {
        "a FAFSA rule carries a citation note with no pinpoint to hang it on: [$statement]"
      }
    }

    /**
     * The document as the coach cites it, on the wire: publisher, title, and
     * the part this rule is in.
     *
     * DERIVED and no longer typed per rule -- the publisher prefix was uniform
     * across all eight documents, so the string was the join all along. The
     * bytes are unchanged, which is what `FafsaProcessChatToolTest` asserts.
     */
    val citedAs: String get() = "${document.publisher}, ${document.title}${pinpoint.orEmpty()}${pinpointNote.orEmpty()}"

    /** The page itself. Many rules cite one url; that is the point of [document]. */
    val url: String get() = document.url
  }

  // ---------------------------------------------------------------------------
  // The documents. Named once, cited many times. ONE PER URL.
  // ---------------------------------------------------------------------------

  private const val FSA = "Federal Student Aid"
  private const val CORNELL = "Cornell Law School Legal Information Institute"

  /** Every document any rule below cites, exposed so the public page can build its own citation from ours. */
  val DCL =
    FafsaDocument(
      publisher = FSA,
      title =
        "Dear Colleague Letter GEN-23-11, FAFSA Simplification Act Changes for " +
          "Implementation in the 2024-25 Award Year",
      url =
        "https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2023-08-04/" +
          "fafsa-simplification-act-changes-implementation-2024-25",
    )

  val AVG_CH1 =
    FafsaDocument(
      publisher = FSA,
      title = "2025-26 FSA Handbook, Application and Verification Guide, Ch. 1",
      url =
        "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
          "ch1-application-process-fafsa-isir",
    )

  val AVG_CH2 =
    FafsaDocument(
      publisher = FSA,
      title = "2025-26 FSA Handbook, Application and Verification Guide, Ch. 2",
      url =
        "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
          "ch2-filling-out-fafsa-form",
    )

  val AVG_CH2_PRIOR =
    FafsaDocument(
      publisher = FSA,
      title = "2024-25 FSA Handbook, Application and Verification Guide, Ch. 2",
      url =
        "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide/" +
          "ch2-filling-out-fafsa",
    )

  val AVG_CH4 =
    FafsaDocument(
      publisher = FSA,
      title = "2025-26 FSA Handbook, Application and Verification Guide, Ch. 4",
      url =
        "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
          "ch4-verification-updates-and-corrections",
    )

  val AVG_CH5 =
    FafsaDocument(
      publisher = FSA,
      title = "2025-26 FSA Handbook, Application and Verification Guide, Ch. 5",
      url =
        "https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/" +
          "ch5-special-cases",
    )

  /**
   * ONE statute page, cited by two rules at two subsections.
   *
   * The title is the document's own name and stops there; which subsection a
   * rule is in is that rule's [FafsaRule.pinpoint] below. Two documents at this
   * one url would be two readings of one page, which is exactly what the public
   * page's reference list refuses.
   */
  val STATUTE =
    FafsaDocument(
      publisher = CORNELL,
      title = "20 U.S.C. § 1090",
      url = "https://www.law.cornell.edu/uscode/text/20/1090",
    )

  /** The free-form rule's own subsection. */
  private const val FREE_FORM_PINPOINT = "(d)(3)"

  /** The preparer conditions are (d)(2). */
  private const val PREPARER_PINPOINT = "(d)(2)"

  /** Where the duty to tell a family the form is free actually sits, which (d)(2) alone does not say. */
  private const val PREPARER_NOTE =
    ", free preparation authorized; the duty to tell a family the form is free is § 1090(d)(2)(C)"

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
        DCL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The student and every contributor each need their own StudentAid.gov account; one login cannot be " +
          "shared between them.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Those accounts can be created before the form opens.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Each contributor must consent to the IRS sending their federal tax information to the FAFSA, and " +
          "that consent is a condition of receiving federal student aid.",
        DCL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "Only the contributor can give that consent: nobody at a college can give it on a family's behalf.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "If even one required contributor refuses consent, the FAFSA is rejected, no Student Aid Index is " +
          "calculated, and the student cannot receive federal student aid -- the Pell Grant included -- until " +
          "that consent is given.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The federal tax information pulled from the IRS is not shown on the family's own FAFSA Submission " +
          "Summary.",
        DCL,
      ),
      FafsaRule(
        FafsaProcessTopic.WHO_FILES,
        "The student themselves must have a Social Security number, except for citizens of the Freely " +
          "Associated States.",
        AVG_CH2,
      ),
      // --- divorced_or_separated_parents -----------------------------------
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "When parents are divorced, separated, or never married and not living together, the parent who " +
          "provided more than half of the student's financial support over the last 12 months is the parent " +
          "whose information goes on the FAFSA.",
        AVG_CH2,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "The old rule -- use the parent you lived with most -- is no longer correct: support decides, not the " +
          "living arrangement.",
        AVG_CH2_PRIOR,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If neither parent provided more than half of the support, the parent with the greater income and " +
          "assets -- and that parent's current spouse, if there is one -- is the required contributor.",
        AVG_CH2_PRIOR,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "Child support that a parent pays counts toward that parent's share of the student's support.",
        AVG_CH2,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If the parent whose information goes on the form has remarried, that parent's current spouse -- the " +
          "stepparent -- is also a required contributor and gives their own information and consent.",
        AVG_CH2,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "The other parent is not a FAFSA contributor at all.",
        AVG_CH2,
      ),
      FafsaRule(
        FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS,
        "If the parent whose information was used dies, the student's dependency moves to the surviving " +
          "biological parent, not to the stepparent.",
        AVG_CH2_PRIOR,
      ),
      // --- contributor_without_an_ssn --------------------------------------
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "A parent or a spouse who has no Social Security number can still create a StudentAid.gov account and " +
          "be a contributor.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "Such a contributor proves who they are through a knowledge-based identity check instead of a Social " +
          "Security match.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN,
        "The detailed mechanics -- the paper form, the ITIN field, and how consent is given -- are written in " +
          "a practitioner handbook families never read, so the college's financial aid office is the place to " +
          "ask what this family should do.",
        AVG_CH2_PRIOR,
      ),
      // --- dependency ------------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "A student under 24 who is unmarried and an undergraduate is a dependent student, even if they live " +
          "alone and their parents do not claim them on a tax return.",
        AVG_CH2,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "A student is independent only by meeting one of the criteria written into the law: born before the " +
          "cutoff date, married, a graduate student, on active duty or a veteran, having dependents of their " +
          "own, an orphan or in foster care or a ward of the court, emancipated or under legal guardianship, " +
          "or an unaccompanied homeless youth.",
        AVG_CH5,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "Parents refusing to pay, or refusing to give their information, does not by itself make a student " +
          "independent.",
        AVG_CH5,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "In unusual circumstances a financial aid administrator can grant a documented dependency override; " +
          "that is the college's decision to make, never a box the family ticks.",
        AVG_CH5,
      ),
      FafsaRule(
        FafsaProcessTopic.DEPENDENCY,
        "On the current form a separated student is not counted as married.",
        DCL,
      ),
      // --- after_you_submit -------------------------------------------------
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "After the form is submitted the family gets a FAFSA Submission Summary, the document that replaced " +
          "the Student Aid Report, showing the Student Aid Index and the estimated Pell Grant eligibility.",
        DCL,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "The Student Aid Index is calculated, and every college listed on the form receives the student's " +
          "data electronically.",
        AVG_CH1,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "The Student Aid Index replaced the Expected Family Contribution and can go as low as -1,500, so " +
          "colleges can tell apart the families with the deepest need.",
        DCL,
      ),
      FafsaRule(
        FafsaProcessTopic.AFTER_YOU_SUBMIT,
        "Some applications are selected for verification: the college asks for documents, though information " +
          "that came straight from the IRS generally does not have to be proved again.",
        AVG_CH4,
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
        STATUTE,
        FREE_FORM_PINPOINT,
      ),
      FafsaRule(
        FafsaProcessTopic.THE_FORM_IS_FREE,
        "Eligibility for federal student aid may be worked out only from the FAFSA; no other form stands in " +
          "for it.",
        STATUTE,
        FREE_FORM_PINPOINT,
      ),
      FafsaRule(
        FafsaProcessTopic.THE_FORM_IS_FREE,
        "Anyone paid to prepare a FAFSA must put their own name and identifying details on the form, faces " +
          "the same penalties as the applicant for knowingly giving false information, must tell the family at " +
          "first contact that the FAFSA is free and can be completed without professional help, and may not " +
          "produce a form of their own for applying for federal student aid.",
        STATUTE,
        PREPARER_PINPOINT,
        PREPARER_NOTE,
      ),
    )

  /** The rules of one topic, in the order they were written. */
  fun rulesOf(topic: FafsaProcessTopic): List<FafsaRule> = RULES.filter { it.topic == topic }

  /**
   * Every document the corpus cites, once each, in first-citation order.
   *
   * DERIVED from the rules and never typed, so a document can neither be listed
   * here and cited by nothing nor cited by a rule and missing from the list.
   */
  val DOCUMENTS: List<FafsaDocument> get() = RULES.map { it.document }.distinct()
}
