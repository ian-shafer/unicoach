package ed.unicoach.db.models

/** Insert input for a `commitment_support` link; omits the DB-generated `created_at`. */
data class NewCommitmentSupport(
  val commitmentId: CommitmentId,
  val claimId: ClaimId,
)
