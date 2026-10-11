package ed.unicoach.db.dao

import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionId
import ed.unicoach.db.models.SharingPermissionRow
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Version
import java.sql.ResultSet
import java.util.UUID

/**
 * Data-access layer over `sharing_permissions` (RFC 248): what a student allows
 * the people they share with to see of their own data. Stateless `object`, one
 * [SqlSession] per call, transaction boundaries owned by the caller — the
 * [IntendedSubjectsDao] shape.
 *
 * A student who was never asked has NO row, and that absence is the third
 * state: [findLive] returns null for it. There is no write that creates "never
 * asked" and no clear; a student withdraws by writing
 * [SharingPermissionStatus.REFUSED].
 */
object SharingPermissionsDao {
  private const val TABLE = "sharing_permissions"
  private const val VERSIONS_TABLE = "sharing_permissions_versions"

  /**
   * Reconstructs one row, from either the live table or its history.
   *
   * An unknown stored `permission` or `status` is row corruption and throws
   * naming the column and the row — never softened to "not granted", which
   * would quietly withdraw a grant the student gave, or to "never asked", which
   * would have a later surface ask a question already answered.
   */
  private fun mapRow(
    rs: ResultSet,
    table: String,
  ): SharingPermissionRow {
    val id = rs.getString("id")
    val rawPermission = rs.getString("permission")
    val rawStatus = rs.getString("status")
    return SharingPermissionRow(
      id = SharingPermissionId(UUID.fromString(id)),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      permission =
        SharingPermission.fromValue(rawPermission)
          ?: throw corruptValue(rawPermission, "SharingPermission", "$table.permission (row [$id])"),
      status =
        SharingPermissionStatus.fromValue(rawStatus)
          ?: throw corruptValue(rawStatus, "SharingPermissionStatus", "$table.status (row [$id])"),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )
  }

  /** The student's live answer to [permission], or null when they were never asked. */
  fun findLive(
    session: SqlSession,
    studentId: StudentId,
    permission: SharingPermission,
  ): Result<SharingPermissionRow?> =
    session
      .queryOne(
        """
        SELECT * FROM sharing_permissions
        WHERE student_id = ? AND permission = ? AND deleted_at IS NULL
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.setString(2, permission.value)
        },
        map = { mapRow(it, TABLE) },
      ).orNullOnNotFound()

  /**
   * Every live answer the student has given, ordered by the stored permission
   * string (not [SharingPermission] enum order). Empty for a student never asked anything.
   */
  fun listLiveForStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<SharingPermissionRow>> =
    session.queryList(
      """
      SELECT * FROM sharing_permissions
      WHERE student_id = ? AND deleted_at IS NULL
      ORDER BY permission
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = { mapRow(it, TABLE) },
    )

  /**
   * Stores the student's answer to [permission], in the caller's transaction,
   * and returns the live row.
   *
   * Two statements. The INSERT creates the row at [status] when the student has
   * none; its conflict target is the PARTIAL unique index
   * `sharing_permissions_student_permission_active_idx` — hence the
   * `WHERE deleted_at IS NULL` on the conflict clause — so two writers racing to
   * create the first row do not both succeed and the loser falls through to the
   * UPDATE.
   *
   * The UPDATE then suppresses an unchanged write (`status IS DISTINCT FROM ?`),
   * exactly as `IntendedSubjectsDao.writeStatus` does. That is not an
   * optimisation: every committed UPDATE is a version and a history row, so
   * writing `granted` over `granted` would record a change of mind that did not
   * happen. On the INSERT path the UPDATE matches nothing for the same reason.
   *
   * `version = version + 1` because `enforce_versioning` refuses any UPDATE
   * that does not advance it with a 40001.
   */
  fun set(
    session: SqlSession,
    studentId: StudentId,
    permission: SharingPermission,
    status: SharingPermissionStatus,
  ): Result<SharingPermissionRow> =
    runCatching {
      insertIfAbsent(session, studentId, permission, status).getOrThrow()
      writeStatus(session, studentId, permission, status).getOrThrow()
      findLive(session, studentId, permission).getOrThrow()
        ?: throw NotFoundException(
          "Expected a live sharing_permissions row for student [${studentId.value}] and " +
            "permission [${permission.value}] after writing it; found none",
        )
    }

  private fun insertIfAbsent(
    session: SqlSession,
    studentId: StudentId,
    permission: SharingPermission,
    status: SharingPermissionStatus,
  ): Result<Int> =
    session.execute(
      """
      INSERT INTO sharing_permissions (student_id, permission, status)
      VALUES (?, ?, ?)
      ON CONFLICT (student_id, permission) WHERE deleted_at IS NULL DO NOTHING
      """.trimIndent(),
      mapError = { e ->
        mapChildWriteError(
          e,
          mapOf(STUDENT_FK_CONSTRAINT to "no student [${studentId.value}] to record a sharing permission for"),
        )
      },
    ) { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.setString(2, permission.value)
      stmt.setString(3, status.value)
    }

  private fun writeStatus(
    session: SqlSession,
    studentId: StudentId,
    permission: SharingPermission,
    status: SharingPermissionStatus,
  ): Result<Int> =
    session.execute(
      """
      UPDATE sharing_permissions SET status = ?, version = version + 1
      WHERE student_id = ? AND permission = ? AND deleted_at IS NULL AND status IS DISTINCT FROM ?
      """.trimIndent(),
    ) { stmt ->
      stmt.setString(1, status.value)
      stmt.setObject(2, studentId.value)
      stmt.setString(3, permission.value)
      stmt.setString(4, status.value)
    }

  /** One row's full history, ascending by version. */
  fun listVersions(
    session: SqlSession,
    id: SharingPermissionId,
  ): Result<List<Version<SharingPermissionRow>>> =
    session.queryList(
      "SELECT * FROM sharing_permissions_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = { Version(mapRow(it, VERSIONS_TABLE)) },
    )

  /** The `sharing_permissions.student_id` FK, whose violation is the owning-student-vanished outcome. */
  private const val STUDENT_FK_CONSTRAINT = "sharing_permissions_student_id_fkey"
}
