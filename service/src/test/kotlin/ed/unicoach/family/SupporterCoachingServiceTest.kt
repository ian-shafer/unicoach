package ed.unicoach.family

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.ReplyEvent
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.coaching.testing.TestScoreCopy
import ed.unicoach.coaching.testing.TestScoreService
import ed.unicoach.coaching.testing.TestScoreWrite
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Month
import java.time.Year
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [SupporterCoachingService] (RFC 258) over the real database and a scripted
 * provider: each refusal arm, and what the composed system text carries.
 */
class SupporterCoachingServiceTest {
  private val database = CostsTestDb.database
  private val sqlSession = CostsTestDb.sqlSession
  private val sharingPermissions = SharingPermissionService(database)
  private val subjects =
    SubjectReadService(database, CollegeListService(database), CollegeCostService(database), sharingPermissions)
  private val supporters = SupporterService(database, LinkEndedNotice(QueueService(database)))
  private val config =
    SupporterCoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  /** Every request the provider saw, in order. */
  private val requests = mutableListOf<ChatRequest>()

  private val provider =
    object : ChatProvider {
      override val id: String = "log"

      override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
          requests += request
          val content = Json.parseToJsonElement("""[{"type":"text","text":"Here is what it costs."}]""")
          emit(
            ChatEvent.Completed(
              response = ChatResponse(content, "claude-sonnet-4-6", "end_turn", TokenUsage(3, 5, null, null), "req_1"),
              rawPayload = content,
            ),
          )
        }
    }

  private fun service(budget: ed.unicoach.coaching.budget.BudgetService = generousBudgetService(database)): SupporterCoachingService =
    SupporterCoachingService(
      database,
      LlmCallLog(provider, database),
      config,
      budget,
      subjects,
      supporters,
      SupporterToolRegistry.of(emptyList()),
    )

  @BeforeEach
  fun reset() {
    CostsTestDb.reset()
    requests.clear()
  }

  private fun createUser(name: String = "Parent Jane"): UserId {
    val userId = UUID.randomUUID()
    sqlSession
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, 'hash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "parent-$userId@example.com")
        stmt.setString(3, name)
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  private fun supporterOf(userId: UserId) = SupportersDao.create(sqlSession, NewSupporter(userId)).getOrThrow()

  private fun link(
    userId: UserId,
    studentId: StudentId,
    relationship: SupporterRelationship = SupporterRelationship.GRANDPARENT,
  ): SupporterStudentLink {
    val supporter = SupportersDao.findByUserId(sqlSession, userId).getOrThrow() ?: supporterOf(userId)
    return SupporterStudentLinksDao
      .create(sqlSession, NewSupporterStudentLink(supporter.id, studentId, relationship))
      .getOrThrow()
  }

  private fun studentNamed(name: String): StudentId {
    val student = CostsTestDb.createStudent()
    sqlSession
      .prepareStatement(
        "UPDATE users SET name = ?, version = version + 1 WHERE id = (SELECT user_id FROM students WHERE id = ?)",
      ).use { stmt ->
        stmt.setString(1, name)
        stmt.setObject(2, student.value)
        stmt.executeUpdate()
      }
    return student
  }

  private fun countConvos(): Int =
    sqlSession.prepareStatement("SELECT COUNT(*) FROM convos").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /** Starts and drains a conversation, returning its id. */
  private fun startedConvo(
    userId: UserId,
    studentId: StudentId,
  ): ConvoId {
    val started = assertIs<SupporterTurnStart.Started>(start(userId, studentId))
    runBlocking { assertIs<ReplyEvent.Completed>(started.reply.toList().last()) }
    return started.convo.id
  }

  private fun start(
    userId: UserId,
    studentId: StudentId,
    message: String = "What would her list cost us?",
  ): SupporterTurnStart = runBlocking { service().startConvo(userId, studentId, message).getOrThrow() }

  private fun post(
    userId: UserId,
    convoId: ConvoId,
  ): SupporterTurnStart = runBlocking { service().postTurn(userId, convoId, "And the FAFSA?").getOrThrow() }

  @Test
  fun `a caller with no supporter row is NoSupporter on every method`() {
    val student = CostsTestDb.createStudent()
    val stranger = createUser()

    assertEquals(SupporterTurnStart.NoSupporter, start(stranger, student))
    assertEquals(SupporterTurnStart.NoSupporter, post(stranger, ConvoId(UUID.randomUUID())))
    assertEquals(SupporterConvoList.NoSupporter, runBlocking { service().listConvos(stranger).getOrThrow() })
    assertEquals(SupporterTurns.NoSupporter, runBlocking { service().listTurns(stranger, ConvoId(UUID.randomUUID())).getOrThrow() })
  }

  @Test
  fun `a supporter never linked to the student is StudentNotFound and no convo is created`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    supporterOf(parent)

    assertEquals(SupporterTurnStart.StudentNotFound(StudentRefusal.NO_LIVE_LINK), start(parent, student))
    assertEquals(0, countConvos(), "a refused start leaves no row")
    assertTrue(requests.isEmpty(), "and makes no provider call")
  }

  @Test
  fun `a link severed between two turns refuses the second, and the conversation stays readable`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    val link = link(parent, student)
    val convo = startedConvo(parent, student)

    assertIs<UnlinkOutcome.Unlinked>(runBlocking { supporters.disconnectAsStudent(student, link.id).getOrThrow() })

    assertEquals(SupporterTurnStart.StudentNotFound(StudentRefusal.NO_LIVE_LINK), post(parent, convo))
    val turns = assertIs<SupporterTurns.Found>(runBlocking { service().listTurns(parent, convo).getOrThrow() })
    assertEquals(1, turns.exchanges.size, "the parent's own words stay theirs to read")
    val listed = assertIs<SupporterConvoList.Found>(runBlocking { service().listConvos(parent).getOrThrow() })
    assertEquals(listOf(convo), listed.conversations.map { it.convo.id }, "and stay listed")
  }

  @Test
  fun `a closed student is StudentNotFound`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    link(parent, student)
    val current = StudentsDao.findById(sqlSession, student).getOrThrow()
    StudentsDao.delete(sqlSession, student, current.version).getOrThrow()

    assertEquals(SupporterTurnStart.StudentNotFound(StudentRefusal.NO_LIVE_LINK), start(parent, student))
  }

  @Test
  fun `another supporter's conversation is ConvoNotFound, to post and to read`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    link(parent, student)
    val convo = startedConvo(parent, student)
    val otherParent = createUser("Other Parent")
    link(otherParent, student)

    assertEquals(SupporterTurnStart.ConvoNotFound(ConvoRefusal.OTHER_SUPPORTER), post(otherParent, convo))
    assertEquals(
      SupporterTurns.ConvoNotFound(ConvoRefusal.OTHER_SUPPORTER),
      runBlocking {
        service().listTurns(otherParent, convo).getOrThrow()
      },
    )
  }

  @Test
  fun `the student's own conversation is ConvoNotFound to a supporter of the same account`() {
    val student = CostsTestDb.createStudent()
    val studentsOwn =
      ed.unicoach.db.dao.ConvosDao
        .create(
          sqlSession,
          ed.unicoach.db.models.NewConvo(
            student,
            (
              ed.unicoach.db.models.ConvoName
                .create("Mine") as ed.unicoach.common.models.ValidationResult.Valid
            ).value,
            ed.unicoach.db.models.ConvoParty.Student,
          ),
        ).getOrThrow()
    val parent = createUser()
    link(parent, student)

    assertEquals(SupporterTurnStart.ConvoNotFound(ConvoRefusal.STUDENT_PARTY), post(parent, studentsOwn.id))
  }

  @Test
  fun `an exhausted parent allowance is BudgetExhausted before any convo row exists`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    link(parent, student)

    val outcome = runBlocking { service(exhaustedBudgetService(database)).startConvo(parent, student, "What will it cost?").getOrThrow() }

    assertIs<SupporterTurnStart.BudgetExhausted>(outcome)
    assertEquals(0, countConvos())
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `an empty message is Invalid`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    link(parent, student)

    val outcome = assertIs<SupporterTurnStart.Invalid>(start(parent, student, message = "   "))
    assertEquals("message", outcome.fieldErrors.single().field)
  }

  @Test
  fun `the system text carries the name, the relationship word and the shared list, and nothing else of the student's`() {
    val student = studentNamed("Maya Okafor")
    val college = CostsTestDb.seedCollege("Sentinel State University")
    CostsTestDb.addToCollegeList(student, college, CollegeListEntryStatus.APPLYING)
    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    // The student's own money profile: on file, and never composed in.
    CostsTestDb.answerResidency(student, "WA")
    CostsTestDb.answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    // The student's score, open explicit commitment and claim: each one the
    // student's own coach composes in, and each carrying content the parent's
    // system text must never hold.
    runBlocking {
      TestScoreService(database)
        .write(
          student,
          TestScoreWrite.Record(AdmissionTestScoreComponent.SAT_TOTAL, 1490, PartialDate.YearAndMonth(Year.of(2026), Month.MARCH)),
        ).getOrThrow()
    }
    CommitmentsDao
      .create(
        sqlSession,
        NewCommitment(student, CommitmentLens.GAP, CommitmentDisclosure.EXPLICIT, "COMMITMENT-SENTINEL ask about deadlines"),
      ).getOrThrow()
    ClaimsDao
      .create(
        sqlSession,
        NewClaim(
          student,
          ClaimOrigin.STUDENT_STATED,
          ClaimKind.GOAL,
          ClaimSubject.STUDENT,
          ClaimTopic.ACADEMICS,
          ClaimVisibility.STUDENT_VISIBLE,
          "CLAIM-SENTINEL wants marine biology",
        ),
      ).getOrThrow()
    val parent = createUser()
    link(parent, student, SupporterRelationship.GRANDPARENT)

    startedConvo(parent, student)

    val system = requests.single().system!!
    assertTrue(system.contains("Maya Okafor"), system)
    assertTrue(system.contains("grandparent"), "the relationship word")
    assertTrue(system.contains("- Sentinel State University: applying; living plan at this school: living on campus"), system)
    assertFalse(system.contains("Money profile"), "no money profile block")
    assertFalse(system.contains("state of residency"), "no residency line")
    assertFalse(system.contains(TestScoreCopy.DECLINED_LINE), "no test-score block")
    assertFalse(system.contains("SAT total"), "no test score")
    assertFalse(system.contains("1490"), "no test score")
    assertFalse(system.contains("Since you last spoke"), "no commitments")
    assertFalse(system.contains("COMMITMENT-SENTINEL"), "no commitment")
    assertFalse(system.contains("CLAIM-SENTINEL"), "no claim")
    assertFalse(system.contains("I found a school you'd love"), "no fit suggestion")
    assertFalse(system.contains(college.value.toString()), "no college id: the parent prompt forbids showing one")
  }

  @Test
  fun `an empty list is said in a line, not left out`() {
    val student = CostsTestDb.createStudent()
    val parent = createUser()
    link(parent, student)

    startedConvo(parent, student)

    assertTrue(requests.single().system!!.contains(ed.unicoach.coaching.collegelist.CollegeListBlock.EMPTY_LINE))
  }
}
