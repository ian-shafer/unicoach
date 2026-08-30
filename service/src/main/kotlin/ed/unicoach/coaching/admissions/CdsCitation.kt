package ed.unicoach.coaching.admissions

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * The citation one CDS section carries (RFC 148 D2), and the one renderer that
 * puts it on the wire. Unlike the Scorecard, whose whole payload comes from one
 * federal file, every CDS fact is a different school's own document in its own
 * cycle -- so the citation rides per college per section, never once per
 * payload. Type and renderer live in this one file so no section has to reach
 * into another section's wire object ([MeritAidWire]) to cite itself.
 *
 * [citedAs] is COMPUTED, never passed in: it is a SPOKEN string the coach reads
 * aloud verbatim, and holding [collegeName] and [sourceYear] instead of the
 * finished sentence is what makes "the cycle is never a bare year" a property
 * of the type rather than a convention every construction site has to keep. A
 * lone `2024` under a bare key is a number with no measure meaning, exactly
 * what the RFC 143 guard exists to catch.
 *
 * [url] is the school's own publication and is wildly heterogeneous -- PDFs,
 * spreadsheets, signed CDN links over a kilobyte long -- so no renderer may
 * assume it is short or human-readable. [archiveUrl] is the corpus's tidy
 * permalink and is nullable in schema, so the null case is handled by omitting
 * the key rather than by emitting an empty string.
 */
data class CdsCitation(
  val collegeName: String,
  val sourceYear: Int,
  val url: String,
  val archiveUrl: String?,
) {
  /** "Amherst College's 2024-25 Common Data Set" -- the sentence fragment the coach says. */
  val citedAs: String get() = "$collegeName's ${cycleLabel(sourceYear)} Common Data Set"

  companion object {
    /**
     * A CDS cycle names the NEXT calendar year by its last two digits, zero
     * padded: 2024 -> "2024-25", 2099 -> "2099-00". The rule is named rather
     * than written twice inside one expression, so the modulus and the width
     * cannot drift apart.
     */
    private const val TWO_DIGIT_YEAR_MODULUS = 100

    /**
     * The spoken cycle label for a CDS `source_year`: 2024 -> "2024-25". The
     * one home for that rendering, so no call site invents a bare year.
     * [Locale.US] is pinned because the label is spoken beside numbers the
     * payload carries, and an ambient locale would render the two differently.
     */
    fun cycleLabel(sourceYear: Int): String = "$sourceYear-%02d".format(Locale.US, (sourceYear + 1) % TWO_DIGIT_YEAR_MODULUS)
  }
}

/** The per-section citation on the wire (D2); a null archive copy omits the key rather than emitting an empty string. */
fun JsonObjectBuilder.putCitation(citation: CdsCitation) {
  put("cited_as", citation.citedAs)
  put("url", citation.url)
  citation.archiveUrl?.let { put("archive_url", it) }
}
