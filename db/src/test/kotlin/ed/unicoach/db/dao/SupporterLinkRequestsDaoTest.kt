package ed.unicoach.db.dao

import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.LinkSide
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterLinkRequest
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterLinkRequest
import ed.unicoach.db.models.SupporterLinkRequestResolution
import ed.unicoach.db.models.SupporterLinkRequestState
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-DB coverage for `supporter_link_requests` (RFC 254), on the
 * [SupporterStudentLinksDaoTest] pattern: one connection, a truncate before each
 * test, [StudentRowFixture] for students and a hand-written `users` insert.
 */
class SupporterLinkRequestsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE supporter_link_requests, supporter_student_links, cost_report_shares, supporters, students, users CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createUser(
    name: String = "Request User",
    verified: Boolean = true,
  ): UserId {
    val userId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash, email_verified_at) VALUES (?, ?, ?, 'ahash', ?)")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "request-$userId@test.com")
        stmt.setString(3, name)
        stmt.setObject(4, if (verified) java.sql.Timestamp.from(java.time.Instant.now()) else null)
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  private fun createStudent(userId: UserId = createUser()): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  private fun createSupporter(userId: UserId = createUser()): Supporter = SupportersDao.create(session, NewSupporter(userId)).getOrThrow()

  /** The student's live share — one per student, so a second request of the same student reuses it. */
  private fun shareOf(student: StudentId): CostReportShareId {
    CostReportSharesDao.findLiveByStudent(session, student).getOrThrow()?.let { return it.id }
    val id = CostReportSharesDao.nextId(session).getOrThrow()
    CostReportSharesDao
      .create(session, NewCostReportShare(id, student, TokenHash.fromRawToken("raw-${UUID.randomUUID()}")))
      .getOrThrow()
    return id
  }

  private fun request(
    supporter: Supporter,
    student: StudentId,
  ): SupporterLinkRequest? =
    SupporterLinkRequestsDao.create(session, NewSupporterLinkRequest(supporter.id, student, shareOf(student))).getOrThrow()

  private fun execute(sql: String) = connection.createStatement().use { it.executeUpdate(sql) }

  @Test
  fun `a pair holds one pending request, and a resolved one is history`() {
    val student = createStudent()
    val supporter = createSupporter()

    val first = assertNotNull(request(supporter, student))
    assertEquals(SupporterLinkRequestState.Pending, first.state, "a new request is pending")
    assertNull(request(supporter, student), "a second pending request for the pair is answered null, not written")
    assertEquals(first.id, SupporterLinkRequestsDao.findPendingPair(session, supporter.id, student).getOrThrow()?.id)

    assertNotNull(SupporterLinkRequestsDao.decline(session, first.id, student).getOrThrow())
    assertNotNull(request(supporter, student), "the index is partial: a resolved request does not block a new one")
    assertTrue(SupporterLinkRequestsDao.hasDeclinedPair(session, supporter.id, student).getOrThrow())
  }

  @Test
  fun `who asked, of whom, from which report and when are frozen`() {
    val student = createStudent()
    val pending = assertNotNull(request(createSupporter(), student))
    val other = createSupporter()

    for (assignment in listOf(
      "supporter_id = '${other.id.value}'",
      "student_id = '${createStudent().value}'",
      "cost_report_share_id = '${shareOf(createStudent()).value}'",
      "created_at = NOW() - INTERVAL '1 day'",
    )) {
      val refused =
        assertFailsWith<SQLException> { execute("UPDATE supporter_link_requests SET $assignment WHERE id = '${pending.id.value}'") }
      assertEquals("P0001", refused.sqlState, "[$assignment] must be refused by a guard")
    }

    // This table's own refusals name the row and the value the write attempted,
    // so a bulk statement that trips one says which request and what was written.
    val newSupporter = other.id.value.toString()
    val refused =
      assertFailsWith<SQLException> {
        execute("UPDATE supporter_link_requests SET supporter_id = '$newSupporter' WHERE id = '${pending.id.value}'")
      }
    val message = refused.message.orEmpty()
    assertTrue(message.contains(pending.id.value.toString()) && message.contains(newSupporter), "names row and value: [$message]")
  }

  @Test
  fun `a resolution is written once`() {
    val student = createStudent()
    val pending = assertNotNull(request(createSupporter(), student))

    assertNotNull(SupporterLinkRequestsDao.decline(session, pending.id, student).getOrThrow())
    assertNull(SupporterLinkRequestsDao.decline(session, pending.id, student).getOrThrow(), "a resolved request matches nothing")
    assertEquals(
      emptyList(),
      SupporterLinkRequestsDao.cancelAllPendingFor(session, LinkSide.Student(student)).getOrThrow(),
      "not even a cancel",
    )
    val refused =
      assertFailsWith<SQLException> {
        execute("UPDATE supporter_link_requests SET resolution = 'cancelled' WHERE id = '${pending.id.value}'")
      }
    assertEquals("P0001", refused.sqlState, "and a raw write past the DAO is refused by the guard")
    assertTrue(refused.message.orEmpty().contains("refused write: resolution [cancelled]"), "and names the write: [${refused.message}]")
  }

  @Test
  fun `a request cannot be deleted`() {
    val pending = assertNotNull(request(createSupporter(), createStudent()))

    val refused = assertFailsWith<SQLException> { execute("DELETE FROM supporter_link_requests WHERE id = '${pending.id.value}'") }
    assertEquals("P0001", refused.sqlState)
    assertTrue(refused.message.orEmpty().contains(pending.id.value.toString()), "the refusal names the row: [${refused.message}]")
  }

  @Test
  fun `a student cannot ask to connect with themselves, refused by name`() {
    val user = createUser()
    val student = createStudent(user)
    val supporter = createSupporter(user)

    val refused =
      assertIs<ConstraintViolationException>(
        SupporterLinkRequestsDao.create(session, NewSupporterLinkRequest(supporter.id, student, shareOf(student))).exceptionOrNull(),
      )
    assertEquals("supporter_link_requests_not_self", refused.constraint)
  }

  @Test
  fun `a decline with another student's id changes nothing`() {
    val student = createStudent()
    val pending = assertNotNull(request(createSupporter(), student))

    assertNull(SupporterLinkRequestsDao.decline(session, pending.id, createStudent()).getOrThrow())
    assertNotNull(SupporterLinkRequestsDao.findPendingPair(session, pending.supporterId, student).getOrThrow(), "still pending")
  }

  @Test
  fun `an approval names its link, and the table refuses one without it`() {
    val student = createStudent()
    val supporter = createSupporter()
    val pending = assertNotNull(request(supporter, student))
    val link =
      SupporterStudentLinksDao
        .create(
          session,
          NewSupporterStudentLink(supporter.id, student, SupporterRelationship.SUPPORTER),
        ).getOrThrow()

    val refused =
      assertFailsWith<SQLException> {
        execute("UPDATE supporter_link_requests SET resolution = 'approved', resolved_at = NOW() WHERE id = '${pending.id.value}'")
      }
    assertEquals("23514", refused.sqlState, "an approval without its link is refused by supporter_link_requests_link_check")

    val approved = assertNotNull(SupporterLinkRequestsDao.approve(session, pending.id, student, link.id).getOrThrow())
    assertEquals(link.id, assertIs<SupporterLinkRequestState.Approved>(approved.state).linkId)
  }

  @Test
  fun `every resolution word is one the CHECK admits`() {
    for (resolution in SupporterLinkRequestResolution.entries) {
      val student = createStudent()
      val supporter = createSupporter()
      val pending = assertNotNull(request(supporter, student))
      // Exhaustive, so a new word has to name the write that makes it.
      when (resolution) {
        SupporterLinkRequestResolution.APPROVED -> {
          val link =
            SupporterStudentLinksDao
              .create(session, NewSupporterStudentLink(supporter.id, student, SupporterRelationship.SUPPORTER))
              .getOrThrow()
          assertIs<SupporterLinkRequestState.Approved>(
            assertNotNull(SupporterLinkRequestsDao.approve(session, pending.id, student, link.id).getOrThrow()).state,
          )
        }

        SupporterLinkRequestResolution.DECLINED -> {
          assertIs<SupporterLinkRequestState.Declined>(
            assertNotNull(SupporterLinkRequestsDao.decline(session, pending.id, student).getOrThrow()).state,
          )
        }

        SupporterLinkRequestResolution.CANCELLED -> {
          assertIs<SupporterLinkRequestState.Cancelled>(
            SupporterLinkRequestsDao
              .cancelAllPendingFor(session, LinkSide.Student(student))
              .getOrThrow()
              .single()
              .state,
          )
        }
      }
    }
  }

  @Test
  fun `the student sees requests from live, verified claimants only, oldest first`() {
    val student = createStudent()
    val first = assertNotNull(request(createSupporter(createUser(name = "Ana Parent")), student))
    val second = assertNotNull(request(createSupporter(createUser(name = "Ben Parent")), student))
    request(createSupporter(createUser(name = "Unverified Stranger", verified = false)), student)
    val closedUser = createUser(name = "Closed Parent")
    request(createSupporter(closedUser), student)
    UsersDao.delete(session, closedUser, UsersDao.findById(session, closedUser).getOrThrow().version).getOrThrow()
    request(createSupporter(), createStudent())

    val visible = SupporterLinkRequestsDao.listPendingVisibleToStudent(session, student).getOrThrow()

    assertEquals(listOf(first.id, second.id), visible.map { it.requestId })
    assertEquals(listOf("Ana Parent", "Ben Parent"), visible.map { it.name.value })
    assertTrue(visible.all { it.email.value.startsWith("request-") }, "each carries the claimant's own email")
  }

  @Test
  fun `the one-row read the doors act on hides exactly what the list hides`() {
    val student = createStudent()
    val visible = assertNotNull(request(createSupporter(), student))
    val unverified = assertNotNull(request(createSupporter(createUser(verified = false)), student))

    assertNotNull(SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate(session, student, visible.id).getOrThrow())
    assertNull(SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate(session, student, unverified.id).getOrThrow())
    assertNull(SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate(session, createStudent(), visible.id).getOrThrow())
  }

  @Test
  fun `cancelling one side's pending requests leaves resolved rows and the other side alone`() {
    val student = createStudent()
    val supporter = createSupporter()
    val pending = assertNotNull(request(supporter, student))
    val otherStudent = createStudent()
    val declined = assertNotNull(request(supporter, otherStudent))
    SupporterLinkRequestsDao.decline(session, declined.id, otherStudent).getOrThrow()
    val elsewhere = assertNotNull(request(createSupporter(), otherStudent))

    val cancelled = SupporterLinkRequestsDao.cancelAllPendingFor(session, LinkSide.Supporter(supporter.id)).getOrThrow()

    assertEquals(listOf(pending.id), cancelled.map { it.id })
    assertIs<SupporterLinkRequestState.Cancelled>(cancelled.single().state)
    assertEquals(1, SupporterLinkRequestsDao.countPendingForSupporter(session, elsewhere.supporterId).getOrThrow())
    assertEquals(0, SupporterLinkRequestsDao.countPendingForSupporter(session, supporter.id).getOrThrow())
  }

  @Test
  fun `turning a report link off cancels the pending requests made from it and nothing else`() {
    val student = createStudent()
    val share = shareOf(student)
    val first = assertNotNull(request(createSupporter(), student))
    val second = assertNotNull(request(createSupporter(), student))
    val declined = assertNotNull(request(createSupporter(), student))
    SupporterLinkRequestsDao.decline(session, declined.id, student).getOrThrow()
    val otherStudent = createStudent()
    val elsewhere = assertNotNull(request(createSupporter(), otherStudent))

    val cancelled = SupporterLinkRequestsDao.cancelAllPendingFromShare(session, share).getOrThrow()

    assertEquals(setOf(first.id, second.id), cancelled.map { it.id }.toSet())
    assertTrue(cancelled.all { it.state is SupporterLinkRequestState.Cancelled })
    assertEquals(
      1,
      SupporterLinkRequestsDao.countPendingForSupporter(session, elsewhere.supporterId).getOrThrow(),
      "another link's request stays",
    )
  }

  @Test
  fun `a notice is decided once`() {
    val supporter = createSupporter()
    val pending = assertNotNull(request(supporter, createStudent()))

    assertEquals(listOf(pending.id), SupporterLinkRequestsDao.listUnnotifiedForSupporter(session, supporter.id).getOrThrow().map { it.id })
    assertNotNull(SupporterLinkRequestsDao.markNotified(session, pending.id).getOrThrow())
    assertNull(SupporterLinkRequestsDao.markNotified(session, pending.id).getOrThrow(), "a second mark finds nothing to decide")
    assertEquals(emptyList(), SupporterLinkRequestsDao.listUnnotifiedForSupporter(session, supporter.id).getOrThrow())
  }
}
