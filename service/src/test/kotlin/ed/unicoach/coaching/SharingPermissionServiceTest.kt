package ed.unicoach.coaching

import ed.unicoach.db.dao.SharingPermissionsDao
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SharingPermissionService] (RFC 248) over the real database. */
class SharingPermissionServiceTest {
  private val service = SharingPermissionService(CoachingTestDb.database)
  private val intendedSubjects = SharingPermission.INTENDED_SUBJECTS

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("sharing_permissions_versions", "sharing_permissions", "students", "users")
  }

  /** The read [SharingPermissionService.isGranted] makes, in a session this test owns. */
  private fun isGranted(student: StudentId): Boolean =
    runBlocking {
      CoachingTestDb.database.withConnection { session -> service.isGranted(session, student, intendedSubjects) }
    }

  @Test
  fun `read names every permission, null for one never answered`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("sp-read")
      assertEquals(
        SharingPermission.entries.associateWith { null },
        service.read(student).getOrThrow(),
      )

      service.set(student, intendedSubjects, SharingPermissionStatus.REFUSED).getOrThrow()
      assertEquals(SharingPermissionStatus.REFUSED, service.read(student).getOrThrow()[intendedSubjects])
    }

  @Test
  fun `only a grant grants - never asked and refused do not`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("sp-granted")
      assertFalse(isGranted(student), "never asked is not consent")

      assertEquals(
        SharingPermissionStatus.GRANTED,
        service.set(student, intendedSubjects, SharingPermissionStatus.GRANTED).getOrThrow(),
      )
      assertTrue(isGranted(student))

      service.set(student, intendedSubjects, SharingPermissionStatus.REFUSED).getOrThrow()
      assertFalse(isGranted(student), "a withdrawn grant is not a grant")
    }

  @Test
  fun `two students are independent`() =
    runBlocking {
      val granting = CoachingTestDb.createStudent("sp-one")
      val other = CoachingTestDb.createStudent("sp-two")
      service.set(granting, intendedSubjects, SharingPermissionStatus.GRANTED).getOrThrow()

      assertTrue(isGranted(granting))
      assertFalse(isGranted(other))
      assertEquals(null, service.read(other).getOrThrow()[intendedSubjects])
    }

  @Test
  fun `isGranted reads in the caller's session, seeing its uncommitted write`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("sp-session")
      // One `withConnection` block is one transaction, committed only when the
      // block returns: the grant below is uncommitted while both reads run.
      val (seenInSession, seenOutside) =
        CoachingTestDb.database.withConnection { session ->
          SharingPermissionsDao.set(session, student, intendedSubjects, SharingPermissionStatus.GRANTED).getOrThrow()
          service.isGranted(session, student, intendedSubjects) to committedGrantCount(student)
        }
      assertEquals(0, seenOutside, "the grant must still be uncommitted when isGranted reads it")
      assertTrue(seenInSession, "a grant written in the caller's session must be visible to a read in it")
    }

  /** Live grants for [student] as another connection sees them: committed rows only. */
  private fun committedGrantCount(student: StudentId): Int =
    CoachingTestDb.database.createRawConnection().use { conn ->
      conn
        .prepareStatement("SELECT count(*) FROM sharing_permissions WHERE student_id = ? AND status = 'granted'")
        .use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
          }
        }
    }
}
