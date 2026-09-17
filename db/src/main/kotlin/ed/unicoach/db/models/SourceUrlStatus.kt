package ed.unicoach.db.models

/**
 * Whether a college's own published file was still served when we last looked
 * (RFC 200), backing `external_documents.url_status` (RFC 228).
 *
 * The Family Cost Report links each Common Data Set citation to the college's
 * own filing (RFC 196), and `AidPolicyWire` hands the coach that url beside the
 * byte-frozen sentence "We link to the college's own file so you can see it."
 * Measured 2026-09-13 (db/seed/cds/link-status.csv): of 441 distinct
 * documents in the committed seed, 417 are FOUND, 15 answer 404 in two
 * independent passes, and 9 are UNKNOWN -- we asked and got no usable answer.
 * This is the recorded answer, written at
 * INGEST by `bin/cds-link-sweep` and never asked at render -- a family must
 * never wait on a college's web server.
 *
 * THREE states, not four. An earlier draft carried a fourth for "we asked and
 * got no usable answer"; it did no work, because it behaves exactly like "we
 * never asked" -- both KEEP the link -- and the two are already told apart by
 * `source_url_checked_on` beside it:
 *
 *     UNKNOWN + no date   -- we never asked
 *     UNKNOWN + a date    -- we asked and got no usable answer
 *     FOUND / NOT_FOUND   -- we asked, and the date says when
 *
 * So RFC 182's rule still holds -- a check that quietly did not run must never
 * look like one that passed -- while the enum stays the size of its own
 * question.
 *
 * The names mirror the HTTP codes they are derived from (404 IS Not Found), so
 * whoever debugs a row later can see the mapping without a table.
 *
 * ONLY [NOT_FOUND] drops an anchor. Every other state keeps the link, because a
 * check that could not reach the network is OUR gap and never the school's
 * silence (RFC 166 s6): a bot-blocked university is not a dead filing.
 */
enum class SourceUrlStatus(
  val value: String,
) {
  /** We have no verdict: either we never asked, or we asked and got no usable answer. Keeps the link. */
  UNKNOWN("unknown"),

  /** The school's own file was served when we looked. Keeps the link. */
  FOUND("found"),

  /** The school's own file answered 404 or 410 in BOTH passes. The ONE state that drops the anchor. */
  NOT_FOUND("not_found"),
  ;

  /**
   * Whether this verdict must say WHEN it was reached -- the Kotlin half of
   * `source_documents_checked_when_known`.
   *
   * An exhaustive `when` with no `else`, and written ONCE: both the row type
   * ([NewSourceDocument]) and the sidecar reader ask this question, and a
   * status added later must ANSWER it here rather than inheriting an answer
   * from a `!= UNKNOWN`. You cannot claim to know without saying when you
   * looked; only "we have no verdict" is allowed to have no day.
   */
  val requiresCheckedOn: Boolean
    get() =
      when (this) {
        UNKNOWN -> false
        FOUND -> true
        NOT_FOUND -> true
      }

  companion object {
    /**
     * The status behind a stored value, or null where the string is none of
     * ours.
     *
     * Unlike [AssuranceTier], this one IS persisted and read back, so a decoder
     * is a decoder with a reader: `SourceDocumentsDao` builds it from the
     * column on every citation read.
     */
    fun fromValue(value: String): SourceUrlStatus? = entries.find { it.value == value }
  }
}
