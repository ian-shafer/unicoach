package ed.unicoach.db.dao

import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimSupport
import ed.unicoach.db.models.NewClaimSupport
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `claim_support` link log (RFC 66).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The log is insert-only; [link] is idempotent so re-citing the same
 * observation for the same claim is a no-op, never a duplicate-key error.
 */
object ClaimSupportDao : Creatable<NewClaimSupport, ClaimSupport> {
  private fun mapSupport(rs: ResultSet): ClaimSupport =
    ClaimSupport(
      claimId = ClaimId(UUID.fromString(rs.getString("claim_id"))),
      observationId = ObservationId(rs.getLong("observation_id")),
      createdAt = rs.getInstant("created_at"),
    )

  private fun mapObservation(rs: ResultSet): Observation =
    Observation(
      id = ObservationId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      studentId =
        ed.unicoach.db.models
          .StudentId(UUID.fromString(rs.getString("student_id"))),
      convoId =
        ed.unicoach.db.models
          .ConvoId(UUID.fromString(rs.getString("convo_id"))),
      sourceRequestId =
        ed.unicoach.db.models
          .ConvoRequestId(rs.getLong("source_request_id")),
      utteredAt = rs.getInstant("uttered_at"),
      quote = rs.getString("quote"),
    )

  /**
   * Links an observation to a claim, idempotently. A first insert returns the
   * new row; a repeat (the composite PK already exists) hits `ON CONFLICT DO
   * NOTHING`, so RETURNING yields nothing and the existing row is read back —
   * the call is a no-op success either way.
   */
  fun link(
    session: SqlSession,
    claimId: ClaimId,
    observationId: ObservationId,
  ): Result<ClaimSupport> {
    val insert =
      session.mutateReturning(
        """
        INSERT INTO claim_support (claim_id, observation_id)
        VALUES (?, ?)
        ON CONFLICT (claim_id, observation_id) DO NOTHING
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, claimId.value)
          stmt.setLong(2, observationId.value)
        },
        map = ::mapSupport,
        mapError = ::mapSupportError,
        onNoRow = { ConflictNoOp },
      )
    return insert.recoverCatching { error ->
      if (error === ConflictNoOp) {
        readExisting(session, claimId, observationId).getOrThrow()
      } else {
        throw error
      }
    }
  }

  override fun create(
    session: SqlSession,
    input: NewClaimSupport,
  ): Result<ClaimSupport> = link(session, input.claimId, input.observationId)

  private fun readExisting(
    session: SqlSession,
    claimId: ClaimId,
    observationId: ObservationId,
  ): Result<ClaimSupport> =
    session.queryOne(
      "SELECT * FROM claim_support WHERE claim_id = ? AND observation_id = ?",
      bind = { stmt ->
        stmt.setObject(1, claimId.value)
        stmt.setLong(2, observationId.value)
      },
      map = ::mapSupport,
    )

  /** The observations backing a claim (the "what backs this claim" read). */
  fun listObservationsForClaim(
    session: SqlSession,
    claimId: ClaimId,
  ): Result<List<Observation>> =
    session.queryList(
      """
      SELECT o.* FROM claim_support cs
      JOIN observations o ON o.id = cs.observation_id
      WHERE cs.claim_id = ?
      ORDER BY o.created_at, o.id
      """.trimIndent(),
      bind = { it.setObject(1, claimId.value) },
      map = ::mapObservation,
    )

  /**
   * The claims an observation supports — the exact reverse of
   * [listObservationsForClaim]. Joins `claims` on `claim_support.claim_id`, served
   * by `claim_support_observation_idx`, ordered `created_at, id`. Read-only admin
   * surface (RFC 77).
   */
  fun listClaimsForObservation(
    session: SqlSession,
    observationId: ObservationId,
  ): Result<List<Claim>> =
    session.queryList(
      """
      SELECT c.* FROM claim_support cs
      JOIN claims c ON c.id = cs.claim_id
      WHERE cs.observation_id = ?
      ORDER BY c.created_at, c.id
      """.trimIndent(),
      bind = { it.setLong(1, observationId.value) },
      map = ClaimsDao::mapClaim,
    )

  /** Sentinel marking the idempotent no-op insert (existing row read back via [readExisting]). */
  private object ConflictNoOp : Exception()

  private fun mapSupportError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("claim_support_claim_id_fkey") -> NotFoundException("Claim not found")
          message.contains("claim_support_observation_id_fkey") -> NotFoundException("Observation not found")
          else -> NotFoundException()
        }
      }

      else -> mapDatabaseError(e)
    }
}
