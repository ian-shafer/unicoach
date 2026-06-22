package ed.unicoach.db.models

/**
 * Update input for `ClaimsDao.revise`: the next [status], [confidence], and
 * optional [supersededById] for a claim. The DAO sets `superseded_at`/
 * `retracted_at` to match the [status] so the DB lifecycle-consistency CHECKs
 * hold:
 *
 * - [status] `superseded` requires a non-null [supersededById]; `superseded_at`
 *   is stamped to NOW().
 * - [status] `retracted` stamps `retracted_at` to NOW(); [supersededById] stays
 *   null.
 * - [status] `active` clears both pointers.
 */
data class ClaimRevision(
  val status: ClaimStatus,
  val confidence: Int,
  val supersededById: ClaimId? = null,
)
