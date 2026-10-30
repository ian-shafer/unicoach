package ed.unicoach.family

import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
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
    val (adult, link) = FamilyTestDb.linkedAdult(student)
    assertNull(FamilyTestDb.runTool(costPicture, adult, student)["error"], "linked, it answers")

    sever(student, link)

    assertEquals(notFound, FamilyTestDb.runTool(costPicture, adult, student))
  }

  @Test
  fun `fafsa_deadlines answers the not-found error object for a severed link`() {
    val student = CostsTestDb.createStudent()
    val (adult, link) = FamilyTestDb.linkedAdult(student)
    assertNull(FamilyTestDb.runTool(subjectDeadlines, adult, student)["error"], "linked, it answers")

    sever(student, link)

    assertEquals(notFound, FamilyTestDb.runTool(subjectDeadlines, adult, student))
  }

  @Test
  fun `a never-linked viewer is refused by both, in the same words`() {
    val student = CostsTestDb.createStudent()
    val stranger = FamilyTestDb.createUser()

    assertEquals(notFound, FamilyTestDb.runTool(costPicture, stranger, student))
    assertEquals(notFound, FamilyTestDb.runTool(subjectDeadlines, stranger, student))
  }

  @Test
  fun `family_cost_picture labels a withheld subject and renders the cost tool's own payload`() {
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Withheld U"))
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val payload = FamilyTestDb.runTool(costPicture, adult, student)

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
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val payload = FamilyTestDb.runTool(costPicture, adult, student)

    assertFalse(FamilyCostPictureChatTool.INTENDED_SUBJECTS_SHARED_KEY in payload)
    val full = assertIs<SharedCostPicture.Full>(picture(adult, student))
    assertEquals(CollegeCostChatTool.payloadOf(full.profile), payload)
  }

  @Test
  fun `the subject fafsa_deadlines answers exactly what the student's own tool answers`() {
    val student = CostsTestDb.createStudent()
    val (adult, _) = FamilyTestDb.linkedAdult(student)
    val studentsOwn =
      runBlocking { FafsaDeadlineChatTool(fafsaDeadlines, MoneyProfileService(database)).execute(student, buildJsonObject {}) }

    assertEquals(studentsOwn, FamilyTestDb.runTool(subjectDeadlines, adult, student), "one shared function, one residency, one answer")
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
