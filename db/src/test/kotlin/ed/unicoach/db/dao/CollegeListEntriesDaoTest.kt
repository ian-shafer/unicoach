package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeListEntriesDaoTest {
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
      stmt.execute("TRUNCATE TABLE college_list_entries, students, users, colleges CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var ipedsUnitIdCounter = 900000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cle-$userId@test.com', 'CLE User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createCollege(): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          ipedsUnitId = ipedsUnitIdCounter++,
          opeid = null,
          name = "Test College",
          city = "Townsville",
          state = "CA",
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollmentHeadcount = 5000,
          admissionRateShare = 0.5,
          satAverageEquivalentScore = 1200,
          costOfAttendancePerYearUsd = 40000,
          netPricePerYearUsd = 20000,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = 12000,
          tuitionAndFeesOutOfStatePerYearUsd = 30000,
          completionRate150pct4yrShare = 0.7,
          medianEarnings10yAfterEntryUsd = 55000,
          medianDebtAtCompletionUsd = null,
          pellShare = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun newEntry(
    studentId: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
    reasons: String? = null,
  ): NewCollegeListEntry = NewCollegeListEntry(studentId, collegeId, status, reasons)

  private fun countVersions(id: CollegeListEntryId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM college_list_entries_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `create persists all columns with default status considering`() {
    val student = createStudent()
    val college = createCollege()

    val entry =
      CollegeListEntriesDao
        .create(session, NewCollegeListEntry(student, college, CollegeListEntryStatus.CONSIDERING, null))
        .getOrThrow()

    assertEquals(student, entry.studentId)
    assertEquals(college, entry.collegeId)
    assertEquals(CollegeListEntryStatus.CONSIDERING, entry.status)
    assertNull(entry.reasons)
    assertEquals(1, entry.version)
    assertNull(entry.deletedAt)
  }

  @Test
  fun `create with an unknown college_id raises NotFoundException College not found`() {
    val student = createStudent()
    val result = CollegeListEntriesDao.create(session, newEntry(student, CollegeId(UUID.randomUUID())))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message == "College not found", "got $error")
  }

  @Test
  fun `create with an unknown student_id raises NotFoundException Owning student not found`() {
    val college = createCollege()
    val result = CollegeListEntriesDao.create(session, newEntry(StudentId(UUID.randomUUID()), college))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message == "Owning student not found", "got $error")
  }

  @Test
  fun `create duplicate active student_id college_id raises ConstraintViolationException`() {
    val student = createStudent()
    val college = createCollege()
    CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val second = CollegeListEntriesDao.create(session, newEntry(student, college))
    assertTrue(second.exceptionOrNull() is ConstraintViolationException, "got ${second.exceptionOrNull()}")
  }

  @Test
  fun `create for the same student_id college_id after soft-delete succeeds as a new row`() {
    val student = createStudent()
    val college = createCollege()
    val first = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    CollegeListEntriesDao.delete(session, first.id, first.version).getOrThrow()

    val second = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    assertTrue(second.id != first.id, "the re-added entry must be a new row")
  }

  @Test
  fun `findById respects SoftDeleteScope`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    assertTrue(CollegeListEntriesDao.findById(session, entry.id, SoftDeleteScope.ACTIVE).isSuccess)
    assertTrue(CollegeListEntriesDao.findById(session, entry.id, SoftDeleteScope.DELETED).isFailure)

    val deleted = CollegeListEntriesDao.delete(session, entry.id, entry.version).getOrThrow()

    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.ACTIVE).isFailure)
    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.DELETED).isSuccess)
    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.ALL).isSuccess)
  }

  @Test
  fun `findByIdAndStudent returns NotFoundException for a wrong-owner id`() {
    val student = createStudent()
    val otherStudent = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val result = CollegeListEntriesDao.findByIdAndStudent(session, entry.id, otherStudent)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")

    assertTrue(CollegeListEntriesDao.findByIdAndStudent(session, entry.id, student).isSuccess)
  }

  @Test
  fun `update bumps version and rejects a stale currentVersion`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val updated =
      CollegeListEntriesDao
        .update(session, CollegeListEntryEdit(entry.id, entry.version, CollegeListEntryStatus.APPLYING, "Great fit"))
        .getOrThrow()
    assertEquals(2, updated.version)
    assertEquals(CollegeListEntryStatus.APPLYING, updated.status)
    assertEquals("Great fit", updated.reasons)

    val stale =
      CollegeListEntriesDao.update(session, CollegeListEntryEdit(entry.id, entry.version, CollegeListEntryStatus.ADMITTED, null))
    assertTrue(stale.exceptionOrNull() is ConcurrentModificationException, "got ${stale.exceptionOrNull()}")
  }

  @Test
  fun `listActiveByStudent excludes soft-deleted rows and orders created_at id`() {
    val student = createStudent()
    val c1 = createCollege()
    val c2 = createCollege()
    val c3 = createCollege()
    val e1 = CollegeListEntriesDao.create(session, newEntry(student, c1)).getOrThrow()
    val e2 = CollegeListEntriesDao.create(session, newEntry(student, c2)).getOrThrow()
    val e3 = CollegeListEntriesDao.create(session, newEntry(student, c3)).getOrThrow()
    CollegeListEntriesDao.delete(session, e2.id, e2.version).getOrThrow()

    val active = CollegeListEntriesDao.listActiveByStudent(session, student).getOrThrow()
    assertEquals(listOf(e1.id, e3.id), active.map { it.id })
  }

  @Test
  fun `delete undelete round-trip via OccDeletable`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val deleted = CollegeListEntriesDao.delete(session, entry.id, entry.version).getOrThrow()
    assertNotNull(deleted.deletedAt)
    assertEquals(2, deleted.version)

    val restored = CollegeListEntriesDao.undelete(session, deleted.id, deleted.version).getOrThrow()
    assertNull(restored.deletedAt)
    assertEquals(3, restored.version)
  }

  @Test
  fun `listVersions returns ascending version history after multiple updates`() {
    val student = createStudent()
    val college = createCollege()
    val v1 = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    val v2 =
      CollegeListEntriesDao
        .update(session, CollegeListEntryEdit(v1.id, v1.version, CollegeListEntryStatus.APPLYING, "a"))
        .getOrThrow()
    CollegeListEntriesDao.update(session, CollegeListEntryEdit(v2.id, v2.version, CollegeListEntryStatus.ADMITTED, "b")).getOrThrow()

    val versions = CollegeListEntriesDao.listVersions(session, v1.id).getOrThrow()
    assertEquals(listOf(1, 2, 3), versions.map { it.version })
    assertEquals(CollegeListEntryStatus.CONSIDERING, versions[0].entity.status)
    assertEquals(CollegeListEntryStatus.APPLYING, versions[1].entity.status)
    assertEquals(CollegeListEntryStatus.ADMITTED, versions[2].entity.status)
    assertEquals(2, countVersions(v1.id) - 1)
  }

  @Test
  fun `reasons length 2049 chars is rejected by the DB CHECK`() {
    val student = createStudent()
    val college = createCollege()
    val tooLong = "a".repeat(2049)
    val result = CollegeListEntriesDao.create(session, newEntry(student, college, reasons = tooLong))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `reasons empty string or all-whitespace is rejected but NULL succeeds`() {
    val student = createStudent()
    val college1 = createCollege()
    val college2 = createCollege()
    val college3 = createCollege()

    val empty = CollegeListEntriesDao.create(session, newEntry(student, college1, reasons = ""))
    assertTrue(empty.exceptionOrNull() is ConstraintViolationException, "got ${empty.exceptionOrNull()}")

    val whitespace = CollegeListEntriesDao.create(session, newEntry(student, college2, reasons = "   "))
    assertTrue(whitespace.exceptionOrNull() is ConstraintViolationException, "got ${whitespace.exceptionOrNull()}")

    val nullReasons = CollegeListEntriesDao.create(session, newEntry(student, college3, reasons = null))
    assertTrue(nullReasons.isSuccess)
  }
}
