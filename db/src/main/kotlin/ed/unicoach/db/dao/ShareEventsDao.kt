package ed.unicoach.db.dao

import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.ShareEvent
import ed.unicoach.db.models.ShareEventId
import ed.unicoach.db.models.ShareEventKind
import ed.unicoach.db.models.StudentId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `share_events` log (RFC 160).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller — which matters here more than usual: the share surface records
 * its event in the same transaction as the row mutation it describes, so the
 * event and the mutation commit or roll back together.
 *
 * The log is insert-only, through two writes mirroring the two shapes the DB
 * CHECK (`share_events_share_id_check`) allows: [record] for the share-bearing
 * kinds, [recordOptOut] for the one shareless kind. [hasOptOut] is the
 * synthesis nudge step's permanent-suppression probe, and [listByStudent]
 * serves tests and the Beat 2 parent-claim reader.
 */
object ShareEventsDao {
  private fun mapEvent(rs: ResultSet): ShareEvent {
    val id = ShareEventId(rs.getLong("id"))
    val studentId = StudentId(UUID.fromString(rs.getString("student_id")))
    return ShareEvent(
      id = id,
      createdAt = rs.getInstant("created_at"),
      studentId = studentId,
      shareId = rs.getString("share_id")?.let { CostReportShareId(UUID.fromString(it)) },
      kind = parseKind(rs.getString("kind"), id, studentId),
    )
  }

  private fun parseKind(
    value: String,
    rowId: ShareEventId,
    studentId: StudentId,
  ): ShareEventKind =
    ShareEventKind.fromValue(value)
      ?: throw SQLException(
        "Persisted share_events.kind is not a valid value for row id=[${rowId.value}] student=[${studentId.asString}]: [$value]",
      )

  /**
   * Appends one share-surface event: a fact about the concrete share row
   * [shareId] names. The guard reads the ALLOWLIST every kind declares at its
   * definition site ([ShareEventKind.namesShareRow]) rather than denying the
   * one known-bad value, so a future shareless kind fails closed here.
   * [ShareEventKind.OPTED_OUT] is the one such kind today and has its own
   * write, [recordOptOut] — so an illegal pairing fails at the call site
   * instead of as a DB CHECK violation.
   */
  fun record(
    session: SqlSession,
    studentId: StudentId,
    shareId: CostReportShareId,
    kind: ShareEventKind,
  ): Result<ShareEvent> {
    require(kind.namesShareRow) {
      "A [${kind.value}] event names no share row; use recordOptOut " +
        "(student=[${studentId.asString}], share=[${shareId.asString}])"
    }
    return insert(session, studentId, shareId, kind)
  }

  /**
   * Appends the student's durable "never ask me again" (RFC 160): the one
   * shareless kind, so no share id is taken.
   */
  fun recordOptOut(
    session: SqlSession,
    studentId: StudentId,
  ): Result<ShareEvent> = insert(session, studentId, shareId = null, kind = ShareEventKind.OPTED_OUT)

  private fun insert(
    session: SqlSession,
    studentId: StudentId,
    shareId: CostReportShareId?,
    kind: ShareEventKind,
  ): Result<ShareEvent> =
    session.insertReturning(
      table = "share_events",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, studentId.value) },
          "share_id" to { stmt, i ->
            if (shareId != null) {
              stmt.setObject(i, shareId.value)
            } else {
              stmt.setNull(i, java.sql.Types.OTHER)
            }
          },
          "kind" to { stmt, i -> stmt.setString(i, kind.value) },
        ),
      map = ::mapEvent,
      mapError = ::mapEventError,
    )

  /** Whether any event of [kind] exists for the student: the generic EXISTS probe. */
  fun has(
    session: SqlSession,
    studentId: StudentId,
    kind: ShareEventKind,
  ): Result<Boolean> =
    session.queryOne(
      "SELECT EXISTS (SELECT 1 FROM share_events WHERE student_id = ? AND kind = ?) AS present",
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setString(2, kind.value)
      },
      map = { rs -> rs.getBoolean("present") },
    )

  /**
   * Whether the student has ever opted out of share offers ("never ask me
   * again"). The opt-out is permanent, so any `opted_out` row suppresses the
   * nudge forever.
   */
  fun hasOptOut(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Boolean> = has(session, studentId, ShareEventKind.OPTED_OUT)

  /**
   * The student's share history, ordered `created_at, id` (served by
   * `share_events_student_idx`). Tests and the Beat 2 parent-claim reader.
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<ShareEvent>> =
    session.queryList(
      """
      SELECT * FROM share_events
      WHERE student_id = ?
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapEvent,
    )

  private val FOREIGN_KEY_MESSAGES =
    mapOf(
      "share_events_student_id_fkey" to "Owning student not found",
      "share_events_share_id_fkey" to "Referenced share not found",
    )

  private fun mapEventError(e: SQLException): Exception = mapChildWriteError(e, FOREIGN_KEY_MESSAGES)
}
