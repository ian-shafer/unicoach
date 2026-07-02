package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntrySupport
import ed.unicoach.db.models.NewCollegeListEntrySupport
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `college_list_entry_support` link log
 * (RFC 91). Stateless `object`, one [SqlSession] per call, transaction
 * boundaries owned by the caller. Mirrors [ClaimSupportDao] exactly: the log is
 * insert-only; [link] is idempotent so re-citing the same observation for the
 * same entry is a no-op, never a duplicate-key error.
 */
object CollegeListEntrySupportDao : Creatable<NewCollegeListEntrySupport, CollegeListEntrySupport> {
  private fun mapSupport(rs: ResultSet): CollegeListEntrySupport =
    CollegeListEntrySupport(
      entryId = CollegeListEntryId(UUID.fromString(rs.getString("entry_id"))),
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
   * Links an observation to a college-list entry, idempotently. A first insert
   * returns the new row; a repeat (the composite PK already exists) hits `ON
   * CONFLICT DO NOTHING`, so RETURNING yields nothing and the existing row is
   * read back -- the call is a no-op success either way.
   */
  fun link(
    session: SqlSession,
    entryId: CollegeListEntryId,
    observationId: ObservationId,
  ): Result<CollegeListEntrySupport> {
    val insert =
      session.mutateReturning(
        """
        INSERT INTO college_list_entry_support (entry_id, observation_id)
        VALUES (?, ?)
        ON CONFLICT (entry_id, observation_id) DO NOTHING
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, entryId.value)
          stmt.setLong(2, observationId.value)
        },
        map = ::mapSupport,
        mapError = ::mapSupportError,
        onNoRow = { ConflictNoOp },
      )
    return insert.recoverCatching { error ->
      if (error === ConflictNoOp) {
        readExisting(session, entryId, observationId).getOrThrow()
      } else {
        throw error
      }
    }
  }

  override fun create(
    session: SqlSession,
    input: NewCollegeListEntrySupport,
  ): Result<CollegeListEntrySupport> = link(session, input.entryId, input.observationId)

  private fun readExisting(
    session: SqlSession,
    entryId: CollegeListEntryId,
    observationId: ObservationId,
  ): Result<CollegeListEntrySupport> =
    session.queryOne(
      "SELECT * FROM college_list_entry_support WHERE entry_id = ? AND observation_id = ?",
      bind = { stmt ->
        stmt.setObject(1, entryId.value)
        stmt.setLong(2, observationId.value)
      },
      map = ::mapSupport,
    )

  /** The observations backing an entry (the "what backs this entry" read). */
  fun listObservationsForEntry(
    session: SqlSession,
    entryId: CollegeListEntryId,
  ): Result<List<Observation>> =
    session.queryList(
      """
      SELECT o.* FROM college_list_entry_support cls
      JOIN observations o ON o.id = cls.observation_id
      WHERE cls.entry_id = ?
      ORDER BY o.created_at, o.id
      """.trimIndent(),
      bind = { it.setObject(1, entryId.value) },
      map = ::mapObservation,
    )

  /**
   * The college-list entries an observation supports -- the exact reverse of
   * [listObservationsForEntry]. Joins `college_list_entries` on
   * `college_list_entry_support.entry_id`, served by
   * `college_list_entry_support_observation_idx`, ordered `created_at, id`.
   * Read-only admin surface.
   */
  fun listEntriesForObservation(
    session: SqlSession,
    observationId: ObservationId,
  ): Result<List<CollegeListEntry>> =
    session.queryList(
      """
      SELECT cle.* FROM college_list_entry_support cls
      JOIN college_list_entries cle ON cle.id = cls.entry_id
      WHERE cls.observation_id = ?
      ORDER BY cle.created_at, cle.id
      """.trimIndent(),
      bind = { it.setLong(1, observationId.value) },
      map = CollegeListEntriesDao::mapEntry,
    )

  /** Sentinel marking the idempotent no-op insert (existing row read back via [readExisting]). */
  private object ConflictNoOp : Exception()

  private fun mapSupportError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("college_list_entry_support_entry_id_fkey") -> NotFoundException("College list entry not found")
          message.contains("college_list_entry_support_observation_id_fkey") -> NotFoundException("Observation not found")
          else -> NotFoundException()
        }
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
