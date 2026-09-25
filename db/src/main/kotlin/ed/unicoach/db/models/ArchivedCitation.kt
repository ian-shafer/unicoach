package ed.unicoach.db.models

import ed.unicoach.common.util.requireCitedPageUrl

/**
 * An archived address a published fact was read THROUGH, and the sweep's
 * verdict on THAT address (RFC 200, RFC 228).
 *
 * ONE VALUE, so a row cannot carry an address with no verdict or a verdict
 * with no address: the LEFT join to `external_documents` finds an archived
 * document or it does not, and the two halves of that answer arrive together.
 * Held apart as two nullables, two of the four combinations the types admit
 * are meaningless, and every reader has to be told in prose which two are
 * real.
 */
data class ArchivedCitation(
  val url: String,
  /** What the link sweep last answered for [url]. Whether that is an anchor is [SourceUrlStatus]'s rule. */
  val urlStatus: SourceUrlStatus,
) {
  init {
    // ABSOLUTE, AND NOT https-ONLY, through the one home for that rule
    // ([requireCitedPageUrl]): `external_documents.url` accepts http by an
    // explicit decision, six of the archived addresses the committed seed
    // already holds are plain `http://web.archive.org/...`, and a stricter bar
    // here faults the WHOLE deadline read of a table that is allowed to
    // contain them -- a branded 503 for all fifty-one jurisdictions.
    requireCitedPageUrl(url, "an archived citation")
  }
}
