package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `college_list_entry_support` link log (RFC 91): the
 * immutable fact that an observation was cited as support for a college-list
 * entry. A pure link with a composite key `(entryId, observationId)`; it
 * carries no surrogate id. Mirrors [ClaimSupport].
 */
data class CollegeListEntrySupport(
  val entryId: CollegeListEntryId,
  val observationId: ObservationId,
  override val createdAt: Instant,
) : Created
