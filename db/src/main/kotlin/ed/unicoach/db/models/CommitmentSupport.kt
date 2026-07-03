package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `commitment_support` link log (RFC 93): the immutable
 * fact that a claim was cited as basis for a commitment. A pure link with a
 * composite key `(commitmentId, claimId)`; it carries no surrogate id.
 */
data class CommitmentSupport(
  val commitmentId: CommitmentId,
  val claimId: ClaimId,
  override val createdAt: Instant,
) : Created
