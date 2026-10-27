package ed.unicoach.family

import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The two subject-scoped tools (RFC 258 §5) over the real database: each
 * refuses a severed link with the one not-found error object, and
 * `family_cost_picture` renders the shared picture through the cost tool's own
 * payload builder, labelling a withheld subject.
 */
class SubjectScopedToolTest {
  private val database = CostsTestDb.database
  private val sqlSession = CostsTestDb.sqlSession
  private val costs = CollegeCostService(database)
  private val sharingPermissions = SharingPermissionService(database)
  private val subjects = SubjectReadService(database, CollegeListService(database), costs, sharingPermissions)
  private val supporters = SupporterService(database, LinkEndedNotice(QueueService(database)))
  private val fafsaDeadlines = FafsaDeadlineService(database)

  private val costPicture = FamilyCostPictureChatTool(subjects)
  private val subjectDeadlines = SubjectFafsaDeadlineChatTool(fafsaDeadlines, subjects)

  private val notFound = buildJsonObject { put("error", JsonPrimitive(SubjectScopedChatTool.STUDENT_NOT_FOUND_REASON)) }

  @BeforeEach
  fun reset() {
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

  private fun linkedAdult(studentId: StudentId): Pair<UserId, SupporterStudentLink> {
    val userId = createUser()
    val supporter = SupportersDao.create(sqlSession, NewSupporter(userId)).getOrThrow()
    val link =
      SupporterStudentLinksDao
        .create(sqlSession, NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.PARENT))
        .getOrThrow()
    return userId to link
  }

  private fun run(
    tool: SubjectScopedChatTool,
    viewer: UserId,
    studentId: StudentId,
  ): JsonObject = runBlocking { tool.execute(Subject(viewer, studentId), buildJsonObject {}) }

  private fun sever(
    studentId: StudentId,
    link: SupporterStudentLink,
  ) {
    assertIs<UnlinkOutcome.Unlinked>(runBlocking { supporters.disconnectAsStudent(studentId, link.id).getOrThrow() })
  }

  @Test
  fun `family_cost_picture answers the not-found error object for a severed link`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Severed U"))
    val (adult, link) = linkedAdult(student)
    assertNull(run(costPicture, adult, student)["error"], "linked, it answers")

    sever(student, link)

    assertEquals(notFound, run(costPicture, adult, student))
  }

  @Test
  fun `fafsa_deadlines answers the not-found error object for a severed link`() {
    val student = CostsTestDb.createStudent()
    val (adult, link) = linkedAdult(student)
    assertNull(run(subjectDeadlines, adult, student)["error"], "linked, it answers")

    sever(student, link)

    assertEquals(notFound, run(subjectDeadlines, adult, student))
  }

  @Test
  fun `a never-linked viewer is refused by both, in the same words`() {
    val student = CostsTestDb.createStudent()
    val stranger = createUser()

    assertEquals(notFound, run(costPicture, stranger, student))
    assertEquals(notFound, run(subjectDeadlines, stranger, student))
  }

  @Test
  fun `family_cost_picture labels a withheld subject and renders the cost tool's own payload`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Withheld U"))
    val (adult, _) = linkedAdult(student)

    val payload = run(costPicture, adult, student)

    assertEquals(JsonPrimitive(false), payload[FamilyCostPictureChatTool.INTENDED_SUBJECTS_SHARED_KEY])
    val withheld = assertIs<SharedCostPicture.SubjectsWithheld>(picture(adult, student))
    assertEquals(
      JsonObject(
        CollegeCostChatTool.payloadOf(withheld.profile) + (FamilyCostPictureChatTool.INTENDED_SUBJECTS_SHARED_KEY to JsonPrimitive(false)),
      ),
      payload,
      "the same builder college_cost_profile renders through, plus the label",
    )
  }

  @Test
  fun `family_cost_picture carries no withheld label when the student shared their subjects`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Shared U"))
    runBlocking {
      sharingPermissions.set(student, SharingPermission.INTENDED_SUBJECTS, SharingPermissionStatus.GRANTED).getOrThrow()
    }
    val (adult, _) = linkedAdult(student)

    val payload = run(costPicture, adult, student)

    assertFalse(FamilyCostPictureChatTool.INTENDED_SUBJECTS_SHARED_KEY in payload)
    val full = assertIs<SharedCostPicture.Full>(picture(adult, student))
    assertEquals(CollegeCostChatTool.payloadOf(full.profile), payload)
  }

  @Test
  fun `the subject fafsa_deadlines answers exactly what the student's own tool answers`() {
    val student = CostsTestDb.createStudent()
    val (adult, _) = linkedAdult(student)
    val studentsOwn =
      runBlocking { FafsaDeadlineChatTool(fafsaDeadlines, MoneyProfileService(database)).execute(student, buildJsonObject {}) }

    assertEquals(studentsOwn, run(subjectDeadlines, adult, student), "one shared function, one residency, one answer")
  }

  private fun picture(
    viewer: UserId,
    studentId: StudentId,
  ): SharedCostPicture =
    assertIs<SubjectRead.Found<SharedCostPicture>>(
      runBlocking {
        subjects.costPicture(viewer, studentId).getOrThrow()
      },
    ).value
}
