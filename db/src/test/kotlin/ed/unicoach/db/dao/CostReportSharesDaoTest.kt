package ed.unicoach.db.dao

import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-DB coverage for the Family Cost Report share credential (RFC 155),
 * modelled on [VerificationTokensDaoTest]: one connection opened in @BeforeAll,
 * a truncate before each test, and raw statements for the fixture rows.
 */
class CostReportSharesDaoTest {
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
      stmt.execute("TRUNCATE TABLE cost_report_shares, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'CRS User', 'ahash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "crs-$userId@test.com")
        stmt.executeUpdate()
      }
    connection
      .prepareStatement(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES (?, ?, 2028)",
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, userId)
        stmt.executeUpdate()
      }
    return StudentId(studentId)
  }

  private fun hashOf(raw: String): TokenHash = TokenHash.fromRawToken(raw)

  /**
   * A fresh row id from the DAO's own generator. The share token is derived from
   * the id (RFC 155), so the id is minted before the row rather than by the
   * column default.
   */
  private fun newId(): CostReportShareId = CostReportSharesDao.nextId(session).getOrThrow()

  private fun countShares(studentId: StudentId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM cost_report_shares WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `create inserts a live row for the student`() {
    val student = createStudent()

    val share =
      CostReportSharesDao
        .create(session, NewCostReportShare(newId(), student, hashOf("raw-1")))
        .getOrThrow()

    assertEquals(student, share.studentId)
    assertNull(share.revokedAt, "A freshly minted share must be live")
    assertNotNull(share.createdAt)
  }

  @Test
  fun `findLiveByTokenHash resolves the presented hash to its share`() {
    val student = createStudent()
    val hash = hashOf("resolve-me")
    val created = CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hash)).getOrThrow()

    val found = assertNotNull(CostReportSharesDao.findLiveByTokenHash(session, hash).getOrThrow())

    assertEquals(created.id, found.id)
    assertEquals(student, found.studentId)
  }

  @Test
  fun `findLiveByTokenHash misses a revoked token`() {
    val student = createStudent()
    val hash = hashOf("revoke-me")
    CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hash)).getOrThrow()
    CostReportSharesDao.revokeLive(session, student).getOrThrow()

    val result = CostReportSharesDao.findLiveByTokenHash(session, hash)

    // Null, not a failure: a revoked token is an ABSENCE, and the type says so.
    assertNull(result.getOrThrow(), "A revoked token must be as invisible as an unknown one, got [$result]")
  }

  @Test
  fun `findLiveByTokenHash misses an unknown hash`() {
    createStudent()

    val result = CostReportSharesDao.findLiveByTokenHash(session, hashOf("never-inserted"))

    assertNull(result.getOrThrow(), "an unknown hash resolves to nothing, got [$result]")
  }

  @Test
  fun `findLiveByStudent returns the live share and null before the first mint`() {
    val student = createStudent()

    // A student who has never shared is the ordinary state of every student, so
    // it is an absence rather than a failed read.
    assertNull(CostReportSharesDao.findLiveByStudent(session, student).getOrThrow())

    val created = CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hashOf("mine"))).getOrThrow()
    val found = assertNotNull(CostReportSharesDao.findLiveByStudent(session, student).getOrThrow())

    assertEquals(created.id, found.id)
  }

  @Test
  fun `the one-live-per-student index rejects a second live row`() {
    val student = createStudent()
    CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hashOf("first"))).getOrThrow()

    val second = CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hashOf("second")))

    assertTrue(second.isFailure, "A second live share for the same student must violate the partial unique index")
    // The `23505` shape every other write here uses, rather than the generic
    // DatabaseException: the mint path answers a lost one-live-share race by
    // re-reading, and it must be able to tell that outcome apart from a fault
    // without importing SQLSTATE strings.
    assertTrue(second.exceptionOrNull() is ConstraintViolationException, "Expected ConstraintViolationException, got [$second]")
    assertEquals(1, countShares(student), "The rejected insert must leave one row")
  }

  @Test
  fun `revokeLive is a compare-and-swap that returns the row once`() {
    val student = createStudent()
    val created = CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hashOf("cas"))).getOrThrow()

    val first = assertNotNull(CostReportSharesDao.revokeLive(session, student).getOrThrow())
    assertEquals(created.id, first.id)
    assertNotNull(first.revokedAt, "Revoke must stamp revoked_at")

    val second = CostReportSharesDao.revokeLive(session, student)
    assertNull(second.getOrThrow(), "A second revoke finds nothing live and is not an error, got [$second]")
  }

  @Test
  fun `a new live share can be created after revoking the previous one`() {
    val student = createStudent()
    val oldHash = hashOf("old-link")
    CostReportSharesDao.create(session, NewCostReportShare(newId(), student, oldHash)).getOrThrow()
    CostReportSharesDao.revokeLive(session, student).getOrThrow()

    val newHash = hashOf("new-link")
    val minted = CostReportSharesDao.create(session, NewCostReportShare(newId(), student, newHash)).getOrThrow()

    assertNull(minted.revokedAt)
    assertEquals(minted.id, CostReportSharesDao.findLiveByStudent(session, student).getOrThrow()?.id)
    assertEquals(minted.id, CostReportSharesDao.findLiveByTokenHash(session, newHash).getOrThrow()?.id)
    assertNull(CostReportSharesDao.findLiveByTokenHash(session, oldHash).getOrThrow(), "The old link stays dead forever")
    assertEquals(2, countShares(student), "The revoked row is kept, not replaced")
  }

  /**
   * The `ON DELETE CASCADE` on `student_id` (RFC 155): a deleted student's share
   * links must die with them.
   *
   * Asserted on the CONSTRAINT rather than by deleting a student, because no
   * student row can be physically deleted today — `students` carries
   * `trigger_00_prevent_students_physical_delete`, and its `students_versions`
   * history holds a RESTRICT foreign key on top of that. The clause is landed
   * now so the account-deletion path (brief 0002, parked) owes this table
   * nothing when it is built, and this test is what keeps the clause from being
   * quietly dropped in the meantime.
   */
  @Test
  fun `the student foreign key is declared ON DELETE CASCADE`() {
    val sql =
      """
      SELECT confdeltype
      FROM pg_constraint
      WHERE conrelid = 'cost_report_shares'::regclass
        AND confrelid = 'students'::regclass
      """.trimIndent()
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "the share table must carry a foreign key to students")
        assertEquals("c", rs.getString("confdeltype"), "the student foreign key must be ON DELETE CASCADE")
        assertFalse(rs.next(), "exactly one foreign key to students")
      }
    }
  }

  /**
   * The cascade itself, executed once rather than read off the catalog: the
   * clause above says what the DDL declares, this says what actually happens to
   * the row. A cascade can be defeated by something the `confdeltype` letter
   * cannot see — a future rule or trigger, or a RESTRICT edge reached first —
   * and a parent must not keep a working link to a student who no longer
   * exists.
   *
   * Physically deleting a student is impossible in production
   * (`trigger_00_prevent_students_physical_delete`, plus the RESTRICT from
   * `students_versions`), so the delete runs inside a transaction that disables
   * the trigger, clears the history rows, and is then ROLLED BACK — the
   * rollback takes the trigger back with it, because PostgreSQL's DDL is
   * transactional. Nothing this test touches outlives it.
   */
  @Test
  fun `deleting a student takes the share row with it`() {
    val student = createStudent()
    CostReportSharesDao.create(session, NewCostReportShare(newId(), student, hashOf("cascade"))).getOrThrow()
    assertEquals(1, countShares(student), "the fixture must be live before the delete")

    connection.autoCommit = false
    try {
      connection.createStatement().use {
        it.execute("ALTER TABLE students DISABLE TRIGGER trigger_00_prevent_students_physical_delete")
      }
      connection.prepareStatement("DELETE FROM students_versions WHERE id = ?").use { stmt ->
        stmt.setObject(1, student.value)
        stmt.executeUpdate()
      }
      connection.prepareStatement("DELETE FROM students WHERE id = ?").use { stmt ->
        stmt.setObject(1, student.value)
        assertEquals(1, stmt.executeUpdate(), "the student row must actually be deleted")
      }

      assertEquals(0, countShares(student), "a deleted student's share link must die with them")
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }
}
