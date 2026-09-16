package ed.unicoach.db.models

import java.time.LocalDate
import java.util.UUID

/** Surface id for an `external_documents` row, and the reference every citation carries. */
@JvmInline
value class ExternalDocumentId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * Input for upserting one `external_documents` row on its natural key, the url
 * (RFC 213, brief 0010 D26): one published document we cite, and whether it
 * still resolved when we last looked.
 *
 * A URL, AND WHETHER IT STILL RESOLVES. NOTHING ELSE. No kind, no publisher: the
 * format is derivable from the fetch, the genre is a judgement that varies by
 * reader, and the publisher is already known to the FACT (RFC 177 derived
 * attribution per figure), so a publisher here would be a second copy that can
 * disagree with the first.
 *
 * AN ARCHIVED ADDRESS IS AN ORDINARY ROW OF THIS TABLE. To this product a
 * "capture" is the url a human read when the publisher's own page would not open
 * for us; it is HOW the content was reached, not a kind of object. So it is
 * cited like any other document and link-checked like any other, and the row
 * that needed it points at it with a second, nullable reference.
 *
 * There is no read-shaped twin, for [NewSourceDocument]'s reason: nothing
 * fetches a document on its own, and the reads that need the url JOIN this table
 * for it. One arrives with the first caller that needs it.
 *
 * [urlStatus] has NO default, deliberately, and [urlCheckedOn] is not nullable
 * (RFC 220). "We never asked" was a real answer and the database's own default,
 * and that is exactly what made it dangerous: a defaulted status with no date is
 * how a check that never ran comes to look like one that passed (RFC 182). The
 * column is NOT NULL now, so a citation carries the evidence for it or it cannot
 * be stored, and the mistake is a compile error rather than a run-time refusal.
 *
 * What is still sayable is unchanged: UNKNOWN with a date -- we asked and got no
 * usable answer -- is an ordinary value here, and nine of our rows hold it.
 */
data class NewExternalDocument(
  val url: String,
  val urlStatus: SourceUrlStatus,
  val urlCheckedOn: LocalDate,
) {
  init {
    // The Kotlin halves of the table's own CHECKs, so a fixture or a loader is
    // refused where it is written rather than at the statement. There is no
    // third check for a missing date: [urlCheckedOn]'s TYPE is that check now.
    require(url.isNotEmpty()) { "external document url is empty: an empty url is a url-shaped absence" }
    require(url.startsWith("http://") || url.startsWith("https://")) {
      "external document url [$url] has no http(s) scheme: the scheme is stated, never guessed at"
    }
  }
}
