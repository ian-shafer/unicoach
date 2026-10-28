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
 * One school's borrowing section: the attribution, and the figures said under
 * it (RFC 190).
 *
 * ONE value, because the two halves may not be separated. Before RFC 190 every
 * sentence carried its own "<College> reports that ..." frame and a surface was
 * free to render the list without the line that framed it; the frame is now
 * said once, in [header], and [BorrowingWire.blockOf] is the only way to reach
 * [sentences] -- so a caller cannot render the figures without the claim they
 * are made under.
 *
 * RFC 196 carries the CITATION into this type rather than the prose built from
 * it, and RFC 190's guarantee is unchanged by that -- strengthened, if anything.
 * [blockOf] is still the only way to reach [sentences], and the attribution the
 * figures are unreachable without is now a typed [CdsCitation] instead of an
 * opaque string. Nothing is loosened: no surface gained a way to hold the
 * figures without the claim, it gained a way to say the claim as a LINK. The
 * flattened header used to be built in the service and the page could therefore
 * not link the filing it was already naming -- a fact read from the store and
 * dropped a layer later (the RFC 177 shape).
 */
data class BorrowingBlock(
  /** Who is claiming every figure below, in which cycle, from which document -- and WHERE that document is. */
  val citation: CdsCitation,
  /** The figures, said without re-naming the claimant. */
  val sentences: List<String>,
) {
  /**
   * The whole attribution sentence, for a surface that cannot carry a link.
   *
   * A surface that CAN renders [citation] as the link and
   * [BorrowingWire.HEADER_TAIL] after it. That constant is not mirrored here:
   * it is the same for every block, so a pass-through property on this record
   * would be a rendering parameter riding on one filing's data -- and it is what
   * [header] is built from, so the two forms cannot say different sentences
   * either way.
   */
  val header: String get() = BorrowingWire.blockHeader(citation)
}

/**
 * One school's borrowing section as ROWS: the attribution, the class, one row
 * per loan type the filing speaks (RFC 260).
 *
 * The row twin of [BorrowingBlock], for a surface that lays the figures out in
 * a table rather than in sentences. The same guarantee rides on it: the
 * [citation] every row is claimed under travels in the same value as the rows,
 * and [BorrowingWire.rowsOf] is the only way to reach them, so a table cannot
 * be rendered without the filing it is read from. WHICH loan types are rows,
 * and which absences are said aloud, are the same rules [BorrowingWire.blockOf]
 * applies -- `BorrowingWireTest` asserts the two cannot narrate one filing
 * differently.
 */
data class BorrowingRows(
  val citation: CdsCitation,
  /**
   * CDS H.401, formatted by the page; null when the filing carries no count we
   * hold. Whether that null is OUR gap -- the filing answers and we could not
   * read it -- is said by a [BorrowingGap.GraduatingClassNotReadByUs] entry in
   * [gaps], never by a second reading of this null.
   */
  val graduatingClass: Int?,
  /** In [LoanType.SPOKEN_ORDER]; only loan types with at least one figure. */
  val rows: List<LoanRow>,
  /**
   * Every absence said under the table, in the order it is said: the
   * unread-class gap first if there is one, then each loan type with a gap in
   * [LoanType.SPOKEN_ORDER], then the private absence last if there is one.
   * Typed rather than worded, so a surface can tell one gap from another
   * without parsing a sentence; [BorrowingWire.sentenceOf] says each in words.
   */
  val gaps: List<BorrowingGap>,
)

/**
 * One absence a borrowing table says aloud under its rows (RFC 260): whose
 * silence it is, kept as the typed cause rather than flattened into prose.
 *
 * A sealed union because the three causes are disjoint and each is spoken by
 * its own sentence -- a list of strings beside two booleans that restated
 * three of them made a reader pair a flag with a sentence by position.
 * [BorrowingWire.sentenceOf] is the one place a gap becomes words.
 */
sealed interface BorrowingGap {
  /** OUR gap at CDS H.401: the filing answers the class size and we could not read it, so no share is derived. */
  data object GraduatingClassNotReadByUs : BorrowingGap

  /**
   * One loan type's value-less cell, and WHOSE silence it is. Built only from
   * [BorrowingWire]'s `gapStatusOf`, the one filter that admits a status as a
   * gap, so [status] is always one [BorrowingWire.gapLabel] has a sentence for
   * -- never a value-bearing status, and never the school's own plain silence.
   */
  data class LoanTypeStatus(
    val loanType: LoanType,
    val status: FigureStatus,
  ) : BorrowingGap

  /** The filing reports no private-loan average and that silence is the school's own, not ours or the publisher's. */
  data object PrivateNotFiled : BorrowingGap
}

/**
 * One loan type's row: the words for the type, the average cell, and the
 * borrowed cell (RFC 260).
 *
 * Each cell is ONE value whose states are disjoint, never a pair of nullables a
 * renderer has to reconcile: a figure and the reason it is missing cannot both
 * be set, and a borrowed count cannot reach a cell without the share it is
 * shown with. [BorrowingWire.rowsOf] is where each is decided, once.
 */
data class LoanRow(
  val loanType: LoanType,
  /** The wire's own words for the loan type, mid-sentence form ("federal loans"). */
  val spokenLoanType: String,
  val averageOwed: OwedAverage,
  /** The CONSISTENT pair's count and share, or null (not reported, a class of zero, or the school's own contradiction, D6). */
  val borrowed: BorrowedShare?,
)

/**
 * The average-owed cell of one loan type: the figure, the reason the filing's
 * answer is not shown, or the filing's own silence (RFC 260).
 *
 * A union rather than an `Int?` beside a `FigureStatus?`, because the three are
 * disjoint and two nullables spanned a fourth state -- a figure AND a gap -- that
 * a renderer could only resolve by silently preferring one. [Withheld] carries
 * its sentence resolved at construction, so a status with no sentence fails in
 * [BorrowingWire.rowsOf], where the filing is in hand to name, and never inside
 * a family's page render.
 */
sealed interface OwedAverage {
  /** The school's own average, in whole dollars. */
  data class Shown(
    val usd: Int,
  ) : OwedAverage

  /**
   * The filing answers the column and no figure is shown, for someone's reason
   * other than the school's silence -- [status] says whose, [statement] is
   * [FigureStatusCopy.agentlessStatementOf] for it, the frozen words the gap
   * sentence under the table frames.
   */
  data class Withheld(
    val status: FigureStatus,
    val statement: String,
  ) : OwedAverage

  /** No average in the filing and no status saying why: the filing simply lacks it. */
  data object NotInFiling : OwedAverage
}

/**
 * The borrowed cell's two numbers: how many borrowed this loan type, and the
 * share of the graduating class that is (RFC 260).
 *
 * The share is NON-null here, where [BorrowerCounts.Counted.share] is not (a
 * class of zero has none): a row only carries a pair that yields one, so the
 * "never a share without its count" rule is the type's and not every producer's
 * filter. The class the share divides by is [BorrowingRows.graduatingClass] --
 * the same filing's H.401 every [BorrowerCounts.Counted] is built over -- said
 * once in the column header rather than in every cell.
 */
data class BorrowedShare(
  val borrowers: Int,
  val share: Share,
)

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
 * - Every BLOCK names the school once, in its header, and the sentences are
 *   UNREACHABLE without it ([BorrowingBlock], RFC 190, superseding RFC 175
 *   D10's per-sentence rule). These are self-reported survey answers with no
 *   audit -- fourteen filings in our own corpus report more borrowers than
 *   graduates -- so a borrowing figure is still the school's own claim and
 *   never an administrative record. What changed is the UNIT the attribution
 *   rides in, not the requirement: [blockOf] returns the header and the
 *   figures as ONE value, so no caller can render a figure without the
 *   attribution it is said under, and a figure whose publisher DIFFERS names
 *   its own publisher rather than borrowing this header.
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

  /**
   * The BLOCK HEADER: who is claiming every figure in this section, from which
   * document, over which cohort (RFC 190).
   *
   * The key keeps its name -- the sentence it carries is still the one that
   * names the cohort in words -- and it now carries the section's one
   * attribution as well, so the figure labels beside it need not restate it.
   */
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
      put(COHORT_LABEL_KEY, blockHeader(borrowing.source))
      borrowing.graduatingClass?.let {
        put(GRADUATING_CLASS_KEY, it)
        put(GRADUATING_CLASS_LABEL_KEY, graduatingClassLabel(it))
      }
      // OUR gap at the DENOMINATOR (D7). Said even though every share is simply
      // absent, because a section with averages and no share owes a family the
      // reason -- and the reason is ours, not this school's.
      if (coverage.graduatingClassNotReadByUs) {
        put(GRADUATING_CLASS_NOT_READ_KEY, graduatingClassNotReadByUsLabel())
      }
      // Iterated over [LoanType.SPOKEN_ORDER], the ONE order these types are
      // said in: the coach and the report page narrate the same filing, so
      // they may not narrate it in two orders, and neither may choose its own.
      LoanType.SPOKEN_ORDER.forEach { loanType ->
        borrowing.of(loanType)?.let { putLoanType(loanType, it) }
        // WHOSE silence this column's value-less cell is, said in that voice.
        // It rides beside the loan type's own figures because both can be true
        // at once: a filing that reports a federal average and hides a private
        // one behind a cell we could not read renders the first and says the
        // second.
        //
        // The filter is [gapStatusOf]'s, the one every seam asks; the KEY is
        // still [gapKey]'s. The `checkNotNull` cannot fire: [gapStatusOf]
        // returns only a status [gapKey] has a key for, and it says so loudly
        // rather than dropping the sentence if the two ever part.
        gapStatusOf(loanType, coverage)?.let { status ->
          put(
            checkNotNull(gapKey(loanType, status)) {
              "a gap status with no key: loan_type=[${loanType.slug}] status=[${status.value}] " +
                "cited_as=[${borrowing.source.citedAs}]"
            },
            gapLabel(loanType, status, borrowing.source),
          )
        }
      }
      // Said OUT LOUD for the one loan type whose silence misleads most. A
      // missing private figure otherwise reads as "there is no private
      // borrowing here", which the filing does not say -- and it is exactly
      // the half the federal-only median elsewhere cannot see.
      if (coverage.privateAverageIsUnfiled()) {
        put(PRIVATE_NOT_FILED_KEY, privateNotFiledLabel())
      }
      put(NOTE_KEY, NOTE)
      putJsonObject("source") { putCitation(borrowing.source) }
    }

  /**
   * This section as ONE value: the attribution, and the figures said under it
   * (RFC 190).
   *
   * It returns a [BorrowingBlock] rather than a list of sentences because the
   * attribution is no longer restated in every sentence: a caller that holds
   * the figures holds the header they are claimed under, in the same value, so
   * "the block cannot render without its header" is a property of the type
   * rather than a convention each surface has to keep.
   *
   * WHICH sentences, and in what ORDER, is unchanged -- the SAME rules
   * [objectOf] renders as keys, derived once here so a surface that speaks
   * prose and a surface that speaks JSON cannot narrate the same filing
   * differently.
   */
  fun blockOf(coverage: BorrowingCoverage.Reported): BorrowingBlock =
    BorrowingBlock(
      citation = coverage.figures.source,
      sentences =
        buildList {
          if (coverage.graduatingClassNotReadByUs) add(graduatingClassNotReadByUsLabel())
          LoanType.SPOKEN_ORDER.forEach { loanType -> addAll(listSentencesFor(loanType, coverage)) }
          if (coverage.privateAverageIsUnfiled()) add(privateNotFiledLabel())
        },
    )

  /**
   * This section as ROWS (RFC 260): the same filing [blockOf] narrates, laid
   * out for a table rather than for sentences.
   *
   * A third reading of the same rules, never a third copy of them. Which loan
   * types are spoken is [LoanTypeBorrowing.hasFact] -- the predicate
   * [BorrowingCoverage.of] already admits a filing on -- a pair is carried only
   * when it yields a share (the [putLoanType] rule, D6), and every absence is
   * the same predicate [blockOf] asks, in [blockOf]'s order, kept as a typed
   * [BorrowingGap] that [sentenceOf] words with [blockOf]'s own labels. The
   * average and share SENTENCES are the one thing a row does not carry: a table
   * says the figure under a column header instead, so the words are not
   * restated per cell. `BorrowingWireTest` holds the two seams to one story.
   */
  fun rowsOf(coverage: BorrowingCoverage.Reported): BorrowingRows =
    BorrowingRows(
      citation = coverage.figures.source,
      graduatingClass = coverage.figures.graduatingClass,
      rows = loanRowsOf(coverage),
      gaps = gapsOf(coverage),
    )

  /**
   * One gap in words: the same sentence [blockOf] says for it, so the table's
   * paragraphs and the coach's block cannot word one absence two ways.
   *
   * The ONE place a [BorrowingGap] becomes prose. Exhaustive with no `else`, so
   * a fourth kind of gap must be given its sentence here before it compiles,
   * rather than reaching a page with none.
   */
  fun sentenceOf(
    gap: BorrowingGap,
    citation: CdsCitation,
  ): String =
    when (gap) {
      BorrowingGap.GraduatingClassNotReadByUs -> graduatingClassNotReadByUsLabel()
      is BorrowingGap.LoanTypeStatus -> gapLabel(gap.loanType, gap.status, citation)
      BorrowingGap.PrivateNotFiled -> privateNotFiledLabel()
    }

  /**
   * The rows: one per loan type the filing speaks, in [LoanType.SPOKEN_ORDER].
   * A type with only a gap status gets no row -- its absence is said under the
   * table by [gapsOf] instead -- because a row of two blanks says nothing a
   * family can read off a column.
   */
  private fun loanRowsOf(coverage: BorrowingCoverage.Reported): List<LoanRow> =
    LoanType.SPOKEN_ORDER.mapNotNull { loanType ->
      val figures = coverage.figures.of(loanType)?.takeIf { it.hasFact } ?: return@mapNotNull null
      LoanRow(
        loanType = loanType,
        spokenLoanType = loanType.spoken,
        averageOwed = owedAverageOf(loanType, figures, coverage),
        borrowed = borrowedShareOf(figures.borrowers),
      )
    }

  /**
   * The absences said under the table, in [blockOf]'s order: the unread class,
   * each loan type's gap in [LoanType.SPOKEN_ORDER], the private absence. The
   * same three predicates [blockOf] asks, so the two seams cannot disagree
   * about which absences a filing has; every [BorrowingGap.LoanTypeStatus] is
   * built from [gapStatusOf] and nowhere else.
   */
  private fun gapsOf(coverage: BorrowingCoverage.Reported): List<BorrowingGap> =
    buildList {
      if (coverage.graduatingClassNotReadByUs) add(BorrowingGap.GraduatingClassNotReadByUs)
      LoanType.SPOKEN_ORDER.forEach { loanType ->
        gapStatusOf(loanType, coverage)?.let { status -> add(BorrowingGap.LoanTypeStatus(loanType, status)) }
      }
      if (coverage.privateAverageIsUnfiled()) add(BorrowingGap.PrivateNotFiled)
    }

  /**
   * The status this filing's value-less cell for [loanType] is said under, or
   * null where there is nothing to say -- the [gapKey] filter, asked once by
   * every seam that reads a gap ([objectOf]'s key, [blockOf]'s sentence, and
   * [rowsOf]'s cell and [BorrowingGap]) so no two of them can disagree about
   * which statuses are gaps.
   */
  private fun gapStatusOf(
    loanType: LoanType,
    coverage: BorrowingCoverage.Reported,
  ): FigureStatus? = coverage.gapStatusByLoanType[loanType]?.takeIf { gapKey(loanType, it) != null }

  /**
   * One row's average cell, decided once: the figure where the filing has one,
   * else the gap [gapStatusOf] says is someone's to explain, else the filing's
   * own silence.
   *
   * The figure is asked first, as the page always preferred it: a gap status
   * beside a shown average is about a cell the row does not leave blank. The
   * sentence is resolved HERE rather than at render for [gapLabel]'s reason --
   * a nullable spliced into family-facing copy prints the literal `null` -- and
   * with the filing in hand, so a status with no sentence fails naming the
   * school it came from rather than inside a page render.
   */
  private fun owedAverageOf(
    loanType: LoanType,
    figures: LoanTypeBorrowing,
    coverage: BorrowingCoverage.Reported,
  ): OwedAverage {
    figures.averageDebtUsd?.let { return OwedAverage.Shown(it) }
    val status = gapStatusOf(loanType, coverage) ?: return OwedAverage.NotInFiling
    val statement =
      checkNotNull(FigureStatusCopy.agentlessStatementOf(status)) {
        "a borrowing gap reached the owed cell and its status has no sentence: " +
          "loan_type=[${loanType.slug}] status=[${status.value}] cited_as=[${coverage.figures.source.citedAs}]"
      }
    return OwedAverage.Withheld(status, statement)
  }

  /**
   * One row's borrowed cell: the CONSISTENT pair's count and its share, or
   * null. Routed through the same exhaustive `when` [putLoanType] and
   * [listSentencesFor] use, so the row emits nothing for the school's own
   * contradiction (D6) and nothing for a pair over a class of zero, exactly as
   * they do -- and a third [BorrowerCounts] state cannot fall through a cast.
   */
  private fun borrowedShareOf(counts: BorrowerCounts?): BorrowedShare? =
    when (counts) {
      is BorrowerCounts.Counted -> counts.share?.let { BorrowedShare(borrowers = counts.borrowers, share = it) }
      is BorrowerCounts.ContradictsGraduatingClass, null -> null
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
      loanTypeBorrowing?.averageDebtUsd?.let { add(averageDebtLabel(loanType, it)) }
      // Only a CONSISTENT pair is spoken; the school's own contradiction (D6)
      // is held and never said, exactly as the payload holds it. Routed through
      // the same exhaustive `when` the payload uses, so a third
      // [BorrowerCounts] state cannot fall silently through a cast.
      when (val counts = loanTypeBorrowing?.borrowers) {
        is BorrowerCounts.Counted -> counts.share?.let { add(shareLabel(loanType, counts, it)) }
        is BorrowerCounts.ContradictsGraduatingClass, null -> Unit
      }
      gapStatusOf(loanType, coverage)?.let { status -> add(gapLabel(loanType, status, source)) }
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
  ) {
    figures.averageDebtUsd?.let {
      put(averageDebtKey(loanType), it)
      put(averageDebtLabelKey(loanType), averageDebtLabel(loanType, it))
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
          put(shareLabelKey(loanType), shareLabel(loanType, counts, share))
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

  /**
   * The block's ONE attribution: the document claiming every figure under it,
   * and the cohort those figures are over (RFC 190).
   *
   * Built from [CdsCitation.citedAs] -- the one home of the publisher's spoken
   * name -- so the header cannot drift off the citation the section carries
   * beside it, and so the school, the cycle and the document are said in the
   * same words every other surface cites this filing in. It replaces both the
   * old per-section cohort line and the page's trailing source line: with the
   * document named here, a second citation under the same figures is
   * restatement.
   *
   * "That year" rather than the cycle repeated: the cycle is already in
   * [CdsCitation.citedAs], one clause earlier in this same sentence.
   */
  fun blockHeader(source: CdsCitation): String = "${source.citedAs}$HEADER_TAIL"

  /**
   * The two things the borrowing cohort is NOT, as one clause -- the tail of
   * [HEADER_TAIL], and of the report page's once-per-page kinds sentence (RFC
   * 260 K2).
   *
   * Its own constant so the two surfaces state the cohort's limits in one copy
   * of the words: the page says these hedges once rather than in every school's
   * header, and a change to the cohort definition here reaches both.
   */
  const val COHORT_HEDGES: String = "not this year's freshmen, and not all its undergraduates"

  /**
   * Everything [blockHeader] says AFTER the claimant, as one string.
   *
   * Extracted so a surface that renders the claimant as a LINK (RFC 196) prints
   * the rest of the same sentence rather than composing a second one. There is
   * one copy of these words, and [blockHeader] is built from it, so the two
   * forms cannot drift.
   */
  const val HEADER_TAIL: String = " reports these figures for the students who graduated from it that year -- " + COHORT_HEDGES

  /**
   * The class every figure in the section is reported over.
   *
   * It names no school and no cycle: it is said under [blockHeader], which
   * names both, and a sentence that restated them would be the repetition RFC
   * 190 removes.
   */
  fun graduatingClassLabel(graduatingClass: Int): String = "$graduatingClass students were in the graduating class"

  /**
   * The average, with the loan type and the cohort in the same sentence. "Owed
   * by the time they graduated", never a per-year figure and never a price:
   * this is cumulative principal, and no sentence here may put it next to a
   * cost of attendance.
   *
   * The CLAIMANT is [blockHeader]'s, once for the block (RFC 190): the
   * sentence is only reachable through [blockOf], which hands it out with that
   * header, so dropping the frame here drops no attribution.
   */
  fun averageDebtLabel(
    loanType: LoanType,
    amountUsd: Int,
  ): String =
    "Graduates who borrowed ${loanType.spoken} owed ${WholeDollars.spoken(amountUsd)} on average by the time " +
      "they graduated"

  /**
   * The derived share, with BOTH counts in the same sentence.
   *
   * It takes the COUNTED pair rather than a share beside two loose numbers:
   * [BorrowerCounts.Counted] is where the share and the class size it divides
   * by are bound together, and a signature that split them let a call site say
   * one filing's percentage over another's graduating class.
   *
   * The claimant and the cycle are [blockHeader]'s, once for the block (RFC
   * 190).
   */
  fun shareLabel(
    loanType: LoanType,
    counted: BorrowerCounts.Counted,
    share: Share,
  ): String =
    "${counted.borrowers} of the ${counted.graduatingClass} students who graduated borrowed " +
      "${loanType.spoken} -- " + share.spokenPercent()

  /**
   * OUR gap at one loan type, said as ours: this school's filing ANSWERS the
   * column and we could not read the answer. It never says the school reports
   * nothing -- the school does report it, and D7 forbids handing our failure
   * to the school.
   */
  fun notReadByUsLabel(loanType: LoanType): String =
    "This filing answers what its graduates borrowed in ${loanType.spoken}, and WE could not read that answer " +
      "out of it -- say that the figure is missing from our data, never that this school does not report it"

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
        notReadByUsLabel(loanType)
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
        // AGENTLESS on purpose (RFC 177), with a publisher in hand: the BLOCK
        // HEADER this sentence is said under already names the school's Common
        // Data Set as the publisher, so passing MoneySource.COMMON_DATA_SET
        // would name the same document twice in one block ("... Common Data
        // Set reports these figures ... The school's own Common Data Set
        // withholds ...").
        //
        // "This filing", not the citation restated (RFC 190): the filing is
        // named once, in [blockHeader], and the sentence is unreachable
        // without it.
        "This filing answers what its graduates borrowed in ${loanType.spoken} and no figure is shown here: " +
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
  fun graduatingClassNotReadByUsLabel(): String =
    "This filing reports the size of its graduating class and WE could not read that answer out of it, so no " +
      "share of graduates who borrowed can be given here -- say that the figure is missing from our data, " +
      "never that this school does not report it"

  /**
   * The absence sentence for the private figure, said under the block's own
   * attribution like every other sentence here: it says what this school's
   * FILING does not carry, never that its graduates borrowed no private money.
   * No source publishes a negative.
   */
  fun privateNotFiledLabel(): String =
    "This filing reports no average for private loans, so these figures are not the whole of what its " +
      "graduates borrowed"

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
