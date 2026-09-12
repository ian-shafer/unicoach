package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.LoanTypeBorrowing
import kotlin.test.Test
import kotlin.test.assertEquals
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
 */
class BorrowingWireTest {
  private val citation =
    CdsCitation(
      collegeName = "Ashford College",
      sourceYear = 2024,
      url = "https://example.test/cds.pdf",
      archiveUrl = "https://archive.example.test/cds.pdf",
    )

  private fun coverage() =
    BorrowingCoverage.Reported(
      figures =
        BorrowingAtGraduation(
          graduatingClass = 1000,
          byLoanType =
            mapOf(
              LoanType.FEDERAL to
                LoanTypeBorrowing(
                  averageDebtUsd = 20747,
                  borrowers = BorrowerCounts.Counted(borrowers = 394, graduatingClass = 1000),
                ),
            ),
          source = citation,
        ),
      gapStatusByLoanType = emptyMap(),
      graduatingClassGapStatus = null,
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
  fun `the chat payload still says the header as one string`() {
    // The wire is unchanged by RFC 196: the model reads the whole sentence in
    // one key, and the citation object beside it carries both urls as before.
    val payload = BorrowingWire.objectOf(coverage()).toString()

    assertTrue(payload.contains(BorrowingWire.blockHeader(citation)), "the header is not on the wire: [$payload]")
    assertTrue(payload.contains("https://example.test/cds.pdf"), "source_url is not on the wire: [$payload]")
    assertTrue(payload.contains("https://archive.example.test/cds.pdf"), "archive_url stays on the wire: [$payload]")
  }
}
