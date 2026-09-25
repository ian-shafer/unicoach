package ed.unicoach.coaching.aid

import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaDocument
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.SourceUrlStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The FAFSA process corpus as a set of DOCUMENTS (RFC 238), and the corpus of
 * agencies whose own pages we could not read.
 *
 * `FafsaProcessChatToolTest` already proves what the coach is handed. This
 * suite proves the two properties the PUBLIC page depends on and the coach does
 * not: that a document is identified by its url, so a reference list can carry
 * one reading per page; and that every rule's `cited_as` is still the publisher
 * and the document joined, with the part of the document the rule is in.
 */
class FafsaProcessCopyTest {
  @Test
  fun `every url names exactly one document, so a reference list can hold one reading per page`() {
    val byUrl = FafsaProcessCopy.DOCUMENTS.groupBy { it.url }

    assertEquals(
      emptyList(),
      byUrl.filterValues { it.size > 1 }.keys.toList(),
      "one url carries two documents, which a page's reference list refuses at render time",
    )
    assertEquals(
      FafsaProcessCopy.RULES
        .map { it.url }
        .distinct()
        .sorted(),
      byUrl.keys.sorted(),
      "the document list and the rules do not cite the same set of pages",
    )
  }

  @Test
  fun `two rules citing one statute page keep their own subsections`() {
    val statuteRules =
      FafsaProcessCopy.rulesOf(FafsaProcessCopy.FafsaProcessTopic.THE_FORM_IS_FREE)

    val citations = statuteRules.map { it.citedAs }.distinct()
    assertEquals(
      listOf(FafsaProcessCopy.STATUTE.url),
      statuteRules.map { it.url }.distinct(),
      "the statute rules no longer share one page, so the page's reference list would carry it twice",
    )
    assertTrue(
      citations.any { it.endsWith("20 U.S.C. § 1090(d)(3)") },
      "the free-form rule lost its own subsection: [$citations]",
    )
    assertTrue(
      citations.any { it.contains("§ 1090(d)(2)(C)") },
      "the preparer rule lost the subsection that carries the duty to disclose: [$citations]",
    )
  }

  @Test
  fun `the document type changed no citation the coach emits`() {
    // TYPED, NOT DERIVED. These are the eight `cited_as` strings the coach
    // emitted BEFORE RFC 238 made the documents a type, copied from the
    // corpus as it stood at main@8c7d4b27. Recomputing them from
    // `rule.document` would assert the implementation against itself: a
    // dropped comma, a renamed publisher or a lost pinpoint would move both
    // sides together, and "not a byte changes on the wire" -- the whole
    // justification for the refactor -- would be unasserted.
    val before =
      setOf(
        "Federal Student Aid, Dear Colleague Letter GEN-23-11, FAFSA Simplification Act Changes for " +
          "Implementation in the 2024-25 Award Year",
        "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 1",
        "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 2",
        "Federal Student Aid, 2024-25 FSA Handbook, Application and Verification Guide, Ch. 2",
        "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 4",
        "Federal Student Aid, 2025-26 FSA Handbook, Application and Verification Guide, Ch. 5",
        "Cornell Law School Legal Information Institute, 20 U.S.C. § 1090(d)(3)",
        "Cornell Law School Legal Information Institute, 20 U.S.C. § 1090(d)(2), free preparation " +
          "authorized; the duty to tell a family the form is free is § 1090(d)(2)(C)",
      )

    assertEquals(
      before,
      FafsaProcessCopy.RULES.map { it.citedAs }.toSet(),
      "the refactor changed what the coach cites",
    )
  }

  @Test
  fun `a document with no publisher, no title or no absolute address does not construct`() {
    assertFailsWith<IllegalArgumentException> { FafsaDocument("", "A title", "https://example.test/x") }
    assertFailsWith<IllegalArgumentException> { FafsaDocument("A publisher", "  ", "https://example.test/x") }
    // A RELATIVE address, not an http one: the address rule has one home in
    // `:common` and states the bar the store keeps (http accepted, https
    // preferred). Every document in this corpus is https in fact, and the test
    // above says so; a reader's guard stricter than the corpus is what deleted a
    // served state from `/fafsa`.
    assertFailsWith<IllegalArgumentException> { FafsaDocument("A publisher", "A title", "example.test/x") }
  }

  @Test
  fun `every agency we could not read names itself and links an https page we opened`() {
    val agencies = FafsaUnreadableAgencies.BY_JURISDICTION

    assertTrue(agencies.isNotEmpty(), "the corpus is empty, so nothing below proves anything")
    agencies.forEach { (jurisdiction, agency) ->
      assertTrue(agency.publisher.isNotBlank(), "[${jurisdiction.uspsCode}] names no agency to ask")
      assertTrue(agency.url.startsWith("https://"), "[${jurisdiction.uspsCode}] links no https page")
      assertEquals(
        FafsaUnreadableAgencies.CHECKED_ON,
        agency.checkedOn,
        "[${jurisdiction.uspsCode}] states a reading date the corpus did not make",
      )
    }
  }

  /**
   * The duplicate the corpus used to absorb. `associate` keeps the LAST pair for
   * a repeated key and says nothing, which is one agency's page quietly standing
   * in for another's -- a family sent to the wrong state.
   *
   * The assertion this replaces read the MAP's own keys, so both sides moved
   * together and it could not fail.
   */
  @Test
  fun `two entries claiming one jurisdiction are refused, not silently collapsed`() {
    val agency =
      FafsaUnreadableAgencies.UnreadableAgency(
        FafsaDocument("An Agency", "Financial Aid", "https://example.test/x"),
        urlStatus = SourceUrlStatus.FOUND,
      )

    val failure =
      assertFailsWith<IllegalArgumentException> {
        FafsaUnreadableAgencies.keyedByJurisdiction(listOf("AR" to agency, "AR" to agency))
      }

    assertTrue(failure.message!!.contains("AR"), "the refusal does not name the jurisdiction claimed twice")
  }

  /**
   * The guard is [FafsaDocument]'s and is INHERITED, not copied: an agency entry
   * carries a document, so an entry that names nobody or links no absolute page
   * fails on the one guard every cited page in this package goes through -- which
   * is `requireCitedPageUrl`, in `:common`, and not a copy of it here.
   */
  @Test
  fun `an agency entry that names nobody, or links no absolute page, does not construct`() {
    assertFailsWith<IllegalArgumentException> {
      FafsaUnreadableAgencies.UnreadableAgency(
        FafsaDocument("", "Financial Aid", "https://example.test/x"),
        urlStatus = SourceUrlStatus.FOUND,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FafsaUnreadableAgencies.UnreadableAgency(
        FafsaDocument("An Agency", "Financial Aid", "example.test/x"),
        urlStatus = SourceUrlStatus.FOUND,
      )
    }
  }

  /**
   * RFC 200 at this corpus: an entry records what `bin/check-link` answered, and
   * the ANCHOR is that verdict's own rule. A 403 is a state agency refusing our
   * client, which is not a grave, so all four still earn a link.
   */
  @Test
  fun `every agency entry states a swept verdict, and no verdict withholds the link`() {
    FafsaUnreadableAgencies.BY_JURISDICTION.forEach { (jurisdiction, agency) ->
      assertTrue(
        agency.urlStatus.rendersAsAnchor,
        "[${jurisdiction.uspsCode}] records a verdict that withholds the link, so D9 names a page nobody can open",
      )
    }
  }

  @Test
  fun `the agency corpus is keyed by a published jurisdiction, and answers for it`() {
    FafsaUnreadableAgencies.BY_JURISDICTION.keys.forEach { jurisdiction ->
      assertNotNull(
        FafsaUnreadableAgencies.of(jurisdiction),
        "[${jurisdiction.uspsCode}] is in the corpus and `of` does not answer for it",
      )
    }
    val served = assertNotNull(DeadlineJurisdiction.State.of("CA"))
    assertEquals(null, FafsaUnreadableAgencies.of(served), "a state whose deadlines we did read claims an agency we could not")
  }
}
