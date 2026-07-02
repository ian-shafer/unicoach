package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.StudentId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `observations` log (RFC 66). Stateless
 * `object`, one [SqlSession] per call, transaction boundaries owned by the
 * caller. The log is insert-only; the immutability triggers reject any
 * UPDATE/DELETE.
 */
object ObservationsDao :
  Findable<Observation, ObservationId>,
  Listable<Observation>,
  Creatable<NewObservation, Observation> {
  private fun mapObservation(rs: ResultSet): Observation =
    Observation(
      id = ObservationId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      convoId = ConvoId(UUID.fromString(rs.getString("convo_id"))),
      sourceRequestId = ConvoRequestId(rs.getLong("source_request_id")),
      utteredAt = rs.getInstant("uttered_at"),
      quote = rs.getString("quote"),
    )

  /** Appends one observation. Alias of [create] for log-flavoured call sites. */
  fun append(
    session: SqlSession,
    input: NewObservation,
  ): Result<Observation> = create(session, input)

  override fun create(
    session: SqlSession,
    input: NewObservation,
  ): Result<Observation> =
    session.insertReturning(
      table = "observations",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "convo_id" to { stmt, i -> stmt.setObject(i, input.convoId.value) },
          "source_request_id" to { stmt, i -> stmt.setLong(i, input.sourceRequestId.value) },
          "uttered_at" to { stmt, i -> stmt.setObject(i, java.sql.Timestamp.from(input.utteredAt)) },
          "quote" to { stmt, i -> stmt.setString(i, input.quote) },
        ),
      map = ::mapObservation,
      mapError = ::mapObservationError,
    )

  /**
   * Window of observations for a conversation whose `source_request_id` lies in
   * `(afterRequestId, throughRequestId]`, ordered by `created_at, id`.
   */
  fun listByConvoRange(
    session: SqlSession,
    convoId: ConvoId,
    afterRequestId: ConvoRequestId,
    throughRequestId: ConvoRequestId,
  ): Result<List<Observation>> {
    val sql =
      """
      SELECT * FROM observations
      WHERE convo_id = ? AND source_request_id > ? AND source_request_id <= ?
      ORDER BY created_at, id
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setLong(2, afterRequestId.value)
        stmt.setLong(3, throughRequestId.value)
      },
      map = ::mapObservation,
    )
  }

  /** All observations for a student, ordered by `created_at, id`. */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<Observation>> =
    session.queryList(
      "SELECT * FROM observations WHERE student_id = ? ORDER BY created_at, id",
      bind = { it.setObject(1, studentId.value) },
      map = ::mapObservation,
    )

  /** Resolves one observation by id; [NotFoundException] when no row matches. Read-only admin surface (RFC 77). */
  override fun findById(
    session: SqlSession,
    id: ObservationId,
  ): Result<Observation> =
    session.queryOne(
      "SELECT * FROM observations WHERE id = ?",
      bind = { it.setLong(1, id.value) },
      map = ::mapObservation,
    )

  /**
   * One page of observations across all students, ordered `id` (monotonic with
   * insertion on the `BIGINT IDENTITY` key) so paging is deterministic. Read-only
   * admin surface (RFC 77).
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<Observation>> =
    session.queryList(
      """
      SELECT * FROM observations
      ORDER BY id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapObservation,
    )

  /**
   * One bounded page of a student's observations, ordered `created_at, id`
   * (the unbounded overload above is retained). Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<Observation>> =
    session.queryList(
      """
      SELECT * FROM observations
      WHERE student_id = ?
      ORDER BY created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapObservation,
    )

  private fun mapObservationError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("observations_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("observations_convo_id_fkey") -> NotFoundException("Convo not found")
          message.contains("observations_source_request_id_fkey") -> NotFoundException("Source request not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        ConstraintViolationException(e)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
