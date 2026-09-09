package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.putCitation
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.LoanTypeBorrowing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one home for the borrowing section's wire shape and its copy (RFC 175).
 *
 * Three rules hold this file together, and each of them is a defect this repo
 * has already had:
 *
 * - Every number is emitted WITH the sentence that names its cohort, from the
 *   same construct ([AidPolicyWire]'s rule), so no call site can put a figure
 *   in the model's context without the population it is over. "27% borrowed" is
 *   not a fact until it says 27% of whom.
 * - Every sentence names the SCHOOL as the claimant (D10). These are
 *   self-reported survey answers with no audit -- fourteen filings in our own
 *   corpus report more borrowers than graduates -- so a borrowing figure is
 *   said as "this school reports", never in the flat voice an administrative
 *   figure is said in.
 * - Loan types are never added together (D3) and no key here is a total. The
 *   four typed sets overlap, and the any-loan figure is the school's own.
 *
 * The keys are DERIVED from [LoanType], never hand-listed: a sixth loan type
 * would otherwise get its figures rendered and its key left out of
 * [NUMERIC_KEYS], which is exactly the drift the RFC 143 guard exists to catch.
 */
object BorrowingWire {
  /** The section key. */
  const val KEY: String = "borrowing_at_graduation"

  /** The class every figure in the section is reported over, and its sentence. */
  const val GRADUATING_CLASS_KEY: String = "graduating_class_count"
  const val GRADUATING_CLASS_LABEL_KEY: String = "graduating_class_label"

  /** The one sentence naming the cohort in words, with its year. */
  const val COHORT_LABEL_KEY: String = "cohort_label"

  /** The sentence about what this section is NOT, said for every school that has one. */
  const val NOTE_KEY: String = "borrowing_note"

  /** The sentence for a filing that reports borrowing and no private-loan average. */
  const val PRIVATE_NOT_FILED_KEY: String = "private_loan_not_in_filing"

  // OUR gap at ONE loan type (D7): this filing answers that column and we
  // could not read the answer. Per loan type rather than per filing, because
  // the sentence is about the column a family is missing -- and said as the
  // school's own silence it would tell them this school does not report a
  // figure it does.
  private const val NOT_READ_SUFFIX = "_not_read_by_us"

  // THE PUBLISHER's own withholding, and the source saying the question does
  // not apply. Their own keys, because neither is our gap and neither is the
  // school declining to report: one boolean over the whole status vocabulary
  // said all three in the school's voice.
  private const val WITHHELD_SUFFIX = "_withheld_by_publisher"
  private const val NOT_APPLICABLE_SUFFIX = "_not_applicable"

  fun notReadKey(loanType: LoanType): String = "${loanType.slug}$NOT_READ_SUFFIX"

  fun withheldKey(loanType: LoanType): String = "${loanType.slug}$WITHHELD_SUFFIX"

  fun notApplicableKey(loanType: LoanType): String = "${loanType.slug}$NOT_APPLICABLE_SUFFIX"

  /**
   * The key a value-less cell of THIS loan type is said under, or null when
   * there is nothing to say.
   *
   * Exhaustive over [FigureStatus] with no `else`: a seventh status must decide
   * whose silence it is before it compiles, which is exactly how
   * `not_collected_by_us` came to be spoken as the school's in the first place.
   */
  fun gapKey(
    loanType: LoanType,
    status: FigureStatus,
  ): String? =
    when (status) {
      // Value-bearing: the figure itself is rendered, and no cell is missing.
      FigureStatus.REPORTED, FigureStatus.IMPUTED_BY_PUBLISHER -> null

      // The school's own plain silence. Said by absence, as every other
      // unreported CDS figure on this surface is -- plus, for the one loan type
      // whose silence misleads most, [PRIVATE_NOT_FILED_KEY].
      FigureStatus.NOT_REPORTED_BY_INSTITUTION -> null

      FigureStatus.NOT_COLLECTED_BY_US -> notReadKey(loanType)

      FigureStatus.SUPPRESSED_BY_PUBLISHER -> withheldKey(loanType)

      FigureStatus.NOT_APPLICABLE -> notApplicableKey(loanType)
    }

  /** CDS H.401 answered and not readable BY US -- the denominator every share divides by (D7). */
  const val GRADUATING_CLASS_NOT_READ_KEY: String = "graduating_class_not_read_by_us"

  // Every numeric key names its unit, unit last -- the MeritAidWire rule --
  // and every one of them is prefixed by its loan type, because a borrowing
  // figure with no loan type on it is a different fact from the one the school
  // published.
  private const val AVERAGE_DEBT_SUFFIX = "_average_debt_usd"
  private const val BORROWER_COUNT_SUFFIX = "_borrower_count"
  private const val SHARE_SUFFIX = "_share_who_borrowed_percent"
  private const val LABEL_SUFFIX = "_label"

  fun averageDebtKey(loanType: LoanType): String = "${loanType.slug}$AVERAGE_DEBT_SUFFIX"

  fun borrowerCountKey(loanType: LoanType): String = "${loanType.slug}$BORROWER_COUNT_SUFFIX"

  fun shareKey(loanType: LoanType): String = "${loanType.slug}$SHARE_SUFFIX"

  fun averageDebtLabelKey(loanType: LoanType): String = "${averageDebtKey(loanType)}$LABEL_SUFFIX"

  fun shareLabelKey(loanType: LoanType): String = "${shareKey(loanType)}$LABEL_SUFFIX"

  /** The keys under this section whose value is a NUMBER by contract (the RFC 143 guard's allowlist). */
  val NUMERIC_KEYS: Set<String> =
    LoanType.entries
      .flatMap { listOf(averageDebtKey(it), borrowerCountKey(it), shareKey(it)) }
      .toSet() + GRADUATING_CLASS_KEY

  /**
   * The borrowing section. A school whose filing reports nothing we can render
   * never reaches here -- the caller says which silence that is in
   * `borrowing_availability` -- so this renderer always has a figure.
   *
   * It takes the whole [BorrowingCoverage.Reported] rather than the figures
   * alone: the loan types this filing answers and we could not read are part
   * of what the section has to say, and a signature that let a call site pass
   * the figures without them is how our own gap came to be spoken as the
   * school's silence.
   */
  fun objectOf(coverage: BorrowingCoverage.Reported): JsonObject =
    buildJsonObject {
      val borrowing = coverage.figures
      put(COHORT_LABEL_KEY, cohortLabel(borrowing.source))
      borrowing.graduatingClass?.let {
        put(GRADUATING_CLASS_KEY, it)
        put(GRADUATING_CLASS_LABEL_KEY, graduatingClassLabel(borrowing.source, it))
      }
      // OUR gap at the DENOMINATOR (D7). Said even though every share is simply
      // absent, because a section with averages and no share owes a family the
      // reason -- and the reason is ours, not this school's.
      if (coverage.graduatingClassNotReadByUs) {
        put(GRADUATING_CLASS_NOT_READ_KEY, graduatingClassNotReadByUsLabel(borrowing.source))
      }
      // Iterated over [LoanType.SPOKEN_ORDER], the ONE order these types are
      // said in: the coach and the report page narrate the same filing, so
      // they may not narrate it in two orders, and neither may choose its own.
      LoanType.SPOKEN_ORDER.forEach { loanType ->
        borrowing.of(loanType)?.let { putLoanType(loanType, it, borrowing.source) }
        // WHOSE silence this column's value-less cell is, said in that voice.
        // It rides beside the loan type's own figures because both can be true
        // at once: a filing that reports a federal average and hides a private
        // one behind a cell we could not read renders the first and says the
        // second.
        coverage.gapStatusByLoanType[loanType]?.let { status ->
          gapKey(loanType, status)?.let { key -> put(key, gapLabel(loanType, status, borrowing.source)) }
        }
      }
      // Said OUT LOUD for the one loan type whose silence misleads most. A
      // missing private figure otherwise reads as "there is no private
      // borrowing here", which the filing does not say -- and it is exactly
      // the half the federal-only median elsewhere cannot see.
      if (coverage.privateAverageIsUnfiled()) {
        put(PRIVATE_NOT_FILED_KEY, privateNotFiledLabel(borrowing.source))
      }
      put(NOTE_KEY, NOTE)
      putJsonObject("source") { putCitation(borrowing.source) }
    }

  /**
   * Every sentence this section says about one school, in the order a family
   * hears them -- the SAME rules [objectOf] renders as keys, derived once here
   * so a surface that speaks prose and a surface that speaks JSON cannot
   * narrate the same filing differently.
   *
   * The cohort line and the source line are NOT here: they frame the list and
   * each surface places them itself.
   */
  fun listSentences(coverage: BorrowingCoverage.Reported): List<String> =
    buildList {
      val source = coverage.figures.source
      if (coverage.graduatingClassNotReadByUs) add(graduatingClassNotReadByUsLabel(source))
      LoanType.SPOKEN_ORDER.forEach { loanType -> addAll(listSentencesFor(loanType, coverage)) }
      if (coverage.privateAverageIsUnfiled()) add(privateNotFiledLabel(source))
    }

  /**
   * Everything one filing says about ONE loan type, in the order a family hears
   * it -- the prose twin of [putLoanType], so the two surfaces cannot come to
   * apply the same per-type policy differently.
   */
  private fun listSentencesFor(
    loanType: LoanType,
    coverage: BorrowingCoverage.Reported,
  ): List<String> =
    buildList {
      val source = coverage.figures.source
      val loanTypeBorrowing = coverage.figures.of(loanType)
      loanTypeBorrowing?.averageDebtUsd?.let { add(averageDebtLabel(loanType, it, source)) }
      // Only a CONSISTENT pair is spoken; the school's own contradiction (D6)
      // is held and never said, exactly as the payload holds it. Routed through
      // the same exhaustive `when` the payload uses, so a third
      // [BorrowerCounts] state cannot fall silently through a cast.
      when (val counts = loanTypeBorrowing?.borrowers) {
        is BorrowerCounts.Counted -> counts.share?.let { add(shareLabel(loanType, counts, it, source)) }
        is BorrowerCounts.ContradictsGraduatingClass, null -> Unit
      }
      coverage.gapStatusByLoanType[loanType]?.let { status ->
        if (gapKey(loanType, status) != null) add(gapLabel(loanType, status, source))
      }
    }

  /**
   * True when the missing private-loan average is the SCHOOL's own silence.
   *
   * Named once and asked by both surfaces, because it is a POLICY question and
   * not a null check: an unread private cell is OUR gap and has already been
   * said as one, a withheld or not-applicable cell is the publisher's or the
   * source's, and this claim on top of any of them would put someone else's
   * silence in the school's mouth.
   */
  private fun BorrowingCoverage.Reported.privateAverageIsUnfiled(): Boolean {
    if (figures.of(LoanType.PRIVATE)?.averageDebtUsd != null) return false
    val gap = gapStatusByLoanType[LoanType.PRIVATE]
    return gap == null || gap == FigureStatus.NOT_REPORTED_BY_INSTITUTION
  }

  /**
   * One loan type's figures. The average and the share are INDEPENDENT: a
   * school that reports a borrower count and no class size has an average to
   * say and no share, and this emits exactly that.
   */
  private fun JsonObjectBuilder.putLoanType(
    loanType: LoanType,
    figures: LoanTypeBorrowing,
    source: CdsCitation,
  ) {
    figures.averageDebtUsd?.let {
      put(averageDebtKey(loanType), it)
      put(averageDebtLabelKey(loanType), averageDebtLabel(loanType, it, source))
    }
    // The derived share and BOTH of its counts, or none of them. The pair IS
    // the derivation ([ed.unicoach.db.models.BorrowerCounts] holds it), so the
    // share can never be put without the class size it is a share of -- the
    // figure this codebase refuses to publish (RFC 148).
    when (val counts = figures.borrowers) {
      is BorrowerCounts.Counted -> {
        counts.share?.let { share ->
          put(borrowerCountKey(loanType), counts.borrowers)
          put(shareKey(loanType), share.percent)
          put(shareLabelKey(loanType), shareLabel(loanType, counts, share, source))
        }
      }

      // The SCHOOL's own contradiction (D6): more borrowers than graduates, so
      // neither cell is spoken and no share is derived. Nothing is said about
      // it -- a family is owed this school's figures, not a report on its
      // filing's arithmetic -- and the average above still stands.
      is BorrowerCounts.ContradictsGraduatingClass, null -> {
        Unit
      }
    }
  }

  /** The cohort, in words, with its year and the school that is claiming it. */
  fun cohortLabel(source: CdsCitation): String =
    "${source.collegeName} reports these borrowing figures for the students who graduated from it in " +
      "${CdsCitation.cycleLabel(source.sourceYear)} -- not this year's freshmen, and not all its undergraduates"

  fun graduatingClassLabel(
    source: CdsCitation,
    graduatingClass: Int,
  ): String =
    "${source.collegeName} reports $graduatingClass students in its ${CdsCitation.cycleLabel(source.sourceYear)} " +
      "graduating class"

  /**
   * The average, with the loan type, the cohort and the claimant in the same
   * sentence. "Owed by the time they graduated", never a per-year figure and
   * never a price: this is cumulative principal, and no sentence here may put
   * it next to a cost of attendance.
   */
  fun averageDebtLabel(
    loanType: LoanType,
    amountUsd: Int,
    source: CdsCitation,
  ): String =
    "${source.collegeName} reports that the students who graduated in " +
      "${CdsCitation.cycleLabel(source.sourceYear)} and borrowed ${loanType.spoken} owed " +
      "${WholeDollars.spoken(amountUsd)} on average by the time they graduated"

  /**
   * The derived share, with both counts and the claimant in the same sentence.
   *
   * It takes the COUNTED pair rather than a share beside two loose numbers:
   * [BorrowerCounts.Counted] is where the share and the class size it divides
   * by are bound together, and a signature that split them let a call site say
   * one filing's percentage over another's graduating class.
   */
  fun shareLabel(
    loanType: LoanType,
    counted: BorrowerCounts.Counted,
    share: Share,
    source: CdsCitation,
  ): String =
    "${source.collegeName} reports that ${counted.borrowers} of the ${counted.graduatingClass} students who " +
      "graduated in ${CdsCitation.cycleLabel(source.sourceYear)} borrowed ${loanType.spoken} -- " +
      share.spokenPercent()

  /**
   * OUR gap at one loan type, said as ours: this school's filing ANSWERS the
   * column and we could not read the answer. It never says the school reports
   * nothing -- the school does report it, and D7 forbids handing our failure
   * to the school.
   */
  fun notReadByUsLabel(
    loanType: LoanType,
    source: CdsCitation,
  ): String =
    "${source.citedAs} answers what its " +
      "graduates borrowed in ${loanType.spoken}, and WE could not read that answer out of it -- say that the " +
      "figure is missing from our data, never that this school does not report it"

  /**
   * Someone ELSE's silence at one loan type, said in that voice: the publisher
   * withheld this cell, or the source itself says the question does not apply
   * here.
   *
   * The words come from [FigureStatusCopy], the one home of what each status
   * MEANS, rather than a fourth copy invented here; this adds only the frame
   * every sentence on this surface carries -- the school, the cycle and the
   * column. It never says the school does not report the figure, because none
   * of these statuses says that.
   */
  fun gapLabel(
    loanType: LoanType,
    status: FigureStatus,
    source: CdsCitation,
  ): String =
    when (status) {
      FigureStatus.NOT_COLLECTED_BY_US -> {
        notReadByUsLabel(loanType, source)
      }

      // The two statuses whose silence is SOMEONE ELSE's: the publisher
      // withheld the cell, or the source itself says the question does not
      // apply here. Named rather than caught by an `else`, so a seventh status
      // must be decided here instead of inheriting a sentence written for these
      // two.
      FigureStatus.SUPPRESSED_BY_PUBLISHER, FigureStatus.NOT_APPLICABLE -> {
        // Resolved inside the `when`, never by interpolation:
        // [FigureStatusCopy.agentlessStatementOf] is null for
        // [FigureStatus.REPORTED], and interpolating a nullable splices the
        // literal text `null` into family-facing coach copy. Neither arm above is REPORTED,
        // so this never fires; it fires LOUDLY rather than silently if a status
        // with no sentence is ever routed here.
        val statement =
          checkNotNull(FigureStatusCopy.agentlessStatementOf(status)) {
            "a borrowing gap is spoken and this status has no sentence: " +
              "loan_type=[${loanType.slug}] status=[${status.value}] cited_as=[${source.citedAs}]"
          }
        // [CdsCitation.citedAs], not a hand-typed "<school>'s <cycle> Common
        // Data Set": the publisher's spoken name is derived at its one home --
        // here and at the three sibling sentences in this object -- so none of
        // them can drift off the citation beside it (RFC 177). Byte-identical
        // output, four fewer places that type a publisher.
        //
        // AGENTLESS on purpose (RFC 177), with a publisher in hand: this
        // sentence's own frame already names the school's Common Data Set as
        // the publisher, so passing MoneySource.COMMON_DATA_SET would name the
        // same document twice in one sentence ("... Common Data Set answers
        // ... The school's own Common Data Set withholds ...").
        "${source.citedAs} answers what its " +
          "graduates borrowed in ${loanType.spoken} and no figure is shown here: " +
          "$statement Say it that way, never that this school does not report it."
      }

      // NOT a gap sentence at all, and refused rather than worded. The first two
      // are value-bearing -- the figure itself is rendered ([gapKey] emits no
      // key for them) -- and the third is the SCHOOL's own silence, which this
      // sentence explicitly promises never to say. [listSentencesFor] and
      // [objectOf] already filter all three through [gapKey]; this makes the
      // filter the type's rule rather than each caller's.
      FigureStatus.REPORTED, FigureStatus.IMPUTED_BY_PUBLISHER, FigureStatus.NOT_REPORTED_BY_INSTITUTION -> {
        error(
          "this status is not someone else's silence at a loan type, so it has no gap sentence: " +
            "loan_type=[${loanType.slug}] status=[${status.value}] cited_as=[${source.citedAs}]",
        )
      }
    }

  /**
   * OUR gap at CDS H.401, the class every share divides by. Its own sentence
   * because it is not a loan type: without the denominator no share is derived
   * for ANY loan type, and a family told nothing reads that as the school
   * having reported nothing.
   */
  fun graduatingClassNotReadByUsLabel(source: CdsCitation): String =
    "${source.citedAs} reports the size of " +
      "its graduating class and WE could not read that answer out of it, so no share of graduates who borrowed " +
      "can be given here -- say that the figure is missing from our data, never that this school does not " +
      "report it"

  /**
   * The absence sentence for the private figure, attributed like every other
   * sentence here: it says what this school's FILING does not carry, never that
   * its graduates borrowed no private money. No source publishes a negative.
   */
  fun privateNotFiledLabel(source: CdsCitation): String =
    "${source.citedAs} reports no average " +
      "for private loans, so these figures are not the whole of what its graduates borrowed"

  /**
   * How a loan type is SAID. The source's own field ids and slugs stay in the
   * store; a family hears words (RFC 143's bare-code rule).
   */
  private val LoanType.spoken: String
    get() =
      when (this) {
        LoanType.ANY -> "a loan of any kind"
        LoanType.FEDERAL -> "federal loans"
        LoanType.INSTITUTIONAL -> "a loan from the school itself"
        LoanType.STATE -> "a state loan"
        LoanType.PRIVATE -> "a private loan"
      }

  /**
   * The standing sentence about what this section is not. Said for every school
   * that has one, because the misreadings are predictable: that the loan types
   * add up, that debt can be taken off a price, and that a self-reported survey
   * answer is an audited record.
   */
  const val NOTE: String =
    "These figures are what the school itself reported about its own graduating class, so name the school when " +
      "you say one. Each loan type is separate and they overlap, so never add two of them together and never " +
      "present one as a total. This is money owed after graduating, not a price: never subtract it from any " +
      "cost, and never present it as one."
}
