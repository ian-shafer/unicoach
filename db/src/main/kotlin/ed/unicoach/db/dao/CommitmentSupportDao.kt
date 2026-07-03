package ed.unicoach.db.dao

import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.CommitmentSupport
import ed.unicoach.db.models.NewCommitmentSupport
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `commitment_support` link log (RFC 93).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The log is insert-only; [link] is idempotent so re-citing the same
 * claim for the same commitment is a no-op, never a duplicate-key error —
 * identical to [ClaimSupportDao.link].
 */
object CommitmentSupportDao : Creatable<NewCommitmentSupport, CommitmentSupport> {
  private fun mapSupport(rs: ResultSet): CommitmentSupport =
    CommitmentSupport(
      commitmentId = CommitmentId(UUID.fromString(rs.getString("commitment_id"))),
      claimId = ClaimId(UUID.fromString(rs.getString("claim_id"))),
      createdAt = rs.getInstant("created_at"),
    )

  /**
   * Links a claim to a commitment, idempotently. A first insert returns the new
   * row; a repeat (the composite PK already exists) hits `ON CONFLICT DO
   * NOTHING`, so RETURNING yields nothing and the existing row is read back —
   * the call is a no-op success either way.
   */
  fun link(
    session: SqlSession,
    commitmentId: CommitmentId,
    claimId: ClaimId,
  ): Result<CommitmentSupport> {
    val insert =
      session.mutateReturning(
        """
        INSERT INTO commitment_support (commitment_id, claim_id)
        VALUES (?, ?)
        ON CONFLICT (commitment_id, claim_id) DO NOTHING
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, commitmentId.value)
          stmt.setObject(2, claimId.value)
        },
        map = ::mapSupport,
        mapError = ::mapSupportError,
        onNoRow = { ConflictNoOp },
      )
    return insert.recoverCatching { error ->
      if (error === ConflictNoOp) {
        readExisting(session, commitmentId, claimId).getOrThrow()
      } else {
        throw error
      }
    }
  }

  override fun create(
    session: SqlSession,
    input: NewCommitmentSupport,
  ): Result<CommitmentSupport> = link(session, input.commitmentId, input.claimId)

  private fun readExisting(
    session: SqlSession,
    commitmentId: CommitmentId,
    claimId: ClaimId,
  ): Result<CommitmentSupport> =
    session.queryOne(
      "SELECT * FROM commitment_support WHERE commitment_id = ? AND claim_id = ?",
      bind = { stmt ->
        stmt.setObject(1, commitmentId.value)
        stmt.setObject(2, claimId.value)
      },
      map = ::mapSupport,
    )

  /** The claims backing a commitment (the "what backs this commitment" read). */
  fun listClaimsForCommitment(
    session: SqlSession,
    commitmentId: CommitmentId,
  ): Result<List<Claim>> =
    session.queryList(
      """
      SELECT c.* FROM commitment_support cs
      JOIN claims c ON c.id = cs.claim_id
      WHERE cs.commitment_id = ?
      ORDER BY c.created_at, c.id
      """.trimIndent(),
      bind = { it.setObject(1, commitmentId.value) },
      map = ClaimsDao::mapClaim,
    )

  /**
   * The commitments a claim backs — the exact reverse of [listClaimsForCommitment].
   * Joins `commitments` on `commitment_support.commitment_id`, served by
   * `commitment_support_claim_idx`, ordered `created_at, id`. Drives stale-drop
   * and the read-only admin surface (RFC 77).
   */
  fun listCommitmentsForClaim(
    session: SqlSession,
    claimId: ClaimId,
  ): Result<List<Commitment>> =
    session.queryList(
      """
      SELECT c.* FROM commitment_support cs
      JOIN commitments c ON c.id = cs.commitment_id
      WHERE cs.claim_id = ?
      ORDER BY c.created_at, c.id
      """.trimIndent(),
      bind = { it.setObject(1, claimId.value) },
      map = CommitmentsDao::mapCommitment,
    )

  /** Sentinel marking the idempotent no-op insert (existing row read back via [readExisting]). */
  private object ConflictNoOp : Exception()

  private fun mapSupportError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("commitment_support_commitment_id_fkey") -> NotFoundException("Commitment not found")
          message.contains("commitment_support_claim_id_fkey") -> NotFoundException("Claim not found")
          else -> NotFoundException()
        }
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
