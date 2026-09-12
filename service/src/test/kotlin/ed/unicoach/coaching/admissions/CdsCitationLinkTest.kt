package ed.unicoach.coaching.admissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The one thing a citation's url is allowed to be used for outside the wire: an
 * `href` (RFC 196).
 *
 * The Family Cost Report is served to a logged-out parent, and `href` is the one
 * attribute where escaping does not save you -- `javascript:` in an href is live
 * whatever `kotlinx.html` does to the attribute value. So the scheme is asserted
 * at the type that holds the url, once, rather than trusted at each renderer.
 */
class CdsCitationLinkTest {
  private fun citation(url: String) = CdsCitation(collegeName = "Ashford College", sourceYear = 2024, url = url, archiveUrl = null)

  @Test
  fun `an http or https filing is linkable, verbatim`() {
    // VERBATIM, and long: the corpus's longest `source_url` is 1,744 characters
    // and the type's own KDoc calls them "wildly heterogeneous". Nothing here
    // shortens, normalises or re-encodes one -- it is a target, never text.
    val https = "https://example.test/institutional-research/cds_2024_2025-final-2-12-2025-publish-ready.pdf"
    val http = "http://its.example.test/IntranetUploads/009182-CommonData-8182026115318.pdf"

    assertEquals(https, citation(https).linkTarget())
    assertEquals(http, citation(http).linkTarget())
  }

  @Test
  fun `a url that is not http faults, and is neither dropped nor rendered`() {
    // FAILS CLOSED. The two softer answers are both wrong: a silent drop leaves
    // us saying "we link to the college's own file" beside no link, and
    // rendering it puts an executable url in front of a family.
    val fault = assertFailsWith<UnlinkableSourceUrlException> { citation("javascript:alert(1)").linkTarget() }

    // LOCATED: the message identifies the seed row, not merely the failure.
    assertEquals("Ashford College", fault.collegeName)
    assertEquals(2024, fault.sourceYear)
    assertEquals("javascript:alert(1)", fault.url)
    assertEquals(
      "source_documents url for [Ashford College] [2024-25] is not an http(s) url: [javascript:alert(1)]",
      fault.message,
    )
  }

  @Test
  fun `a scheme-shaped prefix is not a scheme, and case is not a scheme difference`() {
    // `startsWith("https:")` would admit the first two. The check is on the
    // whole `https://` prefix for that reason, and `JavaScript:` is why it is
    // compared case-insensitively rather than by three hand-written variants.
    listOf("https:alert(1)", "http:evil", " https://example.test/cds.pdf", "JavaScript:alert(1)").forEach {
      val fault = assertFailsWith<UnlinkableSourceUrlException>("[$it] must not be linkable") { citation(it).linkTarget() }
      // LOCATED on every arm, not only the first: the property D5a argues for is
      // that the message identifies the seed row, and a fault that named no row
      // would still satisfy an assertion about its type.
      assertEquals(it, fault.url)
      assertEquals("Ashford College", fault.collegeName)
    }
    // A scheme is case-insensitive in RFC 3986, so this is a link that works and
    // refusing it would stop a page over nothing. It is handed over VERBATIM.
    val shouty = "HTTPS://example.test/cds.pdf"
    assertEquals(shouty, citation(shouty).linkTarget())
  }

  @Test
  fun `the wire is untouched by the check`() {
    // The url on the wire is DATA handed to a language model, where no scheme is
    // executable, and a coach answer is not the place to discover a bad seed
    // row. The check is on the link path, which is the path that can hurt
    // somebody -- so a citation the page would refuse still cites itself in
    // chat.
    val poisoned = citation("javascript:alert(1)")
    assertEquals("Ashford College's 2024-25 Common Data Set", poisoned.citedAs)
    assertEquals("javascript:alert(1)", poisoned.url)
  }
}
