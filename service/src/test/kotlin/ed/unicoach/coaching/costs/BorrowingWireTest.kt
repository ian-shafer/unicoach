package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.LoanTypeBorrowing
import ed.unicoach.db.models.SourceUrlStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BorrowingBlock] carries the CITATION, and the two forms of its one
 * attribution sentence cannot drift (RFC 196).
 *
 * RFC 190's guarantee is the thing being protected here, not relaxed: the
 * figures stay unreachable without the claim they are made under, and [blockOf]
 * stays the only way to reach them. What RFC 196 changed is WHAT the attribution
 * is -- a typed [CdsCitation] instead of prose flattened from it in the service,
 * which is what left the page unable to link a filing it was already naming.
 *
 * RFC 260 adds a third seam, [BorrowingWire.rowsOf], and the second half of
 * this file holds it to the first: the rows and the sentences are two readings
 * of one filing and may not narrate it differently.
 */
class BorrowingWireTest {
  private val citation =
    CdsCitation(
      collegeName = "Ashford College",
      sourceYear = 2024,
      url = "https://example.test/cds.pdf",
      archiveUrl = "https://archive.example.test/cds.pdf",
      sourceUrlStatus = SourceUrlStatus.FOUND,
    )

  private fun coverage() =
    reported(
      graduatingClass = 1000,
      byLoanType =
        mapOf(
          LoanType.FEDERAL to
            LoanTypeBorrowing(
              averageDebtUsd = 20747,
              borrowers = BorrowerCounts.Counted(borrowers = 394, graduatingClass = 1000),
            ),
        ),
    )

  private fun reported(
    graduatingClass: Int? = 1000,
    byLoanType: Map<LoanType, LoanTypeBorrowing>,
    gapStatusByLoanType: Map<LoanType, FigureStatus> = emptyMap(),
    graduatingClassGapStatus: FigureStatus? = null,
  ) = BorrowingCoverage.Reported(
    figures = BorrowingAtGraduation(graduatingClass = graduatingClass, byLoanType = byLoanType, source = citation),
    gapStatusByLoanType = gapStatusByLoanType,
    graduatingClassGapStatus = graduatingClassGapStatus,
  )

  @Test
  fun `the block carries the filing itself, so a surface can link it`() {
    val block = BorrowingWire.blockOf(coverage())

    assertEquals(citation, block.citation, "the attribution is the citation, not a string derived from it")
    assertTrue(block.sentences.isNotEmpty(), "a block with no sentences would make the guarantee vacuous")
  }

  @Test
  fun `the linked form and the flat form are one sentence`() {
    val block = BorrowingWire.blockOf(coverage())

    // ONE composition. A surface that renders the claimant as a link prints
    // `citedAs` and then `HEADER_TAIL`; a surface that cannot prints `header`.
    // If these ever differ, the page and the coach are describing one filing in
    // two different sentences.
    assertEquals(block.header, citation.citedAs + BorrowingWire.HEADER_TAIL)
    assertEquals(block.header, BorrowingWire.blockHeader(citation))
    assertTrue(block.header.startsWith("Ashford College's 2024-25 Common Data Set reports these figures"))
  }

  @Test
  fun `the header tail is byte-unchanged by the cohort hedges moving to their own constant`() {
    // RFC 260 extracted [BorrowingWire.COHORT_HEDGES] so the report page's K2
    // sentence can say the same two hedges. The coach's header is built from
    // it now, and must read exactly as it did before the extraction: these are
    // the words the model has been reading, typed out in full here on purpose.
    assertEquals(
      " reports these figures for the students who graduated from it that year -- not this year's freshmen, " +
        "and not all its undergraduates",
      BorrowingWire.HEADER_TAIL,
    )
  }

  @Test
  fun `the chat payload still says the header as one string`() {
    // The wire is unchanged by RFC 196: the model reads the whole sentence in
    // one key, and the citation object beside it carries both urls as before.
    val payload = BorrowingWire.objectOf(coverage()).toString()

    assertTrue(payload.contains(BorrowingWire.blockHeader(citation)), "the header is not on the wire: [$payload]")
    assertTrue(payload.contains("https://example.test/cds.pdf"), "source_url is not on the wire: [$payload]")
    assertTrue(payload.contains("https://archive.example.test/cds.pdf"), "archive_url stays on the wire: [$payload]")
  }

  // ---------------------------------------------------------------------------
  // RFC 260: the rows and the sentences are one filing
  // ---------------------------------------------------------------------------

  /**
   * Every shape a filing can take that [BorrowingCoverage.of] admits as
   * REPORTED, named so a failure says which one: the full pair at two loan
   * types, a federal-only filing, our own unread private cell, the publisher's
   * withholding beside a counted share, a not-applicable cell, the school's own
   * contradiction (D6), a class we could not read, a filing with no class at
   * all, and the any-loan figure said after federal.
   */
  private val fixtures: Map<String, BorrowingCoverage.Reported> =
    mapOf(
      "federal and private, both counted" to
        reported(
          byLoanType =
            mapOf(
              LoanType.FEDERAL to LoanTypeBorrowing(20747, BorrowerCounts.Counted(394, 1000)),
              LoanType.PRIVATE to LoanTypeBorrowing(43865, BorrowerCounts.Counted(83, 1000)),
            ),
        ),
      "federal only" to
        reported(byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, BorrowerCounts.Counted(394, 1000)))),
      "private not read by us" to
        reported(
          byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, BorrowerCounts.Counted(394, 1000))),
          gapStatusByLoanType = mapOf(LoanType.PRIVATE to FigureStatus.NOT_COLLECTED_BY_US),
        ),
      "private average withheld beside a counted private share" to
        reported(
          byLoanType =
            mapOf(
              LoanType.FEDERAL to LoanTypeBorrowing(20747, BorrowerCounts.Counted(394, 1000)),
              LoanType.PRIVATE to LoanTypeBorrowing(null, BorrowerCounts.Counted(83, 1000)),
            ),
          gapStatusByLoanType = mapOf(LoanType.PRIVATE to FigureStatus.SUPPRESSED_BY_PUBLISHER),
        ),
      "a state loan the source says does not apply" to
        reported(
          byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, null)),
          gapStatusByLoanType = mapOf(LoanType.STATE to FigureStatus.NOT_APPLICABLE),
        ),
      "a contradicting federal pair beside its average" to
        reported(
          graduatingClass = 39,
          byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, BorrowerCounts.ContradictsGraduatingClass(2588, 39))),
        ),
      "the graduating class not read by us" to
        reported(
          graduatingClass = null,
          byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, null)),
          graduatingClassGapStatus = FigureStatus.NOT_COLLECTED_BY_US,
        ),
      "no graduating class in the filing" to
        reported(graduatingClass = null, byLoanType = mapOf(LoanType.FEDERAL to LoanTypeBorrowing(20747, null))),
      "any loan and federal, averages only" to
        reported(
          byLoanType =
            mapOf(LoanType.ANY to LoanTypeBorrowing(27202, null), LoanType.FEDERAL to LoanTypeBorrowing(20747, null)),
        ),
    )

  /**
   * The sentences [rows] says the block should speak, in the ORDER the spec
   * fixes: the unread-class sentence first, then each loan type in
   * [LoanType.SPOKEN_ORDER] -- its row's average, then its share, then its gap
   * -- and the private-absence sentence last.
   *
   * The per-type gap is read off the row where the type has one (the status
   * its [OwedAverage.Withheld] carries) and off the filing where it does not
   * (a type with only a gap status gets no row), through the public [gapKey]
   * rule the spec states. The share sentence is rebuilt over
   * [BorrowingRows.graduatingClass], the class the page's column header says,
   * so a row whose share divided by another class would narrate differently
   * here and fail.
   *
   * Each gap sentence carries the [BorrowingGap] it stands for, so the rows'
   * own typed list under the table is checked against the same walk rather
   * than recovered from the words. The unread class is read off the FILING
   * rather than off the rows; the private absence, whose rule is the wire's
   * own, off the rows -- and the block, which asks that rule independently,
   * is what proves it.
   */
  private fun expectedSentencesOf(
    rows: BorrowingRows,
    coverage: BorrowingCoverage.Reported,
  ): List<Narrated> =
    buildList {
      if (coverage.graduatingClassNotReadByUs) add(gapOf(BorrowingGap.GraduatingClassNotReadByUs, rows))
      LoanType.SPOKEN_ORDER.forEach { type ->
        val row = rows.rows.singleOrNull { it.loanType == type }
        (row?.averageOwed as? OwedAverage.Shown)?.let { add(Narrated(BorrowingWire.averageDebtLabel(type, it.usd), gap = null)) }
        row?.borrowed?.let { borrowed ->
          val counted =
            BorrowerCounts.Counted(
              borrowers = borrowed.borrowers,
              graduatingClass = requireNotNull(rows.graduatingClass) { "a share is shown with no class to divide by" },
            )
          add(Narrated(BorrowingWire.shareLabel(type, counted, borrowed.share), gap = null))
        }
        val gap =
          if (row == null) {
            coverage.gapStatusByLoanType[type]?.takeIf { BorrowingWire.gapKey(type, it) != null }
          } else {
            (row.averageOwed as? OwedAverage.Withheld)?.status
          }
        gap?.let { add(gapOf(BorrowingGap.LoanTypeStatus(type, it), rows)) }
      }
      if (BorrowingGap.PrivateNotFiled in rows.gaps) add(gapOf(BorrowingGap.PrivateNotFiled, rows))
    }

  /** A gap of the expected story, worded by the wire's one gap-to-words seam. */
  private fun gapOf(
    gap: BorrowingGap,
    rows: BorrowingRows,
  ): Narrated = Narrated(BorrowingWire.sentenceOf(gap, rows.citation), gap)

  /** One sentence of the expected story, and the gap it says under the table -- null for a figure said in a cell. */
  private data class Narrated(
    val text: String,
    val gap: BorrowingGap?,
  )

  @Test
  fun `rowsOf and blockOf imply the same sentences in the same order for every fixture`() {
    fixtures.forEach { (name, coverage) ->
      val block = BorrowingWire.blockOf(coverage)
      val rows = BorrowingWire.rowsOf(coverage)

      assertEquals(coverage.figures.source, rows.citation, "[$name] the rows carry the filing they are read from")
      assertTrue(rows.rows.isNotEmpty(), "[$name] a REPORTED filing always has a row, or the seam is vacuous")
      // The block's sentences are exactly what the rows imply, in the order
      // the spec fixes -- nothing more, nothing fewer, nothing moved. One
      // filing, one story, two layouts.
      val expected = expectedSentencesOf(rows, coverage)
      assertEquals(expected.map { it.text }, block.sentences, "[$name] the rows and the block narrate one filing differently")
      // And the rows' own gap list is that story minus the figure sentences,
      // in the same order: the typed gaps exactly, and their words -- through
      // the one gap-to-words seam -- exactly the block's.
      assertEquals(expected.mapNotNull { it.gap }, rows.gaps, "[$name] the gaps under the table")
      assertEquals(
        expected.filter { it.gap != null }.map { it.text },
        rows.gaps.map { BorrowingWire.sentenceOf(it, rows.citation) },
        "[$name] the gap sentences under the table",
      )
      assertEquals(
        rows.rows.map { it.loanType },
        LoanType.SPOKEN_ORDER.filter { type -> rows.rows.any { it.loanType == type } },
        "[$name] rows are in SPOKEN_ORDER",
      )
      // The two non-loan gaps agree with the sentences they stand for, checked
      // against the BLOCK rather than against the rows' own words.
      assertEquals(
        block.sentences.contains(BorrowingWire.privateNotFiledLabel()),
        BorrowingGap.PrivateNotFiled in rows.gaps,
        "[$name] the private absence gap",
      )
      assertEquals(
        block.sentences.contains(BorrowingWire.graduatingClassNotReadByUsLabel()),
        BorrowingGap.GraduatingClassNotReadByUs in rows.gaps,
        "[$name] the H.401 gap",
      )
      assertEquals(coverage.figures.graduatingClass, rows.graduatingClass, "[$name] the class is the filing's")
    }
  }

  @Test
  fun `a loan type with only a gap status has no row and one gap sentence`() {
    val rows = BorrowingWire.rowsOf(fixtures.getValue("private not read by us"))

    assertEquals(listOf(LoanType.FEDERAL), rows.rows.map { it.loanType }, "the unread private cell is not a row")
    assertEquals(
      listOf(BorrowingGap.LoanTypeStatus(LoanType.PRIVATE, FigureStatus.NOT_COLLECTED_BY_US)),
      rows.gaps,
      "and it is said once, under the table, as OUR gap -- and only that: an unread cell is not the school's " +
        "silence, so the private-absence claim is not made",
    )
    assertEquals(
      BorrowingWire.notReadByUsLabel(LoanType.PRIVATE),
      BorrowingWire.sentenceOf(rows.gaps.single(), rows.citation),
      "in OUR voice",
    )
  }

  @Test
  fun `a withheld average beside a counted share is a row with a share and a gap status`() {
    val rows = BorrowingWire.rowsOf(fixtures.getValue("private average withheld beside a counted private share"))
    val private = rows.rows.single { it.loanType == LoanType.PRIVATE }

    assertEquals(
      BorrowedShare(borrowers = 83, share = requireNotNull(BorrowerCounts.Counted(83, 1000).share)),
      private.borrowed,
      "the counted pair's count and share are on the row",
    )
    // The status of the missing average, and its sentence resolved once, at
    // construction -- the frozen agentless words, never re-worded here.
    assertEquals(
      OwedAverage.Withheld(
        FigureStatus.SUPPRESSED_BY_PUBLISHER,
        requireNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER)),
      ),
      private.averageOwed,
    )
    assertEquals(listOf(BorrowingGap.LoanTypeStatus(LoanType.PRIVATE, FigureStatus.SUPPRESSED_BY_PUBLISHER)), rows.gaps)
  }

  @Test
  fun `a contradicting pair yields no borrowers on its row and the average still stands`() {
    val rows = BorrowingWire.rowsOf(fixtures.getValue("a contradicting federal pair beside its average"))
    val federal = rows.rows.single()

    assertEquals(LoanType.FEDERAL, federal.loanType)
    assertEquals(OwedAverage.Shown(20747), federal.averageOwed, "the average does not divide by the contradicted class (D6)")
    assertNull(federal.borrowed, "the contradiction is held and never said")
    // Nothing is said about the filing's arithmetic. The one sentence under
    // the table is the private-absence claim, which this federal-only filing
    // earns exactly as it does without the contradiction.
    assertEquals(listOf<BorrowingGap>(BorrowingGap.PrivateNotFiled), rows.gaps)
  }

  @Test
  fun `the row speaks the loan type in the wire's own words`() {
    val rows = BorrowingWire.rowsOf(fixtures.getValue("federal and private, both counted"))

    assertEquals(listOf("federal loans", "a private loan"), rows.rows.map { it.spokenLoanType })
    rows.rows.forEach { row ->
      assertTrue(
        BorrowingWire.averageDebtLabel(row.loanType, 1).contains(" ${row.spokenLoanType} "),
        "the row's words are the sentence's words: [${row.spokenLoanType}]",
      )
    }
  }
}
