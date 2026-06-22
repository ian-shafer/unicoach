package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `claim_support` link log (RFC 66): the immutable fact
 * that an observation was cited as support for a claim. A pure link with a
 * composite key `(claimId, observationId)`; it carries no surrogate id.
 */
data class ClaimSupport(
  val claimId: ClaimId,
  val observationId: ObservationId,
  override val createdAt: Instant,
) : Created
