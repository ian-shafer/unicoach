package ed.unicoach.coaching.admissions

import ed.unicoach.db.models.SourceUrlStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
  private fun citation(
    url: String,
    sourceUrlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
  ) = CdsCitation(
    collegeName = "Ashford College",
    sourceYear = 2024,
    url = url,
    archiveUrl = null,
    sourceUrlStatus = sourceUrlStatus,
  )

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
  fun `a filing whose own file is gone has no link target, and every other status keeps one`() {
    // RFC 200 D5. Only the recorded 404 verdict drops the anchor: an
    // unreachable check is OUR gap and never the school's silence (RFC 166 s6),
    // so UNKNOWN -- "we never asked", or "we asked and got no usable answer" --
    // links exactly as it did before this RFC existed.
    val url = "https://example.test/cds.pdf"

    assertNull(citation(url, SourceUrlStatus.NOT_FOUND).linkTargetOrNull(), "a 404 filing must not be linked")
    assertEquals(url, citation(url, SourceUrlStatus.FOUND).linkTargetOrNull())
    assertEquals(url, citation(url, SourceUrlStatus.UNKNOWN).linkTargetOrNull())
    // The citation itself is unchanged by the verdict: the family still learns
    // whose claim it is and in which cycle. Only the anchor goes.
    assertEquals("Ashford College's 2024-25 Common Data Set", citation(url, SourceUrlStatus.NOT_FOUND).citedAs)
  }

  @Test
  fun `a gone filing answers no link rather than faulting, even with a poisoned scheme`() {
    // The ORDER of the two checks, asserted rather than assumed: NOT_FOUND is
    // read FIRST, so a dead url that is also poisoned answers "no link" instead
    // of stopping a whole page over a link nobody would have rendered.
    // `assertNull` alone would pass if the call threw... it would not: a thrown
    // exception fails the test, which is exactly the property being asserted.
    assertNull(citation("javascript:alert(1)", SourceUrlStatus.NOT_FOUND).linkTargetOrNull())

    // And the RFC 196 fault is NOT weakened for anything we would still link.
    assertFailsWith<UnlinkableSourceUrlException> { citation("javascript:alert(1)", SourceUrlStatus.FOUND).linkTargetOrNull() }
    assertFailsWith<UnlinkableSourceUrlException> { citation("javascript:alert(1)", SourceUrlStatus.UNKNOWN).linkTargetOrNull() }
    // `linkTarget()` itself is untouched by RFC 200: a gone filing still faults
    // if somebody asks for the target directly, because that caller asked for a
    // link and this url cannot be one.
    assertFailsWith<UnlinkableSourceUrlException> { citation("javascript:alert(1)", SourceUrlStatus.NOT_FOUND).linkTarget() }
    assertNotNull(citation("https://example.test/cds.pdf", SourceUrlStatus.NOT_FOUND).linkTarget())
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
