package ed.unicoach.db.models

/** Insert input for a `claim_support` link; omits the DB-generated `created_at`. */
data class NewClaimSupport(
  val claimId: ClaimId,
  val observationId: ObservationId,
)
