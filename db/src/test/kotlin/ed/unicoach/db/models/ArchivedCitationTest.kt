package ed.unicoach.db.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ArchivedCitation]: the archived address a published fact was read THROUGH,
 * and the sweep's verdict on THAT address (RFC 200, RFC 228).
 *
 * The address bar is the whole subject. This value is built by the deadline
 * mapper on a read path with NO failure branch, so a guard stricter than the
 * column it is read from does not cost one archive link -- it faults the whole
 * read and answers the public page and the coach's tool with a 503 for every
 * jurisdiction.
 */
class ArchivedCitationTest {
  @Test
  fun `a plain-http archived address is accepted, because the column it is read from holds them`() {
    // `external_documents.url` accepts http by an explicit decision, and the
    // committed link-status seed holds `http://web.archive.org/...` rows.
    val citation =
      ArchivedCitation(
        "http://web.archive.org/web/20250724085032/https://ksrevisor.gov/statutes/chapters/ch72/072_051_0079.html",
        SourceUrlStatus.FOUND,
      )

    assertTrue(citation.url.startsWith("http://"), "the citation did not keep the address it was given")
    assertEquals(SourceUrlStatus.FOUND, citation.urlStatus, "the citation did not keep the verdict it was given")
  }

  @Test
  fun `an address with no scheme is refused, and the refusal names it`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        ArchivedCitation("web.archive.org/web/2026/aid.example.gov", SourceUrlStatus.FOUND)
      }

    assertTrue(
      failure.message!!.contains("web.archive.org"),
      "the refusal does not name the address it refused: [${failure.message}]",
    )
  }
}
