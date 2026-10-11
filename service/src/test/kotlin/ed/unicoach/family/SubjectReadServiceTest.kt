package ed.unicoach.family

import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.collegelist.SharedCollegeListEntry
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The subject seam (RFC 250) over the real database, on the [CostsTestDb]
 * fixture so the cost picture has real colleges and a real program return to
 * withhold. Which refusal causes answer NotFound is the DAO's matrix
 * (`SupporterStudentLinksDaoTest`); this suite proves the seam asks it on every
 * call and reads what it authorised.
 */
class SubjectReadServiceTest {
  private val database = CostsTestDb.database
  private val sqlSession = CostsTestDb.sqlSession
  private val costs = CollegeCostService(database)
  private val sharingPermissions = SharingPermissionService(database)
  private val supporters = SupporterService(database, LinkEndedNotice(QueueService(database)))
  private val service = SubjectReadService(database, CollegeListService(database), costs, sharingPermissions)

  @BeforeEach
  fun reset() {
    // `students` and `users` cascade to supporters, links and sharing permissions.
    CostsTestDb.reset()
  }

  private fun createUser(): UserId {
    val userId = UUID.randomUUID()
    sqlSession
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Parent Jane', 'hash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "parent-$userId@example.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  /** A fresh adult account with a live link to [studentId]. */
  private fun linkedAdult(studentId: StudentId): Pair<UserId, SupporterStudentLink> {
    val userId = createUser()
    val supporter = SupportersDao.create(sqlSession, NewSupporter(userId)).getOrThrow()
    val link =
      SupporterStudentLinksDao
        .create(sqlSession, NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.PARENT))
        .getOrThrow()
    return userId to link
  }

  private fun collegeList(
    viewer: UserId,
    studentId: StudentId,
  ): SubjectRead<List<SharedCollegeListEntry>> = runBlocking { service.collegeList(viewer, studentId).getOrThrow() }

  private fun costPicture(
    viewer: UserId,
    studentId: StudentId,
  ): SubjectRead<SharedCostPicture> = runBlocking { service.costPicture(viewer, studentId).getOrThrow() }

  private fun foundPicture(
    viewer: UserId,
    studentId: StudentId,
  ): SharedCostPicture = assertIs<SubjectRead.Found<SharedCostPicture>>(costPicture(viewer, studentId)).value

  private fun answerSharing(
    studentId: StudentId,
    status: SharingPermissionStatus,
  ) = runBlocking { sharingPermissions.set(studentId, SharingPermission.INTENDED_SUBJECTS, status).getOrThrow() }

  @Test
  fun `a linked adult reads the list as a projection without the student's reasons`() {
    val student = CostsTestDb.createStudent()
    val collegeId = CostsTestDb.seedCollege("Shared U")
    val entry =
      CollegeListEntriesDao
        .create(
          sqlSession,
          NewCollegeListEntry(student, collegeId, CollegeListEntryStatus.APPLYING, "I loved the campus tour", LivingArrangement.ON_CAMPUS),
        ).getOrThrow()
    val (adult, _) = linkedAdult(student)

    val found = assertIs<SubjectRead.Found<List<SharedCollegeListEntry>>>(collegeList(adult, student))

    assertEquals(
      listOf(SharedCollegeListEntry(collegeId, "Shared U", CollegeListEntryStatus.APPLYING, LivingArrangement.ON_CAMPUS, entry.updatedAt)),
      found.value,
    )
  }

  @Test
  fun `the very next call after an unlink is NotFound, so nothing is cached`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Severed U"))
    val (adult, link) = linkedAdult(student)
    assertIs<SubjectRead.Found<*>>(collegeList(adult, student))
    assertIs<SubjectRead.Found<*>>(costPicture(adult, student))

    assertIs<UnlinkOutcome.Unlinked>(runBlocking { supporters.disconnectAsStudent(student, link.id).getOrThrow() })

    assertEquals(SubjectRead.NotFound, collegeList(adult, student))
    assertEquals(SubjectRead.NotFound, costPicture(adult, student))
  }

  @Test
  fun `an adult with no link reads nothing, and neither does an id that was never a student`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Private U"))
    linkedAdult(CostsTestDb.createStudent())

    val stranger = createUser()
    assertEquals(SubjectRead.NotFound, collegeList(stranger, student))
    assertEquals(SubjectRead.NotFound, costPicture(stranger, student))
    assertEquals(SubjectRead.NotFound, collegeList(stranger, StudentId(UUID.randomUUID())), "an id that was never a student")
  }

  /**
   * The cost picture is the same computation the student's own read makes,
   * and the program return — keyed by what the student wants to study — rides
   * only on a GRANT. Without one the answer SAYS it withheld them, so a reader
   * never mistakes "not shared" for "not answered". The fixture arms the
   * section first, so its absence is the seam's doing and not an empty
   * fixture's.
   */
  @Test
  fun `the cost picture carries the program return only when intended_subjects is granted`() {
    CostsTestDb.seedSubject("nursing", "Nursing", "51")
    val student = CostsTestDb.createStudent()
    val collegeId = CostsTestDb.seedCollege("Program U")
    CostsTestDb.addToCollegeList(student, collegeId)
    CostsTestDb.seedProgramMoney(CostsTestDb.seedProgram(collegeId, cipCode = "5101"))
    CostsTestDb.answerIntendedSubjects(student, "nursing")
    val (adult, _) = linkedAdult(student)

    val full = runBlocking { costs.getForStudent(student).getOrThrow() }
    assertNotNull(full.programReturn, "the fixture must arm the section, or withholding it proves nothing")

    // Compared as rendered text: the profile carries served-figure evidence
    // that is not value-equal across two reads, while its rendering is.
    val neverAsked = assertIs<SharedCostPicture.SubjectsWithheld>(foundPicture(adult, student), "never asked is not a grant")
    assertNull(neverAsked.profile.programReturn)
    assertEquals(full.copy(programReturn = null).toString(), neverAsked.profile.toString(), "everything else is the student's own picture")

    answerSharing(student, SharingPermissionStatus.REFUSED)
    assertIs<SharedCostPicture.SubjectsWithheld>(foundPicture(adult, student), "a refusal is not a grant")

    answerSharing(student, SharingPermissionStatus.GRANTED)
    val granted = assertIs<SharedCostPicture.Full>(foundPicture(adult, student))
    assertEquals(full.toString(), granted.profile.toString())
  }
}
