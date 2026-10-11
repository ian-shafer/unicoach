package ed.unicoach.coaching

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SharingPermissionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId

/**
 * Domain layer over [SharingPermissionsDao] (RFC 248): what a student allows
 * the people they share with to see of their own data.
 *
 * A `null` status means the student was never asked. It reads as not granted,
 * exactly as [SharingPermissionStatus.REFUSED] does; the two are kept apart so a
 * later surface can ask once without asking again.
 */
class SharingPermissionService(
  private val database: Database,
) {
  /** Every [SharingPermission], in enum order, with the student's answer or `null` when never asked. */
  suspend fun read(studentId: StudentId): Result<Map<SharingPermission, SharingPermissionStatus?>> =
    try {
      database.withConnection { session ->
        SharingPermissionsDao.listLiveForStudent(session, studentId).map { rows ->
          val answers = rows.associate { it.permission to it.status }
          SharingPermission.entries.associateWith { answers[it] }
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /**
   * Whether the student has granted [permission], read in the CALLER's
   * session — so a surface that renders the student's data in one session (the
   * Family Cost Report) decides what to show from the same snapshot it prints.
   * Only [SharingPermissionStatus.GRANTED] grants; never asked does not.
   *
   * Throws on a failed read, like `CollegeCostService.readInSession`: inside the
   * caller's `withConnection` block a throw is what rolls the session back.
   */
  fun isGranted(
    session: SqlSession,
    studentId: StudentId,
    permission: SharingPermission,
  ): Boolean =
    when (SharingPermissionsDao.findLive(session, studentId, permission).getOrThrow()?.status) {
      // Never asked: silence is not consent.
      null -> false

      SharingPermissionStatus.REFUSED -> false

      SharingPermissionStatus.GRANTED -> true
    }

  /** Stores the student's answer to [permission] and returns it as it now stands. Re-sending the current answer writes nothing. */
  suspend fun set(
    studentId: StudentId,
    permission: SharingPermission,
    status: SharingPermissionStatus,
  ): Result<SharingPermissionStatus> =
    try {
      Result.success(
        database.withConnection { session ->
          // `.getOrThrow()` INSIDE the block: `set` is two statements, and
          // `withConnection` rolls back only when the block throws.
          SharingPermissionsDao.set(session, studentId, permission, status).getOrThrow().status
        },
      )
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }
}
