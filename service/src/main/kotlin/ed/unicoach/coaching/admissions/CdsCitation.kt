package ed.unicoach.coaching.admissions

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.isWebUrl
import ed.unicoach.db.models.SourceUrlStatus
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

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
  /**
   * Whether [url] was still served when we last looked (RFC 200), read off the
   * `source_documents` row this citation was built from.
   *
   * REQUIRED, with no default: a default would let a construction site that
   * never plumbed the column through claim the school's file is fine, and the
   * whole point of the column is that only a recorded check may speak. The
   * verdict is established at INGEST by `bin/cds-link-check`; nothing on this
   * path asks the network.
   */
  val sourceUrlStatus: SourceUrlStatus,
) {
  /** "Amherst College's 2024-25 Common Data Set" -- the sentence fragment the coach says. */
  val citedAs: String get() = "$collegeName's ${cycleLabel(sourceYear)} Common Data Set"

  /**
   * [url] as an `href` TARGET, or a LOCATED FAULT (RFC 196).
   *
   * The Family Cost Report is served to a logged-out parent, and `href` is the
   * one attribute where escaping does not save you: `javascript:` in an href is
   * live whatever `kotlinx.html` does to the attribute value. So the scheme is
   * asserted here, at the one type that holds the url, rather than trusted at
   * each renderer.
   *
   * It FAILS CLOSED, and neither of the two softer answers is available to a
   * caller: a silent drop would leave us saying "we link to the college's own
   * file" beside no link, which is the promise RFC 196 exists to keep, and
   * rendering it anyway would put an executable url in front of a parent. The
   * alternative to this check is not "a missing link" -- it is an executable
   * one.
   *
   * Failing closed costs nothing today: all 5,048 rows of the committed CDS
   * seed carry `http`/`https` (RFC 196), so a row that reaches here with any
   * other scheme is evidence the corpus is wrong or poisoned, not a case to
   * degrade gracefully.
   *
   * WHAT COUNTS as a web url is [isWebUrl]'s, in `:common`: the scheme question
   * is domain-agnostic and every `href` in this repo asks it, while WHAT A
   * VIOLATION MEANS is this type's -- a stored Common Data Set row that cannot
   * be linked is corrupt data about a named college, so the fault is located
   * here and not there.
   *
   * A FUNCTION rather than a computed property, because it can fault and a
   * property that throws reads like a field.
   *
   * NOT applied to [putCitation]: the wire hands the url to a language model as
   * data, where no scheme is executable, and a coach answer is not the place to
   * discover a bad seed row. The check is on the LINK path, which is the path
   * that can hurt somebody.
   */
  fun linkTarget(): String =
    if (isWebUrl(url)) {
      url
    } else {
      throw UnlinkableSourceUrlException(collegeName, sourceYear, url)
    }

  /**
   * Whether we are still willing to point a reader at the college's own file
   * (RFC 200) -- the ONE place that question is answered.
   *
   * Every surface that withholds something because the file is gone asks THIS:
   * [linkTargetOrNull] drops the anchor, [putCitation] emits a note instead of
   * a url, and `AidPolicyWire` drops the sentence that promises the link (via
   * `FilingLink`). Written once, because the rule is one rule -- a fourth
   * status, or a second reason to withhold a link, is then added here and
   * nowhere else, instead of being hunted across three spellings in two
   * packages.
   *
   * A PROPERTY and not a function: it cannot fault and it reads as the fact it
   * is, unlike [linkTarget], which can.
   */
  val linksToFile: Boolean get() =
    when (sourceUrlStatus) {
      // EXHAUSTIVE, with no `else`, deliberately. This is the one predicate
      // that decides whether we point a family at a school's file, so a status
      // added later must be ANSWERED here rather than inheriting "yes" from a
      // `!= NOT_FOUND`. That is what makes the claim elsewhere in this RFC --
      // that a fourth answer fails to compile -- true at this seam too.
      SourceUrlStatus.FOUND -> true

      SourceUrlStatus.UNKNOWN -> true

      SourceUrlStatus.NOT_FOUND -> false
    }

  /**
   * The `href` for a filing we are still willing to point at, or null where the
   * college's own file is [SourceUrlStatus.NOT_FOUND] (RFC 200).
   *
   * NOT_FOUND is checked FIRST, before [linkTarget]'s scheme fault, so a dead
   * url that is ALSO poisoned answers "no link" rather than faulting a whole
   * page over a link nobody was going to render. The RFC 196 fault is not
   * weakened by that ordering: it still fires for every url this type is asked
   * to LINK, which is the only path that can hurt somebody.
   *
   * Every other status keeps the link. An unreachable check is OUR gap and
   * never the school's silence (RFC 166 s6) -- a bot-blocked university is not
   * a dead filing -- so only the one recorded 404 verdict drops an anchor.
   */
  fun linkTargetOrNull(): String? = if (linksToFile) linkTarget() else null

  companion object {
    /**
     * The spoken cycle label for a CDS `source_year`: 2024 -> "2024-25".
     *
     * Delegates to [AcademicYear], the shared home for that rendering: an
     * academic year is said the same way whoever published the fact, so this
     * citation states the CDS meaning of the year and borrows nobody's
     * formatter -- and owns nobody else's either.
     */
    fun cycleLabel(sourceYear: Int): String = AcademicYear(sourceYear).label
  }
}

/**
 * A `source_documents` row whose `source_url` cannot be an `href` (RFC 196).
 *
 * LOCATED: it names the college and the cycle whose filing is unlinkable, so the
 * seed row is identifiable from the message alone -- the shape this repo uses
 * for an unmapped source code, rather than a bare failure at render.
 *
 * It reaches a family as the branded 503 the report route already renders for a
 * read fault, which is the intended outcome: the page stops rather than showing
 * a parent a link we are not willing to stand behind.
 */
class UnlinkableSourceUrlException(
  val collegeName: String,
  val sourceYear: Int,
  val url: String,
) : IllegalStateException(
    "source_documents url for [$collegeName] [${CdsCitation.cycleLabel(sourceYear)}] is not an http(s) url: [$url]",
  )

/** The wire key for the sentence said INSTEAD of a url we know is dead (RFC 200). */
const val SOURCE_FILE_NOTE_KEY: String = "source_file_note"

/**
 * What the coach says about a filing whose own file is gone -- a new, unfrozen
 * sentence (RFC 200 D8).
 *
 * SAID, not merely omitted. Dropping the url silently would leave the model
 * free to invent a link to the document it is citing; stating the absence is
 * what makes "we cannot link this one" a fact the coach can pass on. The
 * citation itself is unchanged, so the family still learns whose claim it is
 * and in which cycle.
 */
const val SOURCE_FILE_NOT_FOUND_NOTE: String =
  "The college's own file for this filing is no longer online, so there is nothing to link."

/**
 * The per-section citation on the wire (D2); a null archive copy omits the key
 * rather than emitting an empty string.
 *
 * A [SourceUrlStatus.NOT_FOUND] filing emits [SOURCE_FILE_NOTE_KEY] and NO
 * `url`: a url we recorded as a 404 is not data the coach should be handed to
 * repeat. `archive_url` is unaffected either way -- it is a different document
 * with a different claim behind it (RFC 196 D1), and this RFC does not start
 * offering it in words.
 */
fun JsonObjectBuilder.putCitation(citation: CdsCitation) {
  put("cited_as", citation.citedAs)
  // ASKED, never re-derived: [CdsCitation.linksToFile] is the one place that
  // decides whether we point a reader at this file, so this seam cannot drift
  // from the anchor the page drops or the promise the wire withholds.
  if (citation.linksToFile) {
    put("url", citation.url)
  } else {
    put(SOURCE_FILE_NOTE_KEY, SOURCE_FILE_NOT_FOUND_NOTE)
  }
  citation.archiveUrl?.let { put("archive_url", it) }
}
